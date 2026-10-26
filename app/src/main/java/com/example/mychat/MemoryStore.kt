package com.example.mychat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * v1.5.0 模块一：长期记忆系统（存储层）。
 *
 * 为什么单开一个文件：记忆的读写是纯数据逻辑，跟 UI、跟 Agent 循环都没关系。
 * 这里的函数只依赖 File 和 String，不碰 Context —— 这样 JVM 单测可以直接跑，
 * 不需要 Robolectric，也不会因为测试环境里没有 filesDir 而挂掉。
 *
 * ★ 关于「在工程目录下建 memory.json」：
 *   Android 运行时进程对源码工程目录没有写权限（那是构建机上的路径，
 *   装到手机上根本不存在）。真正能长期存在、只有卸载才清掉的位置是
 *   Context.filesDir，也就是 /data/data/<包名>/files/memory.json。
 *   工具层拿不到 Context，所以用下面的 AppCtx 在 onCreate 里注入一次。
 */

/** 一条记忆。id 只用来删除，界面上不展示。 */
internal data class MemoryItem(
    val id: String,
    val content: String,
    val createdAt: Long
)

/** 单条记忆最大字数：防止模型把整篇文章塞进来，一次 recall 就把上下文撑爆 */
internal const val MEMORY_MAX_CHARS = 2000

/** 记忆条数上限：超了淘汰最旧的，保证 memory.json 不会无限长大 */
internal const val MEMORY_MAX_ITEMS = 200

/** recall_memory 默认返回条数 */
internal const val MEMORY_RECALL_LIMIT = 10

/**
 * 全局 Context 持有者。
 *
 * 为什么必须要有：executeTool 是同步纯函数，签名里没有 Context，
 * 而项目里也没有 Application 子类。get_clipboard 那些工具能绕过去，
 * 是因为它们全走 Shizuku shell；记忆库要写文件，绕不过去。
 * 只在 MainActivity.onCreate 里 install 一次，之后的读取都是只读。
 */
internal object AppCtx {
    @Volatile
    private var app: Context? = null

    fun install(context: Context) {
        app = context.applicationContext
    }

    /** 记忆库文件；上下文还没注入时返回 null（工具层据此给出明确报错，而不是崩） */
    fun memoryFile(): File? {
        val c = app ?: return null
        return File(c.filesDir, "memory.json")
    }
}

internal object MemoryStore {

    /** 写文件时的互斥锁：Agent 循环理论上可能并发触发两次 save_memory */
    private val lock = Any()

    // ---------- 序列化 ----------

    /**
     * 解析 memory.json。
     *
     * 容错优先：文件被截断、被手改坏、是空文件，一律当「没有记忆」处理，
     * 绝不让一条坏数据把整个记忆库（以及 App）搞崩。
     */
    fun parse(raw: String): List<MemoryItem> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<MemoryItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val content = o.optString("content", "")
                if (content.isBlank()) continue
                out.add(
                    MemoryItem(
                        id = o.optString("id", "").ifBlank { "legacy-$i" },
                        content = content,
                        createdAt = o.optLong("createdAt", 0L)
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun serialize(items: List<MemoryItem>): String {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("content", item.content)
                    .put("createdAt", item.createdAt)
            )
        }
        return arr.toString()
    }

    // ---------- 文件读写 ----------

