package com.streambridge.app.ui.details

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adaptive background for the detail screen (Spotify "Now Playing" style):
 * the dominant color of the title's artwork tints a vertical gradient that
 * sits behind the hero and fades into the screen's base color, so the page
 * picks up each title's mood without ever compromising text readability.
 *
 * Three layers, each independently simple:
 *  1. [AdaptiveColorLogic]    — pure color math (JVM unit tested).
 *  2. [AdaptiveColorCache]    — per-title in-memory cache.
 *  3. [AdaptiveColorExtractor] — Coil + androidx.palette, off the main
 *     thread, cancellable.
 *
 * The composable layer ([rememberAdaptiveBackgroundColor] +
 * [AdaptiveBackgroundGradient]) wires them into the UI with animated
 * transitions and a reduce-motion escape hatch.
 */

// ---------------------------------------------------------------------
// 1. Pure color logic — no Android dependencies, fully unit-testable.
// ---------------------------------------------------------------------

/**
 * Chooses and prepares a background color from palette candidates.
 *
 * Strategy (mirrors the classic Palette guidance for backgrounds):
 *  - Candidates arrive pre-ordered by preference (dark muted first —
 *    muted dark tones tint beautifully without fighting white text).
 *  - A candidate that is too washed out (near-gray, very low saturation)
 *    is rejected and the next candidate is tried; a washed-out color
 *    would make the screen look broken rather than tinted.
 *  - A candidate that is too bright is *dimmed* (its HSV value is
 *    clamped) instead of rejected — hue survives, readability returns.
 *  - If nothing survives, the caller keeps the default black background.
 */
object AdaptiveColorLogic {

    /** Below this saturation a color reads as gray — not worth using. */
    const val MIN_SATURATION = 0.10f

    /**
     * Upper bound for brightness after clamping. With the gradient's
     * ~70% alpha composited over a near-black base, this keeps white
     * hero text/icons above WCAG AA contrast for large text.
     */
    const val MAX_VALUE = 0.48f

    /** Neon colors are tempered slightly so the tint stays cinematic. */
    const val MAX_SATURATION = 0.85f

    /**
     * Redmean color distance (a weighted Euclidean approximation that
     * accounts for human perception of red/green/blue). Used to throttle
     * updates: only re-tint the screen when the new color is a real
     * change, not a subtle shift, which prevents flicker.
     */
    const val DISTANCE_THRESHOLD = 40.0

    /** Returns the first usable candidate after dimming, or null. */
    fun pickBackgroundColor(candidates: List<Int>): Int? {
        for (candidate in candidates) {
            prepare(candidate)?.let { return it }
        }
        return null
    }

    /**
     * Rejects near-gray colors and dims over-bright ones. Returns an
     * opaque ARGB int ready for the gradient, or null when the color is
     * unsuitable for a background.
     */
    fun prepare(argb: Int): Int? {
        val hsv = rgbToHsv(argb)
        val s = hsv[1]
        val v = hsv[2]
        if (s < MIN_SATURATION) return null
        val safeS = if (s > MAX_SATURATION) MAX_SATURATION else s
        val safeV = if (v > MAX_VALUE) MAX_VALUE else v
        return hsvToArgb(hsv[0], safeS, safeV)
    }

    /** Redmean distance between two opaque colors (0 = identical). */
    fun colorDistance(first: Int, second: Int): Double {
        val r1 = (first shr 16) and 0xFF
        val g1 = (first shr 8) and 0xFF
        val b1 = first and 0xFF
        val r2 = (second shr 16) and 0xFF
        val g2 = (second shr 8) and 0xFF
        val b2 = second and 0xFF
        val rMean = (r1 + r2) / 2.0
        val dr = (r1 - r2).toDouble()
        val dg = (g1 - g2).toDouble()
        val db = (b1 - b2).toDouble()
        return Math.sqrt(
            (2.0 + rMean / 256.0) * dr * dr +
                4.0 * dg * dg +
                (2.0 + (255.0 - rMean) / 256.0) * db * db
        )
    }

    /** True when [colorDistance] exceeds the perceptual threshold. */
    fun differsSignificantly(first: Int, second: Int): Boolean =
        colorDistance(first, second) > DISTANCE_THRESHOLD

    /** ARGB → HSV (h in [0,360), s/v in [0,1]); no android.graphics. */
    fun rgbToHsv(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val h = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val s = if (max == 0f) 0f else delta / max
        return floatArrayOf(h, s, max)
    }

    /** HSV → opaque ARGB. */
    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - Math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        val r = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

// ---------------------------------------------------------------------
// 2. Per-title cache — repeat visits re-tint instantly, no re-extraction.
// ---------------------------------------------------------------------

/**
 * In-memory LRU of extracted colors keyed by "mediaKey|imageUrl". The
 * key includes the URL so a refreshed poster re-extracts, and the media
 * key so different titles never share entries. Process-lifetime only:
 * colors are cheap to recompute and not worth persisting to disk.
 */
object AdaptiveColorCache {
    private val lru = LruCache<String, Int>(48)

    private fun key(mediaKey: String, imageUrl: String) = "$mediaKey|$imageUrl"

    fun get(mediaKey: String, imageUrl: String): Int? =
        synchronized(lru) { lru.get(key(mediaKey, imageUrl)) }

