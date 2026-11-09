package com.mymav.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * MyMav palette — matches the glasses HUD ("smoked glass", one cyan accent).
 *
 * The shell is always dark. Previously the theme followed the system setting
 * while the background gradient faded from navy to near-white, so half of
 * every screen had the wrong text contrast.
 */
object MyMavColors {
    val Void = Color(0xFF06080B)
    val Night = Color(0xFF0B1016)
    val Surface = Color(0xFF111821)
    val SurfaceHigh = Color(0xFF17202B)
    val Hairline = Color(0x1FFFFFFF)
    val HairlineStrong = Color(0x33FFFFFF)
    val Text = Color(0xFFEAF1F7)
    val TextMuted = Color(0xFF93A3B5)
    val TextFaint = Color(0xFF627285)
    val Accent = Color(0xFF45D6F5)
    val OnAccent = Color(0xFF03141B)
    val Amber = Color(0xFFF5B945)
    val Good = Color(0xFF4ADE80)
    val Bad = Color(0xFFF87171)
}

private val ShellColors = darkColorScheme(
    primary = MyMavColors.Accent,
    onPrimary = MyMavColors.OnAccent,
    primaryContainer = Color(0xFF0E3440),
    onPrimaryContainer = MyMavColors.Accent,
    secondary = MyMavColors.Amber,
    onSecondary = Color(0xFF1A1200),
    background = MyMavColors.Void,
    onBackground = MyMavColors.Text,
    surface = MyMavColors.Surface,
    onSurface = MyMavColors.Text,
    surfaceVariant = MyMavColors.SurfaceHigh,
    onSurfaceVariant = MyMavColors.TextMuted,
    surfaceContainerHighest = MyMavColors.SurfaceHigh,
    surfaceContainerHigh = MyMavColors.SurfaceHigh,
    surfaceContainer = MyMavColors.Surface,
    outline = MyMavColors.HairlineStrong,
    outlineVariant = MyMavColors.Hairline,
    error = MyMavColors.Bad,
)

/** Mini-app screens were designed light; keep them light regardless of system mode. */
private val MiniColors = lightColorScheme(
    primary = Color(0xFF0E7C8C),
    onPrimary = Color.White,
    secondary = Color(0xFFB7791F),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF0E1A24),
    surface = Color.White,
    onSurface = Color(0xFF0E1A24),
    surfaceVariant = Color(0xFFEDF1F4),
    onSurfaceVariant = Color(0xFF4B5B68),
)

private val MyMavType = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    )
}

@Composable
fun MyMavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShellColors,
        typography = MyMavType,
        content = content,
    )
}

@Composable
fun MyMavMiniTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MiniColors,
        typography = MyMavType,
        content = content,
    )
}
