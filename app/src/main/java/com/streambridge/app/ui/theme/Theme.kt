package com.streambridge.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Stream Bridge is dark-first: the app is always dark, with an optional
 * pure-black (OLED) variant and selectable accent colors.
 */
@Composable
fun StreamBridgeTheme(
    accentKey: String = AccentTheme.Violet.key,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val accent = accentFor(accentKey)
    val background = if (pureBlack) Color(0xFF000000) else SbBackground
    val surface = if (pureBlack) Color(0xFF08070C) else SbSurface
    val surfaceElevated = if (pureBlack) Color(0xFF0F0D16) else SbSurfaceElevated
    val surfaceVariant = if (pureBlack) Color(0xFF14111D) else SbSurfaceVariant

    val colors = darkColorScheme(
        primary = accent.primary,
        onPrimary = Color(0xFF0B0A10),
        primaryContainer = accent.primary.copy(alpha = 0.22f),
        onPrimaryContainer = accent.primary,
        secondary = accent.secondary,
        onSecondary = Color(0xFF0B0A10),
        secondaryContainer = accent.secondary.copy(alpha = 0.20f),
        onSecondaryContainer = accent.secondary,
        tertiary = SbSuccess,
        background = background,
        onBackground = SbOnBackground,
        surface = surface,
        onSurface = SbOnSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = SbOnSurfaceVariant,
        outline = SbOutline,
        outlineVariant = SbOutline.copy(alpha = 0.55f),
        error = SbError,
        onError = Color(0xFF2A060B),
        scrim = SbScrim
    )

    MaterialTheme(
        colorScheme = colors,
        typography = StreamBridgeTypography,
        shapes = StreamBridgeShapes,
        content = content
    )
}
