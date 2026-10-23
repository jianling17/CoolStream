package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1 阶段二「Office / WPS 文档解析」的 JVM 单元测试。
 *
 * 被测对象：app/src/main/java/com/example/mychat/OfficeParser.kt
 * 规格来源：.dispatch/spec-v11-office.md（T1 子代理产出）。
 *
 * 这一层测试钉死的是「用户把一个文件丢进来之后会发生什么」：
 *   · 解析成功时拿到完整正文（中文、英文、xml:space 属性都不能丢字）
 *   · 旧版二进制格式 / PDF 必须被明确拒绝，且拒绝话术一字不差
 *   · 任何畸形输入（垃圾字节、截断 zip、未闭合 XML、缺失共享字符串）
 *     都只能走到兜底，绝不能抛异常崩掉 App
 *   · 10000 字符截断边界：不多截、不少截、提示必须带上
 *
 * 全部用例只依赖 JDK（ZipOutputStream / XML 解析都在被测代码内部），
 * 不 import 任何 android.* —— 工程开着 unitTests.isReturnDefaultValues，
 * 真调了 android.* 框架方法只会拿到默认返回值，测出来是假绿。
 *
 * 运行：./gradlew testDebugUnitTest
 */
class OfficeParserTest {

    // ================================================================
    // 规格 1：docx 正文提取
    // ================================================================

    @Test
    fun docx_extracts_paragraphs_with_newline_separator() {
        // 钉：多个 <w:p> 段落全部提取，两两之间有换行，中英文都不丢字
        val bytes = OfficeTestFixtures.docx(
            paragraphs = listOf("第一段中文", "第二段中文", "Hello ASCII")
        )

        val text = extractDocxText(bytes)

        assertTrue("第一段必须提取到: [$text]", text.contains("第一段中文"))
        assertTrue("第二段必须提取到: [$text]", text.contains("第二段中文"))
        assertTrue("纯 ASCII 英文也不能丢: [$text]", text.contains("Hello ASCII"))
        assertTrue("段落之间必须有换行: [$text]", text.contains("\n"))
    }

    @Test
    fun docx_preserves_xml_space_attribute() {
        // 钉：<w:t xml:space="preserve"> 的前后空格必须保留，不能被 trim 掉
        val bytes = OfficeTestFixtures.docx(
            rawDocumentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="${OfficeTestFixtures.W_NS}">
                  <w:body>
                    <w:p><w:r><w:t xml:space="preserve">  leading and trailing  </w:t></w:r></w:p>
                    <w:p><w:r><w:t>Plain</w:t></w:r></w:p>
                  </w:body>
                </w:document>""".trimIndent()
        )

        val text = extractDocxText(bytes)

        assertTrue("xml:space=preserve 的空格必须保留: [$text]", text.contains("leading and trailing"))
        assertTrue("普通段落也要在: [$text]", text.contains("Plain"))
    }

    @Test
    fun docx_handles_multiple_runs_in_one_paragraph() {
        // 钉：一个 <w:p> 里多个 <w:r> 的文本要拼起来，且段内不插换行
        val bytes = OfficeTestFixtures.docx(
            rawDocumentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="${OfficeTestFixtures.W_NS}">
                  <w:body>
                    <w:p><w:r><w:t>甲</w:t></w:r><w:r><w:t>乙</w:t></w:r></w:p>
                  </w:body>
                </w:document>""".trimIndent()
        )

        val text = extractDocxText(bytes)

        assertTrue("同段多 run 必须拼接成一段: [$text]", text.contains("甲乙"))
        assertFalse("同段内不应插入换行: [$text]", text.contains("甲\n乙"))
    }

    // ================================================================
    // 规格 2：docx zip 结构健壮性（乱序条目 / 条目名大小写）
    // ================================================================

    @Test
    fun docx_reads_document_when_it_is_not_the_first_entry() {
        // 钉：word/document.xml 是 zip 里第二个条目时也能取到
        //     （先塞一个 docProps/core.xml 干扰，证明不是「只读第一个条目」）
        val bytes = OfficeTestFixtures.docx(
            paragraphs = listOf("乱序条目正文"),
            leadingEntries = listOf("docProps/core.xml" to "<cp:coreProperties/>")
        )

        val text = extractDocxText(bytes)

        assertTrue("第二个条目里的正文必须提取到: [$text]", text.contains("乱序条目正文"))
    }

