package com.example.mychat

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
// ★ 必须起别名：本文件里 Context 已经是 android.content.Context（几十处在用），
//   直接 import org.mozilla.javascript.Context 会变成「Conflicting import」，
//   而 KSP 只会报一个莫名其妙的 MissingType，把真正的错误盖住。
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.content.Context
import android.provider.OpenableColumns
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import java.util.Calendar
import javax.net.ssl.SSLException
import java.util.Locale
// 时间感知（把「现在是几点」告诉模型）要用。注意必须配 Locale.US，见 timeContextLine()
import java.text.SimpleDateFormat
import java.util.Date


// ============================================================
// ★★★ 配置区 ★★★
// ============================================================

// 接口地址和模型名不再是编译期常量 —— 用户在「设置 → 模型与 API」里填，
// 存加密存储，见后面的 ApiConfig。这里只留一句提醒：改的是运行期值。

/**
 * 系统提示词。
 *
 * ⚠️ 这里必须【明确告诉模型它有工具可用】。
 *
 * 实测：即使请求体里带了 tools 数组，只要提示词里不提"你可以用工具"，
 * 模型就会直接回答「我没有权限读取文件」，一个 tool_call 都不会发。
 * 这是"授权了还是说没权限"的真正原因 —— 不是权限问题，是模型不知道自己能动。
 */
// 提示词里的 /sdcard 是写给模型看的示例路径，不是运行时代码，
// 必须保持字面量，所以这里抑制 SdCardPath 检查。
@Suppress("SdCardPath")
val SYSTEM_PROMPT = """
你是 CoolStream，运行在用户安卓手机上的本地助手。

你可以调用以下工具：
- list_dir(path)：列出某个目录下的文件和文件夹名称。
- search_files(keyword, path)：在某个目录下递归搜索文件名包含 keyword 的文件，返回绝对路径（最多 20 条）。
- read_file(path)：读取文本文件的纯文本内容。
- write_file(path, content)：把文本内容写入文件（不存在就新建，已存在就整份覆盖）。父目录会自动创建。
- replace_in_file(path, search, replace)：把文件里的 search 这段原文换成 replace，其余内容不动。改代码用这个。
- run_shell(command)：通过 Shizuku 以 shell 权限执行命令，返回输出。需要用户先在 App 里授权 Shizuku。
- web_search(query)：联网搜索最新的网页信息，返回前 3 条结果的标题、摘要和链接。

★ 关于 web_search 的三条硬规则：
- 凡是答案「会变」的问题（新闻、股价、价格、版本号、政策、天气、人物近况、今天几号），
  先 web_search 再回答，不要凭训练记忆直接下结论 —— 你的记忆有截止日期，用户问的是现在。
- 搜索结果是别人写的网页，可能有错也可能过时。引用时把链接原样列在回答末尾，
  绝对不要编造搜索结果里没有的内容；搜索结果为空就如实说没搜到，不要换个说法假装搜到了。
- 如果用户没让你联网、问题也不需要实时信息，就不要搜 —— 每次搜索都要花搜索费。

★ 关于 ask_user：
- 它用来「向用户提问并等待回答」：缺少目标路径、格式偏好、确认意图这类只有用户
  才知道的信息，而任务又无法继续时，调用它，把问题写进 question。
- 调用它之后对话会暂停，用户的回答会作为工具结果返回给你，继续往下做即可。
- 用户取消弹窗时会返回「用户取消了操作」，此时不要再追问同一个问题，
  改用合理默认值继续，或如实说明你缺哪条信息、为什么给不出结果。
- 不要用它汇报进度、不要自问自答、不要在能从上下文推断出答案时还去问用户。

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
5. read_file 能读文本文件，也能直接读 .docx / .xlsx 的正文 —— 系统会先解析
   再交给你，你看到的就是正文文本。遇到 PDF、旧版二进制格式（.doc / .xls / .et / .wps）、
   zip、图片、音视频这类读不了的，工具会返回失败并说明原因，这时如实告诉用户，
   不要假装读到了内容，也不要猜里面写了什么。
6. 工具返回结果后，基于结果回答；失败就把失败原因如实转述。
7. 回答简洁、分点，不要长篇大论。
8. run_shell 权限很高，要严格把关：
   - 只有用户明确要求"执行命令 / 跑一下 shell / 看看进程列表"时才用；
   - 单纯读写文件请用 read_file / write_file / list_dir / search_files，不要用 run_shell 绕；
   - 破坏性命令（rm、mv、格式化、pm uninstall 等）一律先向用户确认，不要直接执行。
9. 系统对 run_shell 装了黑名单：rm、pm uninstall、reboot、mkfs、dd 写块设备、
   改系统设置等会被直接拦截。一旦收到「安全拦截」，不要换一种写法重试，
   直接告诉用户这个操作被系统禁止了。
10. write_file 会把 content 原样写入（UTF-8），已存在的文件会被整份覆盖，不是追加：
   - 用户说「新建 / 写一份 / 生成 / 保存成文件」时才用；
   - 覆盖一个已存在的文件之前，先用 read_file 看清原内容，避免把用户的东西冲掉；
     如果原文件很重要而你不确定，就先问用户一句再写；
   - 只能写纯文本。生成 pptx / docx / xlsx / 图片 / 压缩包等二进制文件不是它能做的事，
     遇到这种要求要如实说明，不要假装写成功了；
   - 写完把最终路径和字节数如实报告，不要说"应该已经写好了"这种含糊话。
11. 改已有文件里的某一段，优先用 replace_in_file，不要用 write_file 整篇重写
   —— 整篇重写会把用户其它没提到的内容一起覆盖掉，是事故高发区：
   - search 必须从 read_file 的结果里【逐字复制】，包括缩进、换行、括号、全角半角标点。
     你凭印象手打的 search 基本一定匹配不上；
   - search 前后各带一两行上下文，让它在文件里唯一。只写 "return" 这种太短的一定会撞；
   - 工具回「找不到这段原文」时，不要猜着改，重新 read_file 看一眼再试；
   - 工具回「出现了 N 次」时，把 search 加长到能区分这几处，不要图省事开 replace_all
     一次改一片 —— 除非用户明确说"全部替换"；
   - 改完可以再 read_file 看一眼确认结果，然后把改了哪几行如实告诉用户。
""".trim()

/**
 * 实际发给模型的系统提示词 = 预设基础提示词 + 当前运行时的约束。
 *
 * 工作区和工具开关都是**运行时**才确定的（用户在设置页里选的），
 * 没法写死在常量里，所以每次请求前拼上去。
 */
private fun systemPromptWithWorkspace(): String {
    val enabled = ALL_TOOL_NAMES.filter { it in AppSettings.enabledTools }
    val disabled = ALL_TOOL_NAMES.filter { it !in AppSettings.enabledTools }
    val wsLine = workspaceRuleLine()

    return when (AppSettings.preset) {
        // ---------- 极简：一个工具都没有，只留一句工作区说明是多余的，直接返回 ----------
        AgentPreset.MINIMAL -> MINIMAL_SYSTEM_PROMPT

        // ---------- PTC：只有 run_code，但要告诉它沙箱里哪些函数是关着的 ----------
        AgentPreset.PTC -> buildString {
            append(PTC_SYSTEM_PROMPT)
            append("\n\n补充约束：\n")
            if (wsLine.isNotEmpty()) append("1. ").append(wsLine).append('\n')
            if (disabled.isNotEmpty()) {
                append("2. 用户在设置里关闭了这些工具：").append(disabled.joinToString("、"))
                    .append("。在代码里调用它们会直接返回 ok=false，不要写进代码，")
                    .append("确实需要就让用户去「设置 → 插件」里打开。")
            }
        }

        // ---------- 标准：原样保留，一个字符都不改 ----------
        AgentPreset.STANDARD -> {
            // ★ 工具开关是运行时状态，拼进来让模型知道「哪些工具现在真的能用」。
            //   只删 tools 数组、不提一句的话，模型会照旧调用已被关掉的工具，
            //   然后一直收到「未知工具」，白烧好几轮。
            val toolLine = if (enabled.isEmpty()) {
                "当前可用工具：无（用户在设置里把工具全关了）。你只能凭对话回答，" +
                    "绝不要声称你读过、写过或改过任何文件。"
            } else {
                "当前可用工具：${enabled.joinToString("、")}。" +
                    "不在这份清单里的工具已被用户在设置里关闭，不要调用，也不要假装调用过；" +
                    "如果当前任务确实需要某个被关掉的工具，如实告诉用户去「设置 → 插件」里打开它。"
            }
            "$SYSTEM_PROMPT\n\n12. $toolLine\n13. $wsLine"
        }
    }
}

/** 工作区那一条约束。未设置时返回空串（PTC 模式下直接跳过这一条） */
private fun workspaceRuleLine(): String {
    val ws = currentWorkspace
    return if (ws.isBlank()) {
        "用户还没有设置工作区，暂时不限制路径范围。"
    } else {
        "你的工作区是：$ws\n" +
            "    所有工具操作（读文件、写文件、改文件、列目录、搜索、shell）都必须严格限制在这个目录下，严禁越界。\n" +
            "    越界的操作会被代码层直接拦截并返回「工作区越界」，不要换路径反复重试。"
    }
}

// ============================================================
// 3.21 时间感知：把「现在几点」告诉模型
//
// 模型的训练数据有截止日期，它不知道今天几号 —— 你问「今天有什么新闻」，
// 它会用训练时的"今天"来回答，而且说得跟真的一样。
// 解决办法不是让它猜，而是在每次请求的系统提示词末尾塞一句真实时间。
//
// ★ 三个必须遵守的细节：
//   1. 必须是 SimpleDateFormat(pattern, Locale.US)。
//      只写 pattern 的话，某些系统语言下会输出本地数字（阿拉伯-印度数字等
//      非 ASCII 字符），模型读到的就不是 2026-10-23 了，而是 ٢٠٢٦-١٠-٢٣。
//   2. 时间要放在提示词【最末尾】，不要放开头。
//      DeepSeek 的上下文缓存是按前缀命中的：时间一变整段就变，放开头等于
//      每次都让缓存全部失效，输入费用从 0.02 涨到 1 元/百万 token（差 50 倍）。
//      放末尾只让最后几十个 token 失效，前面的提示词照样命中缓存。
//   3. 只在「发请求前」取一次，不要放进每 token 的热路径。
//      这个函数每次请求调用一次，代价是微秒级；放进流式循环里就是浪费。
// ============================================================

/**
 * 拼在系统提示词末尾的那一行时间。
 *
 * 输出形如：
 * ```
 * 当前系统时间：2026-10-23 15:33。请以此时间为准进行推理。
 * （星期：星期五；时区：GMT+08:00）
 * ```
 *
 * 用 internal 而不是 private：单元测试（app/src/test）要断言它的格式 ——
 * 时间格式一旦写错（比如漏了 Locale.US 变成阿拉伯数字），肉眼很难发现。
 */
internal fun timeContextLine(): String {
    // ★ 只取一次 Date，两次 format 之间不能跨过整分钟，否则会出现
    //   「15:33」和「15:34」同时出现的一瞬间。
    val now = Date()
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now)
    // 星期几用中文输出：「星期五」比「Friday」对模型更友好（提示词是中文的）
    val week = SimpleDateFormat("EEEE", Locale.SIMPLIFIED_CHINESE).format(now)
    val tz = java.util.TimeZone.getDefault()
        .getDisplayName(false, java.util.TimeZone.SHORT, Locale.US)

    return "当前系统时间：$time。请以此时间为准进行推理。\n" +
        "（星期：$week；时区：$tz。涉及「今天 / 现在 / 最新」的问题，" +
        "一律以上面这行时间为基准；需要实时信息时用 web_search 查，不要凭记忆编造。）"
}


/** 日志标签，Logcat 里用 tag:MyChatPerf 过滤 */
internal const val TAG = "MyChatPerf"

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
internal val httpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()


// ============================================================
// 0. 存储权限（Agent 的 read_file 工具需要）
//
// MANAGE_EXTERNAL_STORAGE 不是普通的运行时权限：
// 在 Manifest 里声明只是「有资格申请」，真正的开关在系统设置里，
// 必须跳到 Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 让用户手动打开。
// 所以在 App 启动时要检查一次，没开就弹窗引导。
//
// ★★ 这里是全项目最容易崩的地方，改动前务必读完 ★★
//
//   Environment.isExternalStorageManager() 是 API 30（Android 11）才加的方法。
//   minSdk 是 24 —— 在 Android 7~10 的机器上直接调它会抛
//   NoSuchMethodError，而且是在 Application 启动路径上，
//   表现就是「一打开就闪退」，堆栈还指不到业务代码，极难定位。
//
//   所以：所有调用点都必须先判 SDK_INT >= R。
//   为了不让人漏掉，统一走下面这两个函数，不要在外面直接调 Environment。
//
//   Android 10 及以下没有「所有文件访问权限」这个概念，
//   它们走的是老的运行时权限模型：WRITE_EXTERNAL_STORAGE。
// ============================================================

/**
 * 有没有「能读写整个外部存储」的权限。
 *
 * - Android 11+：查 MANAGE_EXTERNAL_STORAGE（所有文件访问权限）
 * - Android 10 及以下：查 WRITE_EXTERNAL_STORAGE 运行时权限
 *   （这些系统上 Manifest 里的 maxSdkVersion 限定让它只在老系统生效）
 *
 * ⚠️ 这个函数在 Application 启动路径上被调用，绝不能抛异常。
 */
private fun hasAllFilesAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Android 11+：系统级开关，只能由用户在设置里打开
        Environment.isExternalStorageManager()
    } else {
        // Android 10 及以下：老存储模型，查运行时权限。
        // 用 ContextCompat 而不是 context.checkSelfPermission()，
        // 后者本身也是 API 23+ 的方法（虽然 minSdk 24 够用，
        // 但 ContextCompat 在部分定制 ROM 上更稳，且能统一处理权限被撤销的情况）。
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

private fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    // ★ 必须加 FLAG_ACTIVITY_NEW_TASK。
    //
    //   这里传进来的 context 是【不是 Activity】的 Context ——
    //   界面层用的是 withAppLocale() 包装过的 createConfigurationContext()，
    //   它没有 Activity 的任务栈，直接 startActivity 会抛：
    //     AndroidRuntimeException: Calling startActivity() from outside of
    //     an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag
    //
    //   这个坑在模拟器上测不出来（模拟器某些路径拿到的是 Activity context），
    //   一到真机点「授予文件访问权限」就必崩回桌面。
    //
    //   加了 NEW_TASK 之后，系统会从应用的任务栈起一个新的设置页，
    //   返回时仍能回到我们的 App（因为都没设 CLEAR_TASK）。
    //
    // 返回 true = 这个 intent 已经被成功派发；false = 当前 ROM 不认，试下一级。
    fun fire(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        // 最常见的一级失败：某些 ROM 没有这个 Activity，
        // 或者带 package 的那个 action 不被支持。
        false
    } catch (e: Exception) {
        // 极少数情况（权限被系统策略拦截等）：记一笔，别让用户看到闪退。
        Log.w(TAG, "打开权限设置页失败: ${e.javaClass.simpleName}", e)
        false
    }

    // ① 带包名，直跳本应用的「所有文件访问」页（用户少点两下）
    if (fire(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData("package:${context.packageName}".toUri())
        )
    ) return

    // ② 全局「所有文件访问」列表，用户自己找本应用
    if (fire(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))) return

    // ③ 最后的退路：应用详情页。虽然要多点两下，但至少能手动找到权限开关，
    //    总比点了没反应强。
    fire(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData("package:${context.packageName}".toUri())
    )
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
internal fun isShizukuRunning(): Boolean = try {
    Shizuku.pingBinder()
} catch (_: Exception) {
    false
}

