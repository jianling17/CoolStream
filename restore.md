# 换电脑怎么还原 CoolStream

这份文档写给「拿到桌面上的 `CoolStream-backup-*.zip`，在一台全新的电脑上想重新跑起来」的场景。

只需要补 **一个文件**（`local.properties`）和 **一个设置**（Gradle JDK），其余都在压缩包里。

---

## 0. 包里有什么

```
MyChat/
├── .git/                 ← 完整提交历史（含所有本地提交）
├── patches/              ← 每次提交一个 .patch（.git 丢了时的备份方案）
├── app/                  ← 源码
├── gradle/               ← 版本目录 + Gradle Wrapper
├── .idea/                ← 含 Gradle JDK 配置，恢复后少踩一次坑
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties.example   ← 需要照着它新建 local.properties
├── restore.md            ← 本文件
├── README.md
└── DEVLOG.md
```

**故意没有打进包里的两样东西：**

| 排除项 | 原因 | 怎么办 |
|---|---|---|
| `local.properties` | 里面有 DeepSeek API Key 明文，打进包就等于泄露 | 按第 2 步自己新建 |
| `build/`、`.gradle/`、`.kotlin/` | 构建产物和缓存，几十 MB 且和机器绑定 | Gradle 会自己重新生成 |

---

## 1. 前置条件

| 需要 | 说明 |
|---|---|
| Android Studio | 任意较新版本。**不要单独装 JDK**，用 AS 自带的就行 |
| Android SDK | AS 首次启动会引导安装；确保装了 **Android 16 (API 36)** 的 Platform |
| 网络 | 依赖走阿里云镜像，国内可直连 |

可选但建议：真机开着 USB 调试，或者建一个模拟器。

---

## 2. 新建 `local.properties`（必须）

解压后在项目根目录新建文件 `local.properties`：

```properties
# 你的 Android SDK 路径
# Windows 例：C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
# macOS   例：/Users/你的用户名/Library/Android/sdk
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk

# DeepSeek API Key，从 platform.deepseek.com 申请
DEEPSEEK_API_KEY=sk-你的key
```

⚠️ **Windows 路径里的反斜杠要写成双反斜杠**（`\\`），否则会被当成转义字符。

⚠️ 这个文件**不要提交到 Git**（`.gitignore` 已经排除了），也不要发给别人。

懒得手写的话，复制现成的模板再改：

```bash
cp local.properties.example local.properties
```

---

## 3. 设置 Gradle JDK（最容易卡住的一步）

这一步不做，Sync 会直接失败。

**Android Studio → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**

选 **jbr-21**（Android Studio 自带的那一栏，通常叫 `jbr-21` 或 `Embedded JDK 21`）。

### 如果下拉里没有 21

点 `Download JDK`：

- **Vendor** 选 **Azul Zulu** 或 **Amazon Corretto**
- **Version** 选 **21**

> ⚠️ **不要选 Temurin / GraalVM / SapMachine**。它们的下载源在 GitHub，
> 国内经常卡死或直接失败（本项目就踩过：下了一半失败，`.idea` 里留下一个指向
> 不存在 JDK 的悬空引用，之后每次 Sync 都报错）。

### 为什么不能用默认的 JDK 25

AS 自带 JBR 已经升到 **JDK 25**，而本项目用的 **Gradle 8.13 只支持到 Java 23**。
用 25 跑会报：

```
Unsupported class file major version 69
```

（69 = Java 25 的 class 版本号）

---

## 4. 同步与构建

AS 会自动开始 Sync；也可以手动：**File → Sync Project with Gradle Files**。

命令行构建：

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 命令行构建报 JDK 错误怎么办

AS 里的设置对命令行**不生效**（AS 通过 Tooling API 把自己的 JDK 传给 Gradle）。
命令行构建如果报 JDK 版本不对，在项目根目录的 `gradle.properties` 里加一行：

```properties
org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
```

（路径换成你机器上的实际位置；macOS 一般是
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`）

---

## 5. 提交历史没了怎么办

正常情况下 `.git/` 是完整的，解压后直接 `git log` 就能看到全部历史，什么都不用做。

**只有在 `.git/` 损坏或缺失时**才需要打补丁：

```bash
cd MyChat
git init
git config user.name "jianling17"
git config user.email "178297900+jianling17@users.noreply.github.com"

# 按顺序打上所有补丁（patches/ 里是 0001~00NN）
git am patches/*.patch
```

> 补丁是用 `git format-patch` 生成的，保留了原作者、时间和提交信息。
> 打完 `git log` 应该和原来的历史一致。

如果 `git am` 中途卡住（冲突），用 `git am --abort` 全部回滚，再检查补丁顺序。

---

## 6. 恢复后的自检清单

按顺序过一遍，任何一条不对都说明环境还没好：

| 检查项 | 怎么验 |
|---|---|
| Gradle 能同步 | AS 右下角没有红色报错，Build 面板显示 `BUILD SUCCESSFUL` |
| 单元测试能跑 | `gradlew testDebugUnitTest` → 16 个用例全过（0.6 秒，不需要真机） |
| 能装到设备 | `adb install -r app-debug.apk` 成功，桌面出现 CoolStream |
| Key 生效 | 打开 App 发一句话，能收到流式回复（收不到就是 `local.properties` 的 Key 没配对） |
| 文件权限 | 设置 → 通用 → 权限 → 「文件权限」显示**已开启**（没开就去系统设置里给「所有文件访问权限」） |
| 工作区 | 设置 → 通用 → 工作区 → 选一个目录（不选则不限制路径） |

---

## 7. 已知的坑（都会真实遇到）

| 现象 | 原因 | 解法 |
|---|---|---|
| `Unsupported class file major version 69` | Gradle JDK 是 25 | 换成 JDK 21（第 3 步） |
| Sync 报 `MyChatTheme` 找不到，但代码明明有 | AS 和命令行同时构建，增量缓存冲突 | `gradlew clean` 后再试一次 |
| 依赖下载卡住 | 走了官方源 | 项目已配阿里云镜像，检查 `settings.gradle.kts` 里的 `repositories` |
| `git push` 报 `CONNECT tunnel failed, response 502` | 代理不支持 push（`gh-proxy` 这类加速站只读） | 见下 |

### 关于 push

本机曾经配过 `url.<proxy>.insteadOf https://github.com/` 这类加速规则。
**它只适合 clone，不适合 push** —— 加速站是只读的，push 必然 502。

检查有没有残留：

```bash
git config --global --get-regexp "url\..*\.insteadof"
```

有的话删掉：

```bash
git config --global --unset url."https://gh-proxy.com/https://github.com/".insteadOf
```

然后在能正常访问 GitHub 的网络下：

```bash
git push origin main
```

---

## 8. 一句话总结

> 解压 → 建 `local.properties`（SDK 路径 + API Key）→ Gradle JDK 选 21 → Sync → 跑。

`.git` 完整的话，历史、分支、提交信息全都在，不需要任何补丁操作。