    @Test
    fun docx_with_uppercase_entry_name_does_not_crash() {
        // 钉：条目名大小写混合（Word/document.xml）至少不能崩；
        //     能不能识别是实现自由度，这里只守住「不抛异常 + 不吐乱码」这条红线。
        val bytes = OfficeTestFixtures.docx(
            paragraphs = listOf("大写条目名"),
            leadingEntries = listOf("docProps/core.xml" to "<干扰条目>DECOY</干扰条目>"),
            documentEntryName = "Word/document.xml"
        )

        val text = extractDocxText(bytes)

        // 识别与否是实现自由度：空串（未识别）或含正文（已识别）都合法
        assertTrue(
            "大小写混合条目名：要么兜底成空串，要么提取到正文，实际 [$text]",
            text.isEmpty() || text.contains("大写条目名")
        )
        assertFalse("不能把 XML 标签本身吐出来: [$text]", text.contains("<w:"))
        assertFalse("不能把其他条目的内容误当正文: [$text]", text.contains("DECOY"))
    }

    // ================================================================
    // 规格 3 / 4：xlsx 提取 + 共享字符串缺失
    // ================================================================

    @Test
    fun xlsx_extracts_shared_strings_and_numbers() {
        // 钉：t="s" 走共享字符串表，无 t 属性的直接取 <v>，
        //     单元格之间 tab、行之间换行
        val bytes = OfficeTestFixtures.xlsx(
            sharedStrings = "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<si><t>标题</t></si><si><t>列A</t></si></sst>",
            sheet1 = "<worksheet><sheetData>" +
                "<row><c t=\"s\"><v>0</v></c><c t=\"s\"><v>1</v></c></row>" +
                "<row><c><v>123</v></c></row>" +
                "</sheetData></worksheet>"
        )

        val text = extractXlsxText(bytes)

        assertTrue("共享字符串「标题」必须解析到: [$text]", text.contains("标题"))
        assertTrue("共享字符串「列A」必须解析到: [$text]", text.contains("列A"))
        assertTrue("数字单元格 123 必须解析到: [$text]", text.contains("123"))
        assertTrue("单元格之间必须有 tab: [$text]", text.contains("\t"))
        assertTrue("行之间必须有换行: [$text]", text.contains("\n"))
    }

    @Test
    fun xlsx_missing_shared_strings_does_not_crash() {
        // 钉：sheet 里引用 t="s" 但 xl/sharedStrings.xml 整个缺失时，
        //     只能静默跳过该单元格，不能抛 IndexOutOfBounds / NPE，
        //     更不能把 XML 标签或索引数字当成正文吐出来。
        val bytes = OfficeTestFixtures.xlsx(
            sharedStrings = null,
            sheet1 = "<worksheet><sheetData>" +
                "<row><c t=\"s\"><v>0</v></c><c t=\"s\"><v>1</v></c></row>" +
                "</sheetData></worksheet>"
        )

        val text = extractXlsxText(bytes)

        assertFalse("不能把 XML 标签吐出来: [$text]", text.contains("<si>"))
        assertFalse("不能把共享串索引当成正文: [$text]", text.trim() == "0" || text.trim() == "01")
    }

    @Test
    fun xlsx_empty_sheet_does_not_crash() {
        // 钉：sheetData 里一行都没有时也不抛异常，且不吐标签
        val bytes = OfficeTestFixtures.xlsx(
            sharedStrings = "<sst><si><t>只有共享串</t></si></sst>",
            sheet1 = "<worksheet><sheetData></sheetData></worksheet>"
        )

        val text = extractXlsxText(bytes)

        assertFalse("空表不能崩，也不能吐标签: [$text]", text.contains("<"))
    }

