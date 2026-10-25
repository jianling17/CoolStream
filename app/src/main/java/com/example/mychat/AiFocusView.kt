package com.example.mychat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * CoolStream v1.4.0 —— AI 屏幕感知的「赛博视界」悬浮层。
 *
 * 只在 screen_see / screen_tap 执行期间出现，告诉用户「AI 此刻正在看你的屏幕」。
 * 两件事：屏幕最边缘一圈流动的霓虹光晕 + 顶部偏下（摄像头附近）一只会眨的眼睛。
 *
 * 三条纪律：
 *
 * 1) 纯 Canvas 手绘。不引第三方特效库，也不加载任何位图资源 ——
 *    这个 View 会被 WindowManager 反复挂载/卸载，静态资源越少越好。
 *
 * 2) 动画必须能停。ValueAnimator 是 INFINITE 的，不 cancel 就会一直持有 View，
 *    卸载后仍然每帧回调 invalidate —— 这是最典型的悬浮窗泄漏形态。
 *    所以 onDetachedFromWindow 里一定 stopAnimation。
 *
 * 3) 绝不出现在无障碍树里。它自己就是给无障碍服务用的装饰层，
 *    要是被 screen_see 读到自己，模型会以为屏幕上真有个眼睛控件，
 *    所以 importantForAccessibility 直接设成 NO。
 */
class AiFocusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    /** 动画进度 0..1，一个周期转一整圈光晕、眨一次眼。 */
    private var phase = 0f

    private var animator: ValueAnimator? = null

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }

    private val eyeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = NEON
    }

    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = NEON
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = NEON
    }

    private val eyePath = Path()
    private val ringRect = RectF()
    private val sweepMatrix = Matrix()
    private var sweep: SweepGradient? = null

    init {
        // 装饰层：不吃触摸、不吃焦点、不进无障碍树。
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(v: Float): Float = v * density

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    /** 开始循环动画。重复调用无副作用（已开就什么都不做）。 */
    fun startAnimation() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FOCUS_LOOP_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 停掉动画并释放对 View 的持有。卸载时必须调用，否则悬浮窗泄漏。 */
    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawEdgeGlow(canvas, w, h)
        drawEye(canvas, w / 2f, h * EYE_TOP_RATIO)
    }

    /** 屏幕边缘的流光：一条扫掠渐变绕屏一周，叠一层常亮细边保底。 */
    private fun drawEdgeGlow(canvas: Canvas, w: Float, h: Float) {
        val inset = dp(2f)
        ringRect.set(inset, inset, w - inset, h - inset)
        val radius = dp(28f)
        val alpha = (255f * focusGlowAlpha(phase)).toInt().coerceIn(0, 255)

        if (sweep == null) {
            sweep = SweepGradient(
                w / 2f,
                h / 2f,
                intArrayOf(TRANSPARENT_NEON, NEON, TRANSPARENT_NEON, NEON_DIM, TRANSPARENT_NEON),
                floatArrayOf(0f, 0.12f, 0.26f, 0.62f, 0.76f),
            )
        }
        sweepMatrix.setRotate(focusSweepDegrees(phase), w / 2f, h / 2f)
        sweep?.setLocalMatrix(sweepMatrix)
        edgePaint.shader = sweep
        edgePaint.alpha = alpha
        canvas.drawRoundRect(ringRect, radius, radius, edgePaint)

        // 常亮细边：流光转过去之后边缘不会彻底消失，像一层薄薄的玻璃。
        edgePaint.shader = null
        edgePaint.color = NEON
        edgePaint.alpha = (alpha * 0.22f).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(ringRect, radius, radius, edgePaint)
    }

    /** 顶部那只眼睛：两段二次贝塞尔拼成杏仁形，瞳孔跟着呼吸缩放，一个周期眨一次。 */
    private fun drawEye(canvas: Canvas, cx: Float, cy: Float) {
        val eyeWidth = dp(78f)
        val openness = focusEyeOpenness(phase)
        val halfW = eyeWidth / 2f
        val lid = eyeWidth * 0.40f * openness

        eyePath.reset()
        eyePath.moveTo(cx - halfW, cy)
        eyePath.quadTo(cx, cy - lid, cx + halfW, cy)
        eyePath.quadTo(cx, cy + lid, cx - halfW, cy)
        eyePath.close()

        // 眼窝里的柔光，闭眼时同步变暗。
        glowPaint.alpha = (46f * openness).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, eyeWidth * 0.46f, glowPaint)

        canvas.save()
        canvas.clipPath(eyePath)
        val pupil = eyeWidth * 0.17f * focusPupilScale(phase) *
            openness.coerceAtLeast(0.25f)
        pupilPaint.alpha = 235
        canvas.drawCircle(cx, cy, pupil, pupilPaint)
        canvas.restore()

        eyeStroke.alpha = 245
        canvas.drawPath(eyePath, eyeStroke)
    }

    companion object {
        /** 眼睛中心离屏幕顶边的比例。10% 大致落在前摄附近。 */
        private const val EYE_TOP_RATIO = 0.10f
    }
}

