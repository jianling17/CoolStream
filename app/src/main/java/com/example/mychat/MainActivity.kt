package com.example.mychat

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.mychat.ui.theme.MyChatTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import android.content.Context
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Dao
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import java.util.concurrent.TimeUnit

// ---- 抽屉式布局新增的 import ----
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import rikka.shizuku.Shizuku

// ---- Markdown 渲染新增的 import ----
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.example.mychat.ui.theme.CodeInlineBackground
import com.example.mychat.ui.theme.CodeBlockBackground
import com.example.mychat.ui.theme.CodeBlockBorder
import com.example.mychat.ui.theme.CodeBlockText
import com.example.mychat.ui.theme.GlassDrawerDark
import com.example.mychat.ui.theme.GlassDrawerLight
import com.example.mychat.ui.theme.GlassEdgeDark
import com.example.mychat.ui.theme.GlassEdgeLight
import com.example.mychat.ui.theme.GlassFillDark
import com.example.mychat.ui.theme.GlassFillLight
import com.example.mychat.ui.theme.GlassHighlight
import com.example.mychat.ui.theme.GlassTintDark
import com.example.mychat.ui.theme.GlassTintLight

// ---- 像素级 UI 重构新增的 import ----
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import java.util.Calendar
import java.util.Locale


// ============================================================
// ★★★ 配置区 ★★★
// ============================================================

/** DeepSeek 接口地址。必须写全，包含 /chat/completions */
private const val API_URL = "https://api.deepseek.com/chat/completions"

/** 模型名。当前在售：deepseek-flash / deepseek-v4-pro */
private const val MODEL_NAME = "deepseek-flash"

/**
 * 系统提示词。
 *
 * ⚠️ 这里必须【明确告诉模型它有工具可用】。
 *
 * 实测：即使请求体里带了 tools 数组，只要提示词里不提"你可以用工具"，
 * 模型就会直接回答「我没有权限读取文件」，一个 tool_call 都不会发。
 * 这是"授权了还是说没权限"的真正原因 —— 不是权限问题，是模型不知道自己能动。
 */
val SYSTEM_PROMPT = """
你是 CoolStream，运行在用户安卓手机上的本地助手。

你可以调用以下工具：
- list_dir(path)：列出某个目录下的文件和文件夹名称。
- search_files(keyword, path)：在某个目录下递归搜索文件名包含 keyword 的文件，返回绝对路径（最多 20 条）。
- read_file(path)：读取文本文件的纯文本内容。
- run_shell(command)：通过 Shizuku 以 shell 权限执行命令，返回输出。需要用户先在 App 里授权 Shizuku。

严格遵守以下规则：
1. 用户要求查看、读取、打开某个文件时，必须先调用 read_file，
   绝对不要说"我没有权限""我无法访问你手机上的文件"之类的话。
2. 如果用户只说了文件名、不知道完整路径，
   先调用 search_files 按关键词搜索；如果连大概在哪个目录都不知道，
   先用 list_dir("/sdcard") 看看有哪些目录，再逐层缩小范围。
3. 从用户话语中提取路径时，只保留绝对路径本身，
   去掉所有中文标点：中文双引号、中文单引号、中文逗号、中文句号、空格都不能带进路径。
   例如用户说"看看“/sdcard/Download/a.txt”"时，路径应该是 /sdcard/Download/a.txt。
4. 绝对不要凭空编造路径。搜索和列目录都找不到时，如实告诉用户没找到。
5. read_file 只能读文本文件。遇到 pptx / docx / xlsx / zip / 图片 / 音视频等
   二进制文件会返回失败，这时如实告诉用户"这是二进制文件，无法直接读取内容"，
   不要假装读到了内容，也不要猜里面写了什么。
6. 工具返回结果后，基于结果回答；失败就把失败原因如实转述。
7. 回答简洁、分点，不要长篇大论。
8. run_shell 权限很高，要严格把关：
   - 只有用户明确要求"执行命令 / 跑一下 shell / 看看进程列表"时才用；
   - 单纯读写文件请用 read_file / list_dir / search_files，不要用 run_shell 绕；
   - 破坏性命令（rm、mv、格式化、pm uninstall 等）一律先向用户确认，不要直接执行。
9. 系统对 run_shell 装了黑名单：rm、pm uninstall、reboot、mkfs、dd 写块设备、
   改系统设置等会被直接拦截。一旦收到「安全拦截」，不要换一种写法重试，
   直接告诉用户这个操作被系统禁止了。
""".trim()

/**
 * 实际发给模型的系统提示词 = 静态规则 + 当前工作区约束。
 *
 * 工作区是**运行时**才确定的（用户在设置页里选的），没法写死在 SYSTEM_PROMPT 里，
 * 所以每次请求前拼上去。
 */
private fun systemPromptWithWorkspace(): String {
    val ws = currentWorkspace
    return if (ws.isBlank()) {
        SYSTEM_PROMPT + "\n\n10. 用户还没有设置工作区，暂时不限制路径范围。"
    } else {
        SYSTEM_PROMPT +
            "\n\n10. 你的工作区是：$ws" +
            "\n    所有工具操作（读文件、列目录、搜索、shell）都必须严格限制在这个目录下，严禁越界。" +
            "\n    越界的操作会被代码层直接拦截并返回「工作区越界」，不要换路径反复重试。"
    }
}

/** 日志标签，Logcat 里用 tag:MyChatPerf 过滤 */
private const val TAG = "MyChatPerf"

/**
 * ★★★ 核心参数：UI 刷新间隔（毫秒）★★★
 *
 * AI 每秒可能吐 30~60 个分片。每个分片都刷界面的话，CPU 会被
 * "重新排版 + 重绘"占满，手机发烫。
 *
 * 50ms = 每秒最多刷 20 次。人眼在 60~100ms 内感觉不出卡，
 * 这是"看起来流畅"和"省电"之间的甜点。
 *
 * 想验证批处理有没有用：改成 0L 再跑一次，对比日志里的 flush 次数。
 */
private const val FLUSH_INTERVAL_MS = 50L

/**
 * ★ 多轮上下文：最多携带多少条历史消息
 *
 * 每次请求都会把之前的对话一起发过去，AI 才能"记得"上文。
 * 但对话太长会超出模型的上下文窗口（而且更贵），所以要设个上限。
 * 40 条 ≈ 20 轮问答。
 *
 * 想体验"AI 忘记上文"是什么感觉，把它改成 1 试试。
 */
private const val MAX_HISTORY_MESSAGES = 40


// ============================================================
// 全局唯一的 OkHttp 客户端
//
// 每个 OkHttpClient 自带连接池 + 线程池，反复新建 = 反复创建销毁线程，
// 本身就是发热来源。整个 App 共用一个就够。
// ============================================================
private val httpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()


// ============================================================
// 0. 存储权限（Agent 的 read_file 工具需要）
//
// MANAGE_EXTERNAL_STORAGE 不是普通的运行时权限：
// 在 Manifest 里声明只是「有资格申请」，真正的开关在系统设置里，
// 必须跳到 Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 让用户手动打开。
// 所以在 App 启动时要检查一次，没开就弹窗引导。
// ============================================================
private fun hasAllFilesAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        // Android 10 及以下走老的存储模型，这里不处理
        true
    }

private fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    // 优先跳到「本应用」的授权页
    val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    try {
        context.startActivity(direct)
    } catch (e: Exception) {
        // 部分机型不认带 package 的那个 action，退回全局列表
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}


// ============================================================
// 0.1 Shizuku（提权执行 shell）
//
// 工作方式：
//   1. 用户在手机装 Shizuku App，并用「无线调试 / USB 调试」启动它
//   2. 我们的 App 通过 ShizukuProvider（Manifest 里声明）和它通信
//   3. 用户点一次「授权」，之后就能以 shell 权限执行命令
//
// ⚠️ 三个状态要分清，不然提示会误导人：
//   · Shizuku App 没装 / 服务没启动  → pingBinder() 返回 false
//   · 服务在跑但没给我们授权        → checkSelfPermission() != GRANTED
//   · 都 OK                        → newProcess() 才能用
// ============================================================
private const val SHIZUKU_REQUEST_CODE = 1001

/** Shizuku 服务是否在跑（App 装没装、有没有启动） */
private fun isShizukuRunning(): Boolean = try {
    Shizuku.pingBinder()
} catch (e: Exception) {
    false
}

/** 是否已经拿到 Shizuku 授权 */
private fun isShizukuGranted(): Boolean = try {
    Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
} catch (e: Exception) {
    false
}

/** 弹出 Shizuku 的授权对话框。必须先 pingBinder 成功，否则会抛异常。 */
private fun requestShizukuPermission() {
    try {
        if (!Shizuku.pingBinder()) {
            Log.e(TAG, "Shizuku 服务未运行，无法申请授权")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Shizuku 已授权，无需重复申请")
            return
        }
        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    } catch (e: Exception) {
        Log.e(TAG, "requestShizukuPermission 失败", e)
    }
}


// ============================================================
// 1. 数据模型
// ============================================================

/**
 * 会话表。
 *
 * 一条记录 = 侧边栏里的一行 = 一个独立对话。
 * createdAt 用来排序：数值越大越新，侧边栏就按它倒序排。
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val id: String,

    val title: String,

    // 创建时间（毫秒），排序用
    val createdAt: Long = System.currentTimeMillis()
)


/**
 * 消息表。
 *
 * ★ 新增 sessionId：这条消息属于哪个会话。
 *
 * foreignKeys 是"数据库层面强制关联"：
 *   parentColumns = ["id"]         → 指向 sessions 表的 id
 *   childColumns  = ["sessionId"]  → 本表的 sessionId
 *   onDelete = CASCADE             → 删掉会话时，它下面的消息自动一起删
 *
 * 有外键的列必须建索引（indices），否则 Room 编译期会报警告，
 * 而且每次删会话都要全表扫描。
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ChatMessage(
    // 主键。autoGenerate = true 表示由数据库自动分配，从 1 开始递增。
    // 默认值 0 是 Room 的硬性要求：插入新行时传 0，代表
    // "我还没有 id，你帮我生成一个"。
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    // ★ 属于哪个会话。必须有值，否则这条消息挂不到任何会话下面。
    val sessionId: String,

    val content: String,

    val isUser: Boolean,

    // 是不是"错误提示"气泡。
    // 错误提示只是为了让人在界面上看得见，它并不是 AI 的真实回复，
    // 所以下次发消息时要把它排除掉，否则会污染上下文。
    // 必须给默认值 false：非空列没有默认值时，以后做数据库版本升级会报错。
    val isError: Boolean = false,

    // 消息时间戳（毫秒），用来按时间正序排列。
    // 默认取当前时间，新建消息时不用手动传。
    val timestamp: Long = System.currentTimeMillis(),

    // ★ 消息类型。
    //   USER / AI_TEXT 走原来的气泡渲染；
    //   TOOL_CALL 不走气泡，改渲染成 ToolCallCard，content 里放工具调用 JSON。
    val type: MessageType = MessageType.AI_TEXT
)


// ---------- 消息类型 ----------
enum class MessageType { USER, AI_TEXT, TOOL_CALL }

/**
 * Room 不认识 Kotlin 枚举，必须告诉它怎么在「枚举 ⇄ 字符串」之间转换。
 *
 * 存 name（"USER" / "AI_TEXT" / "TOOL_CALL"）而不是序号（0/1/2）：
 * 以后往枚举中间插入新值时，老数据的含义不会错位。
 */
class MessageTypeConverter {
    @TypeConverter
    fun fromMessageType(value: MessageType): String = value.name

    @TypeConverter
    fun toMessageType(value: String): MessageType =
        runCatching { MessageType.valueOf(value) }.getOrDefault(MessageType.AI_TEXT)
}


// ============================================================
// 1.1 数据访问接口（DAO）
//
// DAO = Data Access Object。你只写接口方法名和 SQL 注解，
// Room 在编译期自动生成实现类，不需要自己写一行 SQL 执行代码。
// 所有 suspend 方法都必须在协程里调用，不要直接在主线程调。
// ============================================================
@Dao
interface ChatDao {

    // ==================== 会话 ====================

    /** 读出所有会话，按创建时间倒序（新的在上面，和侧边栏顺序一致） */
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    suspend fun getAllSessions(): List<SessionEntity>

