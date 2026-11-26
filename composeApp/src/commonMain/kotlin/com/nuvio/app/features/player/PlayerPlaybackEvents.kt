package com.nuvio.app.features.player

import androidx.compose.runtime.staticCompositionLocalOf

/** Optional scoped end-of-stream consumer. The engine remains the sole playback owner.
 * Defaults to no observer, so movie/series autoplay behavior is unchanged. */
internal val LocalPlayerEndOfStream = staticCompositionLocalOf<(() -> Unit)?> { null }
