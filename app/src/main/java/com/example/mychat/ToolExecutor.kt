package com.example.mychat

/**
 * 工具执行层：把「模型说要调用什么」真正落到手机上。
 *
 * 从 MainActivity.kt 抽出来的原因：这一层跟 Compose 界面毫无关系，
 * 却占了主文件 1600 多行，改一个文件工具都要在几千行 UI 代码里翻半天。
 *
 * 边界：本文件只负责「执行」，不碰任何 UI 状态、不碰 SSE、不碰 50ms 防抖。
 *   · 路径与工作区守卫（treeUriToPath / safeCanonicalPath / workspaceViolation）
 *   · 文件工具（read / write / replace / list / search）
 *   · Shell 工具（run_shell + Shizuku 提权）
 *   · PTC 沙箱（run_code + Rhino 宿主函数 + 执行前守卫）
 *   · 联网搜索（web_search）
 *   · MCP 轻量客户端（v1.3：initialize / tools/list / tools/call）
 *   · 派发器 executeTool
 *
 * ★ 搬迁是纯移动：函数体一行未改，行为与之前完全一致。
 */
import android.content.ComponentName
import android.content.res.Configuration
import android.content.ServiceConnection
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.List
import rikka.shizuku.Shizuku
import androidx.compose.material.icons.filled.Search
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.InterruptedIOException


/**
 * 把 content:// 的 tree Uri 转成绝对路径。
 *
 * 例：content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWS
 *     → documentId = "primary:Documents/WS"
 *     → /storage/emulated/0/Documents/WS
 *
 * 外置 SD 卡的 volume 形如 "1A2B-3C4D"，对应 /storage/1A2B-3C4D/...
 */
internal fun treeUriToPath(uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) {
            null
        } else {
            val volume = parts[0]
            val relative = parts[1]
            if (volume.equals("primary", ignoreCase = true)) {
                "/storage/emulated/0/$relative"
            } else {
                "/storage/$volume/$relative"
            }
        }
    } catch (e: Exception) {
        Log.e(TOOL_LOG_TAG, "treeUriToPath 失败: ${e.message}", e)
        null
    }
}

/**
 * 取规范路径；万一 canonicalPath 抛异常（权限不足之类），退回 absolutePath 保底。
 *
 * canonicalPath 会真正解析掉 ".." 和符号链接 —— 这是判断"是否在工作区内"的前提。
 */
internal fun safeCanonicalPath(file: File): String {
    return try {
        file.canonicalPath
    } catch (e: Exception) {
        Log.e(TOOL_LOG_TAG, "canonicalPath 失败，退回 absolutePath: ${file.absolutePath}", e)
        file.absolutePath
    }
}

/**
 * 检查某个路径是否在工作区内。
 * @return null = 放行；非 null = 越界说明（直接作为工具结果回传给模型）
 */
internal fun workspaceViolation(path: String): String? {
    val ws = currentWorkspace
    if (ws.isBlank()) return null          // 没设工作区 → 不限制

    // ⚠️⚠️ 这里必须用 canonicalPath，不能用 absolutePath。
    //      File.absolutePath 只做「相对路径 → 绝对路径」的拼接，**不会消掉 ".."**。
    //      于是 /ws/../../../data/data/com.xxx/f 依旧以 "/ws/" 开头，
    //      会被判成「在工作区内」—— 这是路径前缀判断最容易被忽略的绕过方式。
    //      canonicalPath 会真正解析 ".." 和符号链接，得到落盘时的真实路径。
    //      （write_file 上线后这条从"理论风险"变成了"能删掉系统文件"，
    //        所以必须用 canonical 而不是 absolute。）
    val target = safeCanonicalPath(File(path))
    val root = safeCanonicalPath(File(ws)).trimEnd('/', '\\')

    // ⚠️ 也不能直接写 target.startsWith(root)：
    //    "/storage/emulated/0/DocumentsX" 也以 "/storage/emulated/0/Documents" 开头，
    //    这是路径前缀判断的经典误判。必须比「root + 分隔符」。
    //
    // ⚠️⚠️ 分隔符还必须跟平台走，不能写死 "/"。
    //    Android 上 canonicalPath 用 '/'，没问题；但同一份代码在电脑 JVM 上跑单元测试时，
    //    Windows 的 canonicalPath 返回 "C:\...\ws\a.txt"，拼上 "/" 变成 "C:\...\ws/"，
    //    startsWith 永远不成立 —— 工作区内的文件会被整片误判成越界。
    //    这个坑就是单元测试抓出来的，别改回写死的 "/"。
    val sep = File.separatorChar
    val inside = target == root || target.startsWith("$root$sep")

    return if (inside) {
        null
    } else {
        Log.e(TOOL_LOG_TAG, "工作区越界: path=$target, workspace=$root")
        "工作区越界：$path 不在当前工作区（$ws）内，已拒绝执行。请在「设置 → 工作区」里调整工作区。"
    }
}

/** read_file：文本就返回内容；二进制就明确告诉模型读不了 */
private fun readTextFile(path: String): ToolExecResult {
    // ★ 工作区越界检查（在碰文件之前）
    workspaceViolation(path)?.let { return ToolExecResult(false, it) }

    val file = File(path)
    // ⚠️ 这里刻意不打印 allFilesAccess：
    //   查它需要 Context（Environment.isExternalStorageManager() 是 API 30+，
    //   且必须判版本），而本函数是纯函数、被单元测试直接调用，
    //   不能为了打日志把 Context 拖进来。
    //   前面已经有 canRead，排查权限问题时看它更直接。
    Log.d(
        TOOL_LOG_TAG,
        "read_file path=$path, exists=${file.exists()}, isFile=${file.isFile}, " +
            "canRead=${file.canRead()}, length=${file.length()}"
    )

    return when {
        // ① 模型很可能瞎编路径，先判存在
        !file.exists() -> {
            Log.e(TOOL_LOG_TAG, "文件不存在: $path")
            ToolExecResult(false, "文件不存在：$path。可以先用 search_files 按文件名搜一下。")
        }

        !file.isFile -> {
            Log.e(TOOL_LOG_TAG, "不是文件（可能是目录）: $path")
            ToolExecResult(false, "这不是一个文件（可能是目录）：$path。想列目录请用 list_dir。")
        }

        !file.canRead() -> {
            Log.e(TOOL_LOG_TAG, "没有读取权限: $path")
            ToolExecResult(
                false,
                "没有读取权限：$path。请到系统设置里给本应用开启「所有文件访问权限」。"
            )
        }

        file.length() > MAX_FILE_BYTES -> {
            Log.e(TOOL_LOG_TAG, "文件太大: ${file.length()} 字节")
            ToolExecResult(
                false,
                "文件太大（${file.length()} 字节），超过 ${MAX_FILE_BYTES / 1024 / 1024}MB 上限，无法一次性读取。"
            )
        }

        else -> {
            val bytes = file.readBytes()

            // 二进制探测：前 8KB 里出现 NUL 字节就当作二进制。
            // 不拦截读取，只是不让模型拿到一串乱码 —— 明确告诉它"解析不了"。
            val probe = minOf(bytes.size, 8192)
            var isBinary = false
            for (i in 0 until probe) {
                if (bytes[i] == 0.toByte()) {
                    isBinary = true
                    break
                }
            }

            if (isBinary) {
                // ★ 二进制不等于"读不了"：.docx / .xlsx 本身就是 zip 容器，
                //   NUL 探测必然命中，但里面是可以提取出正文的。
                //   所以先问解析器：是 Office 就提取正文（截断由解析器做，
                //   这里不再二次截断）；解析器说不支持（PDF、旧版二进制 .doc/.xls/.et/.wps），
                //   就把它的话术原样转给模型 —— 和弹窗里给用户的拒绝理由保持同一个来源。
                val parsed = parseOfficeBytes(file.name, bytes)
                if (parsed.ok) {
                    // ★ 本侧也兜一次截断，理由同 addAttachment：解析器是并行开发的
                    //   独立文件，它的 4 个截断单测现在还是红的，token 安全不能押在
                    //   别人今天能不能改对上。已截断文本再过一遍是幂等的。
                    val text = truncateOfficeText(parsed.text)
                    Log.d(TOOL_LOG_TAG, "Office 解析成功: kind=" + parsed.kind + ", " + text.length + " 字符")
                    ToolExecResult(true, text)
                } else {
                    Log.d(TOOL_LOG_TAG, "Office 不支持: kind=" + parsed.kind + ", message=" + parsed.message)
                    ToolExecResult(false, parsed.message)
                }
            } else {
                val text = String(bytes, Charsets.UTF_8)
                Log.d(TOOL_LOG_TAG, "读取成功: ${text.length} 字符")
                ToolExecResult(true, text)
            }
        }
    }
}

/**
 * write_file：把文本写进文件（不存在就新建，已存在就整份覆盖）。
 *
 * 安全检查的顺序不能调：
 *   ① 工作区越界 → ② 路径合法性 → ③ 内容大小 → ④ 目标是不是目录
 *   → ⑤ 父目录 → ⑥ 覆盖前备份 → ⑦ 真正写入
 *
 * ★ 为什么每一步都要单独判、单独给话术：
 *   模型拿到"执行失败"这四个字只会重复调用，拿到"父目录不存在且创建失败"
 *   才知道该换路径。错误信息就是 Agent 的观测值，写得越具体，它越不容易钻牛角尖。
 */
internal fun writeTextFile(path: String, content: String): ToolExecResult {
    // ① 工作区越界检查（必须在碰任何文件之前）
    workspaceViolation(path)?.let { return ToolExecResult(false, it) }

    // ② 路径合法性：写入必须落到具体文件上
    if (path.endsWith("/")) {
        return ToolExecResult(
            false,
            "路径不能以 / 结尾：$path。请给出包含文件名的完整路径，例如 /sdcard/Documents/a.txt"
        )
    }

    val bytes = content.toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_WRITE_BYTES) {
        return ToolExecResult(
            false,
            "内容太大（${bytes.size} 字节），超过 ${MAX_WRITE_BYTES / 1024 / 1024}MB 上限，未写入。" +
                "请拆成几个文件写，或者只写真正需要的那部分。"
        )
    }

    val file = File(path)
    val parent = file.parentFile
        ?: return ToolExecResult(false, "路径不合法（取不到父目录）：$path")

    // 同上：不打印 allFilesAccess，理由见 readTextFile 里的注释
    Log.d(
        TOOL_LOG_TAG,
        "write_file path=$path, bytes=${bytes.size}, exists=${file.exists()}, " +
            "isDirectory=${file.isDirectory}, parent=${parent.absolutePath}, " +
            "parentExists=${parent.exists()}"
    )

    // ④ 目标是个目录 → 提前拒绝。
    //    ｜ 不判的话 writeBytes 会抛 FileNotFoundException("Is a directory")，
    //    ｜ 模型看到 "FileNotFoundException" 只会以为是"文件不存在"，然后反复重试。
    if (file.isDirectory) {
        return ToolExecResult(
            false,
            "目标是一个目录，不能当文件写：$path。请换一个带文件名的路径，例如 $path/note.txt"
        )
    }

    // ⑤ 父目录不存在就创建（模型经常写到一个还不存在的新目录）
    var createdDirs = false
    if (!parent.exists()) {
        parent.mkdirs()
        if (!parent.isDirectory) {
            Log.e(TOOL_LOG_TAG, "父目录创建失败: ${parent.absolutePath}")
            return ToolExecResult(
                false,
                "父目录不存在且创建失败：${parent.absolutePath}。" +
                    "请确认路径拼写是否正确，或者换一个已经存在的目录。"
            )
        }
        createdDirs = true
        Log.d(TOOL_LOG_TAG, "已创建父目录: ${parent.absolutePath}")
    }

    // ⑥ 覆盖前备份：只在原文件不太大时做，避免磁盘占用翻倍
    val existedBefore = file.exists() && file.isFile
    val oldSize = if (existedBefore) file.length() else 0L
    val backupNote = if (existedBefore) backupBeforeOverwrite(file) else ""

    // ⑦ 真正写入
    return try {
        file.writeBytes(bytes)

        // 落盘后复查长度：writeBytes 正常不会少写，
        // 但存储满时有些实现会"写成功"却只落了一部分，这里兜一层。
        val newSize = file.length()
        if (newSize != bytes.size.toLong()) {
            Log.e(TOOL_LOG_TAG, "写入长度不符: 期望 ${bytes.size}，实际 $newSize")
            return ToolExecResult(
                false,
                "写入不完整：期望 ${bytes.size} 字节，实际只写进 $newSize 字节。" +
                    "多半是手机存储空间不足，请让用户清一下空间再试。"
            )
        }

        val dirNote = if (createdDirs) "，并创建了目录 ${parent.absolutePath}" else ""
        val msg = if (existedBefore) {
            "已覆盖文件：$path（原 $oldSize 字节 → 现 $newSize 字节，UTF-8）$backupNote$dirNote"
        } else {
            "已创建文件：$path（$newSize 字节，UTF-8）$dirNote"
        }
        Log.d(TOOL_LOG_TAG, "写入成功: $path, $newSize 字节")
        ToolExecResult(true, msg)
    } catch (e: SecurityException) {
        Log.e(TOOL_LOG_TAG, "没有写入权限: $path", e)
        ToolExecResult(
            false,
            "没有写入权限：$path。请到系统设置里确认本应用已获得「所有文件访问权限」。"
        )
    } catch (e: IOException) {
        // 把内核 errno 翻译成人话，否则模型只会看到一串 "EACCES (Permission denied)"
        val friendly = friendlyIoReason(e)
        Log.e(TOOL_LOG_TAG, "$friendly: $path（${e.message}）", e)
        ToolExecResult(false, "$friendly：$path（${e.javaClass.simpleName}: ${e.message}）")
    }
}

