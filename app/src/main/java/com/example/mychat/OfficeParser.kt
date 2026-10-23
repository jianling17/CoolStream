package com.example.mychat

import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import kotlin.text.Charsets

// ============================================================
// OfficeParser —— docx / xlsx 的纯文本提取，外加「是不是 Office」的识别
// ============================================================
//
// 背景：read_file 工具以前遇到 docx / xlsx 只能回一句「这是二进制文件」，
// 可用户明明看得见里面全是字。其实 OOXML（docx / xlsx）本质是一个 zip 包，
// 文字就躺在里面的 XML 上 —— 解出来就能给模型看。
//
// ★ 为什么不引第三方库（POI / docx4j / Tika）：
//   那几个是几百 KB 到几 MB 的 jar，而我们要的只是「把 <w:t> 里的字抠出来」。
//   java.util.zip + 一段标签扫描就能做到，包体零增长。
//   Android 出厂自带 org.xmlpull.v1，但 JVM 单测里它是 android.jar 的桩实现
//   （app/build.gradle.kts 开了 unitTests.isReturnDefaultValues = true），
//   桩会静默返回空值 —— 用它测出来的「解析结果为空」分不清是文件错还是环境错。
//   所以这里自带一个标签驱动的轻量扫描器，行为可预期，真机和 JVM 两头一致。
//
// ★ 线程约定：本文件全是同步纯函数，不起协程、不开线程、不碰 Android 框架类。
//   调用方必须自己在 Dispatchers.IO 里调 —— 解 zip + 扫 XML 是 CPU + IO 活，
//   搁主线程上会直接卡 UI。
//
// ★ 健壮性：ZIP 条目损坏、条目名带 ../、XML 畸形、编码异常、数组越界，
//   一律 try-catch 吞掉，返回空串或对应 message。这个函数的输入是用户挑的文件，
//   「不可信」是默认前提，绝不 throw、绝不崩溃。
// ============================================================

/** 纯文本上限。超长就截断，避免把几百 KB 的 XML 文本原样塞进 prompt。 */
internal const val MAX_TEXT_CHARS = 10000

/**
 * 截断后缀。
 * ⚠️ 改 MAX_TEXT_CHARS 时，这句话里写死的 10000 必须同步改，否则文案对不上实际截了多少。
 */
internal const val TRUNCATE_SUFFIX = "\n……（内容过长，已截断，仅保留前 10000 字）"

/**
 * 旧版二进制格式（.doc / .xls，OLE2 复合文档）的固定话术，会原样展示给用户。
 *
 * ⚠️ 这句话同时会被单元测试逐字断言。改文案必须同步改
 *    .dispatch/spec-v11-office.md，两边不一致就是线上文案漂移。
 */
internal const val LEGACY_BINARY_MESSAGE =
    "暂不支持旧版二进制格式，请在 WPS/Office 中另存为 .docx 或 .xlsx 格式后再试。"

/** docx 正文所在的 zip 条目名。 */
private const val DOCX_ENTRY = "word/document.xml"

/** xlsx 工作表目录前缀。 */
private const val XLSX_SHEET_PREFIX = "xl/worksheets/"

/** xlsx 共享字符串表。 */
private const val XLSX_SHARED_STRINGS = "xl/sharedStrings.xml"

/** xlsx 第一张工作表。 */
private const val XLSX_SHEET1 = "xl/worksheets/sheet1.xml"

/**
 * 会被当成「旧版二进制 Office」的扩展名（调用方已转小写）。
 *
 * 里面除了规格点名的 .doc/.xls/.wps/.et，还收了 .docx/.xlsx：
 * 用户把老文件改名成 .docx 是常事，这时按内容（OLE2 头）给旧版提示，
 * 比回一句「无法识别的文件格式」有用得多。
 */
private val LEGACY_OFFICE_EXTS = setOf("doc", "docx", "xls", "xlsx", "wps", "et")

