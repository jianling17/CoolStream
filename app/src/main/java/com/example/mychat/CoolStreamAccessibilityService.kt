package com.example.mychat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * CoolStream v1.3.0 —— 无障碍服务底层骨架（看屏幕 / 点屏幕）。
 *
 * 三条硬性纪律：
 *
 * 1) 主线程零重活。AccessibilityService 的回调全部跑在主线程，节点树遍历是跨进程
 *    binder 调用，放主线程必然 ANR。这里 onAccessibilityEvent 只做「节流 + 置脏 + 派发」，
 *    真正的遍历在 Dispatchers.Default 的协程里跑。
 *
 * 2) 节点即拿即还。AccessibilityNodeInfo 在 API 33 之前是系统侧对象池里的对象，不 recycle
 *    就会拖垮系统进程（不是「GC 会帮你收」）。统一走 [recycleCompat]，并且保证异常路径也回收。
 *    对外主推 [UiNode] 快照 —— 把整棵节点树一次性压成纯数据 POJO 并立刻归还所有节点，
 *    之后对 AI 只暴露 POJO，从根上消灭「长期持有节点」这类泄漏。
 *
 * 3) 点击必须可熔断。反复点同一个坐标是最典型的 Agent 死循环。这里用 [ClickGuard]
 *    做「同目标窗口内限流 + 任务总预算」，配合 [clickAndAwaitChange] 的「点完 UI 没变化就停」，
 *    保证单次任务一定会终止。
 */
