package com.example.mychat

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.mychat.ui.theme.MyChatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit


// ============================================================
// ★★★ 配置区 ★★★
// ============================================================

/** DeepSeek 接口地址。必须写全，包含 /chat/completions */
private const val API_URL = "https://api.deepseek.com/chat/completions"

/** 模型名。当前在售：deepseek-flash / deepseek-v4-pro */
private const val MODEL_NAME = "deepseek-flash"

/** 系统提示词。不想用就改成 "" */
private const val SYSTEM_PROMPT = "你是一位专业、严谨、条理清晰的助手。回答尽量简洁，分点说明。"

/** 日志标签，Logcat 里用 tag:MyChatPerf 过滤 */
private const val TAG = "MyChatPerf"

/**
 * ★★★ 核心参数：UI 刷新间隔（毫秒）★★★
 *
 * AI 每秒可能吐 30~60 个分片。每个分片都刷界面的话，CPU 会被
 * "重新排版 + 重绘"占满，手机发烫。
 *
 * 50ms = 每秒最多刷 20 次。人眼在 60~100ms 内感觉不出卡，
 * 这是"看起来流畅"和"省电"之间的甜点。
 *
 * 想验证批处理有没有用：改成 0L 再跑一次，对比日志里的 flush 次数。
 */
private const val FLUSH_INTERVAL_MS = 50L

/**
 * ★ 多轮上下文：最多携带多少条历史消息
 *
 * 每次请求都会把之前的对话一起发过去，AI 才能"记得"上文。
 * 但对话太长会超出模型的上下文窗口（而且更贵），所以要设个上限。
 * 40 条 ≈ 20 轮问答。
 *
 * 想体验"AI 忘记上文"是什么感觉，把它改成 1 试试。
 */
private const val MAX_HISTORY_MESSAGES = 40


// ============================================================
// 全局唯一的 OkHttp 客户端
//
// 每个 OkHttpClient 自带连接池 + 线程池，反复新建 = 反复创建销毁线程，
// 本身就是发热来源。整个 App 共用一个就够。
// ============================================================
private val httpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()


// ============================================================
// 1. 数据模型
// ============================================================
data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    // 是不是"错误提示"气泡。
    // 错误提示只是为了让人在界面上看得见，它并不是 AI 的真实回复，
    // 所以下次发消息时要把它排除掉，否则会污染上下文。
    val isError: Boolean = false
)


