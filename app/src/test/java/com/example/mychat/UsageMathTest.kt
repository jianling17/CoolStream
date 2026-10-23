package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 用量与费用计算的测试。
 *
 * 这层的风险不是崩，而是【算错钱】—— 单价改一位、漏乘一个系数，
 * 界面上仍然显示得好好的。所以这里钉住三件事：
 *   1) cacheMiss = prompt - hit，且永不为负（服务端偶尔给 hit > prompt）
 *   2) 峰谷单价的比例关系（命中是未命中的 1/50、输出是 8 倍/4 倍）
 *   3) 展示格式的分档边界（1 分钱、1000、1000000）
 *
 * ⚠️ costCny 依赖 isPeakHour()，会随时钟变化，所以断言一律写成
 *    「峰价或谷价之一」，绝不写死单个数字，否则白天绿晚上红。
 */
class UsageMathTest {

    private val YEN = "¥"

    // ==================== UsageStats ====================

    @Test
    fun stats_defaultsAreZero() {
        val u = UsageStats()
        assertEquals(0, u.promptTokens)
        assertEquals(0, u.cacheHitTokens)
        assertEquals(0, u.completionTokens)
    }

    @Test
    fun stats_emptyCompanionIsZero() {
        assertEquals(0, UsageStats.EMPTY.totalTokens)
        assertEquals(0, UsageStats.EMPTY.cacheMissTokens)
    }

    @Test
    fun stats_cacheMissIsPromptMinusHit() {
        assertEquals(600, UsageStats(promptTokens = 1000, cacheHitTokens = 400).cacheMissTokens)
    }

    @Test
    fun stats_cacheMissNeverNegative() {
        assertEquals(0, UsageStats(promptTokens = 100, cacheHitTokens = 150).cacheMissTokens)
    }

    @Test
    fun stats_totalIsPromptPlusCompletion() {
        assertEquals(1050, UsageStats(promptTokens = 1000, completionTokens = 50).totalTokens)
    }

    @Test
    fun stats_plusAddsEachField() {
        val a = UsageStats(promptTokens = 10, cacheHitTokens = 4, completionTokens = 2)
        val b = UsageStats(promptTokens = 5, cacheHitTokens = 1, completionTokens = 3)
        val s = a + b
        assertEquals(15, s.promptTokens)
        assertEquals(5, s.cacheHitTokens)
        assertEquals(5, s.completionTokens)
    }

    // ==================== costCny ====================

    @Test
    fun cost_emptyIsZero() {
        assertEquals(0.0, costCny(UsageStats.EMPTY), 1e-12)
    }

    @Test
    fun cost_allCacheHit_isHitPrice() {
        val v = costCny(UsageStats(promptTokens = 1_000_000, cacheHitTokens = 1_000_000))
        assertTrue(abs(v - 0.04) < 1e-9 || abs(v - 0.02) < 1e-9)
    }

    @Test
    fun cost_allCacheMiss_isMissPrice() {
        val v = costCny(UsageStats(promptTokens = 1_000_000, cacheHitTokens = 0))
        assertTrue(abs(v - 2.0) < 1e-9 || abs(v - 1.0) < 1e-9)
    }

    @Test
    fun cost_allCompletion_isOutputPrice() {
        val v = costCny(UsageStats(completionTokens = 1_000_000))
        assertTrue(abs(v - 8.0) < 1e-9 || abs(v - 4.0) < 1e-9)
    }

    @Test
    fun cost_missIsFiftyTimesHit() {
        val hit = costCny(UsageStats(promptTokens = 1_000_000, cacheHitTokens = 1_000_000))
        val miss = costCny(UsageStats(promptTokens = 1_000_000, cacheHitTokens = 0))
        assertEquals(miss, hit * 50.0, 1e-9)
    }

    // ==================== formatCny ====================

    @Test
    fun cny_zero_usesFourDecimals() {
        assertEquals(YEN + "0.0000", formatCny(0.0))
    }

    @Test
    fun cny_smallValue_usesFourDecimals() {
        assertEquals(YEN + "0.0010", formatCny(0.001))
    }

    @Test
    fun cny_justBelowOneCent_stillFourDecimals() {
        assertEquals(YEN + "0.0100", formatCny(0.00999))
    }

    @Test
    fun cny_exactlyOneCent_usesTwoDecimals() {
        assertEquals(YEN + "0.01", formatCny(0.01))
    }

    @Test
    fun cny_normalValue_twoDecimals() {
        assertEquals(YEN + "1.23", formatCny(1.234))
    }

    @Test
    fun cny_trailingZeroKept() {
        assertEquals(YEN + "12.50", formatCny(12.5))
    }

    // ==================== formatTokens ====================

    @Test
    fun tokens_zero() {
        assertEquals("0", formatTokens(0))
    }

    @Test
    fun tokens_belowThousand_raw() {
        assertEquals("999", formatTokens(999))
    }

    @Test
    fun tokens_exactlyThousand_K() {
        assertEquals("1.0K", formatTokens(1000))
    }

    @Test
    fun tokens_oneAndHalfK() {
        assertEquals("1.5K", formatTokens(1500))
    }

    @Test
    fun tokens_justBelowMillion_stillK() {
        assertEquals("1000.0K", formatTokens(999_999))
    }

    @Test
    fun tokens_exactlyMillion_M() {
        assertEquals("1.0M", formatTokens(1_000_000))
    }

    @Test
    fun tokens_twoAndHalfM() {
        assertEquals("2.5M", formatTokens(2_500_000))
    }

    // ==================== UsageLedgerSnapshot ====================

    @Test
    fun snapshot_defaultsAreZero() {
        val s = UsageLedgerSnapshot()
        assertEquals(0, s.todayPromptTokens)
        assertEquals(0.0, s.todayCny, 1e-12)
        assertEquals(0.0, s.totalCny, 1e-12)
    }

    @Test
    fun snapshot_todayTotalTokens() {
        val s = UsageLedgerSnapshot(todayPromptTokens = 100, todayCompletionTokens = 20)
        assertEquals(120, s.todayTotalTokens)
    }

    @Test
    fun snapshot_totalTotalTokens() {
        val s = UsageLedgerSnapshot(totalPromptTokens = 300, totalCompletionTokens = 40)
        assertEquals(340, s.totalTotalTokens)
    }

    @Test
    fun snapshot_copyKeepsOtherFields() {
        val s = UsageLedgerSnapshot(todayPromptTokens = 100, totalPromptTokens = 300)
        val c = s.copy(todayCny = 0.5)
        assertEquals(100, c.todayPromptTokens)
        assertEquals(300, c.totalPromptTokens)
        assertEquals(0.5, c.todayCny, 1e-12)
    }
}
