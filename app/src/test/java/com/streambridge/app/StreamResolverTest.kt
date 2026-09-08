package com.streambridge.app

import com.streambridge.app.addon.AddonApi
import com.streambridge.app.addon.InstalledExtension
import com.streambridge.app.addon.StreamResolver
import com.streambridge.app.addon.model.AddonCatalog
import com.streambridge.app.addon.model.AddonManifest
import com.streambridge.app.addon.model.AddonMeta
import com.streambridge.app.addon.model.AddonStream
import com.streambridge.app.addon.model.CatalogResponse
import com.streambridge.app.addon.model.StreamResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stream fan-out logic against a scripted fake addon API.
 */
class StreamResolverTest {

    private class FakeAddonApi : AddonApi {
        val responses = mutableMapOf<String, StreamResponse>()
        val requestedUrls = mutableListOf<String>()
        var failFor: String? = null

        override suspend fun fetchManifest(baseUrl: String): AddonManifest {
            return AddonManifest(id = baseUrl, name = baseUrl, version = "1.0.0")
        }

        override suspend fun fetchCatalog(
            baseUrl: String,
            type: String,
            catalogId: String,
            search: String?,
            genre: String?,
            skip: Int?
        ): CatalogResponse = CatalogResponse()

        override suspend fun fetchMeta(baseUrl: String, type: String, id: String): AddonMeta? = null

        override suspend fun fetchStreams(baseUrl: String, type: String, id: String): StreamResponse {
            requestedUrls.add("$baseUrl|$type|$id")
            failFor?.let { if (baseUrl == it) throw java.io.IOException("down") }
            return responses["$baseUrl/$id"] ?: StreamResponse()
        }

        override suspend fun fetchRaw(baseUrl: String, path: String): String = "{}"

        override suspend fun fetchSubtitles(
            baseUrl: String,
            type: String,
            id: String
        ): List<com.streambridge.app.addon.model.AddonSubtitle> = emptyList()
    }

    private fun extension(
        id: String,
        stream: Boolean = true,
        enabled: Boolean = true
    ) = InstalledExtension(
        addonId = id,
        name = id.replaceFirstChar { it.uppercase() },
        version = "1.0.0",
        description = "",
        baseUrl = "https://$id.example.com",
        logo = null,
        background = null,
        types = listOf("movie", "series"),
        resources = listOfNotNull("catalog", if (stream) "stream" else null),
        idPrefixes = emptyList(),
        catalogs = listOf(AddonCatalog(type = "movie", id = "top")),
        enabled = enabled,
        installedAt = 0L,
        updatedAt = 0L
    )

    private fun directStream(url: String, name: String = "1080p") =
        AddonStream(url = url, name = name)

    private fun torrentStream(hash: String, name: String = "720p") =
        AddonStream(infoHash = hash, name = name)

    @Test
    fun `movie streams are merged from all enabled addons`() = runTest {
        val api = FakeAddonApi()
        api.responses["https://a.example.com/tt123"] = StreamResponse(
            listOf(directStream("https://a.example.com/video.mp4"))
        )
        api.responses["https://b.example.com/tt123"] = StreamResponse(
            listOf(directStream("https://b.example.com/video.mp4"), torrentStream("abcdef0123"))
        )
        val resolver = StreamResolver(api)
        val extensions = listOf(extension("a"), extension("b"), extension("c"))

        val streams = resolver.resolveMovie(extensions, "movie", "tt123", imdbId = null)

        assertEquals(3, streams.size)
        // Direct streams first, then torrents
        assertTrue(streams[0].isPlayable)
        assertTrue(streams[1].isPlayable)
        assertTrue(streams[2].isTorrent)
    }

    @Test
    fun `imdb id and meta id are both tried`() = runTest {
        val api = FakeAddonApi()
        api.responses["https://a.example.com/tt123"] = StreamResponse(
            listOf(directStream("https://a.example.com/v.mp4"))
        )
        val resolver = StreamResolver(api)
        resolver.resolveMovie(listOf(extension("a")), "movie", "tmdb:55", imdbId = "tt123")

        assertEquals(
            listOf(
                "https://a.example.com|movie|tmdb:55",
                "https://a.example.com|movie|tt123"
            ),
            api.requestedUrls
        )
    }

    @Test
    fun `duplicate streams across addons are deduped`() = runTest {
        val api = FakeAddonApi()
        api.responses["https://a.example.com/tt1"] = StreamResponse(
            listOf(directStream("https://cdn.example.com/video.mp4"))
        )
        api.responses["https://b.example.com/tt1"] = StreamResponse(
            listOf(directStream("https://cdn.example.com/video.mp4"))
        )
        val resolver = StreamResolver(api)
        val streams = resolver.resolveMovie(
            listOf(extension("a"), extension("b")), "movie", "tt1", null
        )
        assertEquals(1, streams.size)
    }

    @Test
    fun `failing addons do not break resolution`() = runTest {
        val api = FakeAddonApi()
        api.failFor = "https://bad.example.com"
        api.responses["https://good.example.com/tt1"] = StreamResponse(
            listOf(directStream("https://good.example.com/v.mp4"))
        )
        val resolver = StreamResolver(api)
        val streams = resolver.resolveMovie(
            listOf(extension("bad"), extension("good")), "movie", "tt1", null
        )
        assertEquals(1, streams.size)
    }

    @Test
    fun `episode resolution constructs imdb episode fallback id`() = runTest {
        val api = FakeAddonApi()
        api.responses["https://a.example.com/custom:1:2"] = StreamResponse(
            listOf(directStream("https://a.example.com/v.mp4"))
        )
        api.responses["https://a.example.com/tt456:1:2"] = StreamResponse(
            listOf(directStream("https://a.example.com/v2.mp4"))
        )
        val resolver = StreamResolver(api)
        val streams = resolver.resolveEpisode(
            listOf(extension("a")), "series", "custom:1:2", "tt456", 1, 2
        )
        assertEquals(2, streams.size)
        assertTrue(api.requestedUrls.contains("https://a.example.com|series|custom:1:2"))
        assertTrue(api.requestedUrls.contains("https://a.example.com|series|tt456:1:2"))
    }

    @Test
    fun `disabled and non-stream addons are skipped`() = runTest {
        val api = FakeAddonApi()
        api.responses["https://a.example.com/tt1"] = StreamResponse(
            listOf(directStream("https://a/v.mp4"))
        )
        val resolver = StreamResolver(api)
        val streams = resolver.resolveMovie(
            listOf(
                extension("a"),
                extension("disabled", enabled = false),
                extension("metaonly", stream = false)
            ),
            "movie", "tt1", null
        )
        assertEquals(1, streams.size)
        assertFalse(api.requestedUrls.any { it.startsWith("https://disabled") })
        assertFalse(api.requestedUrls.any { it.startsWith("https://metaonly") })
    }
}
