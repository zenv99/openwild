package org.openwild.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Forest = Color(0xFF1B4332)
val Leaf = Color(0xFF40916C)
val Mint = Color(0xFF95D5B2)
val Danger = Color(0xFFB00020)
val DangerBg = Color(0xFFFFE5E5)
val Caution = Color(0xFF8A5A00)
val CautionBg = Color(0xFFFFF3D6)
val Safe = Color(0xFF2D6A4F)

private val LightColors = lightColorScheme(primary = Forest, secondary = Leaf, tertiary = Mint, error = Danger)
private val DarkColors = darkColorScheme(primary = Mint, secondary = Leaf, tertiary = Forest, error = Color(0xFFFF6B6B))

@Composable
fun OpenWildTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