// ============================================================
// 3.26 文件改写：备份、定位、局部替换
// ============================================================

/**
 * 返回一个「按指定语言」的 Context。
 *
 * ★ 为什么不引 appcompat 的 setApplicationLocales：
 *   那要为了一行语言切换拖进一个几 MB 的库，还得改 Activity 基类。
 *   这里只需要让 stringResource 读到正确的资源 —— 而它读的是 LocalContext，
 *   所以造一个换了 locale 的 Context 塞下去就够了。
 */
internal fun Context.withAppLocale(lang: String): Context {
    if (lang == "system") return this
    val locale = if (lang == "en") Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config)
}

/** 判断"空白差异"用的正则（缩进、空格、CRLF 全归一到单个空格） */
private val WHITESPACE_RE = Regex("\\s+")

/** 把 IOException 翻译成人话前缀。write_file 和 replace_in_file 共用 */
private fun friendlyIoReason(e: IOException): String {
    val raw = (e.message ?: "").lowercase()
    return when {
        "enospc" in raw || "no space" in raw -> "存储空间不足，写入失败"
        "eacces" in raw || "permission denied" in raw -> "没有写入权限（这个目录可能属于其它应用）"
        "erofs" in raw -> "目标位置是只读的，无法写入"
        else -> "写入失败"
    }
}

/**
 * 覆盖已有文件前留一份 .bak。
 *
 * 只备份 1MB 以内的文件：大文件再复制一份会让磁盘占用翻倍，
 * 而对"改代码"这个场景来说，需要留后路的基本都是小文件。
 *
 * @return 拼在成功消息后面的说明；没备份就是空串
 */
internal fun backupBeforeOverwrite(file: File): String {
    val size = file.length()
    // 空文件不用备份；太大不备份；本身是 .bak 也不再套娃
    if (size <= 0L || size > MAX_BACKUP_BYTES || file.path.endsWith(".bak")) return ""
    return try {
        val bak = File("${file.path}.bak")
        file.copyTo(bak, overwrite = true)
        "，原文件已备份到 ${bak.name}"
    } catch (e: Exception) {
        // 备份失败不阻断写入，但要让模型和用户都知道这次没有后悔药
        Log.e(TOOL_LOG_TAG, "备份失败（继续写入）: ${e.message}", e)
        "，原文件备份失败（${e.javaClass.simpleName}）"
    }
}

/** 统计 needle 在 haystack 里出现几次（不重叠） */
internal fun countOccurrences(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var i = haystack.indexOf(needle)
    while (i >= 0) {
        count++
        i = haystack.indexOf(needle, i + needle.length)
    }
    return count
}