// ============================================================
// 2. 网络层：调用 DeepSeek 流式 API
//
// ★ 多轮上下文：参数从"一句话"改成"整个对话历史"。
//   每次请求都把之前的 user / assistant 消息按顺序一起发过去，
//   模型才知道上一轮聊了什么。
//
// ★ 50ms 批处理在这里（IO 线程上）完成：只有真正要刷界面时才切主线程。
//
// 参数说明：
//   history       —— 对话历史（已按时间顺序排好，最后一条是用户刚发的）
//   onFlush(text) —— 每满 50ms（以及流结束时）回调一次，text 是"到目前为止的全部文本"
//   onDone(total) —— 流正常结束
//   onError(msg)  —— 出错
// ============================================================
fun sendToDeepSeek(
    history: List<ChatMessage>,
    scope: CoroutineScope,
    onFlush: (String) -> Unit,
    onDone: (totalChars: Int) -> Unit,
    onError: (String) -> Unit
) {
    scope.launch(Dispatchers.IO) {
        try {
            // ---------- 1. 检查 API Key ----------
            val apiKey = BuildConfig.DEEPSEEK_API_KEY
            Log.d(TAG, "stream start: key length=${apiKey.length}, history=${history.size} messages")

            if (apiKey.isBlank()) {
                withContext(Dispatchers.Main) {
                    onError("API Key 是空的，请检查 local.properties 里的 DEEPSEEK_API_KEY")
                }
                return@launch
            }

            // ---------- 2. ★★★ 把整个对话历史转成 API 格式 ★★★ ----------
            val messagesArray = JSONArray()

            // ① 系统提示词放最前面
            if (SYSTEM_PROMPT.isNotEmpty()) {
                messagesArray.put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
            }

            // ② 历史消息按时间顺序排进去
            //    isUser = true  → role = "user"      （我说的话）
            //    isUser = false → role = "assistant" （AI 说的话）
            var skippedError = 0
            for (msg in history) {

                // 空消息不发（比如流被中断时留下的空气泡）
                if (msg.content.isBlank()) continue

                // 错误提示不是 AI 的真实回复，不能当上下文发过去
                if (msg.isError) {
                    skippedError++
                    continue
                }

                messagesArray.put(JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "assistant")
                    put("content", msg.content)
                })
            }

            Log.d(TAG, "history packed: ${messagesArray.length()} messages (skipped $skippedError error bubbles)")

            val bodyJson = JSONObject().apply {
                put("model", MODEL_NAME)
                put("stream", true)
                put("messages", messagesArray)

                // ★★★ 关掉思考模式 ★★★
                // deepseek-flash 的思考模式【默认开启】。开启后它会先吐一长串
                // 思维链，而思维链分片里 content 字段是 JSON null
                //（思维内容在 reasoning_content 字段里）。
                // 那些 content=null 的分片，正是"满屏 nullnullnull"的源头之一。
                // 关掉后：响应更快，且不会再有这种空分片。
                put("thinking", JSONObject().put("type", "disabled"))
            }.toString()

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            // ---------- 3. 发出去，逐行读 ----------
            val response = httpClient.newCall(request).execute()
            Log.d(TAG, "response code=${response.code}")

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "HTTP error body: ${errBody.take(300)}")
                withContext(Dispatchers.Main) { onError("HTTP ${response.code}") }
                return@launch
            }

            val bodyStream = response.body
            if (bodyStream == null) {
                withContext(Dispatchers.Main) { onError("服务器返回了空内容") }
                return@launch
            }

            val reader = BufferedReader(InputStreamReader(bodyStream.byteStream()))

            // ---------- 4. ★★★ 50ms 批处理的核心 ★★★ ----------
            // StringBuilder：可变字符串缓冲区。
            // 不用 String 拼接是因为 Kotlin 的 String 不可变，每次 a + b 都要
            // 复制全部内容，攒到 1000 字时每个分片都要复制 1000 字，复杂度 O(n²)。
            val buffer = StringBuilder()
            var lastFlushAt = 0L      // 上次刷界面的时刻
            var flushCount = 0        // 实际刷了多少次
            var deltaCount = 0        // 收到多少个有效分片（= 不批处理时要刷多少次）
            var nullContentCount = 0  // 被过滤掉的"content 不是字符串"分片数
            var total = 0

            while (true) {
                // readLine() 返回 null 表示流结束
                val line = reader.readLine() ?: break

                // SSE 格式：有意义的行都以 "data:" 开头，其余（空行、注释、心跳）跳过
                if (!line.startsWith("data:")) continue

                val data = line.substring(5).trim()
                if (data.isEmpty()) continue

                // 服务端说"说完了"
                if (data == "[DONE]") break

                // 从这一行 JSON 里抠出文字。抠不出来（content 是 null 等）就跳过
                val delta = parseDelta(data)
                if (delta == null) {
                    // ★ 诊断日志：只打印前 3 条，避免刷屏。
                    //   如果这里数量很多，说明服务端一直在发 content=null 的分片，
                    //   把打印出来的原始内容发我，就能确认原因。
                    nullContentCount++
                    if (nullContentCount <= 3) {
                        Log.d(TAG, "skip chunk #$nullContentCount (content is not a String): ${data.take(200)}")
                    }
                    continue
                }

                deltaCount++
                total += delta.length

                // ① 无条件先把分片攒起来（纯内存操作，几乎零成本）
                buffer.append(delta)

                // ② 看一眼表，距上次刷新够 50ms 了吗？
                val now = SystemClock.elapsedRealtime()
                if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {

                    // 第一次时 lastFlushAt 还是 0，直接相减会得到"开机至今毫秒数"这种假值，
                    // 所以第一次统一记成 0
                    val interval = if (flushCount == 0) 0L else now - lastFlushAt

                    val text = buffer.toString()

                    // 【日志】每次批处理刷新
                    Log.d(TAG, "flush: ${text.length} chars, interval=${interval}ms")

                    lastFlushAt = now
                    flushCount++

                    // ★ 只在真正要刷界面时才切回主线程
                    withContext(Dispatchers.Main) { onFlush(text) }
                }
                // 不够 50ms → 什么都不做，继续攒。这就是"批处理"。
            }

            reader.close()

            // ---------- 5. 尾部补偿 ----------
            // 流结束时，缓冲区里可能还留着不足 50ms 的那几个字。
            // 不补这一次，最后几个字永远不会出现在屏幕上。
            val finalText = buffer.toString()
            withContext(Dispatchers.Main) { onFlush(finalText) }

            // 【日志】流结束
            Log.d(
                TAG,
                "stream done: total=$total chars " +
                    "(deltas=$deltaCount, flushes=$flushCount, skipped=$nullContentCount)"
            )

            withContext(Dispatchers.Main) { onDone(total) }

        } catch (e: Exception) {
            Log.e(TAG, "stream exception: ${e.message}", e)
            withContext(Dispatchers.Main) { onError(e.message ?: "unknown") }
        }
    }
}