/** 是否已经拿到 Shizuku 授权 */
internal fun isShizukuGranted(): Boolean = try {
    Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
} catch (_: Exception) {
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
                    onError("还没有配置 API Key。\n请到「设置 → 模型与 API」里填入，或者点这里重新走一遍引导。")
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
                    // 带附件的消息在这里展开成数组 / 追加 read_file 指令
                    put("content", contentForApi(msg))
                })
            }

            Log.d(TAG, "history packed: ${messagesArray.length()} messages (skipped $skippedError error bubbles)")

            val bodyJson = JSONObject().apply {
                put("model", ApiConfig.model)
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
                .url(chatCompletionsUrl())
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
                withContext(Dispatchers.Main) { onError(httpErrorMessage(response.code, errBody)) }
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
            withContext(Dispatchers.Main) { onError(networkErrorMessage(e)) }
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
internal fun parseDelta(json: String): String? {
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

    } catch (_: Exception) {
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
internal data class ToolCallPiece(
    val index: Int,
    val id: String?,
    val name: String?,
    val arguments: String?
)

internal data class DeltaInfo(
    val content: String?,               // 正文分片（可能为空）
    val toolCalls: List<ToolCallPiece>  // 本次分片里的工具调用片段
)

internal fun parseDeltaFull(json: String): DeltaInfo? {
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
    } catch (_: Exception) {
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
private const val TOOL_DEFINITIONS = """
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
      "name": "write_file",
      "description": "把文本内容写入一个文件：文件不存在就新建，已存在就整份覆盖。用于创建或修改笔记、代码、Markdown、配置等纯文本文件。不能写二进制文件。",
      "parameters": {
        "type": "object",
        "properties": {
          "path": {
            "type": "string",
            "description": "要写入的文件绝对路径，必须带文件名，例如 /sdcard/Documents/notes.md。父目录不存在会自动创建。"
          },
          "content": {
            "type": "string",
            "description": "要写入的完整文本内容（UTF-8）。注意这是整份内容，不是追加到原文件后面。"
          }
        },
        "required": ["path", "content"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "replace_in_file",
      "description": "在文件里做局部替换：把 search 这段原文换成 replace，文件其余部分原样保留。改代码、改配置用这个，不要用 write_file 整篇覆盖。",
      "parameters": {
        "type": "object",
        "properties": {
          "path": {
            "type": "string",
            "description": "要修改的文件绝对路径，例如 /sdcard/Documents/notes.md"
          },
          "search": {
            "type": "string",
            "description": "要被替换掉的原文，必须和文件里的内容一字不差（包括缩进、换行、标点）。建议前后各带一两行上下文，保证这段文本在文件里是唯一的。"
          },
          "replace": {
            "type": "string",
            "description": "替换成的新文本。如果只是想删掉 search 这段，传空字符串即可。"
          },
          "replace_all": {
            "type": "boolean",
            "description": "search 在文件里出现多次时：true = 全部替换；false 或不传 = 出现多次就报错、不做任何修改。默认为 false。"
          }
        },
        "required": ["path", "search", "replace"]
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
  },
  {
    "type": "function",
    "function": {
      "name": "web_search",
      "description": "联网搜索网页，返回最相关的 3 条结果（标题 + 摘要 + 链接）。用于获取你的训练数据里没有的实时信息：今天的新闻、当前股价、最新版本号、政策变化、天气、某个人最近在做什么。凡是答案可能已经过时的问题，先搜再答。",
      "parameters": {
        "type": "object",
        "properties": {
          "query": {
            "type": "string",
            "description": "搜索关键词，1~128 个字符。写关键词，不要贴整段问题。支持检索运算符：site:example.com（限定站点）、-site:example.com（排除站点）、\"精确短语\"、-词（排除词）、lang:zh,en（限定语言）、after:2026-01-01（限定发布时间下限）、before:2026-06-01（上限），多个运算符可组合，例如 DeepSeek 发布 site:github.com lang:en after:2026-09-01"
          }
        },
        "required": ["query"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "ask_user",
      "description": "向用户提出一个问题并等待回答。当你缺少关键信息（目标路径、格式偏好、确认意图等）而无法继续时调用它；question 写一句话、说清需要用户提供什么。用户回答后你会收到答案并继续；用户取消则返回「用户取消了操作」。不要用它汇报进度或自问自答。",
      "parameters": {
        "type": "object",
        "properties": {
          "question": {
            "type": "string",
            "description": "要问用户的问题，一句话，1~500 个字符"
          }
        },
        "required": ["question"]
      }
    }
  }
]
"""

/**
 * 一次工具执行的结果。
 *
 * 用 internal 而不是 private：单元测试（app/src/test）要断言它。
 * Kotlin 的 private 是「文件内可见」，测试类在别的文件里，够不着。
 */
/**
 * 一条「还没渲染成文字」的错误。
 *
 * ★ 为什么不用 String：
 *   错误文案现在要跟着语言走，而拼文案需要 Context.getString(resId, args)。
 *   但 httpErrorMessage / networkErrorMessage 是在 IO 协程里被调用的，
 *   那里既不是 Composable、也不该把 Context 拖进纯函数。
 *
 *   所以这里只记「资源 id + 参数」，等到要上屏的那一刻（主线程、手里有
 *   localizedContext）再调 resolve() 变成字符串。
 *
 *   好处：
 *     · 纯数据，可以安全跨线程传递
 *     · 单元测试里可以直接断言 resId，不用碰 Android 资源
 *     · 语言切换后旧错误气泡不会「半中半英」
 */
data class ErrorText(val resId: Int, val args: List<Any> = emptyList())

/**
 * 把 ErrorText 渲染成当前语言的文案。
 *
 * 注意：传进来的 context 必须已经过 withAppLocale 处理，
 * 否则 App 内切换语言后，错误气泡会跟界面语言不一致。
 */
fun ErrorText.resolve(context: Context): String {
    val arr: Array<out Any> = args.toTypedArray()
    return context.getString(resId, *arr)
}

/**
 * 把 HTTP 状态码翻译成用户能照着做的话。
 *
 * ★ 为什么要单独做这件事：
 *   原来界面上只显示「HTTP 401」，用户看到这四个字符完全不知道该干嘛。
 *   401 和 429 是最常见的两种，必须给出下一步动作。
 *
 * ★ 返回 ErrorText 而不是 String：见 ErrorText 的注释。
 */
fun httpErrorText(code: Int, body: String): ErrorText {
    return when (code) {
        401 -> ErrorText(R.string.err_401)
        402 -> ErrorText(R.string.err_402)
        403 -> ErrorText(R.string.err_403)
        404 -> ErrorText(R.string.err_404)
        429 -> ErrorText(R.string.err_429)
        in 500..599 -> ErrorText(R.string.err_5xx, listOf(code))
        else -> {
            val hint = body.take(200).trim()
            if (hint.isEmpty()) {
                ErrorText(R.string.err_http, listOf(code))
            } else {
                ErrorText(R.string.err_http_body, listOf(code, hint))
            }
        }
    }
}

/**
 * 把网络异常翻译成人话。
 *
 * 这些异常用户一天能遇到好几次（地铁里、电梯里、切飞行模式），
 * 每一种都要能自己判断「是我网不好，还是 App 挂了」。
 */
fun networkErrorText(e: Throwable): ErrorText {
    return when (e) {
        is UnknownHostException -> ErrorText(R.string.err_no_network)
        is SocketTimeoutException -> ErrorText(R.string.err_timeout)
        is SSLException -> ErrorText(R.string.err_ssl)
        is IOException ->
            ErrorText(R.string.err_io, listOf(e.message ?: e.javaClass.simpleName))
        else -> ErrorText(
            R.string.err_unknown,
            listOf(e.javaClass.simpleName, e.message ?: e.javaClass.simpleName)
        )
    }
}

/**
 * ⚠️ 下面这两个是【退化路径 sendToDeepSeek】专用的旧实现，故意保留。
 *
 * 铁律 3：sendToDeepSeek 一个字不改。它现在没有任何调用点，
 * 但仍保留作参照。它的 onError 收的是 String，所以这里继续返回中文。
 * 真正在跑的 runAgentLoop 已经改用上面的 httpErrorText / networkErrorText。
 */
private fun httpErrorMessage(code: Int, body: String): String {
    return when (code) {
        401 -> "API Key 无效或已欠费（401）。\n请到「设置 → 模型与 API」检查 Key 是否填错、账户是否还有余额。"
        402 -> "账户余额不足（402）。\n请到 DeepSeek 开放平台充值后再试。"
        403 -> "没有访问权限（403）。\n这个 Key 可能被限制访问该模型。"
        404 -> "接口地址不对（404）。\n检查「设置 → 模型与 API」里的 API 地址。"
        429 -> "请求太频繁，请休息一下（429）。\n等几十秒再发，或者换到低峰时段。"
        in 500..599 -> "服务器出问题了（$code）。\n不是你的操作问题，过一会儿再试。"
        else -> {
            val hint = body.take(200).trim()
            if (hint.isEmpty()) "HTTP $code" else "HTTP $code\n$hint"
        }
    }
}

private fun networkErrorMessage(e: Throwable): String {
    return when (e) {
        is UnknownHostException ->
            "连不上网络。\n检查 Wi-Fi 或移动数据是否正常；如果开了代理/VPN，先关掉再试。"
        is SocketTimeoutException ->
            "请求超时。\n网络太慢或服务器没响应，换个网络或稍后重试。"
        is SSLException ->
            "安全连接失败（SSL）。\n多半是代理或证书在中间拦截，关掉代理再试。"
        is IOException ->
            "网络异常：${e.message ?: e.javaClass.simpleName}"
        else ->
            "出错了：${e.javaClass.simpleName}: ${e.message ?: "未知原因"}"
    }
}


internal data class ToolExecResult(val ok: Boolean, val output: String)

/**
 * 最多几轮工具调用（防止模型无限调用把自己转晕）。
 *
 * ★ 这个数字是「请求次数」不是「工具次数」：
 *   每一轮 = 带着全部上下文发一次请求。模型在某轮里发起工具调用，我们执行完、
 *   把结果回填，然后再发下一轮。
 *
 *   典型链路要烧掉几次：
 *     search_files → read_file → replace_in_file → 最终回答  = 4 轮
 *   如果模型还要 read_file 复查一遍结果，就是 5 轮 —— 正好顶到原来的上限，
 *   一点余量都没有，稍微多一步就被掐断。所以抬到 8。
 */
private const val MAX_TOOL_ROUNDS = 8

/**
 * 单个文件最大读取字节数：5 MB。
 *
 * 抬到 5MB 是为了能读长文本 / 日志 / 代码。
 * 仍然保留上限，是因为一次性 readBytes() 太大容易 OOM ——
 * 超过就明确告诉模型"文件太大"，比让它把 App 搞崩要好。
 */
internal const val MAX_FILE_BYTES = 5_242_880L

/**
 * 单次 write_file 允许写入的最大字节数：2 MB。
 *
 * 比读取上限小，是因为模型一次输出 2MB 文本几乎不可能（上下文窗口也不够），
 * 真到这个量级基本是模型把 content 参数写歪了。拦下来比让 App 卡死好。
 */
internal const val MAX_WRITE_BYTES = 2_097_152L

/**
 * 覆盖已有文件时，原文件超过这个大小就不留 .bak 备份。
 *
 * 备份是为了防止模型手滑把用户文件冲掉；但原文件很大时再复制一份
 * 会让磁盘占用翻倍，所以只在 1MB 以内备份。
 */
internal const val MAX_BACKUP_BYTES = 1_048_576L

/** 单次 search_files 最多返回多少条匹配 */
internal const val MAX_SEARCH_HITS = 20

/** 单次 search_files 最多访问多少个文件（防止在 /sdcard 里扫到天荒地老） */
internal const val MAX_SEARCH_VISITS = 50_000

/** 递归搜索的最大目录深度 */
internal const val MAX_SEARCH_DEPTH = 12

/** 单次 list_dir 最多返回多少条 */
internal const val MAX_LIST_ENTRIES = 300

/** 工具执行的独立日志标签。Logcat 里过滤 tag:ToolExec 就能只看工具相关的行 */
internal const val TOOL_LOG_TAG = "ToolExec"

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
internal fun argOf(argsJson: String, key: String): String {
    return try {
        (JSONObject(argsJson).opt(key) as? String) ?: ""
    } catch (e: Exception) {
        Log.e(TOOL_LOG_TAG, "参数解析失败（key=$key）: ${e.message}", e)
        ""
    }
}

/**
 * 从参数 JSON 里安全取一个布尔值。
 *
 * 模型有时会把 true 写成字符串 "true"，这里只认真正的 Boolean，
 * 其余一律当 false —— 对 replace_all 这种"放权"型参数，宁可保守。
 */
internal fun argBoolOf(argsJson: String, key: String): Boolean {
    return try {
        (JSONObject(argsJson).opt(key) as? Boolean) ?: false
    } catch (e: Exception) {
        Log.e(TOOL_LOG_TAG, "参数解析失败（key=$key）: ${e.message}", e)
        false
    }
}

/**
 * 把模型给的路径清洗干净。
 *
 * 实测：用户输入里常带中文双引号 “ ”，模型会把它一起塞进 path。
 * 这里再兜一层 —— 把首尾的中英文标点和空白全部削掉。
 */
internal fun cleanPath(raw: String): String {
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
internal var currentWorkspace: String = ""

private fun loadWorkspace(context: Context): String {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_WORKSPACE, "")
        ?: ""
}

private fun saveWorkspace(context: Context, path: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit { putString(KEY_WORKSPACE, path) }
    currentWorkspace = path
}

// ============================================================
// 3.24 用户偏好：外观 / 字号 / 工具开关
//
// 这些偏好的共同点是「影响全局、而且要在 Compose 里能被观察到」。
// 所以统一放进 AppSettings 这个对象，内部用 mutableStateOf 托着 ——
// 一改就会触发重组，不需要在界面里手动刷新。
//
// 读写仍然走 SharedPreferences（重启后还在），AppSettings 只是内存镜像。
// ============================================================

/** 七个手写工具的标识，顺序即设置页里的显示顺序，也是发给模型时的顺序 */
internal val ALL_TOOL_NAMES = listOf(
    "list_dir", "search_files", "read_file", "write_file", "replace_in_file", "run_shell",
    // ★ v1.1 新增。放在最后不是随便排的：前六个都是「动手机上的东西」，
    //   只有它一个是要花钱联网的，单独排在末尾，设置页里一眼就能看出区别。
    "web_search",
    // ★ v1.1 阶段三新增。排 web_search 后面：它不碰手机、也不花钱，
    //   但要「暂停等用户输入」，性质和前七个都不同，放在最后单独一组。
    "ask_user"
)

/** 每个工具在设置页里的一句话说明（写给用户看，不是写给模型看） */
private val TOOL_DESCRIPTIONS = mapOf(
    "list_dir" to "列出目录里有什么",
    "search_files" to "按文件名递归搜索",
    "read_file" to "读取文本内容（.docx / xlsx 会先解析正文）",
    "write_file" to "新建文件，或整份覆盖已有文件",
    "replace_in_file" to "局部替换文件里的某段文本（改代码用这个）",
    "run_shell" to "通过 Shizuku 执行 shell 命令，权限很高",
    // ★ 说明里要写清「要花钱」，否则用户会以为和别的工具一样是免费的本地操作。
    //   官方单价：每 1000 次成功请求 5 元（失败和空结果不计费）。
    "web_search" to "联网搜索网页（走 DeepSeek 官方搜索接口，每次约 ¥0.005）",
    "ask_user" to "向用户提出问题并等待回答（不花钱，会暂停对话）"
)

// ============================================================
// 3.24a Agent 预设模式
//
// 同一个模型，换一种「工具怎么交给它」的方式，行为差别很大。
// 三种预设的本质区别只有两件事：**tools 数组里放什么** 和 **系统提示词怎么写**。
//
//   standard（标准）
//     七个工具逐个作为 function 定义发过去，模型一次挑一个调。
//     最稳、最好调试，出问题一眼能看出是哪一步。
//
//   ptc（Programmatic Tool Calling）
//     只发一个 run_code 工具。模型写一段 JavaScript，在代码里连续调用工具 ——
//     能循环、能分支、能一次读十个文件再汇总，省掉「一次只能调一个」的往返。
//     概念对齐 DSH 的 PTC 预设（其原文：「标准模式直接向模型提供各个工具；
//     PTC 让模型用代码组织工具调用」），只是把它的 TS 运行时换成了 Rhino。
//
//   minimal（极简）
//     一个工具都不发，系统提示词压到最短，纯对话。
//     用来对比「不挂工具时模型的回答质量」，也方便临时省 token。
//
// ⚠️ 预设只改变「怎么把工具交给模型」，不改变工具本身的行为；
//    每个工具干的活、工作区限制、shell 黑名单，三种模式下完全一致。
// ============================================================
enum class AgentPreset(val id: String) {
    STANDARD("standard"),
    PTC("ptc"),
    MINIMAL("minimal");

    companion object {
        /** 从持久化的字符串还原；认不出来就退回标准模式，绝不让 App 起不来 */
        fun of(id: String?): AgentPreset =
            entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

/**
 * PTC 模式的 tools 数组 —— 只有一个 run_code。
 *
 * ★ description 写这么长是有原因的：模型必须**准确知道** JS 里有哪些函数、
 *   参数是什么、返回值长什么样，否则会写出一堆调不存在的 API 的代码。
 *   这本质上是一份塞进 schema 里的 SDK 文档。
 */
private val PTC_TOOL_DEFINITION = """
{
  "type": "function",
  "function": {
    "name": "run_code",
    "description": "用 JavaScript 组织多次工具调用，在一段代码里连续完成多个步骤。适合需要循环、条件判断、批量处理的任务（一次读多个文件、按内容筛选、连续改写多处）。沙箱里没有网络、没有 Java 类、不能用 require/import，只能调用 tools 对象上的方法。每次调用返回 {ok: boolean, output: string}，务必检查 ok 再决定下一步。最后一行表达式的值会作为返回值。用 print(...) 打印调试信息。",
    "parameters": {
      "type": "object",
      "properties": {
        "code": {
          "type": "string",
          "description": "要执行的 JavaScript 源码。\n★ 这个引擎是老式 JS，以下写法【不要用】，否则报错或结果错误：\n  - 模板字符串插值（`a${'$'}{x}` 不会替换，会原样保留）→ 用 'a' + x\n  - for (const x of arr) 里的 const → 用 let 或 var\n  - 展开运算符 ...、可选链 ?.、空值合并 ??\n可放心使用：let / const 声明、箭头函数、解构、for (let ... of ...)、Map / Set、includes、padStart、以及所有数组方法。\nAPI：\n  tools.listDir(path)                          -> {ok, output}\n  tools.searchFiles(keyword, path)             -> {ok, output}\n  tools.readFile(path)                         -> {ok, output}\n  tools.writeFile(path, content)               -> {ok, output}\n  tools.replaceInFile(path, search, replace, replaceAll) -> {ok, output}\n  tools.runShell(command)                      -> {ok, output}\n  tools.webSearch(query)                       -> {ok, output}（联网搜索，前 3 条结果）\n另有 print(...)。示例：\nconst r = tools.readFile('/sdcard/Documents/a.txt');\nif (!r.ok) { print('读失败: ' + r.output); }\nelse { print('长度 ' + r.output.length); }\nr.ok"
        }
      },
      "required": ["code"]
    }
  }
}
""".trim()

/** PTC 模式的系统提示词。工具清单在这里，不在「规则」里 —— 混着写模型容易漏 */
// 提示词里的 /sdcard 是写给模型看的示例路径，不是运行时代码，
// 必须保持字面量，所以这里抑制 SdCardPath 检查。
@Suppress("SdCardPath")
private val PTC_SYSTEM_PROMPT = """
你是 CoolStream，运行在用户安卓手机上的本地助手。

★ 当前是【PTC 模式】：你手上只有一个工具 run_code。
   你不能直接调用 read_file 这些工具，只能写一段 JavaScript，
   在代码里通过 tools 对象去调用它们。代码在你的手机本地、一个隔离沙箱里执行。

沙箱里可用的 API（全部同步、返回 {ok, output}）：
  tools.listDir(path)
  tools.searchFiles(keyword, path)
  tools.readFile(path)
  tools.writeFile(path, content)
  tools.replaceInFile(path, search, replace, replaceAll)
  tools.runShell(command)
  tools.webSearch(query)        // 联网搜索，返回前 3 条结果的标题+摘要+链接
  print(...)                    // 打印到执行输出，方便你看中间结果

写法要求：
1. 每个调用都要检查 r.ok。失败时 r.output 里是原因，不要当作成功继续往下算。
2. 不要用 require / import / fetch / XMLHttpRequest / Packages / java ——
   沙箱把这些全封了，用到就会报错。
3. ★ 这个 JS 引擎偏老，下面这几样【不能用】：
   - 模板字符串插值 `` `a${'$'}{x}` ``：不报错，但 ${'$'}{} 不会被替换，会原样留着 → 用 'a' + x 拼接
   - for (const x of arr)：const 当循环变量会报语法错误 → 用 for (let x of arr)
   - 展开 ...、可选链 ?.、空值合并 ??
   能放心用的：let / const 声明、箭头函数、解构、forEach / map / filter、
   for (let ... of ...)、Map / Set、字符串 includes / padStart。
4. 一段代码做完一件事。需要看结果再决定的，就在代码里 if 判断，
   不要拆成两次 run_code 来回问 —— 那正是不用 PTC 的理由。
5. 最后一行放一个表达式作为返回值（比如最后的统计结果对象）。
6. 代码要短。十几行能搞定的别写五十行。

规则：
1. 用户要求查看、读取、打开某个文件时，直接读，绝对不要说"我没有权限"
   "我无法访问你手机上的文件"之类的话。
2. 路径必须是绝对路径。从用户话语里提取路径时，去掉所有中文标点
   （中文双引号、单引号、逗号、句号、空格都不能带进路径）。
3. 绝对不要凭空编造路径。读不到就如实说没读到，不要猜内容。
4. read_file 只能读文本。pptx / docx / zip / 图片等二进制文件会失败，
   这时如实说"这是二进制文件，读不了内容"，不要假装读到了。
5. 工具报错就如实转述错误原因，不要粉饰。
6. 回答简洁、分点，不要长篇大论。
7. run_shell 权限很高：只有用户明确要求执行命令时才用；破坏性命令先问用户。
8. write_file 是整份覆盖而不是追加。覆盖已存在的文件前先读一遍，
   内容重要就先问用户一句。
9. 改已有文件的某一段用 tools.replaceInFile，search 必须从刚才读到的内容里
   【逐字复制】。凭印象手打的一定匹配不上。
""".trim()

/**
 * 极简模式的系统提示词 —— 故意写得很短。
 *
 * 这段提示词的作用是「不误导」：既然一个工具都没有，
 * 就必须明确说清楚，否则模型会沿用训练里的习惯，声称自己去读文件了。
 */
// 提示词里的 /sdcard 是写给模型看的示例路径，不是运行时代码，
// 必须保持字面量，所以这里抑制 SdCardPath 检查。
@Suppress("SdCardPath")
private val MINIMAL_SYSTEM_PROMPT = """
你是 CoolStream，运行在用户安卓手机上的对话助手。

★ 当前是【极简模式】：你没有任何工具，不能读文件、不能写文件、
   不能执行命令、不能搜索手机上的内容。

因此：
- 需要文件内容才能回答时，如实说"极简模式下我没有文件访问能力，
  请切换到标准模式"，然后把你能凭常识回答的部分讲清楚；
- 绝对不要假装读过某个文件，也不要编造文件内容；
- 回答简洁准确，不知道就说不知道。
""".trim()

private const val KEY_THEME_MODE = "theme_mode"      // system / light / dark
private const val KEY_PRESET = "agent_preset"        // standard / ptc / minimal
private const val KEY_FONT_SCALE = "font_scale"      // 0.9 / 1.0 / 1.15
private const val KEY_CARD_EXPANDED = "tool_card_expanded"
private const val KEY_TOOL_PREFIX = "tool_enabled_"
private const val KEY_LANGUAGE = "language"   // system / zh / en

/**
 * 全局设置的内存镜像。
 *
 * ⚠️ 用 mutableStateOf 而不是普通变量：主题、字号、工具开关都会影响界面，
 *    必须是「可被 Compose 观察」的，否则改了设置要重启才生效。
 */
internal object AppSettings {
    /** system / light / dark */
    var themeMode by mutableStateOf("system")

    /** 字号倍率，只缩放文字，不缩放 dp */
    // ★ 用 mutableFloatStateOf 而不是 mutableStateOf(1f)：
    //   后者会把 Float 装箱成 java.lang.Float，每次读写都产生一次装箱/拆箱。
    //   这个值在重组时会被反复读取，属于 Compose 官方明确建议避免的开销。
    var fontScale by mutableFloatStateOf(1f)

    /** 工具卡片首次渲染时是否展开 */
    var cardExpanded by mutableStateOf(false)

    /** 当前开启的工具名集合 */
    var enabledTools by mutableStateOf(ALL_TOOL_NAMES.toSet())

    /** 界面语言：system / zh / en */
    var language by mutableStateOf("system")

    /**
     * Agent 预设：standard / ptc / minimal
     *
     * ★ 用可变状态而不是只读常量：切换预设要立刻影响下一次请求，
     *   也要让设置页上的选中态立刻跟着变。
     */
    var preset by mutableStateOf(AgentPreset.STANDARD)
}

/**
 * 当前是不是深色。
 *
 * ★ 为什么不直接用 isSystemInDarkTheme()：
 *   用户在设置里可以强制浅色/深色，那时系统主题就不作数了。
 *   玻璃取色（glassFill 等）必须和 MyChatTheme 用同一个判断，
 *   否则会出现「文字按深色排、毛玻璃按浅色算」的错位。
 */
private val LocalIsDark = staticCompositionLocalOf { true }

private fun prefsOf(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

/** App 启动时读一次，把 SharedPreferences 灌进 AppSettings */
internal fun loadAppSettings(context: Context) {
    val p = prefsOf(context)
    AppSettings.themeMode = p.getString(KEY_THEME_MODE, "system") ?: "system"
    AppSettings.fontScale = p.getFloat(KEY_FONT_SCALE, 1f)
    AppSettings.cardExpanded = p.getBoolean(KEY_CARD_EXPANDED, false)
    AppSettings.language = p.getString(KEY_LANGUAGE, "system") ?: "system"
    AppSettings.preset = AgentPreset.of(p.getString(KEY_PRESET, AgentPreset.STANDARD.id))
    // 没写过的工具默认开启
    AppSettings.enabledTools = ALL_TOOL_NAMES.filter {
        p.getBoolean(KEY_TOOL_PREFIX + it, true)
    }.toSet()
    Log.d(
        TAG,
        "设置已加载: theme=${AppSettings.themeMode}, fontScale=${AppSettings.fontScale}, " +
            "cardExpanded=${AppSettings.cardExpanded}, preset=${AppSettings.preset.id}, " +
            "tools=${AppSettings.enabledTools}"
    )
}

/** 切换 Agent 预设。下一次请求立刻生效，不需要重启 */
internal fun setPreset(context: Context, preset: AgentPreset) {
    prefsOf(context).edit { putString(KEY_PRESET, preset.id) }
    AppSettings.preset = preset
    Log.d(TAG, "Agent 预设切换为: ${preset.id}")
}

internal fun setThemeMode(context: Context, mode: String) {
    prefsOf(context).edit { putString(KEY_THEME_MODE, mode) }
    AppSettings.themeMode = mode
}

internal fun setFontScale(context: Context, scale: Float) {
    prefsOf(context).edit { putFloat(KEY_FONT_SCALE, scale) }
    AppSettings.fontScale = scale
}

internal fun setLanguage(context: Context, lang: String) {
    prefsOf(context).edit { putString(KEY_LANGUAGE, lang) }
    AppSettings.language = lang
}

internal fun setCardExpanded(context: Context, expanded: Boolean) {
    prefsOf(context).edit { putBoolean(KEY_CARD_EXPANDED, expanded) }
    AppSettings.cardExpanded = expanded
}

/** 开关某个工具。内存镜像立刻更新，持久化异步写盘 */
internal fun setToolEnabled(context: Context, name: String, enabled: Boolean) {
    prefsOf(context).edit { putBoolean(KEY_TOOL_PREFIX + name, enabled) }
    AppSettings.enabledTools = if (enabled) {
        AppSettings.enabledTools + name
    } else {
        AppSettings.enabledTools - name
    }
    Log.d(TAG, "工具开关: $name = $enabled，当前开启 ${AppSettings.enabledTools.size} 个")
}

/**
 * 组装发给模型的 tools 数组 —— 只放用户开着的那几个。
 *
 * ★ 为什么在发请求时过滤，而不是启动时过滤一次：
 *   TOOL_DEFINITIONS 是一整段静态 JSON，过滤的代价是解析一次（几十微秒），
 *   而工具开关随时可能在设置页里被改。每次现算最不容易出错。
 */
internal fun buildToolsArray(): JSONArray {
    // ★ 预设优先于单个工具开关：
    //   极简模式下一个工具都不发（连 run_code 也不发），
    //   PTC 模式只发 run_code —— 那七个工具变成沙箱里的函数，不再出现在 tools 里。
    when (AppSettings.preset) {
        AgentPreset.MINIMAL -> return JSONArray()
        AgentPreset.PTC -> return JSONArray().put(JSONObject(PTC_TOOL_DEFINITION))
        AgentPreset.STANDARD -> Unit   // 落到下面按开关过滤
    }

    val all = JSONArray(TOOL_DEFINITIONS)
    val enabled = AppSettings.enabledTools
    val out = JSONArray()
    for (i in 0 until all.length()) {
        val item = all.optJSONObject(i) ?: continue
        val name = item.optJSONObject("function")?.optString("name").orEmpty()
        if (name.isNotEmpty() && name in enabled) out.put(item)
    }
    return out
}

// ============================================================
// 3.24b API 配置 —— 加密存储
//
// 老做法是把 Key 写进 local.properties，编译期塞进 BuildConfig。
// 那对开发者方便，但**分发出去的 APK 里就是明文**，反编译就能拿到。
//
// 现在改成：用户在设置页里填，存 EncryptedSharedPreferences。
// 密钥材料放在 Android Keystore 里（不落盘、不能导出），
// 就算把整个 SharedPreferences 文件拷走也解不开。
//
// BuildConfig 里的那把仍然保留，作为「开发者本机默认值」兜底：
// 用户没配过时直接用，省得自己每次重装都要填一遍。
// ============================================================

private const val SECURE_PREFS_NAME = "coolstream_secure"
private const val SEC_KEY_API_KEY = "api_key"
private const val SEC_KEY_BASE_URL = "base_url"
private const val SEC_KEY_MODEL = "model_name"

/** 「引导已完成」标记。放普通 SharedPreferences 就行，它不是敏感信息 */
private const val KEY_ONBOARD_DONE = "onboard_done"

private const val DEFAULT_API_BASE = "https://api.deepseek.com"
private const val DEFAULT_MODEL = "deepseek-flash"

@Volatile
private var securePrefsCache: SharedPreferences? = null

/**
 * 拿到加密的 SharedPreferences。
 *
 * ★ 失败要能兜住：某些机型/系统上 Keystore 会抽风（尤其是恢复备份、
 *   或者用户清了凭据之后），这时候 create() 会抛异常。
 *   真抛了就退回普通 SharedPreferences —— 安全性打折，但 App 不能因此起不来。
 */
private fun securePrefs(context: Context): SharedPreferences? {
    securePrefsCache?.let { return it }
    return try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also {
            securePrefsCache = it
            Log.d(TAG, "加密存储已就绪")
        }
    } catch (e: Exception) {
        Log.e(TAG, "加密存储初始化失败，退回普通 SharedPreferences", e)
        null
    }
}

/** 运行期的 API 配置。用 mutableStateOf 托着，设置页一改界面立刻跟着变 */
internal object ApiConfig {
    var apiKey by mutableStateOf("")
    var baseUrl by mutableStateOf(DEFAULT_API_BASE)
    var model by mutableStateOf(DEFAULT_MODEL)
}

internal fun loadApiConfig(context: Context) {
    val p = securePrefs(context)
    val savedKey = p?.getString(SEC_KEY_API_KEY, "").orEmpty()
    // 用户没配过就用编译期那把（开发者本机），两者都没有就是空
    ApiConfig.apiKey = savedKey.ifBlank { BuildConfig.DEEPSEEK_API_KEY }
    ApiConfig.baseUrl = p?.getString(SEC_KEY_BASE_URL, DEFAULT_API_BASE)
        ?.takeIf { it.isNotBlank() } ?: DEFAULT_API_BASE
    ApiConfig.model = p?.getString(SEC_KEY_MODEL, DEFAULT_MODEL)
        ?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    Log.d(
        TAG,
        "API 配置已加载: baseUrl=${ApiConfig.baseUrl}, model=${ApiConfig.model}, " +
            "key=${if (ApiConfig.apiKey.isBlank()) "空" else "已配置(${ApiConfig.apiKey.length} 字符)"}, " +
            "加密存储=${p != null}"
    )
}

internal fun saveApiConfig(context: Context, key: String, base: String, model: String) {
    val k = key.trim()
    val b = base.trim().ifBlank { DEFAULT_API_BASE }
    val m = model.trim().ifBlank { DEFAULT_MODEL }

    val p = securePrefs(context)
    if (p == null) {
        Log.e(TAG, "加密存储不可用，API 配置无法持久化")
    } else {
        p.edit {
            putString(SEC_KEY_API_KEY, k)
            putString(SEC_KEY_BASE_URL, b)
            putString(SEC_KEY_MODEL, m)
        }
    }

    ApiConfig.apiKey = k
    ApiConfig.baseUrl = b
    ApiConfig.model = m
    Log.d(TAG, "API 配置已保存: baseUrl=$b, model=$m, key=${k.length} 字符")
}

/**
 * 把用户填的地址补成完整的 chat/completions 端点。
 *
 * 用户很可能只填 `https://api.deepseek.com`（文档里就是这么写的），
 * 也有人会填全 `.../chat/completions`，两种都得认。
 */
private fun chatCompletionsUrl(): String {
    val base = ApiConfig.baseUrl.trim().trimEnd('/')
    return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
}

// ============================================================
// 3.24c 首次启动与默认工作区
// ============================================================

/** 默认工作区路径。新用户不选工作区也能直接发指令 */
private const val DEFAULT_WORKSPACE = "/storage/emulated/0/Documents/CoolStream_Workspace"

internal fun isOnboarded(context: Context): Boolean {
    return prefsOf(context).getBoolean(KEY_ONBOARD_DONE, false)
}

internal fun setOnboarded(context: Context, done: Boolean) {
    prefsOf(context).edit { putBoolean(KEY_ONBOARD_DONE, done) }
}

/**
 * 要不要弹引导页。
 *
 * 判断的是**真实状态**而不是一个「首次启动」标记：
 * Key 配好了、文件权限也给了，就直接进主界面 —— 老用户升级后
 * 不会被莫名其妙拦一次。反过来，只要缺一个就拦。
 *
 * 「跳过」按钮会把 KEY_ONBOARD_DONE 置上，之后不再打扰。
 */
internal fun needsOnboarding(context: Context): Boolean {
    if (isOnboarded(context)) return false
    val keyOk = ApiConfig.apiKey.isNotBlank()
    val storageOk = hasAllFilesAccess(context)
    return !(keyOk && storageOk)
}

/**
 * 确保有一个可用的工作区。
 *
 * 只在「用户从来没设过」的时候动手，绝不覆盖用户自己选的目录。
 * 建目录需要「所有文件访问权限」，所以调用方要先确认权限已给。
 */
internal fun ensureDefaultWorkspace(context: Context): String? {
    if (currentWorkspace.isNotBlank()) return currentWorkspace
    // ★ 走统一入口，不要直接调 Environment.isExternalStorageManager()
    if (!hasAllFilesAccess(context)) {
        Log.w(TAG, "没有存储权限，跳过默认工作区创建")
        return null
    }
    return try {
        val dir = File(DEFAULT_WORKSPACE)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "默认工作区创建失败: $DEFAULT_WORKSPACE")
            return null
        }
        saveWorkspace(context, dir.absolutePath)
        Log.d(TAG, "默认工作区已就绪: ${dir.absolutePath}")
        dir.absolutePath
    } catch (e: Exception) {
        Log.e(TAG, "创建默认工作区出错", e)
        null
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

/**
 * 上下文窗口的人类可读标签：1_000_000 显示成「1M tokens」比「1000K」好读。
 * 参数化是为了能单测，也让 IDE 不会把比较折叠成「始终为 true」。
 */
internal fun contextWindowLabel(tokens: Int = CONTEXT_WINDOW_TOKENS): String =
    if (tokens >= 1_000_000) "${tokens / 1_000_000}M tokens" else "${tokens / 1000}K tokens"

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
internal fun isPeakHour(): Boolean {
    val cal = Calendar.getInstance()
    val dow = cal.get(Calendar.DAY_OF_WEEK)
    if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    return hour in 9..11 || hour in 14..17
}

/** 按官方峰谷单价算出这一轮花了多少元 */
internal fun costCny(u: UsageStats): Double {
    val peak = isPeakHour()
    val hitPrice = if (peak) 0.04 else 0.02
    val missPrice = if (peak) 2.0 else 1.0
    val outPrice = if (peak) 8.0 else 4.0
    return (u.cacheHitTokens * hitPrice +
        u.cacheMissTokens * missPrice +
        u.completionTokens * outPrice) / 1_000_000.0
}

/** 费用显示：不到 1 分钱就给 4 位小数，否则 2 位 */
internal fun formatCny(v: Double): String =
    if (v < 0.01) {
        String.format(Locale.US, "¥%.4f", v)
    } else {
        String.format(Locale.US, "¥%.2f", v)
    }

/** token 数显示：1.2K / 3.4M */
internal fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

// ============================================================
// 4.x Token 用量的跨轮累计与持久化（v1.1 阶段三任务二）
//
//   单轮的 usage 在 ChatScreen 里已经有 lastUsage 显示了，
//   但 App 一重启就归零。这里再加一层「账本」：
//     · 每轮对话结束（onUsage 回调）累加一次
//     · 按「今天」和「历史总计」两个口径分别累计
//     · 写 SharedPreferences（coolstream_prefs），读写全在 Dispatchers.IO
//
//   ★ 为什么费用存总额、不存单价再重算：isPeakHour() 随时钟变化，
//     重算会把昨天的谷价轮次按今天的峰价改写，账就错了。
//     所以每轮只把「当轮实付金额」累加进 ledger，之后永不重算。
//
//   ★ 为什么放单例而不是 ViewModel：账本是 App 级的，
//     跨会话、跨 ViewModel 生命周期都该存在；ViewModel 只是「今天」
//     这部分的一个观察窗口。
// ============================================================

/** 账本快照：今天 + 累计 两个口径 */
data class UsageLedgerSnapshot(
    val todayPromptTokens: Int = 0,
    val todayCacheHitTokens: Int = 0,
    val todayCompletionTokens: Int = 0,
    val todayCny: Double = 0.0,
    val totalPromptTokens: Int = 0,
    val totalCacheHitTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
    val totalCny: Double = 0.0
) {
    val todayTotalTokens: Int get() = todayPromptTokens + todayCompletionTokens
    val totalTotalTokens: Int get() = totalPromptTokens + totalCompletionTokens
}

/** 账本日期键：yyyy-MM-dd。SimpleDateFormat 非线程安全，每次调用新建一个小对象（IO 协程里跑，无性能压力） */
private fun usageDayFormat(): SimpleDateFormat =
    SimpleDateFormat("yyyy-MM-dd", Locale.US)

object UsageLedger {
    private const val KEY_DAY = "usage_day"                  // 今天是几号（yyyy-MM-dd）
    private const val KEY_DAY_PREFIX = "usage_day_"          // 前缀 + 字段名 = 今天的 key
    private const val KEY_TOTAL_PREFIX = "usage_total_"      // 前缀 + 字段名 = 累计的 key

    /** 跨轮累计的「今天」用量（主线程写、Compose 观察），落盘在 IO 协程里做 */
    val today = mutableStateOf(UsageLedgerSnapshot())

    /** 把一轮的 usage 累进账本并异步落盘。onUsage 在主线程回调，绝不阻塞流式渲染。 */
    fun record(context: Context, u: UsageStats) {
        val spend = costCny(u)
        // 主线程只改内存状态（一次快照 copy，微秒级），IO 全部丢给协程
        val cur = today.value
        today.value = cur.copy(
            todayPromptTokens = cur.todayPromptTokens + u.promptTokens,
            todayCacheHitTokens = cur.todayCacheHitTokens + u.cacheHitTokens,
            todayCompletionTokens = cur.todayCompletionTokens + u.completionTokens,
            todayCny = cur.todayCny + spend,
            totalPromptTokens = cur.totalPromptTokens + u.promptTokens,
            totalCacheHitTokens = cur.totalCacheHitTokens + u.cacheHitTokens,
            totalCompletionTokens = cur.totalCompletionTokens + u.completionTokens,
            totalCny = cur.totalCny + spend
        )
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val day = usageDayFormat().format(Date())   // yyyy-MM-dd
                // 跨天了：旧的一天数据不删（总量已含），只把「今天」口径清零重开
                val sameDay = prefs.getString(KEY_DAY, null) == day
                prefs.edit {
                    putString(KEY_DAY, day)
                    fun bumpInt(key: String, delta: Int) {
                        val v = if (sameDay) prefs.getInt(key, 0) else 0
                        putInt(key, v + delta)
                    }
                    bumpInt(KEY_DAY_PREFIX + "prompt", u.promptTokens)
                    bumpInt(KEY_DAY_PREFIX + "hit", u.cacheHitTokens)
                    bumpInt(KEY_DAY_PREFIX + "completion", u.completionTokens)
                    val daySpend = if (sameDay) java.lang.Double.longBitsToDouble(
                        prefs.getLong(KEY_DAY_PREFIX + "cny", 0L)) else 0.0
                    putLong(KEY_DAY_PREFIX + "cny",
                        java.lang.Double.doubleToRawLongBits(daySpend + spend))
                    fun bumpTotal(key: String, delta: Int) =
                        putInt(key, prefs.getInt(key, 0) + delta)
                    bumpTotal(KEY_TOTAL_PREFIX + "prompt", u.promptTokens)
                    bumpTotal(KEY_TOTAL_PREFIX + "hit", u.cacheHitTokens)
                    bumpTotal(KEY_TOTAL_PREFIX + "completion", u.completionTokens)
                    val totalSpend = java.lang.Double.longBitsToDouble(
                        prefs.getLong(KEY_TOTAL_PREFIX + "cny", 0L))
                    putLong(KEY_TOTAL_PREFIX + "cny",
                        java.lang.Double.doubleToRawLongBits(totalSpend + spend))
                }
            } catch (e: Exception) {
                Log.e(TAG, "usage 落盘失败", e)
            }
        }
    }

    /** App 启动 / ViewModel 初始化时读一次账本（IO 协程） */
    fun load(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val day = usageDayFormat().format(Date())
                val sameDay = prefs.getString(KEY_DAY, null) == day
                fun dayInt(key: String) = if (sameDay) prefs.getInt(KEY_DAY_PREFIX + key, 0) else 0
                fun dayCny() = if (sameDay) java.lang.Double.longBitsToDouble(
                    prefs.getLong(KEY_DAY_PREFIX + "cny", 0L)) else 0.0
                val snap = UsageLedgerSnapshot(
                    todayPromptTokens = dayInt("prompt"),
                    todayCacheHitTokens = dayInt("hit"),
                    todayCompletionTokens = dayInt("completion"),
                    todayCny = dayCny(),
                    totalPromptTokens = prefs.getInt(KEY_TOTAL_PREFIX + "prompt", 0),
                    totalCacheHitTokens = prefs.getInt(KEY_TOTAL_PREFIX + "hit", 0),
                    totalCompletionTokens = prefs.getInt(KEY_TOTAL_PREFIX + "completion", 0),
                    totalCny = java.lang.Double.longBitsToDouble(
                        prefs.getLong(KEY_TOTAL_PREFIX + "cny", 0L))
                )
                withContext(Dispatchers.Main) { today.value = snap }
            } catch (e: Exception) {
                Log.e(TAG, "usage 账本读取失败", e)
            }
        }
    }
}

/** 从任意一条 SSE 数据里尝试抠出 usage（DeepSeek 通常放在最后一条，且那一条 choices 为空） */
internal fun parseUsage(json: String): UsageStats? {
    return try {
        val u = JSONObject(json).optJSONObject("usage") ?: return null
        if (u.length() == 0) return null
        UsageStats(
            promptTokens = u.optInt("prompt_tokens", 0),
            cacheHitTokens = u.optInt("prompt_cache_hit_tokens", 0),
            completionTokens = u.optInt("completion_tokens", 0)
        )
    } catch (_: Exception) {
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
    /**
     * Agent 想问用户一个问题（ask_user 工具）。
     *
     * 返回用户的回答；用户取消返回 null。
     * 这是 runAgentLoop 唯一的 suspend 回调：调用方要弹对话框、
     * 「等用户打完字再回来」，没有比挂起更自然的写法 ——
     * IO 协程在这里 park 住，Loop 天然暂停，零轮询零忙等。
     *
     * ★ 传 null 表示这一轮没有 UI 可以弹（比如 ask_user 被关掉的场景
     *   由 tools 数组过滤兜底，走不到这里；防御性留空 = 工具直接报错）。
     */
    onAskUser: (suspend (question: String) -> String?)? = null,
    onDone: (totalChars: Int) -> Unit,
    /**
     * 出错回调。
     *
     * ★ 注意参数是 ErrorText 而不是 String：
     *   这个函数跑在 IO 协程里，拿不到 stringResource，也不该把 Context 拖进来。
     *   所以只把「资源 id + 参数」交出去，由调用方（主线程、手里有 localizedContext）
     *   调 resolve(context) 变成当前语言的文字。
     */
    onError: (ErrorText) -> Unit
): Job {
    return scope.launch(Dispatchers.IO) {
        try {
            // ---------- 1. 检查 API Key ----------
            //
            // ★ 这里必须读 ApiConfig.apiKey，不能读 BuildConfig.DEEPSEEK_API_KEY。
            //
            //   历史 bug：原来读的是 BuildConfig，而 release 包里那个值是空串，
            //   于是「用户明明在设置里填了 Key，一发送还是报没配置」——
            //   因为真正发请求的这条路径压根没看用户填的值。
            //   （只有 debug 包能跑，因为本机 local.properties 里有兜底 Key，
            //    把这个问题掩盖了很久。）
            //
            //   ApiConfig.apiKey 在启动时由 loadApiConfig() 填好：
            //   优先用用户存的，其次才是编译期兜底（release 上兜底恒为空）。
            val apiKey = ApiConfig.apiKey
            if (apiKey.isBlank()) {
                withContext(Dispatchers.Main) {
                    onError(ErrorText(R.string.err_no_api_key))
                }
                return@launch
            }

            // ---------- 2. 把历史装进消息数组（这一轮会不断增长） ----------
            val messages = JSONArray()

            if (SYSTEM_PROMPT.isNotEmpty()) {
                messages.put(JSONObject().apply {
                    put("role", "system")
                    // ★ 带上当前工作区约束 + 当前时间。
                    //
                    //   ★★★ 时间必须拼在【末尾】，不能拼在开头 ★★★
                    //     DeepSeek 的上下文缓存是按「前缀」命中的：
                    //     时间戳一变，从它往后所有 token 全部失效。
                    //     放开头 = 每次请求都让整份系统提示词重新计费
                    //     （缓存命中 0.02 元/百万 → 未命中 1 元/百万，差 50 倍）；
                    //     放末尾 = 只让最后几十个 token 失效，前面照样命中。
                    //   三种预设（标准 / PTC / 极简）都会走到这一行，
                    //   所以时间感知对它们一律生效，不存在"某个模式不知道今天几号"。
                    put("content", systemPromptWithWorkspace() + "\n\n" + timeContextLine())
                })
            }

            for (msg in history) {
                if (msg.content.isBlank()) continue
                if (msg.isError) continue
                if (msg.type == MessageType.TOOL_CALL) continue
                messages.put(JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "assistant")
                    // 带附件的消息在这里展开成数组 / 追加 read_file 指令
                    put("content", contentForApi(msg))
                })
            }

            // ★ 只把用户开着的那几个工具发给模型（设置 → 插件 里的开关）
            val toolsArray = buildToolsArray()
            Log.d(TAG, "本次启用工具 ${toolsArray.length()} 个: ${AppSettings.enabledTools}")
            var totalChars = 0
            var round = 0
            // 多轮工具调用会把 token 用量逐轮累加
            var accUsage = UsageStats.EMPTY

            while (round < MAX_TOOL_ROUNDS) {
                round++
                Log.d(TAG, "agent round $round / messages=${messages.length()}")

                val bodyJson = JSONObject().apply {
                    put("model", ApiConfig.model)
                    put("stream", true)
                    // ★ 关键：不加这个，流式响应里【根本不会返回 usage】
                    //   DeepSeek 默认只在非流式请求里给 usage
                    put("stream_options", JSONObject().put("include_usage", true))
                    put("messages", messages)
                    // 工具全被关掉时不要发空数组 —— 有些实现会因此报 400
                    if (toolsArray.length() > 0) {
                        put("tools", toolsArray)
                    }
                    put("thinking", JSONObject().put("type", "disabled"))
                }.toString()

                // 【诊断】把完整请求体打出来 —— 亲眼看 tools 数组在不在
                Log.d(
                    TAG,
                    "发送请求 round=$round, tools=${toolsArray.length()} 个, 请求体: $bodyJson"
                )

                val request = Request.Builder()
                    .url(chatCompletionsUrl())
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
                    withContext(Dispatchers.Main) { onError(httpErrorText(response.code, errBody)) }
                    return@launch
                }

                val bodyStream = response.body
                if (bodyStream == null) {
                    withContext(Dispatchers.Main) { onError(ErrorText(R.string.err_empty_response)) }
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

                    // ★ ask_user 特例：它不在 executeTool 里执行。
                    //
                    //   executeTool 是同步纯函数（文件/搜索都毫秒级返回），
                    //   而 ask_user 的「执行」= 弹对话框 + 等用户打字，秒级甚至分钟级。
                    //   硬塞进 executeTool 就得把挂起函数传染给它的全部调用链。
                    //
                    //   所以在这里拦截：onAskUser 是 suspend 的，
                    //   runAgentLoop 本来就跑在 IO 协程里，await 它等于让
                    //   整个 Loop 挂起 —— 对话暂停、不耗 CPU、不发请求。
                    val result = if (toolName == "ask_user") {
                        val question = argOf(argsJson, "question").trim()
                        if (question.isEmpty()) {
                            ToolExecResult(false, "问题内容为空：question 参数不能为空字符串")
                        } else {
                            val answer = onAskUser?.invoke(question)
                            if (answer == null) {
                                ToolExecResult(true, "用户取消了操作")
                            } else if (answer.isBlank()) {
                                ToolExecResult(
                                    true,
                                    "用户提交了空回答。不要把空串当成有意义的答案，" +
                                        "可以用合理默认值继续，或向用户说明你缺少的信息。"
                                )
                            } else {
                                ToolExecResult(true, "用户的回答：$answer")
                            }
                        }
                    } else {
                        // ★ 本地真正执行（不在主线程，不阻塞 UI）
                        executeTool(toolName, argsJson)
                    }
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
                onError(ErrorText(R.string.err_too_many_rounds, listOf(MAX_TOOL_ROUNDS)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "agent loop failed", e)
            withContext(Dispatchers.Main) {
                onError(networkErrorText(e))
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

        // 设置要在第一次重组之前读出来，否则会先按默认主题渲染一帧再跳变
        loadAppSettings(this)
        // API 配置（加密存储）同理
        loadApiConfig(this)
        // 已经有权限的话，顺手把默认工作区补上（幂等）
        ensureDefaultWorkspace(this)

        setContent {
            // 外观：跟随系统 / 强制浅色 / 强制深色
            val dark = when (AppSettings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            // 字号：只改 fontScale，不动 density ——
            // 动 density 的话间距、圆角、图标会跟着一起缩放，布局会散架。
            val baseDensity = LocalDensity.current

            // ★ 首次启动判断：Key 或文件权限缺一个，就先走引导。
            //   已经引导过（或用户点过「跳过」）就不再拦。
            var onboarded by remember { mutableStateOf(!needsOnboarding(this@MainActivity)) }

            // 语言：跟随系统 / 强制中文 / 强制英文
            val baseContext = LocalContext.current
            val localizedContext = remember(AppSettings.language) {
                baseContext.withAppLocale(AppSettings.language)
            }

            // ★ 必须显式补回 ActivityResultRegistryOwner。
            //
            //   坑：withAppLocale 用的是 createConfigurationContext()，
            //   它返回的是一个【全新的 Context】，不是原来 Activity 的 ContextWrapper，
            //   于是「顺着 Context 链往上找 Activity」这条路就断了。
            //   而 rememberLauncherForActivityResult 正是靠 LocalActivityResultRegistryOwner
            //   （默认从 LocalContext 里找）来拿注册表的 —— 断了就抛
            //   IllegalStateException: No ActivityResultRegistryOwner was provided。
            //
            //   表现：语言设为「跟随系统」时一切正常（withAppLocale 直接 return this，不包装）；
            //        一旦切到强制中文/英文，App 一启动就崩在通知权限请求那里。
            //   所以这里把 owner 直接指向 Activity 自己（ComponentActivity 本来就实现了它）。
            val activityResultOwner = this@MainActivity

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalActivityResultRegistryOwner provides activityResultOwner,
                LocalIsDark provides dark,
                LocalDensity provides Density(baseDensity.density, AppSettings.fontScale)
            ) {
                MyChatTheme(darkTheme = dark) {
                    if (onboarded) {
                        ChatScreen()
                    } else {
                        OnboardingScreen(onFinish = { onboarded = true })
                    }
                }
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
private fun glassFill(): Color = if (LocalIsDark.current) GlassFillDark else GlassFillLight

@Composable
private fun glassDrawer(): Color = if (LocalIsDark.current) GlassDrawerDark else GlassDrawerLight

@Composable
private fun glassEdge(): Color = if (LocalIsDark.current) GlassEdgeDark else GlassEdgeLight

@Composable
private fun glassTint(): Color = if (LocalIsDark.current) GlassTintDark else GlassTintLight


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
/**
 * ask_user 的「用户点了取消」哨兵。
 * ★ 用 === 比较引用而不是字符串内容：用户手动输入恰好这串字的概率为零，
 *   但引用相等是绝对可靠的 —— 不存在歧义路径。
 */
private val ASK_USER_CANCELLED = String(charArrayOf('@'))

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    init {
        // Token 账本：启动时把今天 + 累计的用量从 SharedPreferences 读回来（IO 协程）
        UsageLedger.load(app)
    }

    // ★ 这里刻意不放 dao：
    //   ChatScreen 里已经有 val dao = remember { AppDatabase.get(context).chatDao() }，
    //   ViewModel 再存一份只是重复引用，而且从来没人用过。
    //   （留这行注释是为了说明「不是漏了」，是刻意删的。）

    // ---------- 状态（界面通过 by 委托读取/写入） ----------
    val sessions = mutableStateListOf<ChatSession>()
    val currentSessionIdState = mutableStateOf("")
    val inputTextState = mutableStateOf("")
    val isStreamingState = mutableStateOf(false)
    val lastUsageState = mutableStateOf(UsageStats.EMPTY)
    val workspaceState = mutableStateOf("")
    val showSettingsState = mutableStateOf(false)

    /**
     * 正在联网搜索的关键词。空串 = 没在搜。
     *
     * ★ 为什么单独开一个状态，而不是直接看工具卡片：
     *   工具卡片是「执行完才落库」的历史记录，而用户需要知道的是
     *   「这 3 秒 App 在干什么」。搜索要等网络，卡片那时还只是一张
     *   写着 ok=null 的灰卡片，看不出是搜索还是卡死了。
     *
     * ★ 放在 ViewModel 里（不是 remember）：
     *   旋转屏幕时状态不能丢 —— 否则转一下就"搜索消失了"，
     *   用户会以为搜索被取消了。
     */
    val searchingQueryState = mutableStateOf("")

    /** 首次从数据库加载只做一次。旋转回来不要重新读库，否则会打断正在进行的流。 */
    var loadedOnce = false

    /** 正在跑的流式任务，停止按钮要用 */
    var streamJob: Job? = null

    /** 本轮 AI 占位行在数据库里的 id（停止生成时落库用） */
    val aiRowId = AtomicInteger(0)

    /**
     * ask_user 的桥接状态：Agent 正在等用户回答的问题；null = 没有在等。
     *
     * ★ 为什么放 ViewModel 而不是 remember：
     *   旋转屏幕时 Compose 状态会重建，而「Loop 还挂在 IO 协程里等答案」
     *   这个事实不变。放 remember 的话一旋转 deferred 就丢了，Loop 永远挂起。
     *
     * ★ 为什么 deferred 用 CompletableDeferred 而不是 MutableStateFlow：
     *   挂起中的 runAgentLoop 需要「一次性、可 await」的语义 ——
     *   StateFlow 是「最新值」，没人消费也没关系；Deferred 是「承诺」，
     *   IO 协程 park 在 await 上，用户提交/取消的那一次 complete 就是全部。
     */
    val pendingQuestionState = mutableStateOf<String?>(null)
    var askDeferred: CompletableDeferred<String>? = null

    /**
     * runAgentLoop 传给 onAskUser 的实现：在 IO 协程被调用，
     * 把问题切到主线程写进状态（触发 Compose 弹窗），然后挂起等答案。
     */
    suspend fun askUserAndWait(question: String): String? {
        val d = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            askDeferred = d
            pendingQuestionState.value = question
        }
        // ★ 取消传播：用户点「停止生成」会 cancel 整个 runAgentLoop，
        //   await 在这里抛 CancellationException，弹窗状态必须清掉。
        try {
            val raw = d.await()
            // 哨兵串 = 用户点了取消：归一成 null，让调用方收到「用户取消了操作」
            return if (raw == ASK_USER_CANCELLED) null else raw
        } finally {
            withContext(Dispatchers.Main) {
                if (askDeferred === d) {
                    askDeferred = null
                    pendingQuestionState.value = null
                }
            }
        }
    }

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

    // ★ v1.1：正在联网搜索的关键词（空串 = 没在搜）。
    //   在底部状态条上显示「🔍 正在联网搜索: xxx」。
    var searchingQuery by vm.searchingQueryState

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
        // ★ 默认标题故意【不】做多语言，保持中文：
        //   标题是写进数据库的持久化数据、用户还能自己改。
        //   如果按当前语言生成，同一个侧边栏里会出现「新对话 / New chat」混排，
        //   历史会话的标题还会随着切语言变来变去。宁可它一直是同一个词。
        val defaultTitle = "新对话"
        // 放到列表最上面：越新的会话越靠前，这是主流客户端的习惯
        sessions.add(
            0,
            ChatSession(
                id = id,
                title = defaultTitle,
                messages = emptyList(),
                createdAt = createdAt
            )
        )
        currentSessionId = id
        streamScope.launch(Dispatchers.IO) {
            dao.insertSession(SessionEntity(id = id, title = defaultTitle, createdAt = createdAt))
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
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.drawer_open))
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
                                    contentDescription = stringResource(R.string.new_chat),
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
                            text = stringResource(R.string.chat_greeting),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else {
                    // ---------- 自动滚到底 ----------
                    //
                    // 三个细节值得说：
                    //  ① 用户往上翻历史时不要把他拽回底部，否则根本没法看旧消息
                    //     —— 但这条只管「流式吐字」；新增 item 一律跟随（见下面注释）
                    //  ② 流式输出用瞬时滚动，不用动画 —— 每 50ms 刷一次，
                    //     动画会被下一次刷新打断，看起来反而在抖
                    val listState = rememberLazyListState()
                    val itemCount = currentMessages.size
                    val lastLen = currentMessages.lastOrNull()?.content?.length ?: 0
                    var prevCount by remember { mutableIntStateOf(0) }

                    // 「最后一条是否已经露在屏幕里」。用 index 判断而不是精确偏移，
                    // 是因为流式输出的气泡经常比屏幕还高，精确偏移反而判不准。
                    val atBottom by remember {
                        derivedStateOf {
                            val info = listState.layoutInfo
                            val last = info.visibleItemsInfo.lastOrNull()
                            last == null || last.index == info.totalItemsCount - 1
                        }
                    }

                    LaunchedEffect(itemCount, lastLen) {
                        if (itemCount == 0) {
                            prevCount = 0
                            return@LaunchedEffect
                        }
                        val isNewMessage = itemCount != prevCount
                        prevCount = itemCount
                        // ★ 只有「流式吐字」（itemCount 不变、lastLen 在长）才看 atBottom，
                        //   免得用户上翻看历史时被每 50ms 的刷新一次次拽回底部。
                        //
                        //   ★ 新增 item 一律跟随到底部，不能看 atBottom：
                        //     工具卡片是插在「AI 占位气泡」前面的，这一插就会把
                        //     占位气泡挤出屏幕，atBottom 当场变成 false，
                        //     后面流式输出就再也不跟滚了 —— 用户会看到自己的
                        //     问题下面空空如也，以为模型没回答。
                        //     （v1.1 实测复现：web_search 的答案和工具卡片
                        //     全都躺在屏幕外，要手动往上滑才看得见。）
                        if (!isNewMessage && !atBottom) return@LaunchedEffect

                        val target = itemCount - 1
                        if (isNewMessage) {
                            listState.animateScrollToItem(target)
                        } else {
                            listState.scrollToItem(target)
                            // 最后一条比屏幕高时（长回复流式输出），
                            // 只定位到「它的顶部」看不到新吐出来的字，再往前顶一段。
                            // 到底会自动停住，不会越界。
                            listState.scrollBy(200_000f)
                        }
                    }

                    LazyColumn(
                        state = listState,
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

                val ctx = LocalContext.current
                var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
                var showAttachSheet by remember { mutableStateOf(false) }
                // Office 解析不通过时弹出的提示文案（null = 不弹）
                var officeTip by remember { mutableStateOf<String?>(null) }

                val attachScope = rememberCoroutineScope()

                // 选中后立刻把 content:// 落盘，拿到真实绝对路径。
                // 图片和文件走同一条路，区别只在 isImage 标记。
                //
                // ★ v1.1：isDocument 为 true 时，落盘之后顺手解析一次 Office。
                //   解析成功 → 照常当附件（read_file 会再解析一遍，两边共用同一套解析器）；
                //   解析失败（PDF / 旧版二进制）→ 从列表里摘掉并弹对话框说明原因，
                //   因为这类文件 read_file 也读不了，挂在输入区只会让用户白等一轮。
                fun addAttachment(uri: Uri, isImage: Boolean, isDocument: Boolean = false) {
                    val name = displayNameOf(ctx, uri)
                    val att = Attachment(uri = uri, name = name, isImage = isImage)
                    attachments = attachments + att
                    attachScope.launch(Dispatchers.IO) {
                        val result = persistAttachment(ctx, uri, name)
                        var officeMessage: String? = null
                        // 解析成功的正文连长度一起留着，发送时直接拼进 Prompt
                        var parsedText: String? = null
                        var charCount = 0
                        if (result.isSuccess && isDocument) {
                            val file = result.getOrNull()
                            if (file != null) {
                                val parsed = parseOfficeBytes(file.name, file.readBytes())
                                if (parsed.ok) {
                                    // ★ 这里必须自己再兜一次截断：解析器是并行开发的独立文件，
                                    //   不能把 token 安全押在它今天能不能改对上。已截断的文本
                                    //   再过一遍是幂等的（<= 上限就原样返回），无害。
                                    parsedText = truncateOfficeText(parsed.text)
                                    charCount = parsedText.length
                                } else {
                                    officeMessage = parsed.message
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            attachments = attachments.mapNotNull {
                                if (it.uri == uri) {
                                    if (officeMessage != null) {
                                        // 解析不了：不留在输入区，理由交给对话框
                                        officeTip = officeMessage
                                        null
                                    } else {
                                        it.copy(
                                            state = if (result.isSuccess) {
                                                AttachState.READY
                                            } else {
                                                AttachState.FAILED
                                            },
                                            path = result.getOrNull()?.absolutePath,
                                            error = result.exceptionOrNull()?.message,
                                            parsedText = parsedText,
                                            charCount = charCount
                                        )
                                    }
                                } else {
                                    it
                                }
                            }
                        }
                    }
                }

                // 系统相册（Android 13+ 走照片选择器，不需要读存储权限）
                val imagePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri -> if (uri != null) addAttachment(uri, isImage = true) }

                // 系统文件管理器，不限 MIME 类型
                val filePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> if (uri != null) addAttachment(uri, isImage = false) }

                // 「上传文档」入口：同样走系统文件管理器，但限定 MIME，
                // 让用户一眼能看到 Office / PDF，不会被相册里的视频干扰。
                // ★ 选中后 addAttachment(isDocument = true) 才会做「先解析再挂载」，
                //   普通文件入口保持原样（解析交给 read_file），不改变既有行为。
                // ★ DOC_PICKER_MIME 是数组，launch 收 Array<String>，
                //   所以整个数组原样传进去（末尾 "*/*" 兜底），不要再套一层 arrayOf。
                val docPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> if (uri != null) addAttachment(uri, isImage = false, isDocument = true) }

                // ---------- 附件选择弹层 ----------
                if (showAttachSheet) {
                    AttachmentSheet(
                        onDismiss = { showAttachSheet = false },
                        onPickImage = {
                            showAttachSheet = false
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onPickFile = {
                            showAttachSheet = false
                            filePicker.launch(arrayOf("*/*"))
                        },
                        onPickDoc = {
                            showAttachSheet = false
                            docPicker.launch(DOC_PICKER_MIME)
                        }
                    )
                }

                // ---------- Office 解析结果提示（v1.1） ----------
                // ★ 为什么不复用 AttachmentChip 的红色 ⚠️ 状态：
                //   解析失败的话术来自解析器本身（PDF / 旧版二进制各有固定文案），
                //   一句话塞进小胶囊会被截断，用户看不到「该怎么补救」。
                //   所以选中文档后先解析一次，认不出来的直接弹对话框说明原因。
                if (officeTip != null) {
                    AlertDialog(
                        onDismissRequest = { officeTip = null },
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = { Text(stringResource(R.string.office_unsupported_title)) },
                        text = {
                            Text(
                                text = officeTip ?: "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { officeTip = null }) {
                                Text(stringResource(R.string.action_ok))
                            }
                        }
                    )
                }

                // ---------- ask_user 弹窗（v1.1 阶段三） ----------
                // ★ pendingQuestion 非 null = runAgentLoop 正挂在 askUserAndWait 上等答案。
                //   在这里弹极客风毛玻璃对话框；旋转屏幕状态也不丢（存 ViewModel）。
                vm.pendingQuestionState.value?.let { question ->
                    AskUserDialog(
                        question = question,
                        onSubmit = { answer ->
                            vm.askDeferred?.complete(answer)
                            // 状态由 askUserAndWait 的 finally 统一清，这里不重复清，
                            // 避免竞态下把下一轮刚写进去的问题又抹掉。
                        },
                        onCancel = {
                            // 取消和空回答走两条路：complete 哨兵引用，
                            // askUserAndWait 里用 === 识别后归一成 null。
                            vm.askDeferred?.complete(ASK_USER_CANCELLED)
                        }
                    )
                }

                // ---------- 底部：联网搜索状态条（v1.1） ----------
                // ★ 为什么放在这里、而不是插进消息列表：
                //   消息列表有一条硬不变量 —— 最后一条永远是 AI 占位气泡，
                //   onFlush 靠 messages.last() 定位它来原地改正文（见 §3.3）。
                //   往列表里插一个"临时 UI item"要和 itemsIndexed 的 index 对齐，
                //   一不小心就把这条不变量踩坏。放在输入框上方的状态带里，
                //   视觉上就是"当前这条流式消息的状态"，效果一样，风险为零。
                if (searchingQuery.isNotBlank()) {
                    SearchStatusBar(query = searchingQuery)
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
                    attachments = attachments,
                    onTextChange = { inputText = it },
                    onAttachClick = { showAttachSheet = true },
                    onRemoveAttachment = { index ->
                        attachments = attachments.filterIndexed { i, _ -> i != index }
                    },
                    onSend = {
                        // ★ 全局拦截器：没配 API Key 就不要发请求。
                        //
                        //   为什么拦在这里而不是在引导页强制填：
                        //   引导页允许「跳过」，用户完全可能先进来看看界面。
                        //   真到要用的时候才提示，比在门口堵人体验好得多。
                        //
                        //   表现方式：插一条 isError = true 的气泡。
                        //   这样它不会被当成 AI 的回复发给模型（runAgentLoop 里
                        //   有 `if (msg.isError) continue`），下次请求自动排除。
                        val currentKey = ApiConfig.apiKey
                        if (currentKey.isBlank()) {
                            val sid = currentSessionId.ifBlank { newSession() }
                            val errText = context.getString(R.string.err_no_api_key_bubble)
                            val nowTs = System.currentTimeMillis()
                            updateSession(sid) { s ->
                                s.copy(
                                    messages = s.messages + ChatMessage(
                                        sessionId = sid,
                                        content = errText,
                                        isUser = false,
                                        isError = true,
                                        timestamp = nowTs
                                    )
                                )
                            }
                            streamScope.launch(Dispatchers.IO) {
                                dao.insert(
                                    ChatMessage(
                                        sessionId = sid,
                                        content = errText,
                                        isUser = false,
                                        isError = true,
                                        timestamp = nowTs
                                    )
                                )
                            }
                            Log.w(TAG, "没有 API Key，拦截发送")
                            return@InputBar
                        }

                        // 只把「已落盘」的附件写进消息 —— 没落盘的不骗模型
                        val ready = attachments.filter {
                            it.state == AttachState.READY && it.path != null
                        }

                        // ★ v1.1 文档上下文：把解析出来的正文按 brief 的格式拼在「问题」前面。
                        //
                        //   为什么要包一层 <<DOC:begin|名字|字数>> … <<DOC:end>> 哨兵：
                        //     · 界面上整段剥掉，只在气泡里留一张「📄 已读取文档」卡片，
                        //       否则一万字原文会把聊天气泡撑爆；
                        //     · 发给模型时只剥两行哨兵、正文原样留下，模型才看得见文档；
                        //     · 存进数据库的也是同一份，翻历史记录重发时 contentForApi
                        //       照样能取回正文，不会出现「历史里文档内容丢了」。
                        //
                        //   图片和普通文件不进这里：它们没有 parsedText，仍走 read_file。
                        val docBlock = ready.mapNotNull { att ->
                            val text = att.parsedText
                            if (att.isImage || text.isNullOrBlank()) {
                                null
                            } else {
                                buildString {
                                    append("<<DOC:begin|")
                                    append(att.name)
                                    append("|")
                                    append(att.charCount)
                                    append(">>")
                                    append("\n基于以下文档内容回答我的问题：\n\n")
                                    append(text)
                                    append("\n<<DOC:end>>")
                                }
                            }
                        }.joinToString("\n\n")

                        val attachBlock = if (ready.isEmpty()) {
                            ""
                        } else {
                            // ★ doc 而不是 file：告诉 contentForApi「这个文件的正文
                            //   已经在上面的 DOC 哨兵里了」，别再让模型去调 read_file。
                            "\n\n" + ready.joinToString("\n") { att ->
                                val kind = when {
                                    att.isImage -> "image"
                                    !att.parsedText.isNullOrBlank() -> "doc"
                                    else -> "file"
                                }
                                "<<ATTACH:$kind|${att.path}>>"
                            }
                        }

                        // 文档正文在前、问题在后：brief 要的顺序是「文档 → 问题」。
                        // 「问题：」这个前缀也只在真有文档时才加，没文档就还是用户原话。
                        val docPrefix = if (docBlock.isEmpty()) "" else "$docBlock\n\n问题："
                        val trimmed = (docPrefix + inputText.trim() + attachBlock).trim()

                        // 空消息不发；正在流式回复时也不发
                        if (trimmed.isNotEmpty() && !isStreaming) {

                            inputText = ""
                            attachments = emptyList()
                            isStreaming = true

                            // ★ 先把这一刻的会话 id 钉住。后面所有回调都认它，
                            //   不再读 currentSessionId。
                            val sessionId =
                                currentSessionId.ifBlank { newSession() }

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
                                    // ★ v1.1 联网搜索的 UI 提示（第二步要求的那个）。
                                    //   只对 web_search 显示：读写文件是毫秒级的，
                                    //   弹一条提示只会闪一下更烦人；而搜索要等网络，
                                    //   不告诉用户"在搜什么"，那 2~3 秒看起来就像卡死了。
                                    if (toolName == "web_search") {
                                        searchingQuery = argOf(argsJson, "query").trim()
                                    }

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
                                    } catch (_: Exception) {
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
                                    // ★ v1.1：不管是不是搜索，执行完就收起状态条。
                                    //   工具是一轮一个串行执行的（runAgentLoop 里 for 循环逐个 await），
                                    //   所以这里无条件清空不会误清掉另一个还在跑的搜索。
                                    searchingQuery = ""

                                    val slot = toolCardSlots[callId] ?: -1

                                    // 先把新的 content 算出来，内存和数据库用同一份
                                    val current = sessions.firstOrNull { it.id == sessionId }
                                        ?.messages?.getOrNull(slot)?.content ?: ""
                                    val updated = try {
                                        JSONObject(current).apply {
                                            put("ok", ok)
                                            put("output", output)
                                        }.toString()
                                    } catch (_: Exception) {
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

                                // token 用量回来了：更新 InfoBar + 累进跨轮账本
                                //（record 主线程只做一次快照 copy，落盘全在 Dispatchers.IO，
                                //  绝不碰 50ms 流式批处理路径）
                                onUsage = { u ->
                                    lastUsage = u
                                    UsageLedger.record(context, u)
                                },

                                // ask_user：把问题交给 ChatScreen 的桥接实现
                                onAskUser = { q -> vm.askUserAndWait(q) },

                                onDone = { total ->
                                    Log.d(TAG, "onDone: total=$total")
                                    isStreaming = false
                                    // ★ 兜底：正常路径下 onToolCallFinished 已经清过了，
                                    //   但「模型直接给答案、一次工具都没调」时不会走那里。
                                    searchingQuery = ""
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
                                    // ★ 搜索失败 / 网络断了都要把状态条收掉，
                                    //   否则界面上会永远挂着一条「正在联网搜索」。
                                    searchingQuery = ""
                                    // ★ 这里才把 ErrorText 渲染成文字：
                                    //   context 是 ChatScreen 里拿到的 localizedContext，
                                    //   已经按 App 内语言设置包装过，所以切英文后错误气泡也是英文。
                                    val errMsg = err.resolve(context)
                                    Log.e(TAG, "stream error: $errMsg")
                                    // 把错误显示在气泡里，不然界面上什么都看不到。
                                    // 标上 isError = true，这样下次发消息时它不会被
                                    // 当成 AI 的真实回复发给模型。
                                    updateSession(sessionId) { s ->
                                        val msgs = s.messages
                                        if (msgs.isNotEmpty() && !msgs.last().isUser) {
                                            s.copy(
                                                messages = msgs.dropLast(1) +
                                                    msgs.last().copy(
                                                        content = errMsg,
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
                        // ★ 用户点了停止：搜索请求可能还在路上，状态条必须一起收掉
                        searchingQuery = ""

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
            title = { Text(stringResource(R.string.storage_tip_title)) },
            text = {
                Text(
                    stringResource(R.string.storage_tip_body),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    openAllFilesAccessSettings(context)
                    showStorageTip = false
                }) { Text(stringResource(R.string.storage_tip_open)) }
            },
            dismissButton = {
                TextButton(onClick = { showStorageTip = false }) {
                    Text(stringResource(R.string.storage_tip_later))
                }
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
    // 删除确认框的目标。
    // ★ 刻意和 menuTarget 分开：菜单只是「选动作」，真正下手前必须再问一次。
    var deleteTarget by remember { mutableStateOf<SidebarSession?>(null) }

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
            // ---------- 品牌行：CoolStream + 版本号 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 一个小的方形标记，像终端提示符，比纯文字更有极客感
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(cs.primary)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "CoolStream",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = BuildConfig.BUILD_VERSION_NAME,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }

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
                                        text = stringResource(R.string.search_chats),
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
                DRAWER_GROUPS.forEachIndexed { groupIndex, labelRes ->
                    val groupItems = filtered.filter { dayBucket(it.createdAt) == groupIndex }
                    if (groupItems.isNotEmpty()) {
                        item(key = "header-$groupIndex") {
                            Text(
                                text = stringResource(labelRes),
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
                            text = stringResource(R.string.no_matching_chats),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 16.dp)
                        )
                    }
                }
            }

            // ---------- Token / 花费面板（v1.1 阶段三任务二） ----------
            //
            //   极简两行等宽字：今天 + 累计。数据源是 UsageLedger.today
            //   （mutableStateOf，record() 一改这里就重组，无需手动刷新）。
            //   布局只是两个 Text，不参与流式渲染热路径，不碰 50ms 批处理。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
            ) {
                val ledger = UsageLedger.today.value
                Text(
                    text = "TODAY  " + formatTokens(ledger.todayTotalTokens) +
                        " / " + formatCny(ledger.todayCny),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "TOTAL " + formatTokens(ledger.totalTotalTokens) +
                        " / " + formatCny(ledger.totalCny),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                Text(stringResource(R.string.sidebar_settings), fontFamily = FontFamily.Monospace)
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
    //
    // ★ 这里原来有个很反人类的设计，已经改掉：
    //   旧代码把「删除」挂在 dismissButton 上 —— 而 AlertDialog 里
    //   dismissButton 就长在左边的「取消位」。用户以为自己点的是取消，
    //   结果会话直接被删，而且没有任何二次确认。
    //
    //   现在的做法：
    //     · 两个动作都是并排的普通文本按钮，谁也不占「确认 / 取消」的位置，
    //       点哪里就发生什么事，不存在「位置暗示」和实际行为相反的情况
    //     · 删除只负责打开 deleteTarget 确认框，真正的 onDelete 在确认框里调
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
                Column {
                    Text(
                        text = stringResource(R.string.session_menu_title),
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        onClick = {
                            renameText = target.title
                            renameTarget = target
                            menuTarget = null
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.action_rename))
                    }
                    TextButton(
                        onClick = {
                            // ★ 这里绝不能直接 onDelete —— 只是把确认框顶起来
                            deleteTarget = target
                            menuTarget = null
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = Color(0xFFF85149)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // ---------- 删除确认：删了就没了，必须单独问一次 ----------
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = glassDrawer(),
            titleContentColor = cs.onSurface,
            textContentColor = cs.onSurfaceVariant,
            title = { Text(stringResource(R.string.delete_session_title)) },
            text = {
                Text(
                    // 把会话名带进去 —— 用户能确认自己删的是哪一个
                    text = stringResource(R.string.delete_session_body, target.title),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleteTarget = null
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = Color(0xFFF85149)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
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
            title = { Text(stringResource(R.string.rename_session_title)) },
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
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}


// 侧边栏分组标题。
// ★ 存的是资源 id 而不是中文字面量：这个 val 在顶层、不是 Composable，
//   拿不到 stringResource，所以只能在渲染处再解析成文案。
private val DRAWER_GROUPS = listOf(
    R.string.group_today,
    R.string.group_yesterday,
    R.string.group_earlier
)

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
// 5.5 首次启动引导（Onboarding）
//
// 目标很朴素：一个完全不了解这个 App 的人，下载 APK 之后
// 不看任何文档，也能在自己手机上把它跑起来。
//
// 三步：
//   ① API Key —— 没有这个发不出消息，硬性要求
//   ② 文件权限 —— 没有这个所有工具都废掉，硬性要求
//   ③ Shizuku   —— 只影响 run_shell，可选，只做状态提示
//
// ⚠️ 第 ③ 步刻意不做硬性拦截：Shizuku 需要另外装一个 App、还要电脑激活，
//    拿它当门槛等于把 90% 的用户挡在门外。
// ============================================================
// ============================================================
// 5.5 首次启动引导（Onboarding）—— 分页版
//
// 结构：两页，用 when(currentPage) 直接切换（不用 Pager，原因见页面内的注释），
//       底部两个小圆点指示器。
//
//   第 1 页：选语言
//     · 大号 Logo + 欢迎语
//     · 【简体中文】/【English】两个大按钮
//     · 点了立刻 setLanguage() 并自动滑到第 2 页（不要求用户再点下一步）
//
//   第 2 页：基础配置
//     · API Key 输入（带显示/隐藏）
//     · 文件访问权限（跳系统设置）
//     · Shizuku 状态检查（可选）
//     · 底部【跳过】/【开始使用】
//
// ★ 核心原则：引导页【不设强制门槛】。
//   任何一步没配好，用户都能通过「跳过」进主界面 ——
//   拦在门口只会让人以为 App 坏了。
//   真正的兜底在发送那一刻：没 Key 就弹提示，见 ChatScreen 的 onSend。
//
// ★ 语言切换的时序坑：
//   setLanguage() 会改 AppSettings.language，而 MainActivity 里
//   localizedContext 是 remember(AppSettings.language) 的 ——
//   所以改完语言整棵树会重组。翻页状态因此【必须】是独立的、可预测的 Int，
//   不能挂在会被重建的 Pager 内部状态上。
// ============================================================
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    // 当前页。0 = 选语言，1 = 基础配置。
    // ★ 用 rememberSaveable 而不是 remember：
    //   旋转屏幕 / 系统重建 Activity 时，用户应该停在原来那一页，
    //   而不是被弹回第 1 页重新选一次语言。
    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    val pageCount = 2

    // 【诊断】把真实页号打出来。排查「首启跳过选语言页」时全靠这行日志：
    // 如果它一直是 0、界面上却是第 2 页，那就是渲染问题而不是状态问题。
    LaunchedEffect(currentPage) {
        Log.d(TAG, "onboarding page = $currentPage / ${pageCount - 1}")
    }

    // ---------- 第 2 页的状态 ----------
    var draftKey by remember { mutableStateOf(ApiConfig.apiKey) }
    var draftBase by remember { mutableStateOf(ApiConfig.baseUrl) }
    var draftModel by remember { mutableStateOf(ApiConfig.model) }
    var showKey by remember { mutableStateOf(false) }

    var storageOk by remember { mutableStateOf(hasAllFilesAccess(context)) }
    var shizukuOk by remember { mutableStateOf(isShizukuGranted() && isShizukuRunning()) }

    // ★ 权限状态刷新：监听 ON_RESUME，不再用定时轮询。
    //
    //   旧的写法是 while (true) { 查一次; delay(800) } —— 两个问题：
    //     1. App 退到后台它照样每 800ms 醒一次，和本项目「不发热」的第一优先级冲突
    //     2. 用户从系统设置页返回后，最多要等 800ms 界面才变，手感像卡住
    //
    //   改成 LifecycleEventObserver：只在「第 2 页可见」时挂监听，
    //   ON_RESUME 那一刻立刻重查 —— 返回的瞬间就刷新，且后台零唤醒。
    //
    //   注意 key 里带了 currentPage：离开第 2 页就自动解绑，
    //   不会在别的页面上做无用检查。
    // ⚠️ LocalLifecycleOwner 在 compose-ui 里已标记 deprecated，
    //    官方建议换到 androidx.lifecycle.compose 那个 —— 但那需要新引入
    //    lifecycle-runtime-compose 依赖。这里只是挂一个观察者，行为完全一致，
    //    不值得为一个 deprecation 多拉一个库，故显式抑制。
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, currentPage) {
        if (currentPage != 1) {
            return@DisposableEffect onDispose { }
        }

        // 进入第 2 页先查一次：用户可能早就授权过了，不该显示「待完成」
        storageOk = hasAllFilesAccess(context)
        shizukuOk = isShizukuGranted() && isShizukuRunning()

        val observer = LifecycleEventObserver { _, event ->
            // 从系统设置页切回来 → 立刻重查，绝不沿用旧缓存
            if (event == Lifecycle.Event.ON_RESUME) {
                storageOk = hasAllFilesAccess(context)
                shizukuOk = isShizukuGranted() && isShizukuRunning()
                Log.d(TAG, "onResume 重查权限: storage=$storageOk, shizuku=$shizukuOk")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * 收尾并进入主界面。
     *
     * ★ 「跳过」和「开始使用」走的是同一条路 —— 区别只在于
     *   前者不校验 Key，后者也【不】校验（用户可能就是没 Key 想先看看）。
     *   两者都会把草稿里的配置存下来（空的也无所谓），并记下 onboard_done。
     */
    fun finishAndEnter() {
        // 空 Key 不要覆盖已有配置：万一用户只是重进引导页看了一眼
        if (draftKey.isNotBlank()) {
            saveApiConfig(context, draftKey, draftBase, draftModel)
        }
        // 工作区要在权限拿到之后再建，否则 mkdirs 必失败
        ensureDefaultWorkspace(context)
        setOnboarded(context, true)
        onFinish()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = cs.background,
        contentColor = cs.onBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ★ 这里【不用】HorizontalPager，也不用任何动画容器。
            //
            //   踩过的坑：Pager 放在 Column(weight(1f)) 里时，首次组合测到的高度
            //   是 0，它据此把初始页算成了第 2 页（currentPage 明明是 0，
            //   界面上却是「基础配置」）。表现为「第一次启动跳过选语言页」，
            //   而且每次冷启动都可能不一样，极难复现定位。
            //
            //   引导页只有两页、也不需要「跟手拖拽」，用 Pager 属于杀鸡用牛刀。
            //   改成 when(currentPage) 之后：
            //     · 状态只有一个 Int，不存在测量塌陷
            //     · 没有动画容器参与测量，切换绝不会乱跳
            //
            //   ⚠️ 刻意不加 AnimatedContent / Crossfade：
            //      它们需要 androidx.compose.animation 依赖，而且同样会引入
            //      一层额外的测量与重组。引导页第一优先级是「每次打开都停在
            //      正确的那页」，动画简陋完全可以接受。
            //
            //   外面这层 Box(weight(1f)) 是必须的：
            //     它给页面一个「确定的、被父布局分配好的高度」，
            //     页面内部的 fillMaxSize() 才有意义。少了它，
            //     Column 里的可变高度子项会互相挤压。
            Box(modifier = Modifier.weight(1f)) {
                when (currentPage) {
                    0 -> OnboardLanguagePage(
                        current = AppSettings.language,
                        onPick = { lang ->
                            // ① 先落库 + 改全局状态
                            setLanguage(context, lang)
                            // ② 再翻页：只改一个 Int，不依赖任何测量结果
                            currentPage = 1
                        }
                    )

                    else -> OnboardConfigPage(
                        draftKey = draftKey,
                        draftBase = draftBase,
                        draftModel = draftModel,
                        showKey = showKey,
                        storageOk = storageOk,
                        shizukuOk = shizukuOk,
                        onKeyChange = { draftKey = it },
                        onBaseChange = { draftBase = it },
                        onModelChange = { draftModel = it },
                        onToggleShowKey = { showKey = !showKey },
                        onOpenStorage = { openAllFilesAccessSettings(context) },
                        onShizukuClick = {
                            if (isShizukuGranted()) {
                                bindShizukuUserService(context)
                            } else {
                                requestShizukuPermission()
                            }
                        },
                        onSkip = { finishAndEnter() },
                        onStart = { finishAndEnter() }
                    )
                }
            }

            // ---------- 底部：分页指示器 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pageCount) { index ->
                    val active = currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 5.dp)
                            .size(if (active) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) cs.primary
                                else cs.onSurfaceVariant.copy(alpha = 0.35f)
                            )
                    )
                }
            }
        }
    }
}

/**
 * 引导页第 1 页：选语言。
 *
 * 设计上刻意做得「只剩两个选项」—— 大 Logo、一句欢迎语、两个大按钮。
 * 这一页的目标是让用户 3 秒内做完决定，不给他别的选择。
 */
@Composable
private fun OnboardLanguagePage(
    current: String,
    onPick: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // ---------- 大号 Logo ----------
        // 用「圆角方块 + 终端提示符」的极客符号，而不是图片资源：
        // 这样深色/浅色都能自适应，也不用为多分辨率准备 png。
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(cs.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ">_",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.background
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = stringResource(R.string.ob_welcome_title),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.headlineSmall,
            color = cs.onSurface
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.ob_welcome_sub),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(44.dp))

        // ---------- 两个大按钮 ----------
        // 当前语言对应的按钮加一个描边，让用户知道现在选的是什么。
        LanguageButton(
            label = stringResource(R.string.ob_lang_zh),
            selected = current == "zh",
            onClick = { onPick("zh") }
        )
        Spacer(Modifier.height(14.dp))
        LanguageButton(
            label = stringResource(R.string.ob_lang_en),
            selected = current == "en",
            onClick = { onPick("en") }
        )
    }
}

/** 引导页的语言大按钮：占满宽度、圆角、毛玻璃质感 */
@Composable
private fun LanguageButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (selected) cs.primary.copy(alpha = 0.16f) else glassFill(),
        border = BorderStroke(
            1.dp,
            if (selected) cs.primary else glassEdge()
        ),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) cs.primary else cs.onSurface
            )
        }
    }
}

/**
 * 引导页第 2 页：基础配置。
 *
 * 三个配置项都是「能跳过」的：
 *   · API Key  —— 留空也能进主界面，发送时才拦
 *   · 文件权限 —— 没有就只能聊天，工具全废，但不影响进界面
 *   · Shizuku  —— 只影响 run_shell，纯可选
 */
@Composable
private fun OnboardConfigPage(
    draftKey: String,
    draftBase: String,
    draftModel: String,
    showKey: Boolean,
    storageOk: Boolean,
    shizukuOk: Boolean,
    onKeyChange: (String) -> Unit,
    onBaseChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onToggleShowKey: () -> Unit,
    onOpenStorage: () -> Unit,
    onShizukuClick: () -> Unit,
    onSkip: () -> Unit,
    onStart: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = stringResource(R.string.ob_config_title),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.headlineSmall,
            color = cs.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ob_config_sub),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        // ---------- ① API ----------
        OnboardStep(
            index = 1,
            title = stringResource(R.string.ob_step1_title),
            done = draftKey.isNotBlank()
        ) {
            ConfigField(
                label = stringResource(R.string.field_api_base),
                value = draftBase,
                placeholder = DEFAULT_API_BASE,
                helper = "",
                onValueChange = onBaseChange
            )
            ConfigField(
                label = stringResource(R.string.field_api_key),
                value = draftKey,
                placeholder = "sk-...",
                helper = stringResource(R.string.ob_key_helper),
                masked = !showKey,
                trailing = {
                    TextButton(onClick = onToggleShowKey) {
                        Text(
                            text = if (showKey) {
                                stringResource(R.string.action_hide)
                            } else {
                                stringResource(R.string.action_show)
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                onValueChange = onKeyChange
            )
            ConfigField(
                label = stringResource(R.string.field_model),
                value = draftModel,
                placeholder = DEFAULT_MODEL,
                helper = stringResource(R.string.ob_model_helper),
                onValueChange = onModelChange
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------- ② 文件权限 ----------
        OnboardStep(
            index = 2,
            title = stringResource(R.string.ob_step2_title),
            done = storageOk
        ) {
            Text(
                text = stringResource(R.string.ob_step2_body),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenStorage,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (storageOk) cs.surfaceVariant else cs.primary,
                    contentColor = if (storageOk) cs.onSurfaceVariant else cs.onPrimary
                )
            ) {
                Text(
                    text = if (storageOk) {
                        stringResource(R.string.ob_step2_done)
                    } else {
                        stringResource(R.string.ob_step2_action)
                    },
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- ③ Shizuku（可选） ----------
        OnboardStep(
            index = 3,
            title = stringResource(R.string.ob_step3_title),
            done = shizukuOk
        ) {
            Text(
                text = stringResource(R.string.ob_step3_body),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onShizukuClick) {
                Text(
                    text = if (shizukuOk) {
                        stringResource(R.string.ob_step3_check)
                    } else {
                        stringResource(R.string.ob_step3_not_running)
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Spacer(Modifier.height(26.dp))

        // ---------- 底部：跳过 / 开始使用 ----------
        //
        // ★ 两个按钮都不做「必填校验」。
        //   用户没 Key 也能进 —— 真要用的时候在发送那一刻才提示。
        //   把人拦在门口只会让他以为 App 坏了。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    // 用短版「跳过」：原有的 ob_skip 带「（工具将不可用）」的说明，
                    // 放在分页引导的按钮里太长，会把两个按钮挤变形。
                    text = stringResource(R.string.ob_skip_short),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1.6f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary
                )
            ) {
                Text(
                    text = stringResource(R.string.ob_start),
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

/** 引导页里的一步：序号 + 标题 + 完成状态 + 内容 */
@Composable
private fun OnboardStep(
    index: Int,
    title: String,
    done: Boolean,
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accent = Color(0xFF3FB950)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = glassFill(),
        border = BorderStroke(1.dp, glassEdge()),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (done) accent else cs.primary.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (done) "✓" else "$index",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (done) Color(0xFF0D1117) else cs.primary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (done) stringResource(R.string.common_done) else stringResource(R.string.common_pending),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done) accent else cs.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

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

    var tab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf(
        stringResource(R.string.tab_general),
        stringResource(R.string.tab_model),
        stringResource(R.string.tab_plugins),
        stringResource(R.string.tab_about)
    )

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
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // ---------- 顶部 Tab ----------
            TabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = cs.onSurface
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = {
                            Text(
                                text = title,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    )
                }
            }

            // ---------- 内容 ----------
            when (tab) {
                0 -> GeneralTab(
                    context = context,
                    workspace = workspace,
                    shizukuGranted = shizukuGranted,
                    onPickWorkspace = { folderPicker.launch(null) }
                )
                1 -> ModelTab()
                2 -> PluginsTab()
                else -> AboutTab()
            }
        }
    }
}

/** 通用：外观、字号、卡片默认展开、工作区、权限 */
@Composable
private fun GeneralTab(
    context: Context,
    workspace: String,
    shizukuGranted: Boolean,
    onPickWorkspace: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    // ★ 权限状态刷新：从系统设置页回来时立刻重算。
    //
    //   权限行原来是在组合时直接调 hasAllFilesAccess(context) ——
    //   看着没问题，但用户去系统设置打开权限再返回时，Compose 不一定重组，
    //   于是「权限明明开了，这里还显示未开启」。
    //
    //   加一个 ON_RESUME 计数当 key：切回来的瞬间 tick+1 → 相关 remember 失效
    //   → 立刻重新查权限。和引导页用的是同一套思路。
    var resumeTick by remember { mutableIntStateOf(0) }
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
    ) {
        // ---------- Agent 模式 ----------
        // 放最上面，因为它是「决定下面所有工具怎么用」的总开关 ——
        // 先去调插件开关、再发现预设不对，是很容易踩的顺序坑。
        item { SectionHeader(stringResource(R.string.section_agent)) }
        item {
            ChoiceRow(
                label = stringResource(R.string.label_preset),
                options = listOf(
                    AgentPreset.STANDARD.id to stringResource(R.string.preset_standard),
                    AgentPreset.PTC.id to stringResource(R.string.preset_ptc),
                    AgentPreset.MINIMAL.id to stringResource(R.string.preset_minimal)
                ),
                selected = AppSettings.preset.id,
                onSelect = { setPreset(context, AgentPreset.of(it)) }
            )
        }
        item {
            val desc = when (AppSettings.preset) {
                AgentPreset.STANDARD -> stringResource(R.string.desc_preset_standard)
                AgentPreset.PTC -> stringResource(R.string.desc_preset_ptc)
                AgentPreset.MINIMAL -> stringResource(R.string.desc_preset_minimal)
            }
            Text(
                text = desc,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        item { SectionHeader(stringResource(R.string.section_appearance)) }
        item {
            ChoiceRow(
                label = stringResource(R.string.label_language),
                options = listOf(
                    "system" to stringResource(R.string.lang_system),
                    "zh" to stringResource(R.string.lang_zh),
                    "en" to stringResource(R.string.lang_en)
                ),
                selected = AppSettings.language,
                onSelect = { setLanguage(context, it) }
            )
        }
        item {
            ChoiceRow(
                label = stringResource(R.string.label_theme),
                options = listOf(
                    "system" to stringResource(R.string.theme_system),
                    "light" to stringResource(R.string.theme_light),
                    "dark" to stringResource(R.string.theme_dark)
                ),
                selected = AppSettings.themeMode,
                onSelect = { setThemeMode(context, it) }
            )
        }
        item {
            ChoiceRow(
                label = stringResource(R.string.label_font),
                options = listOf(
                    "0.9" to stringResource(R.string.font_small),
                    "1.0" to stringResource(R.string.font_normal),
                    "1.15" to stringResource(R.string.font_large)
                ),
                selected = AppSettings.fontScale.toString(),
                onSelect = { setFontScale(context, it.toFloat()) }
            )
        }

        item { SectionHeader(stringResource(R.string.section_tool_card)) }
        item {
            SettingSwitchRow(
                label = stringResource(R.string.label_card_expanded),
                desc = stringResource(R.string.desc_card_expanded),
                checked = AppSettings.cardExpanded,
                onChange = { setCardExpanded(context, it) }
            )
        }

        item { SectionHeader(stringResource(R.string.section_workspace)) }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    // 点一下弹系统的「选择目录」，选完自动转成绝对路径并保存
                    .clickable(onClick = onPickWorkspace)
                    .padding(vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.label_workspace),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (workspace.isBlank()) stringResource(R.string.action_pick_dir) else stringResource(R.string.action_change),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = workspace.ifBlank { stringResource(R.string.workspace_unset) },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (workspace.isBlank()) cs.onSurfaceVariant else Color(0xFF3FB950)
                )
            }
        }

        item { SectionHeader(stringResource(R.string.section_permission)) }
        item {
            // key 带上 resumeTick：从系统设置切回来时自动重算，不必等用户手动刷新
            val storageOk = remember(resumeTick) { hasAllFilesAccess(context) }
            PermissionRow(
                label = stringResource(R.string.label_storage_perm),
                status = if (storageOk) stringResource(R.string.common_on) else stringResource(R.string.common_off),
                ok = storageOk,
                onClick = { openAllFilesAccessSettings(context) }
            )
        }
        item {
            val running = isShizukuRunning()
            PermissionRow(
                label = stringResource(R.string.label_shizuku),
                status = when {
                    !running -> stringResource(R.string.shizuku_not_running)
                    shizukuGranted -> stringResource(R.string.shizuku_granted)
                    else -> stringResource(R.string.shizuku_tap_grant)
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
    }
}

/** 模型与 API：地址 / Key / 模型名都能直接改 */
@Composable
private fun ModelTab() {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    // 草稿状态：改了还没点保存时，不影响正在使用的配置
    var draftKey by remember { mutableStateOf(ApiConfig.apiKey) }
    var draftBase by remember { mutableStateOf(ApiConfig.baseUrl) }
    var draftModel by remember { mutableStateOf(ApiConfig.model) }
    var showKey by remember { mutableStateOf(false) }
    // 保存结果的提示：只存 key，真正的文案在渲染时用 stringResource 拼。
    // ★ 以前是直接存中文、再用 hint.startsWith("已保存") 判断颜色 ——
    //   一旦做多语言，这个判断立刻失效（英文文案不是「已保存」开头）。
    //   所以拆成「状态 + 文案 key」两部分，颜色只看状态。
    var hintRes by remember { mutableStateOf<Int?>(null) }
    var hintArgs by remember { mutableStateOf<Array<out Any>>(emptyArray()) }
    var hintIsOk by remember { mutableStateOf(false) }

    val keyOk = draftKey.isNotBlank()
    val dirty = draftKey != ApiConfig.apiKey ||
        draftBase != ApiConfig.baseUrl ||
        draftModel != ApiConfig.model

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
    ) {
        item { SectionHeader(stringResource(R.string.section_model_api)) }

        item {
            ConfigField(
                label = stringResource(R.string.field_api_base),
                value = draftBase,
                placeholder = DEFAULT_API_BASE,
                helper = stringResource(R.string.api_base_helper),
                onValueChange = { draftBase = it; hintRes = null; hintIsOk = false }
            )
        }

        item {
            ConfigField(
                label = stringResource(R.string.field_api_key),
                value = draftKey,
                placeholder = "sk-...",
                helper = if (keyOk) {
                    stringResource(R.string.api_key_saved, draftKey.length)
                } else {
                    stringResource(R.string.api_key_empty)
                },
                masked = !showKey,
                trailing = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(
                            text = if (showKey) {
                                stringResource(R.string.action_hide)
                            } else {
                                stringResource(R.string.action_show)
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                onValueChange = { draftKey = it; hintRes = null; hintIsOk = false }
            )
        }

        item {
            ConfigField(
                label = stringResource(R.string.field_model),
                value = draftModel,
                placeholder = DEFAULT_MODEL,
                helper = stringResource(R.string.model_helper),
                onValueChange = { draftModel = it; hintRes = null; hintIsOk = false }
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        saveApiConfig(context, draftKey, draftBase, draftModel)
                        hintRes = R.string.save_ok
                        hintArgs = emptyArray()
                        hintIsOk = true
                    },
                    enabled = keyOk && dirty,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.primary,
                        contentColor = cs.onPrimary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.action_save),
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    // 没点过保存时，按当前状态给一句实时提示
                    text = if (hintRes != null) {
                        stringResource(hintRes!!, *hintArgs)
                    } else {
                        when {
                            !keyOk -> stringResource(R.string.save_key_required)
                            !dirty -> stringResource(R.string.save_no_change)
                            else -> stringResource(R.string.save_dirty)
                        }
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    // ★ 颜色只看状态，不再靠中文前缀判断
                    color = if (hintIsOk) Color(0xFF3FB950) else cs.onSurfaceVariant
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.full_url, chatCompletionsUrl()),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp)
            )
        }

        item { SectionHeader(stringResource(R.string.section_runtime)) }
        item { InfoRow(stringResource(R.string.label_batching), "${FLUSH_INTERVAL_MS}ms") }
        item {
            InfoRow(stringResource(R.string.label_context), contextWindowLabel())
        }
        item {
            InfoRow(
                stringResource(R.string.label_history),
                stringResource(R.string.history_value, MAX_HISTORY_MESSAGES)
            )
        }
        item {
            InfoRow(
                stringResource(R.string.label_max_rounds),
                stringResource(R.string.rounds_value, MAX_TOOL_ROUNDS)
            )
        }

        item { SectionHeader(stringResource(R.string.section_billing)) }
        item {
            Text(
                text = stringResource(R.string.billing_body),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }
    }
}

/** 设置页里的一行输入框（带说明文字） */
@Composable
private fun ConfigField(
    label: String,
    value: String,
    placeholder: String,
    helper: String,
    onValueChange: (String) -> Unit,
    masked: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            placeholder = {
                Text(
                    text = placeholder,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            visualTransformation = if (masked) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailingIcon = trailing,
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.primary,
                unfocusedBorderColor = glassEdge(),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent
            )
        )
        if (helper.isNotEmpty()) {
            Text(
                text = helper,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 3.dp)
            )
        }
    }
}

/** 插件：七个工具的开关 */
@Composable
private fun PluginsTab() {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.plugins_hint_off),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp, end = 4.dp)
            )
        }

        // ★ 预设和插件是两层开关，容易搞混，这里说清楚它们的关系。
        //   没有这段提示的话，用户会以为「关掉 read_file」在 PTC 模式下也该消失，
        //   然后在模型硬写代码调用时报错时一脸问号。
        if (AppSettings.preset != AgentPreset.STANDARD) {
            item {
                Text(
                    text = when (AppSettings.preset) {
                        AgentPreset.PTC -> stringResource(R.string.plugins_hint_ptc)
                        AgentPreset.MINIMAL -> stringResource(R.string.plugins_hint_minimal)
                        AgentPreset.STANDARD -> ""
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.primary.copy(alpha = 0.85f),
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp, end = 4.dp)
                )
            }
        }

        itemsIndexed(ALL_TOOL_NAMES) { _, name ->
            val checked = name in AppSettings.enabledTools
            ToolSwitchCard(
                name = name,
                desc = TOOL_DESCRIPTIONS[name] ?: "",
                checked = checked,
                onChange = { setToolEnabled(context, name, it) }
            )
        }

        item {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.plugins_enabled_count,
                    AppSettings.enabledTools.size,
                    ALL_TOOL_NAMES.size
                ) + if (AppSettings.enabledTools.isEmpty()) {
                    stringResource(R.string.plugins_all_off)
                } else "",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

/** 关于 */
@Composable
private fun AboutTab() {
    val cs = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
    ) {
        item { SectionHeader(stringResource(R.string.section_version)) }
        item {
            // ★ 版本号来自 BuildConfig.BUILD_VERSION_NAME，
            //   由 build.gradle.kts 在【编译时】按「0.yyMMddHHmm」生成。
            //   每次构建都会变，不需要手动改。
            Text(
                text = stringResource(R.string.about_version, BuildConfig.BUILD_VERSION_NAME),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item { SectionHeader(stringResource(R.string.section_stack)) }
        item {
            InfoRow(
                stringResource(R.string.about_stack_ui),
                stringResource(R.string.about_stack_ui_value)
            )
        }
        item {
            InfoRow(
                stringResource(R.string.about_stack_net),
                stringResource(R.string.about_stack_net_value)
            )
        }
        item {
            InfoRow(
                stringResource(R.string.about_stack_db),
                stringResource(R.string.about_stack_db_value)
            )
        }
        item {
            InfoRow(
                stringResource(R.string.about_stack_shell),
                stringResource(R.string.about_stack_shell_value)
            )
        }
        item {
            InfoRow(
                stringResource(R.string.about_stack_tools),
                stringResource(R.string.about_stack_tools_value, ALL_TOOL_NAMES.size)
            )
        }

        item { SectionHeader(stringResource(R.string.section_notes)) }
        item {
            Text(
                text = stringResource(R.string.about_notes),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * 一行「几个选项选一个」的分段控件。
 *
 * 没用 Material 的 SegmentedButton —— 那个在窄屏上会挤成一团，
 * 这里用等宽排 + 玻璃描边，和整体极客风更连贯。
 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (id, text) ->
                val on = id == selected
                Surface(
                    color = if (on) cs.primary.copy(alpha = 0.16f) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (on) cs.primary.copy(alpha = 0.55f) else glassEdge()
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(id) }
                ) {
                    Text(
                        text = text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (on) cs.primary else cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 9.dp)
                    )
                }
            }
        }
    }
}

/** 带开关的一行（现在只有「默认展开」用，但插件卡片另有自己的样式） */
@Composable
private fun SettingSwitchRow(
    label: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface
            )
            if (desc.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = desc,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF0D1117),
                checkedTrackColor = Color(0xFF3FB950)
            )
        )
    }
}

/**
 * 插件页里的一张小卡片：图标 + 工具名 + 说明 + 开关。
 *
 * 图标复用工具卡片那套 toolVisual()，让「设置里关的是哪个」和
 * 「聊天里出现的是哪个」能对上号。
 */
@Composable
private fun ToolSwitchCard(
    name: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val visual = toolVisual(name)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = glassFill(),
        border = BorderStroke(1.dp, glassEdge()),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = visual.icon,
                contentDescription = null,
                // 关掉之后图标跟着变灰，不用看开关也知道状态
                tint = if (checked) visual.tint else cs.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (checked) cs.onSurface else cs.onSurfaceVariant
                )
                if (desc.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = desc,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF0D1117),
                    checkedTrackColor = Color(0xFF3FB950)
                )
            )
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
@OptIn(ExperimentalFoundationApi::class)
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
    val context = LocalContext.current

    // 用户气泡：实心蓝 + 白字
    // AI 气泡：深/浅灰底 + 1dp 描边 + 正文色
    // 错误气泡：暗红毛玻璃。和用户气泡、AI 气泡一眼能分开
    val isErr = message.isError
    val errorAccent = Color(0xFFF85149)

    val bubbleColor = when {
        isErr -> errorAccent.copy(alpha = 0.14f)
        isUser -> cs.primary
        else -> cs.surface
    }
    val textColor = if (isUser && !isErr) cs.onPrimary else cs.onSurface

    // 界面上不显示 <<ATTACH:...>> 这些内部标记
    val shownContent = remember(message.content) { displayContent(message.content) }

    // 正文为空说明 AI 还在思考（占位行），或者上次流式中断了
    val placeholder = shownContent.isBlank() && !isUser

    // ★ 只有「AI 的、非错误的、非空的」正文才走 Markdown。
    //   用户自己打的内容不解释成 Markdown —— 免得他打了 # 或 ** 被吃掉。
    val useMarkdown = markdown && !isUser && !message.isError && shownContent.isNotBlank()

    // ---------- 长按复制 ----------
    var menuOpen by remember { mutableStateOf(false) }
    // 气泡高度：菜单要贴着气泡下沿弹出，所以得先量一下
    var bubbleHeight by remember { mutableIntStateOf(0) }
    // 代码块只在正文变化时重新抽一次，重组时不重复扫描
    val codeBlocks = remember(message.content) { extractCodeBlocks(displayContent(message.content)) }

    // 外层 Box 负责"靠左还是靠右"
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        // ★ 再套一层 Box：Popup 的 alignment 是相对「直接父布局」算的。
        //   不套这一层，菜单会贴着整行（= 屏幕边缘）出现，而不是贴着气泡。
        Box {
            // 内层 Surface 负责"长什么样"
            Surface(
                color = bubbleColor,
                // 大圆角 24dp：胶囊感
                shape = RoundedCornerShape(24.dp),
                // 无阴影：阴影高度和色调高度都归零
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
                // 两种气泡都加一条极微弱的半透明高光边框（毛玻璃质感）
                border = BorderStroke(
                    1.dp,
                    if (isErr) errorAccent.copy(alpha = 0.45f) else glassEdge()
                ),
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .onGloballyPositioned { bubbleHeight = it.size.height }
                    .combinedClickable(
                        onClick = { /* 单击暂时不做任何事，留给以后编辑/重发 */ },
                        onLongClick = {
                            if (shownContent.isNotBlank()) menuOpen = true
                        }
                    )
            ) {
                // ★ v1.1 文档卡片：解析成功的文档在气泡顶部显示
                //   「📄 已读取文档：xxx.docx (字数: xxx)」。
                //   正文里那一万字不显示（displayContent 已整段剥掉），
                //   所以这张卡片是「模型到底看到了什么」的唯一可见线索。
                val docCards = remember(message.content) { docCardsOf(message.content) }
                Column {
                    docCards.forEach { card ->
                        DocCardRow(
                            card = card,
                            textColor = textColor,
                            modifier = Modifier.padding(
                                start = 14.dp, end = 14.dp, top = 10.dp
                            )
                        )
                    }
                    if (isErr) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = errorAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.error_title),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelMedium,
                                color = errorAccent
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            // 老数据里存的是「错误: xxx」，渲染时把前缀去掉，
                            // 标题已经在上面写着了，正文只留原因
                            text = shownContent
                                .removePrefix("错误: ")
                                .removePrefix("错误："),
                            color = cs.onSurface,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else if (useMarkdown) {
                    MarkdownText(
                        content = shownContent,
                        textColor = textColor,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        onCodeLongPress = {
                            Log.d(TAG, "长按代码块，抽到 ${codeBlocks.size} 段")
                            menuOpen = true
                        }
                    )
                } else {
                    Text(
                        text = if (placeholder) stringResource(R.string.thinking) else shownContent,
                        color = textColor,
                        // 等宽字体：极客风的关键
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                }
            }

            // ---------- 毛玻璃悬浮菜单 ----------
            if (menuOpen) {
                Popup(
                    // 气泡在右边就从右侧展开，在左边就贴着左边
                    alignment = if (isUser) Alignment.TopEnd else Alignment.TopStart,
                    offset = IntOffset(0, bubbleHeight + 8),
                    onDismissRequest = { menuOpen = false },
                    properties = PopupProperties(focusable = true)
                ) {
                    CopyMenu(
                        fullText = shownContent,
                        codeBlocks = codeBlocks,
                        onCopy = { text ->
                            copyToClipboard(context, text)
                            menuOpen = false
                        }
                    )
                }
            }
        }
    }
}

/** 匹配 ``` 围栏代码块；语言标记那一行可有可无 */
private val CODE_FENCE_RE = Regex("```[^\\n]*\\n([\\s\\S]*?)```")

/** 从 Markdown 正文里抽出所有代码块（不含围栏本身） */
private fun extractCodeBlocks(text: String): List<String> {
    if (!text.contains("```")) return emptyList()
    return CODE_FENCE_RE.findAll(text)
        .map { it.groupValues[1].trimEnd('\n', '\r') }
        .filter { it.isNotBlank() }
        .toList()
}

/**
 * 写系统剪贴板。
 *
 * 用平台的 ClipboardManager 而不是 Compose 的 LocalClipboardManager ——
 * 后者在 Compose 版本之间换过 API（ClipboardManager → Clipboard），
 * 平台 API 反而更稳，而且 Android 13+ 复制后系统自己会弹「已复制」提示。
 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (cm == null) {
        Log.e(TAG, "取不到剪贴板服务，复制失败")
        return
    }
    cm.setPrimaryClip(ClipData.newPlainText("CoolStream", text))
    Log.d(TAG, "已复制 ${text.length} 字符到剪贴板")
}

/**
 * 长按气泡后弹出的毛玻璃菜单。
 *
 * 只放「复制」这一类动作：复制不改变任何状态，做错了也没有代价，
 * 所以不需要二次确认，弹出来点了就走。
 */
@Composable
private fun CopyMenu(
    fullText: String,
    codeBlocks: List<String>,
    onCopy: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = glassFill(),
        border = BorderStroke(1.dp, glassEdge()),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(6.dp)) {
            GlassMenuItem(stringResource(R.string.menu_copy_all)) { onCopy(fullText) }

            if (codeBlocks.isNotEmpty()) {
                val label = if (codeBlocks.size > 1) {
                    stringResource(R.string.menu_copy_code_multi, codeBlocks.size)
                } else {
                    stringResource(R.string.menu_copy_code)
                }
                GlassMenuItem(label) { onCopy(codeBlocks.joinToString("\n\n")) }
            }
        }
    }
}

