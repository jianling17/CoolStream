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
| 四 | 模拟器 Pixel_9_Pro 视觉验证 + 截图存 `test-screenshots/` | ✅ 完成（含 1 个真 bug 修复，269 条全绿） |

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

## 阶段四 ✅（commit c11ff15，视觉验证 + 修掉一个真 bug）

### 发现并修复的真 bug：文档正文从来没到过模型
现象：选中 `.docx` 发送后，气泡卡片「📄 已读取文档」正常，但模型回
「I don't have any context for that question」。

根因：`contentForApi()` 用 `stripDocBlocks()` 构造发给模型的内容，而 `stripDocBlocks`
是**给界面用的**——它把 `<<DOC:begin|…>>` 到 `<<DOC:end>>` 整段（含正文）全删。
于是 Prompt 里只剩「问题：xxx」，一万字正文一句没发出去。

修法：新增 `internal fun unwrapDocBlocks(raw: String)`，只删两行哨兵标记、正文原样保留；
`contentForApi` 改用它，`displayContent`（界面）继续用 `stripDocBlocks`。
两个函数的分工用一条单测锁死：`unwrap_contrastWithStrip_stripDropsBody`。

### 验证结果（Pixel_9_Pro / API 37，截图在 `test-screenshots/`）
| 项 | 结果 |
|---|---|
| 主界面 / 附件面板 / 📄 Upload document 入口 | ✅ 渲染正常（06/08-sheet.png） |
| SAF 文件选择器拉起 | ✅ `documentsui/.picker.PickActivity`（09/24-picker3.png） |
| docx 解析 → chip → 气泡卡片 → Prompt → 回答 | ✅ 答出「建议下季度加大华东区域的渠道投入」并复述 520/310/450/1280 万（19-doc-answer.png） |
| xlsx 解析 → 同上 | ✅ 答出「表格里华东的销售额是 520」（26-xlsx-answer.png） |
| 残缺 xlsx（缺 sheet1.xml） | ✅ 不崩溃，弹「识别为 Excel 工作簿，但没能从中提取到文字内容（工作表内容（xl/worksheets/sheet1.xml））」 |
| release APK 体积 | ✅ **2,080,742 字节 ≈ 1.98 MB**（≤ 3MB） |
| 50ms 防抖 `FLUSH_INTERVAL_MS = 50L` | ✅ 未改动 |

### 模拟器操作备忘（踩过的坑）
- 无窗口软件渲染要加 `-no-window -gpu swiftshader_indirect -no-snapshot-save`；
  SystemUI 会反复 ANR 弹「System UI isn't responding」，用
  `adb shell settings put global hide_error_dialogs 1` 一次性压掉。
- 存储权限：`adb shell appops set com.example.mychat MANAGE_EXTERNAL_STORAGE allow`，
  否则 logcat 报「没有存储权限，跳过默认工作区创建」。
- **MSYS 会把 `/sdcard/...` 当成 Windows 路径改写**，`adb push` 必须写成 `//sdcard/...`，
  否则报 `remote secure_mkdirs() failed`。
- 给 Windows 版 python 传路径不要用 `/c/Users/...`，会被解释成 `C:\c\Users\...`，
  文件悄悄写到别处；一律用 `r"C:\\Users\\..."`。
- SAF 列表有缓存：推完文件要退出选择器重进（下拉刷新没用）。
- 底部面板动画未结束时点击会**穿透到输入框**（把键盘/表情面板弹出来）；
  每次点完先等 3~5s 再截图确认坐标。
- 坐标换算：截图 1280x2856，预览 717x1600 时缩放比 ≈ 1.785。

### 已知设计取舍（不是 bug，未改）
`AttachmentSheet` 用 `glassFill()`（`Color.kt:50` `GlassFillDark = 0xFF1C1C1E, alpha 0.80`），
这是刻意的玻璃拟态——注释明确写了「不用 `Modifier.blur()`，半透明底 + 高光边框，开销几乎为零」。
副作用是模态面板压在长聊天文本上时字压字、可读性差。要改只动 `AttachmentSheet` 的
`containerColor`（换成不透明 `surface` 或把 alpha 提到 0.95），**不要动全局 `glassFill`**。

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

---

## 装包约定（用户 m01478 指令，长期有效）

