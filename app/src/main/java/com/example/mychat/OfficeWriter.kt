package com.example.mychat

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ============================================================
// OfficeWriter —— 手写最小 OOXML 生成器（v1.7.0）
// ============================================================
//
// 为什么手写：POI / docx4j 都是几百 KB 到几 MB 的依赖，
// 而我们要生成的只是「纯文本段落 + 换行」级别的 docx/xlsx。
// 一个 OOXML 文件本质是 zip 包，最少数量的 XML 条目就能被 WPS/Office 打开。
//
// ★ 线程约定：与 OfficeParser 相同 —— 全是同步纯函数，调用方必须在 Dispatchers.IO 里调。
// ★ fail-closed：先在内存里拼完整个 zip 字节数组，全部成功后才允许落盘；
//   任何一步失败都返回 OfficeWriteResult(ok=false)，用户磁盘上绝不出现半成品文件。
// ============================================================

/** 生成失败时的用户话术（fail-closed，明确告知原文件未被破坏） */
internal const val OFFICE_WRITE_FAIL_MESSAGE =
    "文档生成失败：内容无法打包成有效的 Office 文件。原始文件未被改动，请重试或换个文件名。"

// ============================================================
// 共享：XML 转义 + 非法字符清洗
// ============================================================

/**
 * XML 1.0 合法字符判断。控制字符（除 Tab/LF/CR）在 XML 里非法，
 * 塞进去 WPS/Office 会报「文件损坏」—— 模型输出里偶尔会混入这种字符。
 */
internal fun isLegalXmlChar(c: Char): Boolean {
    if (c == '\u0009' || c == '\u000A' || c == '\u000D') return true
    if (c < ' ') return false
    if (c == '\uFFFE' || c == '\uFFFF') return false
    return true
}

/** 清洗掉 XML 非法字符（直接删除，不加占位符） */
internal fun sanitizeXmlText(src: String): String {
    if (src.none { !isLegalXmlChar(it) }) return src
    val sb = StringBuilder(src.length)
    for (c in src) if (isLegalXmlChar(c)) sb.append(c)
    return sb.toString()
}

/** XML 文本节点转义：& < > 必须转，引号顺手转掉更稳 */
internal fun xmlEscape(src: String): String {
    val s = sanitizeXmlText(src)
    val sb = StringBuilder(s.length + 16)
    for (c in s) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/** 生成产物：字节数组 + 话术 */
internal data class OfficeWriteResult(
    val ok: Boolean,
    val message: String,
    val bytes: ByteArray? = null
)

/** 把若干条目按顺序压成一个 zip 字节数组（docx / xlsx 共用） */
internal fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zos ->
        for ((name, data) in entries) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(data)
            zos.closeEntry()
        }
    }
    return bos.toByteArray()
}

// ============================================================
// Word (.docx) 生成
// ============================================================

/** 最小 docx 的固定 XML 条目 */
internal object DocxTemplate {
    val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".trimIndent()

    val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""".trimIndent()

    val DOCUMENT_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>""".trimIndent()

    const val DOCUMENT_TAIL = "  </w:body>\n</w:document>"
}

/**
 * 把纯文本打包成最小合法 docx（WordprocessingML）。
 * 空行 = 空段落；普通换行 = 新段落。全部文本过 xmlEscape + 非法字符清洗。
 */
internal fun buildDocx(text: String): OfficeWriteResult {
    return try {
        val body = StringBuilder()
        val paragraphs = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (para in paragraphs) {
            body.append("    <w:p><w:r><w:t xml:space=\"preserve\">")
            body.append(xmlEscape(para))
            body.append("</w:t></w:r></w:p>\n")
        }
        val document = if (body.isBlank()) {
            // 空文本也放一个空段落：合法 docx，且 parseOfficeBytes 能正常读回空正文
            DocxTemplate.DOCUMENT_HEAD + "\n" + "    <w:p/>\n" + DocxTemplate.DOCUMENT_TAIL
        } else {
            DocxTemplate.DOCUMENT_HEAD + "\n" + body + DocxTemplate.DOCUMENT_TAIL
        }
        val bytes = zipOf(
            listOf(
                "[Content_Types].xml" to DocxTemplate.CONTENT_TYPES.toByteArray(StandardCharsets.UTF_8),
                "_rels/.rels" to DocxTemplate.RELS.toByteArray(StandardCharsets.UTF_8),
                "word/document.xml" to document.toByteArray(StandardCharsets.UTF_8)
            )
        )
        OfficeWriteResult(true, "OK", bytes)
    } catch (e: Exception) {
        OfficeWriteResult(false, OFFICE_WRITE_FAIL_MESSAGE + "（" + e.javaClass.simpleName + "）")
    }
}

// ============================================================
// Excel (.xlsx) 生成
// ============================================================

internal object XlsxTemplate {
    val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""".trimIndent()

    val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".trimIndent()

    val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>""".trimIndent()

    val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""".trimIndent()
}

/** 列号转 Excel 字母列名：0->A, 25->Z, 26->AA */
internal fun colName(index0: Int): String {
    var n = index0
    val sb = StringBuilder()
    while (n >= 0) {
        sb.insert(0, ('A' + n % 26))
        n = n / 26 - 1
    }
    return sb.toString()
}

/**
 * 把二维表格（外层=行，内层=单元格）打包成最小合法 xlsx。
 * 跳过 sharedStrings，全部用 inlineStr —— 少一个条目，逻辑最简，Office/WPS 均兼容。
 */
internal fun buildXlsx(rows: List<List<String>>): OfficeWriteResult {
    return try {
        val sheet = StringBuilder()
        sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sheet.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n")
        sheet.append("  <sheetData>\n")
        for ((r, row) in rows.withIndex()) {
            sheet.append("    <row r=\"").append(r + 1).append("\">\n")
            for ((c, cell) in row.withIndex()) {
                sheet.append("      <c r=\"").append(colName(c)).append(r + 1)
                sheet.append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                sheet.append(xmlEscape(cell))
                sheet.append("</t></is></c>\n")
            }
            sheet.append("    </row>\n")
        }
        sheet.append("  </sheetData>\n</worksheet>")
        val bytes = zipOf(
            listOf(
                "[Content_Types].xml" to XlsxTemplate.CONTENT_TYPES.toByteArray(StandardCharsets.UTF_8),
                "_rels/.rels" to XlsxTemplate.RELS.toByteArray(StandardCharsets.UTF_8),
                "xl/workbook.xml" to XlsxTemplate.WORKBOOK.toByteArray(StandardCharsets.UTF_8),
                "xl/_rels/workbook.xml.rels" to XlsxTemplate.WORKBOOK_RELS.toByteArray(StandardCharsets.UTF_8),
                "xl/worksheets/sheet1.xml" to sheet.toString().toByteArray(StandardCharsets.UTF_8)
            )
        )
        OfficeWriteResult(true, "OK", bytes)
    } catch (e: Exception) {
        OfficeWriteResult(false, OFFICE_WRITE_FAIL_MESSAGE + "（" + e.javaClass.simpleName + "）")
    }
}