class CoolStreamAccessibilityService : AccessibilityService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 防重入：同一时刻只允许一个快照任务在跑。 */
    private val refreshing = AtomicBoolean(false)

    /** 请求合并：跑快照期间来的新事件只置脏，跑完自动再来一轮，避免任务堆积。 */
    private val refetchRequested = AtomicBoolean(false)

    @Volatile
    private var activeSnapshot: UiNode? = null

    @Volatile
    private var activePackage: String? = null

    /** 最近一次「窗口内容真正变化」的时间戳，用于点击后停滞判定。 */
    @Volatile
    private var lastWindowChangeAt: Long = 0L

    /** 仅在主线程读写。 */
    private var lastEventAt: Long = 0L

    private val clickGuard = ClickGuard()

    /** 赛博视界悬浮层。只在主线程读写（show/hide 全部走 mainHandler）。 */
    private var focusView: AiFocusView? = null

    /** 悬浮层被点亮的时间戳，用来保证「至少亮够 MIN_OVERLAY_MS」，避免一闪而过。 */
    private var focusShownAt: Long = 0L

    /** 延迟摘除的兜底任务。重新 show 时要 removeCallbacks，否则会被提前掐灭。 */
    private val focusHideRunnable = Runnable { removeFocusOverlayNow() }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        applyServiceInfo()
    }

    /** 运行时按代码重写一遍配置，避免只有 XML 静态配置时改不动。 */
    private fun applyServiceInfo() {
        val info = serviceInfo ?: return
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        // notificationTimeout 是 long 字段，必须传 Long（Int 不隐式提升）
        info.notificationTimeout = EVENT_THROTTLE_MS
        serviceInfo = info
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        // 服务被杀掉时悬浮层必须一起走，否则窗口会留在屏幕上没人管。
        mainHandler.removeCallbacks(focusHideRunnable)
        removeFocusOverlayNow()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // 系统要求实现。这里不做强制中断，正在跑的协程由 refreshSnapshot 的调用方管理。
    }

    // ------------------------------------------------------------------
    // 赛博视界：AI 屏幕感知悬浮层
    //
    // 为什么是 TYPE_ACCESSIBILITY_OVERLAY 而不是 TYPE_APPLICATION_OVERLAY：
    //   前者是无障碍服务自带的窗口类型，不需要 SYSTEM_ALERT_WINDOW 权限，
    //   层级也高于普通应用悬浮窗 —— 盖在别的 App 上不会被压下去。
    //   全程不申请任何运行时权限，是这套特效唯一可行的挂载方式。
    // ------------------------------------------------------------------

    /**
     * 点亮屏幕边缘的 AI 焦点光效。
     *
     * 幂等：已经在显示时只重置计时，不重复 addView（重复 add 会抛异常）。
     * 所有窗口操作都 post 到主线程 —— WindowManager 是线程敏感的，
     * 而调用方（ToolExecutor）跑在 IO 协程里。
     */
    fun showAiFocusOverlay() {
        mainHandler.post {
            if (focusView != null) {
                focusShownAt = SystemClock.uptimeMillis()
                mainHandler.removeCallbacks(focusHideRunnable)
                return@post
            }
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val view = AiFocusView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // 不吃触摸、不吃焦点：用户点哪儿都正常穿透到下面的 App。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.START
            // API 30 起窗口默认避让系统栏 inset，这里显式关掉避让。
            // ⚠️ 实测（REDMI K80 / 澎湃 OS）：这一条【不足以】让无障碍悬浮窗盖住状态栏 ——
            //    窗口依然被放在内容区，光效边框的上沿落在状态栏下面。
            //    这是 TYPE_ACCESSIBILITY_OVERLAY 的窗口布局规则，不是我们漏了标志位。
            //    保留这段是因为它在部分 ROM 上确实生效，且语义正确（本窗口不需要避让）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                params.fitInsetsTypes = 0
            }
            try {
                wm.addView(view, params)
                focusView = view
                focusShownAt = SystemClock.uptimeMillis()
                mainHandler.removeCallbacks(focusHideRunnable)
            } catch (e: Throwable) {
                // 悬浮层只是装饰，加不上就静默放弃 —— 绝不能因为它让工具调用失败。
                Log.w(TAG, "添加 AI 焦点悬浮层失败", e)
                view.stopAnimation()
            }
        }
    }

    /**
     * 熄灭悬浮层。
     *
     * 保证至少亮够 [MIN_OVERLAY_MS]：screen_see 读一次快照可能只要 80ms，
     * 直接 hide 就是一闪而过的白闪，看起来像花屏。所以短于下限时改成延迟摘除。
     */
    fun hideAiFocusOverlay() {
        mainHandler.post {
            if (focusView == null) return@post
            val shownFor = SystemClock.uptimeMillis() - focusShownAt
            val remain = MIN_OVERLAY_MS - shownFor
            mainHandler.removeCallbacks(focusHideRunnable)
            if (remain > 0L) {
                mainHandler.postDelayed(focusHideRunnable, remain)
            } else {
                removeFocusOverlayNow()
            }
        }
    }

    /** 真正摘窗口。只在主线程调用。 */
    private fun removeFocusOverlayNow() {
        val view = focusView ?: return
        focusView = null
        focusShownAt = 0L
        view.stopAnimation()
        try {
            (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(view)
        } catch (e: Throwable) {
            // 窗口可能已经被系统收走了，removeView 抛 IllegalArgument 是正常的。
            Log.w(TAG, "移除 AI 焦点悬浮层失败", e)
        }
    }

    // ------------------------------------------------------------------
    // 事件入口：主线程只做最廉价的过滤 + 合并
    // ------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val now = SystemClock.uptimeMillis()
        val type = event.eventType

        // 只有「内容/状态变化」才算 UI 真变了；我们自己点击产生的 TYPE_VIEW_CLICKED 不算，
        // 否则 clickAndAwaitChange 永远立刻返回 true，死循环检测形同虚设。
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            lastWindowChangeAt = now
        }

        event.packageName?.toString()?.let { pkg ->
            if (pkg.isNotEmpty()) activePackage = pkg
        }

        refetchRequested.set(true)

        if (now - lastEventAt < EVENT_THROTTLE_MS) return
        lastEventAt = now
        scheduleRefetch()
    }

    private fun scheduleRefetch() {
        // CAS 失败说明已有快照任务在跑；此时 refetchRequested 已置位，那一轮结束后会自动再跑。
        if (!refreshing.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                var bursts = 0
                do {
                    refetchRequested.set(false)
                    val snapshot = try {
                        buildActiveWindowSnapshot()
                    } catch (_: Throwable) {
                        null
                    }
                    if (snapshot != null) activeSnapshot = snapshot
                    bursts++
                } while (refetchRequested.get() && bursts < MAX_REFETCH_BURST)
            } finally {
                refreshing.set(false)
            }
        }
    }

    // ------------------------------------------------------------------
    // 对外 API：快照（推荐给 AI 用）
    // ------------------------------------------------------------------

    /** 最近一次窗口快照。纯数据对象，可跨线程、跨会话安全传递。 */
    fun latestSnapshot(): UiNode? = activeSnapshot

    /** 强制刷新一次并返回（挂起函数，遍历在 Default 上跑，不阻塞调用方线程）。 */
    suspend fun refreshSnapshot(): UiNode? = withContext(Dispatchers.Default) {
        val snapshot = try {
            buildActiveWindowSnapshot()
        } catch (_: Throwable) {
            null
        }
        if (snapshot != null) activeSnapshot = snapshot
        snapshot
    }

    /**
     * 遍历全部交互窗口（弹窗、输入法通常是独立 window）。
     * 注意：AccessibilityWindowInfo 由系统托管，**不要** recycle 它；它 root 出的节点必须回收，
     * UiTreeBuilder 已在内部完成。
     */
    suspend fun snapshotWindows(): List<WindowSnapshot> =
        withContext(Dispatchers.Default) { snapshotWindowsBlocking() }

    /** 同步实现。**仅供内部使用**：全窗口遍历是重活，必须跑在 Default 线程池。 */
    private fun snapshotWindowsBlocking(): List<WindowSnapshot> {
        val windowList = try {
            windows
        } catch (_: Throwable) {
            null
        } ?: return emptyList()

        val out = ArrayList<WindowSnapshot>(windowList.size)
        for (window in windowList) {
            val root = try {
                window.root
            } catch (_: Throwable) {
                null
            } ?: continue
            val tree = try {
                UiTreeBuilder(MAX_NODES, MAX_DEPTH).build(root)
            } catch (_: Throwable) {
                null
            }
            if (tree != null) {
                out.add(
                    WindowSnapshot(
                        id = window.id,
                        type = window.type,
                        active = window.isActive,
                        focused = window.isFocused,
                        root = tree
                    )
                )
            }
        }
        return out
    }

    private fun buildActiveWindowSnapshot(): UiNode? {
        // UiTreeBuilder 保证「进了 buildNode 就一定回收一次」，异常路径同样回收。
        val root = try {
            rootInActiveWindow
        } catch (_: Throwable) {
            null
        } ?: return null
        return UiTreeBuilder(MAX_NODES, MAX_DEPTH).build(root)
    }

    // ------------------------------------------------------------------
    // 对外 API：查找（直接操作节点树）
    // ------------------------------------------------------------------

    /**
     * 按文字找首个控件。[exact] = true 时全等匹配，否则包含匹配（忽略大小写）。
     *
     * ⚠️ 返回的 AccessibilityNodeInfo **所有权归调用方**，用完必须 `recycleCompat()`。
     * 若只是给 AI 判断，优先用 [latestSnapshot] + [UiNode.findByText]，零回收负担。
     */
    fun findNodeByText(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        if (text.isBlank()) return null
        val root = try {
            rootInActiveWindow
        } catch (_: Throwable) {
            null
        } ?: return null
        return searchFirst(root) { node -> nodeMatches(node, text, exact) }
    }

    /** 按文字找出全部控件。返回值中每个节点都必须由调用方回收。 */
    fun findNodesByText(
        text: String,
        maxResults: Int = MAX_SEARCH_RESULTS,
        exact: Boolean = false
    ): List<AccessibilityNodeInfo> {
        if (text.isBlank()) return emptyList()
        val root = try {
            rootInActiveWindow
        } catch (_: Throwable) {
            null
        } ?: return emptyList()

        val results = ArrayList<AccessibilityNodeInfo>(maxResults)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        try {
            while (queue.isNotEmpty() && results.size < maxResults && visited < MAX_SEARCH_NODES) {
                val node = queue.removeFirstOrNull() ?: break
                visited++
                if (nodeMatches(node, text, exact)) {
                    results.add(node) // 命中：保留所有权，不回收，也不再深入其子树
                    continue
                }
                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = try {
                        node.getChild(i)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    queue.addLast(child)
                }
                node.recycleCompat()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "findNodesByText aborted", t)
            for (node in results) node.recycleCompat()
            results.clear()
        } finally {
            while (queue.isNotEmpty()) queue.removeFirstOrNull()?.recycleCompat()
        }
        return results
    }

    /**
     * 广度优先查找首个命中节点。
     * 命中节点归还调用方；其余节点（含异常路径下正在处理的节点）严格回收。
     */
    private fun searchFirst(
        root: AccessibilityNodeInfo,
        maxNodes: Int = MAX_SEARCH_NODES,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var current: AccessibilityNodeInfo? = null
        var visited = 0
        try {
            while (queue.isNotEmpty()) {
                val node = queue.removeFirstOrNull() ?: break
                current = node
                visited++
                if (visited > maxNodes) return null
                if (predicate(node)) {
                    current = null // 所有权交出去，finally 不许回收它
                    return node
                }
                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = try {
                        node.getChild(i)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    queue.addLast(child)
                }
                node.recycleCompat()
                current = null
            }
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "searchFirst aborted", t)
            return null
        } finally {
            current?.recycleCompat()
            while (queue.isNotEmpty()) queue.removeFirstOrNull()?.recycleCompat()
        }
    }

    private fun nodeMatches(node: AccessibilityNodeInfo, query: String, exact: Boolean): Boolean {
        val text = if (node.isPassword) null else node.text?.toString()
        if (containsText(text, query, exact)) return true
        return containsText(node.contentDescription?.toString(), query, exact)
    }

    // ------------------------------------------------------------------
    // 对外 API：点击
    // ------------------------------------------------------------------

    /**
     * 模拟点击。优先 ACTION_CLICK（走无障碍动作，最稳），失效时回退到手势点击。
     *
     * 已有死循环防护：[ClickGuard] 先判定「同目标是否已在窗口内重复 N 次 / 任务预算是否耗尽」，
     * 任一命中直接拒绝执行并打印日志，绝不打穿。
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val target = resolveClickable(node) ?: return false
        val ownedByUs = target !== node
        try {
            if (!clickGuard.tryAcquire(fingerprintOf(target))) {
                Log.w(TAG, "click rejected: repeated target or click budget exhausted")
                return false
            }
            if (performActionClick(target)) return true
            return gestureTapSync(target)
        } catch (t: Throwable) {
            Log.w(TAG, "clickNode failed", t)
            return false
        } finally {
            // 只有我们自己通过 parent 上溯造出来的节点才需要回收
            if (ownedByUs) target.recycleCompat()
        }
    }

    /** 便捷组合：按文字找控件 → 点击 → 回收。目标节点不会泄漏。 */
    fun clickNodeByText(text: String, exact: Boolean = false): Boolean {
        val node = findNodeByText(text, exact) ?: return false
        return try {
            clickNode(node)
        } finally {
            node.recycleCompat()
        }
    }

    /**
     * 点一下并等待 UI 真变化。超时未变化返回 false —— 这是「防死循环」的第二道闸：
     * 点击如果没引起任何窗口内容变化，说明点了个死控件，外层 Agent 循环应立刻停止重试。
     */
    suspend fun clickAndAwaitChange(
        node: AccessibilityNodeInfo,
        timeoutMs: Long = DEFAULT_CHANGE_TIMEOUT_MS,
        pollMs: Long = DEFAULT_CHANGE_POLL_MS
    ): Boolean {
        val before = lastWindowChangeAt
        val clicked = withContext(Dispatchers.Default) { clickNode(node) }
        if (!clicked) return false
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (lastWindowChangeAt > before) return true
            delay(pollMs)
        }
        return false
    }

    /** 点击目标是否仍是屏幕上的「活」控件。 */
    private fun resolveClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.isEnabled) return node
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (hops < MAX_ANCESTOR_WALK) {
            val parent = try {
                current?.parent
            } catch (_: Throwable) {
                null
            }
            if (parent == null) {
                if (current !== node) current?.recycleCompat()
                return null
            }
            hops++
            val hit = parent.isClickable && parent.isEnabled
            // 只有不是调用方传进来的那个节点才回收
            if (current !== node) current?.recycleCompat()
            if (hit) return parent
            current = parent
        }
        if (current !== node) current?.recycleCompat()
        return null
    }

    private fun performActionClick(target: AccessibilityNodeInfo): Boolean = try {
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } catch (t: Throwable) {
        Log.w(TAG, "ACTION_CLICK failed", t)
        false
    }

    private fun gestureTapSync(target: AccessibilityNodeInfo): Boolean {
        val rect = Rect().also { target.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return gestureTapAtSync(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    /**
     * 手势点击。dispatchGesture 按官方建议在主线程调用；若调用方本身在后台线程，
     * 则 post 到主线程并带超时等待 —— 最多阻塞后台线程几百毫秒，绝不会阻塞主线程造成 ANR。
     */
    private fun gestureTapAtSync(
        x: Float,
        y: Float,
        durationMs: Long = DEFAULT_TAP_DURATION_MS,
        timeoutMs: Long = DEFAULT_GESTURE_TIMEOUT_MS
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return runGesture(x, y, durationMs)

        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val posted = mainHandler.post {
            try {
                result.set(runGesture(x, y, durationMs))
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return false
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS) && result.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun runGesture(x: Float, y: Float, durationMs: Long): Boolean = try {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    } catch (t: Throwable) {
        Log.w(TAG, "dispatchGesture failed", t)
        false
    }

    private fun fingerprintOf(node: AccessibilityNodeInfo): String {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val label = if (node.isPassword) "" else (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
        val sb = StringBuilder(96)
        sb.append(activePackage ?: "").append('|')
        sb.append(node.viewIdResourceName ?: "").append('|')
        sb.append(node.className ?: "").append('|')
        sb.append(label).append('|')
        sb.append(rect.left).append(',').append(rect.top).append(',')
        sb.append(rect.right).append(',').append(rect.bottom)
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 对外 API：状态与预算
    // ------------------------------------------------------------------

    fun hasWindowChangedSince(timestampMs: Long): Boolean = lastWindowChangeAt > timestampMs

    fun lastWindowChangeTimestamp(): Long = lastWindowChangeAt

    /** 新一轮 Agent 任务开始时调用，重置点击预算。 */
    fun resetClickBudget() {
        clickGuard.resetTask()
    }

    fun clickBudgetRemaining(): Int = clickGuard.remainingBudget()

    fun clicksPerformed(): Int = clickGuard.total()

    companion object {
        private const val TAG = "CoolStreamA11y"

        /** 事件节流：80ms 内只触发一次快照，足够跟手，又不会把系统拖爆。 */
        const val EVENT_THROTTLE_MS = 80L

        /** 单次遍历节点上限，防止巨型列表页遍历失控。 */
        const val MAX_NODES = 1_200
        const val MAX_DEPTH = 40
        const val MAX_SEARCH_NODES = 1_200
        const val MAX_SEARCH_RESULTS = 20

        /** clickable 祖先最多上溯几层。 */
        const val MAX_ANCESTOR_WALK = 8

        /** 一轮快照最多补跑的轮数，防止极端高频事件把协程钉死。 */
        const val MAX_REFETCH_BURST = 3

        const val DEFAULT_TAP_DURATION_MS = 40L
        const val DEFAULT_GESTURE_TIMEOUT_MS = 500L
        const val DEFAULT_CHANGE_TIMEOUT_MS = 1_200L
        const val DEFAULT_CHANGE_POLL_MS = 80L

        /** 悬浮层最短可见时长。太短会像白闪。 */
        const val MIN_OVERLAY_MS = 900L

        @Volatile
        private var instance: CoolStreamAccessibilityService? = null

        fun peek(): CoolStreamAccessibilityService? = instance

        fun isConnected(): Boolean = instance != null

        /** 本应用的 CoolStream 无障碍服务是否已在系统设置里被用户开启。 */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            if (!manager.isEnabled) return false
            val selfPackage = context.packageName
            val services = try {
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            } catch (_: Throwable) {
                null
            } ?: return false
            for (service in services) {
                val info = service.resolveInfo?.serviceInfo ?: continue
                if (info.packageName == selfPackage) return true
            }
            return false
        }

        /** 跳转到系统无障碍设置页，引导用户手动开启（无障碍权限无法静默申请）。 */
        fun openSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "open accessibility settings failed", t)
            }
        }
    }
}

// -------------------------------------------------------------------------
// 节点回收：API 33 起 recycle() 已废弃（系统去掉了对象池），加版本护栏
// -------------------------------------------------------------------------

@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.recycleCompat() {
    // Build.VERSION_CODES.TIRAMISU = 33
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recycle()
}

// -------------------------------------------------------------------------
// 轻量节点快照：替代「长期持有 AccessibilityNodeInfo」的纯数据模型
// -------------------------------------------------------------------------

/**
 * 一棵窗口树的纯数据快照。不含任何系统对象，可安全缓存、跨线程传递、序列化给 AI。
 */
data class UiNode(
    val index: Int,
    val depth: Int,
    val className: String?,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val bounds: Rect
) {

    val children: MutableList<UiNode> = ArrayList()

    /** 先序展开成平铺列表，便于给 AI 逐条编号引用。 */
    fun flatten(): List<UiNode> {
        val out = ArrayList<UiNode>(16)
        val stack = ArrayDeque<UiNode>()
        stack.addLast(this)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            out.add(node)
            for (i in node.children.indices.reversed()) stack.addLast(node.children[i])
        }
        return out
    }

    fun label(): String? = text ?: contentDescription

    fun findByText(query: String, exact: Boolean = false): UiNode? {
        if (query.isBlank()) return null
        for (node in flatten()) {
            if (containsText(node.text, query, exact)) return node
            if (containsText(node.contentDescription, query, exact)) return node
        }
        return null
    }

    fun selectorKey(): String = buildString(64) {
        append(viewId ?: "")
        append('|')
        append(className ?: "")
        append('|')
        append(label() ?: "")
        append('|')
        append(bounds.left).append(',').append(bounds.top).append(',')
        append(bounds.right).append(',').append(bounds.bottom)
    }

    /** 压成给大模型看的紧凑文本，带缩进与属性标记，超长自动截断。 */
    fun describe(maxChars: Int = 6_000): String {
        val sb = StringBuilder(1_024)
        for (node in flatten()) {
            if (sb.length >= maxChars) {
                sb.append("\n... (truncated)\n")
                break
            }
            repeat(node.depth.coerceAtMost(12)) { sb.append("  ") }
            sb.append('#').append(node.index).append(' ')
            node.className?.substringAfterLast('.')?.let { sb.append(it).append(' ') }
            node.viewId?.let { sb.append(it).append(' ') }
            node.label()?.let { sb.append('"').append(it.take(60)).append('"').append(' ') }
            if (node.clickable) sb.append("[click] ")
            if (node.scrollable) sb.append("[scroll] ")
            if (node.editable) sb.append("[edit] ")
            if (!node.enabled) sb.append("[disabled] ")
            sb.append('(')
                .append(node.bounds.left).append(',')
                .append(node.bounds.top).append(',')
                .append(node.bounds.right).append(',')
                .append(node.bounds.bottom)
                .append(")\n")
        }
        return sb.toString()
    }
}

/** 单个交互窗口的快照。 */
data class WindowSnapshot(
    val id: Int,
    val type: Int,
    val active: Boolean,
    val focused: Boolean,
    val root: UiNode
)

internal fun containsText(value: String?, query: String, exact: Boolean): Boolean {
    if (value == null || value.isEmpty()) return false
    return if (exact) value.equals(query, ignoreCase = true) else value.contains(query, ignoreCase = true)
}

// -------------------------------------------------------------------------
// 节点树 → 快照：遍历即回收，绝不把节点留在内存里
// -------------------------------------------------------------------------

private class UiTreeBuilder(private val maxNodes: Int, private val maxDepth: Int) {

    private var visited = 0

    fun build(root: AccessibilityNodeInfo): UiNode? {
        visited = 0
        return buildNode(root, 0)
    }

    /**
     * 进入本函数后，无论正常返回、超限返回还是中途抛异常，节点都会且只会被回收一次。
     * 因此调用方不需要（也不允许）再回收。
     */
    private fun buildNode(node: AccessibilityNodeInfo, depth: Int): UiNode? {
        if (visited >= maxNodes || depth > maxDepth) {
            node.recycleCompat()
            return null
        }
        visited++
        try {
            val ui = UiNode(
                index = visited,
                depth = depth,
                className = node.className?.toString(),
                viewId = node.viewIdResourceName,
                text = if (node.isPassword) null else node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                clickable = node.isClickable,
                enabled = node.isEnabled,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                password = node.isPassword,
                bounds = Rect().also { node.getBoundsInScreen(it) }
            )
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Throwable) {
                    null
                } ?: continue
                val childUi = buildNode(child, depth + 1)
                if (childUi != null) ui.children.add(childUi)
            }
            return ui
        } finally {
            node.recycleCompat()
        }
    }
}