/** 菜单里的一行 */
@Composable
private fun GlassMenuItem(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
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
    modifier: Modifier = Modifier,
    // 长按代码块时回调（气泡层用它弹菜单）
    onCodeLongPress: (() -> Unit)? = null
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

                is MdBlock.Code -> CodeBlock(block.code, onLongPress = onCodeLongPress)
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
@OptIn(ExperimentalFoundationApi::class)
private fun CodeBlock(code: String, onLongPress: (() -> Unit)? = null) {
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
                // ★ combinedClickable 必须放在 horizontalScroll **之后**（也就是内层）。
                //   放外层的话手势会先被滚动容器吃掉 —— 这就是「长按代码块没反应」的原因：
                //   气泡自己那层 combinedClickable 根本收不到事件。
                .combinedClickable(
                    onClick = { /* 单击不做事 */ },
                    onLongClick = { onLongPress?.invoke() }
                )
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

/** 卡片要显示的三样东西（外加一个可选的 diff） */
private data class ToolCallView(
    val name: String,
    val argsText: String,
    val ok: Boolean?,   // null = 还在执行
    val output: String,
    val diff: List<DiffRow> = emptyList()
)

/** diff 里一行属于哪一类 */
private enum class DiffType { CONTEXT, DELETE, ADD }

/** diff 视图的一行 */
private data class DiffRow(val type: DiffType, val text: String)

/**
 * 行级 diff（LCS，最长公共子序列）。
 *
 * ★ 为什么在 UI 层算，而不是让工具把 diff 存下来：
 *   工具卡片的 content 里已经存了 arguments，而 replace_in_file 的 arguments 就是
 *   search（改之前）和 replace（改之后）这两段原文 —— diff 需要的数据本来就在手边。
 *   存一份 diff 等于把同一份信息写两遍，还要改持久化格式（多一次迁移风险）。
 *   所以这里纯计算，不碰任何存储结构。
 *
 * ★ 限制：
 *   LCS 是 O(n×m)，两段都是 60 行时约 3600 次比较，可以忽略；
 *   但模型真塞进来两段几百行的文本时会吃掉几 MB 内存，所以超过阈值就退化成
 *   「整段红 + 整段绿」，视觉效果差一点但不至于卡死。
 */
private fun diffLines(
    before: String,
    after: String,
    maxLinesEach: Int = 200,
    maxRows: Int = 120
): List<DiffRow> {
    val a = before.split('\n')
    val b = after.split('\n')

    // 退化路径：太长就不做 LCS
    if (a.size > maxLinesEach || b.size > maxLinesEach) {
        val fallback = ArrayList<DiffRow>(a.size + b.size)
        a.forEach { fallback.add(DiffRow(DiffType.DELETE, it)) }
        b.forEach { fallback.add(DiffRow(DiffType.ADD, it)) }
        return fallback.take(maxRows)
    }

    val n = a.size
    val m = b.size
    // dp[i][j] = a[i..] 与 b[j..] 的 LCS 长度
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) {
                dp[i + 1][j + 1] + 1
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }

    val rows = ArrayList<DiffRow>(n + m)
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> {
                rows.add(DiffRow(DiffType.CONTEXT, a[i]))
                i++
                j++
            }
            // 相等时优先走「删除」，让红色排在绿色前面，读起来更自然
            dp[i + 1][j] >= dp[i][j + 1] -> {
                rows.add(DiffRow(DiffType.DELETE, a[i]))
                i++
            }
            else -> {
                rows.add(DiffRow(DiffType.ADD, b[j]))
                j++
            }
        }
    }
    while (i < n) {
        rows.add(DiffRow(DiffType.DELETE, a[i]))
        i++
    }
    while (j < m) {
        rows.add(DiffRow(DiffType.ADD, b[j]))
        j++
    }

    // 模型常把整个函数塞进 search，首尾会带一长串根本没变的行。
    // 全留着卡片会变得又长又没重点，所以每侧只保留紧挨改动的那 1 行做上下文。
    var from = 0
    while (from < rows.size && rows[from].type == DiffType.CONTEXT) from++
    from = maxOf(0, from - 1)

    var to = rows.size
    while (to > 0 && rows[to - 1].type == DiffType.CONTEXT) to--
    to = minOf(rows.size, to + 1)

    if (from >= to) return rows.take(maxRows)   // 理论上不会走到（search != replace）
    return rows.subList(from, to).take(maxRows)
}

