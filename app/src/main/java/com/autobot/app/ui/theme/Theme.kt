package com.autobot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 浅色固定色板（白色为主 + 蓝色强调 + 红色报错）
private val LightBackground = Color(0xFFF5F2ED)
private val LightSurface = Color(0xFFF9F7F3)
private val LightSurfaceVariant = Color(0xFFE8E4DE)
private val LightOnSurface = Color(0xFF1C1B18)     // 黑色主文字
private val LightOnSurfaceVariant = Color(0xFF8A8580) // 灰色辅助文字
private val LightOutline = Color(0xFFC9C4BE)

val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF2B6BCA),           // 蓝色强调
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5F1FF),
    onPrimaryContainer = Color(0xFF002453),
    secondary = Color(0xFF8A8580),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E4DE),
    onSecondaryContainer = Color(0xFF1C1B18),
    tertiary = Color(0xFF2B6BCA),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE5F1FF),
    onTertiaryContainer = Color(0xFF002453),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightSurfaceVariant,
    error = Color(0xFFF53F3F),             // 红色报错
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD8D6),
    onErrorContainer = Color(0xFF690005)
)

val AutoBotShapes = Shapes(
    extraSmall = RoundedCornerShape(MaaDesignTokens.CornerRadius.inner),
    small = RoundedCornerShape(MaaDesignTokens.CornerRadius.button),
    medium = RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
    large = RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
    extraLarge = RoundedCornerShape(MaaDesignTokens.CornerRadius.pill)
)

@Composable
fun AutoBotTheme(
    content: @Composable () -> Unit
) {
    // 强制浅色模式，不做深色切换
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        shapes = AutoBotShapes,
        content = content
    )
}
