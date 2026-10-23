package com.example.mychat

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Office 解析单元测试的共享夹具。
 *
 * 为什么坚持造「真字节流」而不是往函数里塞字符串：
 *   被测代码走的是 ZipInputStream + XML 流式解析，只有真 zip 结构才能覆盖
 *   「条目顺序」「条目名大小写」「共享字符串缺失」「zip 被截断」这些分支。
 *   手拼的假字节流会让 ZipInputStream 第一步就抛异常，测到的只是异常路径，
 *   解析逻辑本身反而没被验证。
 *
 * 全部只用 JDK 自带类（ZipOutputStream / ByteArrayOutputStream），
 * 零新增依赖，也不碰任何 android.* 框架类。
 */
internal object OfficeTestFixtures {

    /** wordprocessingml 主命名空间；少了它 <w:p> 就是未绑定前缀，XML 直接非法 */
    const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** 按传入顺序把条目写进 zip —— 顺序即条目顺序，用来验证「乱序也能取到」 */
    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
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

    /** 文本条目版：统一按 UTF-8 编码，中文才不会变问号 */
    fun zipText(entries: List<Pair<String, String>>): ByteArray =
        zip(entries.map { it.first to it.second.toByteArray(Charsets.UTF_8) })

    /** 一段 <w:t> 一个段落；rawDocumentXml 可直接塞畸形 XML 做健壮性测试 */
    fun docx(
        paragraphs: List<String> = emptyList(),
        leadingEntries: List<Pair<String, String>> = emptyList(),
        documentEntryName: String = "word/document.xml",
        rawDocumentXml: String? = null
    ): ByteArray {
        val body = paragraphs.joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" }
        val document = rawDocumentXml
            ?: ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<w:document xmlns:w=\"$W_NS\"><w:body>$body</w:body></w:document>")
        val entries = leadingEntries.toMutableList()
        entries += documentEntryName to document
        return zipText(entries)
    }

    /** sharedStrings 传 null 即模拟「sheet 引用 t=\"s\" 但共享字符串表缺失」 */
    fun xlsx(sheet1: String, sharedStrings: String?): ByteArray {
        val entries = mutableListOf<Pair<String, String>>()
        if (sharedStrings != null) {
            entries += "xl/sharedStrings.xml" to sharedStrings
        }
        entries += "xl/worksheets/sheet1.xml" to sheet1
        return zipText(entries)
    }

    /** OLE2 复合文档头（.doc/.xls/.wps/.et 旧版二进制格式的指纹） */
    fun legacyHeader(): ByteArray = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
    ) + ByteArray(56)

    /** 真 PDF 头 + 一个最小 body */
    fun pdf(): ByteArray = (
        "%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n" +
            "1 0 obj<</Type/Catalog>>endobj\n" +
            "trailer<</Root 1 0 R>>\n%%EOF"
        ).toByteArray(Charsets.ISO_8859_1)
}