    /** 插入一个会话。REPLACE：同 id 就覆盖，重复调用不会崩 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    /** 只改标题。第一句话发出去时，用它把"新对话"换成真实标题 */
    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun updateSessionTitle(id: String, title: String)

    /**
     * 删掉一个会话。
     * messages 表设了 onDelete = CASCADE，
     * 所以这个会话下面的消息会被数据库自动删掉，不用手动清。
     */
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)


    // ==================== 消息 ====================

    /** 读某个会话的全部消息，按时间正序（老的在上面）。id 作第二排序键 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessages(sessionId: String): List<ChatMessage>

    /**
     * 插入一条消息，返回数据库分配的行号（Long）。
     * onConflict = REPLACE 的意思是：如果主键冲突就覆盖。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessage): Long

    /** 更新整条消息（按主键匹配） */
    @Update
    suspend fun update(message: ChatMessage)

    /** 只更新某一条的正文和错误标记，流结束时用这个，比 update 整条更省 */
    @Query("UPDATE messages SET content = :content, isError = :isError WHERE id = :id")
    suspend fun updateContent(id: Int, content: String, isError: Boolean)

    /** 清空某个会话的全部消息（会话本身保留） */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)
}


// ============================================================
// 1.2 数据库本体（单例）
//
// 整个 App 只能有一个数据库实例，否则并发写入会出问题。
// companion object 里的 INSTANCE 就是用来保证这一点的。
// ============================================================
/**
 * 迁移 2 → 3：messages 表新增 type 列（消息类型）。
 *
 * 用正经迁移而不是删表：
 * 加一列 + 给默认值，再把老数据里 isUser = 1 的行标成 USER。
 * 这样之前的聊天记录一条都不会丢。
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN type TEXT NOT NULL DEFAULT 'AI_TEXT'")
        db.execSQL("UPDATE messages SET type = 'USER' WHERE isUser = 1")
    }
}


@Database(
    entities = [SessionEntity::class, ChatMessage::class],
    version = 3,               // ★ 1→2：加 sessions 表；2→3：加 messages.type 列
    exportSchema = false
)
@TypeConverters(MessageTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "chat.db"
                )
                    // ★ 允许"删表重建"。
                    //
                    // 老库里的 messages 表没有 sessionId 列，要正经迁移就得
                    // 建临时表 → 拷数据 → 删旧表 → 改名，而旧数据本身也挂不到
                    // 任何会话下面（没有 sessionId 就不知道它属于哪个对话），
                    // 所以直接丢掉重建最省事。
                    //
                    // 代价：升级后第一次启动，旧聊天记录会消失（只发生这一次）。
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}


// ============================================================
// 2. 网络层：调用 DeepSeek 流式 API
//
// ★ 多轮上下文：参数从"一句话"改成"整个对话历史"。
//   每次请求都把之前的 user / assistant 消息按顺序一起发过去，
//   模型才知道上一轮聊了什么。
//
// ★ 50ms 批处理在这里（IO 线程上）完成：只有真正要刷界面时才切主线程。
//
// 参数说明：
//   history       —— 对话历史（已按时间顺序排好，最后一条是用户刚发的）
//   onFlush(text) —— 每满 50ms（以及流结束时）回调一次，text 是"到目前为止的全部文本"
//   onDone(total) —— 流正常结束
//   onError(msg)  —— 出错
//
// ⚠️⚠️ 注意：这个函数现在【不再被界面调用】。
//   界面已经改用 runAgentLoop（带 tools / tool_calls / 二次请求）。
//   保留它有两个用处：
//     ① 想跑"纯聊天、不带工具"的退化路径时可以直接换回来
//     ② 作为最干净的 50ms 批处理参考实现
//   所以你要是往这里加日志想看请求体，是看不到的 —— 请看 runAgentLoop。
// ============================================================
fun sendToDeepSeek(
    history: List<ChatMessage>,
    scope: CoroutineScope,
    onFlush: (String) -> Unit,
    onDone: (totalChars: Int) -> Unit,
    onError: (String) -> Unit
) {
    scope.launch(Dispatchers.IO) {
        try {
            // ---------- 1. 检查 API Key ----------
            val apiKey = BuildConfig.DEEPSEEK_API_KEY
            Log.d(TAG, "stream start: key length=${apiKey.length}, history=${history.size} messages")

            if (apiKey.isBlank()) {
                withContext(Dispatchers.Main) {
                    onError("API Key 是空的，请检查 local.properties 里的 DEEPSEEK_API_KEY")
                }
                return@launch
            }

            // ---------- 2. ★★★ 把整个对话历史转成 API 格式 ★★★ ----------
            val messagesArray = JSONArray()

            // ① 系统提示词放最前面
            if (SYSTEM_PROMPT.isNotEmpty()) {
                messagesArray.put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
            }

            // ② 历史消息按时间顺序排进去
            //    isUser = true  → role = "user"      （我说的话）
            //    isUser = false → role = "assistant" （AI 说的话）
            var skippedError = 0
            for (msg in history) {

                // 空消息不发（比如流被中断时留下的空气泡）
                if (msg.content.isBlank()) continue

                // 错误提示不是 AI 的真实回复，不能当上下文发过去
                if (msg.isError) {
                    skippedError++
                    continue
                }

                messagesArray.put(JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "assistant")
                    put("content", msg.content)
                })
            }

            Log.d(TAG, "history packed: ${messagesArray.length()} messages (skipped $skippedError error bubbles)")

            val bodyJson = JSONObject().apply {
                put("model", MODEL_NAME)
                put("stream", true)
                put("messages", messagesArray)

                // ★★★ 关掉思考模式 ★★★
                // deepseek-flash 的思考模式【默认开启】。开启后它会先吐一长串
                // 思维链，而思维链分片里 content 字段是 JSON null
                //（思维内容在 reasoning_content 字段里）。
                // 那些 content=null 的分片，正是"满屏 nullnullnull"的源头之一。
                // 关掉后：响应更快，且不会再有这种空分片。
                put("thinking", JSONObject().put("type", "disabled"))
            }.toString()

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            // ---------- 3. 发出去，逐行读 ----------
            val response = httpClient.newCall(request).execute()
            Log.d(TAG, "response code=${response.code}")

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "HTTP error body: ${errBody.take(300)}")
                withContext(Dispatchers.Main) { onError("HTTP ${response.code}") }
                return@launch
            }

            val bodyStream = response.body
            if (bodyStream == null) {
                withContext(Dispatchers.Main) { onError("服务器返回了空内容") }
                return@launch
            }

            val reader = BufferedReader(InputStreamReader(bodyStream.byteStream()))

            // ---------- 4. ★★★ 50ms 批处理的核心 ★★★ ----------
            // StringBuilder：可变字符串缓冲区。
            // 不用 String 拼接是因为 Kotlin 的 String 不可变，每次 a + b 都要
            // 复制全部内容，攒到 1000 字时每个分片都要复制 1000 字，复杂度 O(n²)。
            val buffer = StringBuilder()
            var lastFlushAt = 0L      // 上次刷界面的时刻
            var flushCount = 0        // 实际刷了多少次
            var deltaCount = 0        // 收到多少个有效分片（= 不批处理时要刷多少次）
            var nullContentCount = 0  // 被过滤掉的"content 不是字符串"分片数
            var total = 0

            while (true) {
                // readLine() 返回 null 表示流结束
                val line = reader.readLine() ?: break

                // SSE 格式：有意义的行都以 "data:" 开头，其余（空行、注释、心跳）跳过
                if (!line.startsWith("data:")) continue

                val data = line.substring(5).trim()
                if (data.isEmpty()) continue

                // 服务端说"说完了"
                if (data == "[DONE]") break

                // 从这一行 JSON 里抠出文字。抠不出来（content 是 null 等）就跳过
                val delta = parseDelta(data)
                if (delta == null) {
                    // ★ 诊断日志：只打印前 3 条，避免刷屏。
                    //   如果这里数量很多，说明服务端一直在发 content=null 的分片，
                    //   把打印出来的原始内容发我，就能确认原因。
                    nullContentCount++
                    if (nullContentCount <= 3) {
                        Log.d(TAG, "skip chunk #$nullContentCount (content is not a String): ${data.take(200)}")
                    }
                    continue
                }

                deltaCount++
                total += delta.length

                // ① 无条件先把分片攒起来（纯内存操作，几乎零成本）
                buffer.append(delta)

                // ② 看一眼表，距上次刷新够 50ms 了吗？
                val now = SystemClock.elapsedRealtime()
                if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {

                    // 第一次时 lastFlushAt 还是 0，直接相减会得到"开机至今毫秒数"这种假值，
                    // 所以第一次统一记成 0
                    val interval = if (flushCount == 0) 0L else now - lastFlushAt

                    val text = buffer.toString()

                    // 【日志】每次批处理刷新
                    Log.d(TAG, "flush: ${text.length} chars, interval=${interval}ms")

                    lastFlushAt = now
                    flushCount++

                    // ★ 只在真正要刷界面时才切回主线程
                    withContext(Dispatchers.Main) { onFlush(text) }
                }
                // 不够 50ms → 什么都不做，继续攒。这就是"批处理"。
            }

            reader.close()

            // ---------- 5. 尾部补偿 ----------
            // 流结束时，缓冲区里可能还留着不足 50ms 的那几个字。
            // 不补这一次，最后几个字永远不会出现在屏幕上。
            val finalText = buffer.toString()
            withContext(Dispatchers.Main) { onFlush(finalText) }

            // 【日志】流结束
            Log.d(
                TAG,
                "stream done: total=$total chars " +
                    "(deltas=$deltaCount, flushes=$flushCount, skipped=$nullContentCount)"
            )

            withContext(Dispatchers.Main) { onDone(total) }

        } catch (e: Exception) {
            Log.e(TAG, "stream exception: ${e.message}", e)
            withContext(Dispatchers.Main) { onError(e.message ?: "unknown") }
        }
    }
}


// ============================================================
// 3. ★★★ 解析一行 SSE 数据，抠出文字 ★★★
//
// 服务端发来的一行长这样：
//   data: {"choices":[{"delta":{"content":"你好"},"index":0}]}
// 我们只要 delta.content 里的 "你好"。
//
// ⚠️⚠️ 这里有本项目最容易踩的坑 ⚠️⚠️
//
// 千万不要写成 .optString("content", "")！
//
// 原因：org.json 里有个反直觉的设计。当 JSON 的值是 null 时：
//   JSONObject.optString(name, fallback) 内部会走 JSON.toString(value)，
//   而 JSONObject.NULL 这个单例对象不是 Java 的 null，
//   于是被 String.valueOf() 转成了字符串 "null"，
//   最后返回的是 "null" 而不是你给的 fallback ""。
//
// 结果就是：服务端每发一个 content=null 的分片，屏幕上就多 4 个字母 "null"。
// 思考模式下这种分片成百上千个，于是满屏 nullnullnull...
//
// 正解：用 opt() 取出【原始对象】，再用 `is String` 判断类型。
//       JSON null 取出来是 JSONObject.NULL，不是 String，自然被挡掉。
//
// 另外再加一道保险：即使拿到了字符串，也排除空串和字面量 "null"。
// ============================================================
private fun parseDelta(json: String): String? {
    return try {
        val root = JSONObject(json)

        val choices = root.optJSONArray("choices") ?: return null
        val firstChoice = choices.optJSONObject(0) ?: return null
        val delta = firstChoice.optJSONObject("delta") ?: return null

        // ★ 关键：opt() 而不是 optString()
        val content = delta.opt("content")

        // ① 必须是 String 类型（这样 JSON null 就被挡掉了）
        // ② 不能是空串
        // ③ 不能是字面量 "null"（双重保险）
        (content as? String)?.takeIf { it.isNotEmpty() && it != "null" }

    } catch (e: Exception) {
        // 有些服务会插入心跳包或非标准行，忽略即可
        null
    }
}


// ============================================================
// 3.1 解析一行的「正文 + 工具调用分片」
//
// parseDelta 只抠 content，够用于纯文本回复。
// 但工具调用时服务端还会带一个 tool_calls 数组，而且是【分片到达】的：
//
//   第 1 片: {"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"read_file","arguments":""}}]}
//   第 2 片: {"tool_calls":[{"index":0,"function":{"arguments":"{\"pa"}}]}
//   第 3 片: {"tool_calls":[{"index":0,"function":{"arguments":"th\":\"/sdcard/a.txt\"}"}}]}
//
// 所以必须按 index 累积 id / name / arguments。
// 直接取最后一次的 arguments 只会拿到一小段残缺 JSON。
// ============================================================
private data class ToolCallPiece(
    val index: Int,
    val id: String?,
    val name: String?,
    val arguments: String?
)

private data class DeltaInfo(
    val content: String?,               // 正文分片（可能为空）
    val toolCalls: List<ToolCallPiece>  // 本次分片里的工具调用片段
)

private fun parseDeltaFull(json: String): DeltaInfo? {
    return try {
        val delta = JSONObject(json)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("delta")
            ?: return null

        // 正文沿用 parseDelta 那套「opt + is String」的写法，避免 JSON null 变成 "null"
        val content = (delta.opt("content") as? String)
            ?.takeIf { it.isNotEmpty() && it != "null" }

        val pieces = ArrayList<ToolCallPiece>()
        val arr = delta.optJSONArray("tool_calls")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val fn = o.optJSONObject("function")
                pieces.add(
                    ToolCallPiece(
                        index = o.optInt("index", 0),
                        id = (o.opt("id") as? String)?.takeIf { it.isNotEmpty() },
                        name = (fn?.opt("name") as? String)?.takeIf { it.isNotEmpty() },
                        arguments = (fn?.opt("arguments") as? String)?.takeIf { it.isNotEmpty() }
                    )
                )
            }
        }
        DeltaInfo(content, pieces)
    } catch (e: Exception) {
        // 心跳包 / 非标准行，忽略
        null
    }
}


// ============================================================
// 3.2 工具清单（发给模型）
//
// ⚠️ 没有这一段，模型根本不知道有工具可用，
//    只会回「我没有权限读取文件」——这正是之前的现象。
// ============================================================
private val TOOL_DEFINITIONS = """
[
  {
    "type": "function",
    "function": {
      "name": "list_dir",
      "description": "列出指定目录下的所有文件和文件夹名称。用户不知道文件在哪时，先用它看看某个目录里有什么。",
      "parameters": {
        "type": "object",
        "properties": {
          "path": {
            "type": "string",
            "description": "目录的绝对路径，例如 /sdcard 或 /sdcard/Download"
          }
        },
        "required": ["path"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "search_files",
      "description": "在指定目录下递归搜索文件名包含关键词的文件，返回匹配文件的绝对路径。用户不知道文件在哪时用这个。",
      "parameters": {
        "type": "object",
        "properties": {
          "keyword": {
            "type": "string",
            "description": "要匹配的文件名关键词，不区分大小写，例如 p5r 或 report"
          },
          "path": {
            "type": "string",
            "description": "从哪个目录开始递归搜索，必须是绝对路径，例如 /sdcard"
          }
        },
        "required": ["keyword", "path"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "read_file",
      "description": "读取文本文件的纯文本内容。只能读文本文件；pptx / docx / zip / 图片等二进制文件会失败。",
      "parameters": {
        "type": "object",
        "properties": {
          "path": {
            "type": "string",
            "description": "文件的绝对路径，例如 /sdcard/Download/notes.txt"
          }
        },
        "required": ["path"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "run_shell",
      "description": "通过 Shizuku 以 shell 权限执行一条命令，返回标准输出和错误输出。权限很高，只在用户明确要求执行命令时使用。",
      "parameters": {
        "type": "object",
        "properties": {
          "command": {
            "type": "string",
            "description": "要执行的 shell 命令，例如 ls -la /sdcard 或 pm list packages -3"
          }
        },
        "required": ["command"]
      }
    }
  }
]
"""

/** 一次工具执行的结果 */
private data class ToolExecResult(val ok: Boolean, val output: String)

