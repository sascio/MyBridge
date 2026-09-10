package com.streambridge.app

import com.streambridge.app.addon.StreamHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Header sanitization: addons are third parties — nothing corrupts our requests. */
class StreamHeadersTest {

    @Test
    fun `legitimate headers survive`() {
        val headers = StreamHeaders.sanitize(
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14)",
                "Referer" to "https://provider.example.com/",
                "Cookie" to "session=abc123",
                "Authorization" to "Bearer sometoken"
            )
        )
        assertEquals(4, headers.size)
        assertEquals("Mozilla/5.0 (Linux; Android 14)", headers["User-Agent"])
        assertEquals("Bearer sometoken", headers["Authorization"])
    }

    @Test
    fun `crlf injection is rejected`() {
        val headers = StreamHeaders.sanitize(
            mapOf("X-Evil" to "value\r\nSet-Cookie: poisoned=1")
        )
        assertTrue(headers.isEmpty())
    }

    @Test
    fun `control characters in values are rejected`() {
        assertTrue(StreamHeaders.sanitize(mapOf("X-Evil" to "bad\u0000value")).isEmpty())
        assertTrue(StreamHeaders.sanitize(mapOf("X-Evil" to "bad\u007Fvalue")).isEmpty())
    }

    @Test
    fun `malformed header names are rejected`() {
        assertTrue(StreamHeaders.sanitize(mapOf("Bad Name" to "v")).isEmpty()) // space
        assertTrue(StreamHeaders.sanitize(mapOf("Bad@Name" to "v")).isEmpty()) // illegal char
        assertTrue(StreamHeaders.sanitize(mapOf("" to "v")).isEmpty()) // empty
        assertTrue(
            StreamHeaders.sanitize(mapOf("X".padEnd(80, 'X') to "v")).isEmpty()
        ) // too long
    }

    @Test
    fun `oversized values are rejected`() {
        assertTrue(StreamHeaders.sanitize(mapOf("X-Long" to "a".repeat(9000))).isEmpty())
    }

    @Test
    fun `real-world cookie lengths survive`() {
        val cookie = "session=" + "x".repeat(600)
        val headers = StreamHeaders.sanitize(mapOf("Cookie" to cookie))
        assertEquals(cookie, headers["Cookie"])
    }

    @Test
    fun `header flooding is capped`() {
        val many = (1..40).associate { "X-H$it" to "v$it" }
        assertEquals(32, StreamHeaders.sanitize(many).size)
    }

    @Test
    fun `Range is stripped so it cannot ride on every segment request`() {
        val headers = StreamHeaders.sanitize(
            mapOf(
                "Referer" to "https://provider.example.com/",
                "Range" to "bytes=0-1",
                "Cookie" to "session=abc"
            )
        )
        assertEquals(
            mapOf(
                "Referer" to "https://provider.example.com/",
                "Cookie" to "session=abc"
            ),
            headers
        )
    }

    @Test
    fun `null and empty inputs yield empty maps`() {
        assertTrue(StreamHeaders.sanitize(null).isEmpty())
        assertTrue(StreamHeaders.sanitize(emptyMap()).isEmpty())
    }

    @Test
    fun `merge sanitizes both sides and lets additions win`() {
        val merged = StreamHeaders.merge(
            mapOf("Referer" to "https://old.example.com/", "X-Bad Name" to "x"),
            mapOf("Referer" to "https://new.example.com/")
        )
        assertEquals(mapOf("Referer" to "https://new.example.com/"), merged)
    }

    @Test
    fun `values are trimmed`() {
        assertEquals("v", StreamHeaders.sanitize(mapOf("X-Trim" to "  v  "))["X-Trim"])
    }
}
