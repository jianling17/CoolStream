package com.example.mychat

import androidx.compose.ui.graphics.Color
import com.example.mychat.ui.theme.DiffAddedBackground
import com.example.mychat.ui.theme.DiffAddedText
import com.example.mychat.ui.theme.DiffRemovedBackground
import com.example.mychat.ui.theme.DiffRemovedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ v1.4.0 战役三：代码块行号 / 语言标签 / Diff 红绿高亮。
 *
 * 沿用本项目测试的硬约定：源码里不写反斜杠转义，特殊字符一律用字符码拼 ——
 * 写文件的工具链会把转义序列折成真换行，把字符串字面量从中间劈开。
 */
class CodeBlockRenderTest {

    private val BT = 96.toChar().toString()    // 反引号
    private val NL = 10.toChar().toString()    // 换行
    private val FENCE = BT + BT + BT

    private fun hex(c: Color): String = c.value.toString(16)

    // ---------------- 第一步：围栏语言标签 ----------------

    @Test
    fun fenceLanguageReadsKotlin() {
        assertEquals("kotlin", fenceLanguage(FENCE + "kotlin"))
    }

    @Test
    fun fenceWithoutLanguageIsEmpty() {
        assertEquals("", fenceLanguage(FENCE))
    }

    @Test
    fun fenceLanguageTrimsLeadingSpace() {
        assertEquals("py", fenceLanguage(FENCE + " py"))
    }

    @Test
    fun fenceLanguageLowercases() {
        assertEquals("kotlin", fenceLanguage(FENCE + "Kotlin"))
    }

    @Test
    fun fenceLanguageKeepsSymbols() {
        assertEquals("c++", fenceLanguage(FENCE + "c++"))
    }

    @Test
    fun fenceLanguageSurvivesIndent() {
        assertEquals("js", fenceLanguage("   " + FENCE + "js   "))
    }

    @Test
    fun fenceLanguageIsCapped() {
        val long = "abcdefghijklmnopqrstuvwxyz"
        assertEquals(16, fenceLanguage(FENCE + long).length)
    }

    // ---------------- 行号 ----------------

    @Test
    fun emptyCodeStillCountsOneLine() {
        assertEquals(1, codeLineCount(""))
    }

    @Test
    fun singleLineCountsOne() {
        assertEquals(1, codeLineCount("val a = 1"))
    }

    @Test
    fun twoLinesCountTwo() {
        assertEquals(2, codeLineCount("a" + NL + "b"))
    }

    @Test
    fun trailingNewlineStillCountsTwo() {
        assertEquals(2, codeLineCount("a" + NL))
    }

    @Test
    fun lineNumbersStartAtOne() {
        assertEquals("1", lineNumberText(1))
    }

    @Test
    fun lineNumbersAreNewlineSeparated() {
        assertEquals("1" + NL + "2" + NL + "3", lineNumberText(3))
    }

    @Test
    fun lineNumberTextNeverEmpty() {
        assertEquals("1", lineNumberText(0))
    }

    @Test
    fun lineNumberTextMatchesCodeLineCount() {
        val code = "a" + NL + "b" + NL + "c" + NL + "d"
        val nums = lineNumberText(codeLineCount(code))
        assertEquals(codeLineCount(code), nums.split(NL).size)
    }

    // ---------------- Diff 行判定 ----------------

    @Test
    fun plusLineIsAdded() {
        assertEquals(1, diffLineKind("+val x = 1"))
    }

    @Test
    fun minusLineIsRemoved() {
        assertEquals(-1, diffLineKind("-val x = 1"))
    }

    @Test
    fun triplePlusIsHeaderNotContent() {
        assertEquals(0, diffLineKind("+++ b/Main.kt"))
    }

    @Test
    fun tripleMinusIsHeaderNotContent() {
        assertEquals(0, diffLineKind("--- a/Main.kt"))
    }

    @Test
    fun doublePlusIsStillAdded() {
        assertEquals(1, diffLineKind("++x"))
    }

    @Test
    fun doubleMinusIsStillRemoved() {
        assertEquals(-1, diffLineKind("--x"))
    }

    @Test
    fun plainLineIsNormal() {
        assertEquals(0, diffLineKind(" val x = 1"))
    }

    @Test
    fun emptyLineIsNormal() {
        assertEquals(0, diffLineKind(""))
    }

    @Test
    fun kotlinCommentIsNotRemoved() {
        assertEquals(0, diffLineKind("// note"))
    }

    // ---------------- Diff 区间 ----------------

    @Test
    fun emptyCodeHasNoDiffSpans() {
        assertTrue(diffSpanStyles("").isEmpty())
    }

    @Test
    fun cleanCodeHasNoDiffSpans() {
        assertTrue(diffSpanStyles("val a = 1" + NL + "val b = 2").isEmpty())
    }

    @Test
    fun addedLineProducesOneSpan() {
        val spans = diffSpanStyles("+val a = 1")
        assertEquals(1, spans.size)
        assertEquals(0, spans[0].start)
        assertEquals(10, spans[0].end)
    }

    @Test
    fun spanCoversOnlyItsOwnLine() {
        val code = "head" + NL + "+added" + NL + "tail"
        val spans = diffSpanStyles(code)
        assertEquals(1, spans.size)
        assertEquals(5, spans[0].start)
        assertEquals(11, spans[0].end)
    }

    @Test
    fun addedSpanUsesGreenPalette() {
        val spans = diffSpanStyles("+x")
        assertEquals(hex(DiffAddedText), hex(spans[0].item.color))
        assertEquals(hex(DiffAddedBackground), hex(spans[0].item.background))
    }

    @Test
    fun removedSpanUsesRedPalette() {
        val spans = diffSpanStyles("-x")
        assertEquals(hex(DiffRemovedText), hex(spans[0].item.color))
        assertEquals(hex(DiffRemovedBackground), hex(spans[0].item.background))
    }

    @Test
    fun mixedDiffProducesTwoSpans() {
        val code = "+a" + NL + "-b" + NL + " c"
        val spans = diffSpanStyles(code)
        assertEquals(2, spans.size)
        assertEquals(0, spans[0].start)
        assertEquals(2, spans[0].end)
        assertEquals(3, spans[1].start)
        assertEquals(5, spans[1].end)
    }

    @Test
    fun diffHeadersProduceNoSpans() {
        val code = "+++ a/x" + NL + "--- b/x"
        assertTrue(diffSpanStyles(code).isEmpty())
    }

    @Test
    fun lonePlusMarkerStillHighlighted() {
        val spans = diffSpanStyles("+")
        assertEquals(1, spans.size)
        assertEquals(1, spans[0].end)
    }

    // ---------------- 与 parseMarkdown 的接线 ----------------

    @Test
    fun fencedBlockCarriesLanguage() {
        val src = FENCE + "kotlin" + NL + "val a = 1" + NL + FENCE
        val blocks = parseMarkdown(src)
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.lang)
        assertEquals("val a = 1", code.code)
    }

    @Test
    fun fencedBlockWithoutLanguageHasEmptyLang() {
        val src = FENCE + NL + "val a = 1" + NL + FENCE
        val blocks = parseMarkdown(src)
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("", code.lang)
    }

    @Test
    fun truncatedStreamKeepsLanguage() {
        val src = FENCE + "python" + NL + "def f():" + NL + "    pass"
        val blocks = parseMarkdown(src)
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("python", code.lang)
    }
}