/** 文件是 docx、xlsx、pdf、旧版二进制，还是压根不认识。 */
internal enum class OfficeKind { NONE, DOCX, XLSX, PDF, LEGACY_OFFICE, LEGACY_PDF }

/**
 * 一次解析的结论。
 *
 * ok=false 时 text 一定是空串，message 是要展示给用户的话；
 * ok=true 时 message 是空串，text 是截断后的纯文本。
 */
internal data class OfficeParseResult(
    val ok: Boolean,
    val text: String,
    val kind: OfficeKind,
    val message: String
)

// ---- 标签扫描器的状态 ----
private const val ST_TEXT = 0
private const val ST_LT = 1
private const val ST_NAME = 2
private const val ST_ATTRS = 3
private const val ST_END_NAME = 4
private const val ST_BANG = 5
private const val ST_COMMENT = 7
private const val ST_PI = 8
private const val ST_DECL = 9
private const val ST_CDATA_EXPECT = 10
private const val ST_CDATA = 11

/**
 * 标签驱动的轻量 XML 扫描器（流式，逐字符状态机）。
 *
 * 为什么不直接 import org.xmlpull.v1.XmlPullParser：见文件头。
 * 行为等价：只报三种事件 —— 开始标签、结束标签、字符数据，命名空间和属性原样交给调用方判断。
 *
 * 喂进来的 chars 必须已经是解码后的字符（UTF-8 由 InputStreamReader 负责），
 * 一个多字节字符不会被切成两半 —— 这是「跨 read 块不切断实体引用」的前提。
 */
internal class XmlTagScanner(private val handler: Handler) {

    internal interface Handler {
        /** name 是原始限定名（如 "w:p"）；attrs 是原始属性串，用 attrValue() 取。 */
        fun onStartTag(name: String, attrs: String, selfClosing: Boolean) {}

        fun onEndTag(name: String) {}

        /** 已做实体解码（&amp; → &）。一段连续的字符数据只会报一次。 */
        fun onText(text: String) {}
    }

    private var state = ST_TEXT
    private val text = StringBuilder()
    private val name = StringBuilder()
    private val attrs = StringBuilder()
    private var quote = '\u0000'
    private var selfClosing = false
    private var bangDash = false
    private var commentDashes = 0
    private var piQuestion = false
    private var cdataBrackets = 0
    private var cdataExpect = 0