**给用户手机装新版 = 直接取现成的 release 包装，不要重打包、不要装 debug 包。**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # 见下方注意事项
ADB=/c/Users/20732/AppData/Local/Android/Sdk/platform-tools/adb.exe
cd /c/Users/20732/AndroidStudioProjects/MyChat
$ADB install -r app/build/outputs/apk/release/app-release.apk
$ADB shell dumpsys package com.example.mychat | grep -E "versionName|versionCode"
$ADB shell am start -n com.example.mychat/.MainActivity
```

- APK 路径固定：`C:\Users\20732\AndroidStudioProjects\MyChat\app\build\outputs\apk\release\app-release.apk`
- 手机设备 id `a2c1bf9c`（24117RK2CC / zorn，Android 16 / SDK 36），覆盖安装保留数据
- 装完必须核 `versionName` 并截图确认不崩（R8 混淆包有运行时崩溃风险）

### 踩过的三个坑（别重复踩）
1. **判签名不要比 `META-INF/CERT.RSA` 的字节哈希**：它是 PKCS#7 容器，签名属性不同字节就不同，
   但证书可以完全一样。一律用 `apksigner.bat verify --print-certs` 看证书 DN / SHA-256。
   （曾误判「签名不匹配」，实际两包都是 `CN=CoolStream`、SHA-256 `d9ea8e3d…`。）
2. **win_bash 命令超时被杀后，持久 shell 会重置，`JAVA_HOME` 丢失**，gradle 报
   `Please set the JAVA_HOME variable`。每次先 export，或直接用 Android Studio 的 jbr。
3. **MSYS 的 `/tmp` 和 Windows python 看到的 `/tmp` 不是同一个目录**：`adb pull /tmp/x` 与
   python 读 `/tmp/x` 会错位（症状：adb 说 pull 成功，python 说文件不存在）。
   临时文件放项目内 `.tmpcmp/`，adb 和 python 都传 Windows 路径，用完 `rm -rf`。

### 发版前记得改版本号
`app/build.gradle.kts:91` 的 `val appBaseVersion = "..."`——这是唯一需要手改的版本行，
`versionCode` 由 `yyMMddHH` 自动生成（同一小时多次构建 versionCode 不变）。

---

## v1.1.1 补丁（两处 UI 修复）

### 1. 底部输入栏垂直不对齐（真问题，已修）
根因：`MainActivity.kt` 的 `InputBar` 胶囊 `Row` 用的是 `verticalAlignment = Alignment.Bottom`。
三个子项高度不同 —— `+` 号 `IconButton` 40dp、发送按钮 36dp、`BasicTextField` 自带 6dp 上下内边距，
底部对齐后三者中心线错开，视觉上就是「文字偏上、按钮偏下」。
修法：改成 `Alignment.CenterVertically`（现约在 7468 行）。

### 2. AttachmentSheet 玻璃底透字（已修）
**没有动全局 `glassFill()`**（输入框 / 顶栏按钮继续用它）。新增一套局部色：
- `ui/theme/Color.kt` 新增 `GlassSheetDark = 0xFF1C1C1E alpha 0.97` / `GlassSheetLight = 0xFFFFFFFF alpha 0.98`
  （紧挨 `GlassDrawer*`，后者 0.95/0.97 就是为侧边栏透字加的，同一思路）
- `MainActivity.kt` 新增 `@Composable private fun glassSheet()`，与 `glassFill()` / `glassDrawer()` 并列
- `AttachmentSheet` 的 `containerColor` 由 `glassFill()` 换成 `glassSheet()`

### 验证状态
- `compileDebugKotlin` ✅ / `assembleRelease` ✅ / `testDebugUnitTest` **269 条 0 失败**
- ⚠️ **手机（a2c1bf9c）在装包前从 USB 掉线**，`adb devices` 为空，
  所以这一版**尚未在真机上截图确认视觉效果**。手机接回后按「装包约定」直接 `adb install -r` 即可。
---

## v1.1.2 视差叠搭侧边栏（用户 m01539 下达）

### 为什么自己写，不用 ModalNavigationDrawer
1. 它的遮罩颜色、抽屉宽度、主界面变换全都不暴露，做不出「主界面右移 + 等比缩放 + 圆角 + 阴影」的叠搭观感；
2. 滑动动画是 Material 内部实现，无法指定 spring(dampingRatio, stiffness)。

### 落点（全部在 `MainActivity.kt`）
- `ParallaxDrawerState`：`progress: Float`（0..1，mutableFloatStateOf）、`scrimActive: Boolean`、
  `suspend open()/close()`（`animate(spring(dampingRatio = 0.8f, stiffness = 400f))`）、`dragTo()`、`suspend settle(velocity)`
- `rememberParallaxDrawerState()`
- `ParallaxNavigationDrawer(drawerState, drawerWidthFraction, drawerContent, content)` —— 与 `ModalNavigationDrawer` 同形，
  所以调用点只改了名字、删了 `scrimColor`
- `ParallaxScrim(drawerState, onClose)`
- 常量：`DRAWER_WIDTH_FRACTION = 0.78f`、`DRAWER_SLIDE_EXTRA = 1.35f`、`DRAWER_CONTENT_MIN_SCALE = 0.92f`、
  `DRAWER_SCRIM_MAX_ALPHA = 0.30f`、`DRAWER_EDGE_FRACTION = 0.16f`、`DRAWER_CORNER = 22.dp`、`DRAWER_SHADOW = 24.dp`

### 性能：为什么不会影响 50ms 流式批处理
所有随开合比例变化的量（translationX / scale / 圆角 / 阴影 / 遮罩透明度）都只在
`graphicsLayer { }` 或 `drawBehind { }` 里读 `progress` —— 这是绘制阶段读状态，
只让图层失效、不触发重组。唯一会被 composition 读的是 `scrimActive`，而它只在 0/1 边界翻转，
一个手势最多两次重组。`FLUSH_INTERVAL_MS = 50L` 全程未动。

### 踩到的两个坑（都写进代码注释了）
1. **遮罩必须用 drawBehind，不能 background + graphicsLayer{alpha}**
   `Modifier.fillMaxSize().background(Color.Black).graphicsLayer { alpha = 0.3f * p }`
   里 background 排在 graphicsLayer 左边 = 画在图层外面，alpha 根本不生效，遮罩变成纯黑不透明，
   把右移后的主内容整块盖死（现象：抽屉打开后右侧全黑，只剩状态栏图标）。
2. **容器必须先铺一层主题底色**
   主内容缩到 0.92 后上下会露出两条边，没有底色就透出窗口的白色 windowBackground。
   现在 `BoxWithConstraints` 上挂了 `.background(MaterialTheme.colorScheme.background)`。
3. 附带：`AwaitPointerEventScope` 是 `@RestrictsSuspension` 的，手势循环里不能直接调
   `drawerState.settle()`，要用外部 `rememberCoroutineScope().launch`。

### 手势（实测通过，Pixel_9_Pro / API 37）
| 动作 | 期望 | 结果 |
| --- | --- | --- |
| 点遮罩 | 关闭 | ✅ |
| 从 x=120 左缘滑出 | 打开 | ✅ |
| 右滑 | 关闭 | ✅ |
| 屏幕中部横滑（关闭态） | 不误触 | ✅ |
| 聊天区纵向滑动 | 正常滚动、抽屉不动 | ✅ |

实现要点：外层 `Box` 挂 `pointerInput` + `awaitEachGesture` + `PointerEventPass.Initial`，
位移超过 `viewConfiguration.touchSlop` 后做轴向锁（`abs(accX) >= abs(accY)` 才算横向），
只有横向才 `consume()`，纵向直接撒手交还聊天列表；关闭态只有起手点落在左缘 16% 内才接管。
松手用 `VelocityTracker` 取速度：> 900px/s 判开、< -900px/s 判关，否则按 50% 就近吸附。

### 验证结果
- `compileDebugKotlin` ✅ 0 warning / `testDebugUnitTest` **269 条 0 失败** / `assembleRelease` ✅
- release APK **2,071,446 字节 ≈ 1.98 MB**（≤ 3MB）
- `FLUSH_INTERVAL_MS = 50L`（`MainActivity.kt:453`）未动
- 截图：`test-screenshots/p11-open.png`（打开态，视差叠搭）、`p01-main.png`（关闭态）
- 注意：模拟器测试时把 `navigation_mode` 临时改成 0（三键）避开系统返回手势抢触摸，测完已改回 2

### 一个已知取舍
手势导航下，屏幕最左约 20dp 是系统返回手势区，起手点落在那里时抽屉抢不到触摸。
这是 Android 的系统级约束（Material 官方抽屉同样如此），不是 bug；从边缘内约 60px 起手即可。

