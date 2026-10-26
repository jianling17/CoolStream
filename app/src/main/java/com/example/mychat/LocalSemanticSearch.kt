package com.example.mychat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * CoolStream v1.3.0 —— 零依赖本地语义检索内核。
 *
 * 设计约束（与 2MB 包体红线对齐）：
 *  1. 只用 JDK / Kotlin stdlib / 项目已内置的 kotlinx-coroutines，零新增第三方依赖。
 *  2. 纯 JVM 语义，不依赖任何 Android 框架类 —— 可直接在 unit test 中跑，不上设备。
 *  3. 所有磁盘 IO 走 Dispatchers.IO，所有向量计算走 Dispatchers.Default，主线程零参与。
 *  4. 索引写入 = 原子替换快照（copy-on-write），读路径无锁，避免检索被索引构建阻塞。
 *
 * 算法：极简 TF-IDF（次线性 TF + 平滑 IDF + 词表裁剪）+ 稀疏向量余弦相似度 + 小顶堆 Top-K。
 * 中文分词：连续中文串切 2-gram，拉丁串按词切分，双向停用词过滤。
 */
class LocalSemanticSearch(
    private val tokenizer: Tokenizer = Tokenizer.default(),
    private val tfidfParams: TfidfParams = TfidfParams(),
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val chunkOverlap: Int = DEFAULT_CHUNK_OVERLAP
) {

    init {
        require(chunkSize > 0) { "chunkSize must be > 0" }
        require(chunkOverlap >= 0) { "chunkOverlap must be >= 0" }
        require(chunkOverlap < chunkSize) { "chunkOverlap($chunkOverlap) must be < chunkSize($chunkSize)" }
    }

    /** 一次索引的全部只读状态。整体替换，读路径无需加锁。 */
    private class IndexSnapshot(
        val model: TfidfModel,
        val chunks: List<Chunk>,
        val vectors: List<SparseVector>,
        val stats: IndexStats
    )

    @Volatile
    private var snapshot: IndexSnapshot? = null

    /** 仅保护「写入方」，保证并发索引不会互相踩坏快照。读路径不碰它。 */
    private val installMutex = Mutex()

    /** 当前已索引的文本块数量。 */
    val indexedChunkCount: Int get() = snapshot?.chunks?.size ?: 0

    /** 当前词表维度。 */
    val vocabularySize: Int get() = snapshot?.model?.dimension ?: 0

    // ---------------------------------------------------------------------
    // 索引构建
    // ---------------------------------------------------------------------

    /**
     * 并发索引一批文件。文件读取在 Dispatchers.IO 上以 [parallelism] 为上限并发，
     * 向量化在 Dispatchers.Default 上单点完成（词表 IDF 需要全局统计，天然不可并行）。
     */
    suspend fun indexFiles(
        files: List<File>,
        parallelism: Int = DEFAULT_IO_PARALLELISM,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): IndexStats {
        val startedAt = System.nanoTime()
        val total = files.size
        val completed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val gate = Semaphore(parallelism.coerceAtLeast(1))

        val collected = ArrayList<Chunk>(total * 4)

        coroutineScope {
            files.map { file ->
                async(Dispatchers.IO) {
                    gate.acquire()
                    try {
                        val text = readTextOrNull(file, maxFileBytes)
                        if (text == null) {
                            skipped.incrementAndGet()
                        } else {
                            // 局部 list 组装后再整体入队，避免多线程争用共享集合
                            val local = ArrayList<Chunk>(8)
                            chunkText(file.absolutePath, text, local)
                            synchronized(collected) { collected.addAll(local) }
                        }
                    } catch (_: Throwable) {
                        // 单个文件读取失败不允许拖垮整轮索引
                        skipped.incrementAndGet()
                    } finally {
                        gate.release()
                        onProgress?.invoke(completed.incrementAndGet(), total)
                    }
                }
            }.awaitAll()
        }

        return install(collected, total, skipped.get(), startedAt)
    }

    /** 递归收集目录下的文本文件并索引。目录遍历本身也在 IO 线程上执行。 */
    suspend fun indexDirectory(
        root: File,
        extensions: Set<String> = DEFAULT_EXTENSIONS,
        parallelism: Int = DEFAULT_IO_PARALLELISM,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): IndexStats {
        val files = withContext(Dispatchers.IO) { collectFiles(root, extensions) }
        return indexFiles(files, parallelism, maxFileBytes, onProgress)
    }

    /** 直接索引一批已经切好的文本块。 */
    suspend fun indexChunks(chunks: List<Chunk>): IndexStats {
        val startedAt = System.nanoTime()
        return install(chunks, chunks.size, 0, startedAt)
    }

    /** 清空全部索引，释放向量与词表内存。 */
    suspend fun clear() {
        installMutex.withLock { snapshot = null }
    }

    private suspend fun install(
        chunks: List<Chunk>,
        fileCount: Int,
        skippedFiles: Int,
        startedAt: Long
    ): IndexStats = withContext(Dispatchers.Default) {
        require(chunks.size <= MAX_CHUNKS) {
            "chunk count ${chunks.size} exceeds MAX_CHUNKS=$MAX_CHUNKS; narrow the workspace scope"
        }

        val tokensPerChunk = ArrayList<List<String>>(chunks.size)
        for (chunk in chunks) tokensPerChunk.add(tokenizer.tokenize(chunk.text))

        val model = TfidfModel.fit(tokensPerChunk, tfidfParams)
        val vectors = ArrayList<SparseVector>(chunks.size)
        for (tokens in tokensPerChunk) vectors.add(model.encode(tokens))

        val frozenChunks: List<Chunk> = ArrayList(chunks)
        val stats = IndexStats(
            fileCount = fileCount,
            chunkCount = frozenChunks.size,
            vocabularySize = model.dimension,
            skippedFiles = skippedFiles,
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
        )
        installMutex.withLock {
            snapshot = IndexSnapshot(model, frozenChunks, vectors, stats)
        }
        stats
    }

    // ---------------------------------------------------------------------
    // 检索
    // ---------------------------------------------------------------------

    /**
     * Top-K 余弦检索。用「小顶堆」把全量排序 O(N log N) 压到 O(N log K)，
     * 全量打分在 Dispatchers.Default 上跑。
     */
    suspend fun search(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        minScore: Float = DEFAULT_MIN_SCORE
    ): List<SearchHit> = withContext(Dispatchers.Default) {
        val snap = snapshot ?: return@withContext emptyList()
        val queryVector = snap.model.encode(tokenizer.tokenize(query))
        if (queryVector.norm <= 0f) return@withContext emptyList()

        val k = topK.coerceAtLeast(1)
        val heap = PriorityQueue<SearchHit>(k + 1, SCORE_ASC)

        val cachedVectors = snap.vectors
        val cachedChunks = snap.chunks
        for (i in cachedVectors.indices) {
            val score = CosineSimilarity.of(queryVector, cachedVectors[i])
            if (score < minScore) continue
            if (heap.size < k) {
                heap.add(SearchHit(cachedChunks[i], score))
            } else {
                val top = heap.peek()
                if (top != null && top.score < score) {
                    heap.poll()
                    heap.add(SearchHit(cachedChunks[i], score))
                }
            }
        }

        val ranked = ArrayList<SearchHit>(heap.size)
        while (heap.isNotEmpty()) heap.poll()?.let { ranked.add(it) }
        ranked.reverse() // 小顶堆弹出为升序，反转即降序
        ranked
    }

    // ---------------------------------------------------------------------
    // 切块 / 读取 / 解码
    // ---------------------------------------------------------------------

    private fun chunkText(source: String, text: String, out: MutableList<Chunk>) {
        if (text.isEmpty()) return
        if (text.length <= chunkSize) {
            out.add(Chunk(id = "$source#0", source = source, text = text))
            return
        }
        var start = 0
        var index = 0
        while (start < text.length) {
            var end = if (start + chunkSize < text.length) start + chunkSize else text.length
            if (end < text.length) {
                // 优先在句末边界断句，避免把一句话拦腰砍断
                val from = if (end - BOUNDARY_SEARCH_WINDOW > start + 1) end - BOUNDARY_SEARCH_WINDOW else start + 1
                val boundary = lastSentenceBoundary(text, from, end)
                if (boundary > start) end = boundary
            }
            if (end <= start) end = if (start + chunkSize < text.length) start + chunkSize else text.length
            out.add(Chunk(id = "$source#$index", source = source, text = text.substring(start, end)))
            index++
            if (end >= text.length) break
            val next = end - chunkOverlap
            start = if (next <= start) start + 1 else next
        }
    }

    private fun lastSentenceBoundary(text: String, from: Int, to: Int): Int {
        var i = to - 1
        while (i >= from) {
            if (text[i] in SENTENCE_ENDINGS) return i + 1
            i--
        }
        return to
    }

    private fun collectFiles(root: File, extensions: Set<String>): List<File> {
        if (!root.exists()) return emptyList()
        val out = ArrayList<File>()
        if (root.isFile) {
            if (accepts(root, extensions)) out.add(root)
            return out
        }
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = try {
                dir.listFiles()
            } catch (_: Throwable) {
                null
            } ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (!child.isHidden && child.name != ".git" && child.name != "build") stack.addLast(child)
                } else if (accepts(child, extensions)) {
                    out.add(child)
                }
            }
        }
        return out
    }

    private fun accepts(file: File, extensions: Set<String>): Boolean {
        val name = file.name
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return extensions.contains(name.substring(dot + 1).lowercase())
    }

    private fun readTextOrNull(file: File, maxFileBytes: Long): String? {
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0L || length > maxFileBytes) return null
        val bytes = try {
            file.readBytes()
        } catch (_: Throwable) {
            return null
        }
        if (looksBinary(bytes)) return null
        return decodeText(bytes)
    }

    /** 前 8KB 内出现 NUL 字节即判定为二进制，直接跳过，防止把图片/可执行文件读成「文本」。 */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val limit = if (bytes.size < BINARY_PROBE_BYTES) bytes.size else BINARY_PROBE_BYTES
        for (i in 0 until limit) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    /**
     * 不假定编码：优先 BOM，其次严格 UTF-8，再退 GBK，最后有损 UTF-8。
     * 严格解码用 REPORT 策略，乱码宁可换编码也不吞错丢字。
     */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
        }
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        gbkCharset()?.let { cs -> strictDecode(bytes, cs)?.let { return it } }
        return String(bytes, Charsets.UTF_8)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    } catch (_: Throwable) {
        null
    }

    private fun gbkCharset(): Charset? = try {
        Charset.forName("GBK")
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 480
        const val DEFAULT_CHUNK_OVERLAP = 64
        const val DEFAULT_TOP_K = 10
        const val DEFAULT_MIN_SCORE = 0.05f
        const val DEFAULT_IO_PARALLELISM = 4
        const val DEFAULT_MAX_FILE_BYTES = 4L * 1024L * 1024L

        /** 索引规模硬上限：防止误把整个磁盘当工作区导致 OOM。 */
        const val MAX_CHUNKS = 20_000

        private const val BOUNDARY_SEARCH_WINDOW = 96
        private const val BINARY_PROBE_BYTES = 8 * 1024

        private val SENTENCE_ENDINGS = charArrayOf(
            '\n', '。', '！', '？', '；', '.', '!', '?', ';', '：', ':'
        )

        val DEFAULT_EXTENSIONS: Set<String> = setOf(
            "txt", "md", "markdown", "kt", "java", "json", "xml", "csv", "log", "properties", "yaml", "yml", "html"
        )

        private val SCORE_ASC: Comparator<SearchHit> = Comparator { a, b -> a.score.compareTo(b.score) }
    }
}

