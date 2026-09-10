package com.streambridge.app

import com.streambridge.app.player.Ipv4FirstDns
import com.streambridge.app.player.PlaybackHttp
import com.streambridge.app.player.PlaybackUserAgent
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class PlaybackHttpTest {

    @Test
    fun `Range is stripped from session-wide defaults`() {
        val defaults = PlaybackHttp.forDefaultRequestProperties(
            mapOf(
                "Referer" to "https://provider.example.com/",
                "Range" to "bytes=0-1",
                "Cookie" to "a=1"
            )
        )
        assertFalse(defaults.keys.any { it.equals("Range", ignoreCase = true) })
        assertEquals("https://provider.example.com/", defaults["Referer"])
        assertEquals("a=1", defaults["Cookie"])
    }

    @Test
    fun `session User-Agent wins over factory DataSpec UA`() {
        val merged = PlaybackHttp.mergeRequestHeaders(
            sessionHeaders = mapOf(
                "User-Agent" to "Source/1.0",
                "Referer" to "https://provider.example.com/"
            ),
            dataSpecHeaders = mapOf(
                "User-Agent" to PlaybackUserAgent.DEFAULT,
                "Range" to "bytes=0-1023"
            )
        )
        assertEquals("Source/1.0", merged["User-Agent"])
        assertEquals("https://provider.example.com/", merged["Referer"])
        assertEquals("bytes=0-1023", merged["Range"])
    }

    @Test
    fun `session headers apply to segment and key URLs equally`() {
        val session = mapOf(
            "Referer" to "https://provider.example.com/",
            "Origin" to "https://provider.example.com",
            "Cookie" to "session=abc"
        )
        val manifest = PlaybackHttp.mergeRequestHeaders(session, emptyMap())
        val segment = PlaybackHttp.mergeRequestHeaders(session, mapOf("Range" to "bytes=0-1"))
        val key = PlaybackHttp.mergeRequestHeaders(session, emptyMap())
        assertEquals(session["Referer"], manifest["Referer"])
        assertEquals(session["Cookie"], segment["Cookie"])
        assertEquals(session["Origin"], key["Origin"])
        assertEquals("bytes=0-1", segment["Range"])
    }

    @Test
    fun `effective UA matches Nuvio when the source supplies none`() {
        assertEquals(PlaybackUserAgent.DEFAULT, PlaybackHttp.effectiveUserAgent(emptyMap()))
        assertTrue(PlaybackUserAgent.DEFAULT.contains("Chrome/120.0.0.0"))
        assertEquals("Custom/1", PlaybackHttp.effectiveUserAgent(mapOf("user-agent" to "Custom/1")))
    }

    @Test
    fun `IPv4 addresses are returned before IPv6`() {
        val v6 = InetAddress.getByName("2001:db8::1")
        val v4 = InetAddress.getByName("192.0.2.1")
        assertTrue(v6 is Inet6Address)
        assertTrue(v4 is Inet4Address)
        val dns = Ipv4FirstDns(delegate = Dns { listOf(v6, v4) })
        val result = dns.lookup("cdn.example.com")
        assertEquals(listOf(v4, v6), result)
    }

    @Test
    fun `IPv6-only hosts still resolve`() {
        val v6 = InetAddress.getByName("2001:db8::2")
        val dns = Ipv4FirstDns(delegate = Dns { listOf(v6) })
        assertEquals(listOf(v6), dns.lookup("ipv6-only.example"))
    }
}
