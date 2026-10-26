package com.example.mychat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 长期记忆库（v1.5.0 模块一）的单元测试。
 *
 * 为什么值得测这么细：记忆库是「用户以为它一定记得住」的东西，
 * 解析写坏一条就整库读不出来，写坏一次就把用户之前存的全冲掉。
 * 这里的用例把三件事钉死：坏文件不崩、追加不丢旧数据、检索不乱给结果。
 *
 * 全部跑在电脑 JVM 上（不碰 Context），运行：./gradlew testDebugUnitTest
 */
class MemoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun item(id: String, content: String, at: Long = 1000L) =
        MemoryItem(id = id, content = content, createdAt = at)

    // ---------- 解析：坏数据绝不能让整库消失 ----------

    @Test
    fun parseEmptyStringReturnsEmpty() {
        assertTrue(MemoryStore.parse("").isEmpty())
    }

    @Test
    fun parseBlankStringReturnsEmpty() {
        assertTrue(MemoryStore.parse("   ").isEmpty())
    }

    @Test
    fun parseCorruptJsonReturnsEmpty() {
        assertTrue(MemoryStore.parse("{this is not json").isEmpty())
    }

    @Test
    fun parseJsonObjectInsteadOfArrayReturnsEmpty() {
        assertTrue(MemoryStore.parse("{}").isEmpty())
    }

    @Test
    fun parseSkipsEntriesWithBlankContent() {
        val arr = JSONArray()
        arr.put(JSONObject().put("id", "a").put("content", "").put("createdAt", 1L))
        arr.put(JSONObject().put("id", "b").put("content", "real").put("createdAt", 2L))
        val list = MemoryStore.parse(arr.toString())
        assertEquals(1, list.size)
        assertEquals("real", list[0].content)
    }

    @Test
    fun parseGivesLegacyIdWhenIdMissing() {
        val arr = JSONArray()
        arr.put(JSONObject().put("content", "old entry").put("createdAt", 5L))
        val list = MemoryStore.parse(arr.toString())
        assertEquals(1, list.size)
        assertTrue(list[0].id.startsWith("legacy-"))
        assertEquals("old entry", list[0].content)
    }

    @Test
    fun parseKeepsCreatedAt() {
        val arr = JSONArray()
        arr.put(JSONObject().put("id", "x").put("content", "c").put("createdAt", 123456789L))
        assertEquals(123456789L, MemoryStore.parse(arr.toString())[0].createdAt)
    }

    // ---------- 序列化往返 ----------

    @Test
    fun serializeEmptyListIsEmptyArray() {
        assertEquals("[]", MemoryStore.serialize(emptyList()))
    }

    @Test
    fun serializeThenParseRoundTrips() {
        val src = listOf(item("a", "第一件事"), item("b", "second", 2000L))
        val back = MemoryStore.parse(MemoryStore.serialize(src))
        assertEquals(2, back.size)
        assertEquals("a", back[0].id)
        assertEquals("第一件事", back[0].content)
        assertEquals(1000L, back[0].createdAt)
        assertEquals("second", back[1].content)
        assertEquals(2000L, back[1].createdAt)
    }

    // ---------- 文件读取 ----------

    @Test
    fun loadMissingFileReturnsEmpty() {
        val f = File(tmp.root, "nope.json")
        assertTrue(MemoryStore.load(f).isEmpty())
    }

    @Test
    fun loadCorruptFileReturnsEmpty() {
        val f = File(tmp.root, "bad.json")
        f.writeText("not json at all")
        assertTrue(MemoryStore.load(f).isEmpty())
    }

    // ---------- 追加 ----------

    @Test
    fun appendCreatesFileAndReturnsItem() {
        val f = File(tmp.root, "memory.json")
        val saved = MemoryStore.append(f, "用户喜欢 Kotlin")
        assertTrue(f.exists())
        assertEquals("用户喜欢 Kotlin", saved!!.content)
        assertEquals(1, MemoryStore.load(f).size)
    }

    @Test
    fun appendKeepsPreviousEntries() {
        val f = File(tmp.root, "memory.json")
        MemoryStore.append(f, "第一条")
        MemoryStore.append(f, "第二条")
        val all = MemoryStore.load(f)
        assertEquals(2, all.size)
        assertEquals("第一条", all[0].content)
        assertEquals("第二条", all[1].content)
    }

    @Test
    fun appendTrimsSurroundingWhitespace() {
        val f = File(tmp.root, "memory.json")
        val saved = MemoryStore.append(f, "  带空格的内容  ")
        assertEquals("带空格的内容", saved!!.content)
    }

    @Test
    fun appendBlankContentReturnsNull() {
        val f = File(tmp.root, "memory.json")
        assertNull(MemoryStore.append(f, "   "))
        assertFalse(f.exists())
    }

    @Test
    fun appendTruncatesOverlongContent() {
        val f = File(tmp.root, "memory.json")
        val long = "字".repeat(MEMORY_MAX_CHARS + 500)
        val saved = MemoryStore.append(f, long)
        assertEquals(MEMORY_MAX_CHARS, saved!!.content.length)
    }

    @Test
    fun appendGivesUniqueIds() {
        val f = File(tmp.root, "memory.json")
        val a = MemoryStore.append(f, "one")
        val b = MemoryStore.append(f, "two")
        assertNotEquals(a!!.id, b!!.id)
    }

    @Test
    fun appendHonoursProvidedTimestamp() {
        val f = File(tmp.root, "memory.json")
        val saved = MemoryStore.append(f, "x", now = 42L)
        assertEquals(42L, saved!!.createdAt)
    }

    @Test
    fun appendDropsOldestWhenOverLimit() {
        val f = File(tmp.root, "memory.json")
        val seed = (1..MEMORY_MAX_ITEMS).map { item("old-" + it, "旧记忆 " + it, it.toLong()) }
        f.writeText(MemoryStore.serialize(seed))
        MemoryStore.append(f, "最新一条")
        val all = MemoryStore.load(f)
        assertEquals(MEMORY_MAX_ITEMS, all.size)
        assertFalse(all.any { it.id == "old-1" })
        assertEquals("最新一条", all.last().content)
    }

    // ---------- 删除 ----------

    @Test
    fun deleteRemovesMatchingId() {
        val f = File(tmp.root, "memory.json")
        val a = MemoryStore.append(f, "要删的")
        MemoryStore.append(f, "要留的")
        assertTrue(MemoryStore.delete(f, a!!.id))
        val all = MemoryStore.load(f)
        assertEquals(1, all.size)
        assertEquals("要留的", all[0].content)
    }

    @Test
    fun deleteUnknownIdReturnsFalse() {
        val f = File(tmp.root, "memory.json")
        MemoryStore.append(f, "keep")
        assertFalse(MemoryStore.delete(f, "not-there"))
        assertEquals(1, MemoryStore.load(f).size)
    }

    @Test
    fun deleteBlankIdReturnsFalse() {
        val f = File(tmp.root, "memory.json")
        MemoryStore.append(f, "keep")
        assertFalse(MemoryStore.delete(f, "  "))
    }

    @Test
    fun deleteOnMissingFileReturnsFalse() {
        val f = File(tmp.root, "ghost.json")
        assertFalse(MemoryStore.delete(f, "any"))
    }

    // ---------- 检索词切分 ----------

    @Test
    fun tokenizeEmptyReturnsEmpty() {
        assertTrue(MemoryStore.tokenize("").isEmpty())
    }

    @Test
    fun tokenizeSplitsOnSpaces() {
        val t = MemoryStore.tokenize("hello world")
        assertTrue(t.contains("hello"))
        assertTrue(t.contains("world"))
    }

    @Test
    fun tokenizeLowercases() {
        assertTrue(MemoryStore.tokenize("Kotlin").contains("kotlin"))
    }

    @Test
    fun tokenizeSplitsOnPunctuation() {
        val t = MemoryStore.tokenize("a,b。c")
        assertTrue(t.contains("a"))
        assertTrue(t.contains("b"))
        assertTrue(t.contains("c"))
    }

    @Test
    fun tokenizeAddsBigramsForLongCjkToken() {
        val t = MemoryStore.tokenize("旅行偏好")
        assertTrue(t.contains("旅行偏好"))
        assertTrue(t.contains("旅行"))
        assertTrue(t.contains("行偏"))
        assertTrue(t.contains("偏好"))
    }

    @Test
    fun tokenizeKeepsShortCjkTokenAsIs() {
        val t = MemoryStore.tokenize("旅行")
        assertTrue(t.contains("旅行"))
        assertEquals(1, t.size)
    }

    @Test
    fun tokenizeHandlesMixedQuery() {
        val t = MemoryStore.tokenize("Kotlin 偏好")
        assertTrue(t.contains("kotlin"))
        assertTrue(t.contains("偏好"))
    }

    // ---------- 打分 ----------

    @Test
    fun scoreWithoutMatchIsZero() {
        assertEquals(0, MemoryStore.score("毫不相干", listOf("旅行"), "旅行"))
    }

    @Test
    fun scoreEmptyContentIsZero() {
        assertEquals(0, MemoryStore.score("", listOf("a"), "a"))
    }

    @Test
    fun scoreEmptyTokensIsZero() {
        assertEquals(0, MemoryStore.score("abc", emptyList(), "abc"))
    }

    @Test
    fun scoreWholeQueryMatchGetsBonus() {
        assertEquals(25, MemoryStore.score("我喜欢旅行", listOf("旅行"), "旅行"))
    }

    @Test
    fun scoreIsCaseInsensitive() {
        assertTrue(MemoryStore.score("User likes KOTLIN", listOf("kotlin"), "kotlin") > 0)
    }

    // ---------- 排序与截断 ----------

    @Test
    fun rankReturnsEmptyWhenNothingMatches() {
        val all = listOf(item("a", "喜欢喝咖啡"))
        assertTrue(MemoryStore.rank(all, "部署方式").isEmpty())
    }

    @Test
    fun rankPutsHigherScoreFirst() {
        val all = listOf(
            item("weak", "旅行 一句话", 100L),
            item("strong", "旅行计划", 50L)
        )
        val hits = MemoryStore.rank(all, "旅行 计划")
        assertEquals(2, hits.size)
        assertEquals("strong", hits[0].id)
    }

    @Test
    fun rankBreaksTieByNewerFirst() {
        val all = listOf(
            item("older", "喜欢旅行", 100L),
            item("newer", "喜欢旅行", 900L)
        )
        val hits = MemoryStore.rank(all, "旅行")
        assertEquals("newer", hits[0].id)
    }

    @Test
    fun rankRespectsLimit() {
        val all = (1..5).map { item("m" + it, "旅行计划 " + it, it.toLong()) }
        assertEquals(2, MemoryStore.rank(all, "旅行", limit = 2).size)
    }

    @Test
    fun rankWithBlankQueryReturnsEmpty() {
        val all = listOf(item("a", "任何内容"))
        assertTrue(MemoryStore.rank(all, "   ").isEmpty())
    }

    // ---------- 结果格式化 ----------

    @Test
    fun formatTimeZeroIsUnknown() {
        assertEquals("未知时间", MemoryStore.formatTime(0L))
    }

    @Test
    fun formatTimeIsMinutePrecision() {
        val s = MemoryStore.formatTime(1700000000000L)
        assertEquals(16, s.length)
    }

    @Test
    fun formatRecallEmptySaysNoResult() {
        val s = MemoryStore.formatRecall(emptyList(), "部署方式")
        assertTrue(s.contains("没有"))
        assertTrue(s.contains("部署方式"))
    }

    @Test
    fun formatRecallNumbersEntries() {
        val hits = listOf(item("a", "第一条", 1000L), item("b", "第二条", 2000L))
        val s = MemoryStore.formatRecall(hits, "第")
        assertTrue(s.contains("找到 2 条"))
        assertTrue(s.contains("1. "))
        assertTrue(s.contains("2. "))
        assertTrue(s.contains("第一条"))
        assertTrue(s.contains("第二条"))
    }

    @Test
    fun maxItemsConstantIsSane() {
        assertTrue(MEMORY_MAX_ITEMS >= 50)
        assertTrue(MEMORY_MAX_CHARS >= 500)
        assertTrue(MEMORY_RECALL_LIMIT >= 1)
    }
}
