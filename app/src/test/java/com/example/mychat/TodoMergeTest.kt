package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0：todo 卡片原地合并 + 正文状态标记剥离。
 *
 * 这两件事都在 JVM 侧就能验完，不需要模拟器：
 * 合并是纯列表运算，标记剥离是纯字符串运算。
 */
class TodoMergeTest {

    // 引号用字符码拼出来（不用反斜杠转义），避免源码里的转义把字面量写坏；
    // 只有 emoji 用 \u 形式写，那是 Kotlin 的 Unicode 转义，不会截断字符串。
    private val DQ = 34.toChar().toString()

    private fun toolMsg(name: String, extra: String = ""): ChatMessage =
        ChatMessage(
            sessionId = "s",
            content = "{" + DQ + "tool" + DQ + ":" + DQ + name + DQ + extra + "}",
            isUser = false,
            type = MessageType.TOOL_CALL
        )

    private fun textMsg(text: String): ChatMessage =
        ChatMessage(sessionId = "s", content = text, isUser = false)

    // ---------- isTodoToolPayload ----------

    @Test
    fun payloadTodo() {
        assertTrue(isTodoToolPayload(toolMsg("todo").content))
    }

    @Test
    fun payloadTodoWithMoreFields() {
        assertTrue(isTodoToolPayload(toolMsg("todo", "," + DQ + "ok" + DQ + ":true").content))
    }

    @Test
    fun payloadOtherTool() {
        assertFalse(isTodoToolPayload(toolMsg("web_search").content))
    }

    @Test
    fun payloadPrefixLookalike() {
        // "todox" 不能被当成 todo：前缀判断要求 todo 后面紧跟一个引号
        assertFalse(isTodoToolPayload(toolMsg("todox").content))
    }

    @Test
    fun payloadEmpty() {
        assertFalse(isTodoToolPayload(""))
    }

    @Test
    fun payloadPlainText() {
        assertFalse(isTodoToolPayload("这是一段普通正文，里面提到 todo 三个字"))
    }

    // ---------- mergeTodoCards ----------

    @Test
    fun mergeNoTodo() {
        val msgs = listOf(textMsg("a"), toolMsg("web_search"), textMsg("b"))
        assertEquals(3, mergeTodoCards(msgs).size)
    }

    @Test
    fun mergeSingleTodoKept() {
        val msgs = listOf(textMsg("a"), toolMsg("todo"))
        assertEquals(2, mergeTodoCards(msgs).size)
    }

    @Test
    fun mergeTwoTodosKeepsLast() {
        val first = toolMsg("todo", "," + DQ + "round" + DQ + ":1")
        val second = toolMsg("todo", "," + DQ + "round" + DQ + ":2")
        val out = mergeTodoCards(listOf(first, second))
        assertEquals(1, out.size)
        assertEquals(second.content, out[0].content)
    }

    @Test
    fun mergeKeepsPlainTextBetween() {
        val msgs = listOf(toolMsg("todo"), textMsg("第一步做完了"), toolMsg("todo"))
        val out = mergeTodoCards(msgs)
        assertEquals(2, out.size)
        assertEquals("第一步做完了", out[0].content)
    }

    @Test
    fun mergeThreeTodosKeepsOnlyLast() {
        val a = toolMsg("todo", "," + DQ + "r" + DQ + ":1")
        val b = toolMsg("todo", "," + DQ + "r" + DQ + ":2")
        val c = toolMsg("todo", "," + DQ + "r" + DQ + ":3")
        val out = mergeTodoCards(listOf(a, b, c))
        assertEquals(1, out.size)
        assertEquals(c.content, out[0].content)
    }

    @Test
    fun mergeKeepsOtherToolCards() {
        val msgs = listOf(
            toolMsg("todo"),
            toolMsg("web_search"),
            toolMsg("todo")
        )
        val out = mergeTodoCards(msgs)
        assertEquals(2, out.size)
        assertTrue(out[0].content.contains("web_search"))
    }

    @Test
    fun mergeEmptyList() {
        assertEquals(0, mergeTodoCards(emptyList()).size)
    }

    // ---------- parseTodoArgs 剥离正文标记 ----------

    private fun args(vararg pairs: Pair<String, String>): String {
        val sb = StringBuilder("{" + DQ + "plan" + DQ + ":" + DQ + "P" + DQ + "," +
            DQ + "tasks" + DQ + ":[")
        pairs.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("{").append(DQ).append("content").append(DQ).append(":").append(DQ)
            sb.append(p.first).append(DQ).append(",")
            sb.append(DQ).append("status").append(DQ).append(":").append(DQ)
            sb.append(p.second).append(DQ).append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    @Test
    fun markStrippedFromContent() {
        val p = parseTodoArgs(args("\u2705 确定目的地" to "pending"))
        assertEquals("确定目的地", p.tasks[0].content)
    }

    @Test
    fun markInfersCompletedWhenStatusMissing() {
        val p = parseTodoArgs(args("\u2705 确定目的地" to "pending"))
        assertEquals(TodoStatus.COMPLETED, p.tasks[0].status)
    }

    @Test
    fun doingMarkInfersInProgress() {
        val p = parseTodoArgs(args("\u23F3 写内容" to "pending"))
        assertEquals(TodoStatus.IN_PROGRESS, p.tasks[0].status)
    }

    @Test
    fun explicitStatusWins() {
        val p = parseTodoArgs(args("写内容" to "completed"))
        assertEquals(TodoStatus.COMPLETED, p.tasks[0].status)
    }

    @Test
    fun plainContentUntouched() {
        val p = parseTodoArgs(args("写内容" to "pending"))
        assertEquals("写内容", p.tasks[0].content)
        assertEquals(TodoStatus.PENDING, p.tasks[0].status)
    }

    @Test
    fun checklistUsesEmojiMarks() {
        val plan = TodoPlan(
            "P",
            listOf(
                TodoItem("a", TodoStatus.COMPLETED),
                TodoItem("b", TodoStatus.IN_PROGRESS),
                TodoItem("c", TodoStatus.PENDING)
            )
        )
        val text = renderTodoChecklist(plan)
        assertTrue(text.contains("\u2705 a"))
        assertTrue(text.contains("\u23F3 b"))
        assertTrue(text.contains("\u2610 c"))
    }
}