package com.streambridge.app.ui.theme

import androidx.compose.ui.graphics.Color

// Base dark palette (dark-first design system)
val SbBackground = Color(0xFF0B0A10)
val SbBackgroundAlt = Color(0xFF100E18)
val SbSurface = Color(0xFF14121C)
val SbSurfaceElevated = Color(0xFF1B1826)
val SbSurfaceVariant = Color(0xFF241F33)
val SbOutline = Color(0xFF3B3452)
val SbOnBackground = Color(0xFFF0EEF7)
val SbOnSurface = Color(0xFFE7E3F1)
val SbOnSurfaceVariant = Color(0xFFACA5C2)
val SbScrim = Color(0xB2080608)
val SbSuccess = Color(0xFF6FE3A8)
val SbError = Color(0xFFFF6B7A)
val SbWarning = Color(0xFFFFC46B)

/** Original Stream Bridge accent presets. */
enum class AccentTheme(
    val key: String,
    val label: String,
    val primary: Color,
    val secondary: Color
) {
    Violet("violet", "Violet", Color(0xFF8B7CFF), Color(0xFF5BD6E8)),
    Cyan("cyan", "Cyan", Color(0xFF4DD8E6), Color(0xFF8B7CFF)),
    Magenta("magenta", "Magenta", Color(0xFFFF6EC7), Color(0xFFFFB86B)),
    Amber("amber", "Amber", Color(0xFFFFB454), Color(0xFFFF7A6E)),
    Emerald("emerald", "Emerald", Color(0xFF4BE3A8), Color(0xFF4DD8E6))
}

fun accentFor(key: String): AccentTheme =
    AccentTheme.entries.firstOrNull { it.key == key } ?: AccentTheme.Violet