    /** 喂一段字符。 */
    fun feed(chars: CharArray, len: Int) {
        var i = 0
        while (i < len) {
            val c = chars[i]
            when (state) {
                ST_TEXT -> {
                    if (c == '<') state = ST_LT else text.append(c)
                    i++
                }

                ST_LT -> {
                    when {
                        c == '/' -> { state = ST_END_NAME; name.setLength(0); i++ }
                        c == '!' -> { state = ST_BANG; bangDash = false; i++ }
                        c == '?' -> { state = ST_PI; piQuestion = false; i++ }
                        isNameStart(c) -> { state = ST_NAME; name.setLength(0); name.append(c); i++ }
                        else -> {
                            // '<' 后面不是合法标签起点（畸形 XML）。把它当普通字符留下，
                            // 否则后面的正文会被连标签一起吞掉。
                            text.append('<')
                            state = ST_TEXT
                        }
                    }
                }

                ST_NAME -> {
                    when {
                        isNameChar(c) -> { name.append(c); i++ }
                        c == '/' -> { selfClosing = true; state = ST_ATTRS; attrs.setLength(0); i++ }
                        c == '>' -> { emitStart(); i++ }
                        // 名字和属性之间只认这四种空白；别的一律当属性内容，不吞字符
                        c == ' ' || c == '\t' || c == '\n' || c == '\r' -> {
                            state = ST_ATTRS; attrs.setLength(0); i++
                        }
                        else -> { state = ST_ATTRS; attrs.setLength(0); attrs.append(c); i++ }
                    }
                }

                ST_ATTRS -> {
                    if (quote != '\u0000') {
                        // 引号里的 '>' 不是标签结束（例如 w:val="a>b"），必须连引号一起吞
                        attrs.append(c)
                        if (c == quote) quote = '\u0000'
                        i++
                    } else when (c) {
                        '>' -> { emitStart(); i++ }
                        '"', '\'' -> { quote = c; attrs.append(c); i++ }
                        else -> { attrs.append(c); i++ }
                    }
                }

                ST_END_NAME -> {
                    if (c == '>') { emitEnd(); i++ }
                    else { if (isNameChar(c)) name.append(c); i++ }
                }

                ST_BANG -> {
                    when {
                        c == '-' && !bangDash -> { bangDash = true; i++ }
                        c == '-' && bangDash -> { state = ST_COMMENT; commentDashes = 0; i++ }
                        c == '[' -> { state = ST_CDATA_EXPECT; cdataExpect = 0; i++ }
                        else -> { state = ST_DECL; i++ }
                    }
                }

                ST_COMMENT -> {
                    // 注释里出现 '>' 不算结束，必须 "-->" 三连
                    if (c == '-') commentDashes++
                    else if (c == '>') {
                        if (commentDashes >= 2) { state = ST_TEXT; commentDashes = 0 } else commentDashes = 0
                    } else commentDashes = 0
                    i++
                }

                ST_PI -> {
                    if (c == '?') piQuestion = true
                    else if (c == '>') { if (piQuestion) { state = ST_TEXT; piQuestion = false } else piQuestion = false }
                    else piQuestion = false
                    i++
                }

                ST_DECL -> {
                    // <!DOCTYPE ...> 之类，直接跳到 '>'
                    if (c == '>') state = ST_TEXT
                    i++
                }

                ST_CDATA_EXPECT -> {
                    val expect = "CDATA["
                    if (c == expect[cdataExpect]) {
                        cdataExpect++
                        i++
                        if (cdataExpect == expect.length) { state = ST_CDATA; cdataBrackets = 0 }
                    } else {
                        // 不是 CDATA（例如 <!DOCTYPE ...>），退回「跳到 >」模式
                        state = ST_DECL
                    }
                }

                ST_CDATA -> {
                    when {
                        c == ']' -> { cdataBrackets++; i++ }
                        c == '>' && cdataBrackets >= 2 -> {
                            // 只有连续两个 ] 紧跟 > 才算结束，多出来的 ] 是正文里的字
                            if (cdataBrackets > 2) text.append("]".repeat(cdataBrackets - 2))
                            state = ST_TEXT
                            cdataBrackets = 0
                            i++
                        }
                        else -> {
                            if (cdataBrackets > 0) text.append("]".repeat(cdataBrackets))
                            cdataBrackets = 0
                            text.append(c)
                            i++
                        }
                    }
                }

                else -> { i++ }
            }
        }
    }

    /** 输入读完，收尾。半截标签直接丢掉 —— 宁可少一段，也不要把半个标签名当正文吐出去。 */
    fun finish() {
        if (state == ST_TEXT || state == ST_CDATA) flushText()
        reset()
    }

    private fun emitStart() {
        val rawName = name.toString()
        val rawAttrs = attrs.toString()
        val sc = selfClosing
        reset()
        // 标签前的字符数据必须先报，顺序不能反
        flushText()
        handler.onStartTag(rawName, rawAttrs, sc)
        if (sc) handler.onEndTag(rawName)
    }

    private fun emitEnd() {
        val rawName = name.toString()
        reset()
        flushText()
        handler.onEndTag(rawName)
    }

    private fun flushText() {
        if (text.length == 0) return
        handler.onText(decodeEntities(text))
        text.setLength(0)
    }

    private fun reset() {
        state = ST_TEXT
        name.setLength(0)
        attrs.setLength(0)
        selfClosing = false
        quote = '\u0000'
        bangDash = false
        commentDashes = 0
        piQuestion = false
        cdataBrackets = 0
        cdataExpect = 0
    }
}

