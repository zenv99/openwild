package org.openwild.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand
val Forest = Color(0xFF0F3D2E)
val ForestDeep = Color(0xFF07261C)
val Leaf = Color(0xFF2E8B57)
val Lime = Color(0xFFB7E36A)
val Mint = Color(0xFF95D5B2)
val Sand = Color(0xFFF6F4EE)

// Safety semantics: same meaning in light and dark, but brighter text and deep backgrounds in dark
// so every warning stays easy to read.
val LocalDarkTheme = staticCompositionLocalOf { true }

private fun pick(dark: Boolean, d: Long, l: Long) = Color(if (dark) d else l)

val Danger: Color @Composable @ReadOnlyComposable get() = pick(LocalDarkTheme.current, 0xFFFF6B6B, 0xFFC62828)
val DangerBg: Color @Composable @ReadOnlyComposable get() = pick(LocalDarkTheme.current, 0xFF3D1316, 0xFFFFEBEE)
val OnDangerBg: Color @Composable @ReadOnlyComposable get() = pick(LocalDarkTheme.current, 0xFFFFDAD6, 0xFF7F0000)
val Caution: Color @Composable @ReadOnlyComposable get() = pick(LocalDarkTheme.current, 0xFFFFB74D, 0xFFB26A00)
val CautionBg: Color @Composable @ReadOnlyComposable get() = pick(LocalDarkTheme.current, 0xFF3A2A0C, 0xFFFFF4E0)
val OnCautionBg: Color @Composable @ReadOnlyComposable get() = pick(LocalDarkTheme.current, 0xFFFFE2B8, 0xFF4A3000)
val Safe: Color @Composable @ReadOnlyComposable get() = pick(LocalDarkTheme.current, 0xFF81E09A, 0xFF2E7D32)
val SafeBg: Color @Composable @ReadOnlyComposable get() = pick(LocalDarkTheme.current, 0xFF12301C, 0xFFE8F5E9)
val Psychedelic: Color @Composable @ReadOnlyComposable get() = pick(LocalDarkTheme.current, 0xFFD8A6F0, 0xFF6A1B9A)
val PsychedelicBg: Color @Composable @ReadOnlyComposable get() = pick(LocalDarkTheme.current, 0xFF2E1438, 0xFFF3E5F5)

val HeroGradient = Brush.linearGradient(listOf(ForestDeep, Forest, Leaf))
val DangerGradient = Brush.linearGradient(listOf(Color(0xFF5A0B0B), Color(0xFFC62828)))
val CautionGradient = Brush.linearGradient(listOf(Color(0xFF6B3A00), Color(0xFFB26A00)))
val SafeGradient = Brush.linearGradient(listOf(Forest, Leaf))
val NeutralGradient = Brush.linearGradient(listOf(Color(0xFF263238), Color(0xFF546E7A)))

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = ForestDeep,
    secondary = Leaf,
    tertiary = Lime,
    onTertiary = ForestDeep,
    background = Sand,
    surface = Sand,
    surfaceVariant = Color(0xFFE7EBE4),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3EE),
    error = Danger,
)

private val DarkColors = darkColorScheme(
    primary = Lime,
    onPrimary = ForestDeep,
    primaryContainer = Color(0xFF1F4D38),
    onPrimaryContainer = Color(0xFFD8F5C0),
    secondary = Mint,
    onSecondary = ForestDeep,
    secondaryContainer = Color(0xFF1F3A2C),
    onSecondaryContainer = Color(0xFFD6F2E0),
    tertiary = Lime,
    background = Color(0xFF0B1410),
    onBackground = Color(0xFFF2F6F1),
    surface = Color(0xFF0B1410),
    onSurface = Color(0xFFF2F6F1),
    surfaceVariant = Color(0xFF1E2A24),
    onSurfaceVariant = Color(0xFFD0DCD3),
    surfaceContainer = Color(0xFF16221C),
    surfaceContainerHigh = Color(0xFF1C2923),
    surfaceContainerLow = Color(0xFF111A16),
    outline = Color(0xFF8FA597),
    error = Color(0xFFFF6B6B),
)

// Larger, higher-contrast text than Material defaults: readable outdoors, in sun, at arm's length.
private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold),
        headlineMedium = headlineMedium.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = titleSmall.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(fontSize = 18.sp, lineHeight = 27.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontSize = 17.sp, lineHeight = 25.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun OpenWildTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
