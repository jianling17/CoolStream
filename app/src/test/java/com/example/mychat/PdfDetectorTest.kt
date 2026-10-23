package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 识别与提示文案的单元测试。
 *
 * 为什么 PDF 要单独一层：
 *   v1.1 明确「不支持 PDF」，所以 PDF 识别的价值全在「拦得准」——
 *   真 PDF 必须被认出来（否则会当普通文本解析，用户看到一堆乱码），
 *   而 .pdf 扩展名只是提示，不能当唯一依据（假 pdf 场景见 OfficeParserTest）。
 *
 * 只测 isPdf / pdfNotSupportedMessage 两个入口，不碰解析逻辑。
 * 运行：./gradlew testDebugUnitTest
 */
class PdfDetectorTest {

    @Test
    fun isPdf_true_on_real_pdf_header() {
        // 钉：%PDF- 魔数开头即判为 PDF
        assertTrue(isPdf(OfficeTestFixtures.pdf()))
        assertTrue(isPdf("%PDF-1.4\nrest".toByteArray()))
        assertTrue(isPdf("%PDF-1.7".toByteArray()))
    }

    @Test
    fun isPdf_false_on_non_pdf() {
        // 钉：其他内容一律判为非 PDF，尤其旧版 OLE2 头（假 pdf 的内容侧）
        assertFalse(isPdf(OfficeTestFixtures.legacyHeader()))
        assertFalse(isPdf("hello world".toByteArray()))
        assertFalse(isPdf(OfficeTestFixtures.docx(paragraphs = listOf("docx"))))
        assertFalse(isPdf(ByteArray(0)))
    }

    @Test
    fun isPdf_does_not_crash_on_garbage() {
        // 钉：垃圾字节不能把异常漏出去；结尾是 0 的短缓冲容易越界，专门盯这个
        val garbage = byteArrayOf(0, 255.toByte(), 10, 37, 80, 68, 70, 0)

        assertFalse(isPdf(garbage))
        assertFalse(isPdf(ByteArray(1)))
        assertFalse(isPdf(ByteArray(4)))
    }

    @Test
    fun pdf_message_contains_file_name_and_is_stable() {
        // 钉：提示必须带文件名（否则用户不知道是哪个文件被拒），
        //     且同一文件名多次调用结果一致（不能每次生成不同文案）
        val m1 = pdfNotSupportedMessage("合同.pdf")
        val m2 = pdfNotSupportedMessage("合同.pdf")

        assertTrue("提示不能为空: [$m1]", m1.isNotEmpty())
        assertTrue("提示必须含文件名: [$m1]", m1.contains("合同.pdf"))
        assertEquals("同样输入必须得到同样文案", m1, m2)
    }

    @Test
    fun pdf_message_handles_ascii_and_long_names() {
        // 钉：ASCII 文件名、超长文件名都不抛异常且都带名字
        val ascii = pdfNotSupportedMessage("report-v1.1.pdf")
        assertTrue("ASCII 文件名也要带上: [$ascii]", ascii.contains("report-v1.1.pdf"))

        val long = pdfNotSupportedMessage("n".repeat(200) + ".pdf")
        assertTrue("超长文件名也不能崩: [$long]", long.isNotEmpty())
        assertTrue("超长文件名也要带上名字", long.contains("n".repeat(200)))
    }

    @Test
    fun pdf_not_supported_matches_parse_result_for_pdf() {
        // 钉：parseOfficeBytes 对 PDF 给出的 message 必须与
        //     pdfNotSupportedMessage(fileName) 完全一致（两处文案不能漂移）
        val r = parseOfficeBytes("a.pdf", OfficeTestFixtures.pdf())

        assertFalse(r.message, r.ok)
        assertEquals(pdfNotSupportedMessage("a.pdf"), r.message)
    }
}
