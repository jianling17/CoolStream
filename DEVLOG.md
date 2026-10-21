# CoolStream — Development Log

> Architecture decisions and the pits we fell into. Written for my future self — especially the parts that made no sense at the time and only clicked later.

**English** | [简体中文](#简体中文)

---

# English

## 1. Project Overview

**CoolStream** is a **fully native Jetpack Compose** Android client that ports the capabilities of [DeepSeek Harness](https://github.com/woaiys3/deepseek-harness-android-app) (DSH) from "WebView + embedded Node.js runtime" to pure native code.

### Why it exists

The previous client was `woaiys3/deepseek-harness-android-app`: a native Java shell embedding a Node.js runtime for the DSH engine, with DSH's **web frontend as the UI layer**, rendered by the system WebView.

PerfDog sampling made the problem measurable:

| Metric | Measured |
|---|---:|
| CPU while backgrounded | **108%** (single-core) |
| CPU junction temperature | **94.5 °C** |

An app that is backgrounded and doing nothing visible was saturating a core. The bottleneck is the UI layer: a WebView is a full browser engine, and rendering a chat screen means HTML/CSS/JS parsing, layout and compositing.

### The result

Streaming UI redraws dropped from **911 to 58 — a 93.6% reduction** (same device, single variable).

| | Fragments | Actual redraws | Reduction |
|---|---:|---:|---:|
| Control (0 ms) | 643 | 643 | 0% |
| Experiment (50 ms) | 911 | 58 | **93.6%** |

> ⚠️ **Honest boundary**: this only affects *UI redraws*. It cannot touch screen backlight or the radio. Expect a 1–3 °C order of magnitude — likely imperceptible in hand.
> `flushes` precisely reflects "how many times UI state was updated". It does **not** equal "how much power was saved".

### Final shape

- **Geek UI**: pure black, GitHub/Codex palette, glassmorphism, monospace everywhere
- **DSH agent**: real tool-calling loop (invoke → execute locally → feed result back → second request)
- **Shizuku escalation**: shell commands at uid 2000

---

## 2. Tech Stack

| Layer | Choice | Notes |
|---|---|---|
| UI | Jetpack Compose + Material3 | Single `MainActivity.kt` architecture |
| Network | OkHttp 4.12.0 | Hand-written SSE parsing, no `okhttp-sse` |
| JSON | `org.json` | Ships with Android, zero deps |
| Persistence | Room 2.6.1 + KSP | Two tables: sessions + messages |
| Escalation | Shizuku 13.1.5 | `bindUserService` + AIDL |
| Model | DeepSeek official API | `https://api.deepseek.com/chat/completions` |

> **Two separate things, don't conflate them**: development was AI-assisted with WorkBuddy (international); the **app at runtime calls the official DeepSeek API**.

---

## 3. Core Architecture & Pitfalls

### 3.1 UI and Performance

#### 50 ms batching — the whole point of the project

```
The model emits 30–60 fragments per second → one repaint each → CPU pegged by "relayout + redraw"
```

Fragments accumulate in a `StringBuilder`; the main thread is only touched when more than `FLUSH_INTERVAL_MS` has elapsed since the last write.

```kotlin
private const val FLUSH_INTERVAL_MS = 50L
```

**Two details that are easy to miss:**

1. **You must flush once more at end-of-stream.** Otherwise the last few characters — the ones that arrived inside an unfinished 50 ms window — never reach the screen.
2. **`mutableStateOf` is not a plain variable.** Every assignment triggers recomposition. Never put one on a hot path (loops running dozens of times per second) — use a plain `var`.

#### Pure black + glassmorphism (without `Modifier.blur()`)

`blur()` on Android is **offscreen rendering + a Gaussian convolution, recomputed every frame** — catastrophic for a heat-reduction project.

Instead: a **translucent fill + a semi-transparent white highlight stroke**, which looks close enough at near-zero cost.

```kotlin
val GlassFillDark = Color(0xFF1C1C1E).copy(alpha = 0.80f)   // dark theme
val GlassFillLight = Color(0xFFFFFFFF).copy(alpha = 0.88f)  // light theme
val GlassEdgeDark = Color.White.copy(alpha = 0.15f)
val GlassEdgeLight = Color.Black.copy(alpha = 0.08f)
```

> **Glass must come in two variants.** Applying `#1C1C1E` to a light theme gives dark glass with dark text — completely unreadable.

#### Drawer jank: three root causes, not one

**Symptom**: visible stutter when opening/closing the navigation drawer.

1. Every 50 ms flush does `sessions[i] = sessions[i].copy(messages = ...)`, producing a **new `ChatSession` object**. The sidebar sees "the object changed" → the whole drawer recomposes and the `LazyColumn` re-measures, fighting the drawer animation for frames.
2. `onSelect` / `onSettings` lambdas were recreated on every recomposition → parameters were never equal → **Compose's skipping was defeated**.
3. `ModalDrawerSheet` **hard-codes** a 16 dp shadow (`DrawerDefaults.ModalDrawerElevation`) with no API to change it. That shadow is drawn every frame during the animation.

**Fix:**

```kotlin
// 1. The sidebar gets a "thin" model — it never touches messages or isStreaming
@Immutable data class SidebarSession(val id: String, val title: String, val createdAt: Long)
@Immutable data class SidebarState(val sessions: List<SidebarSession>, val currentSessionId: String)

// 2. Pin callback identity with remember
val streamingState = rememberUpdatedState(isStreaming)
val onSelectSession: (String) -> Unit = remember {
    { id -> if (!streamingState.value) { currentSessionId = id; scope.launch { drawerState.close() } } }
}

// 3. Drop ModalDrawerSheet
Surface(modifier = Modifier.fillMaxHeight().width(320.dp),
        color = glassDrawer(), shape = RoundedCornerShape(0.dp),
        shadowElevation = 0.dp, tonalElevation = 0.dp)
```

`@Immutable` is the key: during streaming, `id` / `title` / `createdAt` are unchanged → `equals` returns true → **Compose skips the entire Sidebar**.

#### Other UI details

- **Global monospace** lives in `Type.kt`: copy all 15 Material text styles with `fontFamily = Monospace`. Doing it per-`Text` guarantees you'll miss the sidebar and dialogs.
- **24 dp bubbles** with a faint translucent highlight border.
- **Input bar** is a floating capsule `RoundedCornerShape(30.dp)` built on `BasicTextField` — not `OutlinedTextField`, which brings its own border and creates a **double stroke** against the outer Surface.

---

### 3.2 Persistence

#### Schema evolution

| Version | Change |
|---|---|
| v1 | Single `messages` table |
| v2 | Added `sessions`; `messages` gained `sessionId` FK (`ON DELETE CASCADE`) |
| v3 | `messages` gained `type` (`USER` / `AI_TEXT` / `TOOL_CALL`) |

#### Pitfalls

**① v2 used destructive migration; v3 does not**

For v1 → v2 the old `messages` rows had no `sessionId` — they couldn't be attached to any session, so `.fallbackToDestructiveMigration()` was fine.

v3 is different — the data is meaningful, so it needs a real migration:

```kotlin
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN type TEXT NOT NULL DEFAULT 'AI_TEXT'")
        db.execSQL("UPDATE messages SET type = 'USER' WHERE isUser = 1")   // backfill
    }
}
```

**② Enums need a `TypeConverter` — and store the `name`, not the `ordinal`**

Room does not understand Kotlin enums. Storing `name` (`"USER"`, `"AI_TEXT"`) instead of the index (`0/1/2`) means inserting a value in the middle of the enum later won't silently reinterpret old rows.

```kotlin
class MessageTypeConverter {
    @TypeConverter fun fromMessageType(value: MessageType): String = value.name
    @TypeConverter fun toMessageType(value: String): MessageType =
        runCatching { MessageType.valueOf(value) }.getOrDefault(MessageType.AI_TEXT)
}
```

`@TypeConverters(MessageTypeConverter::class)` goes on the `@Database`.

**③ Foreign key columns need an index**

```kotlin
@Entity(tableName = "messages",
        foreignKeys = [ForeignKey(entity = SessionEntity::class,
            parentColumns = ["id"], childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE)],
        indices = [Index("sessionId")])
```

Without it Room warns at compile time, and every session deletion full-scans the table.

**④ Write to disk at exactly two moments**

```
On send    → insertSession (if new) + updateSessionTitle (if changed) + insert(user msg) + insert(AI placeholder)
On onDone  → updateContent(AI row, final text)
On onError → updateContent(AI row, "错误: xxx", isError = true)
On onFlush → nothing. Not one line.
```

`onFlush` fires every 50 ms. Writing there means 900 fragments become dozens to hundreds of disk I/Os — guaranteed jank.

---

### 3.3 Agent Toolchain

#### The request must contain `tools`, or the model will just say "I don't have permission"

This was the single easiest thing to misdiagnose in the whole project.

The original `SYSTEM_PROMPT` was `"You are a professional, rigorous assistant"` and the request body had no `tools` field. Testing produced a model that kept answering "I don't have permission to read files" — **not a permission problem at all; it simply didn't know tools existed.**

Both are required:

1. Add a `tools` array to the request body
2. Rewrite `SYSTEM_PROMPT` to list the tools and say "you must call the tool; do not claim you lack permission"

#### `tool_calls` arrive in fragments — accumulate by index

```
Chunk 1: {"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"read_file","arguments":""}}]}
Chunk 2: {"tool_calls":[{"index":0,"function":{"arguments":"{\"pa"}}]}
Chunk 3: {"tool_calls":[{"index":0,"function":{"arguments":"th\":\"/sdcard/a.txt\"}"}}]}
```

Accumulate `id` / `name` / `arguments` in a `LinkedHashMap<Int, JSONObject>`. **Taking only the last chunk's `arguments` gives you a truncated, invalid JSON string.**

#### When it's a pure tool call, `content` must be JSON null

```kotlin
if (buffer.isEmpty()) put("content", JSONObject.NULL)
else put("content", buffer.toString())
```

An empty string is treated as valid content by some implementations.

#### The loop

```
round 1: request with tools
   ↓ streamed tool_calls
append assistant message (with tool_calls)
   ↓
execute locally in executeTool() (IO thread)
   ↓
append role:"tool" + tool_call_id + result
   ↓
round 2: request again → stream the final answer (still through 50 ms batching)
```

`MAX_TOOL_ROUNDS = 5` — "search → read → answer" already needs 3.

#### Two UI-side rules

**① Insert the tool card *before* the AI placeholder**

```kotlin
val at = (list.size - 1).coerceAtLeast(0)
list.add(at, card)
```

`messages.last()` must always be the placeholder — `onFlush` locates it via `last()`. Insert in the wrong place and the entire stream writes into the wrong row.

**② Tool cards must never be sent to the model**

```kotlin
.filter { it.type != MessageType.TOOL_CALL }
```

Otherwise the model receives a JSON blob **it never wrote**, poisoning the context.

#### Tool inventory

| Tool | Params | Notes |
|---|---|---|
| `list_dir` | `path` | Directories first, then files |
| `search_files` | `keyword`, `path` | Recursive filename search, max 20 hits |
| `read_file` | `path` | Text only, 5 MB cap |
| `run_shell` | `command` | Shizuku escalation (see below) |

**`read_file` details:**

- **5 MB cap** — not a feature limit, but an OOM guard on `readBytes()`
- **Binary detection** — if a NUL byte appears in the first 8 KB, it's reported as binary and the model is told *"this cannot be parsed as plain text"* instead of receiving garbage. pptx / docx / zip / images all land here.

**`search_files` safety valves** (otherwise scanning `/sdcard` runs forever):

```kotlin
MAX_SEARCH_HITS   = 20        // max results
MAX_SEARCH_VISITS = 50_000    // max entries examined
MAX_SEARCH_DEPTH  = 12        // max recursion depth
```

Hidden entries (`.thumbnails`, `.trash`) are skipped.

**Path cleaning**: user input often contains Chinese curly quotes `“ ”`, which the model sometimes passes straight into `path`. There's a prompt rule plus a code-level guard:

```kotlin
private fun cleanPath(raw: String): String =
    raw.trim().trim('"', '\'', '“', '”', '‘', '’', '，', ',', '。', '、', '；', ';', '：', ':', ' ', '\n', '\t', '\r')
```

---

### 3.4 Shizuku Escalation

#### ⚠️ `Shizuku.newProcess()` no longer exists in 13.x

Nearly every tutorial online shows `Shizuku.newProcess(...)`. It does not compile:

```
Cannot access 'static fun newProcess(...)': it is private in 'rikka/shizuku/Shizuku'
```

Verified by decompiling the AAR (`javap`):

- `rikka.shizuku.Shizuku` has **no** `newProcess` (it's private)
- `rikka.shizuku.ShizukuRemoteProcess`'s constructor is **package-private**:
  `rikka.shizuku.ShizukuRemoteProcess(moe.shizuku.server.IRemoteProcess)`

**Conclusion: those `newProcess` snippets are Shizuku 12. In 13 the door is closed.**

#### ✅ The only public route: `bindUserService` + AIDL

Shizuku copies our Service into a process running as **uid 2000 (shell)**; the app talks to it over a Binder interface.

**Four parts:**

```
① IRemoteShell.aidl          — the cross-process contract
② RemoteShellService         — the implementation running in the shell process
③ shizukuConnection          — obtains the AIDL proxy
④ runShell()                 — the app-side entry point
```

Configuration:

```kotlin
// build.gradle.kts
buildFeatures { aidl = true }
implementation("dev.rikka.shizuku:api:13.1.5")
implementation("dev.rikka.shizuku:provider:13.1.5")
```

```xml
<!-- AndroidManifest.xml -->
<service android:name=".RemoteShellService" android:exported="false" />
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:enabled="true"
    android:exported="true"
    android:multiprocess="false"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

```kotlin
val args = Shizuku.UserServiceArgs(
    ComponentName(context.packageName, RemoteShellService::class.java.name)
).daemon(false).processNameSuffix("shell").debuggable(false).version(1)
Shizuku.bindUserService(args, shizukuConnection)
```

**`RemoteShellService` does not run in our process** — it runs in the shell process, so `ProcessBuilder` works directly:

```kotlin
class RemoteShellService : IRemoteShell.Stub() {
    override fun exec(command: String): String {
        val process = ProcessBuilder("sh", "-c", "$command 2>&1")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return "${process.waitFor()}\n$output"   // line 1 = exit code, rest = output
    }
}
```

`2>&1` merges stderr — reading the two streams separately **deadlocks** as soon as one buffer fills while nobody reads the other.

#### Three states to distinguish

| State | How to check |
|---|---|
| Shizuku not installed / not started | `Shizuku.pingBinder()` returns false |
| Running but not granted to us | `Shizuku.checkSelfPermission() != PERMISSION_GRANTED` |
| Ready | `bindUserService` connects, then commands work |

The grant result is **not returned synchronously** — it arrives via `OnRequestPermissionResultListener`. Register it in `DisposableEffect` and remove it in `onDispose`, otherwise you leak the listener.

---

### 3.5 Security Layer

#### `SHELL_BLOCKLIST` — a regex blacklist

`run_shell` runs at shell privilege and can do anything. The "ask the user before destructive commands" rule in the prompt is **not a security boundary**, so there's a code-level check.

**Why not `contains("rm -rf")`** — every variant slips through:

| Variant | `contains` | Regex |
|---|---|---|
| `rm -rf` | ✅ | ✅ |
| `rm -fr` | ❌ | ✅ |
| `rm   -rf` (extra spaces) | ❌ | ✅ |
| `RM -RF` | ❌ | ✅ |
| `/system/bin/rm -rf` | ⚠️ by luck | ✅ |
| `busybox rm -rf` | ⚠️ by luck | ✅ |

Approach: **normalise (lowercase + collapse whitespace) then match 16 regex rules**, each using `\b` word boundaries so `charm` isn't flagged.

```kotlin
private val SHELL_BLOCKLIST: List<Pair<String, Regex>> = listOf(
    "rm" to Regex("""\brm\s"""),
    "pm uninstall/clear" to Regex("""\bpm\s+(uninstall|clear)\b"""),
    "pm disable" to Regex("""\bpm\s+disable(-user)?\b"""),
    "reboot/shutdown" to Regex("""\b(reboot|shutdown|poweroff|halt|recovery)\b"""),
    "svc power" to Regex("""\bsvc\s+power\b"""),
    "mkfs/wipe" to Regex("""\b(mkfs(\.[a-z0-9]+)?|mke2fs|fastboot)\b"""),
    "parted/fdisk" to Regex("""\b(parted|fdisk|sgdisk|gdisk)\b"""),
    "dd of=/dev/..." to Regex("""\bdd\b[^|;&]*\bof=\s*/dev/"""),
    "> /dev/block/" to Regex(""">\s*/dev/block/"""),
    "mount remount/rw" to Regex("""\bmount\b[^|;&]*\b(remount|rw)\b"""),
    "chmod -R" to Regex("""\bchmod\b[^|;&]*-[a-z]*r[a-z]*\s"""),
    "chown -R" to Regex("""\bchown\b[^|;&]*-[a-z]*r[a-z]*\s"""),
    "shred/truncate" to Regex("""\b(shred|truncate)\b"""),
    "killall" to Regex("""\bkillall\b"""),
    "kill 1" to Regex("""\bkill\s+(-9\s+)?1\b"""),
    "settings put" to Regex("""\bsettings put\b"""),
)
```

**Placement**: **before** the Shizuku permission check — an unauthorised state should never let a dangerous command reach the execution path.

**Measured**: all 25 should-block cases blocked; all 15 should-pass cases passed (`ls -la`, `pm list packages -3`, `mount`, `mount | grep sdcard`, `charm`). **0 failures.**

#### ⚠️ A safety net, not a security boundary

Shell is Turing-complete. These get through:

```bash
r''m -rf /                                # quote concatenation
echo cm0gLXJmIC8= | base64 -d | sh        # encode then execute
printf '\162\155' -rf /                   # octal escapes
alias x=rm; x -rf /                       # aliases
```

It stops the model from *casually* issuing destructive commands. It does not stop a determined attacker.

---

## 4. Environment & Build (the most time-consuming section)

### ⚠️ JDK 25 is incompatible with Gradle 8.13

Android Studio's bundled `jbr` is now **JDK 25**, and **Gradle 8.13 only supports Java 8–23**:

```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_'
Unsupported class file major version 69
```

`major 69` = Java 25. This also fires during **IDE sync** (the stack trace contains `com.intellij.gradle.toolingExtension...GradleModelFetchAction`).

**The path of failed attempts — do not repeat:**

1. Set `org.gradle.java.home` in the project's `gradle.properties` → **works for CLI only, not for the IDE** (the IDE passes its own JDK via the Tooling API and overrides it)
2. Set it in the user-level `~/.gradle/gradle.properties` → still no effect
3. Set `.idea/gradle.xml`'s `gradleJvm` to an **absolute path** → **rejected**, falls back to `JAVA_PROJECT_JDK`
4. Edit `jdk.table.xml` → **the IDE owns that file at runtime**, edits get overwritten
5. ✅ **The only real fix**: in the IDE, `Gradle JDK` dropdown → `Download JDK` (or `Add JDK`) and pick a **genuinely registered** JDK 21

**A second trap**: when downloading a JDK from the IDE, **avoid Temurin / GraalVM / SapMachine** — their download URLs point at GitHub. Use **Azul Zulu** (`cdn.azul.com`), **Alibaba Dragonwell**, or **Amazon Corretto** (`corretto.aws`).

> A subtle failure mode: `gradleJvm` can end up set to a name like `temurin-21` for a JDK whose **download failed** — a dangling reference. When debugging, cross-check `.idea/gradle.xml`'s `gradleJvm` against `jdk.table.xml` to confirm **the name actually exists**.

### ⚠️ Reinstalling resets the AppOp

After `adb install -r`, the **package-level AppOp for `MANAGE_EXTERNAL_STORAGE` reverts to `default`**. Re-grant it:

```bash
adb shell appops set com.example.mychat MANAGE_EXTERNAL_STORAGE allow
```

Or use the in-app **Settings → File access** button.

### ⚠️ Never build from the IDE and the CLI at the same time

Concurrent builds collide on the incremental cache and produce **phantom failures** (e.g. `Unresolved reference 'MyChatTheme'` for a symbol that is demonstrably present). A clean single run passes.

### ⚠️ "I changed the code but nothing happened" → check the install time first

```bash
adb shell dumpsys package com.example.mychat | grep lastUpdateTime
```

Compare against the build artifact's timestamp. Half an hour was lost to this once — the phone still had an old APK without `runAgentLoop` in it.

### Where Xiaomi hides "All files access"

Not under "App permissions". It is at:
**Settings → Apps → Permission manager → Special permissions → All files access**

The most reliable way to reach it is the intent:
`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`

---

## 5. Current Status

- [x] Environment setup + on-device run
- [x] Minimal chat UI
- [x] DeepSeek streaming API
- [x] 50 ms batching + measured (93.6% fewer redraws)
- [x] Single-file refactor
- [x] Room persistence (sessions + messages, v3)
- [x] Geek dual theme + glassmorphism + drawer layout
- [x] Sidebar state isolation (drawer jank eliminated)
- [x] Markdown + code highlighting (zero-dependency)
- [x] Tool-calling loop (`tools` / `tool_calls` / second request)
- [x] Tools: `list_dir` / `search_files` / `read_file`
- [x] Shizuku escalation + `run_shell` (AIDL + UserService)
- [x] Shell command blacklist

**All core functionality is in place. Moving into day-to-day polish.**

---

## 6. Known Limitations & TODO

### Limitations

1. **Tool cards are not persisted** — memory only; they vanish on restart (final answers are saved).
2. **Crash recovery is incomplete** — an interrupted stream leaves a blank AI placeholder, which is filtered out on load; the user just sees the reply disappear.
3. **Rotation loses state** — `remember` doesn't survive configuration changes; needs ViewModel.
4. **The blacklist is bypassable** — see 3.5.
5. **API key is client-side** — `local.properties` → `BuildConfig`, extractable by decompilation. A server proxy is required for any real deployment.

### TODO

- [ ] Long-press to delete a session (DAO methods exist, UI missing)
- [ ] ViewModel (survive rotation)
- [ ] Persist tool cards (add a `tool_result` column)
- [ ] Command allowlist (a safer model than a blacklist)
- [ ] Signed release APK
- [ ] Server-side key proxy

---

## 7. Three Things to Tell My Future Self

1. **Before changing UI, ask: will this recompute on every refresh?** 50 ms batching cannot save a per-frame blur or shadow.
2. **Before changing config, ask: who owns this file at runtime?** The IDE holds `.idea/*.xml` and `jdk.table.xml`; external edits don't stick.
3. **"My change had no effect" → check the version timestamp before suspecting the code.**

---

<div align="center">

**English** | [简体中文](#简体中文)

</div>

---

# 简体中文

## 1. 项目概述

**CoolStream** 是一个用 **Jetpack Compose 原生实现**的安卓客户端，把 [DeepSeek Harness](https://github.com/woaiys3/deepseek-harness-android-app)（DSH）那套能力，从「WebView + 内嵌 Node.js 运行时」搬到纯原生。

### 为什么做这个

原来的方案是 `woaiys3/deepseek-harness-android-app`：外层原生 Java 壳，内置 Node.js 跑 DSH 引擎，**界面层是 DSH 自己的 Web 前端**，由系统 WebView 渲染。

PerfDog 采样把问题变成了数字：

| 指标 | 实测 |
|---|---:|
| 退到后台时的 CPU 占用 | **108%（单核口径）** |
| CPU 结温 | **94.5 ℃** |

一个已经退到后台、界面上什么都不做的 App，还在满速吃满一个核。瓶颈在界面层：WebView 是一整套浏览器内核，为了渲染聊天界面要跑 HTML / CSS / JS 解析、排版和合成。

### 成果

流式输出的 UI 重绘从 **911 次压到 58 次，减少 93.6%**（同机、单变量）。

| | 碎片数 | 实际刷新 | 减少 |
|---|---:|---:|---:|
| 对照组（0ms） | 643 | 643 | 0% |
| 实验组（50ms） | 911 | 58 | **93.6%** |

> ⚠️ **诚实边界**：这只作用于「UI 重绘」，治不了屏幕背光和射频。预期机身温度下降 1~3℃ 量级，**体感上很可能察觉不到**。
> `flushes` 精确反映「UI 状态被更新了多少次」，但**不等于**「省了多少电」。

### 最终形态

- **极客 UI**：纯黑底 + GitHub / Codex 配色 + 毛玻璃 + 全篇等宽字体
- **DSH Agent**：真实 Tool Calling 闭环（调用 → 本地执行 → 结果回填 → 二次请求）
- **Shizuku 提权**：以 uid 2000（shell）执行命令

---

## 2. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| UI | Jetpack Compose + Material3 | 单 `MainActivity.kt` 架构 |
| 网络 | OkHttp 4.12.0 | 手写 SSE 解析，不用 `okhttp-sse` |
| JSON | `org.json` | 平台自带，零依赖 |
| 持久化 | Room 2.6.1 + KSP | 会话 + 消息两张表 |
| 提权 | Shizuku 13.1.5 | `bindUserService` + AIDL |
| 模型 | DeepSeek 官方 API | `https://api.deepseek.com/chat/completions` |

> **口径要分清**：开发期用 WorkBuddy 国际版辅助写代码；**App 运行时走 DeepSeek 官方 API**。这是两件独立的事，不要合并成一句话。

---

## 3. 核心架构与踩坑记录

### 3.1 UI 与性能

#### 50ms 批处理——项目的立身之本

```
模型每秒吐 30~60 个分片 → 每个都重绘一次 → CPU 被"重新排版 + 重绘"占满
```

分片先攒进 `StringBuilder`，只有距上次刷新超过 `FLUSH_INTERVAL_MS` 才切主线程写一次。

```kotlin
private const val FLUSH_INTERVAL_MS = 50L
```

**两个容易忽略的细节：**

1. **流结束必须补刷一次。** 否则那些落在「不足 50ms」窗口里的最后几个字，永远不会上屏。
2. **`mutableStateOf` 不是普通变量。** 每次赋值都触发重组。热路径（每秒几十次的循环）里绝不能放，要用普通 `var`。

#### 纯黑 + 毛玻璃（不用 `Modifier.blur()`）

`blur()` 在 Android 上是**离屏渲染 + 高斯模糊，每帧重算**——对「防发热」是灾难。

改用「**半透明底 + 半透明白色高光边框**」的拟态做法，视觉接近、开销几乎为零。

```kotlin
val GlassFillDark = Color(0xFF1C1C1E).copy(alpha = 0.80f)   // 暗色
val GlassFillLight = Color(0xFFFFFFFF).copy(alpha = 0.88f)  // 亮色
val GlassEdgeDark = Color.White.copy(alpha = 0.15f)
val GlassEdgeLight = Color.Black.copy(alpha = 0.08f)
```

> **毛玻璃必须做深/亮两套。** 直接把 `#1C1C1E` 用到亮色主题上，深色玻璃 + 深色文字 = 完全看不见。

#### 抽屉卡顿：三层根因，不是一个

**症状**：抽屉拉出/收起时有肉眼可见的卡顿。

1. 每次 50ms flush 都 `sessions[i] = sessions[i].copy(messages = ...)`，**产生新的 `ChatSession` 对象**。侧边栏读到「对象变了」→ 整个抽屉重组 + `LazyColumn` 重新测量，和抽屉动画抢帧。
2. `onSelect` / `onSettings` 这类 lambda 每次重组都新建 → 参数永远不相等 → **Compose 的跳过重组失效**。
3. `ModalDrawerSheet` 内部把阴影**硬编码**成 16dp（`DrawerDefaults.ModalDrawerElevation`），API 不暴露、改不掉。动画期间每帧都要画这层阴影。

**解法：**

```kotlin
// ① 侧边栏只吃"瘦"模型，完全不碰 messages / isStreaming
@Immutable data class SidebarSession(val id: String, val title: String, val createdAt: Long)
@Immutable data class SidebarState(val sessions: List<SidebarSession>, val currentSessionId: String)

// ② 回调用 remember 固定引用
val streamingState = rememberUpdatedState(isStreaming)
val onSelectSession: (String) -> Unit = remember {
    { id -> if (!streamingState.value) { currentSessionId = id; scope.launch { drawerState.close() } } }
}

// ③ 换掉 ModalDrawerSheet
Surface(modifier = Modifier.fillMaxHeight().width(320.dp),
        color = glassDrawer(), shape = RoundedCornerShape(0.dp),
        shadowElevation = 0.dp, tonalElevation = 0.dp)
```

`@Immutable` 是关键：流式刷新期间 `id` / `title` / `createdAt` 都没变 → `equals` 判定相等 → **Compose 直接跳过整个 Sidebar**。

#### 其它 UI 细节

- **全篇等宽**放在 `Type.kt`：把 Material 的 15 个字号档全部 `copy(fontFamily = Monospace)`。逐个 `Text` 写必然漏掉侧边栏和对话框。
- **气泡 24dp 圆角** + 微弱半透明高光边框。
- **输入区**是悬浮胶囊 `RoundedCornerShape(30.dp)`，内部用 `BasicTextField`——不能用 `OutlinedTextField`，它自带边框，会和外层 Surface 形成**双重描边**。

---

### 3.2 数据持久化

#### 表结构演进

| 版本 | 变更 |
|---|---|
| v1 | 单张 `messages` 表 |
| v2 | 新增 `sessions`；`messages` 加 `sessionId` 外键（`ON DELETE CASCADE`） |
| v3 | `messages` 加 `type` 列（`USER` / `AI_TEXT` / `TOOL_CALL`） |

#### 避坑记录

**① v2 用了「删表重建」，v3 改成正经迁移**

v1 → v2 时老 `messages` 表没有 `sessionId`，旧数据挂不到任何会话下，所以 `.fallbackToDestructiveMigration()` 没问题。

v3 不一样——数据是有意义的，必须正经迁移：

```kotlin
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN type TEXT NOT NULL DEFAULT 'AI_TEXT'")
        db.execSQL("UPDATE messages SET type = 'USER' WHERE isUser = 1")   // 老数据回填
    }
}
```

**② 枚举必须配 TypeConverter，而且存 `name` 不存 `ordinal`**

Room 不认识 Kotlin 枚举。存 `name`（`"USER"` / `"AI_TEXT"`）而不是序号（`0/1/2`）——以后往枚举中间插入新值时，老数据的含义不会错位。

```kotlin
class MessageTypeConverter {
    @TypeConverter fun fromMessageType(value: MessageType): String = value.name
    @TypeConverter fun toMessageType(value: String): MessageType =
        runCatching { MessageType.valueOf(value) }.getOrDefault(MessageType.AI_TEXT)
}
```

`@TypeConverters(MessageTypeConverter::class)` 要挂在 `@Database` 上。

**③ 外键列必须建索引**

```kotlin
@Entity(tableName = "messages",
        foreignKeys = [ForeignKey(entity = SessionEntity::class,
            parentColumns = ["id"], childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE)],
        indices = [Index("sessionId")])
```

不建索引的话，Room 编译期报警告，而且每次删会话都要全表扫描。

**④ 写库时机：只有两个点**

```
发消息时  → insertSession(如需) + updateSessionTitle(如需) + insert(用户消息) + insert(AI 占位)
onDone   → updateContent(AI 行, 最终文本)
onError  → updateContent(AI 行, "错误: xxx", isError=true)
onFlush  → 一行都不写
```

`onFlush` 每 50ms 一次，写库的话 900 个碎片就是几十上百次磁盘 IO，界面必卡。

---

### 3.3 Agent 工具链

#### 请求里必须有 `tools`，否则模型只会说"我没权限"

这是整个项目里**最容易误判**的一个坑。

一开始的 `SYSTEM_PROMPT` 是「你是一位专业、严谨、条理清晰的助手」，请求体里也没有 `tools` 字段。测试时模型一直回"我没有权限读取文件"——**不是权限问题，是它压根不知道有工具可用**。

两件事都要做：

1. 请求体加 `tools` 数组
2. 重写 `SYSTEM_PROMPT`，明确列出工具清单 + 「必须调用工具，不要声称没有权限」

#### `tool_calls` 是分片到达的，必须按 index 累积

```
第 1 片: {"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"read_file","arguments":""}}]}
第 2 片: {"tool_calls":[{"index":0,"function":{"arguments":"{\"pa"}}]}
第 3 片: {"tool_calls":[{"index":0,"function":{"arguments":"th\":\"/sdcard/a.txt\"}"}}]}
```

用 `LinkedHashMap<Int, JSONObject>` 累积 `id` / `name` / `arguments`。**只取最后一次的 `arguments` 会拿到一小段残缺 JSON。**

#### 纯工具调用时 `content` 必须是 JSON null

```kotlin
if (buffer.isEmpty()) put("content", JSONObject.NULL)
else put("content", buffer.toString())
```

空串会被某些实现当成有效内容。

#### 闭环流程

```
round 1: 请求带 tools
   ↓ 流式返回 tool_calls
追加 assistant 消息（含 tool_calls）
   ↓
本地执行 executeTool()（IO 线程）
   ↓
追加 role:"tool" + tool_call_id + 结果
   ↓
round 2: 再次请求 → 流式返回最终回答（同样走 50ms 批处理）
```

`MAX_TOOL_ROUNDS = 5`——「搜索 → 读取 → 回答」至少需要 3 轮。

#### UI 侧两条铁律

**① 工具卡片要插在「AI 占位气泡」之前**

```kotlin
val at = (list.size - 1).coerceAtLeast(0)
list.add(at, card)
```

`messages.last()` 必须永远是占位气泡——`onFlush` 靠 `last()` 定位，插错位置整段流式就写歪了。

**② 工具卡片不能发给模型**

```kotlin
.filter { it.type != MessageType.TOOL_CALL }
```

否则模型下一轮会收到一段**它自己没写过的 JSON**，上下文就污染了。

#### 工具清单

| 工具 | 参数 | 说明 |
|---|---|---|
| `list_dir` | `path` | 列目录，目录在前文件在后 |
| `search_files` | `keyword`, `path` | 递归搜文件名，最多 20 条 |
| `read_file` | `path` | 只读文本，5MB 上限 |
| `run_shell` | `command` | Shizuku 提权执行（见下） |

**`read_file` 的两个细节：**

- **5MB 上限**：不是限制功能，是 `readBytes()` 太大容易 OOM
- **二进制探测**：前 8KB 出现 NUL 字节就判定为二进制，明确告诉模型「无法作为纯文本解析」，**不让它拿到一串乱码**。pptx / docx / zip / 图片都走这条。

**`search_files` 的三个安全阀**（不然在 `/sdcard` 里搜能扫到天荒地老）：

```kotlin
MAX_SEARCH_HITS   = 20        // 最多返回 20 条
MAX_SEARCH_VISITS = 50_000    // 最多检查 5 万个条目
MAX_SEARCH_DEPTH  = 12        // 最多递归 12 层
```

以 `.` 开头的隐藏项（`.thumbnails` / `.trash`）会被跳过。

**路径清洗**：用户输入常带中文双引号 `“ ”`，模型有时会把它一起塞进 `path`。提示词里有规则，代码里再加一道：

```kotlin
private fun cleanPath(raw: String): String =
    raw.trim().trim('"', '\'', '“', '”', '‘', '’', '，', ',', '。', '、', '；', ';', '：', ':', ' ', '\n', '\t', '\r')
```

---

### 3.4 Shizuku 提权

#### ⚠️ `Shizuku.newProcess()` 在 13.x 里已经不可用

网上（包括大量教程）都写 `Shizuku.newProcess(...)`，但编译直接报错：

```
Cannot access 'static fun newProcess(...)': it is private in 'rikka/shizuku/Shizuku'
```

反编译 AAR 逐项核对（`javap`）：

- `rikka.shizuku.Shizuku` 里**没有** `newProcess`（是 private）
- `rikka.shizuku.ShizukuRemoteProcess` 的构造器是**包内私有**：
  `rikka.shizuku.ShizukuRemoteProcess(moe.shizuku.server.IRemoteProcess)`

**结论：那些 `newProcess` 示例是 Shizuku 12 的老写法，13 已经封闭。**

#### ✅ 正解：`bindUserService` + AIDL

Shizuku 把我们的 Service **复制到 uid 2000（shell）的进程里**运行，App 通过 Binder 接口把命令丢过去。

**四段式：**

```
① IRemoteShell.aidl          —— 跨进程接口契约
② RemoteShellService         —— 跑在 shell 进程里的实现
③ shizukuConnection          —— 拿到 AIDL 代理
④ runShell()                 —— App 侧调用入口
```

关键配置：

```kotlin
// build.gradle.kts
buildFeatures { aidl = true }
implementation("dev.rikka.shizuku:api:13.1.5")
implementation("dev.rikka.shizuku:provider:13.1.5")
```

```xml
<!-- AndroidManifest.xml -->
<service android:name=".RemoteShellService" android:exported="false" />
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:enabled="true"
    android:exported="true"
    android:multiprocess="false"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

```kotlin
val args = Shizuku.UserServiceArgs(
    ComponentName(context.packageName, RemoteShellService::class.java.name)
).daemon(false).processNameSuffix("shell").debuggable(false).version(1)
Shizuku.bindUserService(args, shizukuConnection)
```

**`RemoteShellService` 的代码不在我们 App 的进程里执行**——它在 shell 进程里，所以可以直接 `ProcessBuilder`：

```kotlin
class RemoteShellService : IRemoteShell.Stub() {
    override fun exec(command: String): String {
        val process = ProcessBuilder("sh", "-c", "$command 2>&1")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return "${process.waitFor()}\n$output"   // 第一行退出码，后面是输出
    }
}
```

`2>&1` 合并 stderr——分开读两个流时，一个写满缓冲区另一个还没人读就会**死锁**。

#### 三个状态要分清

| 状态 | 判断方式 |
|---|---|
| Shizuku 没装 / 服务没启动 | `Shizuku.pingBinder()` 返回 false |
| 服务在跑但没给我们授权 | `Shizuku.checkSelfPermission() != PERMISSION_GRANTED` |
| 都 OK | `bindUserService` 连上后，命令才能执行 |

授权结果**不是同步返回的**，要走 `OnRequestPermissionResultListener`；用 `DisposableEffect` 注册、`onDispose` 摘掉，否则会泄漏 listener。

---

### 3.5 安全防线

#### `SHELL_BLOCKLIST` 正则黑名单

`run_shell` 是 shell 权限，能做任何事。提示词里的「破坏性命令先问用户」**不是安全边界**，所以加了一层代码级硬拦截。

**为什么不用 `contains("rm -rf")`**——任何变体都能绕过：

| 变体 | `contains` | 正则 |
|---|---|---|
| `rm -rf` | ✅ | ✅ |
| `rm -fr` | ❌ | ✅ |
| `rm   -rf`（多空格） | ❌ | ✅ |
| `RM -RF` | ❌ | ✅ |
| `/system/bin/rm -rf` | ⚠️ 碰巧 | ✅ |
| `busybox rm -rf` | ⚠️ 碰巧 | ✅ |

做法：**归一化（转小写 + 压缩连续空白）+ 16 条正则规则**，每条用 `\b` 做词边界，避免误伤 `charm` 这类含 `rm` 的单词。

```kotlin
private val SHELL_BLOCKLIST: List<Pair<String, Regex>> = listOf(
    "删除文件 (rm)" to Regex("""\brm\s"""),
    "卸载或清除应用 (pm uninstall/clear)" to Regex("""\bpm\s+(uninstall|clear)\b"""),
    "禁用应用 (pm disable)" to Regex("""\bpm\s+disable(-user)?\b"""),
    "重启或关机 (reboot/shutdown)" to Regex("""\b(reboot|shutdown|poweroff|halt|recovery)\b"""),
    "电源控制 (svc power)" to Regex("""\bsvc\s+power\b"""),
    "格式化分区 (mkfs/wipe)" to Regex("""\b(mkfs(\.[a-z0-9]+)?|mke2fs|fastboot)\b"""),
    "修改分区表 (parted/fdisk)" to Regex("""\b(parted|fdisk|sgdisk|gdisk)\b"""),
    "写块设备 (dd of=/dev/...)" to Regex("""\bdd\b[^|;&]*\bof=\s*/dev/"""),
    "直接写块设备节点 (> /dev/block/)" to Regex(""">\s*/dev/block/"""),
    "以读写方式重挂分区 (mount remount/rw)" to Regex("""\bmount\b[^|;&]*\b(remount|rw)\b"""),
    "递归修改权限 (chmod -R)" to Regex("""\bchmod\b[^|;&]*-[a-z]*r[a-z]*\s"""),
    "递归修改属主 (chown -R)" to Regex("""\bchown\b[^|;&]*-[a-z]*r[a-z]*\s"""),
    "粉碎或截断文件 (shred/truncate)" to Regex("""\b(shred|truncate)\b"""),
    "批量杀进程 (killall)" to Regex("""\bkillall\b"""),
    "杀 1 号进程 (kill 1)" to Regex("""\bkill\s+(-9\s+)?1\b"""),
    "修改系统设置 (settings put)" to Regex("""\bsettings put\b"""),
)
```

**拦截位置**：放在 **Shizuku 权限检查之前**——即使没授权，也不该让危险命令走到执行路径。

**实测**：25 条该拦的全部拦住，15 条该放行的全部放行（`ls -la`、`pm list packages -3`、`mount`、`mount | grep sdcard`、`charm` 均未误伤），**0 失败**。

#### ⚠️ 这是安全网，不是安全边界

shell 是图灵完备的，以下**拦不住**：

```bash
r''m -rf /                                # 引号拼接
echo cm0gLXJmIC8= | base64 -d | sh        # 编码后执行
printf '\162\155' -rf /                   # 八进制转义
alias x=rm; x -rf /                       # 别名
```

**能防住的是**模型「顺手」发出的破坏性命令，**防不住蓄意攻击**。

---

## 4. 环境与构建（这一节的坑最耗时间）

### ⚠️ JDK 25 与 Gradle 8.13 不兼容

Android Studio 自带的 `jbr` 已升到 **JDK 25**，而 **Gradle 8.13 只支持 Java 8~23**：

```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_'
Unsupported class file major version 69
```

`major 69` = Java 25。这个错在 **AS 点 Sync 时也会出现**（堆栈里有 `com.intellij.gradle.toolingExtension...GradleModelFetchAction`）。

**踩坑顺序（别再重走）：**

1. 改项目 `gradle.properties` 的 `org.gradle.java.home` → **只对命令行有效，AS 无效**（AS 通过 Tooling API 塞自己的 JDK，会覆盖）
2. 改用户级 `~/.gradle/gradle.properties` → 仍无效
3. 改 `.idea/gradle.xml` 的 `gradleJvm` 为**绝对路径** → **AS 不认**，回退成 `JAVA_PROJECT_JDK`
4. 改 `jdk.table.xml` → **AS 运行期独占该文件**，改了就被覆盖
5. ✅ **唯一正解**：在 AS 里 `Gradle JDK` 下拉 → `Download JDK`（或 `Add JDK`）选一个**真实注册过**的 JDK 21

**另一个坑**：AS 下载 JDK 时**不要选 Temurin / GraalVM / SapMachine**——它们的下载源是 GitHub，不稳。改用 **Azul Zulu**（`cdn.azul.com`）/ **Alibaba Dragonwell**（阿里云）/ **Amazon Corretto**（`corretto.aws`）。

> 还有个隐蔽的失败模式：`gradleJvm` 可能被写成 `temurin-21` 这种**下载失败的名字**，形成悬空引用。排查时要拿 `.idea/gradle.xml` 的 `gradleJvm` 去 `jdk.table.xml` 核对**这个名字是否真实存在**。

### ⚠️ 重装会重置 AppOp

`adb install -r` 之后，`MANAGE_EXTERNAL_STORAGE` 的**包级 AppOp 会变回 `default`**，需要重新授予：

```bash
adb shell appops set com.example.mychat MANAGE_EXTERNAL_STORAGE allow
```

或者用 App 里「设置 → 文件权限」按钮再授权一次。

### ⚠️ AS 和命令行不要同时编译

两边同时编会撞增量缓存，出现**假失败**（例如 `Unresolved reference 'MyChatTheme'`——其实那个符号好好地在文件里）。单独重跑一次就能通过。

### ⚠️ 改了没效果，先查版本时间

```bash
adb shell dumpsys package com.example.mychat | grep lastUpdateTime
```

和构建产物的时间对比。曾因为这个问题浪费了半小时——手机装的是旧包，`runAgentLoop` 压根不在里面。

### 小米的「所有文件访问权限」在哪

不在「应用权限」页，在：**设置 → 应用设置 → 权限管理 → 特殊权限 → 所有文件访问权限**。

最稳的到达方式是代码直跳 `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`。

---

## 5. 当前进度

- [x] 环境搭建与真机运行
- [x] 最小聊天界面
- [x] 接入 DeepSeek 流式 API
- [x] 50ms 批处理 + 实测验证（重绘减少 93.6%）
- [x] 单文件架构重构
- [x] Room 持久化（会话 + 消息，v3 schema）
- [x] 极客风双皮肤 + 毛玻璃 + 抽屉式布局
- [x] 侧边栏状态隔离，消除抽屉卡顿
- [x] Markdown 渲染 + 代码块高亮（零依赖自研）
- [x] Tool Calling 闭环（`tools` / `tool_calls` / 二次请求）
- [x] 工具链：`list_dir` / `search_files` / `read_file`
- [x] Shizuku 提权 + `run_shell`（AIDL + UserService）
- [x] 命令黑名单安全防护

**已实现全部核心功能，进入日常体验打磨阶段。**

---

## 6. 已知限制与 TODO

### 限制

1. **工具卡片没有入库**，只存在内存里。重启 App 后卡片消失（最终回答在库里）。
2. **崩溃恢复不完整**：流式中断会留下空白的 AI 占位行，加载时被过滤掉，但用户看到的是「回复没了」。
3. **旋转屏幕会丢当前状态**：`remember` 不跨配置变更，需要 ViewModel。
4. **黑名单可被绕过**：见 3.5 节。
5. **API Key 在客户端**：`local.properties` → `BuildConfig`，反编译能拿到。生产环境必须加后端代理。

### TODO

- [ ] 会话长按删除（DAO 方法已有，缺 UI）
- [ ] ViewModel 解决旋转丢状态
- [ ] 工具卡片持久化（给 `messages` 加 `tool_result` 列）
- [ ] 命令白名单（比黑名单更安全的模型）
- [ ] 签名打包 APK
- [ ] 服务端代理藏 Key

---

## 7. 给未来自己的三句话

1. **改 UI 前先问：这个会不会每次刷新都重算？** 50ms 批处理救不了每帧重绘的模糊和阴影。
2. **改配置前先问：这个文件是谁独占的？** AS 运行期持有 `.idea/*.xml` 和 `jdk.table.xml`，外部改不动。
3. **「改了没效果」先查版本时间，再怀疑代码。**

---

<div align="center">

[English](#english) | **简体中文**

*一份实验记录，不是产品。*

</div>
