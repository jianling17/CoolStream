package com.example.mychat

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * 攒批窗口：50ms。
 * < 50ms：重组次数暴涨、GPU 提交排队 → 发热；> 50ms：肉眼可感知卡顿。
 */
const val DEFAULT_BATCH_INTERVAL_MS = 50L

/**
 * 增量回复来源（SSE / WebSocket / 本地推理）。
 *
 * 契约：返回的 Flow 是 **delta 语义** —— 每个元素都是"新增的一段文本"，
 * 必须逐条 append，绝不能丢中间元素（这也是不能用 Flow.sample/debounce 的原因）。
 * 网络与解析必须已经下沉到 IO 调度器（见下方 flowOn）。
 */
fun interface ReplyStreamSource {
    fun stream(sessionId: Long, prompt: String): Flow<String>
}

/**
 * 落库出口。
 *
 * 契约：**幂等**。同一 (sessionId, 最终文本) 重复写入不得产生重复记录；
 * 实现内部应自行切到 IO，并对 sessionId 做行级 upsert / 覆盖写。
 */
interface MessageSink {
    suspend fun persist(sessionId: Long, content: String)
}

/** 一次性 UI 事件。用 Channel 派发：旋转后不漏、不重（Channel 天然单消费）。 */
sealed interface ChatEffect {
    object Completed : ChatEffect
    object Stopped : ChatEffect
    data class Error(val message: String) : ChatEffect
}

/**
 * 攒批内核：把 token delta 流按 [batchIntervalMs] 聚合，输出**累积全量快照**。
 *
 * 设计要点：
 * 1. 单消费者串行：消费循环运行在调用方所在调度器（调用方须保证是 Main），
 *    因此内部对该缓冲区的读写天然互斥，**无需任何锁**。
 * 2. 上游通过 [Channel] 汇入，`flowOn` 只在 feeder 侧生效，不会把消费侧
 *    挪到 IO（Compose 状态写入必须在 Main）。
 * 3. 取消安全：即使被取消，`finally` 里用 NonCancellable 把最后一个
 *    未满窗口的尾巴补发一次 —— 「停止生成」不会丢最后几十毫秒的字。
 *
 * 注意：`buffer.toString()` 每批产生一个不可变快照，整体是 O(n^2) 字符拷贝。
 * 对单条回答（几万字符、几百批）而言开销可忽略；不要把这个内核拿去跑流式
 * 无限日志。
 */
suspend fun Flow<String>.batchInto(
    batchIntervalMs: Long = DEFAULT_BATCH_INTERVAL_MS,
    onBatch: (String) -> Unit
) {
    coroutineScope {
        val incoming = Channel<String>(Channel.BUFFERED)
        val feeder = launch {
            try {
                collect { token -> incoming.send(token) }
            } finally {
                incoming.close()
            }
        }

        val buffer = StringBuilder(1024)
        var dirty = false
        try {
            while (true) {
                // 阻塞等第一个 token；通道已关闭且排空 → 优雅退出
                val first = incoming.receiveCatching().getOrNull() ?: break
                buffer.append(first)
                dirty = true

                // 在窗口内尽量多攒，减少状态写入次数（每次写入 = 一次重组）
                val deadline = SystemClock.uptimeMillis() + batchIntervalMs
                while (true) {
                    val remain = deadline - SystemClock.uptimeMillis()
                    if (remain <= 0L) break
                    val next = withTimeoutOrNull(remain) {
                        incoming.receiveCatching().getOrNull()
                    } ?: break
                    buffer.append(next)
                    dirty = true
                }

                onBatch(buffer.toString())
                dirty = false
            }
        } finally {
            // 消费侧先退出（如下游取消、切会话）时，连带取消上游，防止 feeder 悬挂
            feeder.cancel()
            // 取消/异常路径下，把已收到但未发布的尾巴补发一次（不可被取消打断）
            if (dirty) {
                withContext(NonCancellable) { onBatch(buffer.toString()) }
            }
        }
    }
}

