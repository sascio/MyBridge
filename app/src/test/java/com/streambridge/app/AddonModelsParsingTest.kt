package com.streambridge.app

import com.streambridge.app.addon.model.AddonManifest
import com.streambridge.app.addon.model.AddonMeta
import com.streambridge.app.addon.model.CatalogResponse
import com.streambridge.app.addon.model.MetaResponse
import com.streambridge.app.addon.model.StreamResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonModelsParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `parses a full manifest with resource objects`() {
        val body = """
            {
              "id": "com.example.addon",
              "version": "1.2.3",
              "name": "Example",
              "description": "An example addon",
              "logo": "https://example.com/logo.png",
              "types": ["movie", "series"],
              "resources": [
                "catalog",
                { "name": "stream", "types": ["movie"], "idPrefixes": ["tt"] }
              ],
              "catalogs": [
                { "type": "movie", "id": "top", "name": "Top movies",
                  "extra": [ { "name": "search", "isRequired": false } ] },
                { "type": "series", "id": "all", "extraSupported": ["search", "genre"] }
              ],
              "idPrefixes": ["tt"]
            }
        """.trimIndent()

        val manifest = json.decodeFromString(AddonManifest.serializer(), body)
        assertEquals("com.example.addon", manifest.id)
        assertEquals("Example", manifest.name)
        assertEquals(listOf("catalog", "stream"), manifest.resources)
        assertEquals(2, manifest.catalogs.size)

        val first = manifest.catalogs[0]
        assertEquals("Top movies", first.displayName)
        assertEquals(listOf("search"), first.effectiveExtraSupported)

        val second = manifest.catalogs[1]
        assertEquals("all", second.displayName)
        assertEquals(listOf("search", "genre"), second.effectiveExtraSupported)
    }

    @Test
    fun `parses catalog response`() {
        val body = """
            {
              "metas": [
                {
                  "id": "tt1254207",
                  "type": "movie",
                  "name": "Big Buck Bunny",
                  "poster": "https://example.com/poster.jpg",
                  "background": "https://example.com/bg.jpg",
                  "description": "A large rabbit",
                  "releaseInfo": "2008",
                  "imdbRating": "8.1",
                  "genres": ["Animation", "Comedy"]
                },
                {
                  "id": "tt9999999",
                  "imdbId": "tt9999999",
                  "type": "series",
                  "name": "Numbers as strings",
                  "imdbRating": 7.4,
                  "runtime": 42
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString(CatalogResponse.serializer(), body)
        assertEquals(2, response.metas.size)

        val first = response.metas[0]
        assertEquals("Big Buck Bunny", first.name)
        assertEquals("8.1", first.imdbRating)
        assertEquals(listOf("Animation", "Comedy"), first.genres)

        // Numbers where the spec says strings must not break parsing.
        val second = response.metas[1]
        assertEquals("7.4", second.imdbRating)
        assertEquals("42", second.runtime)
    }

    @Test
    fun `parses meta with cast as objects and strings`() {
        val objectCast = """
            {
              "meta": {
                "id": "tt0386676",
                "type": "series",
                "name": "The Office",
                "cast": [ { "name": "Steve Carell", "character": "Michael" }, "Rainn Wilson" ],
                "director": [ "Greg Daniels" ],
                "videos": [
                  { "id": "tt0386676:1:1", "title": "Pilot", "season": 1, "episode": 1,
                    "released": "2005-03-24T00:00:00.000Z", "overview": "First day" },
                  { "id": "tt0386676:1:2", "title": "Diversity Day", "season": 1, "number": 2 }
                ]
              }
            }
        """.trimIndent()

        val response = json.decodeFromString(MetaResponse.serializer(), objectCast)
        val meta = checkNotNull(response.meta)
        assertEquals(listOf("Steve Carell", "Rainn Wilson"), meta.cast)
        assertEquals(listOf("Greg Daniels"), meta.director)
        assertEquals(2, meta.videos.size)
        assertEquals(1, meta.videos[0].episodeNumber)
        // 'number' fallback when 'episode' is missing
        assertEquals(2, meta.videos[1].episodeNumber)
    }

    @Test
    fun `meta response with null meta`() {
        val response = json.decodeFromString(MetaResponse.serializer(), """{"meta":null}""")
        assertNull(response.meta)
    }

    @Test
    fun `parses stream response with camel and snake fields`() {
        val body = """
            {
              "streams": [
                { "name": "1080p", "url": "https://example.com/file.mp4",
                  "behaviorHints": { "bingeGroup": "addon-1080" } },
                { "name": "720p", "infoHash": "ABCDEF0123456789", "fileIdx": 1,
                  "sources": ["tracker:http://t.example/announce"] },
                { "info_hash": "abcdef0123456789", "file_idx": 0 },
                { "title": "old style description", "url": "https://example.com/old.mp4" },
                { "ytId": "dQw4w9WgXcQ" }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString(StreamResponse.serializer(), body)
        assertEquals(5, response.streams.size)

        val direct = response.streams[0]
        assertTrue(direct.isDirect)
        assertEquals("addon-1080", direct.bingeGroup)

        val torrent = response.streams[1]
        assertTrue(torrent.isTorrent)
        assertEquals("abcdef0123456789", torrent.torrentHash.lowercase())
        assertEquals(1, torrent.torrentFileIndex)

        val snakeTorrent = response.streams[2]
        assertTrue(snakeTorrent.isTorrent)
        assertEquals("abcdef0123456789", snakeTorrent.torrentHash)

        val legacyTitle = response.streams[3]
        assertEquals("old style description", legacyTitle.displayDescription)

        val youtube = response.streams[4]
        assertTrue(youtube.isYouTube)
    }
}