/**
 * 最多几轮工具调用（防止模型无限调用把自己转晕）。
 *
 * 「搜索 → 读取 → 回答」至少就要 3 轮，所以上限抬到 5，给更长的链路留余量。
 */
private const val MAX_TOOL_ROUNDS = 5

/**
 * 单个文件最大读取字节数：5 MB。
 *
 * 抬到 5MB 是为了能读长文本 / 日志 / 代码。
 * 仍然保留上限，是因为一次性 readBytes() 太大容易 OOM ——
 * 超过就明确告诉模型"文件太大"，比让它把 App 搞崩要好。
 */
private const val MAX_FILE_BYTES = 5_242_880L

/** 单次 search_files 最多返回多少条匹配 */
private const val MAX_SEARCH_HITS = 20

/** 单次 search_files 最多访问多少个文件（防止在 /sdcard 里扫到天荒地老） */
private const val MAX_SEARCH_VISITS = 50_000

/** 递归搜索的最大目录深度 */
private const val MAX_SEARCH_DEPTH = 12

/** 单次 list_dir 最多返回多少条 */
private const val MAX_LIST_ENTRIES = 300

/** 工具执行的独立日志标签。Logcat 里过滤 tag:ToolExec 就能只看工具相关的行 */
private const val TOOL_LOG_TAG = "ToolExec"

/**
 * 在本机真正执行工具。
 *
 * 成功就把内容存下来，失败就把 Exception 信息存下来 —— 两种情况都要回传给模型，
 * 这样模型才知道到底发生了什么，而不是继续瞎猜。
 *
 * 每一步都有日志（tag = ToolExec），出问题直接看 Logcat 就知道卡在哪里：
 *   - 路径存不存在
 *   - 是不是文件
 *   - 有没有读权限
 *   - 文件多大
 *   - 具体抛了什么异常
 *
 * 以后加新工具，就在这里加一个 when 分支 + 在 TOOL_DEFINITIONS 里加一段 schema。
 */
/** 从参数 JSON 里安全取一个字符串（模型偶尔会传不合法的 JSON） */
private fun argOf(argsJson: String, key: String): String {
    return try {
        (JSONObject(argsJson).opt(key) as? String) ?: ""
    } catch (e: Exception) {
        Log.e(TOOL_LOG_TAG, "参数解析失败（key=$key）: ${e.message}", e)
        ""
    }
}

/**
 * 把模型给的路径清洗干净。
 *
 * 实测：用户输入里常带中文双引号 “ ”，模型会把它一起塞进 path。
 * 这里再兜一层 —— 把首尾的中英文标点和空白全部削掉。
 */
private fun cleanPath(raw: String): String {
    return raw.trim().trim(
        '"', '\'', '“', '”', '‘', '’',
        '，', ',', '。', '、', '；', ';', '：', ':', ' ', '\n', '\t', '\r'
    )
}

// ============================================================
// 3.25 工作区（Workspace）
//
// 用一个目录把 Agent 圈起来：文件操作只能在这个目录内。
//
// ⚠️ 这是「防手滑」，不是「安全边界」：
//    · run_shell 是 shell 权限，一条 `cat /data/xxx` 就出去了，代码层拦不住
//    · 真正的隔离要靠操作系统级沙箱
//    但 read_file / search_files / list_dir 这三个走 java.io.File 的工具，可以硬拦。
// ============================================================
private const val PREFS_NAME = "coolstream_prefs"
private const val KEY_WORKSPACE = "workspace_path"

/** 当前工作区绝对路径。空串 = 未设置，不做限制。 */
@Volatile
private var currentWorkspace: String = ""

private fun loadWorkspace(context: Context): String {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_WORKSPACE, "")
        ?: ""
}

private fun saveWorkspace(context: Context, path: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_WORKSPACE, path)
        .apply()
    currentWorkspace = path
}

/**
 * 把 content:// 的 tree Uri 转成绝对路径。
 *
 * 例：content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWS
 *     → documentId = "primary:Documents/WS"
 *     → /storage/emulated/0/Documents/WS
 *
 * 外置 SD 卡的 volume 形如 "1A2B-3C4D"，对应 /storage/1A2B-3C4D/...
 */