/** 从 content 里解析出「工具名 + 美化后的参数 + 执行结果」 */
private fun parseToolCall(content: String): ToolCallView {
    return try {
        val root = JSONObject(content)
        val name = (root.opt("tool") as? String) ?: "unknown"

        val argsNode = root.opt("arguments")
        val argsText = when {
            // ★ run_code 特殊处理：把代码原文取出来直接显示。
            //   照 JSON 渲染的话会变成一堆 "code": "...\n...\n..." 的转义字符串，
            //   一个换行都看不见，代码完全没法读。
            name == "run_code" && argsNode is JSONObject ->
                (argsNode.opt("code") as? String)?.takeIf { it.isNotBlank() }
                    ?: argsNode.toString(2)

            argsNode is JSONObject -> argsNode.toString(2)     // 缩进 2 空格，可读性最好
            argsNode is String -> argsNode
            argsNode == null || argsNode == JSONObject.NULL -> "{}"
            else -> argsNode.toString()
        }

        val okNode = root.opt("ok")
        // JSON null / 缺字段 = 还在执行；非布尔值一律当「不知道」
        val ok = okNode as? Boolean

        // ★ 只在「改成功了」的时候算 diff：
        //   失败时文件一个字都没变，画个红红绿绿的差异图只会误导。
        val diff = if (ok == true && name == "replace_in_file" && argsNode is JSONObject) {
            val search = (argsNode.opt("search") as? String).orEmpty()
            val replace = (argsNode.opt("replace") as? String).orEmpty()
            if (search.isNotEmpty() && search != replace) diffLines(search, replace) else emptyList()
        } else {
            emptyList()
        }

        ToolCallView(name, argsText, ok, (root.opt("output") as? String) ?: "", diff)
    } catch (_: Exception) {
        // 不是合法 JSON 就原样显示，绝不让界面崩
        ToolCallView("unknown", content, null, "")
    }
}

