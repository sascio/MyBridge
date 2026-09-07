package com.streambridge.app

import com.streambridge.app.core.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `clock formatting`() {
        assertEquals("0:00", TimeFormat.msToClock(0))
        assertEquals("0:05", TimeFormat.msToClock(5000))
        assertEquals("1:32", TimeFormat.msToClock(92_000))
        assertEquals("1:00:00", TimeFormat.msToClock(3_600_000))
        assertEquals("1:32:45", TimeFormat.msToClock(5_565_000))
    }

    @Test
    fun `duration labels`() {
        assertEquals("", TimeFormat.msToDurationLabel(0))
        assertEquals("45m", TimeFormat.msToDurationLabel(45 * 60_000L))
        assertEquals("1h 32m", TimeFormat.msToDurationLabel(92 * 60_000L))
        assertEquals("2h", TimeFormat.msToDurationLabel(120 * 60_000L))
    }

    @Test
    fun `runtime parsing`() {
        assertEquals(116, TimeFormat.runtimeToMinutes("116"))
        assertEquals(116, TimeFormat.runtimeToMinutes("116 min"))
        assertEquals(116, TimeFormat.runtimeToMinutes("116m"))
        assertEquals(0, TimeFormat.runtimeToMinutes(null))
        assertEquals(0, TimeFormat.runtimeToMinutes(""))
        assertEquals(0, TimeFormat.runtimeToMinutes("unknown"))
    }

    @Test
    fun `runtime hours and minutes`() {
        assertEquals(115, TimeFormat.runtimeToMinutes("1h 55m"))
        assertEquals(60, TimeFormat.runtimeToMinutes("1h"))
    }

    @Test
    fun `minutes to label`() {
        assertEquals("", TimeFormat.minutesToLabel(0))
        assertEquals("5m", TimeFormat.minutesToLabel(5))
        assertEquals("1h 56m", TimeFormat.minutesToLabel(116))
    }

    @Test
    fun `progress fraction`() {
        assertEquals(0f, TimeFormat.progressFraction(500, 0), 0.001f)
        assertEquals(0.5f, TimeFormat.progressFraction(50, 100), 0.001f)
        assertEquals(1f, TimeFormat.progressFraction(150, 100), 0.001f)
    }

    @Test
    fun `finished threshold`() {
        assertTrue(TimeFormat.isFinished(96, 100, 95))
        assertFalse(TimeFormat.isFinished(50, 100, 95))
        assertFalse(TimeFormat.isFinished(50, 0, 95))
    }
}
