package com.streambridge.app

import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.player.PlaybackPlanning
import com.streambridge.app.addon.StreamMimeTypes
import com.streambridge.app.player.resolveRequestHeadersFor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playback planning layer keeps ONE source's full request context
 * attached to exactly that source: Referer, User-Agent, cookies and
 * any other sanitized header travel with the stream into the player
 * and never leak anywhere else. The container MIME is resolved from
 * real evidence only.
 */
class PlaybackPlanningTest {

    private fun option(
        url: String,
        headers: Map<String, String> = emptyMap(),
        mimeType: String = ""
    ) = StreamOption(
        id = "test:$url",
        label = "Test stream",
        description = null,
        url = url,
        infoHash = null,
        externalUrl = null,
        addonName = "TestSource",
        isTorrent = false,
        isExternal = false,
        bingeGroup = "",
        headers = headers,
        mimeType = mimeType
    )

    // -----------------------------------------------------------------
    // Request context preservation (headers)
    // -----------------------------------------------------------------

    @Test
    fun `referer user-agent and cookies are preserved for their own source`() = runTest {
        val headers = mapOf(
            "Referer" to "https://provider.example.com/watch",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) Test",
            "Cookie" to "session=abc123; cf_clearance=xyz",
            "Origin" to "https://provider.example.com"
        )
        val preparation = PlaybackPlanning.planPlayback(
            option("https://cdn.example.com/v.mp4", headers)
        ) { _, _ -> null }