/** 展开时最多显示多少字符的结果（完整内容已经发给模型了，这里只是别再压垮界面） */
private const val MAX_TOOL_OUTPUT_PREVIEW = 4000

/**
 * 每种工具配一个图标 + 主色。
 *
 * 目的很实在：读和写是两种性质完全不同的操作 ——
 *   read_file  = 灰色扳手，只看不动
 *   write_file = 绿色钢笔，会改磁盘
 * 一眼能分清，就不会把"我让它写个文件"和"它读了个文件"看混。
 *
 * ⚠️ 只用了 material-icons-core 自带的图标（Build / Edit / List / Search / PlayArrow）。
 *    想换成 Folder / Terminal / Save 那些更贴切的，得先加 material-icons-extended 依赖
 *    （几千个图标、好几 MB），为五个图标不值得 —— R8 虽然能裁，但没必要开这个口子。
 */
private data class ToolVisual(val icon: ImageVector, val tint: Color)

@Composable
private fun toolVisual(name: String): ToolVisual {
    val cs = MaterialTheme.colorScheme
    return when (name) {
        "read_file" -> ToolVisual(Icons.Default.Build, cs.onSurfaceVariant)      // 扳手
        "write_file" -> ToolVisual(Icons.Default.Edit, Color(0xFF3FB950))        // 绿色钢笔
        "list_dir" -> ToolVisual(Icons.AutoMirrored.Filled.List, cs.onSurfaceVariant)  // 列表
        "search_files" -> ToolVisual(Icons.Default.Search, cs.onSurfaceVariant)  // 放大镜
        "run_shell" -> ToolVisual(Icons.Default.PlayArrow, Color(0xFFD29922))    // 琥珀色，提示高权限
        // PTC 的 run_code：用 Build 图标但染成紫色，和六个原子工具区分开 ——
        // 它本身不碰磁盘，是「一段代码指挥了很多次调用」
        "run_code" -> ToolVisual(Icons.Default.Build, Color(0xFFA371F7))
        // ask_user：问号语义用 Info 表达（core 图标库没有 Help），青色 ——
        // 它是唯一「要等用户」的工具
        "ask_user" -> ToolVisual(Icons.Default.Info, Color(0xFF39C5CF))
        else -> ToolVisual(Icons.Default.Build, cs.onSurfaceVariant)
    }
}

