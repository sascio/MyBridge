package com.streambridge.app

import com.streambridge.app.addon.AddonApi
import com.streambridge.app.addon.InstalledExtension
import com.streambridge.app.addon.ResolvedSubtitle
import com.streambridge.app.addon.SubtitleResolver
import com.streambridge.app.addon.model.AddonCatalog
import com.streambridge.app.addon.model.AddonManifest
import com.streambridge.app.addon.model.AddonMeta
import com.streambridge.app.addon.model.AddonSubtitle
import com.streambridge.app.addon.model.CatalogResponse
import com.streambridge.app.addon.model.StreamResponse
import com.streambridge.app.addon.model.SubtitleResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleResolverTest {

    private class FakeSubtitleApi : AddonApi {
        val subtitleCalls = mutableListOf<Pair<String, String>>() // baseUrl to id
        var response: List<AddonSubtitle> = emptyList()

        override suspend fun fetchManifest(baseUrl: String): AddonManifest =
            AddonManifest()

        override suspend fun fetchCatalog(
            baseUrl: String,
            type: String,
            catalogId: String,
            search: String?,
            genre: String?,
            skip: Int?
        ): CatalogResponse = CatalogResponse()

        override suspend fun fetchMeta(baseUrl: String, type: String, id: String): AddonMeta? = null

        override suspend fun fetchStreams(
            baseUrl: String,
            type: String,
            id: String
        ): StreamResponse = StreamResponse()

        override suspend fun fetchSubtitles(
            baseUrl: String,
            type: String,
            id: String
        ): List<AddonSubtitle> {
            subtitleCalls += baseUrl to id
            return response
        }

        override suspend fun fetchRaw(baseUrl: String, path: String): String =
            """{"subtitles":[]}"""
    }

    private fun extension(id: String, resources: List<String> = listOf("subtitles")) =
        InstalledExtension(
            addonId = id,
            name = "Addon $id",
            version = "1.0.0",
            description = "",
            baseUrl = "https://$id.example.com",
            logo = null,
            background = null,
            types = listOf("movie", "series"),
            resources = resources,
            idPrefixes = emptyList(),
            catalogs = emptyList<AddonCatalog>(),
            enabled = true,
            installedAt = 0L,
            updatedAt = 0L
        )

    @Test
    fun `subtitles are merged across addons and deduped by url`() = runTest {
        val api = FakeSubtitleApi()
        api.response = listOf(
            AddonSubtitle(url = "https://a.example.com/en.vtt", lang = "eng"),
            AddonSubtitle(url = "https://shared.example.com/en.vtt", lang = "eng", label = "English")
        )
        val resolver = SubtitleResolver(api)
        val subtitles = resolver.resolveForMovie(
            listOf(extension("a"), extension("b")),
            "movie",
            "tt1",
            imdbId = null
        )
        // Two distinct URLs, the shared one appears once.
        assertEquals(2, subtitles.size)
        assertEquals(1, subtitles.count { it.subtitle.url == "https://shared.example.com/en.vtt" })
        assertEquals(2, api.subtitleCalls.size) // one per addon
    }

    @Test
    fun `episode subtitles try both meta id and imdb id`() = runTest {
        val api = FakeSubtitleApi()
        api.response = listOf(AddonSubtitle(url = "https://x/en.vtt", lang = "eng"))
        val resolver = SubtitleResolver(api)
        val subtitles = resolver.resolveForEpisode(
            listOf(extension("a")),
            "series",
            "custom",
            imdbId = "tt456",
            season = 1,
            episode = 2
        )
        assertEquals(1, subtitles.size) // same URL from both candidates dedupes to one
        assertEquals(
            listOf("custom:1:2", "tt456:1:2"),
            api.subtitleCalls.map { it.second }
        )
    }

    @Test
    fun `addons without subtitle resource are skipped`() = runTest {
        val api = FakeSubtitleApi()
        val resolver = SubtitleResolver(api)
        val subtitles = resolver.resolveForMovie(
            listOf(extension("a", resources = listOf("stream"))),
            "movie",
            "tt1",
            imdbId = null
        )
        assertTrue(subtitles.isEmpty())
        assertTrue(api.subtitleCalls.isEmpty())
    }

    @Test
    fun `failing subtitle addon does not break resolution`() = runTest {
        val api = object : AddonApi by FakeSubtitleApi() {
            override suspend fun fetchSubtitles(
                baseUrl: String,
                type: String,
                id: String
            ): List<AddonSubtitle> = throw java.io.IOException("down")
        }
        val resolver = SubtitleResolver(api)
        val subtitles = resolver.resolveForMovie(
            listOf(extension("a")),
            "movie",
            "tt1",
            imdbId = null
        )
        assertTrue(subtitles.isEmpty())
    }

    @Test
    fun `display labels prefer explicit label then language`() {
        val withLabel = ResolvedSubtitle(
            addonName = "A",
            subtitle = AddonSubtitle(url = "u", lang = "eng", label = "English")
        )
        val codeOnly = ResolvedSubtitle(
            addonName = "A",
            subtitle = AddonSubtitle(url = "u", lang = "en")
        )
        val blank = ResolvedSubtitle(
            addonName = "A",
            subtitle = AddonSubtitle(url = "u", lang = "")
        )
        assertEquals("English", withLabel.label)
        assertEquals("EN", codeOnly.label)
        assertEquals("Subtitle", blank.label)
    }
}