private fun treeUriToPath(uri: Uri): String? {
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
 * 检查某个路径是否在工作区内。
 * @return null = 放行；非 null = 越界说明（直接作为工具结果回传给模型）
 */
private fun workspaceViolation(path: String): String? {
    val ws = currentWorkspace
    if (ws.isBlank()) return null          // 没设工作区 → 不限制

    val target = File(path).absolutePath
    val root = File(ws).absolutePath.trimEnd('/')

    // ⚠️ 不能直接写 target.startsWith(root)：
    //    "/storage/emulated/0/DocumentsX" 也以 "/storage/emulated/0/Documents" 开头，
    //    这是路径前缀判断的经典误判。必须比「root + 分隔符」。
    val inside = target == root || target.startsWith("$root/")

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
    Log.d(
        TOOL_LOG_TAG,
        "read_file path=$path, exists=${file.exists()}, isFile=${file.isFile}, " +
            "canRead=${file.canRead()}, length=${file.length()}, " +
            "allFilesAccess=${Environment.isExternalStorageManager()}"
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
                Log.d(TOOL_LOG_TAG, "二进制文件，不按文本解析: $path")
                ToolExecResult(
                    false,
                    "这是二进制文件（pptx / docx / xlsx / zip / 图片 / 音视频之类），" +
                        "无法作为纯文本解析。文件大小 ${bytes.size} 字节。" +
                        "请如实告诉用户无法直接读取内容，不要猜测里面写了什么。"
                )
            } else {
                val text = String(bytes, Charsets.UTF_8)
                Log.d(TOOL_LOG_TAG, "读取成功: ${text.length} 字符")
                ToolExecResult(true, text)
            }
        }
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
private fun searchFiles(keyword: String, rootPath: String): ToolExecResult {
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
private fun bindShizukuUserService(context: Context) {
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
private fun runShell(command: String): ToolExecResult {
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

private fun executeTool(name: String, argsJson: String): ToolExecResult {
    Log.d(TOOL_LOG_TAG, "开始执行工具: name=$name, args=$argsJson")

    return try {
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

            "run_shell" -> {
                val command = argOf(argsJson, "command").trim()
                if (command.isBlank()) {
                    ToolExecResult(false, "参数错误：缺少 command（要执行的 shell 命令）")
                } else {
                    runShell(command)
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
}


// ============================================================
// 3.25 Token 用量与费用估算
//
// 单价来源：DeepSeek 官方文档（2026-08-17 起的峰谷定价）
//   https://api-docs.deepseek.com/zh-cn/quick_start/pricing
//   deepseek-flash = DeepSeek-V4.1-Flash-1017
//
//   元 / 百万 tokens          空闲时段   高峰时段
//   输入（缓存命中）            0.02      0.04
//   输入（缓存未命中）          1         2
//   输出                        4         8
//
//   高峰 = 北京时间 周一至周五（不含法定节假日）09:00-12:00、14:00-18:00
//
// ⚠️ 这是「估算」，不是账单：
//    · 法定节假日无法在客户端判断（需要一张节假日表），那几天会按高峰价算，偏高
//    · 官方随时可能调价，单价写死在代码里就有过期风险
// ============================================================
private const val CONTEXT_WINDOW_TOKENS = 1_000_000   // deepseek-flash 上下文长度 1M

/** 一轮对话累计的 token 用量（多轮工具调用会逐轮累加） */
data class UsageStats(
    val promptTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val completionTokens: Int = 0
) {
    val cacheMissTokens: Int get() = (promptTokens - cacheHitTokens).coerceAtLeast(0)
    val totalTokens: Int get() = promptTokens + completionTokens

    operator fun plus(other: UsageStats) = UsageStats(
        promptTokens + other.promptTokens,
        cacheHitTokens + other.cacheHitTokens,
        completionTokens + other.completionTokens
    )

    companion object {
        val EMPTY = UsageStats()
    }
}

/** 现在是高峰时段吗？ */
private fun isPeakHour(): Boolean {
    val cal = Calendar.getInstance()
    val dow = cal.get(Calendar.DAY_OF_WEEK)
    if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    return hour in 9..11 || hour in 14..17
}

/** 按官方峰谷单价算出这一轮花了多少元 */
private fun costCny(u: UsageStats): Double {
    val peak = isPeakHour()
    val hitPrice = if (peak) 0.04 else 0.02
    val missPrice = if (peak) 2.0 else 1.0
    val outPrice = if (peak) 8.0 else 4.0
    return (u.cacheHitTokens * hitPrice +
        u.cacheMissTokens * missPrice +
        u.completionTokens * outPrice) / 1_000_000.0
}

/** 费用显示：不到 1 分钱就给 4 位小数，否则 2 位 */
private fun formatCny(v: Double): String =
    if (v < 0.01) {
        String.format(Locale.US, "¥%.4f", v)
    } else {
        String.format(Locale.US, "¥%.2f", v)
    }

/** token 数显示：1.2K / 3.4M */
private fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

/** 从任意一条 SSE 数据里尝试抠出 usage（DeepSeek 通常放在最后一条，且那一条 choices 为空） */
private fun parseUsage(json: String): UsageStats? {
    return try {
        val u = JSONObject(json).optJSONObject("usage") ?: return null
        if (u.length() == 0) return null
        UsageStats(
            promptTokens = u.optInt("prompt_tokens", 0),
            cacheHitTokens = u.optInt("prompt_cache_hit_tokens", 0),
            completionTokens = u.optInt("completion_tokens", 0)
        )
    } catch (e: Exception) {
        null
    }
}


// ============================================================
// 3.3 ★★★ Agent 执行闭环（带工具调用）★★★
//
// 和 sendToDeepSeek 的关系：
//   · sendToDeepSeek 一个字都没改，保留作「无工具」的退化路径
//   · 这个函数是它的升级版：
//       请求带 tools → 收 tool_calls → 本地执行 → 结果作为 role:"tool" 回填
//       → 再发一次请求 → 拿到最终回答
//
// 50ms 批处理规则完全一致，第二轮同样走流式：
//   · 网络在 IO 线程
//   · 只有攒够 FLUSH_INTERVAL_MS 才切主线程刷一次
//   · 流结束补刷一次
// ============================================================
fun runAgentLoop(
    history: List<ChatMessage>,
    scope: CoroutineScope,
    onToolCallStarted: (id: String, name: String, argsJson: String) -> Unit,
    onToolCallFinished: (id: String, ok: Boolean, output: String) -> Unit,
    onFlush: (String) -> Unit,
    onUsage: (UsageStats) -> Unit,
    onDone: (totalChars: Int) -> Unit,
    onError: (String) -> Unit
): Job {
    return scope.launch(Dispatchers.IO) {
        try {
            // ---------- 1. 检查 API Key ----------
            val apiKey = BuildConfig.DEEPSEEK_API_KEY
            if (apiKey.isBlank()) {
                withContext(Dispatchers.Main) {
                    onError("API Key 是空的，请检查 local.properties 里的 DEEPSEEK_API_KEY")
                }
                return@launch
            }

            // ---------- 2. 把历史装进消息数组（这一轮会不断增长） ----------
            val messages = JSONArray()

            if (SYSTEM_PROMPT.isNotEmpty()) {
                messages.put(JSONObject().apply {
                    put("role", "system")
                    // ★ 带上当前工作区约束
                    put("content", systemPromptWithWorkspace())
                })
            }

            for (msg in history) {
                if (msg.content.isBlank()) continue
                if (msg.isError) continue
                if (msg.type == MessageType.TOOL_CALL) continue
                messages.put(JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "assistant")
                    put("content", msg.content)
                })
            }

            val toolsArray = JSONArray(TOOL_DEFINITIONS)
            var totalChars = 0
            var round = 0
            // 多轮工具调用会把 token 用量逐轮累加
            var accUsage = UsageStats.EMPTY

            while (round < MAX_TOOL_ROUNDS) {
                round++
                Log.d(TAG, "agent round $round / messages=${messages.length()}")

                val bodyJson = JSONObject().apply {
                    put("model", MODEL_NAME)
                    put("stream", true)
                    // ★ 关键：不加这个，流式响应里【根本不会返回 usage】
                    //   DeepSeek 默认只在非流式请求里给 usage
                    put("stream_options", JSONObject().put("include_usage", true))
                    put("messages", messages)
                    put("tools", toolsArray)
                    put("thinking", JSONObject().put("type", "disabled"))
                }.toString()

                // 【诊断】把完整请求体打出来 —— 亲眼看 tools 数组在不在
                Log.d(
                    TAG,
                    "发送请求 round=$round, tools=${toolsArray.length()} 个, 请求体: $bodyJson"
                )

                val request = Request.Builder()
                    .url(API_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = httpClient.newCall(request).execute()
                Log.d(TAG, "agent round $round HTTP ${response.code}")

                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "HTTP ${response.code}: ${errBody.take(300)}")
                    withContext(Dispatchers.Main) { onError("HTTP ${response.code}") }
                    return@launch
                }

                val bodyStream = response.body
                if (bodyStream == null) {
                    withContext(Dispatchers.Main) { onError("服务器返回了空内容") }
                    return@launch
                }

                val reader = BufferedReader(InputStreamReader(bodyStream.byteStream()))

                // ---------- 50ms 批处理（和 sendToDeepSeek 同一套） ----------
                val buffer = StringBuilder()
                var lastFlushAt = 0L
                var roundChars = 0

                // tool_calls 按 index 累积：index -> {id, name, arguments}
                val pending = LinkedHashMap<Int, JSONObject>()

                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue

                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue

                    // ★ usage 要【先于】delta 解析：
                    //   最后那条 usage 数据的 choices 是空数组，parseDeltaFull 会返回 null 被跳过，
                    //   要是放在后面就永远读不到 usage 了。
                    parseUsage(payload)?.let { accUsage += it }

                    val delta = parseDeltaFull(payload) ?: continue

                    // ---- 正文分片 ----
                    val content = delta.content
                    if (content != null) {
                        buffer.append(content)
                        roundChars += content.length

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                            lastFlushAt = now
                            val text = buffer.toString()
                            withContext(Dispatchers.Main) { onFlush(text) }
                        }
                    }

                    // ---- 工具调用分片 ----
                    for (piece in delta.toolCalls) {
                        val entry = pending.getOrPut(piece.index) {
                            JSONObject().apply {
                                put("id", "")
                                put("name", "")
                                put("arguments", "")
                            }
                        }
                        if (!piece.id.isNullOrEmpty()) entry.put("id", piece.id)
                        if (!piece.name.isNullOrEmpty()) entry.put("name", piece.name)
                        if (!piece.arguments.isNullOrEmpty()) {
                            entry.put("arguments", entry.optString("arguments") + piece.arguments)
                        }
                    }
                }

                // 流结束补刷一次（否则最后几个字不会上屏）
                if (buffer.isNotEmpty()) {
                    val text = buffer.toString()
                    withContext(Dispatchers.Main) { onFlush(text) }
                }

                response.close()

                // 【日志】这一轮到底拿到了什么：
                //   toolCalls > 0 → 模型要调工具，进入下一轮
                //   toolCalls = 0 → 模型直接给了文本回答（说明它没打算用工具）
                //   这行是排查「模型为什么不用工具」最关键的依据
                Log.d(
                    TAG,
                    "agent round $round 结束: toolCalls=${pending.size}, textLen=${buffer.length}"
                )

                // ---------- 没有工具调用：这一轮就是最终回答 ----------
                if (pending.isEmpty()) {
                    totalChars += roundChars

                    val spend = costCny(accUsage)
                    Log.d(
                        TAG,
                        "agent done: round=$round, chars=$totalChars, " +
                            "prompt=${accUsage.promptTokens}(cacheHit=${accUsage.cacheHitTokens}), " +
                            "completion=${accUsage.completionTokens}, " +
                            "费用≈${formatCny(spend)}（${if (isPeakHour()) "高峰" else "空闲"}时段）"
                    )

                    withContext(Dispatchers.Main) {
                        onUsage(accUsage)
                        onDone(totalChars)
                    }
                    return@launch
                }

                // ---------- 有工具调用：先把 assistant 这条记进历史 ----------
                val assistantCalls = JSONArray()
                for ((_, call) in pending) {
                    assistantCalls.put(JSONObject().apply {
                        put("id", call.optString("id"))
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", call.optString("name"))
                            put("arguments", call.optString("arguments"))
                        })
                    })
                }

                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    // 纯工具调用时正文是空的，必须塞 JSON null 而不是空串
                    if (buffer.isEmpty()) {
                        put("content", JSONObject.NULL)
                    } else {
                        put("content", buffer.toString())
                    }
                    put("tool_calls", assistantCalls)
                })

                // ---------- 逐个执行，结果作为 role:"tool" 回填 ----------
                for ((_, call) in pending) {
                    val callId = call.optString("id")
                    val toolName = call.optString("name")
                    val argsJson = call.optString("arguments")

                    withContext(Dispatchers.Main) {
                        onToolCallStarted(callId, toolName, argsJson)
                    }

                    // ★ 本地真正执行（不在主线程，不阻塞 UI）
                    val result = executeTool(toolName, argsJson)
                    Log.d(TAG, "tool=$toolName ok=${result.ok} len=${result.output.length}")

                    withContext(Dispatchers.Main) {
                        onToolCallFinished(callId, result.ok, result.output)
                    }

                    messages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", callId)
                        put("content", result.output)
                    })
                }

                totalChars += roundChars
                // ★★★ 这里就是「自动发起第二次请求」的地方 ★★★
                //
                //   循环回到 while 顶部，此时 messages 里已经多了两条：
                //     { role:"assistant", tool_calls:[{id, function:{name, arguments}}] }
                //     { role:"tool", tool_call_id:<同一个 id>, content:<文件内容或错误信息> }
                //
                //   下一次请求模型就能看到工具结果，从而给出真正的回答。
                //   界面上的 onFlush 会把占位气泡的正文换成这一轮的流式输出。
                Log.d(
                    TAG,
                    "工具执行完毕，即将发起第 ${round + 1} 次请求（messages=${messages.length()}）"
                )
            }

            withContext(Dispatchers.Main) {
                onError("工具调用超过 $MAX_TOOL_ROUNDS 轮，已中止")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "agent loop failed", e)
            withContext(Dispatchers.Main) {
                onError("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}


// ============================================================
// 4. 程序入口
// ============================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 【日志】App 启动标记，方便在 Logcat 里找到每次测试的起点
        Log.d(TAG, "App started")

        enableEdgeToEdge()
        setContent {
            MyChatTheme {
                ChatScreen()
            }
        }
    }
}


// ============================================================
// 5.0 会话模型
//
// 一个 ChatSession = 侧边栏里的一行 = 一个独立对话。
//
// messages 用「不可变 List」而不是 mutableStateListOf：
// 想改内容就 copy 出一个新对象，然后 sessions[i] = 新对象。
// 这样 Compose 一定能感知到变化（对列表元素赋值是会被追踪的），
// 也不会出现"内外两个列表同时被改"的混乱。
// ============================================================
// ============================================================
// 5.00 毛玻璃取色（深 / 亮两套）
//
// ⚠️ 刻意不用 Modifier.blur()：它在 Android 上是离屏渲染 + 高斯模糊，
//    每帧都要重算一遍，对「50ms 批处理防发热」这个核心目标来说是灾难。
//    这里用「半透明底 + 高光边框」的拟态做法，视觉接近、开销几乎为零。
// ============================================================
@Composable
private fun glassFill(): Color = if (isSystemInDarkTheme()) GlassFillDark else GlassFillLight

@Composable
private fun glassDrawer(): Color = if (isSystemInDarkTheme()) GlassDrawerDark else GlassDrawerLight

@Composable
private fun glassEdge(): Color = if (isSystemInDarkTheme()) GlassEdgeDark else GlassEdgeLight

@Composable
private fun glassTint(): Color = if (isSystemInDarkTheme()) GlassTintDark else GlassTintLight


// ============================================================
// 5.01 侧边栏专用的「瘦」数据模型
//
// ⚠️ 这是性能优化的关键，不是多余的抽象。
//
// 如果直接把 ChatSession 传给侧边栏，那么每次 50ms 流式刷新都会产生
// 一个新的 ChatSession 对象（copy 出来带新的 messages），
// 侧边栏读到「对象变了」→ 整个抽屉跟着重组 + LazyColumn 重新测量，
// 抽屉滑动动画期间就卡。
//
// 改成只带 id / title / createdAt 之后：
//   · 流式刷新只改 messages，这三个字段不变
//   · @Immutable + data class 的 equals 判定为「相等」
//   · Compose 直接跳过整个 Sidebar 的重组
// ============================================================
@Immutable
data class SidebarSession(
    val id: String,
    val title: String,
    val createdAt: Long
)