// ---- 下面是一批共用的名字/属性/实体小工具 ----

/**
 * 取标签的本地名，但只在命名空间前缀是 w（或没有前缀）时才剥掉。
 *
 * 只认 w: 是有意的：<a:t> 是 DrawingML 的文本、<w:tab> 是制表符，
 * 它们本地名都叫 t，若一律剥前缀就会把无关内容当成正文。
 */
private fun tagLocalName(qualified: String): String {
    val i = qualified.indexOf(':')
    if (i <= 0) return qualified
    return if (qualified.substring(0, i) == "w") qualified.substring(i + 1) else qualified
}

/** 取属性的本地名（属性名带前缀的情况很少，宽松处理）。 */
private fun attrLocalName(qualified: String): String {
    val i = qualified.indexOf(':')
    return if (i < 0) qualified else qualified.substring(i + 1)
}

private fun isNameStart(c: Char): Boolean = c.isLetter() || c == '_' || c == ':'

private fun isNameChar(c: Char): Boolean = isNameStart(c) || c.isDigit() || c == '-' || c == '.'

/**
 * 从原始属性串里取某个属性的值，取不到返回 null。
 *
 * 手写而不用正则：属性串每个单元格都会过一遍，正则编译 + 匹配在这里是纯浪费。
 */
internal fun attrValue(attrs: String, name: String): String? {
    if (attrs.isEmpty()) return null
    var i = 0
    while (i < attrs.length) {
        while (i < attrs.length && attrs[i].isWhitespace()) i++
        if (i >= attrs.length) break
        val keyStart = i
        while (i < attrs.length && !attrs[i].isWhitespace() && attrs[i] != '=') i++
        val key = attrs.substring(keyStart, i)
        while (i < attrs.length && attrs[i].isWhitespace()) i++
        if (i < attrs.length && attrs[i] == '=') {
            i++
            while (i < attrs.length && attrs[i].isWhitespace()) i++
            if (i < attrs.length && (attrs[i] == '"' || attrs[i] == '\'')) {
                val q = attrs[i]
                i++
                val vStart = i
                while (i < attrs.length && attrs[i] != q) i++
                val value = attrs.substring(vStart, i)
                if (i < attrs.length) i++
                if (attrLocalName(key) == name) return decodeEntities(value)
            } else {
                val vStart = i
                while (i < attrs.length && !attrs[i].isWhitespace()) i++
                if (attrLocalName(key) == name) return decodeEntities(attrs.substring(vStart, i))
            }
        } else if (attrLocalName(key) == name) {
            // 只有名字没有 "=值" 的属性，XML 语义上等价于空串
            return ""
        }
    }
    return null
}

/**
 * 解码 XML 实体引用。
 *
 * 必须做：docx 里 "&amp;" 存的就是一个 &，不解码用户看到的就是乱码 "&amp;"。
 * 不认识的序列原样留下 —— 畸形 XML 里可能有裸 '&'，硬解反而会改坏正文。
 */
internal fun decodeEntities(src: CharSequence): String {
    if (src.indexOf('&') < 0) return src.toString()
    val out = StringBuilder(src.length)
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (c != '&') {
            out.append(c)
            i++
            continue
        }
        // 实体名最长就是 apos/#xHHHHHH，窗口给 12 足够；找不到分号就按字面量处理
        var semi = -1
        val limit = minOf(src.length, i + 13)
        var j = i + 1
        while (j < limit) {
            if (src[j] == ';') { semi = j; break }
            j++
        }
        if (semi < 0) {
            out.append(c)
            i++
            continue
        }
        val ent = src.substring(i + 1, semi)
        val decoded: String? = when (ent) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> if (ent.startsWith("#")) decodeNumericEntity(ent) else null
        }
        if (decoded == null) {
            out.append(c)
            i++
        } else {
            out.append(decoded)
            i = semi + 1
        }
    }
    return out.toString()
}

