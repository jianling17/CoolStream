package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Test

/** ★ v1.6.1 计算式排版：spaceCalcOps 的 JVM 单测 */
class SpaceCalcOpsTest {

    @Test fun t01_equal() = assertEquals("26195 ÷ 600 = 43", spaceCalcOps("26195÷600=43"))
    @Test fun t02_times() = assertEquals("3 × 4 = 12", spaceCalcOps("3×4=12"))
    @Test fun t03_star() = assertEquals("3 * 4 = 12", spaceCalcOps("3*4=12"))
    @Test fun t04_slash() = assertEquals("10 / 2 = 5", spaceCalcOps("10/2=5"))
    @Test fun t05_existing_space() = assertEquals("a = 1", spaceCalcOps("a = 1"))
    @Test fun t06_no_digit() = assertEquals("a=b&c", spaceCalcOps("a=b&c"))
    @Test fun t07_url_protected() = assertEquals("https://x.com/a=1", spaceCalcOps("https://x.com/a=1"))
    @Test fun t08_bold_protected() = assertEquals("**COOL**", spaceCalcOps("**COOL**"))
    @Test fun t09_eq_after_space_before_digit() = assertEquals("= 43", spaceCalcOps("=43"))
    @Test fun t10_word_eq_untouched() = assertEquals("name=value", spaceCalcOps("name=value"))
    @Test fun t11_mixed_sentence() = assertEquals(
        // 两侧无数字的 = / × 不补（防误伤命名赋值），数字参与的才补
        "总量=单价×数量，即 100 * 3 = 300",
        spaceCalcOps("总量=单价×数量，即 100*3=300")
    )
    @Test fun t12_empty() = assertEquals("", spaceCalcOps(""))
}