@Composable
private fun ToolCallCard(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme

    // 折叠 / 展开。默认值来自设置页的「工具卡片默认展开」
    var expanded by remember { mutableStateOf(AppSettings.cardExpanded) }

    // 解析结果按 content 缓存，重组时不重复解析
    val view = remember(message.content) { parseToolCall(message.content) }

    val title = when (view.ok) {
        null -> stringResource(R.string.tool_running, view.name)
        true -> stringResource(R.string.tool_done, view.name)
        false -> stringResource(R.string.tool_failed, view.name)
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
                val visual = toolVisual(view.name)
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,      // 工具名就在右边文字里，重复朗读没意义
                    tint = visual.tint,
                    modifier = Modifier.size(16.dp)
                )
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
                    contentDescription = if (expanded) {
                        stringResource(R.string.tool_card_collapse)
                    } else {
                        stringResource(R.string.tool_card_expand)
                    },
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // ---------- 展开：参数 + 结果 ----------
            if (expanded) {
                Spacer(Modifier.height(10.dp))

                Text(
                    text = stringResource(R.string.tool_card_args),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))

                CodeSurface(text = view.argsText)

                // ---------- 差异（只有 replace_in_file 成功时才有） ----------
                if (view.diff.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.tool_card_diff),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        val removed = view.diff.count { it.type == DiffType.DELETE }
                        val added = view.diff.count { it.type == DiffType.ADD }
                        Text(
                            text = "−$removed",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = DiffDelColor
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "+$added",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = DiffAddColor
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    DiffSurface(rows = view.diff)
                }

                if (view.output.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (view.ok == true) {
                            stringResource(R.string.tool_card_result)
                        } else {
                            stringResource(R.string.tool_card_error)
                        },
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


/** diff 视图的三种配色。红绿取的是 GitHub 深色主题的调子，和代码块的底色是一套 */
private val DiffDelColor = Color(0xFFF85149)
private val DiffAddColor = Color(0xFF3FB950)
private val DiffDelBackground = DiffDelColor.copy(alpha = 0.15f)
private val DiffAddBackground = DiffAddColor.copy(alpha = 0.15f)

/**
 * 极简 diff 视图：一行一条，前面带 - / + / 空格 的前缀。
 *
 * 刻意保持和 CodeSurface 一样的深色底 + 等宽字体，让它在毛玻璃卡片里
 * 看起来像同一套东西，而不是突然插进来的一个控件。
 *
 * 没有用横向滚动：手机屏幕窄，代码行一横滚就看不到行首缩进，
 * diff 反而更难读。这里让长行自己折行，行号和背景跟着一起折。
 */
@Composable
private fun DiffSurface(rows: List<DiffRow>) {
    Surface(
        color = CodeBlockBackground,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, CodeBlockBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            rows.forEach { row ->
                val bg = when (row.type) {
                    DiffType.DELETE -> DiffDelBackground
                    DiffType.ADD -> DiffAddBackground
                    DiffType.CONTEXT -> Color.Transparent
                }
                val sign = when (row.type) {
                    DiffType.DELETE -> "-"
                    DiffType.ADD -> "+"
                    DiffType.CONTEXT -> " "
                }
                val signColor = when (row.type) {
                    DiffType.DELETE -> DiffDelColor
                    DiffType.ADD -> DiffAddColor
                    DiffType.CONTEXT -> CodeBlockText.copy(alpha = 0.35f)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(start = 8.dp, end = 8.dp, top = 1.dp, bottom = 1.dp)
                ) {
                    Text(
                        text = sign,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = signColor,
                        modifier = Modifier.width(12.dp)
                    )
                    Text(
                        // 空行也要占一行高度，不然 diff 的行会挤在一起
                        text = row.text.ifEmpty { " " },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = CodeBlockText,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}


// ============================================================
// 6.5 InfoBar —— 本轮费用 + 上下文用量
//
// 数据全部来自 API 返回的 usage 字段（需要请求里带 stream_options.include_usage）。
// 费用按 DeepSeek 官方峰谷单价估算，见前面 3.25 节。
// ============================================================
/**
 * 联网搜索状态条：`🔍 正在联网搜索: 关键词`
 *
 * ★ 它解决的是「这 2~3 秒 App 到底在干嘛」的问题。
 *   搜索要等网络，这段时间屏幕上一点变化都没有 —— 没有它，用户会以为卡死了，
 *   或者以为模型直接跳过不搜了。
 *
 * ★ 为什么用 Surface 包一层小胶囊，而不是像 InfoBar 那样直接一行文字：
 *   搜索是一个「正在发生的动作」，不是常驻数据。用主题色胶囊能一眼和
 *   InfoBar 那行灰字区分开，而且做完就消失，不会长期占位置。
 *
 * ★ 为什么 maxLines = 1 + Ellipsis：
 *   模型可能把一整句话塞进 query（虽然提示词里要求写关键词）。
 *   不限行数的话，一条超长 query 会把输入框顶到屏幕外面去。
 */
/**
 * ask_user 工具的毛玻璃输入对话框（v1.1 阶段三）。
 *
 * ★ 极客风三件套，全部沿用项目已有手法（§6.4 ⑲）：
 *   1. 半透明填充 + 1dp 半透明描边模拟毛玻璃 —— 不用 Modifier.blur()，
 *      那是每帧高斯模糊，对「50ms 防发热」是灾难；
 *   2. 等宽字体点题「这是 Agent 的终端式提问」；
 *   3. 深浅两套 glassEdge 高光描边，和 Drawer / 弹层保持一致。
 *
 * ★ 不可点外部关闭：这个对话框就是「Loop 正在等你」的化身，
 *   误触返回键把弹窗关了、Loop 却永远挂着，是最恶性的死锁。
 *   要退出只有两个按钮：发送 或 取消。
 */
@Composable
private fun AskUserDialog(
    question: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var answer by remember(question) { mutableStateOf("") }
    // ★ remember(question)：换了问题就清空输入框（同一轮 Loop 可能连问两次）

    Dialog(
        onDismissRequest = { /* 刻意空实现：见 KDoc,不允许点外部关掉 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            // 半透明底：透出下面的聊天列表一点影子,毛玻璃感
            color = if (LocalIsDark.current) Color(0xCC14181F) else Color(0xCCF5F7FA),
            border = BorderStroke(1.dp, glassEdge())
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFF39C5CF),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ask_user_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.primary
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = answer,
                    onValueChange = { if (it.length <= 2000) answer = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.ask_user_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = cs.primary.copy(alpha = 0.6f),
                        unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { if (answer.isNotBlank()) onSubmit(answer.trim()) },
                        enabled = answer.isNotBlank()
                    ) {
                        Text(stringResource(R.string.action_send))
                    }
                }
            }
        }
    }
}

@Composable
fun SearchStatusBar(query: String) {
    val cs = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            // 半透明填充 + 1dp 半透明描边，和项目里毛玻璃那套手法保持一致
            // （不用 Modifier.blur()，见 §6.4 ⑲）
            color = cs.primary.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.25f))
        ) {
            Text(
                text = stringResource(R.string.searching_web, query),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = cs.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

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
            text = stringResource(R.string.info_cost, formatCny(cost)),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(
                R.string.info_context,
                formatTokens(usage.promptTokens),
                formatTokens(CONTEXT_WINDOW_TOKENS)
            ) + "  " + String.format(Locale.US, "%.1f%%", ctxPercent),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (isStreaming) {
            Text(
                text = stringResource(R.string.info_generating),
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
/** 附件的落盘状态 */
enum class AttachState { COPYING, READY, FAILED }

/**
 * 输入区里挂着的附件。
 *
 * uri 是 content:// —— 这个 URI 只有 ContentResolver 能读，java.io.File 打不开，
 * 所以选中之后会立刻把流复制到工作区，拿一个真实的绝对路径（path）。
 * Agent 的 read_file 走的是 File，只有 path 才有用。
 */
data class Attachment(
    val uri: Uri,
    val name: String,
    val isImage: Boolean,
    val state: AttachState = AttachState.COPYING,
    val path: String? = null,
    val error: String? = null,
    /**
     * 「上传文档」入口解析出来的正文（null = 没解析过 / 不是文档）。
     *
     * ★ 为什么要把正文存进来：用户从文档入口选了 .docx，解析在落盘时已经做过一次，
     *   这时候把正文直接带给模型，比让模型再调一次 read_file 省一整轮 token，
     *   也少一次「模型说自己读不了」的误会。
     * ★ 普通文件入口（filePicker）不填这个字段 —— 那些文件仍然交给 read_file，
     *   这里不预解析，免得给用户传个大压缩包也白解一遍。
     */
    val parsedText: String? = null,
    /** parsedText 的字符数；没解析就是 0。气泡和胶囊都靠它显示「已读取 N 字」。 */
    val charCount: Int = 0
)

/** 附件在消息正文里的标记格式：<<ATTACH:image|/abs/path>> */
private val ATTACH_MARK_RE = Regex("""<<ATTACH:(image|file|doc)\|([^>]+)>>""")

/**
 * 文档上下文哨兵的起始标记：<<DOC:begin|文件名|字数>>
 *
 * ★ 为什么单独一套标记而不是复用 ATTACH：它俩的寿命不一样。
 *   ATTACH 标记在「发给模型」和「界面显示」两条路上都要被剥掉，
 *   而 DOC 哨兵只有显示时整段剥掉、发给模型时留着正文。
 *   混用一套正则就分不出该剥到哪儿。
 */
private val DOC_BEGIN_RE = Regex("""<<DOC:begin\|([^|>]*)\|(\d+)>>""")

/** 文档上下文哨兵的结束标记：<<DOC:end>> */
internal const val DOC_END = "<<DOC:end>>"

/** 落盘目录名 */
private const val ATTACH_DIR = "attachments"

/**
 * 「上传文档」入口限定的 MIME 类型（Office + PDF）。
 *
 * ★ 为什么不放开所有类型：全放开的话文件管理器会把相册视频、安装包一起列出来，
 *   用户得在几百个文件里翻自己要的那个。这份名单让系统做初步过滤，
 *   所以它只是"减噪"不是"硬过滤"。
 * ★ 为什么最后还要一个「任意类型」兜底（写法是引号里星号斜杠星号）：各厂商文件管理器对 MIME 的上报很不全，
 *   WPS 存的 .doc 有的只报 application/octet-stream，小米/华为自家文档目录
 *   甚至会报 application/vnd.android.document。少了这一条，
 *   用户在自己手机的文件管理器里明明看着这个文件却选不到 —— 所以宁可多列几个，
 *   真正判"能不能解析"由 OfficeParser.looksLikeOffice 做，那边才是唯一真相。
 * ★ 名单本身是注释级意图：PDF 和旧版二进制格式最终会在 addAttachment 里被
 *   解析器拒掉并弹对话框，列进来只是为了让用户"能选到"，然后得到一句明确的拒绝。
 */
private val DOC_PICKER_MIME = arrayOf(
    // OOXML（现代 Office 真正的 MIME）
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    // PDF：解析器不支持，但必须能选到，才好给出明确拒绝理由
    "application/pdf",
    // 旧版二进制 Office（.doc / .xls / .et）
    "application/msword",
    "application/vnd.ms-excel",
    // WPS 私有格式 / 上报
    "application/vnd.ms-wps",
    // 兜底：各厂商文件管理器 MIME 上报不全，见上面注释
    "*/*"
)

/**
 * 把 content:// 的内容复制成一个真实文件，返回绝对路径。
 *
 * 优先落到「工作区/attachments」，这样 Agent 用 read_file 就能读；
 * 没设工作区又没存储权限时退回应用私有 cacheDir —— 模型照样能读，
 * 只是用户自己在文件管理器里看不到。
 */
private fun persistAttachment(context: Context, uri: Uri, name: String): Result<File> {
    return try {
        val workspaceRoot = currentWorkspace.ifBlank {
            // ★ 走统一入口，不要直接调 Environment.isExternalStorageManager()
            if (hasAllFilesAccess(context)) DEFAULT_WORKSPACE else ""
        }
        val dir = if (workspaceRoot.isNotBlank()) {
            File(workspaceRoot, ATTACH_DIR)
        } else {
            File(context.cacheDir, ATTACH_DIR)
        }
        if (!dir.exists() && !dir.mkdirs()) {
            error("创建目录失败：${dir.absolutePath}")
        }

        // 重名不覆盖，加时间戳后缀
        var target = File(dir, name)
        if (target.exists()) {
            val stem = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            val suffix = if (ext.isEmpty() || ext == name) "" else ".$ext"
            target = File(dir, "$stem-${System.currentTimeMillis()}$suffix")
        }

        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("打不开输入流（URI 可能已失效）")

        Log.d(TAG, "附件已落盘: ${target.absolutePath} (${target.length()} 字节)")
        Result.success(target)
    } catch (e: Exception) {
        Log.e(TAG, "附件落盘失败: $name", e)
        Result.failure(e)
    }
}

/**
 * 图片 → data URL（base64）。
 *
 * 先按 inSampleSize 降采样到最长边 ≤1280，再 JPEG 80 压一遍。
 * 手机拍的原图动辄 4000×3000、几 MB，直接 base64 会让请求体爆炸，
 * 而且模型那边的图片分辨率也不需要这么高。
 */
private fun imageToDataUrl(path: String): String? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.e(TAG, "图片解码失败（不是有效图片？）: $path")
            return null
        }

        var sample = 1
        while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()

        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        Log.d(
            TAG,
            "图片编码完成: ${File(path).name}, 原 ${bounds.outWidth}x${bounds.outHeight} " +
                "→ sample=$sample, ${out.size()} 字节, base64 ${b64.length} 字符"
        )
        "data:image/jpeg;base64,$b64"
    } catch (e: Exception) {
        Log.e(TAG, "图片编码失败: $path", e)
        null
    }
}

/**
 * 把一条历史消息转成 API 要的 content。
 *
 * 普通消息：原样返回字符串。
 * 带附件：解析标记，图片转 image_url 数组，文件则补一句「请调用 read_file」。
 *
 * ★ 为什么在发请求时现算，而不是落库时就把 base64 存下来：
 *   一张图 base64 后是几百 KB 到几 MB，存进 Room 会让数据库和每次读取都变慢，
 *   而且重启后还要重新拼。只存路径，用的时候现编码，是最省的。
 */
private fun contentForApi(msg: ChatMessage): Any {
    if (!msg.isUser) return msg.content
    if (!msg.content.contains("<<ATTACH:") && !msg.content.contains("<<DOC:")) return msg.content

    val matches = ATTACH_MARK_RE.findAll(msg.content).toList()

    // ★ 文档上下文：只剥两行哨兵，正文原样发给模型。
    //   历史消息重发时靠这条把文档内容带回去，不会出现「翻回去问，模型说不认识那份文档」。
    val textOnly = ATTACH_MARK_RE.replace(stripDocBlocks(msg.content), "").trim()
    val images = matches.filter { it.groupValues[1] == "image" }.map { it.groupValues[2] }
    // doc 的正文已经在 textOnly 里了，这里不能再让它走 read_file 那一套，
    // 否则模型会为了「读一个已经读过的文件」白跑一轮工具。
    val files = matches.filter { it.groupValues[1] == "file" }.map { it.groupValues[2] }

    val textPart = buildString {
        if (textOnly.isNotEmpty()) append(textOnly)
        files.forEach { path ->
            if (isNotEmpty()) append("\n\n")
            append("用户上传了一个文件，路径是：").append(path)
            append("\n请调用你的 read_file 工具读取并分析它。")
            // ★ v1.1：docx / xlsx 的 read_file 会直接返回解析出的正文，
            //   不再返回"这是二进制文件"。补一句是怕模型还按老经验先入为主地拒绝，
            //   历史消息里这条提示会和当前版本的系统提示词一起生效。
            append("\n（.docx / .xlsx 会由系统解析成正文后交给你，可以直接分析。）")
        }
    }

    if (images.isEmpty()) {
        return textPart.ifBlank { "（用户上传了附件）" }
    }

    val parts = JSONArray()
    parts.put(JSONObject().put("type", "text").put("text", textPart.ifBlank { "请看这张图片。" }))
    var encoded = 0
    images.forEach { path ->
        imageToDataUrl(path)?.let { url ->
            parts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", url))
            )
            encoded++
        }
    }
    if (encoded == 0) {
        // 一张都没编码成功，退化成纯文本，别让整个请求挂掉
        return textPart.ifBlank { "（用户上传了图片，但本地解码失败）" }
    }
    return parts
}

/**
 * 界面上展示用的正文：去掉附件标记，并且把整段文档上下文剥掉。
 *
 * ★ 文档上下文必须整段剥：正文最多一万字，全塞进聊天气泡会把气泡撑到
 *   屏幕外，用户想看看自己问了什么得划半天。剥掉之后气泡里只剩问题本身，
 *   文档信息由 docCardsOf 单独渲染成一张小卡片。
 */
private fun displayContent(raw: String): String {
    if (!raw.contains("<<ATTACH:") && !raw.contains("<<DOC:")) return raw
    var s = stripDocBlocks(raw)
    s = ATTACH_MARK_RE.replace(s, "")
    return s.trim()
}

/**
 * 剥掉「<<DOC:begin …>> 到 <<DOC:end>>」整段，只留两头的用户话。
 *
 * 用 indexOf 手写循环而不是一条正则：
 *   · 文档正文里完全可能出现 >> 或 <<，正则的 .*? 遇到嵌套/半个标记会配错；
 *   · 正文里出现「<<DOC:begin|」这种字面内容的概率约等于零，但即便出现了，
 *     indexOf 从头往后找也是「找最近的一个 end」，行为可预测。
 */
internal fun stripDocBlocks(raw: String): String {
    if (!raw.contains("<<DOC:")) return raw
    val sb = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val b = raw.indexOf("<<DOC:begin|", i)
        if (b < 0) {
            sb.append(raw, i, raw.length)
            break
        }
        sb.append(raw, i, b)
        val e = raw.indexOf(DOC_END, b)
        if (e < 0) {
            // 只有开头没有结尾（老数据 / 写入被打断）：把剩下的全丢，
            // 否则后面一万字原文会漏到界面上。
            break
        }
        i = e + DOC_END.length
    }
    return sb.toString()
}

/** 气泡里那张「📄 已读取文档」卡片需要的信息 */
internal data class DocCard(val name: String, val charCount: Int)

/**
 * 从消息正文里抽出文档卡片列表（按出现顺序）。
 *
 * ★ 为什么从正文里反解而不是另存一张表：哨兵里已经带了文件名和字数，
 *   反解一次就够，不值得为一个展示字段加一张表 + 一次查询。
 */
internal fun docCardsOf(raw: String): List<DocCard> {
    if (!raw.contains("<<DOC:")) return emptyList()
    return DOC_BEGIN_RE.findAll(raw).map { m ->
        DocCard(name = m.groupValues[1], charCount = m.groupValues[2].toIntOrNull() ?: 0)
    }.toList()
}

/**
 * 气泡里的「📄 已读取文档」小卡片。
 *
 * ★ 为什么用普通 Text 而不是 Card/Surface：卡片本身已经在一张大气泡里，
 *   再套一层容器会画出两层边框、两层圆角，视觉上很脏。
 *   一行字 + 一个 📄 就够说明「这份文档进去了」。
 * ★ 文件名可能很长：maxLines = 1 + ellipsis，否则窄气泡会被撑高。
 */
@Composable
private fun DocCardRow(card: DocCard, textColor: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "📄",
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "已读取文档：" + card.name + " (字数: " + card.charCount + ")",
            color = textColor.copy(alpha = 0.75f),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

/** 从 content:// 取出文件名；取不到就退回路径末段 */
private fun displayNameOf(context: Context, uri: Uri): String {
    val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
    return try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() } ?: fallback else fallback
                } else fallback
            } ?: fallback
    } catch (e: Exception) {
        Log.e(TAG, "读取文件名失败: $uri", e)
        fallback
    }
}

/**
 * 底部输入区。
 *
 * 结构（严格按这个嵌套来）：
 *   Column
 *     ├─ 附件 Chip 行（有附件时才出现，贴在胶囊正上方）
 *     └─ Surface（毛玻璃胶囊）
 *          └─ Row
 *               ├─ IconButton(+)   ← 最左，独立按钮，40dp 全是热区
 *               ├─ BasicTextField  ← weight(1f)，占满中间
 *               └─ 发送/停止按钮   ← 最右
 *
 * ★ + 号刻意不放进 BasicTextField 的 leadingIcon：
 *   leadingIcon 会被算进输入框内部，点击热区只有图标本身那么大，
 *   而且文字起点会被它顶开、光标位置也跟着变。独立成一个 IconButton 更宽松。
 */
@Composable
fun InputBar(
    text: String,
    enabled: Boolean,
    isStreaming: Boolean,
    attachments: List<Attachment>,
    onTextChange: (String) -> Unit,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val canSend = (text.isNotBlank() || attachments.any { it.state == AttachState.READY }) && enabled

    Column(modifier = modifier.fillMaxWidth()) {
        // ---------- 附件：胶囊正上方 ----------
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 14.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEachIndexed { index, att ->
                    AttachmentChip(att = att, onRemove = { onRemoveAttachment(index) })
                }
            }
        }

        // ---------- 毛玻璃胶囊 ----------
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 10.dp),
            shape = RoundedCornerShape(30.dp),
            color = glassFill(),
            border = BorderStroke(1.dp, glassEdge()),
            shadowElevation = 8.dp,
            tonalElevation = 0.dp
        ) {
            Row(
                // 左边距从 18dp 收到 6dp：+ 号按钮自己占 40dp，
                // 视觉上文字起点和以前基本一致。
                modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = onAttachClick,
                    enabled = enabled,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.input_attach),
                        tint = if (enabled) cs.onSurfaceVariant else cs.outline,
                        modifier = Modifier.size(22.dp)
                    )
                }

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
                                    text = stringResource(R.string.input_hint),
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
                            contentDescription = stringResource(R.string.action_send),
                            tint = if (canSend) cs.onPrimary else cs.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 附件小卡片：类型标记 + 文件名 + × 移除 */
