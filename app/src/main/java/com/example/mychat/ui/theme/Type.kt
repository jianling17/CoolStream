package com.example.mychat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

// ============================================================
// ★ v1.6.1 DeepSeek 规范：全局正文字体统一 SansSerif（系统无衬线/Roboto）。
//
// 原设计是全篇等宽（极客风）；对标 DeepSeek 后改为：
//   - 正文、标题、输入框、侧栏 → SansSerif（Typography 默认档整体替换）
//   - 代码块、行内代码、工具卡片标题、算式 → 在使用点显式 FontFamily.Monospace（保留等宽）
// 之前每个 Text 上单独写的 fontFamily = FontFamily.Monospace 只保留在
// 代码相关组件上，聊天主区域与系统组件不再覆盖。
// ============================================================
private val Sans = FontFamily.SansSerif

private fun TextStyle.sans(): TextStyle = copy(fontFamily = Sans)

val Typography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.sans(),
        displayMedium = base.displayMedium.sans(),
        displaySmall = base.displaySmall.sans(),
        headlineLarge = base.headlineLarge.sans(),
        headlineMedium = base.headlineMedium.sans(),
        headlineSmall = base.headlineSmall.sans(),
        titleLarge = base.titleLarge.sans(),
        titleMedium = base.titleMedium.sans(),
        titleSmall = base.titleSmall.sans(),
        bodyLarge = base.bodyLarge.sans(),
        bodyMedium = base.bodyMedium.sans(),
        bodySmall = base.bodySmall.sans(),
        labelLarge = base.labelLarge.sans(),
        labelMedium = base.labelMedium.sans(),
        labelSmall = base.labelSmall.sans()
    )
}