        assertEquals(headers, preparation.headers)
    }

    @Test
    fun `dangerous header values are sanitized but legit ones survive`() = runTest {
        val preparation = PlaybackPlanning.planPlayback(
            option(
                "https://cdn.example.com/v.mp4",
                mapOf(
                    "Referer" to "https://good.example.com/",
                    "X-Bad" to "line1\r\nHost: evil.example.com",
                    "Authorization" to "Bearer legittoken"
                )
            )
        ) { _, _ -> null }
        assertEquals(
            mapOf(
                "Referer" to "https://good.example.com/",
                "Authorization" to "Bearer legittoken"
            ),
            preparation.headers
        )
    }

    @Test
    fun `planning carries only this option's headers - no global context`() = runTest {
        // Two different sources with different contexts, planned in
        // sequence: each preparation contains exactly its own headers.
        val a = PlaybackPlanning.planPlayback(
            option("https://a.example.com/v.mp4", mapOf("Referer" to "https://a.example.com/"))
        ) { _, _ -> null }
        val b = PlaybackPlanning.planPlayback(
            option("https://b.example.com/v.mp4", mapOf("Referer" to "https://b.example.com/"))
        ) { _, _ -> null }
        assertEquals(mapOf("Referer" to "https://a.example.com/"), a.headers)
        assertEquals(mapOf("Referer" to "https://b.example.com/"), b.headers)
    }

    // -----------------------------------------------------------------
    // Session header resolution (per-request scope in the player)
    // -----------------------------------------------------------------

    @Test
    fun `session headers apply to every request of the playback`() {
        val session = mapOf(
            "Referer" to "https://provider.example.com/",
            "User-Agent" to "TestUA"
        )
        // The manifest URL, a segment URL on another host, a key URL —
        // every request of the active playback carries the context.
        assertEquals(session, resolveRequestHeadersFor("https://cdn.example.com/master.m3u8", session, emptyMap()))
        assertEquals(session, resolveRequestHeadersFor("https://seg.other-host.net/chunk-42.ts", session, emptyMap()))
        assertEquals(session, resolveRequestHeadersFor("https://keys.example.com/hls.key", session, emptyMap()))
    }

    @Test
    fun `a per-url override replaces - never merges with - the session context`() {
        val session = mapOf(
            "Referer" to "https://provider.example.com/",
            "Cookie" to "session=abc"
        )
        val override = mapOf("Authorization" to "Bearer sub")
        val resolved = resolveRequestHeadersFor(
            "https://subs.example.com/en.vtt",
            session,
            mapOf("https://subs.example.com/en.vtt" to override)
        )
        assertEquals(override, resolved)
        // The stream URL itself keeps the session context.
        assertEquals(
            session,
            resolveRequestHeadersFor(
                "https://cdn.example.com/master.m3u8",
                session,
                mapOf("https://subs.example.com/en.vtt" to override)
            )
        )
    }

    // -----------------------------------------------------------------
    // MIME resolution (evidence-based only)
    // -----------------------------------------------------------------

    @Test
    fun `provider hint wins for manifests`() = runTest {
        val preparation = PlaybackPlanning.planPlayback(
            option(
                "https://cdn.example.com/d/9f8e7d6c5b4a?token=x",
                mimeType = StreamMimeTypes.APPLICATION_M3U8
            )
        ) { _, _ -> null }
        assertEquals(StreamMimeTypes.APPLICATION_M3U8, preparation.mimeType)
        assertTrue(preparation.isManifest)
    }

    @Test
    fun `url evidence beats a progressive provider hint`() = runTest {
        val preparation = PlaybackPlanning.planPlayback(
            option(
                "https://cdn.example.com/playlist.m3u8?token=x",
                mimeType = StreamMimeTypes.VIDEO_MP4
            )
        ) { _, _ -> null }
        assertEquals(StreamMimeTypes.APPLICATION_M3U8, preparation.mimeType)
    }

    @Test
    fun `url inference resolves extension-less manifests`() = runTest {
        val preparation = PlaybackPlanning.planPlayback(
            option("https://cdn.example.com/hls/9f8e7d6c5b4a/token")
        ) { _, _ -> null }
        assertEquals(StreamMimeTypes.APPLICATION_M3U8, preparation.mimeType)
    }

    @Test
    fun `probe runs only when url and hint carry no evidence`() = runTest {
        val probed = mutableListOf<String>()
        // Evidence in URL: no probe needed.
        PlaybackPlanning.planPlayback(option("https://cdn.example.com/x.m3u8")) { url, _ ->
            probed.add(url); null
        }
        // Evidence from provider hint: no probe needed.
        PlaybackPlanning.planPlayback(
            option("https://cdn.example.com/d/abc", mimeType = StreamMimeTypes.APPLICATION_MPD)
        ) { url, _ -> probed.add(url); null }
        assertEquals(emptyList<String>(), probed)

        // No evidence anywhere: the probe is consulted (with the
        // stream's own headers) and its answer is used.
        val headers = mapOf("Referer" to "https://provider.example.com/")
        var seenHeaders: Map<String, String>? = null
        val preparation = PlaybackPlanning.planPlayback(
            option("https://cdn.example.com/d/9f8e7d6c5b4a?token=x", headers)
        ) { probeUrl, probeHeaders ->
            probed.add(probeUrl)
            seenHeaders = probeHeaders
            StreamMimeTypes.APPLICATION_M3U8
        }
        assertEquals(listOf("https://cdn.example.com/d/9f8e7d6c5b4a?token=x"), probed)
        assertEquals(headers, seenHeaders)
        assertEquals(StreamMimeTypes.APPLICATION_M3U8, preparation.mimeType)
    }

    @Test
    fun `probe failure leaves the mime honestly unknown`() = runTest {
        val preparation = PlaybackPlanning.planPlayback(
            option("https://cdn.example.com/d/9f8e7d6c5b4a")
        ) { _, _ -> null }
        assertNull(preparation.mimeType)
        assertTrue(!preparation.isManifest)
    }

    @Test
    fun `non http urls are never probed`() = runTest {
        var probed = 0
        val preparation = PlaybackPlanning.planPlayback(
            option("rtsp://media.example.com/live")
        ) { _, _ -> probed++; null }
        assertEquals(0, probed)
        assertNull(preparation.mimeType)
    }

    @Test
    fun `a throwing probe is contained`() = runTest {
        val preparation = PlaybackPlanning.planPlayback(
            option("https://cdn.example.com/d/9f8e7d6c5b4a")
        ) { _, _ -> throw java.io.IOException("probe failed") }
        assertNull(preparation.mimeType)
    }

    @Test
    fun `torrent and external options plan with an empty url`() = runTest {
        val preparation = PlaybackPlanning.planPlayback(
            option("", mapOf("Referer" to "https://x.example.com/"))
        ) { _, _ -> null }
        assertEquals("", preparation.url)
        assertNull(preparation.mimeType)
    }

    // -----------------------------------------------------------------
    // Bounded alternate-source fallback
    // -----------------------------------------------------------------

    @Test
    fun `next alternate skips already tried sources and unplayable ones`() {
        val alternates = listOf(
            option("https://a.example.com/1", mimeType = "video/mp4"),
            option("https://b.example.com/2").copy(url = null), // not playable
            option("https://c.example.com/3") // playable, untried
        )
        val next = PlaybackPlanning.nextAlternate(
            tried = setOf("test:https://a.example.com/1"),
            alternates = alternates
        )
        assertEquals("https://c.example.com/3", next?.url)

        // Everything tried: null — never loops, never hammers.
        assertNull(
            PlaybackPlanning.nextAlternate(
                tried = setOf(
                    "test:https://a.example.com/1",
                    "test:https://c.example.com/3"
                ),
                alternates = alternates
            )
        )
        assertNull(PlaybackPlanning.nextAlternate(tried = emptySet(), alternates = emptyList()))
    }
}
