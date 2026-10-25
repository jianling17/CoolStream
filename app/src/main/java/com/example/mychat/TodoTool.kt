package com.example.mychat

import org.json.JSONObject

/**
 * v1.4.0 战役一：Agent 任务规划（Todo）系统。
 *
 * 模型调用 todo 工具时把计划清单写进 tasks，本文件负责把它解析成结构化数据，
 * 供两处共用：
 *   1. executeTool 返回给模型的文本回执（让模型看见自己列的清单，便于后续更新状态）
 *   2. 聊天气泡里的 Checklist UI（不显示冷冰冰的 JSON）
 *
 * ★ 为什么单独成文件：解析逻辑要能被 JVM 单测直接调用，不能挂在 Composable 上。
 */

/** 一步任务的状态。id 是与模型约定的线上值，不要改。 */
enum class TodoStatus(val id: String) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    companion object {
        /**
         * 宽容解析：模型偶尔会写 done / doing / 已完成 之类。
         * 认不出来就当 PENDING —— 绝不能因为一个状态词丢掉整条清单。
         */
        fun of(raw: String?): TodoStatus {
            val s = raw?.trim()?.lowercase() ?: return PENDING
            return when (s) {
                "completed", "complete", "done", "finished", "已完成", "完成" -> COMPLETED
                "in_progress", "in-progress", "doing", "active", "进行中", "正在做" -> IN_PROGRESS
                else -> PENDING
            }
        }
    }
}

/** 清单里的一步 */
data class TodoItem(val content: String, val status: TodoStatus)

/** 一次 todo 调用的完整内容 */
data class TodoPlan(val plan: String, val tasks: List<TodoItem>) {
    val completedCount: Int get() = tasks.count { it.status == TodoStatus.COMPLETED }
    val totalCount: Int get() = tasks.size
}

/**
 * 从模型给的 arguments JSON 里解析出清单。
 *
 * 全程不抛异常：参数畸形就返回空清单，由调用方给一句可读提示。
 * tasks 里缺 content 的条目直接跳过（空任务没有意义，还会撑坏 UI）。
 */
internal fun parseTodoArgs(argsJson: String): TodoPlan {
    val plan = argOf(argsJson, "plan").trim()
    val tasks = mutableListOf<TodoItem>()
    try {
        val arr = JSONObject(argsJson).optJSONArray("tasks")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val raw = o.optString("content", "").trim()
                if (raw.isEmpty()) continue
                // ★ v1.4.0：模型常把状态标记直接写进正文（「✅ 确定目的地」），
                //   卡片上就会同时出现图标和 emoji，看起来像重复了两遍。
                //   这里把正文里的标记剥掉，只用图标表达状态；标记本身则当作
                //   状态线索 —— 模型漏写 status 时全靠它。
                val content = planCleanContent(raw)
                if (content.isEmpty()) continue
                val declared = TodoStatus.of(o.optString("status", "pending"))
                val fromMark = planStatusOf(raw)
                val status = if (declared == TodoStatus.PENDING) fromMark else declared
                tasks.add(TodoItem(content, status))
            }
        }
    } catch (t: Throwable) {
        // 畸形 JSON 当作空清单处理，绝不把异常抛到调用线程
    }
    return TodoPlan(plan, tasks)
}

/**
 * 把清单渲染成纯文本 Checklist。
 *
 * 同一份文本既回给模型看（省得它忘了自己列过什么），
 * 也是气泡里 Checklist UI 的数据源（UI 再把它拆成行）。
 */
internal fun renderTodoChecklist(p: TodoPlan): String {
    val sb = StringBuilder()
    if (p.plan.isNotEmpty()) sb.append("计划：").append(p.plan).append((10).toChar())
    for (t in p.tasks) {
        val mark = when (t.status) {
            TodoStatus.COMPLETED -> "✅"
            TodoStatus.IN_PROGRESS -> "⏳"
            TodoStatus.PENDING -> "☐"
        }
        sb.append(mark).append(" ").append(t.content).append((10).toChar())
    }
    return sb.toString().trimEnd()
}

/**
 * todo 工具的执行入口。
 *
 * ★ 这个工具不碰文件、不碰网络、不碰手机系统，纯内存运算，
 *   所以不需要 withContext(IO) —— 它比一次日志打印还快。
 */
internal fun todoTool(argsJson: String): ToolExecResult {
    val p = parseTodoArgs(argsJson)
    if (p.tasks.isEmpty()) {
        return ToolExecResult(
            false,
            "参数错误：tasks 为空。请传入至少一条任务，每条含 content 和 status" +
                "（pending / in_progress / completed）。"
        )
    }
    return ToolExecResult(true, renderTodoChecklist(p))
}

// ============================================================
// v1.4.0 兜底：把 AI 正文里的计划反解成 TodoPlan
//
// 为什么需要：模型偶尔不听劝，把计划写成 Markdown 表格或「第 1 步 · xxx」纯文本。
// 这时 todo 工具根本没被调用，卡片不会出现，用户看到的只是一坨文字。
// 这里把那段文字反解成 TodoPlan，交给同一张 TodoCard 渲染，观感统一。
// ============================================================

/** 兜底解析的结果：计划本身 + 剥掉计划段之后剩下的正文 */
internal data class PlanExtraction(val plan: TodoPlan, val remaining: String)

