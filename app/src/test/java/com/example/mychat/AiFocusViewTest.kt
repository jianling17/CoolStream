package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 赛博视界动画曲线的边界测试。
 *
 * 这几个函数决定「光晕会不会闪瞎眼」「眨眼会不会把眼睛眨没」。
 * 它们都是纯数学，不需要 Android 运行时，所以能在 JVM 单测里直接卡边界 ——
 * 真正的绘制好不好看只能上真机看。
 */
class AiFocusViewTest {

    private val eps = 1e-3f

    // ---------------- 周期常量 ----------------

    @Test
    fun loopDurationIsOnePointSixSeconds() {
        assertEquals(1_600L, FOCUS_LOOP_MS)
    }

    // ---------------- 呼吸灯 ----------------

    @Test
    fun glowAlphaAtZeroPhaseIsMidLow() {
        // sin(0) = 0 → 0.55 + 0.45 * 0.5 = 0.775
        assertEquals(0.775f, focusGlowAlpha(0f), eps)
    }

    @Test
    fun glowAlphaPeaksAtQuarterPhase() {
        assertEquals(1.0f, focusGlowAlpha(0.25f), eps)
    }

    @Test
    fun glowAlphaBottomsAtThreeQuarterPhase() {
        assertEquals(0.55f, focusGlowAlpha(0.75f), eps)
    }

    @Test
    fun glowAlphaStaysInRange() {
        var i = 0
        while (i <= 100) {
            val a = focusGlowAlpha(i / 100f)
            assertTrue("相位 " + i + " 的亮度越界: " + a, a >= 0.55f - eps && a <= 1.0f + eps)
            i++
        }
    }

    @Test
    fun glowAlphaClampsOutOfRangePhase() {
        assertEquals(focusGlowAlpha(0f), focusGlowAlpha(-5f), eps)
        assertEquals(focusGlowAlpha(1f), focusGlowAlpha(5f), eps)
    }

    // ---------------- 眨眼 ----------------

    @Test
    fun eyeFullyOpenBeforeBlinkWindow() {
        assertEquals(1.0f, focusEyeOpenness(0f), eps)
        assertEquals(1.0f, focusEyeOpenness(0.5f), eps)
        assertEquals(1.0f, focusEyeOpenness(0.88f), eps)
    }

    @Test
    fun eyeClosesAtBlinkMidpoint() {
        // 0.94 落在闭眼窗口的正中，三角波取满 → 1 - 0.88 = 0.12
        assertEquals(0.12f, focusEyeOpenness(0.94f), eps)
    }

    @Test
    fun eyeReopensAtCycleEnd() {
        assertEquals(1.0f, focusEyeOpenness(1f), eps)
    }

    @Test
    fun eyeNeverFullyCloses() {
        // 完全闭成一条线时瞳孔会被裁没，看起来像眼睛消失，所以下限是 0.12
        var i = 0
        while (i <= 100) {
            val o = focusEyeOpenness(i / 100f)
            assertTrue("相位 " + i + " 的开合度越界: " + o, o >= 0.12f - eps && o <= 1.0f + eps)
            i++
        }
    }

    // ---------------- 瞳孔 ----------------

    @Test
    fun pupilScaleAtZeroPhase() {
        assertEquals(0.925f, focusPupilScale(0f), eps)
    }

    @Test
    fun pupilScaleStaysInRange() {
        var i = 0
        while (i <= 100) {
            val p = focusPupilScale(i / 100f)
            assertTrue("相位 " + i + " 的瞳孔缩放越界: " + p, p >= 0.85f - eps && p <= 1.0f + eps)
            i++
        }
    }

    // ---------------- 流光角度 ----------------

    @Test
    fun sweepTurnsFullCirclePerCycle() {
        assertEquals(0f, focusSweepDegrees(0f), eps)
        assertEquals(90f, focusSweepDegrees(0.25f), eps)
        assertEquals(180f, focusSweepDegrees(0.5f), eps)
        assertEquals(360f, focusSweepDegrees(1f), eps)
    }

    @Test
    fun sweepClampsOutOfRangePhase() {
        assertEquals(0f, focusSweepDegrees(-1f), eps)
        assertEquals(360f, focusSweepDegrees(2f), eps)
    }
}