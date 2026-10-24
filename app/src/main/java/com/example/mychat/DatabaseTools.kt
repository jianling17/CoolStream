package com.example.mychat

import android.database.Cursor
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ============================================================
// 战役六 · 功能一：sqlite_query（SQLite 只读查询）
//
// ★ 线程契约：
//   本函数是「同步阻塞普通函数」，与 executeTool 的约定一致。
//   executeTool 已经跑在 runAgentLoop 的 IO 协程里（MainActivity.kt:2600 + 2945），
//   所以这里可以放心做磁盘 IO，不需要自己再开线程。
//   ⚠️ 绝对不要把 executeTool 改成 suspend —— 那会传染 Rhino PTC 宿主函数
//      （ToolExecutor.kt:1454）等全部调用链。要用挂起形态，走本文件底部的
//      sqliteQueryAsync 包装，别动 executeTool。
//
// ★ 安全基调：宁可误拒，不可误放。
//   三层防线：扩展名白名单 → SQL 结构护栏 → OPEN_READONLY 兜底。
// ============================================================

/** 单次查询最多返回的数据行数（防爆护栏，避免大表把上下文撑爆） */
internal const val SQLITE_MAX_ROWS = 50

/** 单个单元格最多输出的字符数（超长单元格截断，防一行把结果撑爆） */
private const val SQLITE_MAX_CELL_CHARS = 200

/** SQL 语句长度上限（防止把整段脚本塞进来） */
private const val SQLITE_MAX_SQL_LENGTH = 20_000

/**
 * 【纯函数】db_path 的扩展名白名单校验。
 *
 * 只放行 .db / .sqlite。这不是为了「挑刺」，而是把明显不是数据库的输入
 * 在碰磁盘之前就挡掉：BadCase 是模型把 `notes.txt` 当成 db_path 传进来。
 *
 * @return null = 放行；非 null = 拒绝原因（直接作为工具结果回传）
 */
internal fun sqliteGuardDbPathSuffix(dbPath: String): String? {
    val lower = dbPath.lowercase()
    return if (lower.endsWith(".db") || lower.endsWith(".sqlite")) {
        null
    } else {
        "db_path 必须以 .db 或 .sqlite 结尾，当前是：$dbPath"
    }
}

/**
 * 【纯函数】剥离 SQL 中的「字符串字面量」与「注释」，只留下结构骨架。
 *
 * 为什么必须剥离：
 *   1) 不剥离就扫关键字 → `SELECT * FROM t WHERE name = 'update'` 会被
 *      `\bUPDATE\b` 误杀，正常查询直接被拒。
 *   2) 只在原文扫分号 → 字符串里的分号（`WHERE note = 'a;b'`）会被误判成多语句。
 *   3) 反之，如果不剥离就直接执行原文，`SELECT 1; DROP TABLE x` 会被 rawQuery
 *      当成多语句 —— 所以「剥离 + 在骨架上判定」是唯一正确顺序。
 *
 * 处理对象：'...'（可含 '' 转义） / "..."、`...`、[...]（标识符） /
 *          -- 行注释 / /* 块注释 */。
 *
 * 本函数不依赖 Android 框架类，可在 JVM 单元测试里直接覆盖。
 */
internal fun stripSqlLiteralsAndComments(sql: String): String {
    val out = StringBuilder(sql.length)
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        when {
            // ---- 单引号字符串字面量：'...'，内部 '' 表示一个单引号 ----
            c == '\'' -> {
                i++
                while (i < sql.length) {
                    if (sql[i] == '\'') {
                        if (i + 1 < sql.length && sql[i + 1] == '\'') {
                            i += 2          // '' → 字面量内的一个引号，继续
                            continue
                        } else {
                            i++             // 字符串结束
                            break
                        }
                    }
                    i++
                }
            }

            // ---- 双引号 / 反引号标识符："..." / `...`，同样支持双写转义 ----
            c == '"' || c == '`' -> {
                val quote = c
                i++
                while (i < sql.length) {
                    if (sql[i] == quote) {
                        if (i + 1 < sql.length && sql[i + 1] == quote) {
                            i += 2
                            continue
                        } else {
                            i++
                            break
                        }
                    }
                    i++
                }
            }

            // ---- 方括号标识符：[...]（SQLite 里 [name] 等价于 "name"） ----
            c == '[' -> {
                i++
                while (i < sql.length && sql[i] != ']') i++
                if (i < sql.length) i++   // 吃掉右括号
            }

            // ---- 行注释：-- 一直到行尾（不含换行符本身，换行保留） ----
            c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                i += 2
                while (i < sql.length && sql[i] != '\n') i++
            }

            // ---- 块注释：/* ... */ ----
            c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                i += 2
                while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                i = if (i + 1 < sql.length) i + 2 else sql.length
            }

            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

