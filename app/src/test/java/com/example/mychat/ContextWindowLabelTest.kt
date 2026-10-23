package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 上下文窗口标签：1_000_000 显示成「1M tokens」比「1000K」好读。 */
class ContextWindowLabelTest {

    @Test
    fun defaultIsOneMillion() {
        assertEquals("1M tokens", contextWindowLabel())
    }

    @Test
    fun exactMillionBoundary() {
        assertEquals("1M tokens", contextWindowLabel(1_000_000))
    }

    @Test
    fun justBelowMillionFallsBackToK() {
        assertEquals("999K tokens", contextWindowLabel(999_999))
    }

    @Test
    fun multipleMillions() {
        assertEquals("2M tokens", contextWindowLabel(2_000_000))
    }

    @Test
    fun classic128k() {
        assertEquals("128K tokens", contextWindowLabel(128_000))
    }
}
