package com.example.mychat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ============================================================
// 暗色方案：把 Color.kt 里的颜色装进 Material 3 的槽位
// 槽位 → 用途的对应关系（改颜色只需要动 Color.kt）：
//   primary          → 用户气泡底色
//   onPrimary        → 用户气泡文字
//   background       → 页面背景
//   onBackground     → 正文
//   surface          → AI 气泡底色
//   onSurface        → AI 气泡文字
//   surfaceVariant   → 顶栏等次级面板
//   surfaceContainerHighest → 输入框底色
//   onSurfaceVariant → 次要文字 / 占位符
//   outline          → AI 气泡边框 + 输入框边框
//   error            → 错误气泡
// ============================================================
private val DarkColorScheme = darkColorScheme(
    primary = DkUserBubble,
    onPrimary = DkOnUserBubble,
    background = DkBackground,
    onBackground = DkText,
    surface = DkSurface,
    onSurface = DkText,
    surfaceVariant = DkSurfaceVar,
    surfaceContainerHighest = DkInputBackground,
    onSurfaceVariant = DkTextDim,
    outline = DkBorder,
    outlineVariant = DkBorder,
    error = DkError,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = LtUserBubble,
    onPrimary = LtOnUserBubble,
    background = LtBackground,
    onBackground = LtText,
    surface = LtSurface,
    onSurface = LtText,
    surfaceVariant = LtSurfaceVar,
    surfaceContainerHighest = LtInputBackground,
    onSurfaceVariant = LtTextDim,
    outline = LtBorder,
    outlineVariant = LtBorder,
    error = LtError,
    onError = Color.White
)

// ============================================================
// 注意：这里刻意去掉了模板里的 dynamicColor 参数。
// 原模板在 Android 12+ 会用「壁纸取色」覆盖掉上面所有自定义颜色，
// 你手机是 Android 16，只要 dynamicColor 还是 true，GitHub 配色就完全看不出来。
// 现在固定走 darkTheme / lightTheme 两条分支，配色才可控。
// ============================================================
@Composable
fun MyChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
