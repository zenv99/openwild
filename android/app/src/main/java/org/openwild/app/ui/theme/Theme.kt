package org.openwild.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

// Safety semantics (fixed in light and dark so meaning never changes)
val Danger = Color(0xFFC62828)
val DangerBg = Color(0xFFFFEBEE)
val Caution = Color(0xFFB26A00)
val CautionBg = Color(0xFFFFF4E0)
val Safe = Color(0xFF2E7D32)
val SafeBg = Color(0xFFE8F5E9)
val Psychedelic = Color(0xFF6A1B9A)
val PsychedelicBg = Color(0xFFF3E5F5)

val HeroGradient = Brush.linearGradient(listOf(ForestDeep, Forest, Leaf))
val DangerGradient = Brush.linearGradient(listOf(Color(0xFF5A0B0B), Danger))
val CautionGradient = Brush.linearGradient(listOf(Color(0xFF6B3A00), Caution))
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
    primaryContainer = Forest,
    onPrimaryContainer = Mint,
    secondary = Mint,
    tertiary = Lime,
    background = Color(0xFF0B1410),
    surface = Color(0xFF0B1410),
    surfaceVariant = Color(0xFF1E2A24),
    surfaceContainer = Color(0xFF15201B),
    surfaceContainerHigh = Color(0xFF1B2721),
    surfaceContainerLow = Color(0xFF111A16),
    error = Color(0xFFFF6B6B),
)

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
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
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