/** 列出 needle 的所有起点，最多 limit 个（限制数量是为了避免病态输入吃内存） */
internal fun findAllOccurrences(haystack: String, needle: String, limit: Int): List<Int> {
    if (needle.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var i = haystack.indexOf(needle)
    while (i >= 0 && out.size < limit) {
        out.add(i)
        i = haystack.indexOf(needle, i + needle.length)
    }
    return out
}

/** 某个字符下标在第几行（从 1 开始） */
internal fun lineNumberAt(text: String, index: Int): Int {
    var line = 1
    val end = minOf(index, text.length)
    for (k in 0 until end) if (text[k] == '\n') line++
    return line
}

/** 取包含该下标的整行内容，让模型看到"改成了什么" */
internal fun lineTextAt(text: String, index: Int, maxLen: Int = 140): String {
    if (text.isEmpty()) return ""
    val safeIndex = index.coerceIn(0, text.length - 1)
    val nl = text.lastIndexOf('\n', (safeIndex - 1).coerceAtLeast(0))
    val start = if (nl < 0) 0 else nl + 1
    val end = text.indexOf('\n', safeIndex).let { if (it < 0) text.length else it }
    val line = text.substring(start.coerceAtMost(end), end).trim()
    return if (line.length > maxLen) line.take(maxLen) + "…" else line
}

/**
 * replace_in_file：把文件里的 search 换成 replace，其余内容原样保留。
 *
 * 这是"能改代码"和"只能整篇覆盖"的分界线。
 * 整篇覆盖（write_file）的风险是：模型只记得住文件的一部分，
 * 重写时会把没提到的内容一起冲掉。局部替换天然规避这个问题。
 *
 * 三条硬规则：
 *   ① search 必须逐字匹配（空白也算），不做模糊匹配 —— 模糊匹配在代码里会改错地方
 *   ② 匹配到多处时默认拒绝，不猜"大概是想改第一处"
 *   ③ 改之前先备份
 */
internal fun replaceInFile(
    path: String,
    search: String,
    replace: String,
    replaceAll: Boolean
): ToolExecResult {
    // ① 工作区越界检查（在碰文件之前）
    workspaceViolation(path)?.let { return ToolExecResult(false, it) }

    if (search.isEmpty()) {
        return ToolExecResult(false, "参数错误：search 是空的。要把哪段原文换掉，就把它原样填进来。")
    }
    if (search == replace) {
        return ToolExecResult(false, "search 和 replace 一模一样，改了等于没改，本次未做任何修改。")
    }

    val file = File(path)
    Log.d(
        TOOL_LOG_TAG,
        "replace_in_file path=$path, searchLen=${search.length}, replaceLen=${replace.length}, " +
            "replaceAll=$replaceAll, exists=${file.exists()}, canWrite=${file.canWrite()}"
    )

    // ② 文件本身的状态
    when {
        !file.exists() ->
            return ToolExecResult(false, "文件不存在：$path。可以先用 search_files 按文件名找一下。")

        !file.isFile ->
            return ToolExecResult(false, "这不是一个文件：$path。想列目录请用 list_dir。")

        !file.canRead() ->
            return ToolExecResult(false, "没有读取权限：$path。请确认已开启「所有文件访问权限」。")

        !file.canWrite() ->
            return ToolExecResult(false, "没有写入权限：$path。这个文件可能是只读的，或者所在目录不允许写入。")

        file.length() > MAX_FILE_BYTES ->
            return ToolExecResult(
                false,
                "文件太大（${file.length()} 字节），超过 ${MAX_FILE_BYTES / 1024 / 1024}MB 上限，" +
                    "没法一次性读进来改写。"
            )
    }

    // ③ 读 + 二进制探测（二进制文件做字符串替换只会把它改坏）
    val bytes = try {
        file.readBytes()
    } catch (e: Exception) {
        Log.e(TOOL_LOG_TAG, "读取失败: ${e.message}", e)
        return ToolExecResult(false, "读取失败：$path（${e.javaClass.simpleName}: ${e.message}）")
    }

    val probe = minOf(bytes.size, 8192)
    for (i in 0 until probe) {
        if (bytes[i] == 0.toByte()) {
            return ToolExecResult(
                false,
                "这是二进制文件（pptx / docx / zip / 图片等），不能用文本替换的方式修改。" +
                    "请如实告诉用户改不了，不要假装改成功了。"
            )
        }
    }

    val text = String(bytes, Charsets.UTF_8)

    // ④ 定位
    val total = countOccurrences(text, search)
    Log.d(TOOL_LOG_TAG, "replace_in_file 匹配到 $total 处")

    if (total == 0) {
        // 诊断一下：如果忽略空白差异能匹配上，说明模型是"手打"的 search，缩进/换行不对
        val normalizedHit = text.replace(WHITESPACE_RE, " ").contains(
            search.replace(WHITESPACE_RE, " ")
        )
        val hint = if (normalizedHit) {
            "\n提示：忽略空白差异后其实能匹配到 —— 说明你给的 search 里，缩进或换行符" +
                "（CRLF/LF）和文件里的不一致。请直接从 read_file 的输出里整段复制，不要手打。"
        } else {
            "\n请先 read_file 看一下这个文件，确认原文长什么样，再逐字复制要改的那段。"
        }
        return ToolExecResult(false, "在 $path 里找不到这段原文，本次未做任何修改。$hint")
    }

    if (total > 1 && !replaceAll) {
        val lines = findAllOccurrences(text, search, 10).map { lineNumberAt(text, it) }
        return ToolExecResult(
            false,
            "search 在 $path 里出现了 $total 次（第 ${lines.joinToString("、")} 行附近），" +
                "无法确定你想改哪一处，本次未做任何修改。\n" +
                "两个办法：① 把 search 前后各带一两行上下文，让它只匹配一处；" +
                "② 如果确实要全部替换，把 replace_all 传 true。"
        )
    }

    // ⑤ 组装新内容
    val firstHit = text.indexOf(search)
    val newText = if (replaceAll) {
        text.replace(search, replace)
    } else {
        text.replaceRange(firstHit, firstHit + search.length, replace)
    }
    if (newText == text) {
        return ToolExecResult(false, "替换前后内容完全一样，本次未做任何修改。")
    }

    // ⑥ 备份 + 写入
    val oldSize = file.length()
    val backupNote = backupBeforeOverwrite(file)

    return try {
        file.writeBytes(newText.toByteArray(Charsets.UTF_8))
        val newSize = file.length()

        val line = lineNumberAt(text, firstHit)
        val scope = if (replaceAll) "共替换 $total 处" else "替换 1 处"
        val preview = lineTextAt(newText, firstHit)
        val msg = buildString {
            append("已修改：").append(path)
            append("（第 ").append(line).append(" 行，").append(scope).append("）")
            append("，原 ").append(oldSize).append(" 字节 → 现 ").append(newSize).append(" 字节")
            append(backupNote)
            if (preview.isNotEmpty()) {
                append("\n改后该处所在行：").append(preview)
            }
        }
        Log.d(TOOL_LOG_TAG, "替换成功: $path，$scope，$newSize 字节")
        ToolExecResult(true, msg)
    } catch (e: SecurityException) {
        Log.e(TOOL_LOG_TAG, "没有写入权限: $path", e)
        ToolExecResult(false, "没有写入权限：$path。请到系统设置里确认本应用已获得「所有文件访问权限」。")
    } catch (e: IOException) {
        val friendly = friendlyIoReason(e)
        Log.e(TOOL_LOG_TAG, "$friendly: $path（${e.message}）", e)
        ToolExecResult(false, "$friendly：$path（${e.javaClass.simpleName}: ${e.message}）")
    }
}

/** list_dir：列出目录内容（目录在前、文件在后） */
private fun listDirectory(path: String): ToolExecResult {
    // ★ 工作区越界检查
    workspaceViolation(path)?.let { return ToolExecResult(false, it) }

    val dir = File(path)
    Log.d(
        TOOL_LOG_TAG,
        "list_dir path=$path, exists=${dir.exists()}, isDirectory=${dir.isDirectory}, canRead=${dir.canRead()}"
    )

    return when {
        !dir.exists() -> ToolExecResult(false, "目录不存在：$path")
        !dir.isDirectory -> ToolExecResult(false, "这不是目录：$path。想读文件请用 read_file。")
        !dir.canRead() -> ToolExecResult(false, "没有读取权限：$path。请开启「所有文件访问权限」。")

        else -> {
            val entries = dir.listFiles()
            if (entries.isNullOrEmpty()) {
                ToolExecResult(true, "目录为空：$path")
            } else {
                val dirs = entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                val files = entries.filter { !it.isDirectory }.sortedBy { it.name.lowercase() }

                val sb = StringBuilder()
                sb.append("目录 ").append(path).append(" 下共 ").append(entries.size).append(" 项：\n")

                var shown = 0
                for (d in dirs) {
                    if (shown++ >= MAX_LIST_ENTRIES) break
                    sb.append("[目录] ").append(d.name).append('\n')
                }
                for (f in files) {
                    if (shown++ >= MAX_LIST_ENTRIES) break
                    sb.append("[文件] ").append(f.name)
                        .append("  (").append(f.length()).append(" 字节)\n")
                }
                if (entries.size > shown) {
                    sb.append("…（只显示前 ").append(shown).append(" 项）")
                }
                ToolExecResult(true, sb.toString().trimEnd())
            }
        }
    }
}

/** search_files：递归搜索「文件名包含关键词」的文件 */
internal fun searchFiles(keyword: String, rootPath: String): ToolExecResult {
    // ★ 工作区越界检查：连搜索根目录都不许出界
    workspaceViolation(rootPath)?.let { return ToolExecResult(false, it) }

    val root = File(rootPath)
    Log.d(
        TOOL_LOG_TAG,
        "search_files keyword=$keyword, path=$rootPath, " +
            "exists=${root.exists()}, isDirectory=${root.isDirectory}"
    )

    if (!root.exists()) return ToolExecResult(false, "目录不存在：$rootPath")
    if (!root.isDirectory) return ToolExecResult(false, "这不是目录：$rootPath。想读文件请用 read_file。")

    val kw = keyword.lowercase()
    val hits = ArrayList<String>()
    var visited = 0
    var truncated = false

    fun walk(dir: File, depth: Int) {
        if (hits.size >= MAX_SEARCH_HITS) {
            truncated = true
            return
        }
        if (depth > MAX_SEARCH_DEPTH) return
        if (visited >= MAX_SEARCH_VISITS) {
            truncated = true
            return
        }

        val children = dir.listFiles() ?: return
        for (child in children) {
            if (hits.size >= MAX_SEARCH_HITS || visited >= MAX_SEARCH_VISITS) {
                truncated = true
                return
            }

            visited++

            // 跳过隐藏项（.thumbnails / .trash 之类，扫了没用还慢）
            val hidden = child.name.startsWith(".")
            if (!hidden && child.name.lowercase().contains(kw)) {
                hits.add(child.absolutePath)
            }
            if (child.isDirectory && !hidden) {
                walk(child, depth + 1)
            }
        }
    }

    walk(root, 0)

    Log.d(TOOL_LOG_TAG, "search_files 命中 ${hits.size} 个，共检查 $visited 项")

    return if (hits.isEmpty()) {
        ToolExecResult(
            true,
            "未找到文件名包含 \"$keyword\" 的文件（搜索范围：$rootPath，共检查 $visited 项）。"
        )
    } else {
        val sb = StringBuilder()
        sb.append("在 ").append(rootPath).append(" 下找到 ")
            .append(hits.size).append(" 个文件名包含 \"")
            .append(keyword).append("\" 的文件：\n")
        hits.forEach { sb.append(it).append('\n') }
        if (truncated) {
            sb.append("…（结果已截断：最多返回 ").append(MAX_SEARCH_HITS)
                .append(" 条 / 最多检查 ").append(MAX_SEARCH_VISITS).append(" 项）")
        }
        ToolExecResult(true, sb.toString().trimEnd())
    }
}

/** 单次 run_shell 最多返回多少字符（防止 ls -R / 大日志把界面和上下文撑爆） */
private const val MAX_SHELL_OUTPUT = 20_000

// ============================================================
// v1.4 战役二：search_workspace —— 工作区全文检索（本地轻量 RAG，纯字符串匹配）
// ============================================================

/**
 * search_workspace：在整个工作区里搜「文件内容包含关键字」的文本行。
 *
 * 铁律对齐：
 *  · 纯字符串匹配（String.contains，忽略大小写），无向量库、无 Embedding、无网络。
 *  · useLines 逐行流式读取，超大文件不整份进内存。
 *  · >2MB 的文件直接跳过并记日志；二进制（前 8KB 有 NUL）跳过；
 *    隐藏目录（.git / node_modules 等）整个不进。
 *  · 由 executeTool 在 IO 协程调用，天然不阻塞主线程。
 */
internal fun searchWorkspace(query: String): ToolExecResult {
    val q = query.trim()
    if (q.isEmpty()) {
        return ToolExecResult(false, "参数错误：缺少 query（要搜索的关键字）")
    }

    val rootPath = currentWorkspace
    if (rootPath.isBlank()) {
        return ToolExecResult(
            false,
            "还没有设置工作区，无法搜索。请在「设置 → 工作区」里选择一个目录。"
        )
    }
    workspaceViolation(rootPath)?.let { return ToolExecResult(false, it) }
    val root = File(rootPath)
    if (!root.isDirectory) {
        return ToolExecResult(false, "工作区目录无效：$rootPath")
    }

    val ql = q.lowercase()
    val hits = ArrayList<String>()
    var visited = 0
    var skippedLarge = 0
    var truncated = false
    fun walk(dir: File, depth: Int) {
        if (hits.size >= MAX_GREP_HITS) { truncated = true; return }
        if (depth > MAX_SEARCH_DEPTH) return
        if (visited >= MAX_SEARCH_VISITS) { truncated = true; return }

        val children = dir.listFiles() ?: return
        for (child in children) {
            if (hits.size >= MAX_GREP_HITS || visited >= MAX_SEARCH_VISITS) {
                truncated = true
                return
            }
            visited++

            val hidden = child.name.startsWith(".")
            if (child.isDirectory) {
                // 隐藏目录整个跳过（.git / .gradle / node_modules 这类既深又无关）
                if (!hidden) walk(child, depth + 1)
                continue
            }
            if (hidden) continue

            if (child.length() > MAX_GREP_FILE_BYTES) {
                skippedLarge++
                Log.d(TOOL_LOG_TAG, "search_workspace 跳过大文件: ${child.absolutePath} (${child.length()} 字节)")
                continue
            }

            // 二进制探测：前 8KB 有 NUL 字节就当二进制跳过
            val probe = runCatching {
                child.inputStream().use { ins ->
                    val buf = ByteArray(8192)
                    val n = ins.read(buf)
                    var bin = false
                    for (i in 0 until (n.coerceAtLeast(0))) {
                        if (buf[i] == 0.toByte()) { bin = true; break }
                    }
                    bin
                }
            }.getOrDefault(true)
            if (probe) continue

            try {
                child.useLines { lines ->
                    var lineNo = 0
                    for (line in lines) {
                        lineNo++
                        if (line.lowercase().contains(ql)) {
                            hits.add("${child.absolutePath}:$lineNo: ${line.trim().take(200)}")
                            if (hits.size >= MAX_GREP_HITS) {
                                truncated = true
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // 读不动的文件（权限/编码怪癖）跳过，不中断整轮搜索
                Log.d(TOOL_LOG_TAG, "search_workspace 读取失败，跳过: ${child.absolutePath}: ${e.message}")
            }
        }
    }

    walk(root, 0)

    Log.d(
        TOOL_LOG_TAG,
        "search_workspace query=$q, 命中 ${hits.size} 条, 检查 $visited 项, 跳过大文件 $skippedLarge"
    )

    return if (hits.isEmpty()) {
        ToolExecResult(
            true,
            "未找到包含 \"$q\" 的内容（搜索范围：$rootPath，共检查 $visited 项" +
                (if (skippedLarge > 0) "，跳过 $skippedLarge 个超大文件" else "") +
                "）。可以先 search_files 按文件名找，或确认关键字拼写。"
        )
    } else {
        val sb = StringBuilder()
        sb.append("在工作区 \"").append(rootPath).append("\" 中找到 ")
            .append(hits.size).append(" 条包含 \"").append(q).append("\" 的内容：\n")
        hits.forEach { sb.append(it).append('\n') }
        if (truncated) {
            sb.append("…（结果已截断：最多返回 ").append(MAX_GREP_HITS)
                .append(" 条匹配，可换更精确的关键字缩小范围）")
        }
        ToolExecResult(true, sb.toString().trimEnd())
    }
}

// ============================================================
// v1.2.0 semantic_search -- 本地语义检索（TF-IDF 相似度，按"意思"搜）
// 区别于 search_workspace 的纯关键字匹配：用 2-gram CJK 分词 + TF-IDF 权重 +
// 稀疏余弦相似度，能搜到「用词不同但含义相近」的内容。
// ============================================================

/** 懒加载的语义搜索实例 + 已索引路径（workspace 变了就重建索引）。 */
@Volatile
private var semanticSearcher: LocalSemanticSearch? = null

@Volatile
private var semanticIndexPath: String = ""

internal fun semanticSearch(query: String, topK: Int): ToolExecResult {
    val q = query.trim()
    val rootPath = currentWorkspace
    if (rootPath.isBlank()) {
        return ToolExecResult(false, "还没有设置工作区，无法搜索。请在「设置 → 工作区」里选择一个目录。")
    }
    workspaceViolation(rootPath)?.let { return ToolExecResult(false, it) }
    val root = File(rootPath)
    if (!root.isDirectory) {
        return ToolExecResult(false, "工作区目录无效：$rootPath")
    }

    val k = topK.coerceIn(1, 10)
    var searcher = semanticSearcher
    // 工作区变了或首次调用 -> 重建索引
    if (searcher == null || semanticIndexPath != rootPath) {
        searcher = LocalSemanticSearch()
        runBlocking {
            searcher.indexDirectory(
                root = root,
                extensions = DEFAULT_SEARCH_EXTENSIONS,
                parallelism = 4
            )
        }
        semanticSearcher = searcher
        semanticIndexPath = rootPath
        Log.d(TOOL_LOG_TAG, "semantic_search 索引构建完成: " + searcher.indexedChunkCount + " chunks, 词表 " + searcher.vocabularySize)
    }

    val hits = runBlocking { searcher.search(q, topK = k) }
    if (hits.isEmpty()) {
        return ToolExecResult(
            true,
            "未找到与「" + q + "」语义相近的内容（索引 " + searcher.indexedChunkCount + " 块，词表 " + searcher.vocabularySize + " 词）。可以换个说法再试。"
        )
    }

    val sb = StringBuilder()
    sb.append("按语义相似度排序，找到 ").append(hits.size).append(" 条与「").append(q).append("」相关的内容：\n")
    hits.forEachIndexed { i, hit ->
        val preview = hit.chunk.text.take(200).replace('\n', ' ')
        sb.append((i + 1).toString() + ". [" + hit.chunk.source + "] (score=" + "%.3f".format(hit.score) + ")\n   " + preview + "\n")
    }
    return ToolExecResult(true, sb.toString().trimEnd())
}

/** 语义搜索默认索引的文件扩展名。 */
private val DEFAULT_SEARCH_EXTENSIONS = setOf("txt", "md", "kt", "java", "py", "js", "ts", "json", "xml", "csv", "yaml", "yml", "html", "css", "sql")

// ============================================================
// run_shell 的三段式结构
//
// ⚠️ 重要事实：Shizuku 13.1.5 里 **没有** 公开的 Shizuku.newProcess()。
//    用 javap 查过：Shizuku 类里没有这个方法，ShizukuRemoteProcess 的构造器
//    也是包内私有（package-private）。网上那些 newProcess 的示例是 Shizuku 12 的老写法。
//
//    官方公开的提权路径只有一条：bindUserService() + AIDL。
//    原理：Shizuku 把我们的 RemoteShellService 复制到 shell 权限的进程里跑，
//          我们的 App 通过 AIDL 接口把命令丢过去。
//
//   ① RemoteShellService  —— 实际执行命令的代码（跑在 shell 进程里）
//   ② shizukuConnection   —— 拿到 AIDL 代理
//   ③ runShell            —— App 侧调用入口
// ============================================================

/**
 * 跑在 shell 权限进程里的服务。
 *
 * ⚠️ 这里的代码**不在我们 App 的进程里执行** —— Shizuku 会把它加载到
 *    uid 2000（shell）的进程里，所以可以直接 ProcessBuilder 起子进程。
 */
class RemoteShellService : IRemoteShell.Stub() {
    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", "$command 2>&1")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()

            val clipped = if (output.length > MAX_SHELL_OUTPUT) {
                output.take(MAX_SHELL_OUTPUT) + "\n…（输出已截断）"
            } else {
                output
            }
            "$code\n$clipped"
        } catch (e: Exception) {
            "-1\n<执行失败> ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}

/** 连上之后的 AIDL 代理。volatile：写在 binder 线程，读在 IO 线程 */
@Volatile
private var remoteShell: IRemoteShell? = null

private val shizukuConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        remoteShell = IRemoteShell.Stub.asInterface(service)
        Log.d(TAG, "Shizuku UserService 已连接")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        remoteShell = null
        Log.w(TAG, "Shizuku UserService 已断开")
    }
}

/** 绑定 Shizuku UserService（拿到 shell 权限执行器） */
internal fun bindShizukuUserService(context: Context) {
    if (!isShizukuGranted()) {
        Log.w(TAG, "未授权 Shizuku，跳过绑定")
        return
    }
    try {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, RemoteShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(1)

        Shizuku.bindUserService(args, shizukuConnection)
        Log.d(TAG, "已请求绑定 Shizuku UserService")
    } catch (e: Exception) {
        Log.e(TAG, "绑定 Shizuku UserService 失败", e)
    }
}

/**
 * run_shell 的黑名单。
 *
 * ⚠️ 这是「安全网」，不是「安全边界」。
 *    shell 是图灵完备的，理论上永远能被绕过（`r''m`、`busybox rm`、
 *    `echo cm0gLXJmIC8= | base64 -d | sh` ……）。
 *    这层的作用是：拦住模型「顺手」发起的破坏性操作，而不是防御蓄意攻击。
 *
 * 匹配前会把命令**转小写 + 压缩连续空白**，所以 `RM   -RF` 也能拦到。
 * 每条规则用 `\b` 做词边界，避免误伤 `charm` 这类含 rm 的单词。
 */
private val SHELL_BLOCKLIST: List<Pair<String, Regex>> = listOf(
    // 删除。要允许普通 rm 的话，把这条改成 Regex("""\brm\s+(-[a-z]*\s+)*-[a-z]*[rf][a-z]*""")
    "删除文件 (rm)" to Regex("""\brm\s"""),
    // 包管理
    "卸载或清除应用 (pm uninstall/clear)" to Regex("""\bpm\s+(uninstall|clear)\b"""),
    "禁用应用 (pm disable)" to Regex("""\bpm\s+disable(-user)?\b"""),
    // 重启 / 关机
    "重启或关机 (reboot/shutdown)" to Regex("""\b(reboot|shutdown|poweroff|halt|recovery)\b"""),
    "电源控制 (svc power)" to Regex("""\bsvc\s+power\b"""),
    // 格式化 / 分区
    "格式化分区 (mkfs/wipe)" to Regex("""\b(mkfs(\.[a-z0-9]+)?|mke2fs|fastboot)\b"""),
    "修改分区表 (parted/fdisk)" to Regex("""\b(parted|fdisk|sgdisk|gdisk)\b"""),
    // 直接写块设备
    "写块设备 (dd of=/dev/...)" to Regex("""\bdd\b[^|;&]*\bof=\s*/dev/"""),
    "直接写块设备节点 (> /dev/block/)" to Regex(""">\s*/dev/block/"""),
    // 挂载
    "以读写方式重挂分区 (mount remount/rw)" to Regex("""\bmount\b[^|;&]*\b(remount|rw)\b"""),
    // 权限 / 属主
    "递归修改权限 (chmod -R)" to Regex("""\bchmod\b[^|;&]*-[a-z]*r[a-z]*\s"""),
    "递归修改属主 (chown -R)" to Regex("""\bchown\b[^|;&]*-[a-z]*r[a-z]*\s"""),
    // 覆写
    "粉碎或截断文件 (shred/truncate)" to Regex("""\b(shred|truncate)\b"""),
    // 杀进程
    "批量杀进程 (killall)" to Regex("""\bkillall\b"""),
    "杀 1 号进程 (kill 1)" to Regex("""\bkill\s+(-9\s+)?1\b"""),
    // 改系统设置
    "修改系统设置 (settings put)" to Regex("""\bsettings\s+put\b"""),
)

/**
 * 检查命令是否命中黑名单。
 * @return null = 放行；非 null = 命中的规则说明
 */
private fun blockedReason(command: String): String? {
    val normalized = command.lowercase().replace(Regex("""\s+"""), " ").trim()
    for ((label, regex) in SHELL_BLOCKLIST) {
        if (regex.containsMatchIn(normalized)) return label
    }
    return null
}

/**
 * run_shell：通过 Shizuku 以 shell 权限执行命令。
 *
 * ⚠️ 一定跑在 Dispatchers.IO 上 —— executeTool 是从 runAgentLoop 的 IO 协程里调的，
 *    所以这里天然不会阻塞主线程，不需要再自己 withContext。
 *    （AIDL 调用本身是阻塞式的 binder 事务。）
 *
 * 🛡️ 执行前先过黑名单：命中就**根本不执行**，直接把拦截信息回传给模型。
 */
internal fun runShell(command: String): ToolExecResult {
    Log.d(TOOL_LOG_TAG, "run_shell command=$command")

    // ===== 第一道：黑名单硬拦截 =====
    // 放在权限检查之前 —— 即使 Shizuku 没授权，也不该让危险命令走到执行路径
    val blocked = blockedReason(command)
    if (blocked != null) {
        Log.e(TOOL_LOG_TAG, "安全拦截[$blocked]: $command")
        return ToolExecResult(
            false,
            "安全拦截：该命令被系统禁止执行\n（命中规则：$blocked）"
        )
    }

    // ===== 第二道：Shizuku 状态检查 =====
    if (!isShizukuRunning()) {
        return ToolExecResult(
            false,
            "Shizuku 服务没有运行。请先安装并启动 Shizuku App，" +
                "再回到本应用的「设置」里点「Shizuku」完成授权。"
        )
    }
    if (!isShizukuGranted()) {
        return ToolExecResult(
            false,
            "还没有获得 Shizuku 授权。请打开本应用的「设置」弹窗，" +
                "点「Shizuku」那一行完成授权后重试。"
        )
    }

    val shell = remoteShell
        ?: return ToolExecResult(
            false,
            "Shizuku 执行器还没连上。请打开「设置」弹窗，再点一次「Shizuku」那一行触发重新连接。"
        )

    return try {
        // 返回值约定：第一行是退出码，后面是合并后的输出
        val raw = shell.exec(command)
        val nl = raw.indexOf('\n')
        val exitCode = if (nl > 0) raw.substring(0, nl).trim().toIntOrNull() ?: -1 else -1
        val output = if (nl >= 0) raw.substring(nl + 1).trimEnd() else raw

        Log.d(TOOL_LOG_TAG, "run_shell exit=$exitCode, 输出 ${output.length} 字符")

        val body = if (output.isEmpty()) "（没有输出）" else output
        ToolExecResult(true, "退出码 $exitCode：\n$body")
    } catch (e: Exception) {
        Log.e(TOOL_LOG_TAG, "run_shell 失败: ${e.message}", e)
        ToolExecResult(false, "命令执行失败：${e.javaClass.simpleName}: ${e.message}")
    }
}

// ============================================================
// v1.4 系统级工具：get_clipboard / send_notification / take_screenshot
// 全部复用 runShell（Shizuku 通道），零新权限逻辑，天然带黑名单与状态检查。
// ============================================================

/** shell 单引号包裹：把参数里的单引号转成 '\'' 防注入拆参数 */
private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** get_clipboard：读系统剪贴板文本（cmd clipboard get，Android 10+ shell 可用） */
internal fun getClipboard(): ToolExecResult {
    val r = runShell("cmd clipboard get")
    if (!r.ok) return r
    // cmd clipboard get 的输出里可能混有 warning 行，取最后一个非空行作为剪贴板内容
    val text = r.output.lines().lastOrNull { it.isNotBlank() } ?: ""
    return if (text.isBlank() || text == "null") {
        ToolExecResult(true, "剪贴板当前是空的（或只有非文本内容）")
    } else {
        ToolExecResult(true, "剪贴板内容：\n$text")
    }
}

/** send_notification：发系统通知（cmd notification post，title/content 已做引号包裹） */
internal fun sendNotification(title: String, content: String): ToolExecResult {
    val t = shellQuote(title)
    val c = shellQuote(content)
    val r = runShell("cmd notification post -S bigtext -t $t CoolStream $c")
    return if (r.ok) {
        ToolExecResult(true, "已发送系统通知：\n标题：$title\n内容：$content")
    } else r
}


/** http_request 返回的最大字符数 */
private const val HTTP_TOOL_MAX_CHARS = 5000

/** calculate：用现有 Rhino 沙箱算数学表达式，避免模型脑算出错 */
internal fun calculate(expression: String): ToolExecResult {
    val expr = expression.trim()
    if (expr.isEmpty()) {
        return ToolExecResult(false, "参数错误：缺少 expression（数学表达式）")
    }
    return try {
        // 复用 run_code 的沙箱 ContextFactory：禁 Java 类、解释执行、超时保护。
        val cx = PtcContextFactory.enterContext()
        try {
            val deadlineBackup = runCodeDeadline.get()
            runCodeDeadline.set(SystemClock.elapsedRealtime() + RUN_CODE_TIMEOUT_MS)
            val result = cx.evaluateString(
                cx.initStandardObjects(),
                "(" + expr + ")",
                "<calculate>",
                1,
                null
            )
            runCodeDeadline.set(deadlineBackup)
            val text = RhinoContext.toString(result)
            ToolExecResult(true, "$expr = $text")
        } finally {
            RhinoContext.exit()
        }
    } catch (e: Exception) {
        Log.d(TOOL_LOG_TAG, "calculate 失败: ${e.message}")
        ToolExecResult(false, "表达式无法计算：${e.message?.take(200)}。请检查括号与运算符是否配对。")
    }
}

/** http_request：OkHttp 发请求，返回前 5000 字。让 AI 能直接对接天气等公共 API */
internal fun httpRequest(url: String, method: String, body: String): ToolExecResult {
    val m = method.trim().uppercase().ifBlank { "GET" }
    if (m !in setOf("GET", "POST", "PUT", "DELETE", "HEAD")) {
        return ToolExecResult(false, "不支持的 method：$method（仅 GET/POST/PUT/DELETE/HEAD）")
    }
    return try {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val builder = okhttp3.Request.Builder().url(url)
        when (m) {
            "POST" -> builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            "PUT" -> builder.put(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            "DELETE" -> builder.delete()
            "HEAD" -> builder.head()
        }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val clipped = if (text.length > HTTP_TOOL_MAX_CHARS) {
                text.take(HTTP_TOOL_MAX_CHARS) + "\n…（响应过长，已截断为前 $HTTP_TOOL_MAX_CHARS 字符）"
            } else text
            val ctype = resp.header("Content-Type") ?: "unknown"
            ToolExecResult(true, "HTTP ${resp.code} (${ctype})\n$clipped")
        }
    } catch (e: Exception) {
        ToolExecResult(false, "请求失败：${e.javaClass.simpleName}: ${e.message?.take(200)}")
    }
}

/** open_app：Shizuku 执行 monkey -p <package> 1 拉起应用（复用 runShell 通道） */
internal fun openApp(packageName: String): ToolExecResult {
    val pkg = packageName.trim()
    if (pkg.isEmpty() || !pkg.matches(Regex("[A-Za-z0-9_.]+"))) {
        return ToolExecResult(false, "参数错误：package_name 应为应用包名，例如 com.tencent.mm")
    }
    val r = runShell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
    return if (r.ok) {
        ToolExecResult(true, "已尝试拉起应用：$pkg。如果手机上没有安装这个应用，不会有任何反应。")
    } else r
}
/** 工作区未设置时的截屏兜底目录（与 MainActivity DEFAULT_WORKSPACE 一致） */
internal const val FALLBACK_SCREENSHOT_DIR = "/storage/emulated/0/Documents/CoolStream_Workspace"

/** take_screenshot：截屏保存到工作区，返回图片路径 */
internal fun takeScreenshot(workspace: String): ToolExecResult {
    val dir = (if (workspace.isNotBlank()) workspace else FALLBACK_SCREENSHOT_DIR).trimEnd('/')
    val path = "$dir/.cache_screenshot.png"
    val r = runShell("screencap -p $path")
    if (!r.ok) return r
    // screencap 失败也可能退出码 0（个别 ROM），补一个文件存在性校验
    val check = runShell("ls -l $path")
    if (!check.ok || check.output.contains("No such file")) {
        return ToolExecResult(false, "截屏命令执行了，但没有找到生成的文件：$path")
    }
    return ToolExecResult(
        true,
        "已截屏并保存到：$path\n（PNG 图片。如果你具备读图能力，可以引导用户查看；" +
        "文本模型可提示用户在文件管理器中打开该路径。）"
    )
}


// ============================================================
// 3.26 无障碍屏幕操作：screen_see / screen_tap
//
// 为什么单独一节：这是本 App 里唯二「AI 直接动手点你的手机」的能力，风险比读文件高一档。
// 三重护栏全部沉在底层服务里，这一层只负责「翻译成模型能懂的话」：
//   · 快照是纯数据 POJO（UiNode），节点用完即还，不长期持有系统对象；
//   · 点击走 ClickGuard（同目标限流 + 任务预算），反复点同一个按钮会被拒绝；
//   · 遍历是跨进程 binder 调用，必须离开主线程 —— 见下面的 runBlocking(Dispatchers.IO)。
// ============================================================

/** screen_see 返回给模型的字数上限。够看清一层界面，又不至于把上下文撑爆。 */
internal const val SCREEN_SEE_MAX_CHARS = 2000

/** 读一次快照的最长等待。服务真卡住时宁可报失败，也不把 IO 线程钉死。 */
internal const val SCREEN_SEE_TIMEOUT_MS = 2_000L

/**
 * 赛博视界：工具执行期间点亮屏幕边缘的 AI 焦点光效，结束（或至少亮够 900ms）后熄灭。
 *
 * 为什么包一层而不是在两头各写一行：screenSee / screenTap 都有多条提前返回的路径，
 * 手工配对 show/hide 迟早漏掉一条，漏掉就是悬浮层永远留在屏幕上。
 * 用 inline + try/finally 把「一定熄灭」变成编译期保证，调用点也不用缩进。
 *
 * 光效只是装饰：show/hide 内部全部吞异常，绝不会让工具本身失败。
 */
private inline fun <T> withAiFocus(service: CoolStreamAccessibilityService, block: () -> T): T {
    service.showAiFocusOverlay()
    return try {
        block()
    } finally {
        service.hideAiFocusOverlay()
    }
}

/**
 * screen_see：把当前屏幕的无障碍节点树压成文字返回给模型。
 *
 * 为什么是 runBlocking(Dispatchers.IO)：executeTool 是同步函数，而底层 refreshSnapshot()
 * 是挂起函数。这里显式切到 IO 再阻塞等待 —— 调用方本来就是 IO 协程
 * （runAgentLoop / run_code），主线程一次都不会被碰到。
 */
internal fun screenSee(): ToolExecResult {
    val service = CoolStreamAccessibilityService.peek()
        ?: return ToolExecResult(
            false,
            "无障碍服务当前没有连接，读不到屏幕内容。可能的原因有两个：" +
                "①用户在系统设置里关掉了「CoolStream 屏幕助手」；" +
                "②服务刚被系统重启，还没重新连上。" +
                "请让用户到 App 的「设置 → 通用 → 无障碍自动化」确认开关是打开的，然后再试。"
        )

    return withAiFocus(service) {
        val snapshot = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(SCREEN_SEE_TIMEOUT_MS) { service.refreshSnapshot() }
        } ?: return ToolExecResult(
            false,
            "服务已连接，但这次没能读到屏幕结构（超时，或当前窗口没有可读节点）。" +
                "可能是界面正在动画中，或前台应用屏蔽了无障碍访问。可以让用户先停在这一屏再试一次。"
        )

        val tree = snapshot.describe(SCREEN_SEE_MAX_CHARS)
        if (tree.isBlank()) {
            return ToolExecResult(
                false,
                "屏幕结构读到了，但里面没有任何可描述的节点。当前界面可能是空白页或纯图片。"
            )
        }

        ToolExecResult(
            true,
            "当前屏幕的界面结构如下（'#' 后是节点编号，[click] 可点击，[edit] 可输入，" +
                "括号里是 left,top,right,bottom 坐标）：" + "\n\n" +
                tree +
                "\n要操作哪个控件，就用 screen_tap 传它的文字。"
        )
    }
}

/**
 * screen_tap：按文字找到控件并点击。
 *
 * text 刻意不走 cleanPath —— 按钮文字本身可能带标点（「下一步，继续」「确定！」），
 * cleanPath 会把首尾标点削掉，削完就匹配不上了。
 */
internal fun screenTap(text: String): ToolExecResult {
    val target = text.trim()
    if (target.isEmpty()) {
        return ToolExecResult(false, "参数错误：缺少 text（要点击的控件上显示的文字）。")
    }
    val service = CoolStreamAccessibilityService.peek()
        ?: return ToolExecResult(
            false,
            "无障碍服务当前没有连接，点不了屏幕。" +
                "请让用户到 App 的「设置 → 通用 → 无障碍自动化」确认开关是打开的。"
        )

    val clicked = withAiFocus(service) {
        runBlocking(Dispatchers.IO) { service.clickNodeByText(target) }
    }

    return if (clicked) {
        ToolExecResult(
            true,
            "已点击「" + target + "」。如果还要继续操作，先调用 screen_see 确认界面真的变了，" +
                "不要凭想象接着点。"
        )
    } else {
        ToolExecResult(
            false,
            "没能在当前屏幕上点到「" + target + "」。可能的原因：" +
                "①屏幕上没有这段文字（先用 screen_see 看看实际有哪些文字）；" +
                "②这个控件不可点击；" +
                "③刚刚已经连续点过它两次，被防死循环护栏拒绝了（换个目标，或停下来问用户）。"
        )
    }
}
// ============================================================
// 3.27 PTC 沙箱：run_code 的 JavaScript 运行时
//
// 这一段回答一个问题：**凭什么敢让大模型写的代码在你手机上跑？**
//
// 三层约束，缺一不可：
//   1. ClassShutter 全禁
//      不给 JS 任何碰 Java 类的通道。没有 Packages.java、没有 getClass()，
//      也就不可能摸到 Runtime.exec —— 这是最重要的一层，没它前面全白搭。
//   2. 能力只有一个入口
//      JS 能看到的只有一个 tools 对象，六个方法，全部走 executeTool。
//      工作区拦截、shell 黑名单、文件大小上限，因此在 PTC 模式下**同样生效**，
//      不是绕开它们另开一条路。
//   3. 指令数观察器 + 墙钟截止
//      while(true){} 这类死循环必须能被掐断，否则主界面就再也回不来了。
//      Rhino 没有内置超时，靠 observeInstructionCount 定期回调来检查时间。
//
// ⚠️ optimizationLevel 必须是 -1（解释执行）。
//    Android 跑在 Dalvik/ART 上，Rhino 默认的字节码生成那条路走不通。
// ⚠️ Context 是「每线程一个」，用完必须 RhinoContext.exit()，
//    否则那个线程下次进入会拿到一个没清理的上下文。
// ============================================================

/** 一段 run_code 最多跑多久（毫秒）。超了就抛异常中止，不是等它自己停 */
private const val RUN_CODE_TIMEOUT_MS = 15_000L

/** 回传给模型的输出最多多少字符 —— 脚本可以把 print 刷到几十万字，得截断 */
private const val RUN_CODE_MAX_OUTPUT = 20_000

/** 模型给的代码最多多长。超了基本是它把整个文件内容当代码塞进来了 */
private const val RUN_CODE_MAX_CODE = 200_000

/**
 * 当前线程这次 run_code 的截止时刻（SystemClock.elapsedRealtime）。
 *
 * ★ 用 ThreadLocal 而不是普通变量：并发跑两段代码时互不干扰。
 *   每个线程有自己的 deadline，观察器只读自己那个。
 */
private val runCodeDeadline = ThreadLocal<Long>()

/**
 * 沙箱专用的 ContextFactory。
 *
 * 所有安全设置都在 makeContext 里一次配好 —— 这样即使别处不小心用了
 * RhinoContext.enter()，也拿不到能碰 Java 类的上下文。
 */
private object PtcContextFactory : ContextFactory() {

    override fun makeContext(): RhinoContext {
        val cx = super.makeContext()

        // 解释执行。Android 上必须这样，不能让它生成字节码
        cx.optimizationLevel = -1
        // 允许 const / 箭头函数 / 模板字符串，模型写现代 JS 更顺手
        cx.languageVersion = RhinoContext.VERSION_ES6

        // ★ 第 1 层：任何 Java 类对脚本都不可见
        cx.setClassShutter(ClassShutter { _ -> false })

        // ★ 第 3 层：每执行 2 万条指令回调一次，用来检查是否超时
        cx.setInstructionObserverThreshold(20_000)

        // 递归深度上限，防止爆栈把进程带走
        cx.setMaximumInterpreterStackDepth(400)

        return cx
    }

    override fun observeInstructionCount(cx: RhinoContext, instructionCount: Int) {
        val limit = runCodeDeadline.get() ?: return
        if (SystemClock.elapsedRealtime() > limit) {
            // 抛异常是唯一能中止 evaluateString 的方式
            throw EvaluatorException("代码执行超过 ${RUN_CODE_TIMEOUT_MS / 1000} 秒，已强制中止")
        }
    }
}

/** 把一个 Kotlin 函数包装成 JS 里可以调用的函数 */
private fun jsHostFunction(
    fn: (RhinoContext, Scriptable, Array<Any?>) -> Any?
): BaseFunction = object : BaseFunction() {
    override fun call(
        cx: RhinoContext,
        scope: Scriptable,
        thisObj: Scriptable?,
        args: Array<Any?>
    ): Any? = fn(cx, scope, args)
}

/** 取第 i 个 JS 参数当字符串。undefined / 缺参 / null 一律当空串 */
private fun jsArgStr(args: Array<Any?>, i: Int): String {
    if (i >= args.size) return ""
    val v = args[i] ?: return ""
    if (v is Undefined) return ""
    return RhinoContext.toString(v)
}

/** 取第 i 个 JS 参数当布尔。只认真正的 truthy 值，字符串 "false" 不算 true */
private fun jsArgBool(args: Array<Any?>, i: Int): Boolean {
    if (i >= args.size) return false
    val v = args[i] ?: return false
    if (v is Undefined) return false
    if (v is Boolean) return v
    if (v is Number) return v.toDouble() != 0.0
    return RhinoContext.toString(v) == "true"
}

/**
 * 把 JS 的值变成人能读的一行字，用在实际返回值的展示上。
 *
 * 对象只展开一层：深了没意义（模型自己可以 print(JSON.stringify(x))），
 * 而且循环引用的对象会无限递归 —— depth 就是拦这个的。
 */
private fun jsValuePreview(v: Any?, depth: Int = 0): String {
    if (v == null) return "null"
    if (v is Undefined) return "undefined"
    if (v is Scriptable) {
        if (depth >= 2) return "{…}"
        val ids = v.ids
        if (ids.isEmpty()) return "{}"
        val shown = ids.take(20)
        val body = shown.joinToString(", ") { id ->
            "$id: ${jsValuePreview(ScriptableObject.getProperty(v, id.toString()), depth + 1)}"
        }
        return if (ids.size > shown.size) "{$body, …}" else "{$body}"
    }
    return RhinoContext.toString(v)
}

/**
 * 把字符串字面量、行注释、块注释全部替换成等长空格（保留换行）。
 *
 * ★ 为什么要这么绕：执行前检查要判断「代码里有没有 `??`」，
 *   但 `print("a ?? b")` 里的 ?? 只是普通文本，不该误报。
 *   换成等长空格而不是直接删掉，是为了让后面算出的行号还能对上原文。
 *
 * 反引号字符串【故意不抹掉】—— 模板字符串插值失效这一条正是要检测它。
 */
internal fun maskJsLiterals(src: String): String {
    val out = StringBuilder(src)
    var i = 0
    val n = src.length
    fun blank(p: Int) {
        if (p < n && src[p] != '\n') out.setCharAt(p, ' ')
    }
    while (i < n) {
        val c = src[i]
        when {
            // 行注释 //
            c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                while (i < n && src[i] != '\n') { blank(i); i++ }
            }
            // 块注释 /* */
            c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                blank(i); blank(i + 1); i += 2
                while (i < n) {
                    if (src[i] == '*' && i + 1 < n && src[i + 1] == '/') {
                        blank(i); blank(i + 1); i += 2
                        break
                    }
                    blank(i); i++
                }
            }
            // 单引号 / 双引号字符串
            c == '\'' || c == '"' -> {
                val quote = c
                blank(i); i++
                while (i < n) {
                    if (src[i] == '\\') { blank(i); blank(i + 1); i += 2; continue }
                    if (src[i] == quote) { blank(i); i++; break }
                    blank(i); i++
                }
            }
            else -> i++
        }
    }
    return out.toString()
}

/** 把字符下标换算成 1 起的行号 */
internal fun lineAt(src: String, index: Int): Int {
    var line = 1
    for (k in 0 until minOf(index, src.length)) if (src[k] == '\n') line++
    return line
}

/**
 * 执行前检查：把 Rhino 1.7.13 不支持、或者「能跑但结果是错的」写法拦下来。
 *
 * ★ 这个函数存在的理由是有一次真实翻车：
 *   模型写了 `for (const f of files)`，Rhino 只回一句 `syntax error (line 5)`，
 *   模型完全不知道自己错在哪，只好整个重写一遍 —— 白烧一轮 token。
 *
 * ★ 更要命的是模板字符串：`` `a${x}` `` 不报错，
 *   而是原样返回字符串 "a${x}"。模型会拿这个错值继续算下去，
 *   最后给用户一个看起来很正常的错误答案。这种必须拦。
 *
 * @return null = 放行；非 null = 给模型看的错误说明（含正确写法）
 */
internal fun ptcPreflight(code: String): String? {
    val masked = maskJsLiterals(code)

    // ① 模板字符串插值 —— 不报错但结果是错的，必须拦
    Regex("`[^`]*\\$\\{").find(masked)?.let { m ->
        return "第 ${lineAt(code, m.range.first)} 行：本沙箱的模板字符串【不会做插值】，" +
            "`a\${x}` 会被当成普通文本原样保留，算出来的结果是错的。\n" +
            "请改用字符串拼接：'a' + x。"
    }

    // ② for (const x of arr) —— const 不能用在 for-of 的循环变量上
    Regex("""for\s*\(\s*const\b[^)]*\bof\b""").find(masked)?.let { m ->
        return "第 ${lineAt(code, m.range.first)} 行：for-of 的循环变量不能用 const" +
            "（本沙箱的 JS 引擎不支持）。\n" +
            "改成 for (let x of arr)，或者用 for (var i = 0; i < arr.length; i++)。"
    }

    // ③ 可选链 ?.
    Regex("""\?\.""").find(masked)?.let { m ->
        return "第 ${lineAt(code, m.range.first)} 行：不支持可选链 ?.（本沙箱引擎不支持）。\n" +
            "改成 a && a.b，或者用 if 判断。"
    }

    // ④ 空值合并 ?? 和 ??=
    Regex("""\?\?""").find(masked)?.let { m ->
        return "第 ${lineAt(code, m.range.first)} 行：不支持 ?? / ??=（本沙箱引擎不支持）。\n" +
            "改成 || （注意 0 和空串也会被当成假值），或者用三元表达式。"
    }

    // ⑤ 展开/剩余运算符 ...
    Regex("""\.\.\.""").find(masked)?.let { m ->
        return "第 ${lineAt(code, m.range.first)} 行：不支持展开运算符 ...（本沙箱引擎不支持）。\n" +
            "数组合并用 a.concat(b)，对象就用逐个赋值。"
    }

    return null
}

/**
 * run_code 的真实实现。
 *
 * 返回给模型的内容分三段：内部调用了什么、print 打了什么、脚本返回了什么。
 * 这三段是分开的，因为模型排错时最想知道的是「第几步错了」，
 * 而不是一坨混在一起的文本。
 */
internal fun runCode(code: String): ToolExecResult {
    if (code.isBlank()) {
        return ToolExecResult(false, "参数错误：code 是空的，一句话都没写。")
    }
    if (code.length > RUN_CODE_MAX_CODE) {
        return ToolExecResult(
            false,
            "代码太长：${code.length} 字符，上限 $RUN_CODE_MAX_CODE。请把任务拆成更小的几段。"
        )
    }

    // ★ 执行前检查：把这次沙箱引擎不支持的写法挡在前面，并直接给出改法。
    //   放行的代码再进 JS 引擎 —— 否则引擎只会回一句 "syntax error (line 5)"，
    //   模型得靠猜。
    ptcPreflight(code)?.let { return ToolExecResult(false, it) }

    val startedAt = SystemClock.elapsedRealtime()
    val printed = mutableListOf<String>()      // print() 的输出
    val innerCalls = mutableListOf<String>()   // 内部工具调用记录
    var returned: Any? = null
    var failure: String? = null
    var failureLine = -1

    runCodeDeadline.set(startedAt + RUN_CODE_TIMEOUT_MS)
    val cx = PtcContextFactory.enterContext()
    try {
        val scope = cx.initStandardObjects()

        /**
         * 所有工具调用的唯一出口。
         *
         * ★ 这里的 enabledTools 判断不能省：PTC 模式下 tools 数组里只有 run_code，
         *   模型看不到「哪些工具被关了」，只能靠这里拦下来再告诉它。
         */
        fun invoke(toolName: String, args: JSONObject): Any {
            innerCalls += toolName
            val result = if (toolName !in AppSettings.enabledTools) {
                ToolExecResult(false, "工具 $toolName 已被用户在设置里关闭，调用被拒绝。")
            } else {
                executeTool(toolName, args.toString())
            }
            Log.d(TOOL_LOG_TAG, "  [run_code] $toolName ok=${result.ok} len=${result.output.length}")
            val obj = cx.newObject(scope)
            ScriptableObject.putProperty(obj, "ok", result.ok)
            ScriptableObject.putProperty(obj, "output", result.output)
            return obj
        }

        // ---------- print ----------
        ScriptableObject.putProperty(
            scope,
            "print",
            jsHostFunction { _, _, args ->
                printed += args.joinToString(" ") { jsValuePreview(it) }
                Undefined.instance
            }
        )

        // ---------- tools ----------
        val tools = cx.newObject(scope)
        ScriptableObject.putProperty(tools, "listDir", jsHostFunction { _, _, a ->
            invoke("list_dir", JSONObject().put("path", jsArgStr(a, 0)))
        })
        ScriptableObject.putProperty(tools, "searchFiles", jsHostFunction { _, _, a ->
            invoke(
                "search_files",
                JSONObject().put("keyword", jsArgStr(a, 0)).put("path", jsArgStr(a, 1))
            )
        })
        ScriptableObject.putProperty(tools, "readFile", jsHostFunction { _, _, a ->
            invoke("read_file", JSONObject().put("path", jsArgStr(a, 0)))
        })
        ScriptableObject.putProperty(tools, "writeFile", jsHostFunction { _, _, a ->
            invoke(
                "write_file",
                JSONObject().put("path", jsArgStr(a, 0)).put("content", jsArgStr(a, 1))
            )
        })
        ScriptableObject.putProperty(tools, "replaceInFile", jsHostFunction { _, _, a ->
            invoke(
                "replace_in_file",
                JSONObject()
                    .put("path", jsArgStr(a, 0))
                    .put("search", jsArgStr(a, 1))
                    .put("replace", jsArgStr(a, 2))
                    .put("replace_all", jsArgBool(a, 3))
            )
        })
        ScriptableObject.putProperty(tools, "runShell", jsHostFunction { _, _, a ->
            invoke("run_shell", JSONObject().put("command", jsArgStr(a, 0)))
        })
        // ★ v1.1 联网搜索。
        //   注意：JS 沙箱本身没有网络（fetch / XMLHttpRequest 都被 ClassShutter 封了），
        //   这条是【宿主函数】—— 请求在 App 的 Java 层发出，脚本只是拿到一个字符串结果。
        //   所以「沙箱无网络」这条安全边界没有破：脚本依然摸不到任何 Java 类。
        ScriptableObject.putProperty(tools, "webSearch", jsHostFunction { _, _, a ->
            invoke("web_search", JSONObject().put("query", jsArgStr(a, 0)))
        })
        // ★ v1.5.0 模块二：网页正文提取，同样是宿主函数（请求在 Java 层发出）。
        ScriptableObject.putProperty(tools, "webFetch", jsHostFunction { _, _, a ->
            invoke("web_fetch", JSONObject().put("url", jsArgStr(a, 0)))
        })
        // ★ v1.4 无障碍：操作手机屏幕。
        //   同样走宿主函数 —— 真正的点击发生在 Java 层（AccessibilityService），
        //   脚本拿到的只是一个 {ok, output} 字符串结果。
        //   不暴露这两个方法的话，PTC 模式下模型根本调不到 screen_see / screen_tap。
        ScriptableObject.putProperty(tools, "screenSee", jsHostFunction { _, _, _ ->
            invoke("screen_see", JSONObject())
        })
        ScriptableObject.putProperty(tools, "screenTap", jsHostFunction { _, _, a ->
            invoke("screen_tap", JSONObject().put("text", jsArgStr(a, 0)))
        })
        ScriptableObject.putProperty(scope, "tools", tools)

        // ---------- 跑 ----------
        returned = cx.evaluateString(scope, code, "run_code.js", 1, null)
    } catch (e: Throwable) {
        failure = e.message ?: e.javaClass.simpleName
        // Rhino 的 EvaluatorException 带行号，对模型排错非常有用
        //
        // ⚠️ lineNumber 在 Rhino 1.7.13 里被标了 @Deprecated，
        //    但它是拿「脚本第几行出错」最直接的公开 API；
        //    替代方案（解析 message 字符串）更脆弱。所以这里显式抑制。
        @Suppress("DEPRECATION")
        if (e is EvaluatorException) failureLine = e.lineNumber
        Log.e(TOOL_LOG_TAG, "run_code 执行失败: ${e.message}", e)
    } finally {
        runCodeDeadline.remove()
        RhinoContext.exit()
    }

    val elapsed = SystemClock.elapsedRealtime() - startedAt

    val sb = StringBuilder()
    if (failure != null) {
        sb.append("代码执行失败")
        if (failureLine > 0) sb.append("（第 $failureLine 行）")
        sb.append("：").append(failure).append("\n\n")
    }

    if (innerCalls.isEmpty()) {
        sb.append("内部工具调用：0 次\n")
    } else {
        val summary = innerCalls.groupingBy { it }.eachCount()
            .entries.joinToString("、") { "${it.key} ×${it.value}" }
        sb.append("内部工具调用：${innerCalls.size} 次（$summary）\n")
    }

    if (printed.isNotEmpty()) {
        sb.append("print 输出：\n").append(printed.joinToString("\n")).append('\n')
    }

    // 脚本没抛异常、且最后一行表达式确实有值，才展示返回值
    if (failure == null && returned != null && returned !== Undefined.instance) {
        sb.append("返回值：").append(jsValuePreview(returned)).append('\n')
    }

    sb.append("耗时：${elapsed} ms")

    val text = if (sb.length > RUN_CODE_MAX_OUTPUT) {
        // take() 返回 CharSequence，没有 plus 运算符，必须显式 toString()
        sb.take(RUN_CODE_MAX_OUTPUT).toString() +
            "\n…（输出超过 $RUN_CODE_MAX_OUTPUT 字符，已截断）"
    } else {
        sb.toString()
    }

    // ok=false 只在脚本本身挂了的时候给。内部某个工具失败不算 overall 失败 ——
    // 模型可能在代码里已经判断并补救了，界面不该显示成红色错误。
    return ToolExecResult(failure == null, text)
}

// ============================================================
// 3.28 联网搜索（web_search）
//
// ★ 为什么直接接 云端搜索服务，而不是自己解析网页：
//   云端搜索服务在 2026-10-17 上线了 Search / Fetch 两个「裸 JSON」接口
//   （POST https://api.example.com/v1/tools/search），
//   和模型接口共用同一把 Bearer Key，返回的 summary 字段本身就是「写给模型读的」
//   600~800 字长摘要 —— 这正是我们需要的形状。
//   自己爬 DuckDuckGo / Bing 的 HTML 要解析页面结构，对方一改版就全废，
//   而且国内网络还不一定连得上。官方接口是这里唯一合理的选择。
//
// ★ 铁律（改这段之前先看）：
//   1. 不许硬编码任何 Key。key 一律读 ApiConfig.apiKey（用户填的、加密存的）。
//   2. 这个函数是【同步阻塞】的。它只被 executeTool 调用，而 executeTool 永远
//      跑在 runAgentLoop 的 Dispatchers.IO 协程里。绝对不要在 Compose 或主线程里直接调。
//   3. 任何异常都必须变成一句人话【返回给模型】，不许往上抛 ——
//      工具抛异常会让整轮对话变成红色错误气泡，用户看到的就是「搜索把聊天搞崩了」。
//   4. 它跟 50ms 流式批处理没有半点关系：搜索发生在「工具调用」阶段，
//      那时还没有任何 token 在流。别在这附近引入任何 Compose 状态读写。
//
// ★ 计费（官方口径）：
//   每 1000 次「HTTP 200 且有结果」的请求 5 元；
//   失败、超时、空结果都不计费。2026-11-01 00:00 起正式收费。
// ============================================================

/** 只要前 3 条。条数越多 token 越贵，3 条对回答"今天发生了什么"已经够了 */
private const val SEARCH_MAX_RESULTS = 3

/** 单次搜索最多等多久（秒）。搜索接口正常 1~3 秒返回，20 秒还没动静就是网络有问题 */
private const val SEARCH_TIMEOUT_SECONDS = 20L

/** 官方限制：query 1~128 个 Unicode 字符，不能全是空白 */
private const val SEARCH_MAX_QUERY_CHARS = 128

/** 塞回给模型的结果最多多少字符。3 条 × 800 字摘要 + 标题链接，4000 是够用的上限 */
private const val SEARCH_MAX_OUTPUT_CHARS = 4000

/** 没有配置 baseUrl 时的兜底（正常走 ApiConfig，这里是防 null） */
private const val SEARCH_DEFAULT_BASE = "https://api.example.com"

/**
 * 搜索专用的 OkHttp 客户端。
 *
 * ★ 为什么不直接复用全局的 httpClient：
 *   全局那个 readTimeout 是 60 秒 —— 对流式对话合理（模型吐字慢），
 *   对搜索就太长了：一次搜索卡 60 秒，用户会以为 App 死了。
 *   这里用 newBuilder() 派生一个：连接池、线程池、DNS 缓存全部共享，
 *   只是多了一个总超时，不会像「每请求新建 client」那样反复创建线程池。
 * ★ 用 by lazy 只创建一次，不是每次搜索都 newBuilder 一遍。
 */
private val searchClient: OkHttpClient by lazy {
    httpClient.newBuilder()
        .callTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
}

/**
 * 拼出搜索端点。
 *
 * 用户可能把 baseUrl 填成 `https://api.example.com`，
 * 也可能填成 `https://api.example.com/v1`，
 * 甚至直接填完整端点。三种都要能拼对，不能拼出 `/v1/v1/tools/search`。
 */
private fun toolsSearchUrl(): String {
    val base = ApiConfig.baseUrl.trim().trimEnd('/')
    return when {
        base.isEmpty() -> "$SEARCH_DEFAULT_BASE/v1/tools/search"
        base.endsWith("/tools/search") -> base
        base.endsWith("/v1") -> "$base/tools/search"
        else -> "$base/v1/tools/search"
    }
}

/**
 * 真正执行一次搜索。
 *
 * 返回的 ToolExecResult 会被 runAgentLoop 原样塞进 role:"tool" 消息，
 * 所以 output 里写的每一句话都是【给模型看的】，不是给用户看的 ——
 * 措辞要直白到模型照着做就行（尤其失败时，要告诉它"别重试"）。
 */
internal fun webSearch(query: String): ToolExecResult {
    val q = query.trim()

    // ---------- 参数校验 ----------
    if (q.isEmpty()) {
        return ToolExecResult(false, "参数错误：缺少 query（要搜索的关键词）。")
    }
    if (q.length > SEARCH_MAX_QUERY_CHARS) {
        return ToolExecResult(
            false,
            "参数错误：query 有 ${q.length} 个字符，超过官方上限 $SEARCH_MAX_QUERY_CHARS。\n" +
                "请把它压缩成几个关键词（例如「示例关键词 价格」），不要把整段问题贴进来。"
        )
    }

    // ---------- Key 校验 ----------
    // ★ 和 runAgentLoop 读的是同一个来源：ApiConfig.apiKey。
    //   不许出现任何写死的 key，也不许读 BuildConfig（release 包里那个值恒为空）。
    val key = ApiConfig.apiKey
    if (key.isBlank()) {
        return ToolExecResult(false, "搜索失败：请先在「设置 → 模型与 API」里填写 API Key。")
    }

    // ---------- 组请求体 ----------
    // count / need_content 都是官方字段；need_content=false 表示不要网页全文
    // （正文会让单条结果涨到几万字符，token 费用直接翻十几倍，这里只要摘要）。
    val bodyJson = JSONObject().apply {
        put("query", q)
        put("count", SEARCH_MAX_RESULTS)
        put("need_content", false)
    }.toString()

    val startedAt = SystemClock.elapsedRealtime()

    return try {
        val request = Request.Builder()
            .url(toolsSearchUrl())
            // ★ 唯一合法的鉴权来源：用户自己填的 Key
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // ★ use{} 保证 Response 一定被关掉，否则连接池会泄漏
        searchClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val cost = SystemClock.elapsedRealtime() - startedAt
            Log.d(
                TOOL_LOG_TAG,
                "web_search \"$q\" -> HTTP ${response.code}, ${cost}ms, ${raw.length} 字符"
            )

            if (!response.isSuccessful) {
                Log.e(TOOL_LOG_TAG, "web_search 失败 HTTP ${response.code}: ${raw.take(300)}")
                return@use ToolExecResult(false, searchHttpErrorText(response.code, raw))
            }
            parseSearchResults(raw, q)
        }
    } catch (e: SocketTimeoutException) {
        // 读写超时
        Log.e(TOOL_LOG_TAG, "web_search 超时: ${e.message}")
        ToolExecResult(false, "搜索失败：网络超时，请检查网络连接。")
    } catch (e: InterruptedIOException) {
        // ★ OkHttp 的 callTimeout 抛的是 InterruptedIOException("timeout")，
        //   不是 SocketTimeoutException —— 只捕获后者会漏掉「总超时」这一路。
        Log.e(TOOL_LOG_TAG, "web_search 总超时: ${e.message}")
        ToolExecResult(false, "搜索失败：网络超时，请检查网络连接。")
    } catch (e: UnknownHostException) {
        // DNS 解析不到 = 手机没网 / 域名被墙
        Log.e(TOOL_LOG_TAG, "web_search 域名解析失败: ${e.message}")
        ToolExecResult(false, "搜索失败：网络超时，请检查网络连接。")
    } catch (e: IOException) {
        Log.e(TOOL_LOG_TAG, "web_search 网络异常", e)
        ToolExecResult(false, "搜索失败：网络异常（${e.javaClass.simpleName}），请检查网络连接。")
    } catch (e: Exception) {
        // 兜底：宁可返回一句「搜索失败」，也不能让异常冒到 runAgentLoop 去
        Log.e(TOOL_LOG_TAG, "web_search 未知异常", e)
        ToolExecResult(false, "搜索失败：${e.javaClass.simpleName}: ${e.message}")
    }
}

