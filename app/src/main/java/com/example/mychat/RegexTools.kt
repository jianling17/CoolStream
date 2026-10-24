package com.example.mychat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.PatternSyntaxException

// ============================================================
// 战役六 · 功能二：regex_extract（正则提取）
//
// ★ 线程契约：同 sqlite_query —— 同步阻塞普通函数，由 executeTool 在 IO 协程调用。
//
// ★ 安全基调：ReDoS（灾难性回溯）无法被协程超时打断。
//   Java 正则的实现是纯计算，不检查中断标志，withTimeout/cancel 都拦不住。
//   所以这里的主要防线是「长度上限」，StackOverflowError 捕获只是兜底。
//   —— 这是已知局限，必须在交付说明里如实写明，不能假装已完全防住。
// ============================================================

/** 源文本长度上限。超过直接拒绝（防 ReDoS 的第一道也是主力防线） */
internal const val REGEX_MAX_TEXT = 50_000

/** 返回的匹配项数量上限 */
internal const val REGEX_MAX_MATCHES = 50

/** 正则表达式本身长度上限 */
internal const val REGEX_MAX_PATTERN = 1_000

/**
 * 【主入口】regex_extract 工具实现。
 *
 * 行为：提取所有匹配项的 value（整段匹配文本），用英文逗号 "," 拼接返回。
 * 约定：同步阻塞，任何异常都消化成 ToolExecResult(false, ...)。
 */
internal fun regexExtract(text: String, pattern: String): ToolExecResult {
    // ---------- 护栏 1：长度与空值（先快速失败，不进入正则引擎） ----------
    if (pattern.isEmpty()) return ToolExecResult(false, "参数错误：pattern 不能为空。")
    if (pattern.length > REGEX_MAX_PATTERN) {
        return ToolExecResult(false, "正则表达式过长（超过 $REGEX_MAX_PATTERN 字符）。")
    }
    if (text.isEmpty()) return ToolExecResult(false, "源文本为空，没有可提取的内容。")
    if (text.length > REGEX_MAX_TEXT) {
        // 这里刻意不做「自动切块」：切开可能把本应完整匹配的片段撕成两半，
        // 语义会变。宁可不做，也不做错的。
        return ToolExecResult(false, "文本过长，请分块提取。")
    }

    // ---------- 护栏 2：编译正则，捕获语法错误 ----------
    val regex = try {
        Regex(pattern)
    } catch (e: PatternSyntaxException) {
        return ToolExecResult(
            false,
            "正则语法错误，请调整 pattern：" + (e.description ?: e.message ?: "无法解析")
        )
    }

    // ---------- 护栏 3：迭代匹配，达到上限即停 ----------
    // findAll 是惰性序列，配合 break 可以在取满 50 个后立刻停止，不做多余扫描
    val matches = ArrayList<String>(REGEX_MAX_MATCHES)
    var truncated = false
    try {
        for (m in regex.findAll(text)) {
            if (matches.size >= REGEX_MAX_MATCHES) {
                truncated = true
                break
            }
            matches.add(m.value)   // value = 整段匹配内容
        }
    } catch (e: StackOverflowError) {
        // ★ 灾难性回溯常常先爆栈。StackOverflowError 是 Error 不是 Exception，
        //   只 catch Exception 会漏掉它 → App 直接崩溃。这里必须显式捕获。
        return ToolExecResult(false, "正则执行时发生灾难性回溯（表达式过于复杂），请简化 pattern。")
    } catch (e: Exception) {
        return ToolExecResult(false, "正则执行失败：${e.message ?: e.javaClass.simpleName}")
    }

    if (matches.isEmpty()) return ToolExecResult(true, "没有匹配到任何内容。")

    // ---------- 输出：英文逗号拼接 ----------
    val joined = matches.joinToString(",")
    return if (!truncated) {
        ToolExecResult(true, joined)
    } else {
        ToolExecResult(
            true,
            joined + "\n\n（已截断：匹配项超过 $REGEX_MAX_MATCHES 个，仅返回前 $REGEX_MAX_MATCHES 个）"
        )
    }
}

/**
 * 【挂起包装】给非 IO 上下文使用。
 * ⚠️ 注意：withContext 能把它挪到 IO 线程，但**拦不住**一次已经发生的灾难性回溯
 *    （Java 正则不响应中断）。它只解决「线程归属」，不解决 ReDoS。
 */
internal suspend fun regexExtractAsync(text: String, pattern: String): ToolExecResult =
    withContext(Dispatchers.IO) { regexExtract(text, pattern) }
