# HANDOVER.md — CoolStream 交接文档

> 面向：接手继续开发的 AI（DeepSeek Harness / Codex 模式）与项目作者本人
> 基线提交：`14619f0`（2026-10-22）
> 代码总量：Kotlin 7417 行（单文件 1 个，加 3 个工具文件与测试），中英文案各 106 条
>
> **EN TL;DR** — Android native DeepSeek client in **one** Kotlin file (`MainActivity.kt`, 7417 lines).
> The whole point of the project is **not heating up the phone**: streaming text is batched every
> **50 ms** before touching Compose state. Never move `mutableStateOf` onto the per-token path.
> Never add a Node.js runtime, never replace the Shizuku AIDL bridge, never "clean up" the single-file
> layout. Room is at schema **v3** with WAL — pull `chat.db` **plus** `-wal`/`-shm` or you get an empty DB.
> `github.com:443` is unreachable on this machine; `api.github.com` works — see §9 for the API-based push.
> Read §5 (铁律) and §6 (踩坑) before writing any code.

---

## 目录

- [0. 三十秒速览](#0-三十秒速览)
- [1. 这个项目是什么 / 不是什么](#1-这个项目是什么--不是什么)
- [2. 构建与运行](#2-构建与运行)
- [3. 架构](#3-架构)
- [4. 一次对话的完整数据流](#4-一次对话的完整数据流)
- [5. 绝对不许破坏的铁律](#5-绝对不许破坏的铁律)
- [6. 历史踩坑记录](#6-历史踩坑记录)
- [7. 已完成 / 未完成](#7-已完成--未完成)
- [8. 测试与验证方法](#8-测试与验证方法)
- [9. 提交、推送与发布](#9-提交推送与发布)
- [10. 下一步开发建议](#10-下一步开发建议)
- [11. 交接检查清单](#11-交接检查清单)

---

## 0. 三十秒速览

| 项目 | 值 |
|---|---|
| 应用名 | CoolStream（内部代号 MyChat） |
| 包名 / applicationId | `com.example.mychat` |
| 技术栈 | Kotlin 2.0.21 + Jetpack Compose（BOM 2024.09.00），AGP 8.12.1，Gradle 8.13 |
| SDK | compileSdk 36 / minSdk 24 / targetSdk 36 |
| 网络 | OkHttp 4.12.0，手写 SSE 解析，**不用 okhttp-sse** |
| JSON | `org.json`（Android 内置，零依赖） |
| 数据库 | Room 2.6.1 + KSP，schema **v3** |
| JS 引擎 | Rhino（`com.faendir.rhino:rhino-android:1.6.0`，仅 PTC 模式用） |
| 提权 | Shizuku 13.1.5（AIDL + bindUserService） |
| 模型 | 默认 `deepseek-flash`，接口 `https://api.deepseek.com/chat/completions` |
| APK 体积 | 11.9 MB（12492445 字节，debug 无混淆） |
| 单元测试 | 37 条，全绿，纯 JVM，0.8 秒 |
| 版本号 | `主版本+build.yyMMddHHmm`（如 `1.0.2+build.2610222321`），versionCode = `yyMMddHH` |

---

## 1. 这个项目是什么 / 不是什么

**是什么**：一个跑在安卓手机上的**本地 Agent 客户端**。它把手机的 shell（经 Shizuku）、文件系统
（经 `MANAGE_EXTERNAL_STORAGE`）和摄像头相册（经系统选择器）都变成模型的工具，让模型能真的
读写手机上的文件，而不是只会聊天。

**不是什么**：

- ❌ 不是 WebView 套壳。界面 100% 原生 Compose。**这是立项的根本原因**（见下）。
- ❌ 不是通用聊天软件。它只对接 DeepSeek 系接口，没有多租户、没有账号体系。
- ❌ 不是产品。作者是护理学专业大二学生，零编程基础，用 AI 辅助开发。代码里大量注释是
  "写给未来的自己看的人话翻译"，**不要因为注释啰嗦就删掉它们**。

### 立项动机（决定了所有技术选型的优先级）

作者原先用的是第三方客户端 `woaiys3/deepseek-harness-android-app`。用 PerfDog 实测：
**后台单核 108% CPU、CPU 结温 94.5 ℃**，手机烫手。根因是那套架构在「原生 Java 壳」里塞了
一个 **Node.js 运行时**，界面层又是 **Web 前端跑在系统 WebView 里** —— 两层都在抢 CPU。

所以本项目的**第一优先级永远是「不发热」**，不是功能多、不是代码漂亮。任何改动如果让
每帧的 JS/重组工作量上升，就是在拆项目的台。

---

## 2. 构建与运行

### 2.1 命令行构建（PATH 里没有 java，必须显式给 JAVA_HOME）

```bash
cd "C:/Users/20732/AndroidStudioProjects/MyChat"
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # 只是启动器，哪个 JDK 都行
./gradlew assembleDebug --console=plain
./gradlew testDebugUnitTest --console=plain                      # 37 条单测，不需要真机
```

### 2.2 真机 / 模拟器

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" logcat -c && "$ADB" shell am start -n com.example.mychat/.MainActivity
"$ADB" logcat -d -s MyChatPerf:D ToolExec:D     # 只看这两个 tag
```

⚠️ **Git Bash 会把 `/sdcard/...` 改写成 Windows 路径**。所有 adb 的远端路径参数前先
`export MSYS_NO_PATHCONV=1`，否则 `uiautomator dump` 会写到本机的 `C:/.../sdcard/` 下。

⚠️ **`adb input tap` 的坐标必须每次重新 `uiautomator dump` 读**，不能凭上一次的截图猜。
键盘弹出会把整个输入区上移（实测差过 130px，连点三次全点空）。

### 2.3 构建环境的历史坑（已经踩平，别重走）

| 症状 | 根因 | 正解 |
|---|---|---|
| `Unsupported class file major version 69` | Android Studio 自带 JBR 升到 **JDK 25**，而 Gradle 8.13 只支持 8~23 | Android Studio → Settings → Build Tools → Gradle → **Gradle JDK 选 21**。改 `gradle.properties` / `.idea/gradle.xml` / `jdk.table.xml` **全部无效**（AS 通过 Tooling API 覆盖） |
| AS 里下载 JDK 失败 | 下载源是 GitHub Releases | 在 AS 的 Download JDK 里选 **Azul Zulu / Alibaba Dragonwell / Amazon Corretto**，别选 Temurin / GraalVM / SapMachine |
| 依赖下载慢 | 默认源在国内不稳 | `settings.gradle.kts` 已配阿里云镜像（`[cn-mirrors]`），Gradle 发行版走腾讯镜像 |

### 2.4 配置文件

`local.properties`（**不进 Git**，见 `.gitignore`）需要：

```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
DEEPSEEK_API_KEY=sk-xxxx          # 可选，仅作为本机开发时的兜底默认值
```

**运行时的 Key 不在这里**。用户在「设置 → 模型与 API」里填的 Key 存在
`EncryptedSharedPreferences`（主密钥在 Android Keystore 里），优先级高于 BuildConfig。
参考 `local.properties.example`（无钥匙模板）。

---

## 3. 架构

### 3.1 为什么所有代码挤在一个文件里（这条是铁律，不是偷懒）

`app/src/main/java/com/example/mychat/MainActivity.kt` —— **7417 行，一个文件**。

原因非常务实：作者**零编程基础**，让他跨 6 个文件粘贴代码，他会直接粘贴失败/漏粘。
历史上试过一次拆文件重构，结果卡了两天。所以：

> **不要把 MainActivity.kt 拆成多个文件。**
> 如果确实需要拆分（比如为了测试），只能拆**纯函数**到文件末尾或单独文件，且必须保证
> 作者仍然只需要看这一个文件就能理解全部逻辑。

唯一被允许独立存在的 Kotlin 文件：
- `ui/theme/{Color,Theme,Type}.kt` —— Compose 主题三件套（模板生成的）
- `app/src/test/java/.../FileToolsTest.kt`、`AgentPresetTest.kt` —— 单元测试
- `app/src/main/aidl/.../IRemoteShell.aidl` —— Shizuku 跨进程接口（AIDL 只能是独立文件）

### 3.2 MainActivity.kt 分区地图（行号基于 `14619f0`，改动后会漂移，按小节名定位）

| 行 | 小节 | 内容 |
|---|---|---|
| 434 | `0.1 Shizuku` | 授权、绑定 UserService、`runShell`、**黑名单** |
| 490 | `1.` 数据模型 | `@Entity sessions` / `@Entity messages`、`MessageType` 枚举与 TypeConverter |
| 578 | `1.1 DAO` | `ChatDao`，全部 suspend |
| 638 | `1.2 数据库本体` | `@Database(version = 3)` 单例、迁移 |
| 719 | `sendToDeepSeek` | **无工具的退化路径**。当前**没有任何调用点**，原样保留（见铁律 3） |
| 954 | `3.1 解析` | SSE 单行 → `Delta{content, toolCalls}`，含 `parseUsage` |
| 1015 | `3.2 工具清单` | `TOOL_DEFINITIONS`（发给模型的 JSON Schema，6 个工具） |
| 1306 | `3.25 工作区` | `currentWorkspace`、`workspaceViolation`、`safeCanonicalPath` |
| 1337 | `3.24 用户偏好` | `AppSettings`（Compose 可观察的全局设置）、读写函数 |
| 1362 | `3.24a Agent 预设` | `AgentPreset` 枚举、`PTC_TOOL_DEFINITION`、两套提示词 |
| 1607 | — | `buildToolsArray()`：**预设优先级高于单个工具开关** |
| 1629 | `3.24b API 配置` | `EncryptedSharedPreferences`、`ApiConfig` |
| 1746 | `3.24c 引导` | 首次启动流程、默认工作区创建 |
| 1968 | — | `writeTextFile` |
| 2070 | `3.26 文件改写` | `replaceInFile`、备份、唯一性检查、`diffLines` |
| 2639 | `3.27 PTC 沙箱` | Rhino 运行时、`ptcPreflight`、`runCode` |
| 3028 | — | `executeTool(name, argsJson)`：**唯一的工具分发点** |
| 3148 | `3.25 Token 计费` | `UsageStats`、`costCny`、`isPeakHour` |
| 3239 | `3.3 Agent 闭环` | **`runAgentLoop`，真正在跑的请求路径** |
| 3525 | `4.` 入口 | `MainActivity`、edge-to-edge、主题注入、i18n Context 包装 |
| 3582 | `5.0 会话模型` | UI 侧的会话/消息状态 |
| 3592 | `5.00 毛玻璃取色` | `glassFill()` / `glassEdge()`，深浅两套 |
| 3612 | `5.01 瘦模型` | 侧边栏专用的 `@Immutable` 数据类（治卡顿的关键） |
| 3659 | `5.05 ViewModel` | `ChatViewModel`：旋转存活、`streamJob`、工具卡片持久化 |
| 4555 | `5.1 侧边栏` | 抽屉、长按菜单 |
| 4869 | `5.2 设置页` | 全屏设置，四个 Tab |
| 4875 | `5.5 引导页` | Onboarding |
| 6218 | `6.1 Markdown` | 零依赖的极简 Markdown 渲染 |
| 6456 | `6.2 工具卡片` | `ToolCallCard`、`parseToolCall`、Diff 视图 |
| 6892 | `6.5 InfoBar` | 本轮费用 + 上下文占比条 |

> ⚠️ 小节编号有历史遗留的重复（`3.25` 出现两次：工作区与 Token 计费；`3.26` 曾是文件改写与
> PTC 沙箱共用，PTC 已改为 `3.27`）。**定位时请用「小节名 + 函数名」，不要只依赖编号。**

### 3.3 50ms 批处理机制（整个项目的性能核心）

**问题**：模型每秒吐 30~60 个分片（token）。每个分片都 `mutableStateOf` 一次 → 每秒 60 次
重组 + 重新排版 + 重绘 → CPU 被吃满 → 手机发烫。

**做法**（`runAgentLoop` 内，约 3353 行起）：

```kotlin
val buffer = StringBuilder()
var lastFlushAt = 0L
// ... 每收到一个分片：
buffer.append(content)
val now = SystemClock.elapsedRealtime()
if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {        // FLUSH_INTERVAL_MS = 50L
    lastFlushAt = now
    withContext(Dispatchers.Main) { onFlush(buffer.toString()) }
}
// 流结束必须补刷一次，否则最后几个字永远不上屏
if (buffer.isNotEmpty()) withContext(Dispatchers.Main) { onFlush(buffer.toString()) }
```

关键点：
- `buffer` 是普通 `StringBuilder`，**不是** Compose state。热路径上不能有任何 `mutableStateOf`。
- 每 50ms 最多切 20 次主线程。人眼在 60~100ms 内感觉不出卡顿，这是「流畅 vs 省电」的甜点。
- 实测对比：改成 `0L` 再跑，日志里的 flush 次数会翻十几倍。
- **流结束的补刷不能删**。删了会出现「回答少最后一个字」的诡异现象。
- UI 侧 `onFlush` 是**原地替换最后一个气泡的正文**，不是 append 新气泡。靠
  `messages.last()` 定位占位气泡，所以占位气泡必须永远是列表最后一条（工具卡片插入时
  用了 +1 / +2 的时间戳分层保证这一点）。

### 3.4 Room 数据库（schema v3）

```kotlin
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"],
                              onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: String,
    val content: String,          // 普通消息=正文；TOOL_CALL=工具调用 JSON
    val isUser: Boolean,
    val isError: Boolean = false, // 错误提示气泡，下次请求要排除，别污染上下文
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.AI_TEXT
)

enum class MessageType { USER, AI_TEXT, TOOL_CALL }
```

迁移历史：`v1`（只有 messages）→ `v2`（加 sessions 表 + 外键）→ `v3`（加 `messages.type` 列）。

⚠️ **枚举存的是 `name` 字符串，不是序号**（`MessageTypeConverter`）。往枚举中间插新值时
老数据含义不会错位。别改成 ordinal。

⚠️ **非空列必须给默认值**，否则以后升 schema 时 Room 会报错。

### 3.5 附件链路（多模态）

流程：系统选择器 → `content://` Uri → **复制到磁盘** → Chip 显示就绪 → 发送时按类型分流。

```
Uri (content://)
  └─ ContentResolver.openInputStream  ──►  <workspace>/attachments/<原名>[_时间戳].<ext>
                                             │  冲突时加毫秒时间戳，不覆盖
                                             ▼
                                      Attachment(uri, name, isImage, state, path)
                                             │
                     ┌───────────────────────┴───────────────────────┐
                     ▼ 图片                                          ▼ 其他文件
        imageToDataUrl(path)                              content 里追加一句话：
        inJustDecodeBounds → inSampleSize ≤1280            「请调用你的 read_file 工具…」
        → JPEG 80 → Base64.NO_WRAP
        → "data:image/jpeg;base64,…"
                     │
                     ▼
        content = JSONArray[ {"type":"text"}, {"type":"image_url", "image_url":{"url":…}} ]
```

两个已经定型的架构决策，**不要推翻**：

1. **只存路径，不存 Base64**。Base64 存进 Room 会让数据库膨胀（一张图 60KB 字符），
   而且每次读历史都要解码。发送时现算，算完就丢。
2. **编码前必须降采样**。原图 1280×2856 直接编码是 400KB+ 的 Base64，上传慢、token 贵、
   内存压力大。实测降采样后：`原 1280x2856 → sample=4, 45169 字节, base64 60228 字符`。

已验证：`deepseek-flash` **接受** `image_url`，返回 HTTP 200，且能准确描述截图内容
（读出了时钟 9:00、5G 图标、标题文字、按钮颜色）。这不是猜的，是实测。

### 3.6 Agent 三档预设（已完成）

```kotlin
enum class AgentPreset(val id: String) { STANDARD("standard"), PTC("ptc"), MINIMAL("minimal") }
```

| 预设 | tools 数组 | 系统提示词 | 用途 |
|---|---|---|---|
| **标准** | 6 个工具逐个（按开关过滤） | `SYSTEM_PROMPT` + 工具清单 + 工作区 | 默认。最稳，好定位问题 |
| **PTC** | 只有 `run_code` | `PTC_SYSTEM_PROMPT` + 沙箱 API 文档 | 批量/循环任务，省往返 |
| **极简** | 空数组 | `MINIMAL_SYSTEM_PROMPT`（很短） | 纯对话，省 token，做对照 |

**PTC 的概念来源**：对齐 DSH 的 PTC 预设。DSH 原文（从 `app.asar` 里挖出的官方描述）：

> 「标准模式直接向模型提供各个工具；PTC 让模型用代码组织工具调用。」

DSH 用 TypeScript 运行时（外层工具名 `run_code`），本项目换成 **Rhino + JavaScript**。

#### PTC 沙箱的三层安全约束（缺一不可）

1. **`ClassShutter` 全禁** —— 不给 JS 任何碰 Java 类的通道，`Packages.java.…`、
   `getClass()` 全部拒绝。没有这一层，`Runtime.exec` 就出去了，后面两层全白搭。
2. **能力只有一个入口** —— JS 只能看到 `tools` 对象上的 6 个方法，全部走 `executeTool`。
   因此**工作区拦截、shell 黑名单、文件大小上限在 PTC 模式下同样生效**，不是另开一条路。
3. **指令数观察器 + 墙钟截止** —— `observeInstructionCount` 每 2 万条指令回调一次检查时间，
   超过 `RUN_CODE_TIMEOUT_MS = 15000` 就抛异常中止。否则 `while(true){}` 会让界面再也回不来。

其他必须保持的细节：
- `optimizationLevel = -1`（解释执行）。Android 跑在 ART 上，Rhino 生成字节码那条路走不通。
- `RhinoContext` 是**每线程一个**，用完必须 `exit()`，否则该线程下次拿到脏上下文。
- 内部工具失败**不算** `run_code` 整体失败（模型可能已在代码里补救），界面上不该显示红色。
- 脚本抛异常时**不回滚**已发生的写操作，如实把"已经写了什么"告诉模型。

#### Rhino 1.7.13 的语言方言（必须知道，否则会写出跑不了的代码）

实测结论（脚本：`tools/` 下曾用过的探针，结论可直接复现）：

| 写法 | 结果 |
|---|---|
| `let` / `const` 声明、箭头函数、解构、生成器 | ✅ |
| `for (var x of arr)` / `for (let x of arr)` | ✅ |
| **`for (const x of arr)`** | ❌ 语法错误 |
| **模板字符串 `` `a${x}` `` 插值** | ⚠️ **不报错，但 `${}` 原样保留 → 结果是错的** |
| 展开 `...`、可选链 `?.`、空值合并 `??`、`??=` | ❌ 语法错误 |
| `Map` / `Set` / `includes` / `padStart` / `Array.from` / 正则 / `Date` | ✅ |

**为此加了一道执行前检查 `ptcPreflight(code)`**：在进 JS 引擎之前，用「抹掉字符串和注释后」
的正则扫描这些写法，命中就返回**带行号 + 具体改法**的错误。特别是模板字符串那条 —— 它不报错，
只会静默给出错值，模型会拿着错值继续算，最后给用户一个看起来正常的错误答案。

> `maskJsLiterals` 会把字符串/注释替换成**等长空格**（保留换行），这样既能避免
> `print("a ?? b")` 误报，又不会打乱后面算出的行号。改这段时务必保持"等长"。

### 3.7 Shizuku（提权 shell）

为什么必须用 AIDL：**Shizuku 13 的 `Shizuku.newProcess()` 和 `ShizukuRemoteProcess`
构造器都是包内私有**（用 `javap` 验证过）。App 侧唯一公开的提权路径是
`Shizuku.bindUserService()`，而它要求一个 AIDL 接口。

```
App 进程                         Shizuku 进程（shell 权限）
─────────────────────────────────────────────────────────
bindUserService(intent, args) ──► 实例化 RemoteShellService
                                   │
IRemoteShell.exec(cmd)  ────────►  Runtime.exec("sh -c <cmd>")
        ◄──────────────────────── "退出码\n输出合并"
```

返回值约定：**第一行是退出码，其后是 stdout+stderr 合并的输出**。解析代码在 `runShell`。

黑名单（`SHELL_BLOCKLIST`，正则，约 2535 行）：`rm`、`pm uninstall/clear`、`pm disable`、
`reboot/shutdown/poweroff`、`svc power`、`mkfs*`、`mke2fs`、`fastboot` 等。
命中直接返回「安全拦截」，**并且系统提示词里明确要求模型收到拦截后不要换写法重试**。

⚠️ 黑名单是「防手滑」，不是安全边界。一条 `cat /data/xxx` 它拦不住 —— 真正的隔离要靠
操作系统沙箱。文档和提示词里都不要把它吹成安全机制。

### 3.8 工作区（Workspace）

```kotlin
internal var currentWorkspace: String = ""     // 空串 = 不限制

internal fun workspaceViolation(path: String): String? {
    val target = safeCanonicalPath(File(path))         // canonicalPath：会解析 .. 和符号链接
    val root   = safeCanonicalPath(File(currentWorkspace)).trimEnd('/', '\\')
    val sep    = File.separatorChar                     // ★ 不能写死 '/'
    val inside = target == root || target.startsWith("$root$sep")   // ★ 必须带分隔符
    return if (inside) null else "工作区越界：…"
}
```

这段代码里有**两个曾经真实越界的 bug**，见 §6.3 与 §6.4。改它之前先读完那两节。

默认工作区：`/storage/emulated/0/Documents/CoolStream_Workspace`（首次启动自动创建）。

### 3.9 Token 计费

单价（元 / 百万 token，DeepSeek 官方峰谷价，`deepseek-flash`）：

| | 空闲时段 | 高峰时段 |
|---|---|---|
| 输入（缓存命中） | 0.02 | 0.04 |
| 输入（缓存未命中） | 1 | 2 |
| 输出 | 4 | 8 |

高峰 = 北京时间**周一至周五**（不含法定节假日）`09:00-12:00`、`14:00-18:00`。

⚠️ 这是**估算不是账单**：法定节假日无法在客户端判断（需要节假日表），那几天会按高峰价算，偏高。
⚠️ 单价写死在代码里，官方调价就会过期。

---

## 4. 一次对话的完整数据流

```
用户点发送
   │
   ├─ 附件？→ 图片转 data URL / 文件追加 read_file 指引
   ▼
ChatViewModel.send()  →  messages += 用户消息（Room 落库）
   │                    messages += 空占位气泡（Room 落库，拿 rowId）
   ▼
viewModelScope.launch { runAgentLoop(...) }        ← 必须用 viewModelScope
   │                                                  rememberCoroutineScope 在旋转时会被 cancel
   ▼
┌─ while (round < MAX_TOOL_ROUNDS) {          MAX_TOOL_ROUNDS = 8
│    ① 组 body：model / stream / stream_options{include_usage} / messages / tools / thinking
│    ② OkHttp POST，读 SSE 行
│    ③ 每行：先 parseUsage（必须在 delta 之前！）→ 再 parseDeltaFull
│    ④ 分片进 StringBuilder，满 50ms 才 withContext(Main){ onFlush }
│    ⑤ 流结束补刷
│    ⑥ pending.isEmpty() ?  → onUsage + onDone，return
│        否 ↓
│    ⑦ assistant 消息（含 tool_calls）入 messages
│    ⑧ 逐个 executeTool() → 结果作为 role:"tool" 入 messages
│    ⑨ round++，回到 ①（这就是"自动发起第二次请求"）
│  }
└─ 超出轮次 → onError("工具调用超过 8 轮")
```

关键不变量：
- 每条 `role:"tool"` 消息的 `tool_call_id` 必须与 assistant 那条里的 `id` 一一对应。
- 纯工具调用的 assistant 消息，`content` 必须是 **JSON null**，不能是空串。
- `usage` 那条数据的 `choices` 是空数组，`parseDeltaFull` 会返回 null；**必须先把 usage 抠出来
  再判断 null 并 continue**，否则永远读不到 usage。
- 所有工具执行都在 `Dispatchers.IO`（`runAgentLoop` 本身就跑在 IO），主线程只做 `onFlush`。

---

## 5. 绝对不许破坏的铁律

> 这一节是硬约束。动手前先对照，改完再对照一次。

### 铁律 1：禁止引入 Node.js / 任何第二套运行时来跑界面逻辑

项目存在的唯一理由就是干掉 Node.js + WebView 那套架构带来的高温。引入 Node 等于把立项原因作废。
（Rhino 是例外：它是**纯 JVM 库**，只在 PTC 模式执行用户脚本时短暂工作，不参与界面渲染。

### 铁律 2：死守 50ms 批处理

- `FLUSH_INTERVAL_MS = 50L` 不许改小、不许在分片回调里直接改 Compose state。
- 热路径（每 token 执行一次的代码）里**绝对不能出现 `mutableStateOf` 的赋值**。
- 流结束的尾部补刷不能删。

### 铁律 3：不许动 `sendToDeepSeek` 的网络层

它是**无工具的退化路径**，当前没有任何调用点，但原样保留作为参照。作者明确要求
「一个字不改」。真要改，先确认 `runAgentLoop` 是唯一在跑的路径，且改动只影响退化路径。

### 铁律 4：Shizuku 的 AIDL 通信逻辑不许换实现

`IRemoteShell.aidl` + `bindUserService()` + `RemoteShellService` 是唯一可行的公开路径
（`newProcess()` 是 private）。不要试图改用 `Runtime.exec` 直连、不要引入 libsu 替代。

### 铁律 5：React 到 `optString` 的条件反射 —— 判空只用 `opt()` + `as?`

```kotlin
// ❌ 错：JSON null 在 Android 上会被 String.valueOf 成字符串 "null"
val v = obj.optString("k", "默认值")

// ✅ 对
val v = (obj.opt("k") as? String) ?: "默认值"
```

原因：Android 的 `org.json` 把 JSON `null` 表示成单例 `JSONObject.NULL`，它**不是** Java null，
`optString` 的 fallback 只在「键不存在」时生效。json.org 的参考实现行为相反，所以网上说法互相矛盾。

### 铁律 6：解析流式响应时，usage 必须先于 delta

见 §4 的不变量。顺序写反了就永远拿不到 token 用量，费用统计全变 0。

### 铁律 7：路径安全用 `canonicalPath` + 带分隔符的前缀比较

不许用 `absolutePath`（不解析 `..`），不许写 `startsWith(root)`（会把 `DocumentsX` 放进来），
不许写死 `'/'`（Windows JVM 上是 `\`，单测会红）。

### 铁律 8：`cleanPath` 只能作用于 `path`

`cleanPath` 会削掉首尾的引号/逗号/句号 —— 对路径是必要的，对**正文是毁灭性的**。
`write_file.content`、`replace_in_file.search/replace`、`run_code.code` 一律**不许**过 `cleanPath`。

### 铁律 9：不许拆单文件

见 §3.1。

### 铁律 10：不许删注释里的「人话翻译」

作者零编程基础，那些注释是他唯一能读懂代码的渠道。可以更新、可以补充，不要精简掉。

### 铁律 11：改 `.md` 文件后必须跑一次「非预期改写哨兵检查」

某些 Markdown 处理器会把文档做归一化：图片引用 `![alt](url)` 被删成纯 alt 文本、
中文变成 HTML 实体（`的` → `&#x7684;`）、`&` 转义成 `\&`、表格补空格。
项目历史上真实发生过一次 `README.md` 截图区被清空的事故。改完必须查：

```bash
git diff --unified=0 -- README.md | grep -E "^[-+]" | grep -E "!\[|&#x|\\\\&|logo=|screenshot"
```

有命中就先 `cp` 备份 → `git checkout HEAD -- <file>` → 重新用精确替换的方式改一遍。

### 铁律 12：发布包里绝不能有 API Key —— 而且不能靠人记

**两次事故，同一个根因。**

第一次：打出来的 APK 里，`classes3.dex` 明文含有 `sk-0a1fde5d…`。
第二次（2026-10-22，v1.0.0 已发布到 GitHub Release 之后才发现）：同样是
`buildConfigField` 把 `local.properties` 的 Key 编进了 `BuildConfig`，
反编译 APK 就能直接 `grep` 到 `sk-0a1fd…`（长度 35）。
带毒的包在 Release 页面挂了一段时间，只能按「已经泄露到公网」处理。

**为什么老流程不管用**：原铁律写的是「发版前手动清空 local.properties」。
这依赖人记得住 —— 而人会忘、会被打断、会以为上次清过了。**流程必须交给机器。**

#### 现在的做法（三道防线，缺一不可）

1. **构建层强制**（`app/build.gradle.kts`）
   `defaultConfig` 里照常注入（方便 debug），但 `buildTypes.release` 里
   再覆盖一次：
   ```kotlin
   buildConfigField("String", "DEEPSEEK_API_KEY", "\"\"")
   ```
   这样无论 `local.properties` 里有没有 Key，release 包的
   `BuildConfig.DEEPSEEK_API_KEY` **永远是空串**。不再需要任何人「记得清空」。

2. **逻辑层纠偏**（`MainActivity.kt` 的 `runAgentLoop`）
   Key 必须读 `ApiConfig.apiKey`（用户填的、存加密存储的），
   **不能读 `BuildConfig.DEEPSEEK_API_KEY`**。
   之前读 BuildConfig，导致「用户在设置里填了 Key 却不生效」——
   release 包那个值是空的，只有 debug 包被本机兜底 Key 掩盖了。
   这个 bug 是查 Key 泄露时顺带发现的。

3. **发布前必扫**（不能省）
   ```bash
   ./gradlew assembleRelease
   # 解压 APK，对每个 classes*.dex 搜 sk-
   ```
   **判据：0 命中。** 不是「应该没有」，是必须亲眼看到 0。
   顺带扫 `AIza…`（Google）、`ghp_…`（GitHub）、`BEGIN … PRIVATE KEY`。

#### 其它已确认的安全基线

- `local.properties` **从未进过 git**（`git ls-files` 查不到），
  `.gitignore` 里有 `/local.properties` 和 `local.properties` 两条。
- 签名密钥库 `.keystore/`、`*.jks`、`*.keystore` 同样被忽略。
- `git log --all -S "sk-"` 检查过，**提交历史里没有 Key 字符串**。
  也就是说泄露只发生在 APK 二进制里，源码仓库是干净的。
- 已泄露的那把 Key **必须去 DeepSeek 后台作废**（改代码解决不了）。
- 建议在 GitHub 仓库开启 **Secret Scanning**（Settings → Code security）。

> 教训一句话：**凡是「发版前记得做 X」的规则，迟早会失效。**
> 能写成构建脚本的就写成构建脚本；写不成的，必须有一个「跑一次、看输出」的检查命令。

---

## 6. 历史踩坑记录

> 每条都是**真实发生过**的。格式：症状 → 根因 → 正确做法。

### 6.1 致命级（会静默给出错误结果，或造成数据事故）

**① `optString(name, fallback)` 返回字符串 `"null"`**
症状：模型/程序拿到一个叫 `"null"` 的字符串，判断全错，且不报异常。
根因：Android 的 `JSONObject.NULL` 不是 Java null，`optString` 会 `String.valueOf()` 它。
正确做法：`opt()` + `as? String`。见铁律 5。

**② `stream_options` 不加，流式响应里根本没有 usage**
症状：费用永远 ¥0.0000，`promptTokens=0`。
根因：DeepSeek 默认**只在非流式请求**里返回 usage。流式必须显式要求：
```json
"stream_options": { "include_usage": true }
```

**③ usage 解析顺序写反**
症状：加了 `stream_options` 还是读不到 usage。
根因：最后那条 usage 数据的 `choices` 是空数组，`parseDeltaFull` 返回 null；如果先
`val d = parseDeltaFull(...) ?: continue` 就再也走不到 usage 解析。
正确做法：`parseUsage(payload)?.let { accUsage += it }` **写在** `parseDeltaFull` 之前。

**④ DB 里键不存在 vs 值为 null 混淆**（同 ①）

**⑤ APK 里含明文 API Key**
症状：反编译 `classes3.dex` 能直接搜到 `sk-`。
根因：`buildConfigField` 把 `local.properties` 的值编进了 `BuildConfig`。
正确做法：见铁律 12。

**⑥ 只拉 `chat.db` 得到一个空库**
症状：从设备 `adb pull` 下来的数据库打开后一张表都没有。
根因：Room 开了 **WAL 模式**，新写入还在 `chat.db-wal` 里，主库文件是空的。
正确做法：`chat.db` + `chat.db-wal` + `chat.db-shm` **三个一起拉**，或先 `PRAGMA wal_checkpoint`。

**⑦ 模板字符串静默给出错值（Rhino）**
症状：`run_code` 返回 "成功"，但结果里带着字面的 `${x}`。
根因：Rhino 1.7.13 把反引号当普通字符串引号，不做插值。
正确做法：`ptcPreflight` 里拦掉，提示改用 `'a' + x`。见 §3.6。

### 6.2 崩溃 / 编译不过

**⑧ `Unsupported class file major version 69`** → 见 §2.3 表格。核心：只能在 AS 里换 Gradle JDK 到 21。

**⑨ `versionCode` 溢出的负数**
症状：装包报 `INSTALL_FAILED_INVALID_APK` / versionCode 变负数。
根因：`yyMMddHHmm` = `2610221200` ≈ 2.6e9，超过 Int 上限（2147483647）。
正确做法：截断到**小时** `yyMMddHH`。代价：同一小时内多次构建 versionCode 相同。

**⑩ `SimpleDateFormat` 不指定 Locale**
症状：某些系统语言下 `.toInt()` 抛 `NumberFormatException`。
根因：日期格式化用了本地数字（阿拉伯-印度数字等非 ASCII）。
正确做法：`SimpleDateFormat(pattern, Locale.US)`。

**⑪ `This annotation is not repeatable`**
症状：在某个 Composable 前插入新函数后编译失败。
根因：`@Composable` 是加在**下一行那个函数**上的注解。在 `fun X(` 前面插入新函数时，
原来属于 `X` 的 `@Composable` 被留在了新函数头上，`X` 自己的注解就重复了。
正确做法：插入前先确认注解归属，把注解**和函数一起**复制/移动。

**⑫ `Kotlin DSL` 里不能写 `java.util.Xxx()`**
症状：`Unresolved reference` 或把 `java` 解析成 Gradle 扩展。
正确做法：文件顶部 `import java.util.Properties`，然后直接用 `Properties()`。

**⑬ `CharSequence` 没有 `plus`**
症状：`Unresolved reference 'plus' for operator '+'`。
根因：`StringBuilder.take(n)` 返回 `CharSequence`，只有 `String` 有 `+`。
正确做法：`.take(n).toString() + "..."`。

**⑭ `Context` 同名冲突，KSP 报莫名其妙的 `MissingType`**
症状：编译失败，只看到 `e: [ksp] [MissingType]: Element 'AppDatabase' references a type that is not present`，
看不到任何 `Unresolved reference`。
根因：同时 import 了 `android.content.Context` 和 `org.mozilla.javascript.Context`，Kotlin 报
「Conflicting import」，但 KSP 先挂，把真正的错误盖住了。
正确做法：`import org.mozilla.javascript.Context as RhinoContext`。
**教训**：以后遇到 `[MissingType]` 且看不到具体行，先怀疑「有 import 冲突/未解析符号」，
不要顺着 Room 去找。

**⑮ `Long` 相减判超时**
`SystemClock.elapsedRealtime()` 在单测里恒为 0（`returnDefaultValues = true`）。
沙箱**超时中断无法在 JVM 单测里验证**，只能在真机上试。别的逻辑可以测。

### 6.3 安全 / 越界（真实越界过两次）

**⑯ 前缀比较把 `DocumentsX` 当成工作区内**
```kotlin
// ❌ target = "/storage/emulated/0/DocumentsX/a.txt"
//    root   = "/storage/emulated/0/Documents"
//    target.startsWith(root) == true  ← 放行了！
```
正确做法：比 `"$root$sep"`。

**⑰ `absolutePath` 不解析 `..`**
```kotlin
// ❌ File("/ws/../../etc/passwd").absolutePath 里 .. 还在，startsWith 检查照样通过
```
正确做法：`canonicalPath`（会解析 `..` 和符号链接），并用 try/catch 兜住
（路径不存在时 canonicalPath 不抛，但某些异常文件系统会），失败退回 `absolutePath`。

**⑱ 分隔符写死 `'/'`**
症状：Android 上一切正常，**Windows JVM 上单测 10 条全红**（`canonicalPath` 返回 `\`）。
根因：`startsWith("$root/")` 的分隔符写死了。
正确做法：`File.separatorChar`。这是**单测抓到的真 bug** —— 线上没暴露纯属运气。

### 6.4 性能 / 交互

**⑲ 侧边栏拉开卡顿**
三个叠加根因：① 抽屉里的行直接持有完整 `ChatMessage`（大对象）参与重组；
② 传进抽屉的回调 lambda 每次重组都是新实例，导致所有子项失效；③ 实时模糊。
正确做法：① `@Immutable` 瘦模型（`5.01` 小节）只带 id/title/时间；② 回调用 `remember` 钉住
+ `rememberUpdatedState`；③ 毛玻璃**不用** `Modifier.blur()`，改成「半透明填充 + 1dp 半透明白描边」，
深浅两套（`glassFill()` / `glassEdge()`），依赖 `LocalIsDark` 而不是 `isSystemInDarkTheme()`
（强制主题时后者会撒谎，导致深色底配深色字）。

**⑳ 长按代码块没反应**
根因：外层 `Modifier.horizontalScroll()` 先把长按手势吃掉了。
正确做法：`combinedClickable` 必须写在 `horizontalScroll` **之后**（内层）。
另需 `@OptIn(ExperimentalFoundationApi::class)`。

**㉑ 旋转屏幕丢状态 / 流式请求被掐断**
根因：`rememberCoroutineScope()` 在 Composable 销毁时被 cancel。
正确做法：`ChatViewModel` + `viewModelScope`；`streamJob` 存在 ViewModel 里。

**㉒ 工具卡片重启后消失 / 顺序错乱**
根因：工具卡片只进了内存列表，没写 Room；且插入时用的是同一个 `System.currentTimeMillis()`。
正确做法：`dao.insert` 必须 `await`（`CompletableDeferred`），并做**时间戳分层**：
用户消息 = now、工具卡片 = now+1、AI 占位气泡 = now+2。

**㉓ 自动滚动会跟用户抢**
正确做法：`derivedStateOf` 判断「当前是否已在底部」，只在底部时才自动滚；
新消息用 `animateScrollToItem`，流式增量用 `scrollToItem` + `scrollBy(大值)`。

### 6.5 附件 / 多模态

**㉔ `java.io.File` 打不开 `content://`**
正确做法：`ContentResolver.openInputStream(uri)` 流式复制到磁盘，拿到真实路径。

**㉕ 原图直接 Base64 太大**
正确做法：`BitmapFactory` 两趟解码（`inJustDecodeBounds` 拿尺寸 → 算 2 的幂 `inSampleSize`
→ 确保 ≤1280）→ JPEG 80 → `Base64.NO_WRAP`。

**㉖ Chip 显示"已选择"但模型读不到**
根因：附件只是壳，没有真正落地。
正确做法：见 §3.5 全链路。Chip 必须有 `COPYING / READY / FAILED` 三态，只有 READY 才允许发送。

### 6.6 模型行为（不是代码 bug，但会骗你）

**㉗ 模型更相信自己的历史，而不是刚拿到的工具结果**
现象：同一个长会话里，模型前面说过"read_file 不在我的工具列表里"，后面即使工具真的执行成功，
它仍然回答"我无法读取文件"，而日志里明明有 `读取成功: 68 字符`。
正确做法：**开一个新会话再测**。不要因为这种现象去改代码 —— 代码路径是对的。
提示词里已经写了"不要说我没有权限"，但长会话仍可能被自己的旧话带偏。

**㉘ 模型会把中文标点带进路径**
正确做法：`cleanPath` 削掉首尾中英文标点；提示词里也明确要求。

---

## 7. 已完成 / 未完成

### ✅ 已完成并验证

| 模块 | 状态 | 验证方式 |
|---|---|---|
| 原生 Compose UI（纯黑 + 毛玻璃，无 blur） | ✅ | 真机/模拟器 |
| 50ms 批处理流式渲染 | ✅ | 日志 flush 次数对比，减少 93.6% 无效重绘 |
| Room 持久化（会话 + 消息，v3） | ✅ | 重启后消息还在 |
| Markdown 渲染 + 代码块高亮（零依赖） | ✅ | — |
| 6 大工具链（list_dir / search_files / read_file / write_file / replace_in_file / run_shell） | ✅ | 真机逐条跑通 |
| Shizuku AIDL 提权 + 正则黑名单 | ✅ | 真机授权后执行 |
| 工作区限制（含 `..` 与前缀越界修复） | ✅ | 单测 + 手动 |
| `replace_in_file` 唯一性检查 + 写前备份 + 红绿 Diff | ✅ | 单测 16 条 |
| Token 用量与费用估算 | ✅ | 真实 usage |
| 停止生成 | ✅ | — |
| ViewModel（旋转存活） | ✅ | 手动旋转 |
| 侧边栏长按菜单 / 删除会话 | ✅ | — |
| 工具卡片持久化 + 时间戳分层 | ✅ | 重启后卡片还在、顺序正确 |
| 中英双语基础设施 + 主要界面文案 | 🟡 约 75% | 见下 |
| 首次启动引导 + API Key 加密存储 + 自定义接口地址 | ✅ | — |
| 版本号 `主版本+build.yyMMddHHmm`（tag 与 APK 对齐） | ✅ | 关于页 / 侧边栏 |
| 多模态图片上传（降采样 + data URL） | ✅ | HTTP 200，模型准确描述截图 |
| 非图片附件走 `read_file` 指引 | ✅ | 新会话验证，读出 `BANANA-7742` |
| 附件 Chip 三态 + 落盘 + 时间戳防覆盖 | ✅ | 第二次选择落地为 `attachtest-1792664822047.txt` |
| **Agent 三预设（标准 / PTC / 极简）** | ✅ | 单测 20 条；模拟器 PTC 端到端跑通 |
| **PTC Rhino 沙箱 + 执行前检查** | ✅ | 3 轮完成批量读文件，结果正确（3/7/11，总计 21） |
| 单元测试 37 条 | ✅ | `testDebugUnitTest`，0.8s |

### 🟡 部分完成

**i18n（约 75%）**
- 已有：`values/strings.xml` 与 `values-en/strings.xml` 各 **106 条**，`stringResource` 调用 **84 处**。
- 缺口 **12 处**硬编码中文（都是 `text = "中文"` 直写）：
  `没有匹配的会话`、`完整请求地址：`、`费用按 DeepSeek 官方峰谷单价估算…`、
  `关掉的工具不会出现在请求里…`、`已开启 N / M`、`当前版本：`、`CoolStream 是个人项目…`、
  `参数`、`差异`、`本轮 ≈ ¥x`、`上下文 x / y`、`生成中…`
- 更大的缺口：**所有网络/HTTP 错误文案**（`httpErrorMessage` / `networkErrorMessage`）返回的是
  硬编码中文，而且它们在**非 Composable 的协程里**，拿不到 `stringResource`。
  要给它们加 i18n，需要把 `Context` 一路传进去，或者改成返回资源 id + 参数。
- ⚠️ **不要给系统提示词做 i18n**。那是发给模型的中文指令，不是界面文案，翻译了反而降低效果。

### ⏳ 未开始

- 签名 Release APK（目前只有 debug）
- 服务端代理藏 Key（现在 Key 在客户端，虽然加密存储但仍可被提取）
- 删除会话的确认对话框（现在长按菜单里直接删）
- Markdown 语法高亮（现在只有等宽 + 背景，没有 token 级着色）
- Shizuku 之外的提权方案（如 libsu / Root）
- 设置页 Tab 的中文化（有些地方还是英文）

---

## 8. 测试与验证方法

### 8.1 单元测试（不需要真机，**改完必跑**）

```bash
./gradlew testDebugUnitTest --console=plain
# app/build/test-results/testDebugUnitTest/TEST-*.xml 里有明细
```

37 条：
- `FileToolsTest`（16）—— 唯一匹配、多处拒绝、找不到原文、空白归一化提示、二进制拒读、
  工作区越界、`..` 穿越、`.bak` 备份、`replace_all`、`mkdirs`
- `AgentPresetTest`（20）—— 三预设的 tools 数组、沙箱执行、工具桥接、**Java 类封锁**、
  工具开关拦截、工作区拦截、方言护栏、字符串不误报
- `ExampleUnitTest`（1）—— 模板自带

⚠️ **`app/build.gradle.kts` 里这两项缺一不可**：
```kotlin
testOptions { unitTests.isReturnDefaultValues = true }   // 否则 Log.d 抛 "not mocked"
testImplementation("org.json:json:20240303")             // 否则 org.json 是桩，put() 返回 null
```
第二条是这次踩的新坑：测试跑在 JVM 上，classpath 里那份 `org.json` 来自 android.jar 的桩实现，
配合 `returnDefaultValues = true`，连 `JSONObject().put()` 都返回 null → 所有链式调用 NPE，
**测出来的失败跟代码对不对完全无关**。踩过一次，8 条测试假红。

### 8.2 手动/端到端验证清单

1. 发一条普通消息 → 有回复，费用不为 0
2. 让它读一个文件 → 出现工具卡片，展开能看到参数和结果
3. 切换预设到「极简」→ 再让它读文件 → 它应该说"极简模式下我没有文件访问能力"
4. 切换预设到「PTC」→ 让它遍历一个目录并汇总 → 界面上应该出现 `run_code` 卡片，
   一个卡片里包含多次内部调用
5. 旋转屏幕 → 消息还在，正在流式的请求不被掐断
6. 关掉某个工具 → 下一轮请求的日志里 `本次启用工具 N 个` 应该少一个

看日志只看两个 tag：`MyChatPerf`（业务）和 `ToolExec`（工具执行）。

---

## 9. 提交、推送与发布

### 9.1 Git 身份（仓库级已配好）

```
user.name  = jianling17
user.email = 178297900+jianling17@users.noreply.github.com
branch     = main
remote     = https://github.com/jianling17/CoolStream.git
```

### 9.2 ⚠️ 这台机器 `git push` 会失败，必须用 API 推

现状（2026-10-22 实测）：

| 通道 | 结果 |
|---|---|
| `https_proxy=http://127.0.0.1:5532`（Watt Toolkit）→ `github.com` | **502 / 超时** |
| 直连 `github.com` | 超时 |
| `-c http.schannelCheckRevoke=false` | **Git 2.55 下被忽略，无效** |
| `-c http.sslBackend=openssl -c http.sslVerify=false` | 曾成功过，现在也被 502 挡住 |
| **`api.github.com`** | ✅ **通**（200，0.85s） |

所以：**`git push` 走不通，改用 GitHub Git Data API 逐提交重放。**
脚本：`安卓Compose聊天客户端/tools/push-via-api.py`（在文档工作区里，不在代码仓库里）。

```bash
python push-via-api.py "C:/Users/20732/AndroidStudioProjects/MyChat"
```

原理：对每个待推送提交 → `POST /git/blobs`（base64 内容）→ `POST /git/trees`（`base_tree` = 父提交
tree，删除项 `sha: null`）→ `POST /git/commits`（原样带 author/committer/date/message）
→ `PATCH /git/refs/heads/main`。最后校验「远程 HEAD 的 tree == 本地 HEAD 的 tree」。

**实测结果：6 个提交全部重放，SHA 与本地逐位一致**（`6c7c8e70…` → `79e30f05…`），
即远程历史与本地完全相同，不是「内容一样但历史分叉」。

踩过的两个坑（脚本已修）：
1. **`prev_sha` 必须分成两个变量**。重放后远程 SHA 在本地不存在，拿它做 `git diff` 会
   `fatal: bad object`。要分开：`local_parent`（本地 diff 用）和 `remote_parent`（API 用）。
2. **message 一个字节都不能动**。第一版用了 `msg.rstrip("\n")`，结果 SHA 全部对不上。
   用 `git cat-file -p` 取原始对象、按 `\n\n` 切出正文，**连结尾换行一起传**，SHA 就一致了。
   验证方法：本地复算 commit object 的 SHA-1，能看到「rstrip 版本」恰好等于远程实际生成的 SHA。

推送成功后要手动同步本地跟踪引用：`git update-ref refs/remotes/origin/main <sha>`。

### 9.3 版本标签与发版流程

> 2026-10-22 之后的现状：已发布 `v1.0.1`（安全修复）、`v1.0.2`（权限跳转修复）。
> 更早的 `v0.261022` 曾被删除（那份包里的 versionName 与 tag 对不上）。

**版本号规则**（`app/build.gradle.kts` 的 `defaultConfig`）：

```
versionName = "1.0.2+build.2610222321"
               └主版本┘ └构建时间戳，自动┘
versionCode = 26102223        // yyMMddHH，只到小时
```

主版本写在 `build.gradle.kts` 里那一行 `val appBaseVersion = "1.0.2"`，
**发版时只改这一行**，然后：

1. 改 `appBaseVersion` → 构建 `./gradlew assembleRelease`
2. **必扫**：`powershell -File tools/check-apk-secrets.ps1`，必须 0 命中
3. 真机装上，跑一遍 §8.2 的手动清单
4. `git commit` → `git tag -a v<主版本> -F <说明文件>`（tag 名 = 主版本）
5. 推 tag + 建 Release，附上 APK

这样 tag `v1.0.2`、APK 里 `versionName=1.0.2+build.xxx`、
关于页显示的文字三者一致，用户下载后不会困惑。

### 9.4 打包备份（含 `.git`）

桌面上的 `CoolStream-backup-<时间戳>.zip`。打包脚本要点：排除 `build/`、`.gradle/`、`local.properties`、
`*.apk`、`*.bak`，**但保留 `.git/`**。注意排除目录时必须在**每一层**都过滤，只在根层过滤会打出 35MB 的包。

---

## 10. 下一步开发建议

作者原本的计划是「对齐 0.2-rc2 的 Agent 预设模式」。**这一项已经完成**（见 §3.6，单测 20 条 +
模拟器端到端跑通）。所以下一步的重点应该转向下面这些。

### 优先级 P0（不做会限制真实可用性）

1. **签名 Release APK**
   现在只有 debug 包。需要生成 keystore、配 `signingConfigs`、`assembleRelease`，
   并做一次 R8 混淆验证（Rhino 用了反射，**必须加 keep 规则**，否则 release 包会崩）：
   ```
   -keep class org.mozilla.javascript.** { *; }
   -dontwarn org.mozilla.javascript.**
   ```
   这是接手后**第一个该做的事** —— 混淆一开，PTC 沙箱必炸，早点暴露比晚点好。

2. **i18n 收尾**
   12 处硬编码 `text=` 比较容易；难点是 `httpErrorMessage` / `networkErrorMessage` 这组
   （在非 Composable 协程里）。建议改成返回 `(resId, args)`，在 `withContext(Main)` 那一侧用
   `context.getString(resId, *args)` 组装。

3. **Key 安全**
   现在 Key 加密存在设备上，但仍在客户端。长期方案是自建一个极简代理（Cloudflare Worker /
   腾讯云函数），客户端只存代理地址和访问口令。这也是 README 里已经写明的"待做"。

### 优先级 P1（体验补完）

4. **删除会话加确认**（现在长按直接删，误触就没了）
5. **Markdown 代码块语法高亮**（现在是等宽 + 底色，没有 token 着色）
6. **PTC 模式的工具卡片可视化**：现在一个 `run_code` 卡片折叠后只看到代码和汇总文本，
   看不出「里面调了哪几次工具」。建议在卡片里把 `内部工具调用` 那几行渲染成缩进的小行，
   带各自图标。

### 优先级 P2（探索）

7. **PTC 沙箱换更高版本的 Rhino**
   当前 `rhino-android:1.6.0` 基于 Rhino 1.7.13，ES6 缺口见 §3.6。换成 `org.mozilla:rhino:1.7.15+`
   能补上 `??` / `?.` / `for (const of)`，但要注意 `java.lang.invoke` 在 API 24~25 上不可用
   （minSdk = 24）。**要么升 minSdk 到 26，要么保持现状 + 靠 `ptcPreflight` 兜住。**
   选之前先跑一遍现有的探针脚本，别凭印象。

8. **前台服务跑长任务**
   Manifest 里已经声明了 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` 和通知权限，
   但还没实现。PTC 模式适合跑长任务，配一个常驻通知会明显改善体验。

### 明确**不要**做的事

- ❌ 不要为了"更现代"把 OkHttp 换成 Ktor / Retrofit —— 流式 SSE 手写解析已经稳定，换库只会引入新坑
- ❌ 不要引入 Hilt / Dagger —— 这个体量用不上，反而让作者读不懂
- ❌ 不要把 Room 换成 DataStore / SQLDelight
- ❌ 不要提议「手机连电脑用 DSH 引擎」。作者已明确：**手机端与电脑端完全解耦、各自独立运行**。
  （曾经实测过 ACP 方案并否决：`@deepseek-ai/dsh-acp` 没有 bin；ACP 只推「已提交的整条消息」，
  没有 token 级 delta，实测 124 字符一次性到达，首片延迟 3351ms —— 50ms 批处理在它上面无事可做。）
- ❌ 不要把 60 分的东西包装成 1.0 发布。作者对"诚实标注未完成"这件事非常在意。

---

## 11. 交接检查清单

接手第一天，按顺序做这几件，能避免 90% 的翻车：

- [ ] `./gradlew testDebugUnitTest` 跑通 37 条（不需要真机）
- [ ] `./gradlew assembleDebug` 打得出包
- [ ] 装到设备上，发一条消息能看到回复，`InfoBar` 显示的费用不为 0
- [ ] 读一遍 §5 的 12 条铁律，对照自己打算改的地方
- [ ] 读一遍 §6 的踩坑，尤其是 6.1（静默错值）和 6.3（越界）
- [ ] 确认 `git status` 干净，记住 `git push` 要改用 §9.2 的 API 方式
- [ ] 改任何 `.md` 之后跑 §5 铁律 11 的哨兵检查
- [ ] 如果要发版：先做 §5 铁律 12 的 Key 清理

---

## 附：文件清单

```
MyChat/
├── app/
│   ├── build.gradle.kts              # 自动版本号、buildConfigField、依赖
│   ├── src/main/
│   │   ├── AndroidManifest.xml       # 权限、Shizuku provider、RemoteShellService
│   │   ├── aidl/com/example/mychat/IRemoteShell.aidl
│   │   ├── java/com/example/mychat/MainActivity.kt   ← 7417 行，全部逻辑
│   │   ├── java/com/example/mychat/ui/theme/*.kt     # Compose 主题
│   │   └── res/values/strings.xml, values-en/strings.xml   # 各 106 条
│   └── src/test/java/com/example/mychat/
│       ├── FileToolsTest.kt          # 16 条
│       └── AgentPresetTest.kt        # 20 条
├── docs/                             # 截图 + 立项背景分析
├── README.md                         # 中英双语
├── DEVLOG.md                         # 中英双语开发日志（10 万字，含大量决策记录）
├── CHANGELOG.md                      # [Unreleased] + 「发布前还差什么」
├── restore.md                        # 换机器恢复指南
├── local.properties.example          # 无 Key 模板
└── HANDOVER.md                       # ← 本文件
```

> `DEVLOG.md` 有 10 万字，按小节编号（3.1 ~ 3.15）记录了每个决策的**理由**。
> 遇到"这个设计为什么是这样"的问题，先去那里搜，大概率已经有答案。