// -------------------------------------------------------------------------
// 数据模型
// -------------------------------------------------------------------------

/** 一个待索引的文本块。[source] 是来源标识（一般是文件绝对路径），[id] 全局唯一。 */
data class Chunk(val id: String, val source: String, val text: String)

/** 一条检索命中。 */
data class SearchHit(val chunk: Chunk, val score: Float)

/** 一次索引的统计结果，可直接用于 UI 展示或日志。 */
data class IndexStats(
    val fileCount: Int,
    val chunkCount: Int,
    val vocabularySize: Int,
    val skippedFiles: Int,
    val elapsedMillis: Long
)

/** TF-IDF 超参数。[minDocumentFrequency] 过滤稀有噪声词，[maxVocabularySize] 硬控词表规模。 */
data class TfidfParams(
    val minDocumentFrequency: Int = 1,
    val maxVocabularySize: Int = 20_000
)

// -------------------------------------------------------------------------
// 分词
// -------------------------------------------------------------------------

/**
 * 极简分词器：连续 CJK 串切 n-gram（默认 2-gram），拉丁串按词切，双向停用词过滤。
 * 补充平面汉字（surrogate pair）不做 2-gram，按整段保留，属已知取舍。
 */
class Tokenizer private constructor(
    private val ngram: Int,
    private val stopChars: Set<Char>,
    private val stopwords: Set<String>
) {

    fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>(text.length / 2 + 4)
        val latin = StringBuilder(16)
        var i = 0
        val length = text.length
        while (i < length) {
            val ch = text[i]
            if (isCjk(ch)) {
                if (latin.isNotEmpty()) flushLatin(latin, out)
                var j = i
                while (j < length && isCjk(text[j])) j++
                emitCjkRun(text, i, j, out)
                i = j
            } else if (isWordChar(ch)) {
                latin.append(ch)
                i++
            } else {
                if (latin.isNotEmpty()) flushLatin(latin, out)
                i++
            }
        }
        if (latin.isNotEmpty()) flushLatin(latin, out)
        return out
    }

    private fun emitCjkRun(text: String, from: Int, to: Int, out: MutableList<String>) {
        val runLength = to - from
        if (runLength <= 0) return
        if (runLength < ngram) {
            if (runLength == 1 && !stopChars.contains(text[from])) out.add(text.substring(from, to))
            return
        }
        var k = from
        while (k + ngram <= to) {
            val gram = text.substring(k, k + ngram)
            if (!isStopGram(gram)) out.add(gram)
            k++
        }
    }

    private fun isStopGram(gram: String): Boolean {
        for (c in gram) {
            if (!stopChars.contains(c)) return false
        }
        return true
    }

    private fun flushLatin(buffer: StringBuilder, out: MutableList<String>) {
        val raw = buffer.toString()
        buffer.setLength(0)
        val normalized = raw.lowercase()
        if (normalized.length < 2) {
            if (normalized.length == 1 && normalized[0].isDigit()) out.add(normalized)
            return
        }
        if (normalized in stopwords) return
        out.add(normalized)
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x4E00..0x9FFF) || (c in 0x3400..0x4DBF) || (c in 0xF900..0xFAFF)
    }

    private fun isWordChar(ch: Char): Boolean =
        (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_'

    companion object {
        const val DEFAULT_NGRAM = 2

        fun default(ngram: Int = DEFAULT_NGRAM): Tokenizer =
            Tokenizer(
                ngram = ngram.coerceAtLeast(1),
                stopChars = DEFAULT_CN_STOP_CHARS,
                stopwords = DEFAULT_EN_STOPWORDS
            )

        private val DEFAULT_CN_STOP_CHARS: Set<Char> =
            "的了和是在我有就不都一个上也很到说要去你会着看好这那与及等或而但又被把让对从为之其该则因由于中没他她它们什么".toSet()

        private val DEFAULT_EN_STOPWORDS: Set<String> = setOf(
            "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "was", "were",
            "be", "been", "for", "on", "with", "as", "at", "by", "it", "this", "that",
            "from", "not", "but", "if", "then", "than", "so", "we", "you", "they", "he", "she"
        )
    }
}

