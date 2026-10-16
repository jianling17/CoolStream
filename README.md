<div align="center">

# CoolStream

**一个为「不发热」而生的原生 Android DeepSeek 客户端**

*A native Android DeepSeek client built to run cool.*

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-orange)
![Status](https://img.shields.io/badge/status-personal%20project-lightgrey)

</div>

---

> 📌 **一句话总结**
> 这是一个**用真实日志数据量化验证「UI 批处理能减少多少无效重绘」**的小项目。
> 实测：911 个流式碎片 → 58 次 UI 刷新，**减少 93.6% 的无效重绘**。
> 但它**不会**让你的手机从烫手变成冰凉 —— 原因见 [第 7 节「说明与局限」](#7-说明与局限--limitations)。

---

## 关于开发方式 / How This Was Built

**先把开发方式说清楚，因为这决定了你应该怎么看待这个仓库。**

本项目由一位**没有编程基础的大二学生**完成（**护理学专业**，C 语言课程考试成绩 0 分），开发过程使用了两个 AI 工具：**DeepSeek 网页端** 和 **WorkBuddy 国际版**。

分工是明确的：

| 谁 | 做了什么 |
|---|---|
| **作者（jianling17）** | 提出需求、确定方案方向、**设计 A/B 对照实验**、抓取与分析日志数据、做取舍决策、判断 AI 给出的结论是否站得住 |
| **AI 工具** | Kotlin / Jetpack Compose 代码实现、Bug 定位与修复、文档撰写辅助 |

**这不是传统意义上的独立开发，而是一次「AI 辅助个人开发」的实践记录。**

作者**不具备独立阅读和修改本项目代码的能力**，这一点对代码的可维护性、安全性和长期迭代能力有实质影响，详见 [7.10 节](#710-作者不具备独立维护本项目代码的能力)。

把它记录下来的原因：我认为「一个非程序员借助 AI 能把想法做到什么程度、边界在哪里」这件事本身值得留一份可复现的材料。

---

## 目录

- [关于开发方式](#关于开发方式--how-this-was-built)
- [1. 项目背景](#1-项目背景--background)
- [2. 界面](#2-界面--screenshots)
- [3. 技术栈](#3-技术栈--tech-stack)
- [4. 核心原理](#4-核心原理--how-it-works)
- [5. 实测数据](#5-实测数据--benchmark)
- [6. 编译与运行](#6-编译与运行--build--run)
- [7. 说明与局限](#7-说明与局限--limitations)
- [8. 免责声明](#8-免责声明--disclaimer)
- [9. 后续计划](#9-后续计划--roadmap)
- [10. 作者贡献与致谢](#10-作者贡献与致谢--credits)

---

## 1. 项目背景 / Background

我是一名**大二学生**（护理学专业），没有编程基础。开发方式见上面 [「关于开发方式」](#关于开发方式--how-this-was-built)。

**这个项目的起点不是「我想学安卓开发」，而是一连串具体的麻烦：**

1. 一开始我在**笔记本上本地构建 DSH**（DeepSeek Harness）。能用，但很不方便 —— DSH 是跑在本机的服务，总不能一直坐在电脑前问问题。
2. 于是我用 **DeepSeek 网页端**去找手机版，找到了 [**woaiys3/deepseek-harness-android-app**](https://github.com/woaiys3/deepseek-harness-android-app)：一位大佬把 DSH 打包成了可以直接安装的 Android APK。
3. 装上之后确实方便了，但有个很严重的毛病：**发热极其严重**，用十几分钟就明显烫手，电量掉得也快。
4. 所以我用 **WorkBuddy 国际版**的免费模型额度（底层是 DeepSeek-V4.1-Flash）接入模型，自己写了一个原生 Android 客户端 —— 就是这个仓库 **CoolStream**。UI 用 **Jetpack Compose**，目标只有一个：**把发热降下来**。
5. 顺带一个插曲：写 CoolStream 期间 GitHub 一直连不稳，最后是靠**笔记本上那份 DSH** 帮我排查，把 GitHub 的**裸连**（不走代理的直连）修好了。

### 1.1 先把「烫」变成数字

「感觉烫」不是能拿来做决策的依据。所以我用 **PerfDog** 对那个客户端做了一次采样，拿到两个数字：

| 指标 | 实测 |
|---|---:|
| 退到后台时的 CPU 占用 | **108%（单核口径）** |
| CPU 结温 | **94.5℃** |

两条都反常：一个已经退到后台、界面上什么都不做的 App，正常应该被系统降优先级、限流，占用落在个位数百分比；而 94.5℃ 已经贴着手机的热保护阈值了。

### 1.2 瓶颈落在 WebView

顺着「后台为什么还在算」往下查，占用最高的不是壳本身，而是 **WebView 的渲染进程**。

> **一句准确性说明**：那个客户端**不是「WebView 套壳」**。它的外层是原生 Java 壳，里面内置 Node.js 运行时跑 DSH 引擎；但它的**界面层**是 DSH 自己的 Web 前端，最终由系统 **WebView** 渲染。下面说「WebView 瓶颈」，指的是**界面渲染这一层**，不是整个 App 的架构。

原因不难理解：WebView 本质上是一个完整的浏览器内核。为了渲染一个聊天界面，它要跑一整套 HTML / CSS / JS 引擎，加上排版和合成。对「文字一条一条往外蹦」这种场景来说，这套引擎太重了。

于是判断可以写成一句可直接验证的话：

> **如果发热的主要来源是 WebView 的渲染开销，那么把界面层换成安卓原生 UI，这块开销就应该显著下降。**

（这不是在说那个项目写得不好 —— 只要界面层是 WebView，同样的量级基本躲不掉。）

### 1.3 把目标收敛成一个能测的指标

但「用原生 UI 重写」本身没法验证，得先收敛成一条可量化的假设：

> **流式输出时，UI 不需要每来一个碎片就刷新一次。攒够 50ms 刷一次，重绘次数应该能降一个数量级。**

这就是 **50ms 批处理** 的来历。

代码是 AI 写的。我在这个项目里实际做的事情是这条链路：

> **本地 DSH 不方便 → 换手机版 → 手机版发热严重 → PerfDog 测出 WebView 瓶颈 → 用原生 UI 重写（CoolStream）→ 定义「50ms 批处理」这个可测假设 → 设计 A/B 对照实验 → 抓日志采集数据 → 判断结论是否站得住**

**这个仓库的重点不是「我写了一个 App」，而是「我用可复现的数据验证了一个优化假设」。**

> 本文的数据来源见 [docs/background-dsh-heat-analysis.md](docs/background-dsh-heat-analysis.md)。

---

## 2. 界面 / Screenshots

> ⚠️ 下面的图片还没放进来。如果你 clone 了这个仓库想看效果，请先看 [第 5 节](#5-实测数据--benchmark) 的日志数据。

| 聊天界面 | 流式输出中 |
|:---:|:---:|
| ![chat](docs/screenshot-chat.png) | ![streaming](docs/screenshot-streaming.png) |

| Logcat 日志（对照组 0ms） | Logcat 日志（实验组 50ms） |
|:---:|:---:|
| ![log-a](docs/screenshot-log-control.png) | ![log-b](docs/screenshot-log-experiment.png) |

> 📁 截图请放在 `docs/` 目录下，文件名与上面保持一致即可直接显示。详见 `docs/README.md`。

---

## 3. 技术栈 / Tech Stack

| 层 | 选型 | 为什么 |
|---|---|---|
| 语言 | **Kotlin** | Android 官方首选，语法比 Java 简洁 |
| UI | **Jetpack Compose**（Material 3） | 声明式 UI，没有 XML 布局文件，状态变化自动重组 |
| 网络 | **OkHttp 4.12.0** | 只需一条依赖；流式响应手写 SSE 解析，不引额外库 |
| JSON | **`org.json`** | Android 平台自带，零依赖 |
| 并发 | **Kotlin 协程** | 网络在 `Dispatchers.IO`，UI 更新切回 `Dispatchers.Main` |
| 模型 | **DeepSeek-V4.1-Flash**（`deepseek-v4.1-flash`） | 通过 WorkBuddy 国际版的免费模型额度调用，OpenAI 兼容格式 |

- `minSdk 24`（Android 7.0） / `targetSdk 36`
- 整个项目**只有一个 Activity、一个 Kotlin 文件**（约 580 行），刻意不做架构分层 —— 因为作者是新手，文件越少越不容易出错。

---

## 4. 核心原理 / How It Works

### 4.1 问题：流式输出会「轰炸」UI

大模型流式输出时，文字是一小片一小片推过来的。一次 900 字的回复，服务端可能推送 **900 多个碎片**。

如果每来一个碎片就更新一次界面：

```
碎片到达 → 改状态 → 触发重组 → 重新排版 → 重新绘制
碎片到达 → 改状态 → 触发重组 → 重新排版 → 重新绘制
碎片到达 → 改状态 → 触发重组 → 重新排版 → 重新绘制
...（重复 900 次）
```

**但对人眼来说，一秒钟刷 20 次和刷 300 次，看起来是一模一样的。** 多出来的那 280 次纯粹是浪费 CPU 和电量，而 CPU 干活就会发热。

### 4.2 解法：50ms 批处理

思路很简单：**碎片照收不误，但先攒着，够 50ms 才往界面上写一次。**

```kotlin
// 网络在 Dispatchers.IO 线程上跑
val buffer = StringBuilder()
var lastFlushAt = 0L      // 上次刷界面的时刻
var flushCount = 0        // 实际刷了多少次
var deltaCount = 0        // 收到多少个碎片（= 不批处理时要刷多少次）

while (true) {
    val line = reader.readLine() ?: break
    if (!line.startsWith("data:")) continue
    val data = line.substring(5).trim()
    if (data.isEmpty()) continue
    if (data == "[DONE]") break

    val delta = parseDelta(data) ?: continue

    deltaCount++
    buffer.append(delta)                          // ① 无条件先攒（纯内存操作，几乎零成本）

    val now = SystemClock.elapsedRealtime()
    if (now - lastFlushAt >= FLUSH_INTERVAL_MS) { // ② 够 50ms 才刷
        val text = buffer.toString()
        Log.d(TAG, "flush: ${text.length} chars, interval=${now - lastFlushAt}ms")

        lastFlushAt = now
        flushCount++
        withContext(Dispatchers.Main) { onFlush(text) }   // ★ 只有这一刻才切回主线程
    }
}

// ③ 尾部补偿：流结束时，缓冲区里可能还留着不足 50ms 的最后几个字
withContext(Dispatchers.Main) { onFlush(buffer.toString()) }
```

这里有三个容易忽略的细节：

**① 用 `StringBuilder` 而不是 `String` 拼接。**
Kotlin 的 `String` 是不可变的，`a + b` 每次都要复制全部内容。攒到 1000 字时，每来一个碎片都要复制 1000 个字，整体复杂度是 **O(n²)**。`StringBuilder.append` 是 **O(1)**。

**② 必须做「尾部补偿」。**
如果流结束时缓冲区里还剩几个字没到 50ms，不补刷这一次，**最后几个字就永远不会出现在屏幕上**。

**③ 切线程的次数 = flush 次数，不是碎片数。**
这是本项目的关键。早期版本把批处理写在了 UI 回调里（每个碎片都 `launch` 一次协程），结果是：日志显示 50ms 刷一次，但界面实际每个碎片都在重组 —— 批处理白做了。现在批处理在 IO 线程上完成，**只有真正要刷界面时才切主线程**。

### 4.3 只更新最后一个气泡（增量渲染）

即便刷新的次数少了，每次刷新如果都「重建整个消息列表」，开销还是很大。所以这里只替换列表的最后一条：

```kotlin
onFlush = { text ->
    val lastIndex = messages.lastIndex
    if (lastIndex >= 0 && !messages[lastIndex].isUser) {
        // ★ 原地替换最后一条 AI 气泡，而不是新增一条
        messages[lastIndex] = ChatMessage(content = text, isUser = false)
    }
}
```

配合 Compose 的 `LazyColumn`，只有可见区域的那一个气泡会重绘，**AI 回复看起来像「一个气泡在慢慢变长」，而不是「不停冒出新气泡」**。

### 4.4 关闭思考模式，减少空碎片

`deepseek-v4.1-flash` 的**思考模式默认是开启的**。开启后它会先推一长串思维链，而思维链碎片里 `content` 字段是 `null`（思维内容放在 `reasoning_content` 字段）。

这带来两个问题：首字延迟变长；以及一堆无效碎片。所以显式关掉：

```kotlin
val bodyJson = JSONObject().apply {
    put("model", MODEL_NAME)
    put("stream", true)
    put("messages", messagesArray)
    put("thinking", JSONObject().put("type", "disabled"))   // ★ 关掉思考模式
}.toString()
```

> 顺带一提：思考模式下 `temperature` 参数是**不生效**的（官方明确说明）。关掉之后如果想调温度就能生效了。

### 4.5 多轮上下文：把整个对话历史一起发过去

早期的版本每次只把**当前这一句**发给模型，结果就是：AI 完全不记得上一轮说过什么，每次提问都像第一次见面。

修法是把整个对话历史按顺序打包成 `messages` 数组：

```kotlin
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
for (msg in history) {
    if (msg.content.isBlank()) continue   // 空消息不发
    if (msg.isError) continue             // 错误提示不是 AI 的真实回复，不能当上下文

    messagesArray.put(JSONObject().apply {
        put("role", if (msg.isUser) "user" else "assistant")
        put("content", msg.content)
    })
}
```

界面侧负责把列表整理好再交出去：

```kotlin
// ① 去掉刚加进去的"正在思考…"占位气泡 —— 它是界面上的假数据，不能发给模型
val fullHistory = messages.dropLast(1).toList()      // ★ 必须 toList() 做快照

// ② 只保留最近 N 条，防止对话太长超出模型上下文窗口
val capped = if (fullHistory.size > MAX_HISTORY_MESSAGES) {
    fullHistory.takeLast(MAX_HISTORY_MESSAGES)
} else fullHistory

// ③ 截断后如果第一条变成了 AI 的回复，去掉它，让历史以用户消息开头
val historyForApi = if (capped.firstOrNull()?.isUser == false) capped.drop(1) else capped
```

这里有三个容易忽略的点：

**① 必须做快照（`.toList()`）。**
`messages` 是 `mutableStateListOf`，是一个**活的列表**。如果直接把它的引用传出去，后面的流式更新会同时改动它，导致发出去的内容变得不确定。

**② 占位气泡和错误提示必须排除。**
"正在思考…"是界面假数据；`错误: HTTP 401` 也不是 AI 的真实回复。这两种东西混进上下文都会污染对话。为此给 `ChatMessage` 加了一个 `isError` 标记字段。

**③ 要设长度上限。**
对话越长，请求体越大，最终会超出模型的上下文窗口并报错。这里用 `MAX_HISTORY_MESSAGES = 40`（约 20 轮问答）兜底。

### 4.6 顺手踩到的一个 Android 专属坑：`optString` 会把 JSON null 变成 `"null"`

开发过程中遇到过「流式输出里出现大量 `nullnullnull`」。根因是：

```kotlin
// ❌ 错误写法
.optString("content", "")
```

看起来是「取不到就给空字符串」，**但在 Android 上不是这个行为**。

Android 的 `JSONObject.optString(name, fallback)` 内部是 `JSON.toString(opt(name))`，而 `JSON.toString` 对非 null 对象会调用 `String.valueOf()`。问题是：**JSON 里的 `null` 在 `org.json` 里不是 Java 的 `null`，而是 `JSONObject.NULL` 这个单例对象** —— 它非空，于是被转成了字符串 `"null"`。

| `content` 的实际值 | `optString` 返回 |
|---|---|
| `"你好"` | `"你好"` ✅ |
| `null` | **`"null"`** ❌ |
| 键不存在 | `""` ✅ |

**只有「键不存在」才会用上 fallback。** 正确写法是用 `opt()` 取原始对象再判断类型：

```kotlin
val content = delta.opt("content")   // ★ 不用 optString
(content as? String)?.takeIf { it.isNotEmpty() && it != "null" }
```

`JSONObject.NULL as? String` 结果是 `null`，自然被挡掉。

---

## 5. 实测数据 / Benchmark

### 5.1 测试方法

**唯一变量**：批处理间隔 `FLUSH_INTERVAL_MS`。

- **对照组**：`FLUSH_INTERVAL_MS = 0L`（等价于关闭批处理，每个碎片都刷）
- **实验组**：`FLUSH_INTERVAL_MS = 50L`

App 在每次流结束时输出一行汇总日志，包含三个数字：

| 字段 | 含义 |
|---|---|
| `total` | 回复总字数 |
| `deltas` | 收到多少个流式碎片（**= 不批处理时需要刷新的次数**） |
| `flushes` | 实际刷新了多少次界面 |

### 5.2 原始日志

**对照组（`FLUSH_INTERVAL_MS = 0L`）**

```
stream done: total=1034 chars (deltas=643, flushes=643, skipped=2)
```

**实验组（`FLUSH_INTERVAL_MS = 50L`）**

```
stream done: total=1458 chars (deltas=911, flushes=58, skipped=2)
```

### 5.3 对比

| 指标 | 对照组（0ms） | 实验组（50ms） | 变化 |
|---|---:|---:|---|
| 回复总字数 | 1034 | 1458 | — |
| 收到流式碎片 `deltas` | 643 | 911 | — |
| **实际 UI 刷新 `flushes`** | **643** | **58** | **↓ 93.6%** |
| 刷新 / 碎片 比值 | 1.000 | 0.0637 | — |
| 被过滤的空碎片 `skipped` | 2 | 2 | — |

**计算方式**：

```
实验组减少的无效重绘 = (911 - 58) / 911 = 853 / 911 = 93.6%
对照组减少的无效重绘 = (643 - 643) / 643 = 0%
```

### 5.4 怎么理解这组数字

**第一，对照组证明了「不批处理 = 每个碎片刷一次」。**
643 个碎片对应 643 次刷新，比值正好是 1.000。这说明在没有批处理时，**服务端推多少片，界面就重绘多少次**。

**第二，实验组证明了「批处理确实生效」。**
911 个碎片只触发了 58 次刷新。理论上限是每秒 20 次（1000ms ÷ 50ms），58 次刷新意味着这段流式输出至少持续了 **2.9 秒**。

**第三，一个自洽性检查。**
58 次刷新 × 50ms = 2.9 秒，这是流式过程的最短耗时。也就是说 911 个碎片是在 2.9 秒以上吐完的，平均每秒不超过约 314 个字 —— 这个量级与 `deepseek-v4.1-flash` 的实际速度相符。反过来说：**在那 2.9 秒里，不做批处理就要重绘 911 次，做批处理只要 58 次。**

### 5.5 怎么自己复现

1. Clone 项目，按 [第 6 节](#6-编译与运行--build--run) 配好 API Key
2. 打开 `app/src/main/java/com/example/mychat/MainActivity.kt`
3. 找到配置区的这一行：

   ```kotlin
   private const val FLUSH_INTERVAL_MS = 50L
   ```

4. 改成 `0L` → 保存 → `Run` → 发一条长消息 → 记下 Logcat 里的 `stream done` 那一行
5. 改回 `50L` → 保存 → `Run` → 发**同一条**消息 → 再记一次
6. 对比两次的 `deltas` 和 `flushes`

**查看日志**：Android Studio 打开 Logcat（`Alt + 6`），查询框输入：

```
tag:MyChatPerf & package:mine
```

级别选 `Verbose`（选 `Info` 及以上会把 `Log.d` 过滤掉）。

---

## 6. 编译与运行 / Build & Run

### 6.1 环境要求

| 项目 | 版本 |
|---|---|
| Android Studio | 2026.2.1（Rabbit）或更新 |
| JDK | 17 及以上（用 Android Studio 自带的即可） |
| Android SDK | API 36 |
| 最低运行版本 | Android 7.0（API 24） |
| 手机 | 开启「USB 调试」的 Android 真机（模拟器也可以，但测不了温度） |

### 6.2 第一步：拿到 WorkBuddy 国际版的 API Key

注册 **WorkBuddy 国际版**，在它的免费模型额度里生成一个 API Key，形如 `sk-xxxxxxxx`。

> ⚠️ **两处常量要和你自己的渠道对齐**：代码里 `MainActivity.kt` 的 `API_URL` 目前写的是 `https://api.deepseek.com/chat/completions`，`MODEL_NAME` 写的是 `deepseek-flash`。换用其它渠道的 Key 时，把这两行改成该渠道给出的 OpenAI 兼容地址与模型名即可，**其余代码不用动**（请求体格式和 SSE 解析都是通用的）。

### 6.3 第二步：把 Key 放进 `local.properties`

在**项目根目录**（和 `settings.gradle.kts` 同一层）找到 `local.properties`，在最后新加一行：

```properties
DEEPSEEK_API_KEY=sk-你的Key
```

**格式要求**（这一步最容易出错）：

- 等号两边**不加空格**
- 值**不加引号**
- 不加分号，不写 `export`
- 行尾不要有全角空格

> ✅ `local.properties` 是 Android 项目的标准本地配置文件，新建项目时会被自动写进 `.gitignore`，所以**不会被提交到 GitHub**。
> 你可以自己验证：在项目根目录执行 `git check-ignore -v local.properties`，如果输出里有这个文件名，说明确实被忽略了。

### 6.4 第三步：确认 `app/build.gradle.kts` 里的四处配置

```kotlin
// ① 文件最顶部
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ② plugins 之后，读取 local.properties
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }
}
val deepSeekApiKey: String = localProps.getProperty("DEEPSEEK_API_KEY") ?: ""

android {
    defaultConfig {
        // ③ 把 Key 生成成 BuildConfig.DEEPSEEK_API_KEY
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepSeekApiKey\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true          // ④ 新版 AGP 默认关闭，必须显式打开
    }
}

dependencies {
    // OkHttp：流式请求模型 API（OpenAI 兼容格式）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

另外 `app/src/main/AndroidManifest.xml` 里需要联网权限（**必须放在 `<application>` 外面**）：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

### 6.5 第四步：编译运行

在 Android Studio 里点 **`Sync Now`**（顶部黄色横幅），然后选中你的手机点 **`Run`**。

或者用命令行：

```bash
# Windows
set JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
gradlew.bat assembleDebug

# macOS / Linux
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

### 6.6 常见问题

| 报错 | 原因 | 解法 |
|---|---|---|
| `Unresolved reference: BuildConfig` | 没加 `buildConfig = true` | 加上后重新 Sync |
| `Unresolved reference: okhttp3` | 依赖没加或没 Sync | 检查 `dependencies` 并重新 Sync |
| `Unresolved reference: util`（在 `build.gradle.kts` 里） | 写了 `java.util.Properties()` | Gradle Kotlin DSL 里 `java` 会被解析成 Gradle 扩展，**必须在文件顶部 `import java.util.Properties`** |
| `Could not resolve ... No cached version available for offline mode` | `gradle.properties` 里有 `org.gradle.offline=true` | 注释掉这一行再 Sync |
| `HTTP 401` | Key 无效 / 过期 / 多复制了空格 | 去你的 API 渠道后台重新生成，粘贴时注意空格 |
| `HTTP 404` | 接口地址不完整 | 必须是该渠道的 OpenAI 兼容地址，即 `/chat/completions` 结尾（对应代码里的 `API_URL`） |
| 界面上出现 `nullnullnull` | 用了 `optString` 取 `content` | 见 [4.6 节](#46-顺手踩到的一个-android-专属坑optstring-会把-json-null-变成-null) |
| AI 不记得上一轮说了什么 | 历史没打包，或 `MAX_HISTORY_MESSAGES` 太小 | 见 [4.5 节](#45-多轮上下文把整个对话历史一起发过去) |

---

## 7. 说明与局限 / Limitations

**这一节比上面的性能数字更重要。** 我不想让这个项目看起来像在吹牛。

### 7.1 渲染不是手机发热的唯一原因，甚至不是主要原因

手机在聊天场景下发热，主要来源大致是：

| 发热来源 | 占比感觉 | 本项目能否改善 |
|---|---|---|
| 屏幕背光 | 大头 | ❌ 无关 |
| 射频（Wi-Fi / 蜂窝收发） | 大头 | ❌ 无关（流式请求反而在持续收发） |
| SoC 计算（含 UI 重绘） | 一部分 | ✅ **本项目的目标就是这一块** |
| 电池充放电损耗 | 视情况 | ❌ 无关 |

所以，**批处理优化的是「UI 重绘」这一块，它只是发热来源的一部分。**

### 7.2 预期收益的量级：1~3℃，不是「从烫手变冰凉」

如果你期待换上这个 App 后手机温度「断崖式下降」，**那你会失望**。

合理的预期是机身温度降低 **1~3℃ 量级**，而且这个幅度在环境温度波动面前很容易被淹没 —— 你很难用体感分辨出来。要测出来需要控制变量（拔掉充电线、固定亮度、清后台、静置到同一基线、A/B 交替多轮取平均）。

**本仓库没有提供温度实测数字。** 原因是：手机温度的测量受环境影响太大（室温、握持方式、后台应用、是否充电），我目前的测试条件不足以得出可信的数字。**与其给一个不可信的漂亮数字，不如不给。**

### 7.3 两组数据的回复长度不同，这是本次测试的不足

对照组是 1034 字，实验组是 1458 字，**长度不一样**。

原因是大模型每次生成的长度无法完全控制。这里比较的是**比例**（碎片数 → 刷新数的压缩比）而不是绝对次数，所以长度差异不影响「批处理减少了约 93.6% 的重绘」这个结论；但如果要更严谨，应该：

- 固定提示词并多次采样取平均
- 或者人为控制生成长度（如设置 `max_tokens`）

这一点算本次实验的方法论瑕疵，如实列出。

### 7.4 只在单台设备上测过

测试设备：**小米（型号 24117RK2CC）/ Android 16 / 骁龙 8 系平台（pm8550）**。

不同 SoC、不同刷新率屏幕、不同系统调度策略下，重绘的开销占比会不同。本数据**不能直接外推到其他设备**。

### 7.5 「50ms」不是一个神圣的数字

选 50ms 的理由是：人眼在 60~100ms 以内基本感觉不出卡顿，50ms 意味着每秒最多刷新 20 次，是「看起来流畅」和「省电」之间的一个折中点。

**它不是通过实验优化出来的最优值。** 如果你有兴趣，可以试试 30ms / 100ms / 150ms，看看在你这台设备上哪个是甜点。

### 7.6 数据只说明「重绘次数少了」，没有直接测功耗

日志里的 `flushes` 是**代码自己数的**，它精确反映「UI 状态被更新了多少次」。但它**不等于**「省了多少电」或「降了多少度」。

从「重绘次数减少 93.6%」到「机身温度下降 X℃」之间，还隔着调度器、DVFS、散热设计等一大堆变量。**本项目只证明了前一件事，没有证明后一件事。**

### 7.7 这个项目还没做的事

- ❌ **没有聊天记录持久化** —— 关掉 App 重开，历史就没了
- ❌ **没有 ViewModel** —— 旋转屏幕会丢消息
- ✅ **支持多轮上下文** —— 会把最近 40 条消息一起发给模型（见 [4.5 节](#45-多轮上下文把整个对话历史一起发过去)）
- ❌ **没有 Markdown 渲染** —— 回复是纯文本，代码块不会高亮
- ⚠️ **API Key 的安全性有限** —— 见下

### 7.8 关于 API Key 安全性的诚实说明

本项目用 `local.properties` + `buildConfigField` 的方式管理 Key。它能做到的是：**防止你把 Key 手滑提交到 GitHub**。

**它做不到的是**：`buildConfigField` 生成的字符串会被**编译进 APK**，任何人拿到你的 APK 都可以反编译提取出来。

真正安全的做法是**加一个自己的后端做代理**：App 请求你自己的服务器，服务器再带着 Key 去请求模型 API。这个项目没有做，因为它需要额外的服务器，超出了「学做一个客户端」的范围。

**所以：不要把这个 APK 分发给别人。**

### 7.9 那这个项目的价值到底是什么

不是「我做出了一个不发热的 App」（这个说法站不住）。

而是：

> **在一个真实项目里，用可复现的日志数据，量化验证了「UI 批处理能把流式渲染的重绘次数降低约 93.6%」这个假设，并且清楚地知道这个优化能改善什么、不能改善什么。**

对一个零编程基础的人来说，我觉得这件事本身比 App 更有意思。

### 7.10 作者不具备独立维护本项目代码的能力

这一条不是客套话，是必须写明的边界。

- 作者**无法独立阅读、理解或修改本项目的 Kotlin 代码**。所有代码由 AI 生成，作者只能通过「描述现象 → 让 AI 定位 → 让 AI 修改 → 重新编译运行」这个循环来推进。
- 因此，**代码的可维护性、安全性和长期迭代能力都受此限制**：
  - 遇到 AI 没处理过的报错时，作者无法自行排查
  - 代码里的设计取舍（比如为什么用 `StringBuilder`、为什么把批处理放在 IO 线程），作者能理解**结论**，但无法独立判断**替代方案是否更优**
  - 无法进行代码审查（code review），因为作者读不懂 diff
- 本项目**不适合用于生产环境或任何严肃用途**。它是一份学习记录和一个实验样本，不是一个可以依赖的软件产品。
- 如果要把这个思路用到真实场景，**建议请具备 Android 开发能力的人重新实现**，而不是直接使用这份代码。

---

## 8. 免责声明 / Disclaimer

- 本项目是**个人学习项目**，与 DeepSeek、WorkBuddy 官方均无任何关系，不代表任何一方立场。
- **开发期使用的是 WorkBuddy 国际版的免费模型额度**（底层模型为 DeepSeek-V4.1-Flash），**不是 DeepSeek 官方的付费 API**。使用者复现时请遵守该渠道的服务条款。
- 本项目调用的是渠道公开提供的 **OpenAI 兼容 HTTP 接口**，使用的是公开文档中记载的格式。**不涉及任何逆向工程**，没有破解、没有抓包官方 App、没有绕过任何协议。
- 本项目**不提供任何绕过付费、绕过限额、共享账号的功能**。API 调用产生的费用（如有）由使用者自己承担。
- **API Key 不会被上传到任何地方**，只保存在使用者本机的 `local.properties` 中。但请注意 7.8 节所述的局限性。
- 本项目**仅限个人学习与自用**。请勿用于任何商业用途，也请勿将打包好的 APK 分发给他人。
- 使用者应遵守所用 API 渠道的服务条款及所在地区的相关法律法规。
- 作者是护理学专业学生，**本项目与护理学、医学均无关**，不构成任何医疗建议。

---

## 9. 后续计划 / Roadmap

- [x] 最小可用聊天界面
- [x] 接入模型流式 API（OpenAI 兼容）
- [x] 50ms 批处理 + 只更新最后一个气泡
- [x] 关闭思考模式，过滤空碎片
- [x] 用日志量化验证批处理效果
- [x] 多轮上下文（携带最近 40 条历史）
- [ ] Room 持久化（聊天记录本地保存）
- [ ] ViewModel（旋转屏幕不丢数据）
- [ ] Markdown 渲染
- [ ] 控制变量后的温度实测（需要更严谨的测试条件）

---

## 10. 作者贡献与致谢 / Credits

### 作者（jianling17）做的事

- **发现痛点** —— 长期使用 WebView 方案，观察到「十几分钟就明显发热」这个现象，并判断它值得解决
- **定义优化目标** —— 把模糊的「让 App 不发热」收敛成一个可测量的工程指标：**把流式输出的 UI 刷新从「每个碎片刷一次」改成「攒够 50ms 刷一次」**
- **设计 A/B 对照实验** —— 确定唯一变量（`FLUSH_INTERVAL_MS` 取 `0L` / `50L`），设计对照组与实验组的测试流程
- **数据采集** —— 在真机上抓取 Logcat，记录 `deltas` / `flushes` 两组原始数据
- **数据分析** —— 计算压缩比，得出「减少 93.6% 无效重绘」的结论
- **方案决策** —— 选定模型渠道（WorkBuddy 国际版的免费额度）与 OkHttp 作为网络库，确定 API Key 的存放方式
- **质疑与纠错** —— 在 AI 给出错误结论时提出质疑（例如模型名一度给成了已停用的 `deepseek-chat`）

### AI 工具做的事

- **代码实现** —— 全部 Kotlin / Jetpack Compose 代码、SSE 流式解析、50ms 批处理逻辑、UI 布局
- **Bug 定位与修复** —— `optString` 把 JSON null 读成字符串 `"null"`；批处理放在错误位置导致重组被抵消
- **配置与排错** —— Gradle 依赖、`local.properties` → `BuildConfig` 链路、AndroidManifest 权限
- **文档撰写辅助** —— 本 README 的结构与文字、`docs/` 截图指南、代码注释

### 使用的 AI 工具

| 工具 | 用途 |
|---|---|
| **DeepSeek 网页端** | 方案讨论、代码生成、报错排查 |
| **WorkBuddy 国际版** | 项目搭建、代码修改、构建验证、文档整理 |

### 致谢

- **DeepSeek** —— 提供本项目使用的模型（DeepSeek-V4.1-Flash），以及开发过程中使用的网页端
- **WorkBuddy 国际版** —— 提供开发期的免费模型额度，以及项目开发与验证环境
- **Jetpack Compose / OkHttp** —— 本项目依赖的开源项目

---

## 许可 / License

个人项目，暂未指定开源许可。如需引用其中的思路或代码片段，注明出处即可。

---

<div align="center">

*一份实验记录，不是产品。*

</div>
