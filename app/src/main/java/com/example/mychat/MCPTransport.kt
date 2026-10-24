package com.example.mychat

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.3.0 MCP 客户端长连接重构
 *
 * 替换原来的短连接（每次调用重新握手）为标准 SSE 长连接：
 * - 缓存 Mcp-Session-Id，initialize 只握手一次，后续请求复用会话
 * - 断线自动重连（指数退避：1s -> 2s -> 4s -> 8s -> 16s -> 30s 封顶）
 * - SSE 流式响应正确解析（多事件、event/data/id 行、多行 data 拼接）
 * - OkHttp 连接池复用 TCP 连接，降低外部工具调用延迟
 *
 * 全部阻塞式实现，调用方必须包在 Dispatchers.IO 里（铁律：绝不阻塞主线程）。
 */
internal object MCPTransport {
    private const val TAG = "MCPTransport"

    /** 单次 MCP 请求超时（毫秒） */
    internal const val TIMEOUT_MS = 15_000L

    /** initialize 请求里上报的协议版本 */
    internal const val PROTOCOL_VERSION = "2025-03-26"

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    @Volatile
    private var state: State = State.DISCONNECTED

    /** 当前会话 id：initialize 成功后从响应头取，之后每个请求回传 */
    @Volatile
    internal var sessionId: String? = null
        private set

    /** 是否已完成 initialize 握手 */
    @Volatile
    private var initialized: Boolean = false

    /** JSON-RPC id 自增器（App 进程内唯一即可） */
    private val rpcIdCounter = AtomicInteger(0)

    // ---- 指数退避参数 ----
    private const val BASE_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 30_000L
    @Volatile
    private var backoffMs: Long = BASE_BACKOFF_MS

    /** 单次请求最大重连尝试次数 */
    private const val MAX_RETRIES = 3

    /** MCP 专用 OkHttp：连接池复用 TCP，超时和主链路分开 */
    internal val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // 连接池：保持 5 个空闲连接 5 分钟，复用 TCP 降低握手开销
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 重置连接状态（服务器地址变更时调用）。
     */
    @Synchronized
    internal fun reset() {
        sessionId = null
        initialized = false
        state = State.DISCONNECTED
        backoffMs = BASE_BACKOFF_MS
        lastToolCount = 0
        Log.d(TAG, "连接状态已重置")
    }

    /** UI 只读：当前是否已建立会话（无需网络请求） */
    internal fun peekConnected(): Boolean = initialized && sessionId != null && state == State.CONNECTED

    /** UI 只读：上次成功拉取的工具数（无需网络请求） */
    internal fun peekToolCount(): Int = lastToolCount

    /** 供 ToolExecutor 在 mcpListTools 成功后回写工具计数 */
    internal fun reportToolCount(count: Int) {
        lastToolCount = count
    }

    /**
     * 确保已建立 MCP 会话。
     * 已初始化且 sessionId 仍有效 -> 直接返回（会话复用）。
     * 未初始化 -> 执行 initialize 握手 + notifications/initialized。
     */
    /** 上次成功拉取的工具数量（供 UI 侧边栏显示，无需网络请求） */
    @Volatile
    internal var lastToolCount: Int = 0
        private set

    /** 上次成功连接时的服务器地址，变更时自动重置会话 */
    @Volatile
    private var connectedUrl: String = ""

    @Synchronized
    internal fun ensureConnected() {
        // 服务器地址变更 -> 旧会话失效，必须重新握手
        if (mcpServerUrl != connectedUrl) {
            sessionId = null
            initialized = false
            state = State.DISCONNECTED
            connectedUrl = mcpServerUrl
        }
        if (initialized && sessionId != null && state == State.CONNECTED) return
        if (mcpServerUrl.isBlank()) {
            throw IllegalStateException("未配置 MCP 服务器地址")
        }

        state = State.CONNECTING
        try {
            doInitialize()
            state = State.CONNECTED
            initialized = true
            resetBackoff()
            Log.d(TAG, "MCP 会话已建立, sessionId=" + sessionId)
        } catch (e: Exception) {
            state = State.DISCONNECTED
            throw e
        }
    }