/**
 * 会话级流式渲染 ViewModel。
 *
 * 关键设计：
 * - 热点文本用 Compose `mutableStateOf`（零 StateFlow 分配开销，Compose 直读）；
 * - 结构性状态（当前会话）用 `StateFlow`；一次性事件用 `Channel -> Flow`；
 * - 「世代计数器 generation」拦截已废弃会话的迟到写入；
 * - 所有落库走 `NonCancellable`，取消也能安全落库；
 * - 流式任务挂在 `viewModelScope` 上：屏幕旋转不重建、不重复订阅。
 */
class ReferenceChatViewModel(
    private val source: ReplyStreamSource,
    private val sink: MessageSink
) : ViewModel() {

    /** 热路径：当前正在流式渲染的文本快照。UI 直接读即自动订阅。 */
    var streamingText by mutableStateOf("")
        private set

    var isGenerating by mutableStateOf(false)
        private set

    private val _currentSessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()

    private val effectChannel = Channel<ChatEffect>(Channel.BUFFERED)
    val effects: Flow<ChatEffect> = effectChannel.receiveAsFlow()

    /** 世代计数器：每次 start/switch 自增，用于让旧会话的迟到回调永久失效。 */
    private val generation = AtomicLong(0L)
    private var streamJob: Job? = null

    /** 串行落库，避免并发写同一会话造成竞态。 */
    private val persistMutex = Mutex()

    fun startGeneration(sessionId: Long, prompt: String) {
        cancelActive()
        _currentSessionId.value = sessionId

        val gen = generation.incrementAndGet()
        isGenerating = true
        streamingText = ""

        streamJob = viewModelScope.launch { // 默认 Dispatchers.Main.immediate
            var latestText = ""

            try {
                source.stream(sessionId, prompt)
                    .flowOn(Dispatchers.IO) // 网络 / 解析全部下沉 IO，绝不占主线程
                    .batchInto { snapshot ->
                        // 无论世代是否已失效，都要更新落库快照（旧会话仍需安全落库）
                        latestText = snapshot
                        // 只有仍属于当前世代时才写 UI 状态，杜绝"往废弃 State 写"
                        if (gen == generation.get()) {
                            streamingText = snapshot // 每批仅一次写入 = 一次重组
                        }
                    }

                if (gen == generation.get()) {
                    effectChannel.trySend(ChatEffect.Completed)
                }
            } catch (ce: CancellationException) {
                throw ce // 关键：绝不吞取消信号，否则父作用域无法真正取消
            } catch (t: Throwable) {
                if (gen == generation.get()) {
                    effectChannel.trySend(ChatEffect.Error(t.message ?: t.javaClass.simpleName))
                }
            } finally {
                // 终止（正常 / 取消 / 异常）都保证落库，且不可被取消打断
                val textToPersist = latestText
                if (textToPersist.isNotEmpty()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        persistMutex.withLock { sink.persist(sessionId, textToPersist) }
                    }
                }
                if (gen == generation.get()) {
                    isGenerating = false
                }
            }
        }
    }

    /** 用户主动「停止生成」：取消后 effect 通知 UI，尾部由 finally 安全落库。 */
    fun stopGeneration() {
        if (streamJob == null) return
        cancelActive()
        effectChannel.trySend(ChatEffect.Stopped)
    }

    /** 切换到新会话：取消旧流（旧流仍落库到旧 sessionId），并让旧世代永久失效。 */
    fun switchSession(sessionId: Long) {
        cancelActive()
        generation.incrementAndGet()
        _currentSessionId.value = sessionId
        streamingText = "" // 清掉 UI 上旧会话的残留文本
    }

    private fun cancelActive() {
        val job = streamJob ?: return
        streamJob = null
        isGenerating = false
        job.cancel() // 不 join，避免阻塞主线程；落库在 NonCancellable 中完成
    }
}