@Immutable
data class SidebarState(
    val sessions: List<SidebarSession>,
    val currentSessionId: String
)


data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    // 创建时间（毫秒）。只用于侧边栏分组（今天 / 昨天 / 更早）。
    // 数据来自 SessionEntity.createdAt —— Room 表结构没有任何改动。
    val createdAt: Long = System.currentTimeMillis()
)


// ============================================================
// 5. 聊天主界面（侧边抽屉 + 主内容）
//
// 结构：
//   ModalNavigationDrawer
//     ├─ drawerContent → DrawerContent（新建对话 / 会话列表 / 设置）
//     └─ 主内容 → Scaffold(TopAppBar) + 聊天区 + 输入框
// ============================================================
// ============================================================
// 5.05 ChatViewModel —— 跨屏幕旋转存活的状态
//
// 为什么需要它：
//   ① remember { mutableStateOf() } 在 Activity 重建（旋转屏幕）时全部丢失
//   ② rememberCoroutineScope() 会随组合销毁被 cancel —— 旋转屏幕会把正在跑的
//      流式请求直接掐断，用户看到的是"回复突然停住"
//
// 做法：状态搬进 ViewModel，但对外暴露的是 MutableState，
//       界面侧用 `by vm.xxxState` 委托，现有代码几乎不用改。
//       流式请求改跑在 viewModelScope 上，旋转就不再打断。
//
// ⚠️ 注意：ViewModel 只在「配置变更」时存活；进程被杀（真正退出）依然会丢，
//    那种情况靠 Room 恢复。
// ============================================================
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    val dao: ChatDao = AppDatabase.get(app).chatDao()

    // ---------- 状态（界面通过 by 委托读取/写入） ----------
    val sessions = mutableStateListOf<ChatSession>()
    val currentSessionIdState = mutableStateOf("")
    val inputTextState = mutableStateOf("")
    val isStreamingState = mutableStateOf(false)
    val lastUsageState = mutableStateOf(UsageStats.EMPTY)
    val workspaceState = mutableStateOf("")
    val showSettingsState = mutableStateOf(false)

    /** 首次从数据库加载只做一次。旋转回来不要重新读库，否则会打断正在进行的流。 */
    var loadedOnce = false

    /** 正在跑的流式任务，停止按钮要用 */
    var streamJob: Job? = null

    /** 本轮 AI 占位行在数据库里的 id（停止生成时落库用） */
    val aiRowId = AtomicInteger(0)

    /** 本轮属于哪个会话 */
    var activeSessionId: String = ""
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    // ===== 全局状态：全部放在 ViewModel 里，旋转屏幕不丢 =====
    val vm: ChatViewModel = viewModel()

    // 所有会话。mutableStateListOf 是"能被 Compose 感知的列表"。
    val sessions = vm.sessions

    // 当前选中的会话 id
    var currentSessionId by vm.currentSessionIdState

    // 输入框文字 / 是否正在流式回复
    var inputText by vm.inputTextState
    var isStreaming by vm.isStreamingState

    // 抽屉开合状态（rememberDrawerState 内部用的就是 rememberSaveable，本身就能扛旋转）
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // 设置页
    var showSettings by vm.showSettingsState

    // 存储权限提示（Agent 的 read_file 工具需要「所有文件访问权限」）
    var showStorageTip by remember { mutableStateOf(false) }

    // Shizuku 授权状态（run_shell 工具需要）
    var shizukuGranted by remember { mutableStateOf(false) }

    // UI 操作（开关抽屉之类）用这个作用域
    val scope = rememberCoroutineScope()
    // ★ 流式请求用 viewModelScope：旋转屏幕时 rememberCoroutineScope 会被 cancel，
    //   而 viewModelScope 要等 ViewModel 真正销毁才结束 —— 这样旋转不会打断生成。
    val streamScope: CoroutineScope = vm.viewModelScope

    // ===== Room 数据库 =====
    // AppDatabase.get(...) 是单例，调多少次都只会创建一个数据库实例。
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).chatDao() }

    // ===== 工作区（Settings 页里选，存 SharedPreferences，重启不丢） =====
    var workspace by vm.workspaceState
    LaunchedEffect(Unit) {
        workspace = loadWorkspace(context)
        // ★ 同步给顶层的工具层：executeTool 是普通函数，读不到 Compose 状态，
        //   所以用一个 @Volatile 的全局变量传递。
        currentWorkspace = workspace
        Log.d(TAG, "已加载工作区: ${workspace.ifBlank { "(未设置)" }}")
    }

    // ===== 流式任务 / 费用统计（状态都在 ViewModel 里） =====
    var lastUsage by vm.lastUsageState
    // AI 占位行在数据库里的 id。停止时要靠它把已收到的文字写回去。
    val aiRowId = vm.aiRowId

    // ===== 工具：按 id 更新某个会话 =====
    // ⚠️ 关键设计：流式回调里必须按"发送那一刻记下的 sessionId"更新，
    //    绝不能读 currentSessionId。否则用户在流式过程中切到别的会话，
    //    正在流的那段文字就会写到新会话里（串台）。
    fun updateSession(id: String, block: (ChatSession) -> ChatSession) {
        val i = sessions.indexOfFirst { it.id == id }
        if (i >= 0) sessions[i] = block(sessions[i])
    }

    // ===== 工具：新建会话并选中 =====
    // 内存里加一条，同时异步写库（写库失败也不影响界面继续用）
    fun newSession(): String {
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        // 放到列表最上面：越新的会话越靠前，这是主流客户端的习惯
        sessions.add(
            0,
            ChatSession(
                id = id,
                title = "新对话",
                messages = emptyList(),
                createdAt = createdAt
            )
        )
        currentSessionId = id
        streamScope.launch(Dispatchers.IO) {
            dao.insertSession(SessionEntity(id = id, title = "新对话", createdAt = createdAt))
        }
        return id
    }

    // ===== 启动时：从数据库把会话和历史消息全部读回来 =====
    // LaunchedEffect(Unit) 表示：这段代码在界面第一次显示时执行一次。
    // 数据库读写必须切到 IO 线程，否则会卡住界面。
    LaunchedEffect(Unit) {
        // ★ 只加载一次。
        //   ViewModel 在旋转屏幕时是存活的，state 还在；要是这里重新读库 + clear，
        //   会把正在流式输出的那条气泡、以及还没落库的工具卡片全冲掉。
        if (vm.loadedOnce) {
            Log.d(TAG, "旋转回来，跳过重新加载（sessions=${sessions.size}）")
            return@LaunchedEffect
        }
        vm.loadedOnce = true

        val loaded: List<ChatSession> = withContext(Dispatchers.IO) {
            dao.getAllSessions().map { se ->
                ChatSession(
                    id = se.id,
                    title = se.title,
                    // 过滤掉正文为空的行：那是流式中断时留下的空壳，显示出来是个空气泡
                    messages = dao.getMessages(se.id).filter { it.content.isNotBlank() },
                    createdAt = se.createdAt
                )
            }
        }
        sessions.clear()
        sessions.addAll(loaded)

        if (loaded.isEmpty()) {
            // 首次启动，或者刚被删表重建过：建一个空会话，否则输入框没地方发
            newSession()
        } else {
            currentSessionId = loaded.first().id
        }
    }

    // ===== 启动时检查存储权限 =====
    // MANAGE_EXTERNAL_STORAGE 只能由用户在系统设置里打开，所以这里只负责"提醒 + 跳转"。
    LaunchedEffect(Unit) {
        showStorageTip = !hasAllFilesAccess(context)
    }

    // ===== 通知权限（Android 13 / API 33 起是运行时权限）=====
    // 拒绝也不影响聊天，只是后续 DSH 的前台服务通知看不见。
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "POST_NOTIFICATIONS granted=$granted")
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ===== Shizuku 授权状态监听 =====
    // 用户点「授权」后，结果会通过这个 listener 回来（不是同步返回的）。
    // 用 DisposableEffect 保证界面销毁时把 listener 摘掉，否则会内存泄漏。
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuGranted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Shizuku 授权结果: $grantResult")
            // 刚拿到授权就立刻绑定 UserService，否则 run_shell 会说"执行器还没连上"
            if (shizukuGranted) bindShizukuUserService(context)
        }
        Shizuku.addRequestPermissionResultListener(listener)

        shizukuGranted = isShizukuGranted()
        if (shizukuGranted) bindShizukuUserService(context)

        onDispose {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    // ===== 当前会话的消息（派生态，不单独存一份） =====
    val currentMessages: List<ChatMessage> =
        sessions.firstOrNull { it.id == currentSessionId }?.messages ?: emptyList()

    // ===== 侧边栏的「瘦」状态 =====
    // 每次重组都会新建这个对象，但它是 @Immutable + data class：
    // 流式刷新期间 id / title / createdAt 都没变 → equals 判定相等
    // → Compose 直接跳过整个 Sidebar 的重组。这是消除卡顿的关键。
    val sidebarState = SidebarState(
        sessions = sessions.map { SidebarSession(it.id, it.title, it.createdAt) },
        currentSessionId = currentSessionId
    )

    // 回调必须用 remember 固定引用。
    // 否则每次重组都新建 lambda，Sidebar 的参数永远"变了"，跳过重组就失效了。
    // isStreaming 用 rememberUpdatedState 包一层，保证读到的永远是最新值。
    val streamingState = rememberUpdatedState(isStreaming)
    val onSelectSession: (String) -> Unit = remember {
        { id: String ->
            if (!streamingState.value) {
                currentSessionId = id
                scope.launch { drawerState.close() }
            }
        }
    }
    val onOpenSettings: () -> Unit = remember {
        {
            scope.launch { drawerState.close() }
            showSettings = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 遮罩加深：默认 0.32 太浅，侧边栏右侧会透出聊天区的蓝色用户气泡，
        // 在屏幕右边缘看起来就是一条"蓝色竖条"
        scrimColor = Color.Black.copy(alpha = 0.6f),
        drawerContent = {
            // ★ 不用 ModalDrawerSheet：
            //   它内部把阴影硬编码成 16dp（DrawerDefaults.ModalDrawerElevation），
            //   API 不暴露、改不掉。抽屉滑动时每帧都要绘制这层阴影，是卡顿来源之一。
            //   这里换成自己的 Surface，shadowElevation = 0 —— 纯黑背景下不需要阴影区分层级。
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(320.dp),
                color = glassDrawer(),
                shape = RoundedCornerShape(0.dp),
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                // 系统栏内边距放在 Surface 内部：这样背景铺满整高，内容又不会被状态栏压住
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                Sidebar(
                    state = sidebarState,
                    onSelect = onSelectSession,
                    // 重命名：内存 + Room 一起改
                    onRename = { id, newTitle ->
                        updateSession(id) { it.copy(title = newTitle) }
                        streamScope.launch(Dispatchers.IO) {
                            dao.updateSessionTitle(id, newTitle)
                        }
                        Log.d(TAG, "重命名会话: $id → $newTitle")
                    },
                    // 删除：Room 级联删消息；删的是当前会话时自动切走，绝不白屏
                    onDelete = { id ->
                        sessions.removeAll { it.id == id }
                        streamScope.launch(Dispatchers.IO) {
                            dao.deleteSession(id)   // messages 表有 ON DELETE CASCADE，会一起删
                        }
                        Log.d(TAG, "删除会话: $id，剩余 ${sessions.size} 个")

                        if (currentSessionId == id) {
                            val next = sessions.firstOrNull()?.id
                            if (next != null) {
                                currentSessionId = next
                            } else {
                                // 全删光了：建一个新的空会话，否则界面没有可显示的会话
                                newSession()
                            }
                        }
                    },
                    onSettings = onOpenSettings
                )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = sessions.firstOrNull { it.id == currentSessionId }?.title
                                ?: "CoolStream",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "打开侧边栏")
                        }
                    },
                    actions = {
                        // 右侧：正圆形毛玻璃「新建对话」按钮（半透明底 + 高光边框 + 高亮加号）
                        Surface(
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .size(38.dp)
                                .clickable(enabled = !isStreaming) { newSession() },
                            shape = CircleShape,
                            color = glassFill(),
                            border = BorderStroke(1.dp, glassEdge()),
                            shadowElevation = 2.dp,
                            tonalElevation = 0.dp
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "新建对话",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        // 顶栏透明：聊天内容滑到顶部时能隐约透出来
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
            ) {
                // ---------- 中间：空会话显示欢迎语，否则显示消息列表 ----------
                if (currentMessages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "你好，我是 CoolStream",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(currentMessages) { index, message ->
                            // ★★★ 渲染时机（防发热的核心）★★★
                            //
                            //   「正在流式输出的最后一条 AI 消息」→ 纯文本
                            //   其余已经定稿的消息 → Markdown
                            //
                            //   这样 50ms 刷新期间，那一条永远走 Text 分支，
                            //   一次 Markdown 解析都不会发生。
                            //   等 onDone 把 isStreaming 置回 false，
                            //   它才第一次被解析——只解析一次。
                            val streamingTail =
                                isStreaming &&
                                    index == currentMessages.lastIndex &&
                                    !message.isUser

                            MessageBubble(
                                message = message,
                                markdown = !streamingTail
                            )
                        }
                    }
                }

                // ---------- 底部：InfoBar（本轮费用 + 上下文用量） ----------
                if (lastUsage.totalTokens > 0) {
                    InfoBar(usage = lastUsage, isStreaming = isStreaming)
                }

                // ---------- 底部：输入区 ----------
                InputBar(
                    text = inputText,
                    enabled = !isStreaming,        // ★ 发送中禁止再发，防止重复请求
                    isStreaming = isStreaming,
                    onTextChange = { inputText = it },
                    onSend = {
                        val trimmed = inputText.trim()

                        // 空消息不发；正在流式回复时也不发
                        if (trimmed.isNotEmpty() && !isStreaming) {

                            inputText = ""
                            isStreaming = true

                            // ★ 先把这一刻的会话 id 钉住。后面所有回调都认它，
                            //   不再读 currentSessionId。
                            val sessionId =
                                if (currentSessionId.isBlank()) newSession() else currentSessionId

                            // ① 在内存里追加「用户消息」+「Mock 工具卡片」+「AI 占位消息」
                            val now = System.currentTimeMillis()

                            val userMessage = ChatMessage(
                                sessionId = sessionId,      // ★ 挂到当前会话下
                                content = trimmed,
                                isUser = true,
                                timestamp = now,
                                type = MessageType.USER
                            )

                            // AI 占位消息：正文先存空串，等流结束再填最终文本。
                            // ★ 它必须永远是 messages 的最后一条 —— onFlush 靠 last() 定位它。
                            //   真实的工具卡片会在回调里插到它「前面」。
                            val aiPlaceholder = ChatMessage(
                                sessionId = sessionId,
                                content = "",
                                isUser = false,
                                // ★ 必须比工具卡片（now + 1）大：
                                //   重启后按 timestamp 排序，AI 回复要排在工具卡片之后。
                                //   用户消息 = now，工具卡片 = now + 1，AI = now + 2。
                                timestamp = now + 2,
                                type = MessageType.AI_TEXT
                            )

                            // 会话标题：如果是这个会话的第一句话，就用它前 14 个字当标题
                            val isFirstMessage =
                                sessions.firstOrNull { it.id == sessionId }
                                    ?.messages?.isEmpty() ?: true
                            val newTitle = if (isFirstMessage) trimmed.take(14) else null

                            updateSession(sessionId) { s ->
                                s.copy(
                                    title = newTitle ?: s.title,
                                    messages = s.messages + userMessage + aiPlaceholder
                                )
                            }

                            // ①-2 异步落库（不等它，立刻去发请求）
                            //
                            //   ★ 写库只发生在两个时刻：这里 和 onDone。
                            //     onFlush 每 50ms 触发一次，绝对不能碰数据库，
                            //     否则 900 个碎片就是几十上百次磁盘 IO，界面必卡。
                            //
                            //   aiRowId 用 AtomicInteger 装：写库在 IO 线程，
                            //   读它发生在 onDone，普通 var 跨线程不安全。
                            // aiRowId 用的是 ChatScreen 级的那个（停止生成时要读它），这里不再重新声明
                            vm.activeSessionId = sessionId
                            val dbJob = streamScope.launch(Dispatchers.IO) {
                                // 标题变了才更新（会话行本身在 newSession() 时已经插过）
                                if (newTitle != null) {
                                    dao.updateSessionTitle(sessionId, newTitle)
                                }
                                dao.insert(userMessage)
                                aiRowId.set(dao.insert(aiPlaceholder).toInt())
                            }

                            // ② ★★★ 把整个对话历史打包发给模型 ★★★
                            //
                            //   dropLast(1)：去掉刚加进去的那条"正在思考…"占位气泡。
                            //   它是界面上的假数据，不能发给模型。
                            //
                            //   ⚠️ 必须 .toList() 做一份快照。
                            //      直接传原列表的话，后面流式更新会同时改动它，
                            //      导致发出去的内容不确定。
                            //   ⚠️ 工具卡片只是界面展示，不能当对话内容发给模型，
                            //      否则模型会收到一段它自己没写过的 JSON。
                            val fullHistory: List<ChatMessage> =
                                (sessions.firstOrNull { it.id == sessionId }?.messages
                                    ?: emptyList())
                                    .dropLast(1)
                                    .filter { it.type != MessageType.TOOL_CALL }
                                    .toList()

                            // ③ 只保留最近 N 条，防止对话太长超出模型上下文窗口
                            val capped = if (fullHistory.size > MAX_HISTORY_MESSAGES) {
                                fullHistory.takeLast(MAX_HISTORY_MESSAGES)
                            } else {
                                fullHistory
                            }

                            // ④ 截断之后如果第一条变成了 AI 的回复，去掉它，
                            //    让历史以"用户消息"开头（更符合对话格式惯例）
                            val historyForApi: List<ChatMessage> =
                                if (capped.firstOrNull()?.isUser == false) {
                                    capped.drop(1)
                                } else {
                                    capped
                                }

                            // ⑤ ★ 发起 Agent 闭环请求
                            //
                            //    sendToDeepSeek 原样保留、一个字没改。
                            //    这里换成 runAgentLoop —— 它在它的基础上多了：
                            //      请求带 tools / 解析 tool_calls / 本地执行 / 结果回填 / 二次请求
                            //
                            //    onFlush / onDone / onError 都在主线程被调用
                            //    （函数内部已经 withContext(Dispatchers.Main) 切好了）。
                            //
                            //    ★ 工具卡片的位置很关键：
                            //      必须插在「AI 占位气泡」之前，
                            //      这样 messages.last() 永远还是占位气泡，onFlush 才不会写错地方。
                            val toolCardSlots = HashMap<String, Int>()
                            // callId → 数据库行号。用 CompletableDeferred 保证：
                            // 「执行完」要更新那一行时，insert 一定已经完成了（不然会更新到一个还不存在的 id）
                            val toolCardRowIds = ConcurrentHashMap<String, CompletableDeferred<Int>>()

                            vm.streamJob = runAgentLoop(
                                history = historyForApi,
                                scope = streamScope,

                                // 模型要调工具：先插一张「执行中」的卡片
                                onToolCallStarted = { callId, toolName, argsJson ->
                                    val payload = try {
                                        JSONObject().apply {
                                            put("tool", toolName)
                                            put(
                                                "arguments",
                                                if (argsJson.isBlank()) JSONObject()
                                                else JSONObject(argsJson)
                                            )
                                            put("ok", JSONObject.NULL)   // JSON null = 还在执行
                                            put("output", "")
                                        }.toString()
                                    } catch (e: Exception) {
                                        // 参数不是合法 JSON 也要能显示出来
                                        JSONObject().apply {
                                            put("tool", toolName)
                                            put("arguments", argsJson)
                                            put("ok", JSONObject.NULL)
                                            put("output", "")
                                        }.toString()
                                    }

                                    val card = ChatMessage(
                                        sessionId = sessionId,
                                        content = payload,
                                        isUser = false,
                                        // ★ 必须是 now + 1：用户消息是 now、AI 占位是 now + 2。
                                        //   这样重启后按 timestamp 排序，卡片才会落在用户和 AI 之间。
                                        timestamp = now + 1,
                                        type = MessageType.TOOL_CALL
                                    )

                                    var slot = -1
                                    updateSession(sessionId) { s ->
                                        val list = s.messages.toMutableList()
                                        // 插在最后一条（AI 占位）之前
                                        val at = (list.size - 1).coerceAtLeast(0)
                                        list.add(at, card)
                                        slot = at
                                        s.copy(messages = list)
                                    }
                                    if (slot >= 0) toolCardSlots[callId] = slot

                                    // ★ 落库：不落库的话，重启 App 这张卡片就直接消失了
                                    val deferred = CompletableDeferred<Int>()
                                    toolCardRowIds[callId] = deferred
                                    streamScope.launch(Dispatchers.IO) {
                                        val rid = dao.insert(card).toInt()
                                        deferred.complete(rid)
                                        Log.d(TAG, "工具卡片已落库: callId=$callId rowId=$rid")
                                    }
                                },

                                // 工具执行完：把结果写回那张卡片（内存 + 数据库）
                                onToolCallFinished = { callId, ok, output ->
                                    val slot = toolCardSlots[callId] ?: -1

                                    // 先把新的 content 算出来，内存和数据库用同一份
                                    val current = sessions.firstOrNull { it.id == sessionId }
                                        ?.messages?.getOrNull(slot)?.content ?: ""
                                    val updated = try {
                                        JSONObject(current).apply {
                                            put("ok", ok)
                                            put("output", output)
                                        }.toString()
                                    } catch (e: Exception) {
                                        current
                                    }

                                    updateSession(sessionId) { s ->
                                        val msgs = s.messages
                                        if (slot in msgs.indices &&
                                            msgs[slot].type == MessageType.TOOL_CALL
                                        ) {
                                            val list = msgs.toMutableList()
                                            list[slot] = msgs[slot].copy(content = updated)
                                            s.copy(messages = list)
                                        } else {
                                            s
                                        }
                                    }

                                    toolCardRowIds[callId]?.let { deferred ->
                                        streamScope.launch(Dispatchers.IO) {
                                            val rid = deferred.await()
                                            if (rid > 0) {
                                                dao.updateContent(rid, updated, false)
                                                Log.d(TAG, "工具卡片结果已入库: callId=$callId rowId=$rid")
                                            }
                                        }
                                    }
                                },

                                onFlush = { text ->
                                    // ★ 50ms 批处理的落点：
                                    //   只改 sessionId 这个会话里「最后一条 AI 消息」的正文。
                                    //   其余会话、其余消息一个都不碰。
                                    updateSession(sessionId) { s ->
                                        val msgs = s.messages
                                        if (msgs.isNotEmpty() && !msgs.last().isUser) {
                                            s.copy(
                                                messages = msgs.dropLast(1) +
                                                    msgs.last().copy(content = text)
                                            )
                                        } else {
                                            s
                                        }
                                    }
                                },

                                // token 用量回来了：更新 InfoBar
                                onUsage = { u -> lastUsage = u },

                                onDone = { total ->
                                    Log.d(TAG, "onDone: total=$total")
                                    isStreaming = false
                                    vm.streamJob = null

                                    // ★ 流结束，这才是唯一需要写"正文"的时刻。
                                    //   要是每个 50ms 都写库，900 个碎片就是几十次磁盘操作，
                                    //   界面会明显卡顿，也伤闪存。
                                    val finalText = sessions
                                        .firstOrNull { it.id == sessionId }
                                        ?.messages?.lastOrNull()?.content ?: ""

                                    streamScope.launch(Dispatchers.IO) {
                                        dbJob.join()          // 等插入完成（正常早就完成了）
                                        val rowId = aiRowId.get()
                                        if (rowId > 0) {
                                            dao.updateContent(rowId, finalText, false)
                                        }
                                    }
                                },

                                onError = { err ->
                                    Log.e(TAG, "stream error: $err")
                                    // 把错误显示在气泡里，不然界面上什么都看不到。
                                    // 标上 isError = true，这样下次发消息时它不会被
                                    // 当成 AI 的真实回复发给模型。
                                    updateSession(sessionId) { s ->
                                        val msgs = s.messages
                                        if (msgs.isNotEmpty() && !msgs.last().isUser) {
                                            s.copy(
                                                messages = msgs.dropLast(1) +
                                                    msgs.last().copy(
                                                        content = "错误: $err",
                                                        isError = true
                                                    )
                                            )
                                        } else {
                                            s
                                        }
                                    }

                                    // 出错也算一次流的结束，同样落库。
                                    // 不写的话这一行会一直是空串，下次启动就被过滤掉了。
                                    streamScope.launch(Dispatchers.IO) {
                                        dbJob.join()
                                        val rowId = aiRowId.get()
                                        if (rowId > 0) {
                                            dao.updateContent(rowId, "错误: $err", true)
                                        }
                                    }
                                    isStreaming = false
                                    vm.streamJob = null
                                }
                            )
                        }
                    },
                    // ★★ 停止生成 ★★
                    //   cancel() 之后 runAgentLoop 里的 CancellationException 会被重新抛出，
                    //   协程结束；但 onDone 不会触发，所以「落库」必须在这里自己补上。
                    onStop = {
                        val job = vm.streamJob
                        vm.streamJob = null
                        job?.cancel()
                        isStreaming = false

                        val sid = vm.activeSessionId
                        val partial = sessions.firstOrNull { it.id == sid }
                            ?.messages?.lastOrNull()?.content ?: ""
                        val rowId = aiRowId.get()

                        Log.d(TAG, "用户停止生成: 已收到 ${partial.length} 字符, rowId=$rowId")

                        if (rowId > 0) {
                            streamScope.launch(Dispatchers.IO) {
                                dao.updateContent(rowId, partial, false)
                            }
                        }
                    }
                )
            }
        }
    }

    // ---------- 设置页（全屏，覆盖在聊天界面之上） ----------
    if (showSettings) {
        SettingsScreen(
            shizukuGranted = shizukuGranted,
            workspace = workspace,
            onWorkspaceChanged = { path ->
                workspace = path
                // saveWorkspace() 已经在 SettingsScreen 里调过了，这里只同步界面状态
            },
            onBack = { showSettings = false }
        )
    }

    // ---------- 存储权限提示 ----------
    if (showStorageTip) {
        AlertDialog(
            onDismissRequest = { showStorageTip = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("需要文件访问权限") },
            text = {
                Text(
                    "Agent 的 read_file 工具需要「所有文件访问权限」才能读取本地文件。\n\n" +
                        "不开也能正常聊天，只是模型调用 read_file 时会失败。\n" +
                        "开启后请回到 App 再试一次。",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    openAllFilesAccessSettings(context)
                    showStorageTip = false
                }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { showStorageTip = false }) { Text("稍后") }
            }
        )
    }
}


