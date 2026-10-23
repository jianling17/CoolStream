# PROGRESS.md — CoolStream 夜间挂机任务进度

> **新对话接手第一条指令**：「读取 PROGRESS.md，查看当前进度，继续执行下一项」。
> 未读本文件绝对禁止修改 `MainActivity.kt`。

---

## 铁律（任何阶段都不得违反）

1. **不许动 50ms 流式批处理防抖逻辑** —— 核心卖点。锚点：`MainActivity.kt:456` `private const val FLUSH_INTERVAL_MS = 50L`。
2. 所有文件 IO / ZIP 解压 / 解析必须在 `Dispatchers.IO`，禁止阻塞主线程。
3. 单次提交给模型的纯文本截断 ≤ 10000 字（防 Token 爆炸）。
4. 编译失败最多修 **3 次**；3 次修不好就停下报告原因，不许死循环；必要时 `git restore` 跳过该项。
5. 不破坏 Room 表结构；不引入重型依赖（禁 Node.js / Apache POI）。
6. 每完成一个阶段 = 一次 `git commit`，并更新本文件。

---

## 四阶段总计划（用户 m00771 下达）

| 阶段 | 内容 | 状态 |
|---|---|---|
| 一 | JVM 单测扩到 200+ 并 commit | ✅ 完成（b821ef5，249 条） |
| 二 | 抽出 `ToolExecutor.kt` 降低 MainActivity 体量 | ✅ 完成（见下） |
| 三 | 超长工具结果落盘 `.coolstream/temp/` | **下一步** |
| 四 | 模拟器 Pixel_9_Pro 视觉验证 + 截图存 `test-screenshots/` | 未开始 |

---

## 阶段一 ✅（commit b821ef5）

19 处 `private` → `internal` 放宽（逻辑零改动），新增 5 个测试文件共 152 条，总计 **249 条全绿**：

| 文件 | 覆盖对象 | 用例数 |
|---|---|---|
| `ToolArgsTest.kt` | `argOf` / `argBoolOf` / `cleanPath` | 40 |
| `PtcGuardTest.kt` | `maskJsLiterals` / `lineAt` / `ptcPreflight` | 35 |
| `SseParseTest.kt` | `parseDelta` / `parseDeltaFull` / `parseUsage` | 32 |
| `UsageMathTest.kt` | `UsageStats` / `costCny` / `formatCny` / `formatTokens` / 账本快照 | 28 |
| `DocSentinelTest.kt` | `stripDocBlocks` / `docCardsOf` | 17 |

### 关键踩坑：写 Kotlin 源码时禁止反斜杠转义
用 `"a\\nb"` 这类写法，经脚本层两次转义后会变成真实换行，字符串字面量被截断，
编译报 `Expecting '"'` / `Unexpected tokens` 一大片。**正确做法**：字符码常量 + `+` 拼接：
```kotlin
private val NL = 10.toChar().toString()   // 换行
private val DQ = 34.toChar().toString()   // 双引号
private val SQ = 39.toChar().toString()   // 单引号
private val BS = 92.toChar().toString()   // 反斜杠
private val D  = 36.toChar().toString()   // 美元符
private val BT = 96.toChar().toString()   // 反引号
```
含双引号的 JSON / JS 片段优先用 Kotlin **原始字符串** `"""..."""`（无需转义）。
`costCny()` 依赖 `isPeakHour()`（随时钟变化），断言必须写成「峰价或谷价之一」。

---

## 阶段二 ✅（抽出 ToolExecutor.kt）

### 结果
- `MainActivity.kt`：**9326 → 7691 行**（搬走 1635 行）
- `ToolExecutor.kt`：新建 **1892 行**（含复制过来的 236 条 import，未用到的只是 warning）
- 编译 **一次通过**（0 次返工）；`testDebugUnitTest` **249 条仍全绿**；`assembleDebug` 成功

### 搬迁边界（原 MainActivity.kt 第 2073–3707 行）
路径与工作区守卫（`treeUriToPath`/`safeCanonicalPath`/`workspaceViolation`）→
文件工具（`readTextFile`/`writeTextFile`/`replaceInFile`/`listDirectory`/`searchFiles`）→
Shell（`runShell` + Shizuku 提权 `RemoteShellService`/`bindShizukuUserService`/`SHELL_BLOCKLIST`）→
PTC 沙箱（`PtcContextFactory`/`jsHostFunction`/`maskJsLiterals`/`lineAt`/`ptcPreflight`/`runCode`）→
联网搜索（`webSearch`/`parseSearchResults`）→ 派发器 `executeTool`。

### 做法（可复现，别再重新摸索）
1. 因为同属一个 package，**跨文件搬迁不改变 FQN**，唯一风险是 `private` 变成文件私有。
2. 用脚本做双向交叉引用分析：找出「被 moved 块引用的 rest 私有符号」与「被 rest 引用的 moved 私有符号」。
3. 只放宽这 20 个符号，其余 `private` 一律不动 —— **不要无差别全量放宽**，否则会和
   `OfficeParser.kt` 等文件里的同名私有常量撞车。
4. 新文件直接复制原文件的 **全部 import**，靠编译器把未用项当 warning 忽略，省掉猜 import。

放宽清单（均已落盘）：
- moved → internal：`treeUriToPath`、`withAppLocale`、`searchFiles`、`bindShizukuUserService`、`runShell`、`webSearch`
- rest → internal：`TAG`、`httpClient`、`JSON_MEDIA_TYPE`、`isShizukuRunning`、`isShizukuGranted`、
  `MAX_FILE_BYTES`、`MAX_WRITE_BYTES`、`MAX_BACKUP_BYTES`、`MAX_SEARCH_HITS`、`MAX_SEARCH_VISITS`、
  `MAX_SEARCH_DEPTH`、`MAX_LIST_ENTRIES`、`TOOL_LOG_TAG`、`ApiConfig`

---

## 阶段三：超长工具结果落盘 `.coolstream/temp/`（下一步）

### 目标
工具输出过长时（read_file 读到大文件、run_shell 刷屏、web_search 返回长正文），
不要整段塞进模型上下文，改为：**完整内容落盘 + 只回传截断预览 + 文件路径**，
让模型需要时再自己去读。这样既防 Token 爆炸，又不丢信息。

### 建议落点
- 在 `ToolExecutor.kt` 的 `executeTool` 出口统一拦截（一处生效，覆盖所有工具）。
- 落盘目录：工作区下的 `.coolstream/temp/`，文件名带工具名 + 时间戳。
- 阈值/预览长度建议做成常量，方便测试。

### 必须先读
本文件 + `ToolExecutor.kt` 的 `executeTool`（文件末尾），确认 `currentWorkspace` 的取法。

---

## 阶段四预告

`Pixel_9_Pro` 启动 → 装 debug APK → 走一遍「选文档 → 发送 → 看卡片」→ 截图存 `test-screenshots/`。
顺带核验 release APK 体积是否仍满足 ≤3MB（debug 包 12.01 MB 含调试符号，不作为判据）。

---

## 环境备忘

- 项目根：`C:\Users\20732\AndroidStudioProjects\MyChat`
- `adb`/`emulator` 不在 PATH：`/c/Users/20732/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- 可用 AVD：`Pixel_9_Pro`
- `minSdk = 24` → **不可用 `java.time`**，日期一律 `SimpleDateFormat`
- 测试命令一律加 `--offline`
- 单测统计命令：
  `cd app/build/test-results/testDebugUnitTest && awk -F'"' '/<testsuite /{t+=$4; s+=$6; f+=$8; e+=$10} END{print "tests="t" skipped="s" failures="f" errors="e}' *.xml`
