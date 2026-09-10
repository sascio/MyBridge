package com.streambridge.app

import com.streambridge.app.ui.details.AdaptiveColorLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive detail background's color math, as pure logic: candidate
 * selection, the gray/brightness guards, and the perceptual distance
 * used to throttle updates. Palette itself needs real bitmaps (Android
 * graphics) and stays behind a thin extractor; everything tested here
 * is plain ARGB arithmetic.
 */
class AdaptiveColorLogicTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `first usable candidate wins`() {
        // A dark, muted blue — exactly the kind of color backgrounds want.
        val darkMuted = argb(40, 60, 120)
        assertEquals(darkMuted, AdaptiveColorLogic.pickBackgroundColor(listOf(darkMuted)))
    }

    @Test
    fun `candidates are tried in order`() {
        val muted = argb(30, 90, 70)
        val vibrant = argb(240, 80, 80)
        // The muted candidate comes first and is usable, so it wins even
        // though the vibrant one would also have been (after dimming).
        assertEquals(muted, AdaptiveColorLogic.pickBackgroundColor(listOf(muted, vibrant)))
    }

    @Test
    fun `washed out candidates are skipped`() {
        val gray = argb(120, 120, 120) // saturation 0 -> unusable
        val usable = argb(30, 90, 70)
        assertEquals(usable, AdaptiveColorLogic.pickBackgroundColor(listOf(gray, usable)))
    }

    @Test
    fun `all gray palettes fall back to null`() {
        assertNull(
            AdaptiveColorLogic.pickBackgroundColor(
                listOf(argb(120, 120, 120), argb(200, 200, 205), argb(60, 60, 60))
            )
        )
        assertNull(AdaptiveColorLogic.pickBackgroundColor(emptyList()))
    }

    @Test
    fun `bright colors are dimmed to the brightness ceiling`() {
        val brightRed = argb(240, 80, 80) // v = 0.94
        val prepared = AdaptiveColorLogic.prepare(brightRed)
        assertNotNull(prepared)
        val hsv = AdaptiveColorLogic.rgbToHsv(prepared!!)
        // Hue family preserved (red), brightness clamped well below the
        // original 0.94 to keep white text readable.
        assertTrue("hue preserved", hsv[0] < 15f || hsv[0] > 345f)
        assertTrue(
            "brightness clamped",
            hsv[2] <= AdaptiveColorLogic.MAX_VALUE + 0.01f
        )
        assertTrue("still visibly red", hsv[1] > 0.3f)
    }

    @Test
    fun `neon saturation is tempered`() {
        val neon = argb(255, 0, 110) // s = 1.0
        val prepared = AdaptiveColorLogic.prepare(neon)
        assertNotNull(prepared)
        val hsv = AdaptiveColorLogic.rgbToHsv(prepared!!)
        assertTrue(hsv[1] <= AdaptiveColorLogic.MAX_SATURATION + 0.02f)
        assertTrue(hsv[2] <= AdaptiveColorLogic.MAX_VALUE + 0.01f)
    }

    @Test
    fun `near gray colors are rejected entirely`() {
        assertNull(AdaptiveColorLogic.prepare(argb(128, 130, 132)))
        assertNull(AdaptiveColorLogic.prepare(argb(245, 245, 245)))
    }

    @Test
    fun `very dark colors pass through untouched`() {
        // Extremely dark posters naturally produce near-black tints.
        val veryDark = argb(8, 10, 14)
        assertEquals(veryDark, AdaptiveColorLogic.prepare(veryDark))
    }

    @Test
    fun `identical colors have zero distance`() {
        val color = argb(40, 60, 120)
        assertEquals(0.0, AdaptiveColorLogic.colorDistance(color, color), 0.0001)
        assertFalse(AdaptiveColorLogic.differsSignificantly(color, color))
    }

    @Test
    fun `perceptually close colors are not significant`() {
        val color = argb(40, 60, 120)
        val near = argb(43, 62, 122)
        assertFalse(AdaptiveColorLogic.differsSignificantly(color, near))
    }

    @Test
    fun `distinct colors are significant`() {
        val blue = argb(40, 60, 120)
        val orange = argb(240, 140, 30)
        assertTrue(AdaptiveColorLogic.differsSignificantly(blue, orange))
        assertTrue(AdaptiveColorLogic.colorDistance(blue, orange) > 100.0)
    }

    @Test
    fun `hsv conversion round trips within quantization error`() {
        val samples = listOf(
            argb(40, 60, 120),
            argb(240, 80, 80),
            argb(10, 200, 130),
            argb(1, 2, 3),
            argb(250, 250, 250)
        )
        for (color in samples) {
            val hsv = AdaptiveColorLogic.rgbToHsv(color)
            val roundTrip = AdaptiveColorLogic.hsvToArgb(hsv[0], hsv[1], hsv[2])
            val r1 = (color shr 16) and 0xFF
            val g1 = (color shr 8) and 0xFF
            val b1 = color and 0xFF
            val r2 = (roundTrip shr 16) and 0xFF
            val g2 = (roundTrip shr 8) and 0xFF
            val b2 = roundTrip and 0xFF
            assertTrue("red drift > 1", Math.abs(r1 - r2) <= 1)
            assertTrue("green drift > 1", Math.abs(g1 - g2) <= 1)
            assertTrue("blue drift > 1", Math.abs(b1 - b2) <= 1)
        }
    }

    @Test
    fun `hsv conversion known values`() {
        // Pure red.
        var hsv = AdaptiveColorLogic.rgbToHsv(argb(255, 0, 0))
        assertEquals(0f, hsv[0], 0.001f)
        assertEquals(1f, hsv[1], 0.001f)
        assertEquals(1f, hsv[2], 0.001f)
        // Pure green is 120 degrees.
        hsv = AdaptiveColorLogic.rgbToHsv(argb(0, 255, 0))
        assertEquals(120f, hsv[0], 0.001f)
        // Black has zero saturation and value.
        hsv = AdaptiveColorLogic.rgbToHsv(argb(0, 0, 0))
        assertEquals(0f, hsv[1], 0.001f)
        assertEquals(0f, hsv[2], 0.001f)
    }
}