    fun load(file: File): List<MemoryItem> {
        if (!file.exists()) return emptyList()
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persist(file: File, items: List<MemoryItem>): Boolean {
        return try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            file.writeText(serialize(items))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 追加一条记忆。
     *
     * 返回 null 表示落盘失败（调用方据此回 ok=false，而不是假装记住了）。
     * 超出 [MEMORY_MAX_ITEMS] 时丢最旧的，保证文件不会无限长大。
     */
    fun append(file: File, content: String, now: Long = System.currentTimeMillis()): MemoryItem? {
        val text = content.trim().take(MEMORY_MAX_CHARS)
        if (text.isEmpty()) return null
        synchronized(lock) {
            val item = MemoryItem(
                id = UUID.randomUUID().toString().replace("-", "").take(12),
                content = text,
                createdAt = now
            )
            val all = ArrayList(load(file))
            all.add(item)
            val trimmed = if (all.size > MEMORY_MAX_ITEMS) all.subList(all.size - MEMORY_MAX_ITEMS, all.size) else all
            return if (persist(file, trimmed)) item else null
        }
    }

    /** 按 id 删除一条。返回是否真的删掉了。 */
    fun delete(file: File, id: String): Boolean {
        if (id.isBlank()) return false
        synchronized(lock) {
            val all = load(file)
            val kept = all.filter { it.id != id }
            if (kept.size == all.size) return false
            return persist(file, kept)
        }
    }

    // ---------- 模糊检索 ----------

    /** 中日韩统一表意文字（够用了：记忆基本都是中英混排） */
    private fun isCjk(c: Char): Boolean = c in '\u4e00'..'\u9fff'

    /**
     * 把查询切成检索词。
     *
     * 英文/数字靠空格和标点切；中文没有空格，整句会被当成一个词，
     * 所以对含中文的长词再补一层「两字滑窗」——
     * 用户问「我之前说过的旅行偏好」，才可能命中记忆里的「旅行」两个字。
     */
    fun tokenize(query: String): List<String> {
        val norm = query.lowercase(Locale.US).trim()
        if (norm.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        norm.split(
            ' ', '\t', '\n', '\r', ',', '.', '，', '。', '、', '；', ';', '：', ':',
            '？', '?', '！', '!', '(', ')', '（', '）', '「', '」', '"', '\''
        ).filter { it.isNotBlank() }.forEach { out.add(it) }
        for (token in out.toList()) {
            if (token.length >= 3 && token.any { isCjk(it) }) {
                for (i in 0 until token.length - 1) {
                    val bi = token.substring(i, i + 2)
                    if (bi.all { isCjk(it) }) out.add(bi)
                }
            }
        }
        return out.toList()
    }

    /**
     * 打分：命中的检索词个数 × 10，整句原样命中再 +15。
     *
     * 为什么要整句加权：中文长句会拆出很多两字词，
     * 只中一个「我的」不该排在真正相关的记忆前面。
     */
    fun score(content: String, tokens: List<String>, wholeQuery: String): Int {
        if (content.isEmpty() || tokens.isEmpty()) return 0
        val hay = content.lowercase(Locale.US)
        var matched = 0
        for (t in tokens) if (hay.contains(t)) matched++
        if (matched == 0) return 0
        val whole = if (wholeQuery.isNotBlank() && hay.contains(wholeQuery.lowercase(Locale.US).trim())) 15 else 0
        return matched * 10 + whole
    }

    /** 过滤 + 排序 + 截断。没有命中的记忆不返回，绝不「随便给几条凑数」。 */
    fun rank(items: List<MemoryItem>, query: String, limit: Int = MEMORY_RECALL_LIMIT): List<MemoryItem> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<MemoryItem, Int>>()
        for (item in items) {
            val s = score(item.content, tokens, query)
            if (s > 0) scored.add(item to s)
        }
        scored.sortWith(
            compareByDescending<Pair<MemoryItem, Int>> { it.second }
                .thenByDescending { it.first.createdAt }
        )
        return scored.take(limit.coerceAtLeast(1)).map { it.first }
    }

    // ---------- 结果格式化 ----------

    fun formatTime(ts: Long): String {
        if (ts <= 0L) return "未知时间"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))
    }

    /** 给模型看的 recall 结果。空结果也要说清楚，否则模型会自己编一段「记忆」。 */
    fun formatRecall(hits: List<MemoryItem>, query: String): String {
        if (hits.isEmpty()) {
            return "记忆库里没有和「$query」相关的内容。"
        }
        val sb = StringBuilder()
        sb.append("找到 ").append(hits.size).append(" 条相关记忆：")
        for ((index, item) in hits.withIndex()) {
            sb.append("\n").append(index + 1).append(". [")
                .append(formatTime(item.createdAt)).append("] ").append(item.content)
        }
        return sb.toString()
    }
}
