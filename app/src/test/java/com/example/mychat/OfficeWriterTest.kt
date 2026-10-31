package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class OfficeWriterTest {

    private fun entryNames(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        val zis = ZipInputStream(ByteArrayInputStream(bytes))
        var e = zis.nextEntry
        while (e != null) {
            names.add(e.name)
            e = zis.nextEntry
        }
        zis.close()
        return names
    }

    private fun entryBytes(bytes: ByteArray, name: String): ByteArray? {
        val zis = ZipInputStream(ByteArrayInputStream(bytes))
        var e = zis.nextEntry
        while (e != null) {
            if (e.name == name) return zis.readBytes()
            e = zis.nextEntry
        }
        zis.close()
        return null
    }

    // ---------- docx ----------

    @Test
    fun docxHasRequiredEntries() {
        val r = buildDocx("你好\n世界")
        assertTrue(r.ok)
        val names = entryNames(r.bytes!!)
        assertTrue(names.contains("[Content_Types].xml"))
        assertTrue(names.contains("_rels/.rels"))
        assertTrue(names.contains("word/document.xml"))
    }

    @Test
    fun docxRoundTrip() {
        val original = "第一段内容\n第二段内容"
        val r = buildDocx(original)
        assertTrue(r.ok)
        val parsed = parseOfficeBytes("test.docx", r.bytes!!)
        assertTrue(parsed.ok)
        assertEquals("第一段内容第二段内容", parsed.text.replace("\n", ""))
    }

    @Test
    fun docxEscapeRoundTrip() {
        val original = "a<b>c&d" + "" + "ef"
        val r = buildDocx(original)
        assertTrue(r.ok)
        val docXml = String(entryBytes(r.bytes!!, "word/document.xml")!!, Charsets.UTF_8)
        assertTrue(docXml.contains("&lt;b&gt;"))
        val parsed = parseOfficeBytes("t.docx", r.bytes!!)
        assertTrue(parsed.ok)
        assertEquals(original, parsed.text)
    }

    @Test
    fun docxControlCharsStripped() {
        val withCtrl = "bad" + 1.toChar() + "text" + 2.toChar() + "end"
        val r = buildDocx(withCtrl)
        assertTrue(r.ok)
        val parsed = parseOfficeBytes("t.docx", r.bytes!!)
        assertTrue(parsed.ok)
        assertEquals("badtextend", parsed.text)
    }

    @Test
    fun docxEmptyText() {
        // 写入器层面：空文本也必须产出合法、非空的 docx 包（结构完整）
        val r = buildDocx("")
        assertTrue(r.ok)
        assertTrue(r.bytes!!.isNotEmpty())
        // 读回层面：0 字正文按 R1 护栏 ok=false（识别为 Word 但没有文字）——这是读路径防损坏语义，不是写入器缺陷
        val parsed = parseOfficeBytes("t.docx", r.bytes!!)
        assertFalse(parsed.ok)
    }

    @Test
    fun xmlEscapeBasics() {
        assertEquals("a&amp;b", xmlEscape("a&b"))
        assertEquals("&lt;tag&gt;", xmlEscape("<tag>"))
        assertEquals("plain", xmlEscape("plain"))
    }

    @Test
    fun legalCharBoundaries() {
        assertTrue(isLegalXmlChar("\t"[0]))
        assertTrue(isLegalXmlChar("\n"[0]))
        assertTrue(isLegalXmlChar("你"[0]))
        assertFalse(isLegalXmlChar(0.toChar()))
        assertFalse(isLegalXmlChar(31.toChar()))
        assertFalse(isLegalXmlChar(0xFFFE.toChar()))
    }

    // ---------- xlsx ----------

    @Test
    fun xlsxHasRequiredEntries() {
        val r = buildXlsx(listOf(listOf("姓名", "分数"), listOf("小明", "95")))
        assertTrue(r.ok)
        val names = entryNames(r.bytes!!)
        assertTrue(names.contains("xl/workbook.xml"))
        assertTrue(names.contains("xl/worksheets/sheet1.xml"))
    }

    @Test
    fun xlsxRoundTrip() {
        val rows = listOf(listOf("列A", "列B"), listOf("1", "2"), listOf("合计", "3"))
        val r = buildXlsx(rows)
        assertTrue(r.ok)
        val parsed = parseOfficeBytes("t.xlsx", r.bytes!!)
        assertTrue(parsed.ok)
        val flat = parsed.text.replace("\n", "").replace("\t", "")
        assertTrue(flat.contains("列A"))
        assertTrue(flat.contains("合计"))
    }

    @Test
    fun colNameCases() {
        assertEquals("A", colName(0))
        assertEquals("Z", colName(25))
        assertEquals("AA", colName(26))
        assertEquals("AB", colName(27))
        assertEquals("BA", colName(52))
    }

    @Test
    fun xlsxCellEscape() {
        val r = buildXlsx(listOf(listOf("a&b", "<c>")))
        assertTrue(r.ok)
        val sheetXml = String(entryBytes(r.bytes!!, "xl/worksheets/sheet1.xml")!!, Charsets.UTF_8)
        assertTrue(sheetXml.contains("a&amp;b"))
        assertTrue(sheetXml.contains("t=\"inlineStr\""))
    }

    @Test
    fun xlsxEmpty() {
        val r = buildXlsx(emptyList())
        assertTrue(r.ok)
        assertTrue(r.bytes!!.isNotEmpty())
        val parsed = parseOfficeBytes("t.xlsx", r.bytes!!)
        assertFalse(parsed.ok)
    }
}
