# PROGRESS.md — CoolStream 夜间挂机任务进度

> **新对话接手第一条指令**：「读取 PROGRESS.md，查看当前进度，继续执行下一项」。
> 未读本文件绝对禁止修改 `MainActivity.kt`。

---

## 铁律（任何阶段都不得违反）

1. **不许动 50ms 流式批处理防抖逻辑** —— 这是 CoolStream 的核心卖点，改坏即产品归零。
2. 所有文件 IO / ZIP 解压 / 解析必须在 `Dispatchers.IO`，禁止阻塞主线程。
3. 单次提交给模型的纯文本截断 ≤ 10000 字（防 Token 爆炸）。
4. 编译失败最多修 **3 次**；3 次修不好就停下报告原因，不许死循环；必要时 `git restore` 跳过该项。
5. 不破坏 Room 表结构；不引入重型依赖（禁 Node.js / Apache POI），包体 ≤ 3MB。
6. 每完成一个阶段 = 一次 `git commit`，并更新本文件。

---

## 四阶段总计划（用户 m00771 下达）

| 阶段 | 内容 | 状态 |
|---|---|---|
| 一 | JVM 单测扩到 200+ 并 commit | **进行中（代码已写完，待验证）** |
| 二 | 抽出 `ToolExecutor.kt` 降低 MainActivity 体量 | 未开始 |
| 三 | 超长工具结果落盘 `.coolstream/temp/` | 未开始 |
| 四 | 模拟器 Pixel_9_Pro 视觉验证 + 截图存 `test-screenshots/` | 未开始 |

---

## 阶段一：单测扩到 200+

### 已完成
- `MainActivity.kt` 中 19 处 `private` → `internal`（**仅改可见性，逻辑零改动**），供 JVM 单测直接调用：
  `parseDelta`、`ToolCallPiece`、`DeltaInfo`、`parseDeltaFull`、`argOf`、`argBoolOf`、`cleanPath`、
  `maskJsLiterals`、`lineAt`、`ptcPreflight`、`isPeakHour`、`costCny`、`formatCny`、
  `formatTokens`、`parseUsage`、`stripDocBlocks`、`DocCard`、`docCardsOf`、`DOC_END`。

### 新增测试文件（`app/src/test/java/com/example/mychat/`）

| 文件 | 覆盖对象 | 用例数 |
|---|---|---|
| `ToolArgsTest.kt` | `argOf` / `argBoolOf` / `cleanPath` | 40 |
| `PtcGuardTest.kt` | `maskJsLiterals` / `lineAt` / `ptcPreflight` | 35 |
| `SseParseTest.kt` | `parseDelta` / `parseDeltaFull` / `parseUsage` | 32 |
| `UsageMathTest.kt` | `UsageStats` / `costCny` / `formatCny` / `formatTokens` / 账本快照 | 28 |
| `DocSentinelTest.kt` | `stripDocBlocks` / `docCardsOf` | 17 |

新增 152 条；原有 97 条 → **预期合计 249 条**。

### 关键踩坑（务必记住，别再踩）
- **写 Kotlin 测试文件时，源码里绝对不要出现反斜杠转义**。上一次用 `"a\\nb"` 这类写法，
  经过脚本层两次转义后变成真实换行，字符串字面量被截断，
  编译报 `Expecting '"'` / `Unexpected tokens` 一大片。
- **正确做法**：用字符码常量构造特殊字符，再用 `+` 拼接：
  ```kotlin
  private val NL  = 10.toChar().toString()   // 换行
  private val DQ  = 34.toChar().toString()   // 双引号
  private val SQ  = 39.toChar().toString()   // 单引号
  private val BS  = 92.toChar().toString()   // 反斜杠
  private val D   = 36.toChar().toString()   // 美元符
  private val BT  = 96.toChar().toString()   // 反引号
  ```
  含双引号的 JSON / JS 片段优先用 Kotlin **原始字符串** `"""..."""`（无需转义）。
- `costCny()` 依赖 `isPeakHour()`（随时钟变化），断言必须写成「峰价或谷价之一」，不能写死数字。

### 下一步（恢复工作的第一条命令）
```bash
cd /c/Users/20732/AndroidStudioProjects/MyChat
./gradlew testDebugUnitTest --offline
```
- 全绿 → 更新本文件「阶段一 ✅」→ `git add -A && git commit -m "test: 单测扩到 249 条，覆盖参数解析/沙箱守卫/SSE/用量/文档哨兵"` → 进入阶段二。
- 有红 → **最多修 3 次**，第 3 次仍红就停下，把报错原文贴给用户，不要死循环。

---

## 阶段二预告：抽 ToolExecutor.kt

- 目标：把 `MainActivity.kt` 里工具执行相关逻辑（`executeTool` 及其分支、`runCode`、文件工具）迁到独立文件 `ToolExecutor.kt`。
- 约束：**纯搬迁，不改行为**；搬完立刻跑 `testDebugUnitTest`，249 条必须仍然全绿。
- 必须先读本文件，再动手。

---

## 环境备忘

- 项目根：`C:\Users\20732\AndroidStudioProjects\MyChat`
- `adb` / `emulator` 不在 PATH，需用绝对路径：
  `/c/Users/20732/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- 可用 AVD：`Pixel_9_Pro`
- `minSdk = 24` → **不可用 `java.time`**，日期一律 `SimpleDateFormat`
- 测试命令一律加 `--offline`（避免联网拉依赖卡住）