// -------------------------------------------------------------------------
// 点击熔断器
// -------------------------------------------------------------------------

/**
 * 防止 Agent 反复点同一个目标造成死循环。
 *
 * - 同目标窗口限流：同一指纹（包名+viewId+类名+文本+坐标）在 [windowMillis] 内最多点
 *   [maxSameTargetInWindow] 次，超出直接拒绝。
 * - 任务总预算：单次任务总计最多 [maxClicksPerTask] 次点击，超了要求上层重新规划。
 *
 * 全部方法加 @Synchronized，可安全地被主线程 / 协程并发调用。
 */
class ClickGuard(
    private val windowMillis: Long = DEFAULT_WINDOW_MS,
    private val maxSameTargetInWindow: Int = DEFAULT_MAX_SAME_TARGET,
    private val maxClicksPerTask: Int = DEFAULT_MAX_CLICKS_PER_TASK,
    capacity: Int = DEFAULT_CAPACITY
) {

    private data class Record(val key: String, val at: Long)

    private val records = ArrayDeque<Record>()
    private val maxRecords = capacity.coerceAtLeast(8)
    private val totalClicks = AtomicInteger(0)

    @Synchronized
    fun tryAcquire(key: String, now: Long = SystemClock.uptimeMillis()): Boolean {
        if (totalClicks.get() >= maxClicksPerTask) return false

        val threshold = now - windowMillis
        while (records.isNotEmpty() && records.first().at < threshold) records.removeFirst()

        var repeats = 0
        for (record in records) {
            if (record.key == key) repeats++
        }
        if (repeats >= maxSameTargetInWindow) return false

        records.addLast(Record(key, now))
        while (records.size > maxRecords) records.removeFirst()
        totalClicks.incrementAndGet()
        return true
    }

    @Synchronized
    fun remainingBudget(): Int = (maxClicksPerTask - totalClicks.get()).coerceAtLeast(0)

    @Synchronized
    fun total(): Int = totalClicks.get()

    @Synchronized
    fun resetTask() {
        records.clear()
        totalClicks.set(0)
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 3_000L
        const val DEFAULT_MAX_SAME_TARGET = 2
        const val DEFAULT_MAX_CLICKS_PER_TASK = 60
        const val DEFAULT_CAPACITY = 64
    }
}