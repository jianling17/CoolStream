package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「<<DOC:begin|name|count>> … <<DOC:end>>」哨兵块的测试。
 *
 * 这套哨兵解决了两个必须同时满足的诉求：
 *   · 发给模型的正文里要带文档全文（模型要读）
 *   · 界面上只该显示那张「📄 已读取文档」小卡片（用户不想看一万字）
 * 所以 stripDocBlocks / docCardsOf 必须严格互逆地工作。
 *
 * 最容易翻车的是「只有开头没有结尾」的老数据：必须把余下全部丢掉，
 * 否则一万字原文会直接糊到气泡里。
 *
 * ⚠️ 本文件不用反斜杠转义，换行用字符码拼。
 */
class DocSentinelTest {

    private val NL = 10.toChar().toString()

    private fun block(name: String, count: Int, body: String): String =
        "<<DOC:begin|" + name + "|" + count + ">>" + NL + body + NL + DOC_END

    // ==================== stripDocBlocks ====================

    @Test
    fun strip_noSentinel_identity() {
        assertEquals("你好", stripDocBlocks("你好"))
    }

    @Test
    fun strip_angleBracketsWithoutSentinel_identity() {
        assertEquals("a >> b << c", stripDocBlocks("a >> b << c"))
    }

    @Test
    fun strip_onlyBlock_returnsEmpty() {
        assertEquals("", stripDocBlocks(block("a.docx", 10, "正文正文")))
    }

    @Test
    fun strip_prefixBeforeBlock_kept() {
        val raw = "前言" + NL + block("a.docx", 10, "正文")
        assertEquals("前言" + NL, stripDocBlocks(raw))
    }

    @Test
    fun strip_suffixAfterBlock_kept() {
        val raw = block("a.docx", 10, "正文") + NL + "后语"
        assertEquals(NL + "后语", stripDocBlocks(raw))
    }

    @Test
    fun strip_blockInMiddle_bothSidesKept() {
        val raw = "前" + NL + block("a.docx", 10, "正文") + NL + "后"
        assertEquals("前" + NL + NL + "后", stripDocBlocks(raw))
    }

    @Test
    fun strip_twoBlocks_bothRemoved() {
        val raw = "A" + block("a.docx", 1, "x") + "B" + block("b.xlsx", 2, "y") + "C"
        assertEquals("ABC", stripDocBlocks(raw))
    }

    @Test
    fun strip_unclosedBlock_dropsRest() {
        val raw = "保留" + NL + "<<DOC:begin|a.docx|10>>" + NL + "一万字正文"
        assertEquals("保留" + NL, stripDocBlocks(raw))
    }

    @Test
    fun strip_emptyInput_returnsEmpty() {
        assertEquals("", stripDocBlocks(""))
    }

    // ==================== docCardsOf ====================

    @Test
    fun cards_noSentinel_emptyList() {
        assertTrue(docCardsOf("普通消息").isEmpty())
    }

    @Test
    fun cards_emptyInput_emptyList() {
        assertTrue(docCardsOf("").isEmpty())
    }

    @Test
    fun cards_singleBlock_parsed() {
        val cards = docCardsOf(block("报告.docx", 1234, "正文"))
        assertEquals(1, cards.size)
        assertEquals("报告.docx", cards[0].name)
        assertEquals(1234, cards[0].charCount)
    }

    @Test
    fun cards_twoBlocks_inOrder() {
        val raw = block("a.docx", 1, "x") + NL + block("b.xlsx", 2, "y")
        val cards = docCardsOf(raw)
        assertEquals(2, cards.size)
        assertEquals("a.docx", cards[0].name)
        assertEquals("b.xlsx", cards[1].name)
    }

    @Test
    fun cards_nonNumericCount_notMatched() {
        assertTrue(docCardsOf("<<DOC:begin|a.docx|abc>>").isEmpty())
    }

    @Test
    fun cards_nameWithSpaces_parsed() {
        val cards = docCardsOf(block("年度 总结.docx", 10, "x"))
        assertEquals(1, cards.size)
        assertEquals("年度 总结.docx", cards[0].name)
    }

    @Test
    fun cards_emptyName_parsed() {
        val cards = docCardsOf("<<DOC:begin||12>>")
        assertEquals(1, cards.size)
        assertEquals("", cards[0].name)
        assertEquals(12, cards[0].charCount)
    }

    @Test
    fun cards_largeCount_parsed() {
        val cards = docCardsOf(block("big.docx", 10000, "x"))
        assertEquals(10000, cards[0].charCount)
    }

    // ==================== unwrapDocBlocks（发给模型的路径） ====================

    @Test
    fun unwrap_noSentinel_identity() {
        assertEquals("你好", unwrapDocBlocks("你好"))
    }

    @Test
    fun unwrap_bodyKept_markersGone() {
        val out = unwrapDocBlocks(block("a.docx", 4, "正文正文"))
        assertTrue(out.contains("正文正文"))
        assertTrue(!out.contains("<<DOC:begin"))
        assertTrue(!out.contains(DOC_END))
    }

    @Test
    fun unwrap_fullPrompt_keepsQuestionAndBody() {
        val prompt = block("a.docx", 4, "正文正文") + NL + "问题：结论是什么"
        val out = unwrapDocBlocks(prompt)
        assertTrue(out.contains("正文正文"))
        assertTrue(out.contains("问题：结论是什么"))
        assertTrue(!out.contains("<<DOC:"))
    }

    @Test
    fun unwrap_twoBlocks_bothBodiesKept() {
        val raw = block("a.docx", 2, "甲甲") + NL + block("b.xlsx", 2, "乙乙")
        val out = unwrapDocBlocks(raw)
        assertTrue(out.contains("甲甲"))
        assertTrue(out.contains("乙乙"))
        assertTrue(!out.contains("<<DOC:"))
    }

    @Test
    fun unwrap_unclosedBlock_bodyKept() {
        // 发给模型时不能像界面那样把余下全丢：模型宁可多读也不能读不到
        val raw = "<<DOC:begin|a.docx|4>>" + NL + "正文正文"
        val out = unwrapDocBlocks(raw)
        assertTrue(out.contains("正文正文"))
        assertTrue(!out.contains("<<DOC:begin"))
    }

    @Test
    fun unwrap_bodyContainingAngleBrackets_kept() {
        val out = unwrapDocBlocks(block("a.docx", 6, "a >> b << c"))
        assertTrue(out.contains("a >> b << c"))
    }

    @Test
    fun unwrap_contrastWithStrip_stripDropsBody() {
        // 这条锁住两个函数的分工：界面丢正文，API 留正文
        val raw = block("a.docx", 4, "正文正文")
        assertEquals("", stripDocBlocks(raw))
        assertTrue(unwrapDocBlocks(raw).contains("正文正文"))
    }
}