// -------------------------------------------------------------------------
// TF-IDF 模型
// -------------------------------------------------------------------------

/**
 * 词表 + IDF 权重。TF 用次线性 1+ln(tf)，IDF 用平滑 ln((N+1)/(df+1))+1，
 * 词表按 (df 降序, 词升序) 分配下标 —— 保证 fit 结果完全确定，便于测试与增量对比。
 */
class TfidfModel internal constructor(
    private val vocabulary: Map<String, Int>,
    private val idf: FloatArray
) {

    /** 词表维度，等于稠密向量长度。 */
    val dimension: Int get() = idf.size

    /** 稠密编码，返回长度 = [dimension] 的浮点数组（未命中词为 0）。 */
    fun vectorize(tokens: List<String>): FloatArray {
        val dense = FloatArray(dimension)
        if (dimension == 0) return dense
        val counts = HashMap<Int, Int>(16)
        for (token in tokens) {
            val index = vocabulary[token] ?: continue
            counts[index] = (counts[index] ?: 0) + 1
        }
        for ((index, count) in counts) {
            dense[index] = (1.0 + ln(count.toDouble())).toFloat() * idf[index]
        }
        return dense
    }

    /** 稀疏编码，检索路径用它 —— 内存占用与点积代价都只与命中词数相关。 */
    fun encode(tokens: List<String>): SparseVector {
        if (tokens.isEmpty() || vocabulary.isEmpty()) return SparseVector.EMPTY
        val counts = HashMap<Int, Int>(16)
        for (token in tokens) {
            val index = vocabulary[token] ?: continue
            counts[index] = (counts[index] ?: 0) + 1
        }
        if (counts.isEmpty()) return SparseVector.EMPTY
        val indices = counts.keys.toIntArray()
        indices.sort()
        val values = FloatArray(indices.size)
        for (slot in indices.indices) {
            val index = indices[slot]
            val count = counts[index] ?: 0
            values[slot] = (1.0 + ln(count.toDouble())).toFloat() * idf[index]
        }
        return SparseVector.of(indices, values)
    }

    companion object {
        /** 用一批已分词的文档训练词表与 IDF。空语料返回零维模型。 */
        fun fit(documents: List<List<String>>, params: TfidfParams): TfidfModel {
            val documentCount = documents.size
            if (documentCount == 0) return TfidfModel(emptyMap(), FloatArray(0))

            val documentFrequency = HashMap<String, Int>(1024)
            for (document in documents) {
                val seen = HashSet<String>(document.size)
                for (token in document) {
                    if (seen.add(token)) documentFrequency[token] = (documentFrequency[token] ?: 0) + 1
                }
            }

            var candidates = documentFrequency.entries.filter { it.value >= params.minDocumentFrequency }
            if (candidates.size > params.maxVocabularySize) {
                // 词表超限：保留全局出现最多的词，杜绝词表爆炸
                candidates = candidates.sortedByDescending { it.value }.take(params.maxVocabularySize)
            }

            val ordered = candidates.sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
            )

            val vocabulary = HashMap<String, Int>(ordered.size * 2)
            val idf = FloatArray(ordered.size)
            for (slot in ordered.indices) {
                val entry = ordered[slot]
                vocabulary[entry.key] = slot
                idf[slot] = (ln((documentCount + 1.0) / (entry.value + 1.0)) + 1.0).toFloat()
            }
            return TfidfModel(vocabulary, idf)
        }
    }
}