/** 解 &#233; / &#x4E2D; 这类数字实体，解不出来返回 null。 */
private fun decodeNumericEntity(ent: String): String? {
    val hex = ent.startsWith("#x") || ent.startsWith("#X")
    val digits = if (hex) ent.substring(2) else ent.substring(1)
    if (digits.isEmpty()) return null
    val code = try {
        if (hex) digits.toInt(16) else digits.toInt()
    } catch (e: NumberFormatException) {
        return null
    }
    if (code < 0 || code > 0x10FFFF) return null
    return try {
        String(Character.toChars(code))
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** 条目名归一化：Windows 写的 zip 可能用反斜杠，少数工具会加 "./" 前缀。 */
private fun normalizeEntryName(raw: String): String {
    var n = raw.replace('\\', '/')
    while (n.startsWith("./")) n = n.substring(2)
    return n
}

/**
 * 顺序扫 zip 条目，命中 target 就把那一条流式喂给 scanner。
 *
 * ★ 直接读 ZipInputStream 本体，不用 zip.getInputStream(entry) ——
 *   后者 API 26 才有，而 minSdk 是 24。
 * ★ 只做条目名的精确相等比较，所以条目名带 "../" 也碰不到我们：
 *   既不解压也不落盘，路径穿越在这个用法里无从发生。
 */
private fun scanZipEntry(
    bytes: ByteArray,
    target: String,
    scanner: XmlTagScanner
): Boolean {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: return false
            if (entry.isDirectory) continue
            if (normalizeEntryName(entry.name) != target) continue
            InputStreamReader(zip, Charsets.UTF_8).use { r ->
                val buf = CharArray(8192)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    scanner.feed(buf, n)
                }
            }
            scanner.finish()
            return true
        }
    }
}

/**
 * 提取 docx 正文纯文本。
 *
 * 规则（与规格文件一致）：
 *   · 遇到 w:p 开始标签 → 段与段之间追加 "\n"（第一段之前不补）
 *   · 遇到 w:t → 把字符数据追加进去
 *   · 其他标签与属性一律忽略
 * ★ 结果的首字符就是正文，没有前导换行：换行只做段落分隔，
 *   不占 10000 字预算，调用方拿到的就是用户真正读到的字。
 * 没找到 word/document.xml 就返回空串。
 */
internal fun extractDocxText(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    return try {
        val out = StringBuilder()
        val scanner = XmlTagScanner(object : XmlTagScanner.Handler {
            private var inT = 0
            private var paragraphOpened = false

            override fun onStartTag(name: String, attrs: String, selfClosing: Boolean) {
                when (tagLocalName(name)) {
                    // 段落之间才补换行；第一段之前不补。
                    //
                    // ★ 为什么第一段不补：用户视角数的是字数不是行数，
                    //   让一个前导 \n 占掉 10000 字预算里的一位，
                    //   等于悄悄少给一个字（编排层返工单 R1 红测 1-4 的根因）。
                    //   顺带让结果的首字符就是正文，调用方不用再 trimStart()。
                    "p" -> {
                        if (paragraphOpened) out.append('\n')
                        paragraphOpened = true
                    }
                    "t" -> inT++
                }
            }

            override fun onEndTag(name: String) {
                if (tagLocalName(name) == "t" && inT > 0) inT--
            }

            override fun onText(text: String) {
                // 只有 <w:t> 里的字符数据是正文。<w:tab>、<w:br>、属性值全不算。
                if (inT > 0) out.append(text)
            }
        })
        if (scanZipEntry(bytes, DOCX_ENTRY, scanner)) out.toString() else ""
    } catch (e: Exception) {
        ""
    }
}

