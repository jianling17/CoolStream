import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
    }
}

dependencies {

    // OkHttp：流式请求 DeepSeek API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

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