// ============================================================
// 3. ★★★ 解析一行 SSE 数据，抠出文字 ★★★
//
// 服务端发来的一行长这样：
//   data: {"choices":[{"delta":{"content":"你好"},"index":0}]}
// 我们只要 delta.content 里的 "你好"。
//
// ⚠️⚠️ 这里有本项目最容易踩的坑 ⚠️⚠️
//
// 千万不要写成 .optString("content", "")！
//
// 原因：org.json 里有个反直觉的设计。当 JSON 的值是 null 时：
//   JSONObject.optString(name, fallback) 内部会走 JSON.toString(value)，
//   而 JSONObject.NULL 这个单例对象不是 Java 的 null，
//   于是被 String.valueOf() 转成了字符串 "null"，
//   最后返回的是 "null" 而不是你给的 fallback ""。
//
// 结果就是：服务端每发一个 content=null 的分片，屏幕上就多 4 个字母 "null"。
// 思考模式下这种分片成百上千个，于是满屏 nullnullnull...
//
// 正解：用 opt() 取出【原始对象】，再用 `is String` 判断类型。
//       JSON null 取出来是 JSONObject.NULL，不是 String，自然被挡掉。
//
// 另外再加一道保险：即使拿到了字符串，也排除空串和字面量 "null"。
// ============================================================
private fun parseDelta(json: String): String? {
    return try {
        val root = JSONObject(json)

        val choices = root.optJSONArray("choices") ?: return null
        val firstChoice = choices.optJSONObject(0) ?: return null
        val delta = firstChoice.optJSONObject("delta") ?: return null

        // ★ 关键：opt() 而不是 optString()
        val content = delta.opt("content")

        // ① 必须是 String 类型（这样 JSON null 就被挡掉了）
        // ② 不能是空串
        // ③ 不能是字面量 "null"（双重保险）
        (content as? String)?.takeIf { it.isNotEmpty() && it != "null" }

    } catch (e: Exception) {
        // 有些服务会插入心跳包或非标准行，忽略即可
        null
    }
}


// ============================================================
// 4. 程序入口
// ============================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 【日志】App 启动标记，方便在 Logcat 里找到每次测试的起点
        Log.d(TAG, "App started")

        enableEdgeToEdge()
        setContent {
            MyChatTheme {
                ChatScreen()
            }
        }
    }
}


