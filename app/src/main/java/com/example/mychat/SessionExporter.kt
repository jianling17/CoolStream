package com.example.mychat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
// 战役六 · 功能三：导出会话为 Markdown
//
// ★ 这【不是】LLM 工具：
//   它由侧边栏会话的三点菜单触发，是界面动作 → 不需要 JSON Schema，
//   也不进 executeTool 的 when 分发。
//
// ★ 线程契约：写成 suspend fun 并自带 withContext(Dispatchers.IO)。
//   （LLM 工具则相反，写成同步函数交给 executeTool 的 IO 协程承载。）
//
// ★ 结构：纯函数（可 JVM 单测） + 薄 IO 壳，对齐 persistOversizeToolOutput 的风格。
// ============================================================

/** 导出文件名里必须替换掉的非法字符（Windows / Android 文件系统通用黑名单） */
private val ILLEGAL_FILENAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/** 标题参与文件名时的最大长度 */
private const val EXPORT_TITLE_MAX_CHARS = 40

/** 标题为空时的兜底名 */
private const val DEFAULT_SESSION_TITLE = "未命名会话"

/** 文件名时间戳格式 */
private const val EXPORT_FILE_TIME_PATTERN = "yyyyMMdd-HHmmss"

/** 正文头部的时间格式 */
private const val EXPORT_HEAD_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

/**
 * 【纯函数】把会话标题清洗成可安全用于文件名的片段。
 *
 * 规则：非法字符 → `_`；控制字符丢弃；连续空白折叠成单个空格；
 *      去掉首尾的点/空格/全角空格/制表符；空则兜底「未命名会话」；截断到 40 字符。
 *
 * ⚠️ 为什么要专门去掉首尾的「点」和「全角空格」：
 *   Android/Windows 都不接受以点结尾的文件名；全角空格（U+3000）trim() 默认不认，
 *   不显式列出就会残留下来，生成一个看不见的怪文件名。
 */
internal fun sanitizeExportTitle(rawTitle: String): String {
    if (rawTitle.isBlank()) return DEFAULT_SESSION_TITLE

    val sb = StringBuilder(rawTitle.length)
    for (ch in rawTitle) {
        when {
            ch in ILLEGAL_FILENAME_CHARS -> sb.append('_')
            ch.code < 0x20 || ch.code == 0x7F -> { /* 控制字符：直接丢弃 */ }
            else -> sb.append(ch)
        }
    }

    val collapsed = sb.toString().replace(Regex("""\s+"""), " ").trim()
    val cleaned = collapsed.trim('.', ' ', '\u3000', '\t')
    val titled = if (cleaned.isEmpty()) DEFAULT_SESSION_TITLE else cleaned
    return if (titled.length > EXPORT_TITLE_MAX_CHARS) {
        titled.take(EXPORT_TITLE_MAX_CHARS)
    } else {
        titled
    }
}

/**
 * 【纯函数】拼出导出文件名：CoolStream_导出_{标题}_{yyyyMMdd-HHmmss}.md
 *
 * ⚠️ SimpleDateFormat 必须显式传 Locale.US：某些区域设置会用本地数字
 *   （如阿拉伯-印度数字）或本地日历，生成的文件名会变得不可预期。
 */
internal fun buildExportFileName(sessionTitle: String, exportedAt: Long): String {
    val safeTitle = sanitizeExportTitle(sessionTitle)
    val stamp = SimpleDateFormat(EXPORT_FILE_TIME_PATTERN, Locale.US).format(Date(exportedAt))
    return "CoolStream_导出_${safeTitle}_$stamp.md"
}

/**
 * 【纯函数】把一条消息正文转成 Markdown 引用块，逐行加 "> "。
 * 空行输出裸 ">"，否则引用块会被空行截断成两段。
 */
private fun quoteBlock(content: String): String {
    val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    return normalized.split("\n").joinToString("\n") { line ->
        if (line.isEmpty()) ">" else "> $line"
    }
}

/** 工具调用占位消息不进导出（它的正文对读者没有意义）。留成常量便于日后改。 */
private const val SKIP_TOOL_CALLS = true

/**
 * 【纯函数】把整个会话渲染成 Markdown 正文。
 *
 * 结构：
 *   # {标题}
 *   - 导出时间：...
 *   - 消息条数：N
 *   ---
 *   ## 1. 用户消息        （引用块）
 *   ## 2. AI 回复         （原样保留，含代码块）
 *   ## 3. 错误提示        （引用块 + 显式提示「这是错误信息，不是模型的回复」）
 *
 * ★ AI 回复必须【原样输出】而不是包引用块：模型回复里大量使用 Markdown
 *   （代码块、表格、列表），包进 "> " 会把这些结构全毁掉。
 *   用户消息反而适合引用块——它是「用户说的话」，视觉上与模型回复区分开更清楚。
 */
