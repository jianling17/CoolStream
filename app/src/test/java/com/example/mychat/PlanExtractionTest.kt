package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 UI 兜底：模型不听劝、把计划写成 Markdown 表格或纯文本时，
 * 客户端把它反解成 TodoPlan 再用同一张卡片渲染。
 */
class PlanExtractionTest {

    private val NL = 10.toChar().toString()

    private fun doc(vararg lines: String) = lines.joinToString(NL)

    // ---------- Markdown 表格 ----------

    private val tableDoc = doc(
        "出行计划：",
        "",
        "| 步骤 | 内容 | 状态 |",
        "| --- | --- | --- |",
        "| 1 | 确定目的地 | 待办 |",
        "| 2 | 订机票 | 进行中 |",
        "| 3 | 订酒店 | 已完成 |"
    )

    @Test
    fun tableParsed() {
        val r = extractPlanFromText(tableDoc)
        assertNotNull(r)
        assertEquals(3, r!!.plan.tasks.size)
        assertEquals("确定目的地", r.plan.tasks[0].content)
        assertEquals("订机票", r.plan.tasks[1].content)
        assertEquals("订酒店", r.plan.tasks[2].content)
    }

    @Test
    fun tableStatusMapped() {
        val r = extractPlanFromText(tableDoc)!!
        assertEquals(TodoStatus.PENDING, r.plan.tasks[0].status)
        assertEquals(TodoStatus.IN_PROGRESS, r.plan.tasks[1].status)
        assertEquals(TodoStatus.COMPLETED, r.plan.tasks[2].status)
    }

    @Test
    fun tableTitleTaken() {
        assertEquals("出行计划", extractPlanFromText(tableDoc)!!.plan.plan)
    }

    @Test
    fun tableRemovedFromRemaining() {
        val r = extractPlanFromText(tableDoc)!!
        assertTrue(r.remaining.contains("出行计划"))
        assertTrue(!r.remaining.contains("订机票"))
    }

    @Test
    fun tableTooShortIsNull() {
        val short = doc(
            "| 步骤 | 内容 |",
            "| --- | --- |",
            "| 1 | 确定目的地 |",
            "| 2 | 订机票 |"
        )
        assertNull(extractPlanFromText(short))
    }

    // ---------- 编号列表 ----------

    @Test
    fun numberedListParsed() {
        val r = extractPlanFromText(doc("旅行计划", "1. 定框架", "2. 写内容", "3. 发布"))
        assertNotNull(r)
        assertEquals(3, r!!.plan.tasks.size)
        assertEquals("定框架", r.plan.tasks[0].content)
        assertEquals("旅行计划", r.plan.plan)
    }

    @Test
    fun numberedListLeavesNoNumber() {
        val r = extractPlanFromText(doc("1. 定框架", "2. 写内容", "3. 发布"))!!
        assertTrue(!r.plan.tasks[0].content.contains("1."))
    }

    @Test
    fun stepStyleParsed() {
        val r = extractPlanFromText(
            doc("第 1 步 · 定框架", "第 2 步 · 写内容", "第 3 步 · 发布")
        )
        assertNotNull(r)
        assertEquals(3, r!!.plan.tasks.size)
        assertEquals("定框架", r.plan.tasks[0].content)
        assertEquals("", r.plan.plan)
    }

    @Test
    fun listTooShortIsNull() {
        assertNull(extractPlanFromText(doc("1. 定框架", "2. 写内容")))
    }

    @Test
    fun continuationLineMerged() {
        val r = extractPlanFromText(
            doc("1. 定框架", "先确定整体结构", "2. 写内容", "3. 发布")
        )!!
        assertEquals(3, r.plan.tasks.size)
        assertTrue(r.plan.tasks[0].content.contains("先确定整体结构"))
    }

    @Test
    fun listRemovedFromRemaining() {
        val r = extractPlanFromText(doc("旅行计划", "1. 定框架", "2. 写内容", "3. 发布"))!!
        assertEquals("旅行计划", r.remaining)
    }

    // ---------- 不该误判 ----------

    @Test
    fun blankIsNull() {
        assertNull(extractPlanFromText(""))
    }

    @Test
    fun proseIsNull() {
        assertNull(
            extractPlanFromText(
                doc("今天天气不错。", "我去了一趟超市，买了点水果。", "回来的路上顺便修了自行车。")
            )
        )
    }

    @Test
    fun markdownBulletNotTreatedAsPlan() {
        // 无序列表不算计划：那是正文要点，不是步骤
        assertNull(extractPlanFromText(doc("- 苹果", "- 香蕉", "- 橙子")))
    }
}