/**
 * HTTP 状态码 → 给模型看的一句话。
 *
 * 官方接口的错误码语义（和普通 REST 略有不同，别搞混）：
 *   401 = Key 无效；402 = 余额不足；403 = 被安全策略拦截；
 *   429 = 超过 QPS（每个端点各 5 QPS）。
 */
private fun searchHttpErrorText(code: Int, body: String): String = when (code) {
    401, 402 -> "搜索失败：请检查您的 API Key 余额或权限。"
    403 -> "搜索失败：请求被拒绝（403）。可能是账号没有搜索权限，或这条查询被安全策略拦截了。不要换关键词反复重试。"
    429 -> "搜索失败：请求太频繁（429），官方限制每个账号每秒 5 次。请稍等一下再搜。"
    in 500..599 -> "搜索失败：搜索服务暂时不可用（HTTP $code）。这是对方的问题，不要连续重试，如实告诉用户稍后再试。"
    else -> "搜索失败：HTTP $code。${body.take(200)}"
}

/**
 * 把官方返回的 JSON 拼成「标题 + 摘要 + 链接」的纯文本。
 *
 * ★ 为什么用 summary 而不是 snippet：
 *   官方文档写得很清楚 —— snippet（100~200 字）是给【界面】展示的，
 *   summary（600~800 字）是给【模型】读的。这里的结果最终是塞进模型上下文的，
 *   所以必须取 summary，取错字段等于让模型拿着标题党摘要做推理。
 *   summary 为空时再退回 snippet。
 *
 * 用 internal 而不是 private：这一层是纯函数（字符串进、字符串出），
 * 可以在 JVM 单测里逐条断言，不需要真机也不需要网络 —— 见 WebSearchTest。
 */
