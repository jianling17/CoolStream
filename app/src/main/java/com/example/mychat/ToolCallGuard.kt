package com.example.mychat

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

/** Agent 单步内模型请求执行的一次工具调用。 */
data class ToolCallRequest(
    val name: String,
    val argumentsJson: String
)

/** 拦截决策。 */
sealed interface GuardDecision {
    /** 放行，正常执行工具。 */
    object Proceed : GuardDecision

    /** 检测到重复：把 [hint] 作为工具结果回灌上下文，逼模型换策略（软熔断）。 */
    data class Interrupt(
        val hint: String,
        val repeatTimes: Int,
        val fingerprint: String
    ) : GuardDecision

    /** 连续重复到硬阈值：直接抛错熔断，不再浪费 Token（硬熔断）。 */
    data class Abort(
        val toolName: String,
        val repeatTimes: Int,
        val fingerprint: String
    ) : GuardDecision
}

class RepeatedToolCallException(
    val toolName: String,
    val repeatTimes: Int,
    val fingerprint: String
) : IllegalStateException(
    "工具 '$toolName' 在最近窗口内以相同参数重复调用 $repeatTimes 次（fingerprint=$fingerprint），" +
        "已熔断以避免 Token 空转。请检查模型是否陷入幻觉循环。"
)

/**
 * 重复工具调用拦截器。
 *
 * 原理：对「工具名 + 规范化参数」做 MD5 指纹，维护长度为 [windowSize] 的滑动窗口。
 * 窗口内相同指纹的累计出现次数 [nextTimes]：
 * - [nextTimes] < [softLimit]：放行；
 * - [softLimit] <= [nextTimes] < [hardLimit]：软熔断，回灌提示；
 * - [nextTimes] >= [hardLimit]：硬熔断，抛 [RepeatedToolCallException]。
 *
 * 默认 (windowSize=3, softLimit=2, hardLimit=3)：同一调用第 2 次注入提示、
 * 第 3 次熔断，正好覆盖"同一 read_file 同参连调 3 次"的场景。
 *
 * 线程安全：内部 `synchronized` 保护窗口，可用于多协程并发调度。
 * 说明：MD5 在这里只用于去重指纹，不承担任何安全职能（抗碰撞不是需求）。
 */
class ToolCallGuard(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val softLimit: Int = DEFAULT_SOFT_LIMIT,
    private val hardLimit: Int = DEFAULT_HARD_LIMIT
) {
    init {
        require(windowSize >= 1) { "windowSize 必须 >= 1" }
        require(softLimit in 2..hardLimit) { "必须满足 2 <= softLimit <= hardLimit" }
    }

    private val lock = Any()
    private val window = ArrayDeque<String>(windowSize)

    /** 检查一次即将执行的调用，返回放行 / 软熔断 / 硬熔断。 */
    fun inspect(call: ToolCallRequest): GuardDecision {
        val fingerprint = fingerprintOf(call)
        synchronized(lock) {
            val priorHits = window.count { it == fingerprint }
            val nextTimes = priorHits + 1

            window.addLast(fingerprint)
            while (window.size > windowSize) window.removeFirst()

            return when {
                nextTimes >= hardLimit ->
                    GuardDecision.Abort(call.name, nextTimes, fingerprint)

                nextTimes >= softLimit ->
                    GuardDecision.Interrupt(buildHint(call, nextTimes), nextTimes, fingerprint)

                else -> GuardDecision.Proceed
            }
        }
    }

    /** 每一轮新的用户输入 / 新会话开始前调用，清空历史。 */
    fun reset() {
        synchronized(lock) { window.clear() }
    }

    fun fingerprintOf(call: ToolCallRequest): String =
        md5(call.name + FIELD_SEPARATOR + Canonicalizer.canonicalize(call.argumentsJson))

    private fun buildHint(call: ToolCallRequest, repeatTimes: Int): String =
        "【重复调用拦截】你已经用完全相同的参数调用过工具 `${call.name}`，本次是最近窗口内的第 " +
            "$repeatTimes 次（本次未被执行）。请立即停止重复同样的调用：要么改用不同参数，" +
            "要么换一条思路，要么直接基于已有信息给出最终回答。"

    companion object {
        const val DEFAULT_WINDOW_SIZE = 3
        const val DEFAULT_SOFT_LIMIT = 2
        const val DEFAULT_HARD_LIMIT = 3

        private const val FIELD_SEPARATOR = '\u0000'
        private val HEX = "0123456789abcdef".toCharArray()

        fun md5(input: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }
    }
}

