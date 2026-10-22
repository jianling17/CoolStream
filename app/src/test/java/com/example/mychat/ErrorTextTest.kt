package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 错误文案（ErrorText）的单元测试。
 *
 * 这些测试跑在普通 JVM 上，不需要真机 —— 因为 ErrorText 刻意做成了
 * 「纯数据 + 资源 id」，不带 Context、不带 Android 依赖。
 *
 * ★ 这组测试真正在守的是什么：
 *   1. 状态码 → 资源 id 的映射没有接错（401 不能变成 402 的文案）
 *   2. 需要占位符的地方（5xx 的状态码、HTTP 的 body 摘要）参数确实带上了
 *   3. 各类网络异常分别走各自的文案，没有被 else 分支吞掉
 *
 * ⚠️ 这里【不】断言最终的字符串内容 —— 那取决于语言资源，
 *    真正的文案由 resolve(context) 在设备上渲染。
 */
class ErrorTextTest {

    @Test
    fun `401 映射到 API Key 无效`() {
        val e = httpErrorText(401, "")
        assertEquals(R.string.err_401, e.resId)
        assertTrue("401 不需要额外参数", e.args.isEmpty())
    }

    @Test
    fun `402 映射到余额不足`() {
        assertEquals(R.string.err_402, httpErrorText(402, "").resId)
    }

    @Test
    fun `403 映射到无访问权限`() {
        assertEquals(R.string.err_403, httpErrorText(403, "").resId)
    }

    @Test
    fun `404 映射到接口地址不对`() {
        assertEquals(R.string.err_404, httpErrorText(404, "").resId)
    }

    @Test
    fun `429 映射到请求太频繁`() {
        assertEquals(R.string.err_429, httpErrorText(429, "").resId)
    }

    @Test
    fun `5xx 带上状态码参数`() {
        val e = httpErrorText(503, "")
        assertEquals(R.string.err_5xx, e.resId)
        assertEquals(listOf<Any>(503), e.args)
    }

    @Test
    fun `500 也走 5xx 分支`() {
        assertEquals(R.string.err_5xx, httpErrorText(500, "").resId)
        assertEquals(R.string.err_5xx, httpErrorText(599, "").resId)
    }

    @Test
    fun `未知状态码且 body 为空时只显示 HTTP 码`() {
        val e = httpErrorText(418, "   ")
        assertEquals(R.string.err_http, e.resId)
        assertEquals(listOf<Any>(418), e.args)
    }

    @Test
    fun `未知状态码带 body 时附上摘要`() {
        val e = httpErrorText(418, "  teapot  ")
        assertEquals(R.string.err_http_body, e.resId)
        assertEquals(418, e.args[0])
        assertEquals("teapot", e.args[1])   // 首尾空白要被 trim 掉
    }

    @Test
    fun `body 过长时截断到 200 字`() {
        val long = "x".repeat(500)
        val e = httpErrorText(418, long)
        assertEquals(200, (e.args[1] as String).length)
    }

    @Test
    fun `UnknownHostException 映射到无网络`() {
        assertEquals(R.string.err_no_network, networkErrorText(UnknownHostException("x")).resId)
    }

    @Test
    fun `SocketTimeoutException 映射到超时`() {
        assertEquals(R.string.err_timeout, networkErrorText(SocketTimeoutException("x")).resId)
    }

    @Test
    fun `SSLException 映射到安全连接失败`() {
        assertEquals(R.string.err_ssl, networkErrorText(SSLHandshakeException("x")).resId)
    }

    @Test
    fun `普通 IOException 带上原始信息`() {
        val e = networkErrorText(java.io.IOException("connection reset"))
        assertEquals(R.string.err_io, e.resId)
        assertEquals("connection reset", e.args[0])
    }

    @Test
    fun `IOException 没有 message 时退回类名`() {
        val e = networkErrorText(java.io.IOException())
        assertEquals(R.string.err_io, e.resId)
        assertEquals("IOException", e.args[0])
    }

    @Test
    fun `其它异常走兜底并带上类名`() {
        val e = networkErrorText(IllegalStateException("boom"))
        assertEquals(R.string.err_unknown, e.resId)
        assertEquals("IllegalStateException", e.args[0])
        assertEquals("boom", e.args[1])
    }

    @Test
    fun `不同的状态码不会映射到同一个资源`() {
        // 防止复制粘贴时把 401 的 resId 抄成 402 的
        val codes = listOf(401, 402, 403, 404, 429)
        val ids = codes.map { httpErrorText(it, "").resId }
        assertEquals("五个状态码应各有独立文案", codes.size, ids.toSet().size)
    }

    @Test
    fun `ErrorText 是可比较的纯数据`() {
        // data class 的 equals 行为：同样的 resId + args 视为相等。
        // 这让「错误去重 / 测试断言」都不用写额外代码。
        assertEquals(ErrorText(R.string.err_401), ErrorText(R.string.err_401))
        assertNotEquals(ErrorText(R.string.err_401), ErrorText(R.string.err_402))
        assertNotEquals(
            ErrorText(R.string.err_5xx, listOf(500)),
            ErrorText(R.string.err_5xx, listOf(501))
        )
    }
}
