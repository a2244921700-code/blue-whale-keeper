package com.moneydafeiyu.ledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 取自鲸鱼娘形象的蓝白配色
/**
 * 所有卡片容器的统一不透明度。
 *
 * 取 0.7 是权衡：再低文字对比度就不够了，再高背景图等于白放。
 * 只作用于**容器色**（卡片、面板），文字颜色保持不透明，保证可读性。
 */
const val ALPHA = 0.7f

/**
 * 果冻感的关键一笔：卡片边缘一圈半透明白描边。
 *
 * 只有「半透明 + 大圆角」看着还是平的 —— 描边模拟了果冻表面那层高光反光，
 * 卡片立刻有了厚度和湿润感。用白色而不是灰色，因为白色更像光。
 */
val JellyBorder = androidx.compose.foundation.BorderStroke(
    1.2.dp,
    Color.White.copy(alpha = 0.55f),
)

val WhaleBlue = Color(0xFF2B5C9B)
val WhaleBlueLight = Color(0xFF4A7EBB)
val WhaleBluePale = Color(0xFFDCE8F7)
val WhaleNavy = Color(0xFF1B3A6B)
val IncomeGreen = Color(0xFF3FA46A)
val ExpenseRed = Color(0xFFD9534F)
val WarmSand = Color(0xFFF7F3EA)

private val LightColors = lightColorScheme(
    primary = WhaleBlue,
    onPrimary = Color.White,
    primaryContainer = WhaleBluePale.copy(alpha = ALPHA),
    onPrimaryContainer = WhaleNavy,
    secondary = WhaleBlueLight,
    // 底色全透明 —— 这样页面背景图才能透出来
    background = Color.Transparent,
    surface = Color.White.copy(alpha = ALPHA),
    surfaceVariant = Color(0xFFEDF2F9).copy(alpha = ALPHA),
    errorContainer = Color(0xFFF9DEDC).copy(alpha = ALPHA),
    // Material3 的 Card / Menu / Dialog 默认吃的是这一组 surfaceContainer，
    // 之前只改了 surface 和 surfaceVariant，所以卡片看着还是不透明。
    surfaceContainerLowest = Color.White.copy(alpha = ALPHA),
    surfaceContainerLow = Color.White.copy(alpha = ALPHA),
    surfaceContainer = Color.White.copy(alpha = ALPHA),
    surfaceContainerHigh = Color.White.copy(alpha = ALPHA),
    surfaceContainerHighest = Color(0xFFEDF2F9).copy(alpha = ALPHA),
)

private val DarkColors = darkColorScheme(
    primary = WhaleBlueLight,
    onPrimary = Color(0xFF0E1D33),
    primaryContainer = WhaleNavy.copy(alpha = ALPHA),
    onPrimaryContainer = WhaleBluePale,
    secondary = Color(0xFF7FA8D8),
    background = Color.Transparent,
    surface = Color(0xFF171E27).copy(alpha = ALPHA),
    surfaceVariant = Color(0xFF232C38).copy(alpha = ALPHA),
    errorContainer = Color(0xFF5C2B29).copy(alpha = ALPHA),
    surfaceContainerLowest = Color(0xFF171E27).copy(alpha = ALPHA),
    surfaceContainerLow = Color(0xFF171E27).copy(alpha = ALPHA),
    surfaceContainer = Color(0xFF1C2531).copy(alpha = ALPHA),
    surfaceContainerHigh = Color(0xFF232C38).copy(alpha = ALPHA),
    surfaceContainerHighest = Color(0xFF2A3440).copy(alpha = ALPHA),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun MoneyDafeiyuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
