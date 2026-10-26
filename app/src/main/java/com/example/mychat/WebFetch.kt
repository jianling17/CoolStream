package com.example.mychat

import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.util.concurrent.TimeUnit

/**
 * v1.5.0 模块二：网页正文深度提取（web_fetch）。
 *
 * 设计约束（任务书原文）：
 *  · 只用现有 OkHttp，严禁引入 Jsoup 等重型依赖（包体铁律）。
 *  · 手搓正则剥 HTML：先整块删掉 script/style 这类「不是正文」的标签，
 *    再把块级标签换成换行、行内标签换成空格，最后解码实体、规整空白。
 *  · 单次返回正文截断到 WEB_FETCH_MAX_CHARS 字，防止 token 爆炸。
 *  · 网络 IO 由调用方放在 Dispatchers.IO 上执行（executeTool 的调用点已经如此）。
 *
 * 为什么不用 XmlPullParser：
 *  HTML 不是 XML —— 现实页面到处是没闭合的 <br>、<img> 和重复属性，
 *  丢给 XmlPullParser 会直接抛异常，反而拿不到正文。
 *  正则剥标签对「脏 HTML」的容错明显更好，代价是丢掉结构信息 ——
 *  而这里本来只要纯文本。
 */

/** 塞回给模型的正文上限（字） */
internal const val WEB_FETCH_MAX_CHARS = 5000

/** 单次抓取最多等多久（秒） */
internal const val WEB_FETCH_TIMEOUT_SECONDS = 20L

/** 原始 HTML 最多读多少字符就停 —— 防止一个几 MB 的页面把内存吃满 */
private const val WEB_FETCH_MAX_RAW_CHARS = 1_500_000

/** 有些站点会拦没有 UA 的请求，给一个普通移动浏览器 UA */
private const val WEB_FETCH_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private const val WEB_FETCH_LOG_TAG = "WebFetch"

/**
 * 抓取专用客户端：从全局 httpClient 派生，共享连接池与线程池，
 * 只额外加一个总超时 —— 和 web_search 同一个理由：
 * 全局那个 60 秒的流式超时对「抓一个网页」来说太长，用户会以为 App 卡死。
 */
