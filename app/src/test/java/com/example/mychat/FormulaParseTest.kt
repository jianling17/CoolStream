package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ v1.4.0 战役二：块级公式 $$ ... $$ 的解析。
 *
 * 测试文件里一律不写反斜杠转义 —— 历史教训：写文件的工具链会把 \\n 折成真换行，
 * 把字符串字面量从中间劈开。特殊字符全部用字符码拼。
 */
class FormulaParseTest {

    private val D = 36.toChar().toString()    // 美元符
    private val NL = 10.toChar().toString()   // 换行
    private val DD = D + D                    // $$

    private fun formulaAt(blocks: List<MdBlock>, i: Int): String =
        (blocks[i] as MdBlock.Formula).text

    // ---------------- 基本识别 ----------------

    @Test
    fun singleLineFormulaIsOneBlock() {
        val blocks = parseMarkdown(DD + "E = mc^2" + DD)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Formula)
        assertEquals("E = mc^2", formulaAt(blocks, 0))
    }

    @Test
    fun multiLineFormulaIsOneBlock() {
        val blocks = parseMarkdown(DD + NL + "a + b" + NL + DD)
        assertEquals(1, blocks.size)
        assertEquals("a + b", formulaAt(blocks, 0))
    }

    @Test
    fun formulaOpeningWithContentOnSameLine() {
        val blocks = parseMarkdown(DD + "x^2" + NL + "+ 1" + NL + DD)
        assertEquals(1, blocks.size)
        assertEquals("x^2" + NL + "+ 1", formulaAt(blocks, 0))
    }

    // ---------------- 与其它块混排 ----------------

    @Test
    fun paragraphBeforeFormulaIsFlushed() {
        val blocks = parseMarkdown("公式如下：" + NL + DD + "x = 1" + DD)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Formula)
        assertEquals("x = 1", formulaAt(blocks, 1))
    }

    @Test
    fun paragraphAfterFormulaIsKept() {
        val blocks = parseMarkdown(DD + "x" + DD + NL + NL + "后面的说明")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Formula)
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }

    @Test
    fun headingAfterFormulaIsKept() {
        val blocks = parseMarkdown(DD + "x" + DD + NL + "## 推导" )
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Formula)
        assertTrue(blocks[1] is MdBlock.Heading)
    }

    // ---------------- 不该被当成公式的情况 ----------------

    @Test
    fun pricesAreNotFormula() {
        val blocks = parseMarkdown("价格 " + D + "5 到 " + D + "10 之间")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun formulaInsideCodeBlockStaysCode() {
        val fence = 96.toChar().toString() + 96.toChar().toString() + 96.toChar().toString()
        val src = fence + NL + DD + "x" + DD + NL + fence
        val blocks = parseMarkdown(src)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Code)
        assertEquals(DD + "x" + DD, (blocks[0] as MdBlock.Code).code)
    }

    @Test
    fun emptyFormulaIsDropped() {
        val blocks = parseMarkdown(DD + DD)
        assertEquals(0, blocks.size)
    }

    // ---------------- 流式截断 ----------------

    @Test
    fun unclosedFormulaIsStillShown() {
        val blocks = parseMarkdown(DD + NL + "a + b")
        assertEquals(1, blocks.size)
        assertEquals("a + b", formulaAt(blocks, 0))
    }

    @Test
    fun unclosedSingleLineFormulaIsStillShown() {
        val blocks = parseMarkdown(DD + "x + y")
        assertEquals(1, blocks.size)
        assertEquals("x + y", formulaAt(blocks, 0))
    }
}
