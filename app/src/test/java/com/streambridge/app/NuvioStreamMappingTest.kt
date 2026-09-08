package com.streambridge.app

import com.streambridge.app.addon.plugin.NuvioInstalledRepository
import com.streambridge.app.addon.plugin.NuvioRawStream
import com.streambridge.app.addon.plugin.toStreamOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nuvio provider results must normalize cleanly into Stream Bridge's
 * stream model: playable URLs only, sanitized headers, and quality
 * folded into the label so the existing picker enrichment sees it.
 */
class NuvioStreamMappingTest {

    private val repo = NuvioInstalledRepository(
        manifestUrl = "https://example.com/manifest.json",
        name = "Example repo",
        providers = emptyList(),
        enabledProviderIds = emptyList(),
        addedAt = 0L,
        lastUpdated = 0L
    )

    private val provider = NuvioInstalledRepository.StoredProvider(
        id = "vixsrc",
        name = "VixSrc",
        description = "Video provider",
        version = "1.0.0",
        author = "author",
        supportedTypes = listOf("movie", "tv"),
        filename = "providers/vixsrc.js",
        hasSettings = false,
        formats = emptyList(),
        logo = "",
        contentLanguage = listOf("en"),
        limited = false,
        resources = emptyList()
    )

    @Test
    fun `a full stream object maps with headers and quality`() {
        val option = NuvioRawStream(
            name = "VixSrc",
            title = "1080p Multi",
            url = "https://cdn.example.com/video.mp4?token=abc",
            quality = "1080p",
            format = "mp4",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Referer" to "https://provider.example.com/"
            )
        ).toStreamOption(provider, repo)!!

        assertTrue(option.isPlayable)
        assertEquals("https://cdn.example.com/video.mp4?token=abc", option.url)
        assertEquals("1080p Multi", option.label) // contains quality already, not duplicated
        assertEquals("VixSrc", option.addonName)
        assertEquals("Mozilla/5.0", option.headers["User-Agent"])
        assertEquals("https://provider.example.com/", option.headers["Referer"])
        assertTrue(option.id.startsWith("nuvio:"))
    }

    @Test
    fun `quality missing from the title is folded in for the picker`() {
        val option = NuvioRawStream(
            name = "VixSrc",
            title = "Multi audio",
            url = "https://cdn.example.com/v.mp4",
            quality = "720p",
            format = "mp4",
            headers = emptyMap()
        ).toStreamOption(provider, repo)!!
        assertEquals("Multi audio · 720p", option.label)
    }

    @Test
    fun `quality already in the title is not duplicated`() {
        val option = NuvioRawStream(
            name = "VixSrc",
            title = "1080p Stream",
            url = "https://cdn.example.com/v.mp4",
            quality = "1080p",
            format = "mp4",
            headers = emptyMap()
        ).toStreamOption(provider, repo)!!
        assertEquals("1080p Stream", option.label)
    }

    @Test
    fun `blank titles fall back to the provider name`() {
        val option = NuvioRawStream(
            name = "",
            title = "",
            url = "https://cdn.example.com/v.mp4",
            quality = "",
            format = "m3u8",
            headers = emptyMap()
        ).toStreamOption(provider, repo)!!
        assertEquals("VixSrc", option.label)
    }

    @Test
    fun `non http urls are rejected`() {
        assertNull(
            NuvioRawStream("n", "t", "magnet:?xt=urn:btih:abc", "", "", emptyMap())
                .toStreamOption(provider, repo)
        )
        assertNull(
            NuvioRawStream("n", "t", "javascript:alert(1)", "", "", emptyMap())
                .toStreamOption(provider, repo)
        )
        assertNull(
            NuvioRawStream("n", "t", "ftp://x/file", "", "", emptyMap())
                .toStreamOption(provider, repo)
        )
    }

    @Test
    fun `hostile headers are dropped by sanitization`() {
        val option = NuvioRawStream(
            name = "n",
            title = "t",
            url = "https://cdn.example.com/v.mp4",
            quality = "",
            format = "",
            headers = mapOf(
                "User-Agent" to "ok-agent",
                "X-Weird" to "line\r\nInjected: 1", // CRLF injection
                "Bad Name" to "no spaces allowed", // invalid header name
                "X-Empty" to "   ", // blank value
                ("X-" + "n".repeat(200)) to "oversized name"
            )
        ).toStreamOption(provider, repo)!!

        // The sanitizer is format-based: only well-formed, clean pairs
        // survive (names ^[A-Za-z0-9-]{1,64}$, printable values).
        assertEquals("ok-agent", option.headers["User-Agent"])
        assertTrue(!option.headers.containsKey("X-Weird"))
        assertTrue(!option.headers.containsKey("Bad Name"))
        assertTrue(!option.headers.containsKey("X-Empty"))
        assertEquals(1, option.headers.size)
    }

    @Test
    fun `enabled providers can be toggled per repository`() {
        val withEnabled = repo.copy(
            providers = listOf(provider),
            enabledProviderIds = listOf("vixsrc")
        )
        assertTrue(withEnabled.isEnabled("vixsrc"))
        assertTrue(!withEnabled.isEnabled("other"))
        assertEquals(1, withEnabled.enabledProviders.size)
    }
}