internal fun parseSearchResults(raw: String, query: String): ToolExecResult {
    val root = try {
        JSONObject(raw)
    } catch (e: Exception) {
        Log.e(TOOL_LOG_TAG, "web_search 响应不是合法 JSON: ${raw.take(200)}", e)
        return ToolExecResult(false, "搜索失败：返回内容不是合法 JSON，无法解析。")
    }

    // ★ 铁律 5：判空一律 opt() + as? String，不用 optString 的默认值写法。
    //   Android 的 org.json 会把 JSON null 变成字符串 "null"。
    val results = root.optJSONArray("results")

    // ★ 官方文档：查不到东西返回的是 HTTP 200 + 空数组，【不是错误】。
    //   这里必须 ok = true，否则界面上会出现一张红卡片，还会诱导模型反复重试。
    if (results == null || results.length() == 0) {
        return ToolExecResult(
            true,
            "没有找到与「$query」相关的网页。\n" +
                "这是正常的空结果，不是调用失败。不要用同样的关键词反复搜索，" +
                "可以换个说法再试一次，或者如实告诉用户没搜到。"
        )
    }

    val n = minOf(results.length(), SEARCH_MAX_RESULTS)
    val sb = StringBuilder()
    sb.append("以下是「").append(query).append("」的联网搜索结果（共 ").append(n).append(" 条）：\n")

    for (i in 0 until n) {
        val item = results.optJSONObject(i) ?: continue
        val title = (item.opt("title") as? String)?.trim().orEmpty().ifBlank { "(无标题)" }
        val url = (item.opt("url") as? String)?.trim().orEmpty()
        val summary = (item.opt("summary") as? String)?.trim().orEmpty()
            .ifBlank { (item.opt("snippet") as? String)?.trim().orEmpty() }
        val site = (item.opt("site_name") as? String)?.trim().orEmpty()
        // date 是 RFC 3339（2026-06-01T00:00:00+00:00），只取前 10 位当日期用
        val date = (item.opt("date") as? String)?.trim().orEmpty().take(10)

        sb.append("\n[").append(i + 1).append("] ").append(title).append('\n')
        if (site.isNotEmpty() || date.isNotEmpty()) {
            sb.append("    来源：").append(site.ifBlank { "未知" })
            if (date.isNotEmpty()) sb.append("　发布：").append(date)
            sb.append('\n')
        }
        if (url.isNotEmpty()) sb.append("    链接：").append(url).append('\n')
        sb.append("    摘要：").append(summary.ifBlank { "（这条结果没有摘要）" }).append('\n')
    }

    sb.append("\n---\n")
    sb.append("使用要求：\n")
    sb.append("1. 上面是网页内容，可能有错、也可能过时，不要当成绝对事实。\n")
    sb.append("2. 引用具体信息时，把对应的「链接」原样写在回答末尾，方便用户自己核对。\n")
    sb.append("3. 不要在回答里编造上面没有的内容；结果不够就明说不够。\n")

    val text = sb.toString()
    return ToolExecResult(
        true,
        if (text.length > SEARCH_MAX_OUTPUT_CHARS) {
            text.take(SEARCH_MAX_OUTPUT_CHARS) + "\n…（结果过长，已截断）"
        } else {
            text
        }
    )
}