@Composable
private fun AttachmentChip(att: Attachment, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (att.state == AttachState.FAILED) {
            Color(0xFFF85149).copy(alpha = 0.14f)
        } else {
            glassFill()
        },
        border = BorderStroke(
            1.dp,
            if (att.state == AttachState.FAILED) Color(0xFFF85149).copy(alpha = 0.5f) else glassEdge()
        ),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 落盘状态：复制中 / 已就绪 / 失败
            Text(
                text = when (att.state) {
                    AttachState.COPYING -> "⋯"
                    AttachState.READY -> "✅"
                    AttachState.FAILED -> "⚠️"
                },
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (att.isImage) "IMG" else "FILE",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = if (att.state == AttachState.FAILED) Color(0xFFF85149) else cs.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = att.name,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp)
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.attach_remove),
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}


/**
 * 点击 + 号后从底部升起的选择器。
 *
 * 只放「从哪拿」这两项，不做权限申请 —— 照片选择器（PickVisualMedia）
 * 和系统文件管理器（OpenDocument）都是授权动作本身，不需要额外申请读存储权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet(
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onPickDoc: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = glassFill(),
        contentColor = cs.onSurface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp)
        ) {
            Text(
                text = stringResource(R.string.sheet_title),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 6.dp)
            )
            SheetAction(emoji = "📷", label = stringResource(R.string.sheet_image), onClick = onPickImage)
            SheetAction(emoji = "📁", label = stringResource(R.string.sheet_file), onClick = onPickFile)
            SheetAction(emoji = "📄", label = stringResource(R.string.sheet_doc), onClick = onPickDoc)
        }
    }
}

/** 弹层里的一行 */
@Composable
private fun SheetAction(emoji: String, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface
        )
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