// ============================================================
// 5. 聊天主界面
// ============================================================
@Composable
fun ChatScreen() {
    // 消息列表。mutableStateListOf 是"能被 Compose 感知的列表"，
    // 往里面加/改东西，界面会自动更新，不用手动刷新。
    val messages = remember { mutableStateListOf<ChatMessage>() }

    // 输入框里的文字
    var inputText by remember { mutableStateOf("") }

    // 是否正在请求/接收中（用来禁用发送按钮）
    var isStreaming by remember { mutableStateOf(false) }

    // 和界面生命周期绑定的协程作用域
    val scope = rememberCoroutineScope()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            // ---------- 顶部标题 ----------
            Text(
                text = if (isStreaming) "正在回复…" else "我的聊天助手",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // ---------- 中间：消息列表 ----------
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(message = message)
                }
            }

            // ---------- 底部：输入区 ----------
            InputBar(
                text = inputText,
                enabled = !isStreaming,        // ★ 发送中禁止再发，防止重复请求
                onTextChange = { inputText = it },
                onSend = {
                    val trimmed = inputText.trim()

                    // 空消息不发；正在流式回复时也不发
                    if (trimmed.isNotEmpty() && !isStreaming) {

                        inputText = ""
                        isStreaming = true

                        // ① 先把「用户消息」和「AI 占位消息」加进列表
                        messages.add(ChatMessage(content = trimmed, isUser = true))
                        messages.add(ChatMessage(content = "正在思考…", isUser = false))

                        // ② ★★★ 把整个对话历史打包发给模型 ★★★
                        //
                        //   dropLast(1)：去掉刚加进去的那条"正在思考…"占位气泡。
                        //   它是界面上的假数据，不能发给模型。
                        //
                        //   剩下的形如 [用户1, AI1, 用户2, AI2, ..., 用户N]，
                        //   按顺序发过去，模型就能"记得"前面聊了什么。
                        //
                        //   ⚠️ 必须 .toList() 做一份快照。
                        //      直接传 messages 的话，后面流式更新会同时改动这个列表，
                        //      导致发出去的内容不确定。
                        val fullHistory: List<ChatMessage> = messages.dropLast(1).toList()

                        // ③ 只保留最近 N 条，防止对话太长超出模型上下文窗口
                        val capped = if (fullHistory.size > MAX_HISTORY_MESSAGES) {
                            fullHistory.takeLast(MAX_HISTORY_MESSAGES)
                        } else {
                            fullHistory
                        }

                        // ④ 截断之后如果第一条变成了 AI 的回复，去掉它，
                        //    让历史以"用户消息"开头（更符合对话格式惯例）
                        val historyForApi: List<ChatMessage> =
                            if (capped.firstOrNull()?.isUser == false) capped.drop(1) else capped

                        // ⑤ 发起请求
                        //    注意：onFlush / onDone / onError 都是在主线程被调用的
                        //    （网络函数内部已经用 withContext(Dispatchers.Main) 切好了），
                        //    所以这里不需要再 scope.launch(Dispatchers.Main)。
                        sendToDeepSeek(
                            history = historyForApi,
                            scope = scope,

                            onFlush = { text ->
                                // ★ 关键：原地替换最后一条 AI 气泡，而不是新增一条。
                                //   同时用 isUser 判断，防止误改用户自己的消息。
                                val lastIndex = messages.lastIndex
                                if (lastIndex >= 0 && !messages[lastIndex].isUser) {
                                    messages[lastIndex] = ChatMessage(content = text, isUser = false)
                                }
                            },

                            onDone = { total ->
                                Log.d(TAG, "onDone: total=$total")
                                isStreaming = false
                            },

                            onError = { err ->
                                Log.e(TAG, "stream error: $err")
                                // 把错误显示在气泡里，不然界面上什么都看不到。
                                // 标上 isError = true，这样下次发消息时它不会被
                                // 当成 AI 的真实回复发给模型。
                                val lastIndex = messages.lastIndex
                                if (lastIndex >= 0 && !messages[lastIndex].isUser) {
                                    messages[lastIndex] = ChatMessage(
                                        content = "错误: $err",
                                        isUser = false,
                                        isError = true
                                    )
                                }
                                isStreaming = false
                            }
                        )
                    }
                }
            )
        }
    }
}


// ============================================================
// 6. 消息气泡
// ============================================================
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    // 我发的：主色背景 + 白字；AI 的：浅灰背景 + 深色字
    val bubbleColor = if (message.isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (message.isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 把靠"自己那一侧"的下角收窄，做出气泡的指向感
    val bubbleShape = if (message.isUser) {
        RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = 16.dp, bottomEnd = 4.dp
        )
    } else {
        RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = 4.dp, bottomEnd = 16.dp
        )
    }

    // 外层 Box 负责"靠左还是靠右"
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        // 内层 Surface 负责"长什么样"
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}


// ============================================================
// 7. 底部输入区
// ============================================================
@Composable
fun InputBar(
    text: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (enabled) "输入消息…" else "正在回复…") },
            maxLines = 4,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (enabled) onSend() })
        )

        Button(
            onClick = onSend,
            enabled = text.isNotBlank() && enabled,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Text("发送")
        }
    }
}


// ============================================================
// 8. 预览：只在 Android Studio 里显示，不装到手机
// ============================================================
@Preview(showBackground = true)
@Composable
fun MessageBubblePreview() {
    MyChatTheme {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MessageBubble(ChatMessage(content = "你好，我是你的助手", isUser = false))
            MessageBubble(ChatMessage(content = "帮我看一下这个病例", isUser = true))
        }
    }
}