/** 第一遍：收集 xl/sharedStrings.xml 里每个 <si> 的文本。 */
private fun readSharedStrings(bytes: ByteArray): List<String> {
    val out = ArrayList<String>()
    try {
        val scanner = XmlTagScanner(object : XmlTagScanner.Handler {
            private var siDepth = 0
            private var inT = 0
            private val cur = StringBuilder()

            override fun onStartTag(name: String, attrs: String, selfClosing: Boolean) {
                when (tagLocalName(name)) {
                    "si" -> { siDepth++; if (siDepth == 1) cur.setLength(0) }
                    "t" -> inT++
                }
            }

            override fun onEndTag(name: String) {
                when (tagLocalName(name)) {
                    "t" -> if (inT > 0) inT--
                    "si" -> {
                        if (siDepth > 0) {
                            siDepth--
                            if (siDepth == 0) out.add(cur.toString())
                        }
                    }
                }
            }

            override fun onText(text: String) {
                // 一个 <si> 可能被切成多个 <r> 富文本段，片段要拼回去才算一整句
                if (inT > 0) cur.append(text)
            }
        })
        scanZipEntry(bytes, XLSX_SHARED_STRINGS, scanner)
    } catch (e: Exception) {
        // 共享字符串表读不出来不等于整份文件废了：sheet1 里非 s 类型单元格照样能取
    }
    return out
}

/**
 * 提取 xlsx 第一张工作表的纯文本。
 *
 * 规则（与规格文件一致）：按 <c> 取 t 属性（t="s" 表示共享字符串索引），
 * 其余按行内 <v> / <is> 取；单元格之间 "\t"，行尾 "\n"。
 * 没找到 xl/worksheets/sheet1.xml 就返回空串。
 */
internal fun extractXlsxText(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    return try {
        // 分两趟扫：共享字符串表在 zip 里的先后顺序并不保证，
        // 先把它读出来，第二趟拿它去解 sheet1 里的 t="s" 索引。
        val shared = readSharedStrings(bytes)
        val out = StringBuilder()
        val scanner = XmlTagScanner(object : XmlTagScanner.Handler {
            private var rowOpen = false
            private var cellOpen = false
            private var firstCellInRow = true
            private var cellType = ""
            private var inV = false
            private var inIs = false
            private var hasIs = false
            private var inIsT = 0
            private val vRaw = StringBuilder()
            private val isText = StringBuilder()

            override fun onStartTag(name: String, attrs: String, selfClosing: Boolean) {
                when (tagLocalName(name)) {
                    "row" -> { rowOpen = true; firstCellInRow = true }
                    "c" -> {
                        cellOpen = true
                        // r="A1" 是坐标，用不上；只有 t 决定这个格子的值怎么取
                        cellType = attrValue(attrs, "t") ?: ""
                        vRaw.setLength(0)
                        isText.setLength(0)
                        inV = false
                        inIs = false
                        hasIs = false
                        inIsT = 0
                    }
                    "v" -> if (cellOpen) inV = true
                    "is" -> if (cellOpen) { inIs = true; hasIs = true }
                    "t" -> if (inIs) inIsT++
                }
            }

            override fun onEndTag(name: String) {
                when (tagLocalName(name)) {
                    "c" -> if (cellOpen) { emitCell(); cellOpen = false }
                    // 整行没有单元格时不补换行，否则空行会连成一片
                    "row" -> {
                        if (rowOpen && !firstCellInRow) out.append('\n')
                        rowOpen = false
                    }
                    "v" -> inV = false
                    "is" -> inIs = false
                    "t" -> if (inIsT > 0) inIsT--
                }
            }

            override fun onText(text: String) {
                when {
                    inV -> vRaw.append(text)
                    inIsT > 0 -> isText.append(text)
                }
            }

            private fun emitCell() {
                val raw = when {
                    // t="inlineStr"：值直接写在 <is><t> 里
                    hasIs -> isText.toString()
                    // t="s"：<v> 里存的是共享字符串表的下标，不是文本
                    cellType == "s" -> shared.getOrNull(parseIndex(vRaw.toString())) ?: ""
                    // t="str"/"b"/"e"/"d" 和默认的数字，值都在 <v> 里
                    else -> vRaw.toString()
                }
                if (!firstCellInRow) out.append('\t')
                out.append(raw)
                firstCellInRow = false
            }

            private fun parseIndex(s: String): Int = s.trim().toIntOrNull() ?: -1
        })
        if (scanZipEntry(bytes, XLSX_SHEET1, scanner)) out.toString() else ""
    } catch (e: Exception) {
        ""
    }
}

