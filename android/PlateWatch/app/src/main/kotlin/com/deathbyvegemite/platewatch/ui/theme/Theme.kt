package com.deathbyvegemite.platewatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0E1116)
private val Slate = Color(0xFF171C24)
private val Mint = Color(0xFF6EE7B7)
private val Amber = Color(0xFFFBBF24)
private val Rose = Color(0xFFFB7185)

/**
 * Dark by default whatever the system says: this app is used in a car, often at
 * night, with the phone in the driver's field of view. A white screen there is a
 * genuine hazard.
 */
private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    secondary = Amber,
    onSecondary = Ink,
    error = Rose,
    background = Ink,
    onBackground = Color(0xFFE6EAF2),
    surface = Slate,
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF212936),
    onSurfaceVariant = Color(0xFFA9B4C6),
    outline = Color(0xFF3A4353),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    secondary = Color(0xFFB45309),
    error = Color(0xFFB91C1C),
)

@Composable
fun PlateWatchTheme(
    forceDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = forceDark || isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
