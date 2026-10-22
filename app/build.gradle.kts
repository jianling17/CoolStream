import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)

    // ===== KSP：Room 的注解处理器 =====
    // 版本号格式是「Kotlin 版本 + KSP 版本」。
    // 上面的 kotlin = "2.0.21"，所以这里必须是 2.0.21-1.0.28。
    // 换成别的 Kotlin 版本时，这一行要跟着改，否则同步直接失败。
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

// ===== 读取 local.properties 里的 DEEPSEEK_API_KEY =====
// 注意：这里必须用 import 进来的 Properties，
// 不能写成 java.util.Properties() —— 在 Gradle Kotlin DSL 里
// "java" 会被解析成 Gradle 的 java 扩展，而不是 Java 包名。
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }
}
val deepSeekApiKey: String = localProps.getProperty("DEEPSEEK_API_KEY") ?: ""
// =====================================================

// ===== 读取 release 签名配置（同样来自 local.properties）=====
//
// ★ 为什么不把口令硬编码在这个文件里：
//   build.gradle.kts 是要进 Git 的，硬编码等于把签名口令公开。
//   放进 local.properties（已被 .gitignore 忽略）之后，
//   仓库里只有「怎么读」，没有「读到的值」。
//
// ★ 换电脑/重装时：把 .keystore/coolstream-release.jks 和 local.properties
//   里那 4 个 RELEASE_* 键一起搬过去，否则打不出可覆盖安装的包。
//
// ★ 缺配置时的处理：不让构建直接失败，而是把 signingConfig 留空 ——
//   这样 `assembleDebug`、单元测试这些日常操作在任何机器上都能跑，
//   只有真正要出 release 包时才需要密钥库。
val releaseStoreFile: String? = localProps.getProperty("RELEASE_STORE_FILE")
val releaseStorePassword: String? = localProps.getProperty("RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? = localProps.getProperty("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = localProps.getProperty("RELEASE_KEY_PASSWORD")

// 四项齐全、且密钥库文件真实存在，才算「签名可用」
val hasReleaseSigning: Boolean = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() } && rootProject.file(releaseStoreFile!!).exists()
// =====================================================

android {
    namespace = "com.example.mychat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mychat"
        minSdk = 24
        targetSdk = 36

        // ============================================================
        // ★ 自动版本号：0.yyMMddHHmm
        //
        //   2026-10-22 12:00 → versionName = "0.2610221200"
        //
        //   ⚠️ 这里必须显式指定 Locale.US。
        //      SimpleDateFormat 默认跟随系统语言，某些语言环境下
        //      "yyMMddHH" 会输出非 ASCII 数字，下面的 toInt() 会直接抛
        //      NumberFormatException，整个构建在 configure 阶段就挂掉。
        //
        //   ⚠️ versionCode 只精确到【小时】，不是分钟。
        //      versionCode 必须是 32 位正整数（上限约 21 亿）。
        //      yyMMddHHmm 是 10 位数字（2610221200 ≈ 26 亿），会溢出成负数，
        //      Android 会直接拒绝安装。截到小时是 8 位（最大 99123123），安全。
        //      代价：同一小时内多次构建，versionCode 不变 ——
        //           调试覆盖安装没问题，正式发版时一小时只能出一个包。
        // ============================================================
        val nowDate = Date()
        val versionStamp = SimpleDateFormat("yyMMddHHmm", Locale.US).format(nowDate)
        val versionCodeHour = SimpleDateFormat("yyMMddHH", Locale.US).format(nowDate).toInt()

        versionName = "0.$versionStamp"
        versionCode = versionCodeHour

        // ★★★ 发布包绝不能含 API Key ★★★
        //
        // 历史事故（HANDOVER §6.1 ⑤）：buildConfigField 会把 local.properties 里的
        // Key 编进 BuildConfig，然后被打进 classes.dex。反编译就能直接 grep 到
        // "sk-..."。那次发出去的 APK 里确实带着明文 Key。
        //
        // 现在的规则：
        //   · debug   —— 照常注入，本机开发时不用每次填 Key
        //   · release —— 永远注入空串，Key 只能由用户在 App 里自己填
        //                （存在 EncryptedSharedPreferences 里，跟 APK 无关）
        //
        // 这个默认值在这里是空串，release 的 buildType 里会再覆盖一次空串
        // （见 buildTypes.release 里的 buildConfigField），双保险。
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepSeekApiKey\"")

        // 把版本号也生成进 BuildConfig，UI 里直接读，不用再解析 PackageInfo
        buildConfigField("String", "BUILD_VERSION_NAME", "\"$versionName\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ===== 签名配置 =====
    //
    // 只有 local.properties 里四项齐全时才会真正创建这个 signingConfig。
    // 缺任何一项就不创建 —— 此时 release 包会是未签名状态（打得出，装不上），
    // 但 debug 包和单元测试完全不受影响。
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword

                // v1/v2 都开：minSdk 24 的老机器只认 v1（JAR 签名），
                // Android 7+ 优先用 v2。少一个就会出现「有的手机装不上」。
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // ===== R8 混淆 + 资源压缩 =====
            //
            // ★ 打开这两个之后，任何「靠反射/字符串找类」的代码都可能被删掉，
            //   所以 proguard-rules.pro 里的 keep 规则不是可选项，是保命的。
            //   本项目有四个高危点（见 proguard-rules.pro 的注释）：
            //     · Rhino 的 JS 引擎（反射创建解释器与作用域）
            //     · Shizuku 的 AIDL（跨进程按类名实例化 RemoteShellService）
            //     · Room 生成的 _Impl（按类名反射加载）
            //     · ShizukuProvider（Manifest 声明，系统反射实例化）
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // 有密钥库就用它签，没有就退回 debug 签名（保证能装上自己测）
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }

            // 保留行号信息：线上崩溃堆栈要能定位到源码行，
            // 配合下面的 -renamesourcefileattribute 隐藏真实文件名。
            // 体积代价很小（约 1%），排查价值极大。
            isDebuggable = false

            // ★★★ 发布包里 Key 强制为空 ★★★
            //
            // 这是第二道保险：defaultConfig 里注入的是 local.properties 的值，
            // 这里在 release 上覆盖成空串，保证打出来的 APK 里
            // BuildConfig.DEEPSEEK_API_KEY == ""。
            //
            // 用户在 App 里填的 Key 存在 EncryptedSharedPreferences，
            // 跟 APK 无关，所以清空它不影响正常使用。
            //
            // 验证方法（发版前必做）：
            //   ./gradlew assembleRelease
            //   解压 APK，在 classes*.dex 里 grep "sk-" —— 必须搜不到。
            buildConfigField("String", "DEEPSEEK_API_KEY", "\"\"")
        }
    }

    // 单元测试跑在电脑的 JVM 上，不碰真机。
    // 我们的工具函数里有 Log.d / Environment.isExternalStorageManager() 这类
    // Android 框架调用，JVM 上没有实现会直接抛 "not mocked"，
    // 打开 returnDefaultValues 让它们返回 null / false 而不是崩掉。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // Shizuku 的提权执行要走 UserService，必须用 AIDL 定义跨进程接口
        aidl = true
    }
}