// ============================================================
// 5.1 侧边栏内容
//
// 布局：顶部「新建对话」按钮 → 中间会话列表（可滚动）→ 左下角「设置」
// 中间用 weight(1f) 撑开，这样设置按钮自然被压到底部。
// ============================================================
@Composable
fun Sidebar(
    state: SidebarState,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    // 搜索关键词。纯内存过滤，不碰数据库。
    var query by remember { mutableStateOf("") }

    // 长按菜单的目标会话
    var menuTarget by remember { mutableStateOf<SidebarSession?>(null) }
    // 重命名弹窗的目标 + 输入内容
    var renameTarget by remember { mutableStateOf<SidebarSession?>(null) }
    var renameText by remember { mutableStateOf("") }

    // ★ 注意 remember 的 key 是 state.sessions（内容比较），
    //   不是整个 ChatSession。流式刷新不会让它重算。
    val filtered = remember(state.sessions, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            state.sessions
        } else {
            state.sessions.filter { it.title.contains(q, ignoreCase = true) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            // ---------- 顶部：搜索胶囊（毛玻璃） ----------
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = glassFill(),
                border = BorderStroke(1.dp, glassEdge()),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurface
                        ),
                        cursorBrush = SolidColor(cs.primary),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        text = "搜索会话",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = cs.onSurfaceVariant
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ---------- 中间：按「今天 / 昨天 / 更早」分组 ----------
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                DRAWER_GROUPS.forEachIndexed { groupIndex, label ->
                    val groupItems = filtered.filter { dayBucket(it.createdAt) == groupIndex }
                    if (groupItems.isNotEmpty()) {
                        item(key = "header-$groupIndex") {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp)
                            )
                        }
                        items(groupItems, key = { it.id }) { session ->
                            SessionRow(
                                title = session.title,
                                selected = session.id == state.currentSessionId,
                                onClick = { onSelect(session.id) },
                                onLongClick = { menuTarget = session }
                            )
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "没有匹配的会话",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 16.dp)
                        )
                    }
                }
            }

            // ---------- 左下角：设置（透明底 + 齿轮 + 文字） ----------
            TextButton(
                onClick = onSettings,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("设置", fontFamily = FontFamily.Monospace)
            }
        }

        // ---------- 右缘高光线：毛玻璃的"边缘反光" ----------
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(1.dp)
                .background(GlassHighlight)
        )
    }

    // ---------- 长按菜单：重命名 / 删除 ----------
    menuTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            containerColor = glassDrawer(),
            titleContentColor = cs.onSurface,
            textContentColor = cs.onSurfaceVariant,
            title = {
                Text(
                    text = target.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Text("对这个会话做什么？", style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = {
                TextButton(onClick = {
                    renameText = target.title
                    renameTarget = target
                    menuTarget = null
                }) { Text("重命名") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    menuTarget = null
                }) {
                    Text("删除", color = Color(0xFFF85149))
                }
            }
        )
    }

    // ---------- 重命名弹窗 ----------
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = glassDrawer(),
            titleContentColor = cs.onSurface,
            textContentColor = cs.onSurfaceVariant,
            title = { Text("重命名会话") },
            text = {
                Surface(
                    color = glassFill(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, glassEdge()),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BasicTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurface
                        ),
                        cursorBrush = SolidColor(cs.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = renameText.trim()
                    if (t.isNotEmpty()) onRename(target.id, t)
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }
}


