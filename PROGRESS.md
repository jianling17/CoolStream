# PROGRESS.md — CoolStream 夜间挂机任务进度

> **新对话接手第一条指令**：「读取 PROGRESS.md，查看当前进度，继续执行下一项」。
> 未读本文件绝对禁止修改 `MainActivity.kt`。

---

## 铁律（任何阶段都不得违反）

1. **不许动 50ms 流式批处理防抖逻辑** —— 核心卖点。锚点：`MainActivity.kt` 里的
   `private const val FLUSH_INTERVAL_MS = 50L`（行号会随编辑漂移，用 grep 找，别记死数字）。
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
| 二 | 抽出 `ToolExecutor.kt` 降低 MainActivity 体量 | ✅ 完成（68fda9f） |
| 三 | 超长工具结果落盘 `.coolstream/temp/` | ✅ 完成（257 条全绿） |
| 四 | 模拟器 Pixel_9_Pro 视觉验证 + 截图存 `test-screenshots/` | **下一步** |

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
private val BS = 92.toChar().toString()   // 反斜杠
```
含双引号的 JSON / JS 片段优先用 Kotlin **原始字符串** `"""..."""`（无需转义）。
`costCny()` 依赖 `isPeakHour()`（随时钟变化），断言必须写成「峰价或谷价之一」。

### 另一个坑：文件是 CRLF
`ToolExecutor.kt` / `MainActivity.kt` 都是 **CRLF** 换行。用脚本做字符串替换时，
pattern 里写 `\n` 一律匹配不上，必须先 `const EOL = src.includes("\r\n") ? "\r\n" : "\n"` 再拼。

---

## 阶段二 ✅（commit 68fda9f）

- `MainActivity.kt`：**9326 → 7691 行**（搬走 1635 行）
- `ToolExecutor.kt`：新建 **1892 行**
- 编译一次通过；`testDebugUnitTest` 249 条全绿；`assembleDebug` 成功

### 搬迁边界（原 MainActivity.kt 第 2073–3707 行）
路径与工作区守卫 → 文件工具 → Shell + Shizuku → PTC 沙箱 → `webSearch` → `executeTool` 派发器。

### 做法（可复现）
1. 同 package 跨文件搬迁**不改变 FQN**，唯一风险是 `private` 变成文件私有。
2. 脚本做双向交叉引用分析，只放宽真正跨文件用到的 20 个符号。
3. **不要无差别全量放宽**，否则与 `OfficeParser.kt` 同名私有常量撞车。
4. 新文件直接复制原文件全部 import，靠编译器把未用项当 warning 忽略。

---

## 阶段三 ✅（超长工具结果落盘）

### 落点
`ToolExecutor.kt` 的 `executeTool` **出口统一拦截**（一处生效，覆盖全部工具）：
```kotlin
val result = try { when (name) { ... } } catch (e: Exception) { ... }
return if (result.ok) result.copy(output = persistOversizeToolOutput(name, result.output)) else result
```

### 常量与函数（都在 `ToolExecutor.kt`）
| 名字 | 值 | 含义 |
|---|---|---|
| `TOOL_INLINE_LIMIT` | 8_000 | 超过就落盘 |
| `TOOL_INLINE_HEAD` | 4_000 | 回传给模型的预览长度 |
| `TOOL_TEMP_DIR` | `.coolstream/temp` | 相对工作区的落盘目录 |
| `TOOL_TEMP_KEEP` | 50 | 只留最近 50 个临时文件 |

`persistOversizeToolOutput(toolName, output)`：短输出原样返回；超长则写
`<工作区>/.coolstream/temp/<工具名>-<时间戳>.txt`，回传 = 前 4000 字 + 总字符数 +
文件绝对路径 + 「用 read_file 配合 offset 分段读」提示。
工具名做了 `isLetterOrDigit() || '_'` 白名单过滤，防目录穿越。
工作区为空 / 目录创建失败 / 写盘异常，都退化成「只回预览 + 说明」，绝不抛异常打断工具链。

### 线程安全
`executeTool` 由 `runAgentLoop` 调用，而后者本身就跑在 IO 协程里
（`MainActivity.kt` 里明确注释「runAgentLoop 本来就跑在 IO 协程里」），写盘不碰主线程。

### 测试
新增 `app/src/test/java/com/example/mychat/ToolOutputPersistTest.kt` **8 条**：
短输出不动 / 空串不动 / 恰好等于上限不动 / 超长落盘且内容完整 / 尾部不进提示 /
工作区为空降级 / 工具名净化 / 超量只留 50 个。
**总计 257 条全绿**。

---

## 告警清理 ✅（commit 9669cfd）

- MainActivity.kt 删 15 条失效 import（Rhino 全家桶、`ServiceConnection`、`IBinder`、
  `DocumentsContract`、`delay` 等），每条都用「正文出现次数 = 0」核过
- ToolExecutor.kt 删 197 条从 MainActivity 复制的未使用 import
- `is java.io.IOException ->` 两处改 `is IOException ->`（import 因此重新被使用）
- `AgentPreset.values()` → `.entries`
- `TOOL_DEFINITIONS` / `DOC_END` → `const val`
- **修了一个潜在 bug**：`raw === ASK_USER_CANCELLED` → `==`。
  `ASK_USER_CANCELLED = String(charArrayOf('@'))` 每次新建实例，`===` 比引用几乎永不成立。

### 注意：Kotlin 编译器不报 unused import
那是 IDE inspection，`gradlew compileDebugKotlin` 出来是 **0 warning**。
想找未使用 import 只能自己写脚本按「简单名是否在正文出现」判断，
并且**必须**放行 `getValue` / `setValue` / `provideDelegate` / 运算符等隐式使用的名字。

---

## 阶段四：模拟器视觉验证（下一步）

1. 启动 AVD `Pixel_9_Pro`（`emulator.exe` 在 `/c/Users/20732/AppData/Local/Android/Sdk/emulator/`）
2. 装 debug APK（`adb` 在 `platform-tools`，**不在 PATH**，用绝对路径）
3. 走一遍：选文档 → 发送 → 看「📄 已读取文档」卡片 → 看工具卡片
4. 截图存 `test-screenshots/`
5. 顺带核验 release APK 体积（debug 包 12 MB 含调试符号，**不作为 3MB 判据**）

---

## 环境备忘

- 项目根：`C:\Users\20732\AndroidStudioProjects\MyChat`
- `adb`：`/c/Users/20732/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- AVD：`Pixel_9_Pro`
- `minSdk = 24` → **不可用 `java.time`**，日期一律 `SimpleDateFormat`
- Kotlin 2.0.21
- 命令一律加 `--offline`
- 单测统计：
  `cd app/build/test-results/testDebugUnitTest && awk -F'"' '/<testsuite /{for(i=1;i<=NF;i++){if($(i)~/tests=$/)t+=$(i+1); if($(i)~/failures=$/)f+=$(i+1); if($(i)~/errors=$/)e+=$(i+1)}} END{print "tests="t" failures="f" errors="e}' *.xml`