/**
 * 参数规范化：把 JSON 递归排序 key 后序列化，消除键序 / 空白对指纹的干扰。
 * 例：{"b":1,"a":2} 与 {"a":2,"b":1} 归一后指纹一致，视为同一次调用。
 * 数组保持原序（顺序通常有语义）。非 JSON 输入退化为去空白。
 */
private object Canonicalizer {

    fun canonicalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "{}"
        return try {
            render(JSONObject(trimmed))
        } catch (e: JSONException) {
            try {
                render(JSONArray(trimmed))
            } catch (e2: JSONException) {
                trimmed.replace(WHITESPACE, "")
            }
        }
    }

    private fun render(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted()
            .joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                JSONObject.quote(key) + ":" + render(value.get(key))
            }
        is JSONArray -> (0 until value.length())
            .joinToString(prefix = "[", postfix = "]", separator = ",") { i -> render(value.get(i)) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private val WHITESPACE = Regex("\\s+")
}

/** Agent 对话消息（可见的最小抽象，接入时替换成你自己的消息模型）。 */
data class AgentMessage(
    val role: String,
    val content: String
)

/** 模型单步决策：返回想调用的工具，null 表示模型已给出最终回答。 */
interface AgentModelClient {
    suspend fun nextAction(messages: MutableList<AgentMessage>): ToolCallRequest?
}

/** 真正执行工具。 */
interface ToolExecutor {
    suspend fun execute(call: ToolCallRequest): String
}

/**
 * 与 runAgentLoop 集成的示例骨架：最多 [maxRounds] 轮（默认 8）。
 *
 * 集成约定：
 * - 每轮先问模型要 tool call；
 * - 调 [ToolCallGuard.inspect] 决定放行 / 软熔断 / 硬熔断；
 * - 软熔断时**不执行**工具，而是把 [GuardDecision.Interrupt.hint] 作为该 tool call 的
 *   结果回灌（OpenAI 风格 API 下务必绑定到同一个 tool_call_id，否则模型可能报错）；
 * - 硬熔断时抛 [RepeatedToolCallException] 交给上层收尾。
 */
class AgentLoopRunner(
    private val model: AgentModelClient,
    private val tools: ToolExecutor,
    private val guard: ToolCallGuard = ToolCallGuard(),
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS
) {

    suspend fun run(initial: List<AgentMessage>): List<AgentMessage> {
        val messages = initial.toMutableList()
        guard.reset()

        var round = 0
        while (round < maxRounds) {
            round++

            val call = model.nextAction(messages) ?: break // 无工具调用 → 收尾

            when (val decision = guard.inspect(call)) {
                is GuardDecision.Proceed -> {
                    messages += AgentMessage(role = "tool", content = tools.execute(call))
                }

                is GuardDecision.Interrupt -> {
                    // 不执行工具，直接回灌打断提示（请绑定 tool_call_id）
                    messages += AgentMessage(role = "tool", content = decision.hint)
                }

                is GuardDecision.Abort -> throw RepeatedToolCallException(
                    toolName = decision.toolName,
                    repeatTimes = decision.repeatTimes,
                    fingerprint = decision.fingerprint
                )
            }
        }
        return messages
    }

    companion object {
        const val DEFAULT_MAX_ROUNDS = 8
    }
}