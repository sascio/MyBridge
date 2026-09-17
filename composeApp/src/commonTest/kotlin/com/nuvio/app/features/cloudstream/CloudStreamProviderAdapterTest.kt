package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the CloudStream -> StreamBridge mapping, with emphasis on the
 * details that silently break playback when lost: Referer, User-Agent,
 * cookies and stream type.
 */
class CloudStreamProviderAdapterTest {

    private fun link(
        name: String = "Server 1",
        url: String = "https://cdn.example.com/video.mp4",
        referer: String? = null,
        quality: Int? = null,
        isM3u8: Boolean = false,
        isDash: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        source: String? = null,
    ) = CloudStreamLink(
        name = name,
        url = url,
        referer = referer,
        quality = quality,
        isM3u8 = isM3u8,
        isDash = isDash,
        headers = headers,
        cookies = cookies,
        source = source,
    )

    private fun adapt(vararg links: CloudStreamLink) =
        CloudStreamProviderAdapter.adaptLinks(
            links = links.toList(),
            pluginName = "TestProvider",
            pluginId = "TestProvider",
        )

    @Test
    fun `a link maps onto a playable StreamItem`() {
        val item = adapt(link(quality = 1080)).single()
        assertEquals("https://cdn.example.com/video.mp4", item.url)
        assertEquals("Server 1", item.name)
        assertEquals("TestProvider", item.addonName)
        assertEquals("cloudstream:TestProvider", item.addonId)
        assertNotNull(item.playableDirectUrl)
    }

    @Test
    fun `referer is preserved as a request header`() {
        val item = adapt(link(referer = "https://example.com/watch")).single()
        val headers = item.behaviorHints.proxyHeaders?.request
        assertNotNull(headers)
        assertEquals("https://example.com/watch", headers["Referer"])
    }

    @Test
    fun `user agent and custom headers are preserved`() {
        val item = adapt(
            link(headers = mapOf("User-Agent" to "Mozilla/5.0", "X-Token" to "abc")),
        ).single()
        val headers = item.behaviorHints.proxyHeaders?.request
        assertNotNull(headers)
        assertEquals("Mozilla/5.0", headers["User-Agent"])
        assertEquals("abc", headers["X-Token"])
    }

    @Test
    fun `cookies are serialised into a single Cookie header`() {
        val item = adapt(link(cookies = mapOf("sid" to "1", "pref" to "dark"))).single()
        val cookie = item.behaviorHints.proxyHeaders?.request?.get("Cookie")
        assertNotNull(cookie)
        assertTrue(cookie.contains("sid=1"))
        assertTrue(cookie.contains("pref=dark"))
        assertTrue(cookie.contains("; "))
    }

    @Test
    fun `an explicit Cookie header is not overwritten by the cookie map`() {
        val item = adapt(
            link(headers = mapOf("Cookie" to "explicit=1"), cookies = mapOf("other" to "2")),
        ).single()
        assertEquals("explicit=1", item.behaviorHints.proxyHeaders?.request?.get("Cookie"))
    }

    @Test
    fun `explicit headers override the derived referer`() {
        val item = adapt(
            link(referer = "https://old.example", headers = mapOf("Referer" to "https://new.example")),
        ).single()
        assertEquals(
            "https://new.example",
            item.behaviorHints.proxyHeaders?.request?.get("Referer"),
        )
    }

    @Test
    fun `a link with no headers carries no proxy headers`() {
        val item = adapt(link()).single()
        assertNull(item.behaviorHints.proxyHeaders)
    }

    @Test
    fun `hls dash and direct http are distinguished`() {
        assertEquals("hls", adapt(link(isM3u8 = true)).single().streamType)
        assertEquals("dash", adapt(link(isDash = true)).single().streamType)
        assertEquals("http", adapt(link()).single().streamType)
    }

    @Test
    fun `quality maps to conventional labels`() {
        assertEquals("4K", CloudStreamProviderAdapter.qualityLabel(2160))
        assertEquals("1080p", CloudStreamProviderAdapter.qualityLabel(1080))
        assertEquals("720p", CloudStreamProviderAdapter.qualityLabel(720))
        assertNull(CloudStreamProviderAdapter.qualityLabel(null))
        assertNull(CloudStreamProviderAdapter.qualityLabel(0))
    }

    @Test
    fun `source name falls back to the plugin name`() {
        assertEquals("TestProvider", adapt(link()).single().sourceName)
        assertEquals("Mirror", adapt(link(source = "Mirror")).single().sourceName)
    }

    @Test
    fun `blank and empty urls are dropped rather than producing dead streams`() {
        assertTrue(adapt(link(url = "   ")).isEmpty())
        assertTrue(CloudStreamProviderAdapter.adaptLinks(emptyList(), "P", "P").isEmpty())
    }

    @Test
    fun `subtitles map across with their headers`() {
        val subtitles = CloudStreamProviderAdapter.adaptSubtitles(
            listOf(
                CloudStreamSubtitleFile(
                    language = "en",
                    url = "https://example.com/en.vtt",
                    name = "English",
                    headers = mapOf("Referer" to "https://example.com"),
                ),
            ),
        )
        val subtitle = subtitles.single()
        assertEquals("en", subtitle.language)
        assertEquals("https://example.com/en.vtt", subtitle.url)
        assertEquals("https://example.com", subtitle.headers?.get("Referer"))
    }

    @Test
    fun `subtitles are attached to every adapted stream`() {
        val items = CloudStreamProviderAdapter.adaptLinks(
            links = listOf(link(), link(name = "Server 2")),
            pluginName = "TestProvider",
            pluginId = "TestProvider",
            subtitles = listOf(CloudStreamSubtitleFile("en", "https://example.com/en.vtt")),
        )
        assertEquals(2, items.size)
        assertTrue(items.all { it.externalSubtitles.size == 1 })
    }

    @Test
    fun `a subtitle with a blank url is dropped`() {
        assertTrue(
            CloudStreamProviderAdapter.adaptSubtitles(
                listOf(CloudStreamSubtitleFile("en", "  ")),
            ).isEmpty(),
        )
    }
}