    fun put(mediaKey: String, imageUrl: String, color: Int) {
        synchronized(lru) { lru.put(key(mediaKey, imageUrl), color) }
    }
}

// ---------------------------------------------------------------------
// 3. Extraction — Coil decode (tiny) + Palette, always off the main
//    thread, cancellable mid-flight.
// ---------------------------------------------------------------------

object AdaptiveColorExtractor {

    /** Images are decoded at ~64 px: plenty for a dominant color. */
    private const val EXTRACT_SIZE = 64

    /**
     * Downloads [imageUrl] through the shared Coil pipeline (so the
     * full-size artwork stays disk-cached for the hero itself), decodes
     * it small, and reduces it to one background-ready ARGB color.
     *
     * Returns null on any failure or when no usable color exists —
     * callers keep the default black background in that case. Throws
     * CancellationException upward so screen changes cancel cleanly.
     */
    suspend fun extract(context: Context, imageUrl: String): Int? =
        withContext(Dispatchers.Default) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(EXTRACT_SIZE)
                    // Palette reads pixels: hardware bitmaps are not
                    // readable, so this one decode must be software.
                    .allowHardware(false)
                    .build()
                val result = loadImage(context.imageLoader, request)
                val bitmap = (result as? BitmapDrawable)?.bitmap
                    ?: return@withContext null
                val palette = Palette.from(bitmap)
                    // The default filter drops very dark swatches —
                    // exactly the ones that make good backgrounds.
                    .clearFilters()
                    .maximumColorCount(24)
                    .generate()
                AdaptiveColorLogic.pickBackgroundColor(
                    listOfNotNull(
                        palette.darkMutedSwatch?.rgb,
                        palette.mutedSwatch?.rgb,
                        palette.darkVibrantSwatch?.rgb,
                        palette.vibrantSwatch?.rgb,
                        palette.dominantSwatch?.rgb
                    )
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun loadImage(loader: ImageLoader, request: ImageRequest) =
        loader.execute(request).drawable
}

// ---------------------------------------------------------------------
// Composable layer.
// ---------------------------------------------------------------------

/**
 * True when the system disables animations (Android's equivalent of
 * "Reduce Motion") — color changes then snap instead of animating.
 */
internal fun isReduceMotionEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f
}.getOrDefault(false)

/**
 * Tracks the adaptive background color for one title.
 *
 * Behavior contract:
 *  - Starts as [Color.Transparent] (= no adaptive layer; the screen is
 *    its normal dark self) so the screen never waits on the network.
 *  - Cached colors are applied on the first frame — instant, no flicker.
 *  - Fresh extractions run asynchronously and crossfade in (~420 ms,
 *    ease-in-out); with Reduce Motion they apply instantly.
 *  - When the URL changes mid-screen (preview artwork → full details),
 *    the new color only replaces the old one when it is a *significant*
 *    change (perceptual color distance), preventing subtle flicker.
 *  - Navigating away cancels the in-flight extraction (LaunchedEffect
 *    scoping), so a slow download can never tint the wrong screen.
 *
 * @param imageUrl the artwork to sample (backdrop preferred, poster as
 *   fallback); null disables the feature for this screen.
 * @param mediaKey stable per-title key ("type:id") for the cache.
 */
@Composable
fun rememberAdaptiveBackgroundColor(
    imageUrl: String?,
    mediaKey: String
): State<Color> {
    val context = LocalContext.current
    // Transparent until a color is ready — the gradient layer no-ops.
    val extracted = remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(mediaKey, imageUrl) {
        if (imageUrl == null) {
            extracted.value = null
            return@LaunchedEffect
        }
        // 1. Cache hit: instant, no extraction, no network.
        AdaptiveColorCache.get(mediaKey, imageUrl)?.let { cached ->
            extracted.value = cached
            return@LaunchedEffect
        }
        // 2. Cache miss: extract off the main thread (cancellable).
        val color = AdaptiveColorExtractor.extract(context, imageUrl)
            ?: return@LaunchedEffect // failure → keep default background
        AdaptiveColorCache.put(mediaKey, imageUrl, color)
        // 3. Throttle: ignore imperceptible changes (anti-flicker).
        val current = extracted.value
        if (current == null || AdaptiveColorLogic.differsSignificantly(current, color)) {
            extracted.value = color
        }
    }

    val target = extracted.value?.let { Color(it) } ?: Color.Transparent
    val reduceMotion = remember { isReduceMotionEnabled(context) }
    return animateColorAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            tween(durationMillis = 420, easing = FastOutSlowInEasing)
        },
        label = "adaptive-background-color"
    )
}

/**
 * The adaptive backdrop of the detail screen: the base theme color
 * (near-black) with the extracted color as a vertical gradient on top.
 *
 * Gradient shape: the tint is strongest behind the hero (~72% alpha at
 * the very top), relaxes through the mid-section and is fully gone by
 * ~46% of the screen height — around where the action buttons and
 * metadata end. Everything below (cast, episodes, related…) sits on the
 * untouched base color, exactly as before.
 *
 * A [Color.Transparent] [adaptiveColor] renders the plain base — this
 * is both the pre-extraction state and the permanent fallback.
 */
@Composable
fun AdaptiveBackgroundGradient(
    adaptiveColor: Color,
    baseColor: Color,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(baseColor)) {
        if (adaptiveColor != Color.Transparent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to adaptiveColor.copy(alpha = 0.72f),
                            0.22f to adaptiveColor.copy(alpha = 0.40f),
                            0.46f to baseColor,
                            1f to baseColor
                        )
                    )
            )
        }
    }
}
