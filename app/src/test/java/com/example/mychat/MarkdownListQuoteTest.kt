package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.5.0 模块三：parseMarkdown 的列表与引用块识别 */
class MarkdownListQuoteTest {
    private val NL = 10.toChar().toString()

    private fun lines(vararg s: String) = s.joinToString(NL)

    // ---------- unorderedItemOf ----------

    @Test fun uDash() { assertEquals("苹果", unorderedItemOf("- 苹果")) }
    @Test fun uStar() { assertEquals("苹果", unorderedItemOf("* 苹果")) }
    @Test fun uPlus() { assertEquals("苹果", unorderedItemOf("+ 苹果")) }
    @Test fun uIndent() { assertEquals("苹果", unorderedItemOf("  - 苹果")) }
    @Test fun uTab() { assertEquals("苹果", unorderedItemOf("-" + String(charArrayOf(9.toChar())) + "苹果")) }
    @Test fun uNoSpace() { assertNull(unorderedItemOf("-苹果")) }
    @Test fun uEmpty() { assertNull(unorderedItemOf("- ")) }
    @Test fun uWordDash() { assertNull(unorderedItemOf("a- 苹果")) }
    @Test fun uHr() { assertNull(unorderedItemOf("---")) }
    @Test fun uBold() { assertNull(unorderedItemOf("**bold** text")) }
    @Test fun uArrow() { assertNull(unorderedItemOf("-> 箭头")) }

    // ---------- orderedItemOf ----------

    @Test fun oBasic() { assertEquals(Pair("1", "第一步"), orderedItemOf("1. 第一步")) }
    @Test fun oParen() { assertEquals(Pair("12", "x"), orderedItemOf("12) x")) }
    @Test fun oMulti() { assertEquals(Pair("99", "x"), orderedItemOf("99. x")) }
    @Test fun oIndent() { assertEquals(Pair("1", "x"), orderedItemOf("  1. x")) }
    @Test fun oNoSep() { assertNull(orderedItemOf("1 第一步")) }
    @Test fun oNoSpace() { assertNull(orderedItemOf("1.第一步")) }
    @Test fun oEmpty() { assertNull(orderedItemOf("1. ")) }
    @Test fun oDecimal() { assertNull(orderedItemOf("1.5 是小数")) }
    @Test fun oVersion() { assertNull(orderedItemOf("2.13.0 版本号")) }

    // ---------- quoteLineOf ----------

    @Test fun qBasic() { assertEquals("引用内容", quoteLineOf("> 引用内容")) }
    @Test fun qNoSpace() { assertEquals("引用内容", quoteLineOf(">引用内容")) }
    @Test fun qEmpty() { assertEquals("", quoteLineOf(">")) }
    @Test fun qNot() { assertNull(quoteLineOf("a > b")) }

    // ---------- parseMarkdown 集成 ----------

    @Test fun uListBlock() {
        val blocks = parseMarkdown(lines("- 苹果", "* 香蕉", "+ 橘子"))
        assertEquals(1, blocks.size)
        val b = blocks[0] as MdBlock.ListItem
        assertEquals(false, b.ordered)
        assertEquals(listOf("苹果", "香蕉", "橘子"), b.items)
    }

    @Test fun oListBlock() {
        val blocks = parseMarkdown(lines("1. 苹果", "2. 香蕉", "3. 橘子"))
        val b = blocks[0] as MdBlock.ListItem
        assertEquals(true, b.ordered)
        assertEquals(3, b.items.size)
    }

    @Test fun oListNonSequential() {
        val blocks = parseMarkdown(lines("10. x", "99. y"))
        val b = blocks[0] as MdBlock.ListItem
        assertEquals(listOf("x", "y"), b.items)
    }

    @Test fun quoteBlockMultiLine() {
        val blocks = parseMarkdown(lines("> 第一行", "> 第二行"))
        assertEquals(1, blocks.size)
        assertEquals("第一行" + NL + "第二行", (blocks[0] as MdBlock.Quote).text)
    }

    @Test fun listBreaksOnParagraph() {
        val blocks = parseMarkdown(lines("- 苹果", "香蕉"))
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.ListItem)
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }

    @Test fun unorderedBreaksOnOrdered() {
        val blocks = parseMarkdown(lines("- a", "1. b"))
        assertEquals(2, blocks.size)
        assertEquals(false, (blocks[0] as MdBlock.ListItem).ordered)
        assertEquals(true, (blocks[1] as MdBlock.ListItem).ordered)
    }

    @Test fun quoteBreaksOnBlank() {
        val blocks = parseMarkdown(lines("> a", "", "> b"))
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Quote && blocks[1] is MdBlock.Quote)
    }

    @Test fun listAfterParagraphFlushes() {
        val blocks = parseMarkdown(lines("正文", "- a"))
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.ListItem)
    }

    @Test fun headingInsideListArea() {
        val blocks = parseMarkdown(lines("- a", "## 标题", "- b"))
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MdBlock.Heading)
    }

    @Test fun inlineInListItem() {
        val blocks = parseMarkdown(lines("- 有 **加粗** 内容"))
        val b = blocks[0] as MdBlock.ListItem
        assertEquals("有 **加粗** 内容", b.items[0])
    }

    // 负号负数不被误判：-5 是段落文本
    @Test fun negativeNumberIsParagraph() {
        val blocks = parseMarkdown(lines("-5 度"))
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test fun tableStillWorks() {
        val blocks = parseMarkdown(lines("| a | b |", "|---|---|", "| 1 | 2 |"))
        assertTrue(blocks[0] is MdBlock.Table)
    }
}
