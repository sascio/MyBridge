package com.streambridge.app

import com.streambridge.app.player.MpvRequestOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The libmpv request-context translation — the reference app's exact
 * pattern: User-Agent through the dedicated option, everything else
 * through mpv's http-header-fields list syntax with escaped commas
 * (cookies contain commas). These tests also pin the redaction rules:
 * option strings must never surface in diagnostics or logs.
 */
class MpvRequestOptionsTest {

    @Test
    fun `user agent is extracted case-insensitively`() {
        assertEquals(
            "Mozilla/5.0 StreamBridge",
            MpvRequestOptions.userAgentFrom(mapOf("user-agent" to "Mozilla/5.0 StreamBridge"))
        )
        assertEquals(
            "StreamBridge/1.0",
            MpvRequestOptions.userAgentFrom(mapOf("User-Agent" to "StreamBridge/1.0"))
        )
    }

    @Test
    fun `missing user agent is null`() {
        assertNull(MpvRequestOptions.userAgentFrom(emptyMap()))
        assertNull(MpvRequestOptions.userAgentFrom(mapOf("User-Agent" to "  ")))
    }

    @Test
    fun `effective user agent falls back to the Media3 browser default`() {
        assertEquals(
            com.streambridge.app.player.PlaybackUserAgent.DEFAULT,
            MpvRequestOptions.effectiveUserAgent(emptyMap())
        )
        assertEquals(
            "Source/1.0",
            MpvRequestOptions.effectiveUserAgent(mapOf("User-Agent" to "Source/1.0"))
        )
    }

    @Test
    fun `loadfile args omit start option at position zero`() {
        assertEquals(
            listOf("loadfile", "https://cdn.example.com/a.mkv", "replace"),
            MpvRequestOptions.loadfileArgs("https://cdn.example.com/a.mkv", 0L)
        )
    }

    @Test
    fun `loadfile args include start option when resuming`() {
        assertEquals(
            listOf("loadfile", "https://cdn.example.com/a.mkv", "replace", "start=12.500"),
            MpvRequestOptions.loadfileArgs("https://cdn.example.com/a.mkv", 12_500L)
        )
    }

    @Test
    fun `referer origin and cookies survive the translation`() {
        val headers = mapOf(
            "Referer" to "https://provider.example.com/",
            "Origin" to "https://provider.example.com",
            "Cookie" to "sessionid=abc; theme=dark",
            "User-Agent" to "UA/1.0"
        )
        val fields = MpvRequestOptions.headerFieldsFrom(headers)
        assertEquals(
            "Referer: https://provider.example.com/," +
                "Origin: https://provider.example.com," +
                "Cookie: sessionid=abc; theme=dark",
            fields
        )
        assertFalse(fields.contains("User-Agent", ignoreCase = true))
    }

    @Test
    fun `commas inside header values are escaped for mpv list syntax`() {
        val fields = MpvRequestOptions.headerFieldsFrom(
            mapOf("Cookie" to "a=1, b=2")
        )
        assertEquals("Cookie: a=1\\, b=2", fields)
    }

    @Test
    fun `no headers produce empty fields`() {
        assertEquals("", MpvRequestOptions.headerFieldsFrom(emptyMap()))
    }

    // ---------------- position preservation ----------------

    @Test
    fun `fallback position becomes an mpv start option in seconds`() {
        assertEquals("start=745.300", MpvRequestOptions.startPositionOption(745_300L))
        assertEquals("start=1500.000", MpvRequestOptions.startPositionOption(1_500_000L))
    }

    @Test
    fun `fallback at zero starts from the beginning without an option`() {
        assertNull(MpvRequestOptions.startPositionOption(0L))
        assertNull(MpvRequestOptions.startPositionOption(-5L))
    }

    @Test
    fun `start option is locale-stable`() {
        // Must be a dot decimal even under comma-decimal locales.
        val option = MpvRequestOptions.startPositionOption(61_234L)
        assertEquals("start=61.234", option)
    }
}
