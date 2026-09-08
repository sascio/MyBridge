package com.streambridge.app

import com.streambridge.app.addon.InstalledExtension
import com.streambridge.app.addon.adapter.CloudstreamAdapter
import com.streambridge.app.addon.adapter.NuvioAddonAdapter
import com.streambridge.app.addon.adapter.StremioAddonAdapter
import com.streambridge.app.addon.model.AddonCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability filtering: the adapter layer decides which addons may be
 * asked for which resource, honoring resources, types and idPrefixes.
 */
class AdapterCapabilityTest {

    private fun extension(
        resources: List<String>,
        types: List<String> = emptyList(),
        idPrefixes: List<String> = emptyList(),
        ecosystem: String = "stremio",
        adult: Boolean = false
    ) = InstalledExtension(
        addonId = "com.test",
        name = "Test",
        version = "1.0.0",
        description = "",
        baseUrl = "https://test.example.com",
        logo = null,
        background = null,
        types = types,
        resources = resources,
        idPrefixes = idPrefixes,
        catalogs = listOf(AddonCatalog(type = "movie", id = "top")),
        enabled = true,
        ecosystem = ecosystem,
        adultContent = adult,
        installedAt = 0L,
        updatedAt = 0L
    )

    private val stremio = StremioAddonAdapter.instance

    @Test
    fun `resource must be declared`() {
        val streamOnly = extension(resources = listOf("stream"))
        assertTrue(stremio.canServe(streamOnly, "stream", "movie", "tt1"))
        assertFalse(stremio.canServe(streamOnly, "meta", "movie", "tt1"))
        assertFalse(stremio.canServe(streamOnly, "subtitles", "movie", "tt1"))
    }

    @Test
    fun `types scope requests`() {
        val moviesOnly = extension(resources = listOf("stream"), types = listOf("movie"))
        assertTrue(stremio.canServe(moviesOnly, "stream", "movie", "tt1"))
        assertFalse(stremio.canServe(moviesOnly, "stream", "series", "tt1"))
    }

    @Test
    fun `id prefixes scope id-based resources`() {
        val imdbOnly = extension(resources = listOf("stream"), idPrefixes = listOf("tt"))
        assertTrue(stremio.canServe(imdbOnly, "stream", "movie", "tt0133093"))
        assertFalse(stremio.canServe(imdbOnly, "stream", "movie", "kt-998877"))
        // Catalog requests are not id-scoped.
        assertTrue(stremio.canServe(imdbOnly, "catalog", "movie", "top"))
    }

    @Test
    fun `disabled extensions are never asked`() {
        val disabled = extension(resources = listOf("stream")).copy(enabled = false)
        assertFalse(stremio.canServe(disabled, "stream", "movie", "tt1"))
    }

    @Test
    fun `nuvio adapter reuses stremio rules and surfaces adult flag`() {
        val nuvio = NuvioAddonAdapter.instance
        val adult = extension(resources = listOf("stream"), ecosystem = "nuvio", adult = true)
        assertTrue(nuvio.canServe(adult, "stream", "movie", "tt1"))
        assertTrue(nuvio.isAdult(adult))
        assertFalse(nuvio.isAdult(adult.copy(adultContent = false)))
    }

    @Test
    fun `cloudstream plugins are never executable`() {
        val cloudstream = CloudstreamAdapter.instance
        val anyExtension = extension(resources = listOf("stream", "meta", "catalog"))
        assertFalse(cloudstream.supportsExecution)
        assertFalse(cloudstream.canServe(anyExtension, "stream", "movie", "tt1"))
    }

    @Test
    fun `cloudstream accepts repo and plugin list urls`() {
        val cloudstream = CloudstreamAdapter.instance
        assertTrue(cloudstream.acceptsUrl("https://example.com/repo.json"))
        assertTrue(cloudstream.acceptsUrl("https://example.com/plugins.json"))
        assertFalse(cloudstream.acceptsUrl("https://example.com/manifest.json"))
    }
}