    // ================================================================
    // ================================================================
    // 规格 5 / 6：10000 字符截断边界
    //
    // 观测点说明：真实现的截断只发生在 parseOfficeBytes 里
    // （truncateOfficeText 在 OfficeParser.kt:710，被 724/731 调用），
    // extractDocxText / extractXlsxText 自身不做截断。
    // 所以这一组全部走 parseOfficeBytes 观测，直调 extractXxxText
    // 只能看到「没截断的那一层」，测不到截断逻辑。
    //
    // 缺陷拆解（真实现当前两个都中，回报里已单列）：
    //   A 上限预算：段首补的 \n 被计入 10000 → 正文被截成 9999
    //   B 多余前导 \n：单段 docx 的提取结果以 \n 开头（OfficeParser.kt:493）
    // 两类断言分开写，方便 T1 逐个修、逐个转绿。
    // ================================================================

    @Test
    fun docx_truncates_when_longer_than_limit() {
        // 钉：超过 10000 字符必须截断，且必须追加截断提示
        val r = parseOfficeBytes(
            "a.docx",
            OfficeTestFixtures.docx(rawDocumentXml = documentXmlOf("x".repeat(15000)))
        )

        assertTrue(r.message, r.ok)
        // 前 10000 个正文位置必须原样保留（正文没被提前砍掉）
        assertEquals("x".repeat(LIMIT), bodyPrefix(r.text, LIMIT, 'x'))
        // 必须追加截断提示
        assertTrue("必须带截断提示: [${r.text}]", hasTruncationHint(r.text))
        // 提示必须很短：不能把 15000 的正文全带出来，只能多出一小段提示
        assertTrue(
            "截断后总长必须接近上限，实际 ${r.text.length}",
            r.text.length in (LIMIT + 1)..(LIMIT + 64)
        )
    }

    @Test
    fun docx_truncation_keeps_exactly_the_first_10000_chars() {
        // 钉：截断后剩余部分确实是「前 10000 字符」——防止有人截成 9999 或 5000
        val r = parseOfficeBytes(
            "a.docx",
            OfficeTestFixtures.docx(rawDocumentXml = documentXmlOf("y".repeat(15000)))
        )

        assertTrue(r.message, r.ok)
        assertTrue("必须带截断提示: [${r.text}]", hasTruncationHint(r.text))
        // 前 10000 个正文位置必须恰好是 y
        assertEquals(
            "截断后正文必须恰好是前 10000 字符",
            "y".repeat(LIMIT),
            bodyPrefix(r.text, LIMIT, 'y')
        )
        // 多出来的正文字符一个都不许留：正文算的是 y，分隔符/提示都不算
        assertEquals("多出来的正文一个都不许留", LIMIT, r.text.count { it == 'y' })
    }

    @Test
    fun docx_at_exactly_10000_chars_is_not_truncated() {
        // 钉规格：正文恰好 10000 字符 = 上限本身，不算超长，不能出现截断提示。
        // 这条用例专门防「把截断写成截 9999」，以及防「把段落分隔符也算进上限」：
        // 若实现先补 \n 再判长度，10000 的正文会被算成 10001 而误判超长，这里立刻红。
        //
        // ⚠ spec 分歧（已回报 parent/T1，见交付说明「spec 分歧清单」）：
        //   spec 只写「10000 字截断」，没说分隔符是否计入上限。
        //   本测试按「上限只针对正文字符」钉死；真实现当前把前导 \n 也算进去了，
        //   导致这条红。若 T1 有意让分隔符计入，需要改的是 spec，不是这条测试。
        val r = parseOfficeBytes(
            "a.docx",
            OfficeTestFixtures.docx(rawDocumentXml = documentXmlOf("z".repeat(10000)))
        )

        assertTrue(r.message, r.ok)
        // 前 10000 字符必须一个不少
        assertEquals("前 10000 字符必须一个不少", "z".repeat(LIMIT), bodyPrefix(r.text, LIMIT, 'z'))
        assertFalse("10000 字符不该带截断提示: [${r.text}]", hasTruncationHint(r.text))
        // 正文字符一个都不能少（分隔符不算正文）
        assertEquals("正文不能被截短", LIMIT, r.text.count { it == 'z' })
    }

