package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具参数解析的单元测试（argOf / argBoolOf / cleanPath）。
 *
 * 为什么值得单独测这一层：
 *   模型给的 arguments 是「模型自己写的 JSON」，畸形输入是常态 ——
 *   少个引号、值写成 null、把 true 写成字符串 "true"、路径里混进中文引号。
 *   一旦抛异常整个工具调用就崩；一旦宽松解析，就可能把 replace_all 误判成
 *   true 而误改文件。这里把「宁可保守」钉死。
 *
 * ⚠️ 本文件刻意不用反斜杠转义：所有特殊字符都由字符码构造，
 *    免得转义层数一多就看不出到底在测什么。
 */
class ToolArgsTest {

    private val DQ = 34.toChar().toString()    // 双引号
    private val SQ = 39.toChar().toString()    // 单引号
    private val NL = 10.toChar().toString()    // 换行
    private val TAB = 9.toChar().toString()    // 制表符
    private val BS = 92.toChar().toString()    // 反斜杠

    // ==================== argOf ====================

    @Test
    fun argOf_returnsStringValue() {
        assertEquals("/sdcard/a.txt", argOf("{\"path\":\"/sdcard/a.txt\"}", "path"))
    }

    @Test
    fun argOf_missingKey_returnsEmpty() {
        assertEquals("", argOf("{\"path\":\"/a\"}", "content"))
    }

    @Test
    fun argOf_nullValue_returnsEmpty() {
        assertEquals("", argOf("{\"path\":null}", "path"))
    }

    @Test
    fun argOf_numberValue_returnsEmpty() {
        assertEquals("", argOf("{\"path\":123}", "path"))
    }

    @Test
    fun argOf_booleanValue_returnsEmpty() {
        assertEquals("", argOf("{\"path\":true}", "path"))
    }

    @Test
    fun argOf_objectValue_returnsEmpty() {
        assertEquals("", argOf("{\"path\":{\"a\":1}}", "path"))
    }

    @Test
    fun argOf_arrayValue_returnsEmpty() {
        assertEquals("", argOf("{\"path\":[1,2]}", "path"))
    }

    @Test
    fun argOf_emptyString_returnsEmpty() {
        assertEquals("", argOf("{\"path\":\"\"}", "path"))
    }

    @Test
    fun argOf_emptyJson_returnsEmpty() {
        assertEquals("", argOf("{}", "path"))
    }

    @Test
    fun argOf_malformedJson_returnsEmpty() {
        assertEquals("", argOf("{\"path\":\"/a\"", "path"))
    }

    @Test
    fun argOf_blankJson_returnsEmpty() {
        assertEquals("", argOf("", "path"))
    }

    @Test
    fun argOf_unicodeValue_preserved() {
        assertEquals("你好世界", argOf("{\"q\":\"你好世界\"}", "q"))
    }

    @Test
    fun argOf_escapedQuoteInValue_preserved() {
        val json = "{" + DQ + "q" + DQ + ":" + DQ + "a" + BS + DQ + "b" + DQ + "}"
        assertEquals("a" + DQ + "b", argOf(json, "q"))
    }

    @Test
    fun argOf_emptyKey_returnsEmpty() {
        assertEquals("", argOf("{\"path\":\"/a\"}", ""))
    }

    @Test
    fun argOf_nestedPath_picksTopLevelOnly() {
        assertEquals("", argOf("{\"outer\":{\"path\":\"/x\"}}", "path"))
    }

    @Test
    fun argOf_longPath_preserved() {
        val p = "/sdcard/" + "a".repeat(500) + ".txt"
        assertEquals(p, argOf("{\"path\":\"" + p + "\"}", "path"))
    }

    // ==================== argBoolOf ====================

    @Test
    fun argBoolOf_true_returnsTrue() {
        assertTrue(argBoolOf("{\"replace_all\":true}", "replace_all"))
    }

    @Test
    fun argBoolOf_false_returnsFalse() {
        assertFalse(argBoolOf("{\"replace_all\":false}", "replace_all"))
    }

    @Test
    fun argBoolOf_stringTrue_returnsFalse() {
        assertFalse(argBoolOf("{\"replace_all\":\"true\"}", "replace_all"))
    }

    @Test
    fun argBoolOf_numberOne_returnsFalse() {
        assertFalse(argBoolOf("{\"replace_all\":1}", "replace_all"))
    }

    @Test
    fun argBoolOf_missingKey_returnsFalse() {
        assertFalse(argBoolOf("{}", "replace_all"))
    }

    @Test
    fun argBoolOf_nullValue_returnsFalse() {
        assertFalse(argBoolOf("{\"replace_all\":null}", "replace_all"))
    }

    @Test
    fun argBoolOf_malformedJson_returnsFalse() {
        assertFalse(argBoolOf("{\"replace_all\":tru", "replace_all"))
    }

    @Test
    fun argBoolOf_emptyJson_returnsFalse() {
        assertFalse(argBoolOf("", "replace_all"))
    }

    // ==================== cleanPath ====================

    @Test
    fun cleanPath_plainPath_unchanged() {
        assertEquals("/sdcard/a.txt", cleanPath("/sdcard/a.txt"))
    }

    @Test
    fun cleanPath_trimsWhitespace() {
        assertEquals("/a.txt", cleanPath("   /a.txt   "))
    }

    @Test
    fun cleanPath_trimsAsciiDoubleQuotes() {
        assertEquals("/a.txt", cleanPath(DQ + "/a.txt" + DQ))
    }

    @Test
    fun cleanPath_trimsAsciiSingleQuotes() {
        assertEquals("/a.txt", cleanPath(SQ + "/a.txt" + SQ))
    }

    @Test
    fun cleanPath_trimsChineseDoubleQuotes() {
        assertEquals("/a.txt", cleanPath("“/a.txt”"))
    }

    @Test
    fun cleanPath_trimsChineseSingleQuotes() {
        assertEquals("/a.txt", cleanPath("‘/a.txt’"))
    }

    @Test
    fun cleanPath_trimsChineseComma() {
        assertEquals("/a.txt", cleanPath("/a.txt，"))
    }

    @Test
    fun cleanPath_trimsChinesePeriod() {
        assertEquals("/a.txt", cleanPath("/a.txt。"))
    }

    @Test
    fun cleanPath_trimsColons() {
        assertEquals("/a.txt", cleanPath("/a.txt:"))
    }

    @Test
    fun cleanPath_trimsNewlineAndTab() {
        assertEquals("/a.txt", cleanPath(NL + TAB + "/a.txt" + TAB + NL))
    }

    @Test
    fun cleanPath_allPunctuation_returnsEmpty() {
        assertEquals("", cleanPath("“”‘’，。、；：" + SQ + DQ))
    }

    @Test
    fun cleanPath_emptyString_returnsEmpty() {
        assertEquals("", cleanPath(""))
    }

    @Test
    fun cleanPath_blankString_returnsEmpty() {
        assertEquals("", cleanPath("     "))
    }

    @Test
    fun cleanPath_keepsInnerQuotes() {
        assertEquals("a" + DQ + "b.txt", cleanPath("a" + DQ + "b.txt"))
    }

    @Test
    fun cleanPath_keepsInnerSpaces() {
        assertEquals("/my dir/a.txt", cleanPath("/my dir/a.txt"))
    }

    @Test
    fun cleanPath_windowsStylePath_kept() {
        val p = "C:" + BS + "Users" + BS + "a.txt"
        assertEquals(p, cleanPath(p))
    }
}