// 侧边栏分组标题
private val DRAWER_GROUPS = listOf("今天", "昨天", "更早")

/** 0=今天 / 1=昨天 / 2=更早。纯内存计算，不查数据库。 */
private fun dayBucket(ts: Long): Int {
    val startOfToday = Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis()
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val startOfYesterday = startOfToday - 24L * 60 * 60 * 1000
    return when {
        ts >= startOfToday -> 0
        ts >= startOfYesterday -> 1
        else -> 2
    }
}

/** 侧边栏的一行会话：选中时是半透明胶囊高亮 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) glassTint() else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, glassEdge()) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            // 长按 = 重命名 / 删除
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)
        )
    }
}

// ============================================================
// 5.2 设置页（全屏）
//
// 原来是 AlertDialog，条目一多就挤成一团，改成整页列表：
//   运行 / 权限 / 关于 三段。
// 顶栏带返回箭头，系统返回键也接上了。
// ============================================================
@Composable
fun SettingsScreen(
    shizukuGranted: Boolean,
    workspace: String,
    onWorkspaceChanged: (String) -> Unit,
    onBack: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    // 系统返回键 = 关闭设置页
    BackHandler(onBack = onBack)

    // ===== 系统文件夹选择器 =====
    // OpenDocumentTree 会弹出系统的「选择目录」界面，回调里给一个 content:// 的 tree Uri。
    // 结果是【异步】回来的，所以要先转成绝对路径、落盘，再更新界面。
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val path = treeUriToPath(uri)
            if (path.isNullOrBlank()) {
                Log.e(TAG, "无法把 Uri 转成绝对路径: $uri")
            } else {
                saveWorkspace(context, path)     // 写 SharedPreferences + 更新全局 currentWorkspace
                onWorkspaceChanged(path)         // 更新 Compose 状态，界面立刻刷新
                Log.d(TAG, "工作区已设置: $path")
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = cs.background,
        contentColor = cs.onBackground
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // ★ App 开了 enableEdgeToEdge()，不补这个内边距，
                //   顶栏会钻到状态栏底下，和信号/电量图标叠在一起。
                //   （聊天主界面由 Scaffold 自动处理，设置页是自己画的，得手动补）
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ---------- 顶栏 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // ---------- 运行 ----------
                item { SectionHeader("运行") }
                item { InfoRow("模型", MODEL_NAME) }
                item { InfoRow("接口", API_URL) }
                item { InfoRow("批处理", "${FLUSH_INTERVAL_MS}ms") }
                item { InfoRow("上下文", "最近 $MAX_HISTORY_MESSAGES 条") }

                // ---------- 工作区 ----------
                item { SectionHeader("工作区") }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            // 点一下弹系统的「选择目录」，选完自动转成绝对路径并保存
                            .clickable { folderPicker.launch(null) }
                            .padding(vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "工作区设置",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (workspace.isBlank()) "选择目录" else "点击更换",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = workspace.ifBlank { "未设置（不限制路径）" },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (workspace.isBlank()) {
                                cs.onSurfaceVariant
                            } else {
                                Color(0xFF3FB950)
                            }
                        )
                    }
                }

                // ---------- 权限 ----------
                item { SectionHeader("权限") }
                item {
                    val storageOk = hasAllFilesAccess(context)
                    PermissionRow(
                        label = "文件权限",
                        status = if (storageOk) "已开启" else "未开启",
                        ok = storageOk,
                        onClick = { openAllFilesAccessSettings(context) }
                    )
                }
                item {
                    val running = isShizukuRunning()
                    PermissionRow(
                        label = "Shizuku",
                        status = when {
                            !running -> "服务未运行"
                            shizukuGranted -> "已授权"
                            else -> "点此授权"
                        },
                        ok = shizukuGranted,
                        onClick = {
                            // 已授权 → 重新绑一次（可能是冷启动没连上）
                            // 未授权 → 弹 Shizuku 的授权对话框
                            if (isShizukuGranted()) {
                                bindShizukuUserService(context)
                            } else {
                                requestShizukuPermission()
                            }
                        }
                    )
                }

                // ---------- 关于 ----------
                item { SectionHeader("关于") }
                item {
                    // ★ 版本号来自 BuildConfig.BUILD_VERSION_NAME，
                    //   由 build.gradle.kts 在【编译时】按「0.yyMMddHHmm」生成。
                    //   每次构建都会变，不需要手动改。
                    Text(
                        text = "当前版本：${BuildConfig.BUILD_VERSION_NAME}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                item {
                    InfoRow("工具", "list_dir / search_files / read_file / run_shell")
                }
                item {
                    InfoRow("数据", "Room v3 本地数据库")
                }
            }
        }
    }
}

/** 设置页的分组标题 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp)
    )
}

/** 设置页的「名字 — 值」一行（不可点） */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 设置页里的「权限」一行：左边名字，右边状态，整行可点。
 * 点一下直达对应的授权页（比让用户自己去系统设置里翻可靠得多）。
 */
@Composable
private fun PermissionRow(
    label: String,
    status: String,
    ok: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = status,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) Color(0xFF3FB950) else Color(0xFFF85149)
        )
    }
}