    @Test
    fun docx_at_10001_chars_is_truncated() {
        // 钉规格：10001 字符跨过上限，必须截断并带提示，正文只保留前 10000 字符。
        val r = parseOfficeBytes(
            "a.docx",
            OfficeTestFixtures.docx(rawDocumentXml = documentXmlOf("z".repeat(10001)))
        )

        assertTrue(r.message, r.ok)
        assertTrue("10001 字符必须被截断: [${r.text}]", hasTruncationHint(r.text))
        assertEquals("截断后正文必须是前 10000 字符", "z".repeat(LIMIT), bodyPrefix(r.text, LIMIT, 'z'))
        // 超出的那个字符必须被丢掉，不能出现在结果里
        assertEquals("超出的那个字符不能出现", LIMIT, r.text.count { it == 'z' })
    }



    // ================================================================
    // 规格 7：looksLikeOffice 识别矩阵
    // ================================================================

    @Test
    fun looksLikeOffice_matrix() {
        // 钉：扩展名 + 内容双因子识别，扩展名大小写不敏感，内容说了算
        val docxBytes = OfficeTestFixtures.docx(paragraphs = listOf("docx"))
        val xlsxBytes = OfficeTestFixtures.xlsx(
            sharedStrings = "<sst><si><t>k</t></si></sst>",
            sheet1 = "<worksheet><sheetData><row><c t=\"s\"><v>0</v></c></row></sheetData></worksheet>"
        )
        val legacy = OfficeTestFixtures.legacyHeader()
        val pdf = OfficeTestFixtures.pdf()

        assertEquals(OfficeKind.DOCX, looksLikeOffice("a.docx", docxBytes))
        assertEquals(OfficeKind.XLSX, looksLikeOffice("a.XLSX", xlsxBytes))
        assertEquals(OfficeKind.PDF, looksLikeOffice("a.pdf", pdf))
        assertEquals(OfficeKind.LEGACY_OFFICE, looksLikeOffice("a.doc", legacy))
        assertEquals(OfficeKind.LEGACY_OFFICE, looksLikeOffice("a.xls", legacy))
        assertEquals(OfficeKind.LEGACY_OFFICE, looksLikeOffice("a.wps", legacy))
        assertEquals(OfficeKind.LEGACY_OFFICE, looksLikeOffice("a.et", legacy))
        // 假 pdf：扩展名骗人，内容指纹才是真的
        assertEquals(OfficeKind.LEGACY_PDF, looksLikeOffice("a.pdf", legacy))
        assertEquals(OfficeKind.NONE, looksLikeOffice("a.txt", "plain text".toByteArray()))
        assertEquals(OfficeKind.NONE, looksLikeOffice("a.docx", byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(OfficeKind.NONE, looksLikeOffice("a.docx", ByteArray(0)))
    }

    // ================================================================
    // 规格 8：parseOfficeBytes 语义
    // ================================================================

    @Test
    fun parseOfficeBytes_docx_success() {
        // 钉：正常 docx → ok=true、kind=DOCX、text 非空
        val bytes = OfficeTestFixtures.docx(paragraphs = listOf("正文内容"))

        val r = parseOfficeBytes("a.docx", bytes)

        assertTrue(r.message, r.ok)
        assertEquals(OfficeKind.DOCX, r.kind)
        assertTrue(r.message, r.text.isNotEmpty())
        assertTrue("text 要含正文: [${r.text}]", r.text.contains("正文内容"))
    }

    @Test
    fun parseOfficeBytes_legacy_office_rejected_with_exact_message() {
        // 钉：LEGACY_OFFICE → ok=false、text 空、message 与常量一字不差
        val r = parseOfficeBytes("a.doc", OfficeTestFixtures.legacyHeader())

        assertFalse("旧版二进制必须被拒绝: ${r.message}", r.ok)
        assertEquals("", r.text)
        assertEquals(LEGACY_BINARY_MESSAGE, r.message)
        assertEquals(OfficeKind.LEGACY_OFFICE, r.kind)
    }

    @Test
    fun parseOfficeBytes_pdf_rejected_with_file_name() {
        // 钉：PDF → ok=false、message 含文件名，且与 pdfNotSupportedMessage 一致
        val r = parseOfficeBytes("report.pdf", OfficeTestFixtures.pdf())

        assertFalse("PDF 必须被拒绝: ${r.message}", r.ok)
        assertEquals(OfficeKind.PDF, r.kind)
        assertEquals(pdfNotSupportedMessage("report.pdf"), r.message)
        assertTrue("message 要含文件名: [${r.message}]", r.message.contains("report.pdf"))
    }

    @Test
    fun parseOfficeBytes_none_rejected_with_non_empty_message() {
        // 钉：认不出来 → ok=false、message 非空（用户得知道为什么）
        val r = parseOfficeBytes("a.txt", "hello".toByteArray())

        assertFalse(r.message, r.ok)
        assertEquals(OfficeKind.NONE, r.kind)
        assertTrue("message 不能为空: [${r.message}]", r.message.isNotEmpty())
    }

    // ================================================================
    // 规格 9：拒绝话术常量一字不差（防后续有人改文案）
    // ================================================================

    @Test
    fun legacy_binary_message_is_exact() {
        assertEquals(
            "暂不支持旧版二进制格式，请在 WPS/Office 中另存为 .docx 或 .xlsx 格式后再试。",
            LEGACY_BINARY_MESSAGE
        )
    }

    // ================================================================
    // 规格 10：XML / zip 异常兜底红线（不崩溃）
    //
    // 这一组用例钉的是「兜底」而不是「解析成功」：输入越畸形，
    // 越要求 extractDocxText / parseOfficeBytes 把异常吃干净、
    // 返回空串或部分内容，绝不能让 ZipException / SAXException 漏到 UI 层。
    // ================================================================

    @Test
    fun garbage_bytes_do_not_crash() {
        // 钉：纯垃圾字节 → looksLikeOffice / parseOfficeBytes 都不抛异常，
        //     且识别结果必须是 NONE、message 必须给用户一句解释
        val garbage = byteArrayOf(0, 1, 2, 3, 255.toByte(), 254.toByte(), 10, 13, 0)

        assertEquals(OfficeKind.NONE, looksLikeOffice("a.docx", garbage))
        val r = parseOfficeBytes("a.docx", garbage)

        assertFalse("垃圾字节必须判为不可用: ${r.message}", r.ok)
        assertTrue("必须给出解释: [${r.message}]", r.message.isNotEmpty())
        assertFalse("不能吐出垃圾内容: [${r.text}]", r.text.isNotEmpty())
    }

    @Test
    fun truncated_zip_does_not_crash() {
        // 钉：zip 字节流只写一半（中央目录被砍掉）也不能抛异常
        val full = OfficeTestFixtures.docx(paragraphs = listOf("会被截断的正文"))
        val half = full.copyOfRange(0, full.size / 2)

        val text = extractDocxText(half)

        assertGraceful(text, "会被截断的正文\n", "截断 zip")

        // 顶层入口允许两种走向，但禁止第三种「静默成功」：
        //   ① ok=true 且有正文   ② ok=false 并给出解释
        //   ③ ok=true 但 text 空、message 也空 —— 用户看到一个「解析成功」的空文档
        val r = parseOfficeBytes("a.docx", half)
        assertTrue(
            "截断 zip：要么给出正文，要么给出解释，" +
                "实际 ok=${r.ok} text=[${r.text}] msg=[${r.message}]",
            (r.ok && r.text.isNotEmpty()) || (!r.ok && r.message.isNotEmpty())
        )
    }

    @Test
    fun malformed_xml_unclosed_tag_does_not_crash() {
        // 钉：<w:t> 未闭合 → XML 解析器会报错，必须被兜住而不是抛出去
        val bytes = OfficeTestFixtures.docx(
            rawDocumentXml = """<?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="${OfficeTestFixtures.W_NS}"><w:body>
                <w:p><w:r><w:t>没闭合的标签</w:body></w:document>"""
        )

        val text = extractDocxText(bytes)

        assertGraceful(text, "没闭合的标签\n", "未闭合标签")
    }

    @Test
    fun malformed_xml_invalid_entity_does_not_crash() {
        // 钉：非法 XML 实体（未定义的 &xxx;）不能把异常漏出去
        val bytes = OfficeTestFixtures.docx(
            rawDocumentXml = """<?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="${OfficeTestFixtures.W_NS}"><w:body>
                <w:p><w:r><w:t>before &notarealentity; after</w:t></w:r></w:p>
                </w:body></w:document>"""
        )

        val text = extractDocxText(bytes)

        // 真实现把未识别实体按字面量保留（&notarealentity; 原样出现）是合法兜底，
        // 所以 allowed 串必须含 '&'、';' 和实体名字符。
        assertGraceful(text, "before &notarealentity;\n", "非法实体")
    }

    @Test
    fun empty_document_xml_does_not_crash() {
        // 钉：word/document.xml 是空串 → 不能崩，返回空串
        val bytes = OfficeTestFixtures.docx(rawDocumentXml = "")

        val text = extractDocxText(bytes)

        assertEquals("空 document.xml 应兜底成空串，实际 [$text]", "", text)
    }

    @Test
    fun docx_without_document_entry_does_not_crash() {
        // 钉：zip 里根本没有 word/document.xml → 不能崩
        val bytes = OfficeTestFixtures.zipText(
            listOf("docProps/core.xml" to "<cp:coreProperties/>")
        )

        val text = extractDocxText(bytes)

        assertEquals("缺条目应兜底成空串，实际 [$text]", "", text)
    }

    @Test
    fun empty_byte_array_does_not_crash() {
        // 钉：空 ByteArray → 全套入口都不抛异常
        val empty = ByteArray(0)

        assertEquals(OfficeKind.NONE, looksLikeOffice("a.docx", empty))
        assertFalse("isPdf(空) 应为 false", isPdf(empty))
        val r = parseOfficeBytes("a.docx", empty)
        assertFalse(r.message, r.ok)
        assertTrue("必须给出解释: [${r.message}]", r.message.isNotEmpty())
    }

    // ---------------------------------------------------------------- 辅助

    /** 把正文包进 word/document.xml，绕开 docx() 默认的段落模板，方便构造畸形内容 */
    private fun documentXmlOf(body: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="${OfficeTestFixtures.W_NS}">
              <w:body><w:p><w:r><w:t>$body</w:t></w:r></w:p></w:body>
            </w:document>""".trimIndent()

    /**
     * 兜底断言：畸形输入下，extractDocxText 的返回值只能是空串，
     * 或者只由夹具里出现过的字符组成（允许追加中文截断提示）。
     * 这样既能断言「没抛异常」，又能抓出「把堆栈/标签/索引号吐给用户」这类实现错误。
     */
    private fun assertGraceful(text: String, allowed: String, label: String) {
        assertTrue(
            "$label：兜底结果只能是空串或来自夹具的字符，实际 [$text]",
            text.isEmpty() || text.all { it in allowed || it.code > 0x2E80 }
        )
    }

    /**
     * 截断提示判定。
     *
     * 只看「超出 10000 之后的那一小段」：截断提示必然是中文文案
     * （规格要求带提示），而提取器给段落补的分隔符只是 \n / \t。
     * 所以：超出部分很短、且含中文字符 → 判定为「已截断并带提示」。
     */
    /**
     * 取出「正文前 n 个字符」，把实现给段落补的分隔符（\n / \t）过滤掉。
     *
     * 这样断言只钉正文内容，不绑架分隔符策略：
     * 实现爱在段首补 \n 就在段首补，正文一个字符都不能少、也不能多。
     */
    private fun bodyPrefix(text: String, n: Int, body: Char): String {
        val sb = StringBuilder()
        for (c in text) {
            if (c == body) {
                sb.append(c)
                if (sb.length == n) break
            }
        }
        return sb.toString()
    }

    private fun hasTruncationHint(text: String): Boolean {
        if (text.length <= LIMIT) return false
        val tail = text.substring(LIMIT)
        return tail.length <= 64 && tail.any { it.code > 0x2E80 }
    }

    private companion object {
        /** v1.1 规格：正文提取上限 10000 字符 */
        const val LIMIT = 10000
    }
}