/**
 * 【纯函数】SQL 三道闸的总护栏。
 *
 * @return null = 放行；非 null = 拒绝原因
 */
internal fun sqliteGuardSql(sqlRaw: String): String? {
    val sql = sqlRaw.trim()

    // 闸 0：非空
    if (sql.isEmpty()) return "SQL 不能为空。"

    // 闸 0.5：长度上限
    if (sql.length > SQLITE_MAX_SQL_LENGTH) {
        return "SQL 语句过长（超过 $SQLITE_MAX_SQL_LENGTH 字符），请拆分后再试。"
    }

    // 闸 1：必须 SELECT 开头
    if (!sql.startsWith("SELECT", ignoreCase = true)) {
        return "只允许执行 SELECT 只读查询，已拒绝该语句。"
    }

    // 剥离字面量与注释，得到结构骨架
    val skeleton = stripSqlLiteralsAndComments(sql)

    // 闸 2：单语句（骨架上不允许出现分号）
    if (skeleton.contains(';')) {
        return "一次只允许执行一条 SELECT 语句（检测到额外的分号）。"
    }

    // 闸 3：写操作关键字黑名单（在骨架上扫，避免误杀字符串里的同名单词）
    val writeKeywords = Regex(
        """\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|REPLACE|TRUNCATE|ATTACH|DETACH|PRAGMA|VACUUM|REINDEX|GRANT|BEGIN|COMMIT|ROLLBACK|SAVEPOINT|RELEASE)\b""",
        RegexOption.IGNORE_CASE
    )
    val hit = writeKeywords.find(skeleton)
    if (hit != null) {
        return "检测到被禁止的写操作关键字「${hit.value}」，只允许 SELECT 只读查询。"
    }

    return null   // 放行；真正的最终防线是 OPEN_READONLY
}

/**
 * 【主入口】sqlite_query 工具实现。
 *
 * 约定：同步阻塞，由 executeTool 在 IO 协程中调用。
 * 任何异常都在内部消化成 ToolExecResult(false, ...)，绝不向外抛。
 */
