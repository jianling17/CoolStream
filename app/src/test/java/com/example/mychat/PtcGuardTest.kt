package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * run_code 沙箱的「执行前检查」三件套测试。
 *
 * 为什么这层最值得测：这里的每一条规则都对应一次真实翻车。
 *   · 模板字符串不插值 —— 不报错，静默给出错答案，最难查
 *   · for (const x of arr) —— 直接抛引擎异常
 *   · ?. / ?? / ... —— 引擎不认，报 syntax error 还得靠猜行号
 * 所以判据是：该拦的必须拦下且指出行号，不该拦的（字符串/注释里的）必须放行。
 *
 * ⚠️ 本文件不用反斜杠转义，特殊字符一律用字符码常量拼。
 */
class PtcGuardTest {

    private val NL = 10.toChar().toString()
    private val DQ = 34.toChar().toString()
    private val SQ = 39.toChar().toString()
    private val BS = 92.toChar().toString()
    private val D = 36.toChar().toString()
    private val BT = 96.toChar().toString()

    // ==================== maskJsLiterals ====================

    @Test
    fun mask_lineCommentContent_blanked() {
        val src = """var a = 1; // hello world"""
        val m = maskJsLiterals(src)
        assertFalse(m.contains("hello"))
        assertEquals(src.length, m.length)
    }

    @Test
    fun mask_lineCommentMarkers_alsoBlanked() {
        val m = maskJsLiterals("""var a = 1; // x""")
        assertFalse(m.contains("//"))
    }

    @Test
    fun mask_blockComment_blanked() {
        val src = """/* secret */ var a = 1;"""
        val m = maskJsLiterals(src)
        assertFalse(m.contains("secret"))
        assertFalse(m.contains("/*"))
        assertEquals(src.length, m.length)
    }

    @Test
    fun mask_singleQuotedString_blanked() {
        val m = maskJsLiterals("""var a = 'secret';""")
        assertFalse(m.contains("secret"))
    }

    @Test
    fun mask_doubleQuotedString_blanked() {
        val m = maskJsLiterals("""var a = "secret";""")
        assertFalse(m.contains("secret"))
    }

    @Test
    fun mask_quoteCharsAlsoBlanked() {
        val m = maskJsLiterals("""var a = "x";""")
        assertFalse(m.contains(DQ))
    }

    @Test
    fun mask_backtickString_notBlanked() {
        val m = maskJsLiterals("var a = " + BT + "keep" + BT + ";")
        assertTrue(m.contains("keep"))
    }

    @Test
    fun mask_lengthPreserved() {
        val src = """var a = "x"; // c""" + NL + """var b = 'y';"""
        assertEquals(src.length, maskJsLiterals(src).length)
    }

    @Test
    fun mask_newlinesPreserved() {
        val src = "a" + NL + "b" + NL + "c"
        val m = maskJsLiterals(src)
        assertEquals(src.count { it == NL[0] }, m.count { it == NL[0] })
    }

    @Test
    fun mask_commentInsideString_notTreatedAsComment() {
        val m = maskJsLiterals("""var u = "http://x"; var b = 2;""")
        assertFalse(m.contains("http"))
        assertTrue(m.contains("var b = 2"))
    }

    @Test
    fun mask_escapedQuoteInsideString_scanContinues() {
        val src = "var a = " + DQ + "a" + BS + DQ + "b" + DQ + "; var c = 1;"
        val m = maskJsLiterals(src)
        assertTrue(m.contains("var c = 1"))
    }

    @Test
    fun mask_emptySource_returnsEmpty() {
        assertEquals("", maskJsLiterals(""))
    }

    @Test
    fun mask_noLiterals_unchanged() {
        val src = "var a = 1;"
        assertEquals(src, maskJsLiterals(src))
    }

    // ==================== lineAt ====================

    private val three = "abc" + NL + "def" + NL + "ghi"

    @Test
    fun lineAt_index0_isLine1() {
        assertEquals(1, lineAt(three, 0))
    }

    @Test
    fun lineAt_midFirstLine_isLine1() {
        assertEquals(1, lineAt(three, 2))
    }

    @Test
    fun lineAt_afterFirstNewline_isLine2() {
        assertEquals(2, lineAt(three, 4))
    }

    @Test
    fun lineAt_afterSecondNewline_isLine3() {
        assertEquals(3, lineAt(three, 8))
    }

    @Test
    fun lineAt_negativeIndex_clampsToLine1() {
        assertEquals(1, lineAt(three, -5))
    }

    @Test
    fun lineAt_indexBeyondEnd_clampsToLastLine() {
        assertEquals(3, lineAt(three, 999))
    }

    @Test
    fun lineAt_emptySource_isLine1() {
        assertEquals(1, lineAt("", 0))
    }

    // ==================== ptcPreflight ====================

    @Test
    fun ptc_cleanCode_passes() {
        val code = "var a = 1;" + NL + "var b = a + 2;" + NL + "print(b);"
        assertNull(ptcPreflight(code))
    }

    @Test
    fun ptc_emptyCode_passes() {
        assertNull(ptcPreflight(""))
    }

    @Test
    fun ptc_templateInterpolation_blocked() {
        val code = "var s = " + BT + "a" + D + "{b}" + BT + ";"
        val msg = ptcPreflight(code)
        assertNotNull(msg)
        assertTrue(msg!!.contains("模板字符串"))
    }

    @Test
    fun ptc_templateWithoutInterpolation_passes() {
        assertNull(ptcPreflight("var s = " + BT + "abc" + BT + ";"))
    }

    @Test
    fun ptc_forOfConst_blocked() {
        val msg = ptcPreflight("for (const f of files) { print(f); }")
        assertNotNull(msg)
        assertTrue(msg!!.contains("for-of"))
    }

    @Test
    fun ptc_forOfLet_passes() {
        assertNull(ptcPreflight("for (let f of files) { print(f); }"))
    }

    @Test
    fun ptc_optionalChaining_blocked() {
        val msg = ptcPreflight("var x = a?.b;")
        assertNotNull(msg)
        assertTrue(msg!!.contains("可选链"))
    }

    @Test
    fun ptc_optionalChainingInString_passes() {
        assertNull(ptcPreflight("print(" + DQ + "a?.b" + DQ + ");"))
    }

    @Test
    fun ptc_nullishCoalesce_blocked() {
        val msg = ptcPreflight("var x = a ?? b;")
        assertNotNull(msg)
        assertTrue(msg!!.contains("??"))
    }

    @Test
    fun ptc_nullishInString_passes() {
        assertNull(ptcPreflight("print(" + DQ + "a ?? b" + DQ + ");"))
    }

    @Test
    fun ptc_spread_blocked() {
        val msg = ptcPreflight("var all = a.concat(b); var c = [...a];")
        assertNotNull(msg)
        assertTrue(msg!!.contains("展开"))
    }

    @Test
    fun ptc_spreadInString_passes() {
        assertNull(ptcPreflight("print(" + DQ + "x...y" + DQ + ");"))
    }

    @Test
    fun ptc_lineNumberReported() {
        val code = "var a = 1;" + NL + "var b = 2;" + NL + "var c = a?.b;"
        val msg = ptcPreflight(code)
        assertNotNull(msg)
        assertTrue(msg!!.contains("第 3 行"))
    }

    @Test
    fun ptc_badSyntaxInLineComment_passes() {
        assertNull(ptcPreflight("// a?.b ?? c"))
    }

    @Test
    fun ptc_badSyntaxInBlockComment_passes() {
        assertNull(ptcPreflight("/* a ?? b */"))
    }
}
