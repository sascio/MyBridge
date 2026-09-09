package com.streambridge.app

import com.streambridge.app.addon.plugin.NuvioManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nuvio repository manifests come from third parties and vary wildly in
 * shape: parsing must survive missing fields, extra fields, wrapped or
 * bare arrays — and never produce a provider without code to run.
 */
class NuvioManifestTest {

    @Test
    fun `parses a top-level array manifest`() {
        val result = NuvioManifest.parse(
            """
            [
              {
                "id": "vixsrc",
                "name": "VixSrc",
                "description": "Streams from VixSrc",
                "version": "1.2.0",
                "author": "someone",
                "supportedTypes": ["movie", "tv"],
                "filename": "providers/vixsrc.js",
                "enabled": true,
                "formats": ["mp4", "mkv"],
                "contentLanguage": ["en"],
                "limited": false,
                "unknownFutureField": {"nested": [1, 2, 3]}
              }
            ]
            """.trimIndent()
        )
        assertTrue(result is NuvioManifest.ParseResult.Valid)
        val providers = (result as NuvioManifest.ParseResult.Valid).providers
        assertEquals(1, providers.size)
        val provider = providers.first()
        assertEquals("vixsrc", provider.id)
        assertEquals("VixSrc", provider.displayName)
        assertEquals("providers/vixsrc.js", provider.filename)
        assertEquals(listOf("movie", "tv"), provider.supportedTypes)
        assertTrue(provider.supportsType("movie"))
        assertTrue(provider.supportsType("tv"))
        assertTrue(!provider.supportsType("anime"))
    }

    @Test
    fun `parses an object manifest with a providers array`() {
        val result = NuvioManifest.parse(
            """
            {
              "name": "Community repo",
              "providers": [
                { "id": "a", "name": "Alpha", "filename": "providers/a.js" },
                { "id": "b", "name": "Beta", "filename": "providers/b.js", "supportedTypes": "tv" }
              ]
            }
            """.trimIndent()
        )
        assertTrue(result is NuvioManifest.ParseResult.Valid)
        val providers = (result as NuvioManifest.ParseResult.Valid).providers
        assertEquals(2, providers.size)
        // A single string for supportedTypes is tolerated.
        assertTrue(providers[1].supportsType("tv"))
        assertTrue(!providers[1].supportsType("movie"))
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val result = NuvioManifest.parse("""[{ "filename": "providers/min.js" }]""")
        assertTrue(result is NuvioManifest.ParseResult.Valid)
        val provider = (result as NuvioManifest.ParseResult.Valid).providers.first()
        assertEquals("provider-0", provider.id)
        assertEquals("", provider.name)
        assertEquals("", provider.version)
        assertTrue(provider.supportedTypes.isEmpty())
        assertTrue(!provider.enabled)
        // No type restriction declared: supports everything.
        assertTrue(provider.supportsType("movie"))
        assertTrue(provider.supportsType("series"))
    }

    @Test
    fun `providers without a filename are dropped, not crashed on`() {
        val result = NuvioManifest.parse(
            """[{ "id": "no-code", "name": "No code" }, { "id": "ok", "filename": "ok.js" }]"""
        )
        assertTrue(result is NuvioManifest.ParseResult.Valid)
        assertEquals(1, (result as NuvioManifest.ParseResult.Valid).providers.size)
        assertEquals("ok", result.providers.first().id)
    }

    @Test
    fun `invalid json and empty manifests are reported, not thrown`() {
        assertTrue(NuvioManifest.parse("not json at all") is NuvioManifest.ParseResult.Invalid)
        assertTrue(NuvioManifest.parse("[]") is NuvioManifest.ParseResult.Invalid)
        assertTrue(NuvioManifest.parse("{}") is NuvioManifest.ParseResult.Invalid)
        assertTrue(NuvioManifest.parse("""{"plugins": []}""") is NuvioManifest.ParseResult.Invalid)
    }

    @Test
    fun `parses a scrapers-keyed repository with a name`() {
        // Shape of real Nuvio repositories (e.g. All-in-One-Nuvio),
        // which key their provider list under "scrapers".
        val result = NuvioManifest.parse(
            """
            {
              "name": "All-in-One-Nuvio",
              "version": "1.0.0",
              "scrapers": [
                {
                  "id": "1shows",
                  "name": "1Shows",
                  "description": "Movies | Series in 4k",
                  "version": "1.0.0",
                  "author": "A2R14N",
                  "supportedTypes": ["movie", "tv"],
                  "filename": "providers/1shows.js",
                  "enabled": true,
                  "hasSettings": false,
                  "formats": ["mkv", "mp4", "m3u8"],
                  "logo": "https://example.com/logo.png",
                  "contentLanguage": ["en", "hi"]
                }
              ]
            }
            """.trimIndent()
        )
        assertTrue(result is NuvioManifest.ParseResult.Valid)
        val valid = result as NuvioManifest.ParseResult.Valid
        assertEquals("All-in-One-Nuvio", valid.repositoryName)
        assertEquals(1, valid.providers.size)
        assertEquals("1shows", valid.providers[0].id)
        assertEquals("providers/1shows.js", valid.providers[0].filename)
        assertTrue(valid.providers[0].enabled)
        assertTrue(valid.providers[0].supportsType("tv"))
    }

    @Test
    fun `provider code urls resolve relative to the manifest`() {
        val manifestUrl = "https://raw.example.com/nuvio/main/manifest.json"
        assertEquals(
            "https://raw.example.com/nuvio/main/providers/vixsrc.js",
            NuvioManifest.providerCodeUrl(manifestUrl, "providers/vixsrc.js")
        )
        assertEquals(
            "https://raw.example.com/nuvio/main/vixsrc.js",
            NuvioManifest.providerCodeUrl(manifestUrl, "./vixsrc.js")
        )
        assertEquals(
            "https://cdn.other.com/absolute.js",
            NuvioManifest.providerCodeUrl(manifestUrl, "https://cdn.other.com/absolute.js")
        )
        assertEquals(null, NuvioManifest.providerCodeUrl("notaurl", "x.js"))
    }
}
