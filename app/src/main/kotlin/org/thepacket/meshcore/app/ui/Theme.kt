package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Black-first palette echoing the on-device LVGL UI: a colourful set on near-black.
private val Cyan = Color(0xFF3FC7E8)
private val Green = Color(0xFF4ADE80)
private val Amber = Color(0xFFF59E0B)
private val Surface = Color(0xFF0A0E12)
private val SurfaceVariant = Color(0xFF12181E)
// Filled Cards read their background from surfaceContainerHighest — a very dark gray.
private val CardBackground = Color(0xFF141414)

private val MeshDarkColors = darkColorScheme(
    primary = Cyan,
    secondary = Green,
    tertiary = Amber,
    background = Color.Black,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    surfaceContainerHighest = CardBackground,
    onPrimary = Color.Black,
    onBackground = Color(0xFFE6F2F5),
    onSurface = Color(0xFFE6F2F5),
)

@Composable
fun MeshCoreTheme(content: @Composable () -> Unit) {
    // Always dark — the design language is black-background, like the device UI.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = MeshDarkColors, content = content)
}
