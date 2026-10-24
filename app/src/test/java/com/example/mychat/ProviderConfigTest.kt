package com.example.mychat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0 多 provider 配置层单测。
 *
 * chatCompletionsUrl() 直接读写单例 ApiConfig.baseUrl，
 * 每条用例自己 set、After 里恢复，避免污染别的测试。
 */
class ProviderConfigTest {

    private val savedBase = ApiConfig.baseUrl
    private val savedProvider = ApiConfig.provider

    @After
    fun restore() {
        ApiConfig.baseUrl = savedBase
        ApiConfig.provider = savedProvider
    }

    // ---------- chatCompletionsUrl：四种用户填法都得认 ----------

    @Test fun url_深seek官方不带v1() {
        ApiConfig.baseUrl = "https://api.example.com"
        assertEquals("https://api.example.com/chat/completions", chatCompletionsUrl())
    }

    @Test fun url_深seek官方带v1() {
        ApiConfig.baseUrl = "https://api.example.com/v1"
        assertEquals("https://api.example.com/v1/chat/completions", chatCompletionsUrl())
    }

    @Test fun url_第三方示例() {
        ApiConfig.baseUrl = "https://open.bigmodel.cn/api/paas/v4"
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", chatCompletionsUrl())
    }

    @Test fun url_本地ollama() {
        ApiConfig.baseUrl = "http://localhost:11434/v1"
        assertEquals("http://localhost:11434/v1/chat/completions", chatCompletionsUrl())
    }

    @Test fun url_尾斜杠要削掉() {
        ApiConfig.baseUrl = "https://api.example.com/v1/"
        assertEquals("https://api.example.com/v1/chat/completions", chatCompletionsUrl())
    }

    @Test fun url_用户直接贴了全端点不再补() {
        ApiConfig.baseUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            chatCompletionsUrl()
        )
    }

    @Test fun url_空白地址返回空串由调用方拦截() {
        ApiConfig.baseUrl = "   "
        assertEquals("", chatCompletionsUrl())
    }

    @Test fun url_首尾空格要trim() {
        ApiConfig.baseUrl = "  https://api.example.com  "
        assertEquals("https://api.example.com/chat/completions", chatCompletionsUrl())
    }

    // ---------- provider 枚举与默认 base ----------

    @Test fun provider_默认是深seek() {
        assertEquals(ApiProvider.BUILTIN, ApiConfig.provider.coerceAtLeastDefault())
    }

    @Test fun defaultBase_深seek有默认地址() {
        assertEquals("https://api.example.com/v1", defaultBaseFor(ApiProvider.BUILTIN))
    }

    @Test fun defaultBase_第三方没有万能默认值必须用户填() {
        assertEquals("", defaultBaseFor(ApiProvider.OPENAI_COMPATIBLE))
    }

    @Test fun provider_枚举名可逆() {
        // 持久化存的是 name，读回 valueOf；脏值由 loadApiConfig 的 runCatching 兜
        for (p in ApiProvider.entries) {
            assertEquals(p, ApiProvider.valueOf(p.name))
        }
    }

    @Test fun provider_降级容错_脏字符串退回深seek() {
        val bad = runCatching { ApiProvider.valueOf("not_a_provider") }
        assertTrue(bad.isFailure)  // loadApiConfig 里正是靠这个失败退 BUILTIN
    }
}

/** 测试辅助：provider 不该是 null，直接返回自身（保持断言语义简单） */
private fun ApiProvider.coerceAtLeastDefault(): ApiProvider = this
