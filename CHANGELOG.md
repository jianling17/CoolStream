# Changelog

本文件记录 CoolStream 的版本变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

> ⚠️ **尚未发布任何正式版本。** GitHub 上没有 Release，本地也没有打 tag。
> 桌面上的 `CoolStream-0.9.0-preview.apk` 是开发预览包，不是 1.0。

---

## [Unreleased]

### 已实现
- **国际化（i18n）基础设施**：`res/values/strings.xml`（中文）+ `res/values-en/strings.xml`（英文），
  默认跟随系统语言；设置 → 通用 → 语言 可强制 中文 / English。
  实现方式是用 `createConfigurationContext` 换掉 `LocalContext`，没有引入 appcompat。

### 尚未完成（i18n 覆盖度约 65%）
- 已迁移：引导页全部文案、设置页外壳与「通用」页、模型与 API 页的字段标签、
  聊天页空状态/输入框/思考中、工具卡片标题、长按复制菜单、错误气泡标题
- **仍是中文硬编码**：侧边栏分组、插件页说明、关于页正文、模型页「运行时/计费口径」正文、
  以及全部网络/HTTP 错误文案（它们在非 Composable 的协程里，需要改成传 Context 才能取资源）

---

## 计划中的 1.0.0（尚未发布）

目标：一个完全本地运行的 Android 原生 DeepSeek 客户端，核心目标是**不发烫**。
下面列的是**已经做完**的部分；距离 1.0 还差什么，见本节末尾「发布前还差什么」。

### 新增

**首次使用体验**
- 全屏引导页：三步完成配置（API Key / 文件权限 / Shizuku），状态实时刷新
- API Key 改用 `EncryptedSharedPreferences` 加密存储（Android Keystore 派生密钥），不再明文落盘
- 设置页「模型与 API」可直接编辑 API 地址、Key、模型名，支持自定义第三方兼容接口
- 引导完成后自动创建默认工作区 `/sdcard/Documents/CoolStream_Workspace/`

**异常兜底**
- 断网、超时、SSL 拦截分别给出可照做的中文提示，不再只显示 `HTTP 401` 这种原始错误
- 401 / 402 / 403 / 404 / 429 / 5xx 各有专门文案（Key 无效、余额不足、请求太频繁……）
- 错误以**暗红毛玻璃气泡 + 警告图标**呈现，并标记 `isError`，不会被当成 AI 回复再次发给模型

**交互**
- 长按消息气泡弹出毛玻璃菜单：复制全文 / 复制代码（自动抽取 ``` 围栏内容）
- 消息列表自动跟随新消息与流式输出；用户上翻历史时不抢滚动
- 设置页重构为四个 Tab：通用 / 模型与 API / 插件 / 关于
- 主题（跟随系统 / 浅色 / 深色）、字号（小 / 标准 / 大）、卡片默认展开
- 六个工具逐个开关，关掉的工具不会出现在请求里，也不会写进系统提示词
- `replace_in_file` 成功后在卡片内渲染红绿 Diff（行级 LCS）

### 已有能力（1.0 前的积累）

- **纯原生 Compose UI**，无 WebView；50ms 流式批处理，UI 重绘降低 93.6%
- **完整 Agent Loop**：最多 8 轮多步工具调用（搜索 → 读取 → 修改 → 复查）
- **六个本地工具**：`list_dir` / `search_files` / `read_file` / `write_file` / `replace_in_file` / `run_shell`
- **Shizuku 提权**：AIDL `UserService`，无需 Root；危险命令正则黑名单
- **工作区隔离**：`canonicalPath` 前缀校验，三层拦截越界访问
- **Room v3 持久化**：会话 + 消息 + 工具卡片，旋转不丢状态（ViewModel）
- **真实 Token 计费**：读取 API `usage`，按 DeepSeek 峰谷价估算

### 已知限制

- API Key 存在客户端，仅靠加密存储保护；没有服务端代理，**这个 APK 只适合自己用**
- 工作区限制只对 `java.io.File` 系工具有效；`run_shell` 是 shell 权限，代码层拦不住
- 温度改善只在"UI 重绘"这一项上有量化数据，没有做控制变量后的整机温度实测
- 界面尚未国际化

---

### 发布前还差什么

- [ ] i18n 剩余约 35% 文案迁移（侧边栏分组、插件页说明、关于页正文、全部错误文案）
- [ ] 你提到「还没做出来的功能」——待补充清单后一起做
- [ ] 代码推送到 GitHub（当前 2 个提交卡在本地，代理 502）
- [ ] 打 tag、发 Release、附上签名后的 APK

---

## 更早的版本

1.0 之前没有打 tag，提交历史见 `git log`，踩坑记录见 [DEVLOG.md](DEVLOG.md)。
