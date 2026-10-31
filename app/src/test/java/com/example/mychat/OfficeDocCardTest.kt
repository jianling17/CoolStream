package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.7.0 任务四：办公文档卡片解析 officeDocCardOf 的纯函数测试。 */
class OfficeDocCardTest {

    // ---------- 构造 JSON 的小工具：全部用字符码拼，绝不用反斜杠转义 ----------
    private val DQ = 34.toChar().toString()
    private val BS = 92.toChar().toString()

    /** 拼一条 TOOL_CALL content：路径里的反斜杠按 JSON 规则转义 */
    private fun contentOf(tool: String, ok: Boolean?, path: String?, output: String): String {
        val p = if (path == null) DQ + DQ else DQ + path.replace(DQ, BS + DQ) + DQ
        val okS = when (ok) { null -> "null"; true -> "true"; false -> "false" }
        val out = DQ + output.replace(DQ, BS + DQ) + DQ
        return "{" + DQ + "tool" + DQ + ":" + DQ + tool + DQ + "," +
            DQ + "ok" + DQ + ":" + okS + "," +
            DQ + "arguments" + DQ + ":{" + DQ + "path" + DQ + ":" + p + "}," +
            DQ + "output" + DQ + ":" + out + "}"
    }

    // ---------- 读取类 ----------

    @Test fun t01_readWordSuccess() {
        val c = contentOf("read_word", true, "/sdcard/Download/report.docx", "已读取 Word 文档：report.docx（1234 字符）")
        val card = officeDocCardOf(c)!!
        assertTrue(card.isRead)
        assertEquals("report.docx", card.name)
        assertEquals("1234 字符", card.subtitle)
        assertEquals("/sdcard/Download/report.docx", card.path)
    }

    @Test fun t02_readExcelSuccess() {
        val c = contentOf("read_excel", true, "/sdcard/a.xlsx", "已读取 Excel 表格：a.xlsx（567 字符）")
        val card = officeDocCardOf(c)!!
        assertTrue(card.isRead)
        assertEquals("567 字符", card.subtitle)
    }

    @Test fun t03_readPdfVisual() {
        val c = contentOf("read_pdf", true, "/sdcard/论文.pdf", "<<PDFVIS:/cache/p1.jpg>>" + BS + "n" + BS + "n已将 PDF「论文.pdf」的前 3 页转化为视觉上下文，请直接基于图像内容回答。")
        val card = officeDocCardOf(c)!!
        assertTrue(card.isRead)
        assertEquals("已转为视觉上下文", card.subtitle)
        assertEquals("论文.pdf", card.name)
    }

    // ---------- 生成类 ----------

    @Test fun t04_createWordSuccess() {
        val c = contentOf("create_word", true, "/sdcard/Download/总结.docx", "已生成 Word 文档：/sdcard/Download/总结.docx（2048 字节）。可在文件管理器或 WPS/Office 中打开。")
        val card = officeDocCardOf(c)!!
        assertFalse(card.isRead)
        assertEquals("总结.docx", card.name)
        assertEquals("点击打开", card.subtitle)
    }

    @Test fun t05_createExcelRows() {
        val c = contentOf("create_excel", true, "/sdcard/成绩.xlsx", "已创建 Excel 表格：成绩.xlsx（3 行）")
        val card = officeDocCardOf(c)!!
        assertFalse(card.isRead)
        assertEquals("3 行 · 点击打开", card.subtitle)
    }

    // ---------- 拦截边界 ----------

    @Test fun t06_failedToolNoCard() {
        val c = contentOf("read_word", false, "/sdcard/x.docx", "文件不存在")
        assertNull(officeDocCardOf(c))
    }

    @Test fun t07_runningNoCard() {
        val c = contentOf("read_word", null, "/sdcard/x.docx", "")
        assertNull(officeDocCardOf(c))
    }

    @Test fun t08_nonOfficeToolNoCard() {
        val c = contentOf("read_file", true, "/sdcard/x.txt", "ok")
        assertNull(officeDocCardOf(c))
    }

    @Test fun t09_missingPathNoCard() {
        val c = contentOf("read_word", true, "", "ok")
        assertNull(officeDocCardOf(c))
    }

    @Test fun t10_garbageNoCard() {
        assertNull(officeDocCardOf("这不是 JSON"))
        assertNull(officeDocCardOf(""))
    }

    @Test fun t11_readWithoutCharCountFallback() {
        val c = contentOf("read_word", true, "/sdcard/x.docx", "输出里没有字数")
        assertEquals("已读取", officeDocCardOf(c)!!.subtitle)
    }

    @Test fun t12_prefixImpostorRejected() {
        // 便宜预筛靠 startsWith，工具名嵌在中间时必须不误判
        val c = "{" + DQ + "note" + DQ + ":" + DQ + "read_word" + DQ + "}"
        assertNull(officeDocCardOf(c))
    }
}