    /**
     * 对外 RPC 入口：带自动重连。
     * 连接失败时按指数退避重连（最多 MAX_RETRIES 次），重连成功后重试请求。
     */
    internal fun rpcCall(method: String, params: JSONObject): JSONObject {
        ensureConnected()

        var lastError: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                return doRpcCall(method, params)
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_RETRIES && isReconnectable(e)) {
                    state = State.RECONNECTING
                    val delay = nextBackoffDelay()
                    Log.w(TAG, "MCP 请求失败(" + (attempt + 1) + "/" + MAX_RETRIES + "), " + delay + "ms 后重连: " + e.message)
                    Thread.sleep(delay)
                    // 强制重新握手
                    initialized = false
                    sessionId = null
                    try {
                        ensureConnected()
                    } catch (re: Exception) {
                        Log.w(TAG, "重连失败: " + re.message)
                        // 继续下一次重试
                    }
                } else {
                    break
                }
            }
        }
        throw lastError ?: IllegalStateException("MCP 请求失败：未知错误")
    }

    // ==================== 内部实现 ====================

    private fun nextId(): Int = rpcIdCounter.incrementAndGet()

    private fun resetBackoff() {
        backoffMs = BASE_BACKOFF_MS
    }

    private fun nextBackoffDelay(): Long {
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        return delay
    }

    /** 判断异常是否值得重连（网络层故障），非网络故障不重连 */
    private fun isReconnectable(e: Exception): Boolean {
        return e is IOException ||
            e is SocketTimeoutException ||
            e is UnknownHostException ||
            e.message?.contains("HTTP 5", ignoreCase = true) == true ||
            e.message?.contains("connect", ignoreCase = true) == true ||
            e.message?.contains("reset", ignoreCase = true) == true ||
            e.message?.contains("broken", ignoreCase = true) == true ||
            e.message?.contains("closed", ignoreCase = true) == true
    }

    /**
     * initialize 握手 + notifications/initialized 通知。
     * 握手成功后缓存 Mcp-Session-Id。
     */
    private fun doInitialize() {
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "CoolStream").put("version", "1.3"))
        doRpcCall("initialize", params)

        // notifications/initialized（部分 MCP 实现要求此通知才视为握手完成）
        runCatching {
            val notify = JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized")
            val req = Request.Builder()
                .url(mcpServerUrl.trim())
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json, text/event-stream")
                .addHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .post(notify.toString().toRequestBody(JSON_MEDIA_TYPE))
            sessionId?.let { req.addHeader("Mcp-Session-Id", it) }
            client.newCall(req.build()).execute().close()
        }
    }

    /**
     * 单次 RPC 调用（无重试）：组 JSON-RPC body -> POST -> 校验 HTTP -> 解析响应。
     * 支持 application/json 和 text/event-stream 两种响应格式。
     */
    private fun doRpcCall(method: String, params: JSONObject): JSONObject {
        val url = mcpServerUrl.trim()
        if (url.isBlank()) throw IllegalStateException("未配置 MCP 服务器地址")

        val id = nextId()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()

        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .addHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        sessionId?.let { builder.addHeader("Mcp-Session-Id", it) }

        client.newCall(builder.build()).execute().use { response ->
            // initialize 的会话 id 在响应头里，先记下再读 body
            response.header("Mcp-Session-Id")?.let { sessionId = it }

            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("MCP 服务器返回 HTTP " + response.code + "：" + raw.take(200))
            }

            // 解析响应：可能是纯 JSON，也可能是 SSE 流
            val payload = parseResponse(raw, id)

            val obj = try { JSONObject(payload) } catch (e: Exception) {
                throw IllegalStateException("MCP 响应不是合法 JSON：" + payload.take(120))
            }
            // 带 error 节点 = 服务器侧拒绝
            if (obj.has("error")) {
                val err = obj.optJSONObject("error")
                throw IllegalStateException(
                    "MCP 错误 " + (err?.optInt("code") ?: -1) + "：" + (err?.optString("message") ?: "未知")
                )
            }
            val result = obj.optJSONObject("result")
                ?: throw IllegalStateException("MCP 响应缺少 result 节点：" + payload.take(120))
            if (result.optBoolean("isError", false)) {
                throw IllegalStateException("MCP 工具执行失败：" + result.optString("content"))
            }
            return result
        }
    }

    /**
     * 解析 MCP 响应：纯 JSON 直接返回；SSE 流按事件解析后匹配 JSON-RPC id。
     *
     * SSE 格式：
     *   event: message
     *   data: {"jsonrpc":"2.0","id":1,"result":{...}}
     *
     * 多行 data 用 \n 拼接；多个事件按 id 匹配；无 id 匹配时取最后一条 data。
     */
    private fun parseResponse(raw: String, expectedId: Int): String {
        // 非 SSE：直接是 JSON
        if (!raw.contains("data:")) return raw

        var lastData: String = ""
        var currentData = StringBuilder()
        var inData = false

        for (line in raw.lineSequence()) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("data:") -> {
                    if (inData && currentData.isNotEmpty()) {
                        // 前一个 data 块结束（无空行分隔的连续 data）
                        val dataStr = currentData.toString()
                        if (matchesId(dataStr, expectedId)) return dataStr
                        lastData = dataStr
                    }
                    currentData = StringBuilder(trimmed.removePrefix("data:").trim())
                    inData = true
                }
                trimmed.isEmpty() -> {
                    // 空行 = 事件分隔符
                    if (inData && currentData.isNotEmpty()) {
                        val dataStr = currentData.toString()
                        if (matchesId(dataStr, expectedId)) return dataStr
                        lastData = dataStr
                        currentData = StringBuilder()
                    }
                    inData = false
                }
                inData -> {
                    // 多行 data：续行拼接
                    currentData.append("\n").append(line)
                }
            }
        }
        // 收尾：最后一个事件可能没有空行结束
        if (inData && currentData.isNotEmpty()) {
            val dataStr = currentData.toString()
            if (matchesId(dataStr, expectedId)) return dataStr
            lastData = dataStr
        }

        if (lastData.isBlank()) {
            throw IllegalStateException("MCP 响应是 SSE 但没有 data 行")
        }
        return lastData
    }

    /** 检查 JSON data 的 id 是否匹配预期 */
    private fun matchesId(data: String, expectedId: Int): Boolean {
        return try {
            JSONObject(data).optInt("id", -1) == expectedId
        } catch (e: Exception) {
            false
        }
    }
}
