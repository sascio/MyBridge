package com.nuvio.app.features.media

import com.nuvio.app.navigation.MediaEnvironment
import kotlin.math.abs

/** Policy for the dedicated boundary only, not a content-wide navigation gesture. */
internal fun mediaSwipeDestination(dx: Float, dy: Float, threshold: Float): MediaEnvironment? {
    if (!dx.isFinite() || !dy.isFinite() || !threshold.isFinite() || threshold <= 0f) return null
    if (abs(dx) < threshold || abs(dx) <= abs(dy) * 1.5f) return null
    return if (dx < 0f) MediaEnvironment.Music else MediaEnvironment.YouTube
}
