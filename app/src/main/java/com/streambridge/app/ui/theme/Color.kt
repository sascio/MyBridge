package com.streambridge.app.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------
// Base dark palette: near-black neutrals with elevated surfaces.
// ---------------------------------------------------------------------
val SbBackground = Color(0xFF0D0D0D)
val SbBackgroundAlt = Color(0xFF121212)
val SbSurface = Color(0xFF1A1A1A)
val SbSurfaceElevated = Color(0xFF202020)
val SbSurfaceVariant = Color(0xFF242424)
val SbOutline = Color(0xFF3A3A3A)
val SbOnBackground = Color(0xFFF6F6F6)
val SbOnSurface = Color(0xFFEFEFEF)
val SbOnSurfaceVariant = Color(0xFF9C9C9C)
val SbScrim = Color(0xB3000000)
val SbSuccess = Color(0xFF6FE3A8)
val SbError = Color(0xFFFF6B7A)
val SbWarning = Color(0xFFFFC46B)

/**
 * Stream Bridge accent presets. Every accent carries a three-stop
 * gradient used for hero buttons, progress bars and highlights.
 */
enum class AccentTheme(
    val key: String,
    val label: String,
    val primary: Color,
    val secondary: Color,
    val gradient: List<Color>
) {
    Gold(
        "gold", "Gold",
        Color(0xFFFFD45C), Color(0xFFE8A91C),
        listOf(Color(0xFFB8860B), Color(0xFFE8A91C), Color(0xFFFFD45C))
    ),
    Jade(
        "jade", "Jade",
        Color(0xFF7BF08D), Color(0xFF22D37C),
        listOf(Color(0xFF0BBF9A), Color(0xFF22D37C), Color(0xFF7BF08D))
    ),
    Rose(
        "rose", "Rose",
        Color(0xFFFFB37A), Color(0xFFEC70A9),
        listOf(Color(0xFFB75AFF), Color(0xFFEC70A9), Color(0xFFFFB37A))
    ),
    Violet(
        "violet", "Violet",
        Color(0xFFB7A6FF), Color(0xFF8B7CFF),
        listOf(Color(0xFF6E5BFF), Color(0xFF8B7CFF), Color(0xFFB7A6FF))
    ),
    Azure(
        "azure", "Azure",
        Color(0xFF7BD4F0), Color(0xFF3FA9F5),
        listOf(Color(0xFF2F7BFF), Color(0xFF3FA9F5), Color(0xFF9BE8FF))
    )
}

fun accentFor(key: String): AccentTheme =
    AccentTheme.entries.firstOrNull { it.key == key } ?: AccentTheme.Gold
