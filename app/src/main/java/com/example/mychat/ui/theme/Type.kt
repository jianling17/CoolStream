package com.example.mychat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

// ============================================================
// 全篇等宽字体
//
// 如果在每个 Text 上单独写 fontFamily = FontFamily.Monospace，
// 侧边栏、对话框、按钮这些地方极容易漏掉（漏了就是"一半等宽一半不等宽"）。
// 所以这里直接把 Material 的 15 个字号档全部换成等宽字体：
// 以后写 Text 不用再管字体，默认就是等宽。
//
// 字号 / 行高 / 字重全部沿用 Material 默认值，只替换 fontFamily，
// 所以视觉大小和原来一致，不会突然变大变小。
// ============================================================
private val Mono = FontFamily.Monospace

private fun TextStyle.mono(): TextStyle = copy(fontFamily = Mono)

val Typography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.mono(),
        displayMedium = base.displayMedium.mono(),
        displaySmall = base.displaySmall.mono(),
        headlineLarge = base.headlineLarge.mono(),
        headlineMedium = base.headlineMedium.mono(),
        headlineSmall = base.headlineSmall.mono(),
        titleLarge = base.titleLarge.mono(),
        titleMedium = base.titleMedium.mono(),
        titleSmall = base.titleSmall.mono(),
        bodyLarge = base.bodyLarge.mono(),
        bodyMedium = base.bodyMedium.mono(),
        bodySmall = base.bodySmall.mono(),
        labelLarge = base.labelLarge.mono(),
        labelMedium = base.labelMedium.mono(),
        labelSmall = base.labelSmall.mono()
    )
}
