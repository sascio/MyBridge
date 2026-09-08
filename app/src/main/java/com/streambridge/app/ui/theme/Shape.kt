package com.streambridge.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Generously rounded geometry: cards use a soft 24 dp corner, smaller
 * controls 16 dp, chips and pills round to a full capsule.
 */
val StreamBridgeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Full capsule shape for pills, badges and progress tracks. */
val PillShape = RoundedCornerShape(percent = 50)
