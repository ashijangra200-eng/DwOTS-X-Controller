package com.acwo.dwotsxcontroller.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Cyan = Color(0xFF00F0FF)
val Magenta = Color(0xFFFF00E5)
val Purple = Color(0xFF8B5CF6)
val BgDark = Color(0xFF0A0A0F)
val BgCard = Color(0xFF12121A)
val BgSurface = Color(0xFF1A1A25)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFA0A0B0)
val Success = Color(0xFF00FF9D)
val Error = Color(0xFFFF3B5C)
val Warning = Color(0xFFFFB800)

private val DarkColorScheme = darkColorScheme(
    primary = Cyan,
    secondary = Magenta,
    tertiary = Purple,
    background = BgDark,
    surface = BgCard,
    onPrimary = BgDark,
    onSecondary = BgDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = Error
)

@Composable
fun DwOTSXControllerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, content = content)
}
