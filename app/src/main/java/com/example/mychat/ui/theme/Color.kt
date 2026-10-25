package com.example.mychat.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// CoolStream 皮肤：Codex / GitHub 极客风
// 命名约定：Dk = 暗色（Dark），Lt = 亮色（Light）
// ============================================================

// ---------------- 暗色 Dark ----------------
val DkBackground     = Color(0xFF000000)   // 全局背景：绝对纯黑（OLED 省电）
val DkSurface        = Color(0xFF0D1117)   // AI 气泡底：极暗灰（比纯黑亮一档，才看得出边界）
val DkSurfaceVar     = Color(0xFF161B22)   // 次级面板（顶栏等）
val DkBorder         = Color(0xFF30363D)   // 边框：AI 气泡 / 输入框
val DkInputBackground = Color(0xFF000000)  // 输入框底：纯黑

// 用户气泡：二选一。
// 深邃蓝（当前启用）：
val DkUserBubble     = Color(0xFF1F6FEB)
// GitHub 绿（想换就注释掉上面那行，改成下面这行）：
// val DkUserBubble  = Color(0xFF238636)

val DkOnUserBubble   = Color(0xFFFFFFFF)   // 用户气泡文字
val DkText           = Color(0xFFC9D1D9)   // 正文
val DkTextDim        = Color(0xFF8B949E)   // 次要文字 / 占位符
val DkError          = Color(0xFFF85149)   // 错误提示（GitHub 红）

// ---------------- 亮色 Light ----------------
val LtBackground     = Color(0xFFFFFFFF)   // 全局背景：纯白
val LtSurface        = Color(0xFFF6F8FA)   // AI 气泡底：浅灰（白底上的白气泡会看不见）
val LtSurfaceVar     = Color(0xFFF0F3F9)   // 次级面板
val LtBorder         = Color(0xFFD0D7DE)   // 边框：AI 气泡 / 输入框
val LtInputBackground = Color(0xFFFFFFFF)  // 输入框底：纯白
val LtUserBubble     = Color(0xFF0969DA)   // 用户气泡（GitHub 蓝）
val LtOnUserBubble   = Color(0xFFFFFFFF)   // 用户气泡文字
val LtText           = Color(0xFF24292F)   // 正文
val LtTextDim        = Color(0xFF57606A)   // 次要文字 / 占位符
val LtError          = Color(0xFFCF222E)   // 错误提示

// ---------------- 代码块（两种主题共用）----------------
// 代码块刻意不跟随主题：GitHub / Codex 风格的代码块永远是深色，
// 亮色主题下是"白底上的一块黑"，暗色主题下靠 #30363D 描边和气泡区分开。
val CodeBlockBackground = Color(0xFF161B22)   // 深灰底
val CodeBlockBorder     = Color(0xFF30363D)   // 描边
val CodeBlockText       = Color(0xFFC9D1D9)   // 代码文字

// ★ v1.4.0 战役二：公式强调色。浅紫蓝，在 #161B22 深底上对比度够，
//   又不像纯红那样看着像报错。
val FormulaAccent       = Color(0xFFA78BFA)

// ★ v1.4.0 战役三：Diff 红绿高亮 + 行号灰。
//   底色刻意压到极淡（#1B3524 / #3B1D1D）—— 代码块本身已经是 #161B22 深底，
//   再亮一档就会盖过语法高亮的蓝紫/绿，变成一坨色块。
val DiffAddedBackground   = Color(0xFF1B3524)   // 新增行底：极淡暗绿
val DiffAddedText         = Color(0xFF7EE787)   // 新增行字：亮绿
val DiffRemovedBackground = Color(0xFF3B1D1D)   // 删除行底：极淡暗红
val DiffRemovedText       = Color(0xFFFF7B72)   // 删除行字：亮红
val CodeLineNumber        = Color(0xFF6E7681)   // 行号：暗灰，比正文低一档存在感

// ---------------- 毛玻璃（Glassmorphism）----------------
// 不用 Modifier.blur()（性能极差），而是「半透明底 + 高光边框」的拟态做法。
// 深色 / 亮色各一套：亮色主题下用深色玻璃会让文字完全看不见。
val GlassFillDark    = Color(0xFF1C1C1E).copy(alpha = 0.80f)   // 输入框 / 顶栏按钮底
val GlassFillLight   = Color(0xFFFFFFFF).copy(alpha = 0.88f)

val GlassDrawerDark  = Color(0xFF1C1C1E).copy(alpha = 0.95f)   // 侧边栏底
val GlassDrawerLight = Color(0xFFFFFFFF).copy(alpha = 0.97f)

// 底部弹层（附件选择器）专用：它会盖在长聊天文本上，用 alpha 0.80 的 GlassFill*
// 会把下面的字透出来、字压字读不清。提到 0.97/0.98 后仍带一点玻璃感，正文不再干扰。
val GlassSheetDark   = Color(0xFF1C1C1E).copy(alpha = 0.97f)   // 附件弹层底
val GlassSheetLight  = Color(0xFFFFFFFF).copy(alpha = 0.98f)

val GlassEdgeDark    = Color.White.copy(alpha = 0.15f)         // 高光边框（暗色）
val GlassEdgeLight   = Color.Black.copy(alpha = 0.08f)         // 高光边框（亮色）

val GlassHighlight   = Color.White.copy(alpha = 0.22f)         // 更强的高光线（侧边栏右缘）
val GlassTintDark    = Color.White.copy(alpha = 0.06f)         // 选中项的半透明高亮
val GlassTintLight   = Color.Black.copy(alpha = 0.05f)

// ---------------- 行内代码 `xxx` 的底色 ----------------
// 用中性灰 + 低透明度，白底上是浅灰、黑底上是深灰，一套值两边都能用。
// （行内代码只出现在 AI 气泡里 —— 用户消息不渲染 Markdown）
val CodeInlineBackground = Color(0xFF8B949E).copy(alpha = 0.25f)