internal fun executeTool(name: String, argsJson: String): ToolExecResult {
    Log.d(TOOL_LOG_TAG, "开始执行工具: name=$name, args=$argsJson")

    // ★ v1.3 MCP 拦截分发：
    //   模型回的调用有两种形态——带 mcp_ 前缀（与内置工具重名时），或原名（无冲突时）。
    //   前缀形态直接剥前缀转外部；原名形态必须在本周期 MCP 工具集合里才转外部，
    //   否则会把「模型幻觉出来的未知工具」也发给服务器。
    //   本函数由 runAgentLoop 在 IO 协程里调用，mcpCallTool 的阻塞网络请求不会碰主线程。
    if (name.startsWith("mcp_") || name in mcpActiveToolNames) {
        val realName = if (name.startsWith("mcp_")) name.removePrefix("mcp_") else name
        Log.d(TOOL_LOG_TAG, "MCP 分发: $name -> $realName")
        return try {
            mcpCallTool(realName, argsJson)
        } catch (e: Exception) {
            // mcpCallTool 内部已兜底，这里是最后一道保险
            Log.e(TOOL_LOG_TAG, "MCP 工具执行异常: " + e.message, e)
            ToolExecResult(false, "外部 MCP 工具调用失败：" + e.javaClass.simpleName + ": " + e.message)
        }
    }

    val result = try {
        when (name) {
            "read_file" -> {
                val path = cleanPath(argOf(argsJson, "path"))
                if (path.isBlank()) {
                    Log.e(TOOL_LOG_TAG, "参数错误：缺少 path，原始参数=$argsJson")
                    ToolExecResult(
                        false,
                        "参数错误：缺少 path。请给出文件的绝对路径，例如 /sdcard/Download/a.txt"
                    )
                } else {
                    readTextFile(path)
                }
            }

            "write_file" -> {
                val path = cleanPath(argOf(argsJson, "path"))

                // ★ content 绝对不能过 cleanPath：
                //   cleanPath 会削掉首尾的引号 / 逗号 / 句号 —— 对路径是必要的，
                //   对正文是毁灭性的（一段以 " 结尾的代码、一句以。结尾的中文都会被吃掉）。
                val content = argOf(argsJson, "content")

                when {
                    path.isBlank() -> ToolExecResult(
                        false,
                        "参数错误：缺少 path。请给出要写入的文件绝对路径，例如 /sdcard/Documents/a.txt"
                    )

                    content.isEmpty() -> ToolExecResult(
                        false,
                        "参数错误：content 是空的，一个字都没写。" +
                            "如果确实想创建一个空文件，请把 content 传成 \"\\n\"。"
                    )

                    else -> writeTextFile(path, content)
                }
            }

            "replace_in_file" -> {
                val path = cleanPath(argOf(argsJson, "path"))

                // ★ search / replace 同样不能过 cleanPath：
                //   cleanPath 会削掉首尾引号和标点，一段以 " 或 } 结尾的代码会被削坏，
                //   削过的 search 永远匹配不上原文。
                val search = argOf(argsJson, "search")
                val replace = argOf(argsJson, "replace")
                val replaceAll = argBoolOf(argsJson, "replace_all")

                when {
                    path.isBlank() -> ToolExecResult(
                        false,
                        "参数错误：缺少 path。请给出要修改的文件绝对路径，例如 /sdcard/Documents/a.kt"
                    )

                    search.isEmpty() -> ToolExecResult(
                        false,
                        "参数错误：缺少 search。请把要被替换掉的那段原文原样填进来。"
                    )

                    else -> replaceInFile(path, search, replace, replaceAll)
                }
            }

            "list_dir" -> {
                val path = cleanPath(argOf(argsJson, "path"))
                if (path.isBlank()) {
                    ToolExecResult(false, "参数错误：缺少 path。请给出目录的绝对路径，例如 /sdcard")
                } else {
                    listDirectory(path)
                }
            }

            "search_files" -> {
                val keyword = argOf(argsJson, "keyword").trim()
                val path = cleanPath(argOf(argsJson, "path"))
                when {
                    keyword.isBlank() -> ToolExecResult(false, "参数错误：缺少 keyword（要搜索的文件名关键词）")
                    path.isBlank() -> ToolExecResult(false, "参数错误：缺少 path（从哪个目录开始搜）")
                    else -> searchFiles(keyword, path)
                }
            }

            "sqlite_query" -> {
                // db_path 可以过 cleanPath（它是路径）；sql 绝对不行 ——
                // cleanPath 会削掉首尾标点，一段以分号或引号结尾的 SQL 会被削坏。
                val dbPath = cleanPath(argOf(argsJson, "db_path"))
                val sql = argOf(argsJson, "sql")
                if (dbPath.isBlank()) {
                    ToolExecResult(false, "参数错误：缺少 db_path。")
                } else {
                    sqliteQuery(dbPath, sql)
                }
            }

            "regex_extract" -> {
                // text / pattern 都是正文，同样不能过 cleanPath
                regexExtract(argOf(argsJson, "text"), argOf(argsJson, "pattern"))
            }

            "semantic_search" -> {
                val q = argOf(argsJson, "query").trim()
                val k = argOf(argsJson, "top_k").trim().toIntOrNull() ?: 5
                if (q.isBlank()) {
                    ToolExecResult(false, "参数错误：缺少 query（要搜索的自然语言查询）")
                } else {
                    semanticSearch(q, k)
                }
            }

            // ★ v1.4.0 战役一：任务规划清单。纯内存，不碰 IO，无需切协程。
            "todo" -> todoTool(argsJson)

            "run_shell" -> {
                val command = argOf(argsJson, "command").trim()
                if (command.isBlank()) {
                    ToolExecResult(false, "参数错误：缺少 command（要执行的 shell 命令）")
                } else {
                    runShell(command)
                }
            }

            // ★ v1.4 系统级工具：全部复用 runShell 的 Shizuku 通道（自带黑名单+状态检查）。
            "get_clipboard" -> getClipboard()

            "send_notification" -> {
                val title = argOf(argsJson, "title")
                val content = argOf(argsJson, "content")
                if (title.isBlank() || content.isBlank()) {
                    ToolExecResult(false, "参数错误：缺少 title 或 content")
                } else {
                    sendNotification(title, content)
                }
            }

            "take_screenshot" -> takeScreenshot(currentWorkspace)

            // ★ v1.4.0 战役四：无障碍屏幕操作（看屏幕 / 点屏幕）。
            //   text 不过 cleanPath：按钮文字里的标点是有意义的。
            "screen_see" -> screenSee()
            "screen_tap" -> screenTap(argOf(argsJson, "text"))

            // ★ v1.4 战役二：工作区全文检索。
            "search_workspace" -> searchWorkspace(argOf(argsJson, "query"))

            // ★ v1.2.0 战役四：calculate / http_request / open_app。
            "calculate" -> calculate(argOf(argsJson, "expression"))
            "http_request" -> httpRequest(
                url = argOf(argsJson, "url"),
                method = argOf(argsJson, "method"),
                body = argOf(argsJson, "body")
            )
            "open_app" -> openApp(argOf(argsJson, "package_name"))

            // ★ v1.1 联网搜索。
            //   query 绝不能过 cleanPath —— 它会削掉首尾的引号和标点，
            //   而搜索运算符本身就靠这些字符：`"exact phrase"`、`-词`。
            //   削一刀，运算符就废了，模型还会收到一个语义不同的结果。
            "web_search" -> {
                val query = argOf(argsJson, "query")
                if (query.isBlank()) {
                    ToolExecResult(false, "参数错误：缺少 query（要搜索的关键词）")
                } else {
                    webSearch(query)
                }
            }

            // ★ v1.5.0 模块二：网页正文深度提取。
            //   url 绝不能过 cleanPath —— 它会削掉首尾的引号/斜杠，
            //   而网址末尾的斜杠、带引号的链接都是合法的。
            "web_fetch" -> {
                val url = argOf(argsJson, "url")
                if (url.isBlank()) {
                    ToolExecResult(false, "参数错误：缺少 url（要读取的网址）")
                } else {
                    webFetch(url)
                }
            }

            // ★ PTC 模式的唯一入口。code 绝不能过 cleanPath ——
            //   它会削掉首尾标点，一段以 } 或 " 结尾的代码会被削坏。
            "run_code" -> {
                val code = argOf(argsJson, "code")
                if (code.isBlank()) {
                    ToolExecResult(false, "参数错误：缺少 code（要执行的 JavaScript 源码）")
                } else {
                    runCode(code)
                }
            }

            // ★ v1.5.0 模块一：长期记忆。
            //   两个分支都很薄，真正的逻辑在 MemoryStore（纯 File 逻辑，可单测）。
            "save_memory" -> {
                val content = argOf(argsJson, "content")
                if (content.isBlank()) {
                    ToolExecResult(false, "参数错误：缺少 content（要记住的内容）")
                } else {
                    saveMemory(content)
                }
            }

            "recall_memory" -> {
                val query = argOf(argsJson, "query")
                if (query.isBlank()) {
                    ToolExecResult(false, "参数错误：缺少 query（要检索的关键词）")
                } else {
                    recallMemory(query)
                }
            }

            else -> {
                Log.e(TOOL_LOG_TAG, "未知工具: $name")
                ToolExecResult(false, "未知工具：$name")
            }
        }
    } catch (e: Exception) {
        // 完整异常 + 堆栈，Logcat 里 tag:ToolExec 直接看
        Log.e(TOOL_LOG_TAG, "读取失败: ${e.message}", e)
        ToolExecResult(false, "执行失败（$name）：${e.javaClass.simpleName}: ${e.message}")
    }

    // ★ 出口统一拦截：超长输出落盘，只把预览交给模型，防 Token 爆炸
    return if (result.ok) result.copy(output = persistOversizeToolOutput(name, result.output)) else result
}