internal val fetchClient: OkHttpClient by lazy {
    httpClient.newBuilder()
        .callTimeout(WEB_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

// ---------- 正则 ----------

/** 整块丢弃：连同标签内容一起删，这些不是正文 */
private val DROP_BLOCK_RE = Regex("""(?is)<(script|style|noscript|svg|iframe|template|object|canvas)\b[^>]*>.*?</\1\s*>""")

private val COMMENT_RE = Regex("""(?s)<!--.*?-->""")

/** 块级标签 → 换行；行内标签交给 ANY_TAG_RE 变成空格 */
private val BLOCK_TAG_RE = Regex("""(?i)</?(p|div|br|li|ul|ol|dl|dt|dd|tr|td|th|table|thead|tbody|h[1-6]|section|article|header|footer|nav|aside|blockquote|pre|hr|figure|figcaption|main|form|fieldset)\b[^>]*>""")

/** 剩下的任意标签。长度上界 4000 防止被畸形文本拖进指数回溯 */
private val ANY_TAG_RE = Regex("""<[^<>]{0,4000}>""")

private val SPACE_RUN_RE = Regex("""[ \t\u00A0\u3000]+""")

private val NUMERIC_ENTITY_RE = Regex("""&#(\d{1,7});""")

private val HEX_ENTITY_RE = Regex("""&#[xX]([0-9a-fA-F]{1,6});""")

private val TITLE_RE = Regex("""(?is)<title[^>]*>(.*?)</title\s*>""")

private val UNCLOSED_SCAN_TAGS = listOf("script", "style", "noscript", "iframe", "svg")

// ---------- 纯函数（JVM 单测覆盖，不需要网络） ----------

/** 只放行 http/https，避免 file:// 或 javascript: 这类协议被模型当成网页去抓 */
internal fun isHttpUrl(raw: String): Boolean {
    val u = raw.trim()
    return u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
}

/** 从 <title> 里取页面标题，取不到返回空串 */
internal fun htmlTitle(html: String): String {
    val m = TITLE_RE.find(html) ?: return ""
    return normalizeTextLines(decodeHtmlEntities(m.groupValues[1]))
}

/**
 * 把 HTML 变成给人看的纯文本。
 *
 * 顺序很重要：
 *  ① 先整块删 script/style —— 晚了它们的内容已经被当成正文了
 *  ② 再删注释
 *  ③ 补刀没闭合的 script/style（现实页面里很常见）
 *  ④ 块级标签换行，其它标签换空格
 *  ⑤ 最后才解码实体 —— 反过来 &lt;script&gt; 会先变成真标签被删掉
 */
internal fun htmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    var s = DROP_BLOCK_RE.replace(html, "\n")
    s = COMMENT_RE.replace(s, " ")
    s = cutUnclosedDropBlocks(s)
    s = BLOCK_TAG_RE.replace(s, "\n")
    s = ANY_TAG_RE.replace(s, " ")
    s = decodeHtmlEntities(s)
    return normalizeTextLines(s)
}

/**
 * 处理没闭合的 <script>/<style>。
 *
 * DROP_BLOCK_RE 要求有成对的闭合标签，少一个就整段漏进正文 ——
 * 实测抓新闻页时这种畸形标签很常见（被截断的 HTML 尤其多）。
 * 这里按标签名逐个扫：找到开标签再找它的闭标签，找不到就从开标签处砍到结尾。
 */
private fun cutUnclosedDropBlocks(input: String): String {
    var s = input
    for (tag in UNCLOSED_SCAN_TAGS) {
        val open = Regex("""(?i)<$tag\b""")
        val close = Regex("""(?i)</$tag\s*>""")
        var guard = 0
        while (guard < 8) {
            guard++
            val m = open.find(s) ?: break
            val c = close.find(s, m.range.last + 1)
            s = if (c == null) {
                s.substring(0, m.range.first) + "\n"
            } else {
                s.substring(0, m.range.first) + "\n" + s.substring(c.range.last + 1)
            }
        }
    }
    return s
}

/** 常见 HTML 实体。&amp; 放最后：先处理它会把 &amp;lt; 二次解码成 < */
private val NAMED_ENTITIES = listOf(
    "&nbsp;" to " ",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\u0022",
    "&apos;" to "\u0027",
    "&mdash;" to "\u2014",
    "&ndash;" to "\u2013",
    "&hellip;" to "\u2026",
    "&middot;" to "\u00B7",
    "&ldquo;" to "\u201C",
    "&rdquo;" to "\u201D",
    "&lsquo;" to "\u2018",
    "&rsquo;" to "\u2019",
    "&copy;" to "\u00A9",
    "&reg;" to "\u00AE",
    "&trade;" to "\u2122",
    "&times;" to "\u00D7",
    "&divide;" to "\u00F7",
    "&laquo;" to "\u00AB",
    "&raquo;" to "\u00BB",
    "&bull;" to "\u2022",
    "&deg;" to "\u00B0",
    "&plusmn;" to "\u00B1",
    "&euro;" to "\u20AC",
    "&pound;" to "\u00A3",
    "&yen;" to "\u00A5",
    "&sect;" to "\u00A7",
    "&para;" to "\u00B6",
    "&amp;" to "&"
)

/** 解码实体：数字实体 → 十六进制实体 → 常见命名实体 */
internal fun decodeHtmlEntities(input: String): String {
    if (input.indexOf(38.toChar()) < 0) return input
    var s = NUMERIC_ENTITY_RE.replace(input) { m ->
        val code = m.groupValues[1].toIntOrNull()
        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
    }
    s = HEX_ENTITY_RE.replace(s) { m ->
        val code = m.groupValues[1].toIntOrNull(16)
        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
    }
    for (pair in NAMED_ENTITIES) {
        s = s.replace(pair.first, pair.second)
    }
    return s
}

/** 逐行 trim、把行内连续空白压成一个空格、丢掉空行 */
internal fun normalizeTextLines(input: String): String {
    if (input.isEmpty()) return ""
    val sb = StringBuilder(input.length)
    for (rawLine in input.split(10.toChar())) {
        val line = SPACE_RUN_RE.replace(rawLine, " ").trim()
        if (line.isEmpty()) continue
        if (sb.isNotEmpty()) sb.append(10.toChar())
        sb.append(line)
    }
    return sb.toString()
}

/** 截断到 limit 字，并如实告诉模型原文有多长（模型据此决定要不要分段再抓） */
internal fun truncateWebText(text: String, limit: Int = WEB_FETCH_MAX_CHARS): String {
    if (limit <= 0 || text.length <= limit) return text
    var cut = limit
    while (cut > 0 && text[cut - 1].isWhitespace()) cut--
    return text.substring(0, cut) +
        "\n\n…（正文共 " + text.length + " 字，这里只给了前 " + limit + " 字）"
}

// ---------- 网络 ----------

/** 读响应体，最多读 WEB_FETCH_MAX_RAW_CHARS 个字符 */
private fun readCapped(body: ResponseBody): String {
    val sb = StringBuilder()
    try {
        body.charStream().use { reader ->
            val chunk = CharArray(8192)
            while (sb.length < WEB_FETCH_MAX_RAW_CHARS) {
                val n = reader.read(chunk)
                if (n <= 0) break
                val room = WEB_FETCH_MAX_RAW_CHARS - sb.length
                sb.append(chunk, 0, if (n > room) room else n)
            }
        }
    } catch (e: Exception) {
        Log.e(WEB_FETCH_LOG_TAG, "读取响应体失败: " + e.message, e)
    }
    return sb.toString()
}

/**
 * 真正抓一个网页。
 *
 * 返回的 output 是【给模型看的】：开头三行是网址/标题/正文长度，
 * 空一行之后才是正文 —— 模型靠这几行判断内容够不够、要不要换来源。
 */
internal fun webFetch(rawUrl: String): ToolExecResult {
    val url = rawUrl.trim()
    if (url.isEmpty()) {
        return ToolExecResult(false, "参数错误：缺少 url（要读取的网址）。")
    }
    if (!isHttpUrl(url)) {
        return ToolExecResult(false, "参数错误：url 必须以 http:// 或 https:// 开头。")
    }

    val startedAt = SystemClock.elapsedRealtime()
    return try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_FETCH_UA)
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()

        fetchClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(WEB_FETCH_LOG_TAG, "HTTP " + response.code + ": " + url)
                return ToolExecResult(
                    false,
                    "抓取失败：HTTP " + response.code + "。这个链接可能已失效、需要登录或被反爬拦住。" +
                        "换一个来源，或者直接用 web_search 的摘要回答，不要重试同一个链接。"
                )
            }
            val body = response.body
            if (body == null) {
                return ToolExecResult(false, "抓取失败：响应没有内容。")
            }

            val raw = readCapped(body)
            if (raw.isBlank()) {
                return ToolExecResult(false, "抓取失败：响应是空的。")
            }

            val contentType = body.contentType()?.toString().orEmpty()
            val looksHtml = contentType.contains("html", ignoreCase = true) ||
                raw.trimStart().startsWith("<")
            val title = if (looksHtml) htmlTitle(raw) else ""
            val text = if (looksHtml) {
                htmlToPlainText(raw)
            } else {
                normalizeTextLines(decodeHtmlEntities(raw))
            }

            if (text.isBlank()) {
                return ToolExecResult(
                    false,
                    "抓取成功但正文是空的：页面可能要靠 JavaScript 渲染，或者需要登录。" +
                        "不要重试同一个链接，换一个来源。"
                )
            }

            val shown = truncateWebText(text)
            val sb = StringBuilder()
            sb.append("网址：").append(url).append(10.toChar())
            if (title.isNotBlank()) sb.append("标题：").append(title).append(10.toChar())
            sb.append("正文长度：").append(text.length).append(" 字").append(10.toChar())
            sb.append(10.toChar())
            sb.append(shown)

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.d(
                WEB_FETCH_LOG_TAG,
                "web_fetch " + url + " -> HTTP " + response.code + ", " + elapsed + "ms, " + text.length + " 字"
            )
            ToolExecResult(true, sb.toString())
        }
    } catch (e: java.net.SocketTimeoutException) {
        Log.e(WEB_FETCH_LOG_TAG, "超时: " + e.message, e)
        ToolExecResult(false, "抓取超时：这个网站在 " + WEB_FETCH_TIMEOUT_SECONDS + " 秒内没有返回。换一个来源试试。")
    } catch (e: java.net.UnknownHostException) {
        Log.e(WEB_FETCH_LOG_TAG, "域名解析失败: " + e.message, e)
        ToolExecResult(false, "抓取失败：域名解析不了，检查网址有没有写错。")
    } catch (e: java.io.IOException) {
        Log.e(WEB_FETCH_LOG_TAG, "网络异常: " + e.message, e)
        ToolExecResult(false, "抓取失败：网络异常（" + (e.message ?: "IO") + "）。")
    } catch (e: Exception) {
        Log.e(WEB_FETCH_LOG_TAG, "未知异常: " + e.message, e)
        ToolExecResult(false, "抓取失败：" + (e.message ?: e.javaClass.simpleName))
    }
}
