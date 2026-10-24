package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1 联网搜索 + 时间感知的单元测试。
 *
 * 这两块是「最容易静默出错」的地方，所以专门测：
 *
 *   · 搜索结果解析 —— 官方返回的字段有一堆（title / url / snippet / summary /
 *     date / site_name / rerank_score / authority），取错一个不会报错，
 *     只会让模型拿到一段没用的文字，然后一本正经地胡说。
 *   · 时间格式 —— 漏掉 Locale.US 时，某些系统语言下日期会变成阿拉伯-印度数字，
 *     屏幕上"看起来还行"，模型读到的却是乱码。
 *
 * ⚠️ 这个测试【不联网】。网络那一层（OkHttp + 超时 + 401/402 分支）只能在真机上验，
 *    这里测的是「拿到响应之后」的纯逻辑。别因为单测绿了就以为搜索一定通。
 */
class WebSearchTest {

    /** 造一段官方格式的响应。字段名照抄 2026-10-17 的官方文档 */
    private fun body(vararg items: String): String =
        """{"results":[${items.joinToString(",")}]}"""

    private fun item(
        title: String,
        url: String = "https://example.com/a",
        snippet: String = "",
        summary: String = "",
        site: String = "Example News",
        date: String = ""
    ): String = buildString {
        append("""{"rerank_score":2.4,"authority":3""")
        append(""","title":"""").append(title).append('"')
        append(""","url":"""").append(url).append('"')
        append(""","snippet":"""").append(snippet).append('"')
        append(""","summary":"""").append(summary).append('"')
        append(""","site_name":"""").append(site).append('"')
        if (date.isNotEmpty()) append(""","date":"""").append(date).append('"')
        append('}')
    }

    // ==================== 结果解析 ====================

    @Test
    fun `正常结果拼出标题摘要和链接`() {
        val raw = body(
            item(
                title = "示例模型 V4 发布",
                url = "https://example.com/sample-model-v4",
                summary = "这是一段写给模型读的长摘要。",
                date = "2026-06-01T00:00:00+00:00"
            )
        )

        val r = parseSearchResults(raw, "示例模型 V4")

        assertTrue("有结果时必须是 ok = true", r.ok)
        assertTrue(r.output.contains("示例模型 V4 发布"))
        assertTrue(r.output.contains("https://example.com/sample-model-v4"))
        // ★ 必须取 summary，不能取 snippet（snippet 是给界面看的短句）
        assertTrue(r.output.contains("这是一段写给模型读的长摘要。"))
        assertTrue(r.output.contains("Example News"))
        // 日期只留 yyyy-MM-dd
        assertTrue(r.output.contains("2026-06-01"))
        assertFalse("RFC 3339 的时间部分不应该泄漏给模型", r.output.contains("T00:00:00"))
    }

    @Test
    fun `summary_为空时退回_snippet`() {
        val raw = body(item(title = "只有短摘要", snippet = "短摘要内容", summary = ""))

        val r = parseSearchResults(raw, "q")

        assertTrue(r.ok)
        assertTrue(r.output.contains("短摘要内容"))
    }

    @Test
    fun `超过三条只取前三条`() {
        val raw = body(
            item(title = "第一条"),
            item(title = "第二条"),
            item(title = "第三条"),
            item(title = "第四条"),
            item(title = "第五条")
        )

        val r = parseSearchResults(raw, "q")

        assertTrue(r.ok)
        assertTrue(r.output.contains("第一条"))
        assertTrue(r.output.contains("第三条"))
        // 第四条往后一律丢掉：每条摘要 600~800 字，多带两条白烧 token
        assertFalse(r.output.contains("第四条"))
        assertFalse(r.output.contains("第五条"))
        assertTrue(r.output.contains("共 3 条"))
    }

    @Test
    fun `空结果是正常结果不是错误`() {
        // ★ 官方文档：查不到东西是 HTTP 200 + 空数组，不是错误。
        //   这里要是返回 ok = false，界面上会出现一张红卡片，
        //   模型还会以为"工具坏了"然后反复重试，白花钱。
        val r = parseSearchResults("""{"results":[]}""", "明天北京的天气")

        assertTrue("空结果必须是 ok = true", r.ok)
        assertTrue(r.output.contains("没有找到"))
        assertTrue(r.output.contains("明天北京的天气"))
        // 提示里必须明确让模型别重试
        assertTrue(r.output.contains("不要用同样的关键词反复搜索"))
    }

    @Test
    fun `缺少results字段也当空结果处理`() {
        val r = parseSearchResults("""{"foo":1}""", "q")

        assertTrue(r.ok)
        assertTrue(r.output.contains("没有找到"))
    }

    @Test
    fun `非法_JSON_返回失败而不是抛异常`() {
        val r = parseSearchResults("<html>502 Bad Gateway</html>", "q")

        assertFalse(r.ok)
        assertTrue(r.output.contains("搜索失败"))
    }

    @Test
    fun `空标题不会让输出变成空白`() {
        val raw = body(item(title = ""))

        val r = parseSearchResults(raw, "q")

        assertTrue(r.ok)
        assertTrue(r.output.contains("(无标题)"))
    }

    // ==================== 时间感知 ====================

    @Test
    fun `时间行格式正确且带强制句`() {
        val line = timeContextLine()

        // 要求的原句必须一字不差地出现（模型就是靠这句话建立"现在"的概念）
        assertTrue(line.contains("当前系统时间："))
        assertTrue(line.contains("。请以此时间为准进行推理。"))

        // ★ 关键断言：必须是 ASCII 数字。
        //   漏掉 Locale.US 时，某些系统语言下这里会变成阿拉伯-印度数字，
        //   正则匹配不上 —— 这条断言就是专门抓那个坑的。
        val re = Regex("""当前系统时间：\d{4}-\d{2}-\d{2} \d{2}:\d{2}。请以此时间为准进行推理。""")
        assertTrue("时间格式不对，实际输出：$line", re.containsMatchIn(line))
    }

    @Test
    fun `时间行带星期和时区`() {
        val line = timeContextLine()

        assertTrue("应该带星期几，模型才答得出「今天星期几」", line.contains("星期"))
        assertTrue("应该带时区，否则跨时区推理会差半天", line.contains("时区"))
    }

    @Test
    fun `时间行提到用_web_search_查实时信息`() {
        val line = timeContextLine()

        assertTrue(line.contains("web_search"))
    }

    @Test
    fun `时间行不是空的且足够短`() {
        val line = timeContextLine()

        assertTrue(line.isNotBlank())
        // 它挂在系统提示词最末尾 —— 太长会让每次请求都多付费。
        // 200 字符是上限，超了就说明有人往里塞东西了。
        assertTrue("时间行 ${line.length} 字符，太长了", line.length < 200)
        assertEquals(false, line.startsWith("\n"))
    }
}