// ============================================================
// ★ 超长工具结果落盘
//   工具输出太长时（read_file 读到大文件、run_shell 刷屏…），整段塞进模型上下文
//   会直接吃掉窗口。这里统一在 executeTool 出口拦一道：完整内容写进工作区的
//   .coolstream/temp/，回给模型的只有开头一段预览 + 文件路径，需要时再让模型
//   自己用 read_file 配合 offset 分段读。信息不丢，Token 不炸。
// ============================================================

/** 输出超过这个字符数就落盘 */
internal const val TOOL_INLINE_LIMIT = 8_000

/** 落盘后回传给模型的预览长度 */
internal const val TOOL_INLINE_HEAD = 4_000

/** 落盘目录（相对工作区） */
internal const val TOOL_TEMP_DIR = ".coolstream/temp"

/** 临时文件最多保留几个，超出的按修改时间从旧到新删 */
internal const val TOOL_TEMP_KEEP = 50

/**
 * 把过长的工具输出落盘，只回传开头一段预览 + 文件路径。
 * 短输出原样返回，一个字符都不改。
 */
internal fun persistOversizeToolOutput(toolName: String, output: String): String {
    if (output.length <= TOOL_INLINE_LIMIT) return output

    val head = output.take(TOOL_INLINE_HEAD)
    val ws = currentWorkspace
    if (ws.isBlank()) {
        return head + "\n\n…（输出共 ${output.length} 字符，已截断；当前未设置工作区，完整内容未落盘）"
    }

    val dir = File(ws, TOOL_TEMP_DIR)
    if (!dir.isDirectory && !dir.mkdirs()) {
        return head + "\n\n…（输出共 ${output.length} 字符，已截断；临时目录创建失败：${dir.absolutePath}）"
    }

    return try {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val safeName = toolName.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "tool" }
        val file = File(dir, "$safeName-$stamp.txt")
        file.writeText(output, Charsets.UTF_8)
        pruneToolTempDir(dir)
        head +
            "\n\n…（输出共 ${output.length} 字符，超过 $TOOL_INLINE_LIMIT 字符上限，已截断）" +
            "\n完整内容已保存到：${file.absolutePath}" +
            "\n需要后面的部分，请用 read_file 配合 offset 分段读取，不要一次读完。"
    } catch (e: Exception) {
        Log.w(TOOL_LOG_TAG, "工具结果落盘失败: ${e.message}")
        head + "\n\n…（输出共 ${output.length} 字符，已截断；落盘失败：${e.javaClass.simpleName}）"
    }
}