internal fun formatSessionAsMarkdown(
    messages: List<ChatMessage>,
    sessionTitle: String,
    exportedAt: Long
): String {
    val sb = StringBuilder()
    val timeText = SimpleDateFormat(EXPORT_HEAD_TIME_PATTERN, Locale.US).format(Date(exportedAt))

    // 正文标题用「原始标题」而不是 sanitizeExportTitle 的结果：
    // 清洗是为了文件系统的安全（把 / 换成 _），不该污染文档正文显示。
    // 只做空白折叠 + 空值兜底，避免标题里的换行把 H1 撕成两行。
    val headingTitle = sessionTitle
        .replace(Regex("""\s+"""), " ")
        .trim()
        .ifEmpty { DEFAULT_SESSION_TITLE }

    sb.append("# ").append(headingTitle).append("\n\n")
    sb.append("- 导出时间：").append(timeText).append("\n")
    sb.append("- 消息条数：").append(messages.size).append("\n\n")
    sb.append("---\n\n")

    if (messages.isEmpty()) {
        sb.append("_（本会话暂无消息）_\n")
        return sb.toString()
    }

    var index = 0
    for (msg in messages) {
        if (SKIP_TOOL_CALLS && msg.type == MessageType.TOOL_CALL) continue
        index++

        when {
            // 错误消息：单独标出，并明确告诉读者它不是模型回复
            msg.isError -> {
                sb.append("## ").append(index).append(". 错误提示\n\n")
                sb.append("> **⚠ 这是错误信息，不是模型的回复。**\n>\n")
                sb.append(quoteBlock(msg.content)).append("\n\n")
            }

            // 用户消息：引用块
            msg.isUser -> {
                sb.append("## ").append(index).append(". 用户消息\n\n")
                sb.append(quoteBlock(msg.content)).append("\n\n")
            }

            // AI 回复：原样保留 Markdown
            else -> {
                sb.append("## ").append(index).append(". AI 回复\n\n")
                sb.append(msg.content).append("\n\n")
            }
        }
    }
    return sb.toString()
}

/**
 * 【内部】同名的导出文件已存在时，自动追加 -1 / -2 / ... 直到不冲突。
 * 防的是「同一分钟里连续导出同一会话」把上一份悄悄覆盖掉。
 */
private fun uniqueExportFile(dir: File, fileName: String): File {
    val dot = fileName.lastIndexOf('.')
    val base = if (dot > 0) fileName.substring(0, dot) else fileName
    val ext = if (dot > 0) fileName.substring(dot) else ""

    var candidate = File(dir, fileName)
    var n = 1
    while (candidate.exists() && n <= 999) {
        candidate = File(dir, "$base-$n$ext")
        n++
    }
    return candidate
}

/**
 * 【薄 IO 壳】导出会话为 Markdown 文件。
 *
 * @param messages     该会话的全部消息（按时间正序，来自 dao.getMessages）
 * @param sessionTitle 会话标题
 * @param exportedAt   导出时间戳（默认当前时间；由调用方注入便于单测）
 * @return ToolExecResult —— ok=true 时时 output 为「已成功导出至 {绝对路径}」
 *
 * 自带 Dispatchers.IO：写盘、创建目录、exists()/length() 全部不碰主线程。
 */
internal suspend fun exportSessionToMarkdown(
    messages: List<ChatMessage>,
    sessionTitle: String,
    exportedAt: Long = System.currentTimeMillis()
): ToolExecResult = withContext(Dispatchers.IO) {

    // ---------- 1. 工作区检查 ----------
    val ws = currentWorkspace
    if (ws.isBlank()) {
        return@withContext ToolExecResult(false, "尚未设置工作区，无法导出会话。")
    }
    val workspaceDir = File(ws)
    if (!workspaceDir.isDirectory) {
        return@withContext ToolExecResult(false, "工作区目录不存在：${workspaceDir.absolutePath}")
    }

    // ---------- 2. 渲染正文（纯函数，异常也不该发生，但兜住） ----------
    val markdown = try {
        formatSessionAsMarkdown(messages, sessionTitle, exportedAt)
    } catch (e: Exception) {
        return@withContext ToolExecResult(
            false,
            "会话内容格式化失败：" + (e.message ?: e.javaClass.simpleName)
        )
    }

    // ---------- 3. 定名 + 防覆盖 ----------
    val fileName = buildExportFileName(sessionTitle, exportedAt)
    val target = try {
        uniqueExportFile(workspaceDir, fileName)
    } catch (e: Exception) {
        return@withContext ToolExecResult(false, "生成导出文件名失败：" + (e.message ?: ""))
    }

    // ---------- 4. 越界双保险 ----------
    // 文件名已做过非法字符清洗，正常不可能越界；但「文件名清洗」和「路径安全」
    // 是两套独立机制，多校验一次的成本极低，少一次的风险很高。
    val violation = workspaceViolation(target.absolutePath)
    if (violation != null) {
        return@withContext ToolExecResult(false, violation)
    }

    // ---------- 5. 写盘（UTF-8，显式指定） ----------
    try {
        target.writeText(markdown, Charsets.UTF_8)
    } catch (e: SecurityException) {
        return@withContext ToolExecResult(false, "没有权限在该目录创建文件：${target.absolutePath}")
    } catch (e: IOException) {
        return@withContext ToolExecResult(false, "写入文件失败：${e.message ?: e.javaClass.simpleName}")
    } catch (e: Exception) {
        return@withContext ToolExecResult(
            false,
            "导出失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim()
        )
    }

    // ---------- 6. 写完自查：文件真的存在且非空 ----------
    if (!target.exists() || target.length() <= 0L) {
        return@withContext ToolExecResult(
            false,
            "导出后校验失败：文件为空或未创建（${target.absolutePath}）"
        )
    }

    ToolExecResult(true, "已成功导出至 ${target.absolutePath}")
}