/**
 * 按「内容优先」判断文件是不是本期支持的格式。
 *
 * 优先级（规格规定）：
 *   ① 文件头是 ZIP（PK\x03\x04）→ 扫条目名定 docx / xlsx，都不是就 NONE
 *      （是 zip 但不是 Office，交给调用方按普通二进制处理）
 *   ② 文件头是 "%PDF-" → PDF
 *   ③ 扩展名是旧版 Office 且文件头是 OLE2（D0 CF 11 E0）→ LEGACY_OFFICE
 *   ④ 扩展名是 .pdf 但不是 "%PDF-" 头 → LEGACY_PDF
 *   ⑤ 其余 → NONE
 * 扩展名大小写不敏感（.DOCX / .Pdf 都认）。
 */
internal fun looksLikeOffice(fileName: String, bytes: ByteArray): OfficeKind {
    // ① 内容说了算：docx / xlsx 都是 zip 包，先看这四个字节
    if (startsWithMagic(bytes, MAGIC_ZIP)) {
        return try {
            var hasWord = false
            var hasXl = false
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val n = normalizeEntryName(entry.name)
                    when {
                        n == DOCX_ENTRY -> hasWord = true
                        n.startsWith(XLSX_SHEET_PREFIX) || n == XLSX_SHARED_STRINGS -> hasXl = true
                    }
                    // docx 优先，找到就可以收工
                    if (hasWord) break
                }
            }
            when {
                hasWord -> OfficeKind.DOCX
                hasXl -> OfficeKind.XLSX
                else -> OfficeKind.NONE
            }
        } catch (e: Exception) {
            // zip 头是真的但结构坏了：当不认识的二进制处理，别崩
            OfficeKind.NONE
        }
    }

    // ② PDF
    if (isPdf(bytes)) return OfficeKind.PDF

    val ext = extensionOf(fileName)

    // ③ 旧版二进制 Office
    if (ext in LEGACY_OFFICE_EXTS && startsWithMagic(bytes, MAGIC_OLE2)) {
        return OfficeKind.LEGACY_OFFICE
    }

    // ④ 名字叫 pdf、内容却不是
    if (ext == "pdf") return OfficeKind.LEGACY_PDF

    return OfficeKind.NONE
}

/** 取小写扩展名，不带点。用 Locale 无关的 lowercase()，避开土耳其语 I 的坑。 */
private fun extensionOf(fileName: String): String {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\')
    val dot = base.lastIndexOf('.')
    if (dot < 0) return ""
    return base.substring(dot + 1).lowercase()
}

/**
 * 分隔符字符（段落换行、单元格 tab）。
 * ★ 它们不占用 10000 字的正文预算：上限只数「用户真正能读到的字」，
 *   否则一篇恰好 10000 字的文档会因为段落多出来的 \n 被误判超长而截短。
 */
internal const val SEPARATOR_CHARS = "\n\t\r"

/**
 * 纯文本截断，上限只数正文，不计分隔符。
 *
 * ≤ 10000 个正文字符：原样返回（分隔符照原样带着，一个都不动）。
 * > 10000：保留「按原顺序刚好凑够 10000 个正文字符」的那一段，
 *         后面跟 TRUNCATE_SUFFIX。
 *
 * ★ 为什么不是 text.take(10000)：那样段首/单元格之间补的 \n 会占掉预算，
 *   一篇 10000 字的文档会被截成 9999 字，而测试（与规格 §4）钉的是
 *   「正文字符一个不能少、一个不能多」。
 */