private val PLAN_TABLE_ROW = Regex("""^\s*\|.*\|\s*$""")
private val PLAN_TABLE_SEP = Regex("""^:?-{2,}:?$""")
private val PLAN_ITEM_LINE = Regex(
    """^\s*(?:\d{1,2}[.、)．]|第\s*\d{1,2}\s*步\s*[·:：.、\-]?)\s*\S"""
)
private val PLAN_ITEM_LEAD = Regex(
    """^\s*(?:\d{1,2}[.、)．]|第\s*\d{1,2}\s*步\s*[·:：.、\-]?)\s*"""
)
private const val PLAN_MIN_TASKS = 3

/** 计划条目上可能出现的状态标记，既是状态线索，也要从正文里剥掉 */
private val PLAN_STATUS_MARKS = listOf("✅", "☑", "✔", "✓", "☐", "⬜", "🔄", "⏳", "🚧")

private fun planStatusOf(text: String): TodoStatus {
    // 用 contains 而不是 startsWith：Markdown 表格里状态标记常常落在单独一列，
    // 拼接后的整行并不以标记开头。
    val t = text.trim()
    val done = t.contains("✅") || t.contains("☑") || t.contains("✔") ||
        t.contains("✓") || t.contains("已完成")
    val doing = t.contains("🔄") || t.contains("⏳") || t.contains("🚧") || t.contains("进行中")
    return when {
        done -> TodoStatus.COMPLETED
        doing -> TodoStatus.IN_PROGRESS
        else -> TodoStatus.PENDING
    }
}

private fun planCleanContent(raw: String): String {
    var s = PLAN_ITEM_LEAD.replace(raw, "").trim()
    for (m in PLAN_STATUS_MARKS) {
        if (s.startsWith(m)) s = s.removePrefix(m).trim()
    }
    return s.trim()
}

/** 计划段上方最近的一行非空文字当作标题；剥掉 Markdown 记号与状态标记 */
private fun planTitleBefore(lines: List<String>, index: Int): String {
    for (k in index - 1 downTo 0) {
        val t = lines[k].trim()
        if (t.isEmpty()) continue
        var s = t.trimStart('#', '*', '-', '>', ' ', '\t').trim()
        for (m in PLAN_STATUS_MARKS) s = s.removePrefix(m).trim()
        s = s.trimEnd('：', ':', '。', '.', ' ')
        if (s.isEmpty()) continue
        return if (s.length > 40) s.substring(0, 40) else s
    }
    return ""
}

private fun planRemaining(lines: List<String>, block: List<Int>): String {
    val drop = block.toHashSet()
    val kept = lines.filterIndexed { idx, _ -> idx !in drop }
    return kept.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
}

private fun planTableCells(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

/**
 * 从一段 AI 正文里找出「计划段」并反解成 TodoPlan。
 *
 * 认两种形态：
 *   ① Markdown 表格（表头 + 分隔行 + 至少 3 行数据）；
 *   ② 编号列表 /「第 N 步 · xxx」连续至少 3 条。
 * 认不出、或者条目不够 3 条就返回 null —— 宁可不动，也不要把普通正文错认成计划。
 */
internal fun extractPlanFromText(raw: String): PlanExtraction? {
    if (raw.isBlank()) return null
    val lines = raw.split("\n")

    // ---------- ① Markdown 表格 ----------
    var i = 0
    while (i < lines.size) {
        if (!PLAN_TABLE_ROW.matches(lines[i])) { i++; continue }
        var j = i
        while (j < lines.size && PLAN_TABLE_ROW.matches(lines[j])) j++
        val block = (i until j).toList()
        if (block.size >= PLAN_MIN_TASKS + 2) {
            val rows = block.map { planTableCells(lines[it]) }
            val tasks = rows.drop(2).mapNotNull { cells ->
                val content = cells.firstOrNull {
                    it.isNotBlank() && it.toIntOrNull() == null &&
                        !PLAN_TABLE_SEP.matches(it) && it !in PLAN_STATUS_MARKS
                } ?: return@mapNotNull null
                TodoItem(content = content, status = planStatusOf(cells.joinToString(" ")))
            }
            if (tasks.size >= PLAN_MIN_TASKS) {
                return PlanExtraction(
                    plan = TodoPlan(plan = planTitleBefore(lines, i), tasks = tasks),
                    remaining = planRemaining(lines, block)
                )
            }
        }
        i = j
    }

    // ---------- ② 编号列表 / 第 N 步 ----------
    i = 0
    while (i < lines.size) {
        if (!PLAN_ITEM_LINE.containsMatchIn(lines[i])) { i++; continue }
        var j = i
        val block = mutableListOf<Int>()
        val tasks = mutableListOf<TodoItem>()
        while (j < lines.size) {
            val line = lines[j]
            if (PLAN_ITEM_LINE.containsMatchIn(line)) {
                block.add(j)
                tasks.add(TodoItem(content = planCleanContent(line), status = planStatusOf(line)))
                j++
            } else if (line.isNotBlank() && j + 1 < lines.size &&
                PLAN_ITEM_LINE.containsMatchIn(lines[j + 1]) && tasks.isNotEmpty()
            ) {
                // 紧跟在条目下面的说明行并进上一条，别让它变成散落正文
                block.add(j)
                val last = tasks.removeAt(tasks.size - 1)
                tasks.add(last.copy(content = (last.content + " " + line.trim()).trim()))
                j++
            } else {
                break
            }
        }
        if (tasks.size >= PLAN_MIN_TASKS) {
            return PlanExtraction(
                plan = TodoPlan(plan = planTitleBefore(lines, i), tasks = tasks.toList()),
                remaining = planRemaining(lines, block)
            )
        }
        i = if (j > i) j else i + 1
    }
    return null
}