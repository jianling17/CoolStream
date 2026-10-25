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
                val content = o.optString("content", "").trim()
                if (content.isEmpty()) continue
                tasks.add(TodoItem(content, TodoStatus.of(o.optString("status", "pending"))))
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
            TodoStatus.COMPLETED -> "☑"
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
