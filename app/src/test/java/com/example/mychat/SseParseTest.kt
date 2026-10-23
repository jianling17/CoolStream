package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 行解析测试：正文分片 / 工具调用分片 / usage。
 *
 * 这三个函数是流式渲染的入口，它们的容错策略是【一律返回 null 而不是抛异常】：
 * 服务端会插心跳包、会发空 choices 的 usage 行、会在分片里给 JSON null。
 * 任何一个漏网的异常都会把整条 SSE 循环打断，用户看到的就是「回复卡住」。
 */
class SseParseTest {

    // ==================== parseDelta ====================

    @Test
    fun delta_simpleContent() {
        assertEquals("hi", parseDelta("""{"choices":[{"delta":{"content":"hi"}}]}"""))
    }

    @Test
    fun delta_emptyContent_returnsNull() {
        assertNull(parseDelta("""{"choices":[{"delta":{"content":""}}]}"""))
    }

    @Test
    fun delta_literalNullString_returnsNull() {
        assertNull(parseDelta("""{"choices":[{"delta":{"content":"null"}}]}"""))
    }

    @Test
    fun delta_jsonNull_returnsNull() {
        assertNull(parseDelta("""{"choices":[{"delta":{"content":null}}]}"""))
    }

    @Test
    fun delta_numberContent_returnsNull() {
        assertNull(parseDelta("""{"choices":[{"delta":{"content":123}}]}"""))
    }

    @Test
    fun delta_booleanContent_returnsNull() {
        assertNull(parseDelta("""{"choices":[{"delta":{"content":false}}]}"""))
    }

    @Test
    fun delta_noChoices_returnsNull() {
        assertNull(parseDelta("{}"))
    }

    @Test
    fun delta_emptyChoices_returnsNull() {
        assertNull(parseDelta("""{"choices":[]}"""))
    }

    @Test
    fun delta_noDelta_returnsNull() {
        assertNull(parseDelta("""{"choices":[{"message":{"content":"hi"}}]}"""))
    }

    @Test
    fun delta_malformedJson_returnsNull() {
        assertNull(parseDelta("{"))
    }

    @Test
    fun delta_blankInput_returnsNull() {
        assertNull(parseDelta(""))
    }

    @Test
    fun delta_unicodeContent() {
        assertEquals("你好世界", parseDelta("""{"choices":[{"delta":{"content":"你好世界"}}]}"""))
    }

    @Test
    fun delta_spacesPreserved() {
        assertEquals(" a ", parseDelta("""{"choices":[{"delta":{"content":" a "}}]}"""))
    }

    @Test
    fun delta_roleOnly_returnsNull() {
        assertNull(parseDelta("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
    }

    @Test
    fun delta_otherFieldsIgnored() {
        val json = """{"id":"x","choices":[{"index":0,"delta":{"content":"hi","role":"assistant"}}]}"""
        assertEquals("hi", parseDelta(json))
    }

    // ==================== parseDeltaFull ====================

    @Test
    fun full_contentOnly_noToolCalls() {
        val info = parseDeltaFull("""{"choices":[{"delta":{"content":"hi"}}]}""")
        assertNotNull(info)
        assertEquals("hi", info!!.content)
        assertEquals(0, info.toolCalls.size)
    }

    @Test
    fun full_toolCallPiece_parsed() {
        val json = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"read_file","arguments":"{}"}}]}}]}"""
        val info = parseDeltaFull(json)
        assertNotNull(info)
        assertEquals(1, info!!.toolCalls.size)
        val p = info.toolCalls[0]
        assertEquals(0, p.index)
        assertEquals("call_a", p.id)
        assertEquals("read_file", p.name)
        assertEquals("{}", p.arguments)
    }

    @Test
    fun full_missingId_isNull() {
        val json = """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"f"}}]}}]}"""
        val info = parseDeltaFull(json)
        assertNotNull(info)
        assertNull(info!!.toolCalls[0].id)
    }

    @Test
    fun full_emptyArguments_isNull() {
        val json = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"f","arguments":""}}]}}]}"""
        val info = parseDeltaFull(json)
        assertNotNull(info)
        assertNull(info!!.toolCalls[0].arguments)
    }

    @Test
    fun full_emptyToolCalls_isEmptyList() {
        val info = parseDeltaFull("""{"choices":[{"delta":{"tool_calls":[]}}]}""")
        assertNotNull(info)
        assertEquals(0, info!!.toolCalls.size)
    }

    @Test
    fun full_emptyDeltaObject() {
        val info = parseDeltaFull("""{"choices":[{"delta":{}}]}""")
        assertNotNull(info)
        assertNull(info!!.content)
        assertEquals(0, info.toolCalls.size)
    }

    @Test
    fun full_twoPieces_keptInOrder() {
        val json = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a"},{"index":1,"id":"b"}]}}]}"""
        val info = parseDeltaFull(json)
        assertNotNull(info)
        assertEquals(2, info!!.toolCalls.size)
        assertEquals("a", info.toolCalls[0].id)
        assertEquals("b", info.toolCalls[1].id)
    }

    @Test
    fun full_noDelta_returnsNull() {
        assertNull(parseDeltaFull("""{"choices":[{"index":0}]}"""))
    }

    @Test
    fun full_noChoices_returnsNull() {
        assertNull(parseDeltaFull("{}"))
    }

    @Test
    fun full_malformed_returnsNull() {
        assertNull(parseDeltaFull("not json"))
    }

    // ==================== parseUsage ====================

    @Test
    fun usage_fullFields_parsed() {
        val json = """{"usage":{"prompt_tokens":1000,"prompt_cache_hit_tokens":400,"completion_tokens":50}}"""
        val u = parseUsage(json)
        assertNotNull(u)
        assertEquals(1000, u!!.promptTokens)
        assertEquals(400, u.cacheHitTokens)
        assertEquals(50, u.completionTokens)
        assertEquals(600, u.cacheMissTokens)
    }

    @Test
    fun usage_missingUsage_returnsNull() {
        assertNull(parseUsage("""{"choices":[]}"""))
    }

    @Test
    fun usage_emptyObject_returnsNull() {
        assertNull(parseUsage("""{"usage":{}}"""))
    }

    @Test
    fun usage_missingHit_defaultsToZero() {
        val u = parseUsage("""{"usage":{"prompt_tokens":10,"completion_tokens":2}}""")
        assertNotNull(u)
        assertEquals(0, u!!.cacheHitTokens)
    }

    @Test
    fun usage_missingCompletion_defaultsToZero() {
        val u = parseUsage("""{"usage":{"prompt_tokens":10}}""")
        assertNotNull(u)
        assertEquals(0, u!!.completionTokens)
    }

    @Test
    fun usage_extraFields_ignored() {
        val u = parseUsage("""{"usage":{"prompt_tokens":1,"total_tokens":99,"completion_tokens":2}}""")
        assertNotNull(u)
        assertEquals(1, u!!.promptTokens)
        assertEquals(2, u.completionTokens)
    }

    @Test
    fun usage_malformed_returnsNull() {
        assertNull(parseUsage("}{"))
    }
}
