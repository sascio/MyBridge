package com.nuvio.app.core.ui.glass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Codec round-trip for the navigation-bar glass settings.
 *
 * Regression guarded against (found by `ThemeSettingsRepositoryTest` in CI): the payload was
 * encoded with kotlinx-serialization's default "omit values equal to the property default", while
 * decoding falls back to the *domain* default for absent keys. A user who switched
 * `affectTabIcons` (or `affectFloatingBar`) off stored `false`, the field was omitted, and it came
 * back as `true` after a reload.
 */
class LiquidGlassEffectSettingsCodecTest {
    private val defaults = LiquidGlassEffectDefaults.selectionLens
    private val limits = LiquidGlassEffectDefaults.selectionLensLimits

    @Test
    fun flagsThatEqualTheirPropertyDefaultSurviveARoundTrip() {
        val disabled = defaults.copy(affectTabIcons = false, affectFloatingBar = true)

        val decoded = decodeLiquidGlassEffectSettings(
            encodeLiquidGlassEffectSettings(disabled, defaults, limits),
            defaults,
            limits,
        )

        assertEquals(disabled, decoded)
    }

    @Test
    fun bothFlagsOffSurviveARoundTrip() {
        val disabled = defaults.copy(affectTabIcons = false, affectFloatingBar = false)

        val decoded = decodeLiquidGlassEffectSettings(
            encodeLiquidGlassEffectSettings(disabled, defaults, limits),
            defaults,
            limits,
        )

        assertEquals(disabled, decoded)
    }

    @Test
    fun partialLegacyPayloadStillFallsBackToTheDomainDefaults() {
        // Older payloads only carried the numeric look; their flags must stay "on" rather than
        // being forced off by the round-trip fix.
        val decoded = decodeLiquidGlassEffectSettings("""{"refractionAmountDp":10.0}""", defaults, limits)

        assertTrue(decoded.affectTabIcons)
        assertTrue(decoded.affectFloatingBar)
    }
}
