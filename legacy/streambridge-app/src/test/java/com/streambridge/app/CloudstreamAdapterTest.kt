package com.streambridge.app

import com.streambridge.app.addon.adapter.CloudstreamAdapter
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cloudstream repository BROWSING (repo.json + plugin lists). The
 * repository is parsed and listed read-only; its compiled .cs3 plugins
 * are never executed anywhere — `canServe` is permanently false.
 */
class CloudstreamAdapterTest {

    private lateinit var server: MockWebServer
    private val adapter = CloudstreamAdapter()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val base: String get() = server.url("/").toString().trimEnd('/')

    @Test
    fun `repo json with a relative plugin list is loaded and parsed`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "name": "Test Repo",
                  "description": "A repository used by unit tests",
                  "manifestVersion": 6,
                  "pluginLists": ["plugins.json"]
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "name": "ExampleSource",
                    "internalName": "ExampleProvider",
                    "version": 12,
                    "description": "Example provider for tests",
                    "repositoryUrl": "repo.json url",
                    "url": "https://example.com/plugin.cs3",
                    "authors": ["Tester"],
                    "tvTypes": ["Movie"],
                    "language": "en",
                    "iconUrl": "https://example.com/icon.png",
                    "status": 1,
                    "apiVersion": 39
                  }
                ]
                """.trimIndent()
            )
        )

        val repository = adapter.loadRepository("$base/repo.json")

        assertEquals("Test Repo", repository.name)
        assertEquals(1, repository.plugins.size)
        val plugin = repository.plugins.first()
        assertEquals("ExampleSource", plugin.name)
        assertEquals(12, plugin.version)
        assertEquals("en", plugin.language)
        assertEquals(listOf("Movie"), plugin.tvTypes)
        assertTrue(plugin.isOperational)
        // The plugin lists its compiled .cs3 file — which this adapter
        // must never install or execute.
        assertTrue(plugin.fileUrl.endsWith(".cs3"))
    }

    @Test
    fun `cloudstream plugins are never executable`() {
        val extension = com.streambridge.app.addon.InstalledExtension(
            addonId = "cloudstream",
            name = "Cloudstream",
            version = "1",
            description = "",
            baseUrl = "https://example.com/repo.json",
            logo = null,
            background = null,
            types = listOf("movie"),
            resources = listOf("catalog", "stream"),
            idPrefixes = emptyList(),
            catalogs = emptyList(),
            enabled = true,
            ecosystem = "cloudstream",
            installedAt = 0L,
            updatedAt = 0L
        )
        assertFalse(adapter.supportsExecution)
        assertFalse(adapter.canServe(extension, "stream", "movie", "tt0111161"))
        assertFalse(adapter.canServe(extension, "catalog", "movie", ""))
    }

    @Test
    fun `only repo and plugin list urls are accepted`() {
        assertTrue(adapter.acceptsUrl("https://example.com/repo.json"))
        assertTrue(adapter.acceptsUrl("https://example.com/plugins.json"))
        assertFalse(adapter.acceptsUrl("https://example.com/manifest.json"))
        assertFalse(adapter.acceptsUrl("ftp://example.com/repo.json"))
    }
}
