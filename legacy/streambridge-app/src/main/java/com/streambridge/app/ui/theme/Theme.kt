package com.streambridge.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The active accent, readable from any composable. */
val LocalAccent = staticCompositionLocalOf { AccentTheme.Gold }

/**
 * Stream Bridge is dark-first: the app is always dark, with an optional
 * pure-black (OLED) variant and selectable accent gradients.
 */
@Composable
fun StreamBridgeTheme(
    accentKey: String = AccentTheme.Gold.key,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val accent = accentFor(accentKey)
    val background = if (pureBlack) Color(0xFF000000) else SbBackground
    val surface = if (pureBlack) Color(0xFF0A0A0A) else SbSurface
    val surfaceElevated = if (pureBlack) Color(0xFF101010) else SbSurfaceElevated
    val surfaceVariant = if (pureBlack) Color(0xFF151515) else SbSurfaceVariant

    val colors = darkColorScheme(
        primary = accent.primary,
        onPrimary = Color(0xFF141414),
        primaryContainer = accent.primary.copy(alpha = 0.18f),
        onPrimaryContainer = accent.primary,
        secondary = accent.secondary,
        onSecondary = Color(0xFF141414),
        secondaryContainer = accent.secondary.copy(alpha = 0.18f),
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

/** The active accent's three-stop gradient, for buttons and highlights. */
fun accentGradient(key: String): List<Color> = accentFor(key).gradient