// ============================================================
// 纯函数：动画曲线。
//
// 特意从 View 里抽出来 —— 这几个是「画得好不好看」的全部逻辑，
// 留在 onDraw 里就只能靠肉眼看，抽成纯函数后 JVM 单测能直接卡住边界。
// ============================================================

/** 一个动画周期的时长（毫秒）。 */
internal const val FOCUS_LOOP_MS = 1_600L

/** 霓虹青，边缘流光与眼睛共用。 */
internal val FOCUS_NEON = 0xFF2DD4FF.toInt()

/** 偏暗的青，用来让扫掠渐变有两个亮度层次。 */
internal val FOCUS_NEON_DIM = 0xFF1E7FA8.toInt()

/** 完全透明的同色，作为渐变的两端。 */
internal const val FOCUS_TRANSPARENT = 0x002DD4FF

/**
 * 呼吸灯：整体亮度在 0.55 ~ 1.0 之间做一次正弦起伏。
 *
 * 相位 0 是 0.775（正弦过零），0.25 最亮，0.75 最暗。
 */
internal fun focusGlowAlpha(phase: Float): Float {
    val p = phase.coerceIn(0f, 1f)
    return 0.55f + 0.45f * (0.5f + 0.5f * sin(p * TWO_PI))
}

/**
 * 眼睛开合度：一个周期里前 88% 完全睁开，最后 12% 眨一下（闭到底 0.12 再睁开）。
 *
 * 不返回 0 —— 完全闭成一条线时瞳孔会被裁没，看起来像「眼睛没了」；
 * 留 0.12 的缝更像真的眨眼。
 */
internal fun focusEyeOpenness(phase: Float): Float {
    val p = phase.coerceIn(0f, 1f)
    if (p <= BLINK_START) return 1f
    val t = (p - BLINK_START) / (1f - BLINK_START)
    val tri = if (t < 0.5f) t * 2f else (1f - t) * 2f
    return (1f - 0.88f * tri).coerceIn(0.12f, 1f)
}

/** 瞳孔缩放：跟着呼吸在 0.85 ~ 1.0 之间轻微变化，一个周期两次。 */
internal fun focusPupilScale(phase: Float): Float {
    val p = phase.coerceIn(0f, 1f)
    return 0.85f + 0.15f * (0.5f + 0.5f * sin(p * TWO_PI * 2f))
}

/** 流光旋转角度：一个周期整整一圈。 */
internal fun focusSweepDegrees(phase: Float): Float = phase.coerceIn(0f, 1f) * 360f

private const val TWO_PI = 6.2831855f
private const val BLINK_START = 0.88f

private val NEON = FOCUS_NEON
private val NEON_DIM = FOCUS_NEON_DIM
private const val TRANSPARENT_NEON = FOCUS_TRANSPARENT