/** 只留最近 TOOL_TEMP_KEEP 个临时文件，避免无限堆积 */
private fun pruneToolTempDir(dir: File) {
    val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
    files.drop(TOOL_TEMP_KEEP).forEach { runCatching { it.delete() } }
}

// ============================================================
// MCP（Model Context Protocol）轻量客户端 —— v1.3
//
//   纯 OkHttp + JSONObject 手搓 JSON-RPC 2.0，不引入任何 MCP SDK。
//   只做无状态 Streamable HTTP 交互：initialize 握手 -> tools/list
//   -> tools/call，每个请求回传服务器下发的 Mcp-Session-Id。
//   ★ 全部阻塞式实现，调用方必须包在 Dispatchers.IO 里
//     （铁律：绝不阻塞主线程）。executeTool 本身就跑在 IO 协程里。
// ============================================================

/**
 * App 设置页里的 MCP 地址（内存镜像，重启由 loadAppSettings 灌回）。
 * v1.3.0：MCPTransport 直接读这个变量，地址变更时自动重新握手。
 */
internal var mcpServerUrl: String = ""

/**
 * 本请求周期可用的外部工具「原名」集合（runAgentLoop 探测成功后灌入）。
 * 分发依据：模型调用的工具带 mcp_ 前缀，或原名在此集合里（无冲突时外部工具不加前缀）。
 */
internal var mcpActiveToolNames: Set<String> = emptySet()

/** 外部工具三元组：原名 / 描述 / 参数 JSON Schema 字符串 */
internal data class McpTool(val name: String, val description: String, val schema: String)

/**
 * 对外：列出 MCP Server 上的全部工具。
 * 返回 (工具列表, 失败原因)；没配置地址静默跳过（null），连接失败给原因。
 */
internal fun mcpListTools(): Pair<List<McpTool>, String?> {
    if (mcpServerUrl.isBlank()) return emptyList<McpTool>() to null
    return try {
        val result = MCPTransport.rpcCall("tools/list", JSONObject())
        val tools = mutableListOf<McpTool>()
        val arr = result.optJSONArray("tools")
        for (i in 0 until (arr?.length() ?: 0)) {
            val t = arr!!.optJSONObject(i) ?: continue
            val schema = t.optJSONObject("inputSchema")?.toString() ?: "{}"
            tools.add(McpTool(t.optString("name"), t.optString("description"), schema))
        }
        MCPTransport.reportToolCount(tools.size)
        tools to null
    } catch (e: Exception) {
        Log.w(TOOL_LOG_TAG, "mcpListTools 失败: " + e.message)
        MCPTransport.reportToolCount(0)
        emptyList<McpTool>() to "MCP 服务器连接失败：" + e.message
    }
}

/**
 * 对外：调用 MCP 工具，content 数组里的 text 全部拼接返回。
 * 非 text 类型（image / resource）标注占位说明，不硬塞二进制。
 */
internal fun mcpCallTool(toolName: String, argsJson: String): ToolExecResult {
    if (mcpServerUrl.isBlank()) {
        return ToolExecResult(false, "MCP 未配置：请先在「设置 -> 插件」里填写 MCP 服务器地址。")
    }
    return try {
        // 每次调用都重新握手 —— 无状态实现最抗服务器重启，代价是多一个往返
        val args = if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
        val params = JSONObject()
            .put("name", toolName)
            .put("arguments", args)
        val result = MCPTransport.rpcCall("tools/call", params)

        val sb = StringBuilder()
        val content = result.optJSONArray("content")
        for (i in 0 until (content?.length() ?: 0)) {
            val item = content!!.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "text" -> {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(item.optString("text"))
                }
                "image" -> sb.append("\n[该 MCP 工具返回了一张图片，CoolStream 暂不支持展示]")
                else -> sb.append("\n[该 MCP 工具返回了不支持的内容类型: " + item.optString("type") + "]")
            }
        }
        if (sb.isEmpty()) {
            ToolExecResult(true, "（MCP 工具 " + toolName + " 执行完成，无文本输出）")
        } else {
            ToolExecResult(true, sb.toString())
        }
    } catch (e: Exception) {
        Log.w(TOOL_LOG_TAG, "mcpCallTool(" + toolName + ") 失败: " + e.message)
        ToolExecResult(false, "MCP 工具 " + toolName + " 调用失败：" + e.message)
    }
}

/**
 * 把外部工具合并进内置 tools 数组：名字冲突加 mcp_ 前缀。
 * 返回 (合并后的 JSONArray, 冲突改名映射 原名 -> mcp_原名)。
 */
internal fun mergeMcpTools(builtin: JSONArray, external: List<McpTool>): Pair<JSONArray, Map<String, String>> {
    val out = JSONArray()
    val renamed = mutableMapOf<String, String>()
    val builtinNames = mutableSetOf<String>()
    for (i in 0 until builtin.length()) {
        val item = builtin.optJSONObject(i) ?: continue
        builtinNames.add(item.optJSONObject("function")?.optString("name").orEmpty())
        out.put(item)
    }
    for (t in external) {
        val finalName = if (t.name in builtinNames) "mcp_" + t.name else t.name
        if (finalName != t.name) renamed[t.name] = finalName
        out.put(
            JSONObject()
                .put("type", "function")
                .put(
                    "function", JSONObject()
                        .put("name", finalName)
                        .put("description", t.description.ifBlank { "（外部 MCP 工具，无描述）" })
                        .put("parameters", runCatching { JSONObject(t.schema) }.getOrDefault(JSONObject()))
                )
        )
    }
    return out to renamed
}

/**
 * v1.3：把 MCP 外部工具拼成 system prompt 追加段。
 * 实测（同内置工具的教训）：tools 数组里带了工具，但 system prompt 不提一句，
 * 模型就不知道自己能调它们 —— toolCalls 永远是 0。
 * mcpTools 为空时返回空串（调用方按空串跳过追加）。
 */
internal fun mcpPromptSection(mcpTools: List<McpTool>): String {
    if (mcpTools.isEmpty()) return ""
    val lines = mcpTools.joinToString("\n") { t ->
        "- " + t.name + "：" + t.description.ifBlank { "（外部 MCP 工具，无描述）" }
    }
    return buildString {
        append("\n\n16. 当前已外接 MCP 工具，请优先考虑使用。")
        append("\n以下是用户配置的 MCP 服务器提供的外部工具，本轮可以直接调用（调用方式与内置工具相同）：")
        append("\n")
        append(lines)
        append("\n调用成功后基于返回结果回答；调用失败就把失败原因如实告诉用户。")
        append("这些工具来自外部服务器，不要编造它们不存在的能力。")
    }
}

// ============================================================
// 3.30 长期记忆（v1.5.0 模块一）
//
// 两个工具都是「薄壳」：参数校验 + 取文件 + 调 MemoryStore。
// 所有文件 IO 都在这里同步做完 —— executeTool 本来就保证跑在
// Dispatchers.IO 上（runAgentLoop 的 withContext），主线程不会碰磁盘。
// ============================================================

/** save_memory：把一段内容追加进长期记忆库 */
internal fun saveMemory(content: String): ToolExecResult {
    val text = content.trim()
    if (text.isEmpty()) return ToolExecResult(false, "参数错误：缺少 content（要记住的内容）")
    val file = AppCtx.memoryFile()
        ?: return ToolExecResult(false, "记忆库不可用：App 上下文还没初始化（需要重启 App）")
    return try {
        val item = MemoryStore.append(file, text)
        if (item == null) {
            ToolExecResult(false, "写入记忆库失败：${file.absolutePath} 不可写")
        } else {
            ToolExecResult(
                true,
                "已记住（id=${item.id}，当前共 ${MemoryStore.load(file).size} 条）：\n${item.content}"
            )
        }
    } catch (e: Exception) {
        ToolExecResult(false, "写入记忆库失败：${e.javaClass.simpleName}: ${e.message}")
    }
}

/** recall_memory：模糊检索长期记忆库 */
internal fun recallMemory(query: String): ToolExecResult {
    val q = query.trim()
    if (q.isEmpty()) return ToolExecResult(false, "参数错误：缺少 query（要检索的关键词）")
    val file = AppCtx.memoryFile()
        ?: return ToolExecResult(false, "记忆库不可用：App 上下文还没初始化（需要重启 App）")
    return try {
        val all = MemoryStore.load(file)
        if (all.isEmpty()) {
            ToolExecResult(true, "记忆库还是空的，没有可用的长期记忆。")
        } else {
            val hits = MemoryStore.rank(all, q, MEMORY_RECALL_LIMIT)
            ToolExecResult(true, MemoryStore.formatRecall(hits, q))
        }
    } catch (e: Exception) {
        ToolExecResult(false, "检索记忆库失败：${e.javaClass.simpleName}: ${e.message}")
    }
}