dependencies {

    // OkHttp：流式请求 DeepSeek API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ===== Rhino：PTC 预设的 JavaScript 运行时 =====
    // PTC = Programmatic Tool Calling，模型写一段 JS 在代码里连续调工具。
    // Android 出厂不带 JS 引擎，Rhino 是唯一的纯 JVM 选择（没有原生库、不用 WebView）。
    // 用 faendir 的 rhino-android 而不是 org.mozilla:rhino：
    //   前者为 Android 重新打包过，规避了老机型上 java.lang.invoke 的坑（minSdk=24）。
    // ⚠️ 代码里必须 optimizationLevel = -1（解释执行），Dalvik 不能吃它生成的字节码。
    implementation("com.faendir.rhino:rhino-android:1.6.0")

    // 加密存储：API Key 不能明文躺在 SharedPreferences 里。
    // 底层是 Android Keystore 里的一把 AES256 主密钥，文件被拷走也解不开。
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ===== Room：本地数据库 =====
    // room-runtime 是运行时库，room-ktx 提供协程支持（suspend / Flow），
    // room-compiler 是编译器，用 ksp(...) 而不是 implementation(...)，
    // 因为它只在编译期工作，不会打进 APK。
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ===== Shizuku：提权执行 shell 命令（run_shell 工具用）=====
    // api       → Shizuku.newProcess() / requestPermission() / pingBinder()
    // provider  → 提供 ShizukuProvider，让 Shizuku App 能和我们的 App 通信
    //              （Manifest 里必须声明对应的 <provider>，否则授权拿不到）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // ViewModel：让 sessions / currentSessionId 这类状态在旋转屏幕后存活，
    // 同时用 viewModelScope 跑流式请求（rememberCoroutineScope 会在重建时被 cancel）
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // HorizontalPager（首次启动引导的分页容器）在 foundation 里。
    // material3 会传递带入 foundation，但 Pager 是 foundation 自己的 API，
    // 显式声明一份可以避免「哪天 material3 改了传递依赖」时突然编译不过。
    implementation("androidx.compose.foundation:foundation")
    testImplementation(libs.junit)
    // ★ 单元测试必须用真的 org.json。
    //   测试跑在 JVM 上，classpath 里那份 org.json 来自 android.jar 的桩实现，
    //   配合 returnDefaultValues=true，连 JSONObject().put() 都返回 null ——
    //   于是所有链式 put 全是 NPE，测出来的失败跟代码对不对没关系。
    //   加上真实现之后测试类路径会优先用这个 jar（Android 运行时不受影响）。
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