internal fun sqliteQuery(dbPathRaw: String, sqlRaw: String): ToolExecResult {
    // ---------- 第一步：参数基础校验（不碰磁盘，先快速失败） ----------
    val dbPath = dbPathRaw.trim()
    if (dbPath.isEmpty()) return ToolExecResult(false, "参数错误：缺少 db_path。")

    val suffixProblem = sqliteGuardDbPathSuffix(dbPath)
    if (suffixProblem != null) return ToolExecResult(false, suffixProblem)

    val sqlProblem = sqliteGuardSql(sqlRaw)
    if (sqlProblem != null) return ToolExecResult(false, sqlProblem)

    // ---------- 第二步：工作区 + 路径越界校验 ----------
    val ws = currentWorkspace
    if (ws.isBlank()) return ToolExecResult(false, "尚未设置工作区，无法访问数据库文件。")

    // 相对路径以工作区为基准；绝对路径原样使用
    val file = File(dbPath).let { if (it.isAbsolute) it else File(ws, dbPath) }
    val canonical = safeCanonicalPath(file)          // 复用 ToolExecutor.kt:100 的兜底实现

    val violation = workspaceViolation(canonical)    // ToolExecutor.kt:113
    if (violation != null) return ToolExecResult(false, violation)

    val target = File(canonical)
    if (!target.exists()) return ToolExecResult(false, "数据库文件不存在：$canonical")
    if (!target.isFile) return ToolExecResult(false, "db_path 指向的不是文件：$canonical")
    if (!target.canRead()) return ToolExecResult(false, "数据库文件不可读：$canonical")

    // ---------- 第三步：只读打开 + 查询 ----------
    // db 声明为可空：JVM 单元测试下若静态方法返回默认值，finally 里的 close 也不会 NPE
    var db: SQLiteDatabase? = null
    var cursor: Cursor? = null
    return try {
        // OPEN_READONLY 是最终兜底：即使前三道 SQL 闸全被绕过，SQLite 也拒绝写
        db = SQLiteDatabase.openDatabase(canonical, null, SQLiteDatabase.OPEN_READONLY)
        cursor = db.rawQuery(sqlRaw.trim(), null)
        ToolExecResult(true, renderCursorAsMarkdown(cursor))
    } catch (e: SQLiteDatabaseCorruptException) {
        ToolExecResult(false, "数据库文件已损坏，无法读取：${e.message ?: "未知原因"}")
    } catch (e: SQLiteCantOpenDatabaseException) {
        ToolExecResult(
            false,
            "无法打开数据库（可能不是合法的 SQLite 文件，或权限不足）：${e.message ?: "未知原因"}"
        )
    } catch (e: SQLiteException) {
        ToolExecResult(false, "SQL 语法错误，请检查列名/表名：${e.message ?: "未知原因"}")
    } catch (e: IllegalStateException) {
        ToolExecResult(false, "数据库当前状态不可查询：${e.message ?: "未知原因"}")
    } catch (e: SecurityException) {
        ToolExecResult(false, "没有权限访问该数据库文件。")
    } catch (e: Exception) {
        ToolExecResult(false, "查询失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim())
    } finally {
        // 关闭顺序：先 Cursor 后 DB；用 runCatching 包住，close 失败不影响返回值
        runCatching { cursor?.close() }
        runCatching { db?.close() }
    }
}

/**
 * 【渲染】把 Cursor 渲染成 Markdown 表格。
 * 单元格转义 `|` → `\|`、换行 → `<br>`；BLOB 只输出占位符，不读字节（防 OOM）。
 */
private fun renderCursorAsMarkdown(cursor: Cursor): String {
    val columnCount = cursor.columnCount
    if (columnCount == 0) return "查询未返回任何列。"

    val headers = cursor.columnNames
    val sb = StringBuilder()

    // 表头 + 分隔行
    sb.append("| ")
    sb.append(headers.joinToString(" | ") { escapeTableCell(it) })
    sb.append(" |\n| ")
    sb.append(headers.joinToString(" | ") { "---" })
    sb.append(" |\n")

    var rows = 0
    var truncated = false
    while (cursor.moveToNext()) {
        if (rows >= SQLITE_MAX_ROWS) {
            truncated = true
            break
        }
        sb.append("| ")
        for (i in 0 until columnCount) {
            if (i > 0) sb.append(" | ")
            sb.append(renderCell(cursor, i))
        }
        sb.append(" |\n")
        rows++
    }

    val summary = if (truncated) {
        "已返回前 $SQLITE_MAX_ROWS 行（结果被截断）"
    } else {
        "共返回 $rows 行"
    }
    val hint = if (truncated) {
        "\n\n> 提示：结果超过 $SQLITE_MAX_ROWS 行，已截断。请改用 `LIMIT ... OFFSET ...` 分页查询。"
    } else {
        ""
    }
    return "数据库只读查询（$summary）：\n\n$sb$hint"
}

/** 按 SQLite 的字段类型渲染单个单元格 */
private fun renderCell(cursor: Cursor, index: Int): String {
    return when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> "NULL"
        // BLOB 绝不读字节：getBlob 会把整个二进制塞进内存，是 OOM 的经典入口
        Cursor.FIELD_TYPE_BLOB -> "<BLOB>"
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toString()
        else -> escapeTableCell(cursor.getString(index) ?: "")
    }
}

/** Markdown 表格单元格转义 + 长度截断 */
private fun escapeTableCell(raw: String): String {
    var s = raw
    if (s.length > SQLITE_MAX_CELL_CHARS) {
        s = s.take(SQLITE_MAX_CELL_CHARS) + "…"
    }
    return s
        .replace("|", "\\|")          // 竖线必须转义，否则会撕开表格
        .replace("\r\n", "<br>")
        .replace("\n", "<br>")
        .replace("\r", "<br>")
}

/**
 * 【挂起包装】给非 IO 上下文（例如直接在界面里手动调用）使用。
 *
 * LLM 工具路径（executeTool）不需要它 —— executeTool 已在 IO 协程里。
 * 但留着它，是为了避免将来有人为了「能挂起调用」而去改 executeTool 签名。
 */
internal suspend fun sqliteQueryAsync(dbPath: String, sql: String): ToolExecResult =
    withContext(Dispatchers.IO) { sqliteQuery(dbPath, sql) }
