# docs/ —— 截图放这里

README 首页引用了 4 张图片。把截图按下面的**文件名**放到这个目录，就会自动显示。

---

## 一、需要哪 4 张图

| 文件名 | 内容 | 用什么工具截 |
|---|---|---|
| `screenshot-chat.png` | 聊天界面（有 2~3 轮对话，左右气泡清晰） | **手机自带截屏** |
| `screenshot-streaming.png` | 正在流式输出的瞬间（AI 气泡文字还没吐完） | **手机自带截屏** |
| `screenshot-log-control.png` | **对照组** Logcat，要能看到 `deltas=643, flushes=643` | **电脑截图**（Win+Shift+S） |
| `screenshot-log-experiment.png` | **实验组** Logcat，要能看到 `deltas=911, flushes=58` | **电脑截图**（Win+Shift+S） |

---

## 二、手机截图怎么截（前两张）

### 小米手机（你的机型）

| 方式 | 操作 |
|---|---|
| **按键**（推荐） | 同时按 **电源键 + 音量减键**，听到"咔嚓"就成功了 |
| 三指下滑 | 设置 → 更多设置 → 快捷手势 → 截屏 → 打开"三指下拉" |
| 小爱同学 | 说"小爱同学，截屏" |

### 截完的图在哪

- 手机上：`相册` → `截屏` 相册
- 传到电脑的两种办法：
  1. **微信/QQ 文件传输助手**（最简单）
  2. **数据线**：插上电脑 → 手机弹窗选「传输文件」→ 电脑打开 `此电脑\你的手机\内部存储\DCIM\Screenshots\`

### 截图小技巧

- **`screenshot-streaming.png` 要多试几次**。流式输出只有几秒钟，建议：发一条长消息（800 字以上）→ 看到第一个字出现就立刻截屏
- 截图前先把**深色模式关掉**（或者打开，总之两张保持一致），看起来更专业
- 手机截图通常很长（全面屏比例），**不用裁剪**，GitHub 会自动缩放

---

## 三、Logcat 截图怎么截（后两张）—— 最重要

这两张是**整个 README 最有说服力的证据**，一定要截好。

### 步骤

**1. 打开 Logcat**

Android Studio 菜单栏 → `View` → `Tool Windows` → `Logcat`（快捷键 **`Alt + 6`**）

**2. 设置过滤条件**

在 Logcat 顶部的查询框里输入：

```
tag:MyChatPerf & package:mine
```

**3. 把级别调成 `Verbose`**

查询框右边有个级别下拉框，默认可能是 `Debug`。**必须选 `Verbose` 或 `Debug`** ——
如果选了 `Info` / `Warn` / `Error`，`Log.d` 写出来的日志会被全部过滤掉，你会看到一片空白。

**4. 清空旧日志**

点 Logcat 工具栏上的 🚫 图标（`Clear Logcat`），或者点进 Logcat 面板后按 `Ctrl + L`。

**5. 跑测试**

- **对照组**：先把 `MainActivity.kt` 里的 `FLUSH_INTERVAL_MS` 改成 `0L` → `Ctrl+S` → 点 `Run`
- 在手机上发一条**长消息**（建议直接复制这句）：

  > 请详细介绍人体血液循环系统的组成、体循环和肺循环的路径、以及心脏四个腔室各自的功能，要求分点说明，总字数不少于 800 字。

- 等 AI 回复完（气泡不再变化）

**6. 在 Logcat 里找到关键日志**

滚到最下面，找最后一行 `stream done: ...`。它上面紧挨着的是几条 `flush: ...`。

**7. 框选截图**

按 **`Win + Shift + S`** → 选「矩形截图」→ **框选范围要包含**：

```
D/MyChatPerf: response code=200
D/MyChatPerf: flush: 8 chars, interval=0ms
D/MyChatPerf: flush: 412 chars, interval=51ms
D/MyChatPerf: flush: 890 chars, interval=50ms
D/MyChatPerf: flush: 1034 chars, interval=52ms
D/MyChatPerf: stream done: total=1034 chars (deltas=643, flushes=643, skipped=2)
```

> 💡 **一定要把 `interval` 那几行也框进去**（形如 `interval=51ms`）。
> 这能直接证明「50ms 批处理确实按 50ms 在刷」，比只有 `stream done` 一行有力得多。

**8. 保存**

截图工具会自动复制到剪贴板，也可以点「保存」存成文件。**存成上表对应的文件名**：

- 对照组 → `screenshot-log-control.png`
- 实验组 → `screenshot-log-experiment.png`

**9. 换成实验组再截一次**

把 `FLUSH_INTERVAL_MS` 改回 `50L` → `Ctrl+S` → `Run` → 重复上面第 4~8 步。

> ⚠️ **测完一定要改回 `50L`！** 不然你留在手机上的就是「没批处理」的版本。

---

## 四、截图前的准备（让图好看一点）

| 项目 | 建议 |
|---|---|
| Logcat 字号 | `Settings` → `Editor` → `Color Scheme` → `Console Font` → 调到 **16~18**，不然截图后根本看不清 |
| Logcat 格式 | 点工具栏的 `Configure Logcat Formatting Options` → 选 `Compact`，去掉多余列，一屏能放下更多 |
| 深色模式 | 两张手机截图保持一致（都开或都关） |
| 消息内容 | 用医学/学习类的正常问题，别用「test」「123」这种 |
| 窗口宽度 | 建议先把 Android Studio 窗口拉宽，避免日志被折行 |

---

## 五、图片规格要求

- **格式**：`.png`（`.jpg` 也行，但要同步改 README 里的后缀）
- **宽度**：控制在 **1200px 以内**。太大的图会让 README 加载很慢
- **命名**：**全小写**，用连字符 `-` 连接，**不要用空格或中文**
- **文件名必须和 README 里写的完全一致**，否则图片会显示成裂开的图标

### 检查图片大小

如果单张超过 1 MB，可以压缩一下：

- 在线：搜「TinyPNG」，拖进去就压好了
- Windows 自带：「画图」打开 → `重新调整大小` → 按百分比缩小 → 另存为 PNG

---

## 六、暂时不想放图？

把 README 里「2. 界面」那一节整段删掉即可，不影响其他内容。

或者只放两张 Logcat 截图 —— **那两张才是重点**，手机界面截图相对次要。