// ============================================================
// 6. 消息气泡
// ============================================================
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    // true = 走 Markdown 渲染；false = 纯文本（流式输出中的那一条）
    markdown: Boolean = true
) {
    // ★ 工具调用不走普通气泡，走专门的卡片
    if (message.type == MessageType.TOOL_CALL) {
        ToolCallCard(message = message, modifier = modifier)
        return
    }

    val cs = MaterialTheme.colorScheme
    val isUser = message.isUser

    // 用户气泡：实心蓝 + 白字
    // AI 气泡：深/浅灰底 + 1dp 描边 + 正文色
    val bubbleColor = if (isUser) cs.primary else cs.surface
    val textColor = if (isUser) cs.onPrimary else cs.onSurface

    // 正文为空说明 AI 还在思考（占位行），或者上次流式中断了
    val placeholder = message.content.isBlank() && !isUser

    // ★ 只有「AI 的、非错误的、非空的」正文才走 Markdown。
    //   用户自己打的内容不解释成 Markdown —— 免得他打了 # 或 ** 被吃掉。
    val useMarkdown = markdown && !isUser && !message.isError && message.content.isNotBlank()

    // 外层 Box 负责"靠左还是靠右"
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        // 内层 Surface 负责"长什么样"
        Surface(
            color = bubbleColor,
            // 大圆角 24dp：胶囊感
            shape = RoundedCornerShape(24.dp),
            // 无阴影：阴影高度和色调高度都归零
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            // 两种气泡都加一条极微弱的半透明高光边框（毛玻璃质感）
            border = BorderStroke(1.dp, glassEdge()),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            if (useMarkdown) {
                MarkdownText(
                    content = message.content,
                    textColor = textColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            } else {
                Text(
                    text = if (placeholder) "正在思考…" else message.content,
                    color = textColor,
                    // 等宽字体：极客风的关键
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}


// ============================================================
// 6.1 极简 Markdown 渲染（零依赖）
//
// 为什么不用第三方库：
//   我们只需要 3 个特性：粗体、H1/H2 标题、代码块。
//   而通用 Markdown 库会拖进完整的 CommonMark 解析器、图片加载、链接、
//   列表、表格、HTML 白名单…… 对「防发热」这个核心目标是反向的。
//
//   这里是逐行扫描的实现：O(n)、不建 AST、没有递归。
//   而且它只在 onDone 之后跑一次（流式期间走纯文本分支），
//   一次回复的解析开销大约几十微秒，可以忽略。
// ============================================================

/** Markdown 的最小块模型：标题 / 段落 / 代码块 */
private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
}

/**
 * 把 Markdown 文本切成块。逐行扫描，不建语法树。
 *
 * 支持：
 *   # / ## / ###   标题
 *   ``` ... ```    代码块（不区分语言，统一等宽 + 深色底）
 *   空行           段落分隔
 *   **粗体**       行内加粗（在渲染阶段处理）
 */
private fun parseMarkdown(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.replace("\r\n", "\n").split('\n')
    val para = StringBuilder()
    val code = StringBuilder()
    var inCode = false

    fun flushParagraph() {
        if (para.isNotEmpty()) {
            out.add(MdBlock.Paragraph(para.toString().trim()))
            para.setLength(0)
        }
    }

    for (line in lines) {
        val noTrail = line.trimEnd()

        // ---------- 代码块围栏 ----------
        if (noTrail.trimStart().startsWith("```")) {
            if (inCode) {
                out.add(MdBlock.Code(code.toString().trimEnd('\n')))
                code.setLength(0)
                inCode = false
            } else {
                flushParagraph()
                inCode = true
            }
            continue
        }
        if (inCode) {
            code.append(line).append('\n')
            continue
        }

        // ---------- 空行 = 段落结束 ----------
        if (noTrail.isBlank()) {
            flushParagraph()
            continue
        }

        // ---------- 标题 ----------
        val trimmed = noTrail.trimStart()
        val hashes = trimmed.takeWhile { it == '#' }.length
        if (hashes in 1..3 && trimmed.length > hashes && trimmed[hashes] == ' ') {
            flushParagraph()
            out.add(MdBlock.Heading(hashes, trimmed.substring(hashes + 1).trim()))
            continue
        }

        // ---------- 普通段落（连续多行合成一段） ----------
        if (para.isNotEmpty()) para.append('\n')
        para.append(trimmed)
    }

    // 流被截断时可能停在代码块中间，也要把它显示出来
    if (inCode && code.isNotEmpty()) {
        out.add(MdBlock.Code(code.toString().trimEnd('\n')))
    }
    flushParagraph()
    return out
}

/**
 * 行内标记渲染：把 `**粗体**` 和 `` `行内代码` `` 变成真正的样式。
 *
 * 只处理这两种，不处理 *斜体* / _下划线_ —— 它们和中文标点混在一起时容易误判，
 * 而模型回复里最常出现的就是加粗和工具名，先解决这两个。
 *
 * 做法：从左往右扫，每次找「离当前位置最近的标记」，处理完跳过，继续扫。
 * 单个标记没有配对（常见于流式中断）时原样输出，不吞字符。
 */
private fun inlineFormat(text: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var i = 0

    while (i < text.length) {
        val boldAt = text.indexOf("**", i)
        val codeAt = text.indexOf('`', i)

        // 两种标记都没有了 → 剩下的原样追加，结束
        if (boldAt < 0 && codeAt < 0) {
            builder.append(text.substring(i))
            break
        }

        // 谁的标记更靠前就先处理谁
        val useBold = when {
            boldAt < 0 -> false
            codeAt < 0 -> true
            else -> boldAt <= codeAt
        }
        val start = if (useBold) boldAt else codeAt

        if (start > i) builder.append(text.substring(i, start))

        if (useBold) {
            val end = text.indexOf("**", start + 2)
            if (end < 0) {
                builder.append(text.substring(start))
                break
            }
            builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(text.substring(start + 2, end))
            }
            i = end + 2
        } else {
            val end = text.indexOf('`', start + 1)
            if (end < 0) {
                builder.append(text.substring(start))
                break
            }
            // 行内代码：等宽 + 淡灰底，反引号本身不显示
            builder.withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = CodeInlineBackground
                )
            ) {
                append(text.substring(start + 1, end))
            }
            i = end + 1
        }
    }
    return builder.toAnnotatedString()
}

/**
 * Markdown 正文渲染。
 *
 * ★ 性能关键：用 remember(content) 缓存解析结果。
 *   内容不变就不会重新解析 —— 哪怕父组件因为别的原因重组了。
 */
@Composable
private fun MarkdownText(
    content: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val blocks = remember(content) { parseMarkdown(content) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inlineFormat(block.text),
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                )

                is MdBlock.Paragraph -> Text(
                    text = inlineFormat(block.text),
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium
                )

                is MdBlock.Code -> CodeBlock(block.code)
            }
        }
    }
}

/**
 * 代码块：深色底 + 描边 + 强制等宽 + 横向滚动。
 *
 * 底色刻意不跟随主题 —— GitHub / Codex 风格的代码块永远是深色。
 * 亮色主题下是"白气泡里的一块黑"，暗色主题下靠 #30363D 描边区分。
 */
@Composable
private fun CodeBlock(code: String) {
    Surface(
        color = CodeBlockBackground,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, CodeBlockBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = code,
            color = CodeBlockText,
            // ★ 强制等宽，不受主题 Typography 影响
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            // softWrap = false：代码不折行，改成横向滚动，保住缩进
            softWrap = false,
            modifier = Modifier
                .padding(10.dp)
                .horizontalScroll(rememberScrollState())
        )
    }
}


// ============================================================
// 6.2 工具调用卡片（Tool Calling UI 骨架）
//
// DSH 的灵魂就是工具调用：模型说「我要调用 read_file」，
// 界面得能看见这次调用、点开能看见传了什么参数。
//
// 数据约定：type == TOOL_CALL 时，message.content 存的是
//   {"tool":"read_file","arguments":{...}}
// 这样 ChatMessage 只需要多一个 type 列，
// 不用再加 toolName / toolArgs 两列（少一次迁移）。
// ============================================================

/** 卡片要显示的三样东西 */
private data class ToolCallView(
    val name: String,
    val argsText: String,
    val ok: Boolean?,   // null = 还在执行
    val output: String
)

/** 从 content 里解析出「工具名 + 美化后的参数 + 执行结果」 */
private fun parseToolCall(content: String): ToolCallView {
    return try {
        val root = JSONObject(content)
        val name = (root.opt("tool") as? String) ?: "unknown"

        val argsNode = root.opt("arguments")
        val argsText = when (argsNode) {
            is JSONObject -> argsNode.toString(2)     // 缩进 2 空格，可读性最好
            is String -> argsNode
            null, JSONObject.NULL -> "{}"
            else -> argsNode.toString()
        }

        val okNode = root.opt("ok")
        val ok = when (okNode) {
            true -> true
            false -> false
            else -> null                              // JSON null = 还在执行
        }

        ToolCallView(name, argsText, ok, (root.opt("output") as? String) ?: "")
    } catch (e: Exception) {
        // 不是合法 JSON 就原样显示，绝不让界面崩
        ToolCallView("unknown", content, null, "")
    }
}

/** 展开时最多显示多少字符的结果（完整内容已经发给模型了，这里只是别再压垮界面） */
private const val MAX_TOOL_OUTPUT_PREVIEW = 4000

@Composable
private fun ToolCallCard(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme

    // 折叠 / 展开。默认折叠，只占一行。
    var expanded by remember { mutableStateOf(false) }

    // 解析结果按 content 缓存，重组时不重复解析
    val view = remember(message.content) { parseToolCall(message.content) }

    val title = when (view.ok) {
        null -> "正在调用工具：${view.name}"
        true -> "已执行：${view.name}"
        false -> "执行失败：${view.name}"
    }

    // 状态点的颜色：执行中灰、成功绿、失败红
    val dotColor = when (view.ok) {
        null -> cs.onSurfaceVariant
        true -> Color(0xFF3FB950)
        false -> Color(0xFFF85149)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = glassFill(),
        border = BorderStroke(1.dp, glassEdge()),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(12.dp)
        ) {
            // ---------- 标题行 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🔧", fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 状态点
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "收起参数" else "展开参数",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // ---------- 展开：参数 + 结果 ----------
            if (expanded) {
                Spacer(Modifier.height(10.dp))

                Text(
                    text = "参数",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))

                CodeSurface(text = view.argsText)

                if (view.output.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (view.ok == true) "结果" else "错误",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    CodeSurface(
                        text = if (view.output.length > MAX_TOOL_OUTPUT_PREVIEW) {
                            view.output.take(MAX_TOOL_OUTPUT_PREVIEW) + "\n…（仅显示前 $MAX_TOOL_OUTPUT_PREVIEW 字）"
                        } else {
                            view.output
                        }
                    )
                }
            }
        }
    }
}

/** 卡片里的小代码块（深色底 + 等宽 + 横向滚动） */
@Composable
private fun CodeSurface(text: String) {
    Surface(
        color = CodeBlockBackground,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, CodeBlockBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = CodeBlockText,
            softWrap = false,
            modifier = Modifier
                .padding(10.dp)
                .horizontalScroll(rememberScrollState())
        )
    }
}


// ============================================================
// 6.5 InfoBar —— 本轮费用 + 上下文用量
//
// 数据全部来自 API 返回的 usage 字段（需要请求里带 stream_options.include_usage）。
// 费用按 DeepSeek 官方峰谷单价估算，见前面 3.25 节。
// ============================================================
@Composable
fun InfoBar(usage: UsageStats, isStreaming: Boolean) {
    val cs = MaterialTheme.colorScheme
    val cost = costCny(usage)
    val ctxPercent = usage.promptTokens.toDouble() / CONTEXT_WINDOW_TOKENS * 100

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "本轮 ≈ ${formatCny(cost)}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "上下文 ${formatTokens(usage.promptTokens)} / " +
                "${formatTokens(CONTEXT_WINDOW_TOKENS)}  " +
                String.format(Locale.US, "%.1f%%", ctxPercent),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (isStreaming) {
            Text(
                text = "生成中…",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = cs.primary
            )
        }
    }
}


// ============================================================
// 7. 底部输入区
// ============================================================
@Composable
fun InputBar(
    text: String,
    enabled: Boolean,
    isStreaming: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val canSend = text.isNotBlank() && enabled

    // 悬浮毛玻璃胶囊：半透明深灰底 + 半透明白色高光边框 + 微弱漫反射阴影
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        shape = RoundedCornerShape(30.dp),
        color = glassFill(),
        border = BorderStroke(1.dp, glassEdge()),
        shadowElevation = 8.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 内部只留文字。
            // 不用 OutlinedTextField 是因为它自带边框，会和外面这层 Surface 形成双重描边。
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurface
                ),
                cursorBrush = SolidColor(cs.primary),
                // 自适应高度：1 行起步，最多 4 行，超过就框内滚动
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = if (enabled) "输入消息…" else "正在回复…",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            )

            if (isStreaming) {
                // ★ 流式输出中：发送按钮变成「停止」——正圆底 + 正中间的方块
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(cs.primary)
                        .clickable { onStop() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(cs.onPrimary)
                    )
                }
            } else {
                // 正圆形发送按钮
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (canSend) cs.primary else cs.outline)
                        .clickable(enabled = canSend) { onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (canSend) cs.onPrimary else cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}


// ============================================================
// 8. 预览：只在 Android Studio 里显示，不装到手机
// ============================================================
@Preview(showBackground = true)
@Composable
fun MessageBubblePreview() {
    MyChatTheme {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MessageBubble(ChatMessage(sessionId = "preview", content = "你好，我是你的助手", isUser = false))
            MessageBubble(ChatMessage(sessionId = "preview", content = "帮我看一下这个病例", isUser = true))
        }
    }
}
