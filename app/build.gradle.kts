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

android {
    namespace = "com.example.mychat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mychat"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 把 local.properties 里的 Key 生成成 BuildConfig.DEEPSEEK_API_KEY
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepSeekApiKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