// -------------------------------------------------------------------------
// 向量与相似度
// -------------------------------------------------------------------------

/** 稀疏向量：`indices` 严格升序，`norm` 为 L2 范数（构建时一次算好，检索零重复开销）。 */
class SparseVector private constructor(
    val indices: IntArray,
    val values: FloatArray,
    val norm: Float
) {

    val nonZeroCount: Int get() = indices.size

    companion object {
        val EMPTY = SparseVector(IntArray(0), FloatArray(0), 0f)

        fun of(indices: IntArray, values: FloatArray): SparseVector {
            if (indices.size != values.size) {
                throw IllegalArgumentException("indices(${indices.size}) and values(${values.size}) length mismatch")
            }
            var acc = 0.0
            for (value in values) acc += value.toDouble() * value.toDouble()
            return SparseVector(indices, values, sqrt(acc).toFloat())
        }
    }
}

/** 余弦相似度：稀疏走双指针归并 O(nnz)，稠密走线性 O(min(len))。 */
object CosineSimilarity {

    fun of(a: SparseVector, b: SparseVector): Float {
        if (a.norm <= 0f || b.norm <= 0f) return 0f
        var i = 0
        var j = 0
        var dot = 0.0
        val ai = a.indices
        val bi = b.indices
        val av = a.values
        val bv = b.values
        while (i < ai.size && j < bi.size) {
            val x = ai[i]
            val y = bi[j]
            if (x == y) {
                dot += av[i].toDouble() * bv[j].toDouble()
                i++
                j++
            } else if (x < y) {
                i++
            } else {
                j++
            }
        }
        return (dot / (a.norm.toDouble() * b.norm.toDouble())).toFloat()
    }

    fun of(a: FloatArray, b: FloatArray): Float {
        val length = if (a.size < b.size) a.size else b.size
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until length) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }
}