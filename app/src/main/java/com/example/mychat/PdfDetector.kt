package com.example.mychat

import kotlin.text.Charsets

// ============================================================
// PdfDetector —— PDF 识别与固定话术
// ============================================================
//
// 职责边界（本期铁律）：只做「识别 + 拦截」。
//   · 判断字节流是不是 PDF
//   · 给用户一句人话，告诉他本期为什么不支持、该怎么办
// 严禁在这里引入任何 PDF 解析 / 渲染 / 文字提取逻辑，也严禁 import 任何
// PDF 相关库 —— 本期 PDF 是明确不支持的，零新增依赖、零新增体积。
//
// 顺带放了三个字节级文件头魔数（ZIP / PDF / OLE2）。
// 理由：OfficeParser.kt 的 looksLikeOffice() 和本文件的 isPdf() 比的是同一批魔数，
// 放一份在这儿共用，比两边各写一遍更不容易对不齐。
//
// 全是同步纯函数：不起线程、不开协程、不碰 Android 框架类，
// 所以 JVM 单测和真机行为一致。调用方必须自己在 Dispatchers.IO 里调。
// ============================================================

/**
 * ZIP 容器的文件头。docx / xlsx 本质上都是 zip 包，
 * 所以「是不是 Office 新格式」第一步就是看这四个字节。
 */
internal val MAGIC_ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

/**
 * PDF 的文件头。规范里是 "%PDF-" 后面紧跟版本号（例如 "%PDF-1.7"），
 * 所以只比前 4 个字节 "%PDF"，不把版本号写死。
 *
 * ★ 为什么放宽到前 4 字节而不是 "%PDF-"：
 *   现实里有少数工具写出的文件头是 "%PDF?" 或其他非连字符写法，
 *   认不出就会掉进 NONE 分支给「无法识别的文件格式」，用户更懵。
 *   但也不放到任意 "%PDF" 前缀那么宽 —— 普通文本文件以 "%PDF" 开头的概率极低，
 *   4 字节正好把「真 PDF」和「碰巧含 %PDF 的文本」分开。
 */
internal val MAGIC_PDF = "%PDF".toByteArray(Charsets.US_ASCII)

/**
 * OLE2 复合文档的文件头，也就是 Office 97-2003 时代 .doc / .xls 的容器头。
 *
 * ⚠️ 0xD0 / 0xCF / 0xE0 都大于 127，在 Kotlin 的有符号 Byte 里是负数，
 *    直接写 byteArrayOf(0xD0, 0xCF, 0x11, 0xE0) 编译不过，必须逐个 toByte()。
 */
internal val MAGIC_OLE2 = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())

/**
 * bytes 是否以 magic 开头。
 *
 * 空数组、比 magic 还短的数组一律 false，不做任何越界访问 ——
 * 这个函数的入参是用户挑的任意文件，长度不可信。
 */
internal fun startsWithMagic(bytes: ByteArray, magic: ByteArray): Boolean {
    if (bytes.size < magic.size) return false
    for (i in magic.indices) {
        if (bytes[i] != magic[i]) return false
    }
    return true
}

/**
 * 是不是 PDF。只看文件头，不看扩展名。
 *
 * 为什么不让它掺扩展名：名字叫 .pdf 但内容不是 PDF 的情况，
 * OfficeKind 里有专门的 LEGACY_PDF 表达，两种失败的提示文案不一样。
 * 这里只按文件头魔数判定（"%PDF" 前 4 字节），扩展名判断统一交给 looksLikeOffice()。
 */
internal fun isPdf(bytes: ByteArray): Boolean = startsWithMagic(bytes, MAGIC_PDF)


/**
 * PDF 的固定话术，会原样展示给用户。
 *
 * ⚠️ 这句话同时会被单元测试逐字断言。改文案必须同步改
 *    .dispatch/spec-v11-office.md，两边不一致就是线上文案漂移。
 */
internal fun pdfNotSupportedMessage(fileName: String): String =
    "暂不支持 PDF 文件（" + fileName + "）。PDF 里存的是排版结果而不是文字，" +
    "本期只能提取 .docx / .xlsx 的纯文本；请在 WPS/Office 中把 PDF 转成 .docx 或 .txt 后再试。"