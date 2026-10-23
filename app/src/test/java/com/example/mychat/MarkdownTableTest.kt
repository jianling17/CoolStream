package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 表格的解析测试：tableCellsOf / isTableSeparatorLine / parseMarkdown。
 *
 * 表格是流式回复里最容易把整段渲染搞崩的东西：模型经常给出列数不一致的表格，
 * 短行少一格就会让下面所有行错位。所以「补齐到统一列数」是这里的核心断言。
 *
 * ⚠️ 本文件不用反斜杠转义，需要反斜杠时用字符码拼。
 */
class MarkdownTableTest {

    private val PIPE = 124.toChar().toString()
    private val BS = 92.toChar().toString()
    private val NL = 10.toChar().toString()

    private fun md(vararg lines: String): String = lines.joinToString(NL)

    private fun row(vararg cells: String): String =
        PIPE + " " + cells.joinToString(" " + PIPE + " ") + " " + PIPE

    // ---------------- tableCellsOf ----------------

    @Test
    fun cells_basic() {
        assertEquals(listOf("a", "b", "c"), tableCellsOf("| a | b | c |"))
    }

    @Test
    fun cells_noLeadingPipe_returnsNull() {
        assertNull(tableCellsOf("a | b |"))
    }

    @Test
    fun cells_noTrailingPipe_returnsNull() {
        assertNull(tableCellsOf("| a | b"))
    }

    @Test
    fun cells_plainText_returnsNull() {
        assertNull(tableCellsOf("就是一句普通的话"))
    }

    @Test
    fun cells_tooShort_returnsNull() {
        assertNull(tableCellsOf("||"))
        assertNull(tableCellsOf("|"))
    }

    @Test
    fun cells_singleCell() {
        assertEquals(listOf("only"), tableCellsOf("|only|"))
    }

    @Test
    fun cells_emptyCellsKept() {
        assertEquals(listOf("a", "", "c"), tableCellsOf("| a |  | c |"))
    }

    @Test
    fun cells_trimsSpaces() {
        assertEquals(listOf("a", "b"), tableCellsOf("   |   a   |   b   |   "))
    }

    @Test
    fun cells_escapedPipeKeptInsideCell() {
        assertEquals(listOf("a|b", "c"), tableCellsOf("| a" + BS + "|b | c |"))
    }

    @Test
    fun cells_trailingBackslashNotEscaping() {
        assertEquals(listOf("a" + BS), tableCellsOf("| a" + BS + " |"))
    }

    // ---------------- isTableSeparatorLine ----------------

    @Test
    fun sep_standard() {
        assertTrue(isTableSeparatorLine("|---|---|"))
    }

    @Test
    fun sep_withAlignmentColons() {
        assertTrue(isTableSeparatorLine("|:--|--:|:-:|"))
    }

    @Test
    fun sep_singleDash() {
        assertTrue(isTableSeparatorLine("| - | - |"))
    }

    @Test
    fun sep_containsLetters_false() {
        assertTrue(!isTableSeparatorLine("|---|x|"))
    }

    @Test
    fun sep_noPipes_false() {
        assertTrue(!isTableSeparatorLine("---"))
    }

    @Test
    fun sep_plainHeaderRow_false() {
        assertTrue(!isTableSeparatorLine("| 区域 | 销售额 |"))
    }

    // ---------------- parseMarkdown ----------------

    @Test
    fun parse_tableBlock() {
        val text = md(
            row("区域", "销售额"),
            "|---|---|",
            row("华东", "520"),
            row("华南", "310")
        )
        val blocks = parseMarkdown(text)
        assertEquals(1, blocks.size)
        val t = blocks[0] as MdBlock.Table
        assertEquals(listOf("区域", "销售额"), t.header)
        assertEquals(listOf(listOf("华东", "520"), listOf("华南", "310")), t.rows)
    }

    @Test
    fun parse_shortRowPaddedToHeaderWidth() {
        val text = md(row("a", "b", "c"), "|---|---|---|", row("only"))
        val t = parseMarkdown(text)[0] as MdBlock.Table
        assertEquals(listOf("a", "b", "c"), t.header)
        assertEquals(listOf(listOf("only", "", "")), t.rows)
    }

    @Test
    fun parse_longRowWidensHeader() {
        val text = md(row("a"), "|---|", row("x", "y"))
        val t = parseMarkdown(text)[0] as MdBlock.Table
        assertEquals(listOf("a", ""), t.header)
        assertEquals(listOf(listOf("x", "y")), t.rows)
    }

    @Test
    fun parse_tableWithoutSeparator_isParagraph() {
        val blocks = parseMarkdown(row("a", "b"))
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun parse_paragraphBeforeTableFlushed() {
        val text = md("先看这张表：", "", row("a", "b"), "|---|---|", row("1", "2"))
        val blocks = parseMarkdown(text)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Table)
    }

    @Test
    fun parse_tableThenParagraph() {
        val text = md(row("a", "b"), "|---|---|", row("1", "2"), "", "表格结束了。")
        val blocks = parseMarkdown(text)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Table)
        val p = blocks[1] as MdBlock.Paragraph
        assertEquals("表格结束了。", p.text)
    }

    @Test
    fun parse_tableWithoutBodyRows() {
        val text = md(row("a", "b"), "|---|---|")
        val t = parseMarkdown(text)[0] as MdBlock.Table
        assertEquals(listOf("a", "b"), t.header)
        assertTrue(t.rows.isEmpty())
    }

    @Test
    fun parse_codeBlockContainingPipes_staysCode() {
        val text = md("```", "a | b", "```")
        val blocks = parseMarkdown(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Code)
    }

    @Test
    fun parse_headerLineThenNormalText_noTable() {
        val text = md(row("a", "b"), "普通一行")
        val blocks = parseMarkdown(text)
        assertTrue(blocks.none { it is MdBlock.Table })
    }
}
