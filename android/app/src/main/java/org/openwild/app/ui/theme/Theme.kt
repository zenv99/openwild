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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openwild.app.core.Appearance

// Brand. Nav and Explore colors live in Appearance so the contrast test and the theme cannot drift.
val Forest = Color(0xFF0F3D2E)
val ForestDeep = Color(Appearance.FOREST_DEEP)
val Leaf = Color(Appearance.LEAF)
val Lime = Color(Appearance.LIME)
val Mint = Color(0xFF95D5B2)
val Sand = Color(Appearance.SAND)

/** Card behind an Explore photo that has not decoded yet. Opaque, so the grid is not a black wall. */
@Composable
fun exploreCardColor(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return Color(if (dark) Appearance.DARK_EXPLORE_CARD else Appearance.LIGHT_EXPLORE_CARD)
}

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
    onSecondary = Color.White,
    secondaryContainer = Color(Appearance.LIGHT_INDICATOR),
    onSecondaryContainer = Color(Appearance.LIGHT_SELECTED_ICON),
    tertiary = Lime,
    onTertiary = ForestDeep,
    background = Sand,
    onBackground = Color(Appearance.LIGHT_LABEL),
    surface = Sand,
    onSurface = Color(Appearance.LIGHT_LABEL),
    surfaceVariant = Color(0xFFE7EBE4),
    onSurfaceVariant = Color(Appearance.LIGHT_UNSELECTED),
    surfaceContainer = Color(Appearance.LIGHT_BAR),
    surfaceContainerHigh = Color(Appearance.WHITE),
    surfaceContainerLow = Color(0xFFF1F3EE),
    error = Danger,
)

private val DarkColors = darkColorScheme(
    primary = Lime,
    onPrimary = ForestDeep,
    primaryContainer = Forest,
    onPrimaryContainer = Mint,
    secondary = Mint,
    onSecondary = ForestDeep,
    // Selected nav pill. The Material default (a dark purple) sat on the dark bar at ~1.8:1,
    // and the filled Explore compass read as a black button.
    secondaryContainer = Color(Appearance.DARK_INDICATOR),
    onSecondaryContainer = Color(Appearance.DARK_SELECTED_ICON),
    tertiary = Lime,
    background = Color(Appearance.DARK_PAGE),
    onBackground = Color(Appearance.DARK_LABEL),
    surface = Color(Appearance.DARK_PAGE),
    onSurface = Color(Appearance.DARK_LABEL),
    surfaceVariant = Color(0xFF1E2A24),
    onSurfaceVariant = Color(Appearance.DARK_UNSELECTED),
    surfaceContainer = Color(Appearance.DARK_BAR),
    surfaceContainerHigh = Color(0xFF1B2721),
    surfaceContainerLow = Color(0xFF111A16),
    outline = Color(0xFF8AA394),
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