internal fun truncateOfficeText(text: String): String {
    var body = 0
    var cut = -1
    for (i in text.indices) {
        val c = text[i]
        if (c in SEPARATOR_CHARS) continue
        body++
        if (body > MAX_TEXT_CHARS) { cut = i; break }
    }
    if (cut < 0) return text
    // 分隔符不计入预算，但也不能把凑满那一刻身上挂的分隔符甩在正文后面
    var end = cut
    while (end > 0 && text[end - 1] in SEPARATOR_CHARS) end--
    return text.substring(0, end) + TRUNCATE_SUFFIX
}

/**
 * 成功路径统一收口：有正文就 ok=true + 截断后的文本；
 * 一个字符都没解出来就 ok=false + 一句人话。
 *
 * ★ 为什么要这个函数：zip 头是真的、条目名也扫到了，但条目内容可能被截断
 *   （用户传文件传了一半、或者文件本身损坏）。此时若老老实实返回
 *   ok=true + text="" + message=""，调用方看到的就是「解析成功但文档是空的」
 *   —— 用户完全无法判断该重传还是该放弃。宁可明确说「解不出正文」。
 *
 * ★ 分隔符不算正文：换行与制表符是提取器自己补的结构符号，
 *   只由它们组成的「结果」和空结果一样没有信息量。
 */
internal fun okOrExplain(kind: OfficeKind, raw: String, what: String): OfficeParseResult {
    val text = truncateOfficeText(raw)
    if (text.isNotEmpty() && text.any { it !in SEPARATOR_CHARS }) {
        return OfficeParseResult(ok = true, text = text, kind = kind, message = "")
    }
    return OfficeParseResult(
        ok = false,
        text = "",
        kind = kind,
        message = "识别为 " + when (kind) {
            OfficeKind.DOCX -> "Word 文档"
            OfficeKind.XLSX -> "Excel 工作簿"
            else -> "Office 文档"
        } + "，但没能从中提取到文字内容（$what）。文件可能已损坏或不完整，可以尝试在 WPS/Office 中重新打开并另存一份后再试。"
    )
}

/**
 * 一站式入口：认格式 → 取文本（或给提示）→ 截断。
 *
 * 这个函数自己就是 try-catch 的最后一层，任何意外都变成一条 message，
 * 调用方拿到的永远是一个能直接展示的结果。
 */
internal fun parseOfficeBytes(fileName: String, bytes: ByteArray): OfficeParseResult {
    return try {
        when (val kind = looksLikeOffice(fileName, bytes)) {
            OfficeKind.DOCX -> okOrExplain(
                kind = OfficeKind.DOCX,
                raw = extractDocxText(bytes),
                what = "Word 文档正文（word/document.xml）"
            )

            OfficeKind.XLSX -> okOrExplain(
                kind = OfficeKind.XLSX,
                raw = extractXlsxText(bytes),
                what = "工作表内容（xl/worksheets/sheet1.xml）"
            )

            OfficeKind.LEGACY_OFFICE -> OfficeParseResult(
                ok = false,
                text = "",
                kind = OfficeKind.LEGACY_OFFICE,
                message = LEGACY_BINARY_MESSAGE
            )

            OfficeKind.PDF, OfficeKind.LEGACY_PDF -> OfficeParseResult(
                ok = false,
                text = "",
                kind = kind,
                message = pdfNotSupportedMessage(fileName)
            )

            OfficeKind.NONE -> OfficeParseResult(
                ok = false,
                text = "",
                kind = OfficeKind.NONE,
                message = "无法识别的文件格式"
            )
        }
    } catch (e: Exception) {
        OfficeParseResult(
            ok = false,
            text = "",
            kind = OfficeKind.NONE,
            message = "无法识别的文件格式"
        )
    }
}