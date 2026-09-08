package com.streambridge.app

import com.streambridge.app.addon.model.AddonManifest
import com.streambridge.app.addon.model.AddonMeta
import com.streambridge.app.addon.model.AddonMetaPreview
import com.streambridge.app.addon.model.CatalogResponse
import com.streambridge.app.addon.model.SubtitleResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol parsing for the fields added by the multi-source engine:
 * addon catalogs (community lists), transportUrl attribution, crew
 * (writer) and trailer links.
 */
class AddonCatalogParsingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `manifest addon catalogs are parsed`() {
        val manifest = json.decodeFromString(
            AddonManifest.serializer(),
            """
            {
              "id": "com.example.addons",
              "version": "1.0.0",
              "name": "Community",
              "types": ["movie"],
              "resources": ["catalog", "addon_catalog"],
              "addonCatalogs": [
                {"type": "movie", "id": "recommended", "name": "Recommended"},
                {"type": "series", "id": "popular"}
              ]
            }
            """.trimIndent()
        )
        assertEquals(2, manifest.addonCatalogs.size)
        assertEquals("recommended", manifest.addonCatalogs[0].id)
        assertEquals("Recommended", manifest.addonCatalogs[0].displayName)
        assertEquals("popular", manifest.addonCatalogs[1].displayName) // falls back to id
        assertEquals(manifest.addonCatalogs, manifest.effectiveAddonCatalogs)
    }

    @Test
    fun `catalog previews carry transportUrl for addon lists`() {
        val response = json.decodeFromString(
            CatalogResponse.serializer(),
            """
            {
              "metas": [
                {"id": "com.other.addon", "name": "Other", "transportUrl": "https://other.example.com/manifest.json"},
                {"id": "com.third.addon", "name": "Third"}
              ]
            }
            """.trimIndent()
        )
        val previews: List<AddonMetaPreview> = response.metas
        assertEquals("https://other.example.com/manifest.json", previews[0].transportUrl)
        assertEquals("", previews[1].transportUrl)
    }

    @Test
    fun `meta writer and trailer are parsed leniently`() {
        val meta = json.decodeFromString(
            AddonMeta.serializer(),
            """
            {
              "id": "tt0117731",
              "name": "Twister",
              "type": "movie",
              "writer": ["Michael Crichton", "Anne-Marie Martin"],
              "trailer": "https://www.youtube.com/watch?v=example",
              "cast": ["Helen Hunt"]
            }
            """.trimIndent()
        )
        assertEquals(listOf("Michael Crichton", "Anne-Marie Martin"), meta.writer)
        assertEquals("https://www.youtube.com/watch?v=example", meta.trailer)
        assertEquals(listOf("Helen Hunt"), meta.cast)
    }

    @Test
    fun `writer tolerates nested object entries`() {
        // Some addons send [{"name": "..."}] objects; the lenient
        // serializer extracts the name instead of crashing.
        val meta = json.decodeFromString(
            AddonMeta.serializer(),
            """{"id": "x", "name": "X", "writer": [{"name": "Anne"}, "Bob", 42]}"""
        )
        assertEquals(listOf("Anne", "Bob", "42"), meta.writer)
    }

    @Test
    fun `writer as a plain string degrades to empty instead of crashing`() {
        val meta = json.decodeFromString(
            AddonMeta.serializer(),
            """{"id": "x", "name": "X", "writer": "Anne"}"""
        )
        assertTrue(meta.writer.isEmpty())
    }

    @Test
    fun `subtitle response parses label and lang`() {
        val response = json.decodeFromString(
            SubtitleResponse.serializer(),
            """
            {"subtitles": [
              {"url": "https://s.example.com/en.vtt", "lang": "eng", "label": "English"},
              {"url": "https://s.example.com/de.vtt", "lang": "de"}
            ]}
            """.trimIndent()
        )
        assertEquals(2, response.subtitles.size)
        assertEquals("English", response.subtitles[0].displayLabel)
        assertEquals("DE", response.subtitles[1].displayLabel)
    }

    @Test
    fun `catalog extras default from the extra array`() {
        val manifest = json.decodeFromString(
            AddonManifest.serializer(),
            """
            {
              "id": "com.example", "version": "1", "name": "X",
              "types": ["movie"], "resources": ["catalog"],
              "catalogs": [
                {"type": "movie", "id": "cat",
                 "extra": [{"name": "genre", "isRequired": true, "options": ["Action"]}]}
              ]
            }
            """.trimIndent()
        )
        val catalog = manifest.catalogs.first()
        assertEquals(listOf("genre"), catalog.effectiveExtraSupported)
        assertEquals(listOf("genre"), catalog.effectiveExtraRequired)
        assertTrue(catalog.extra.first().options.contains("Action"))
    }
}
