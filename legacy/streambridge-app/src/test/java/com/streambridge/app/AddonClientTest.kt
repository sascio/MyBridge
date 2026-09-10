package com.streambridge.app

import com.streambridge.app.addon.AddonApi
import com.streambridge.app.addon.HttpAddonApi
import com.streambridge.app.addon.SbHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real HTTP round-trips against a local MockWebServer: verifies that the
 * addon client calls the exact Stremio protocol endpoints.
 */
class AddonClientTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AddonApi

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HttpAddonApi(SbHttpClient(), json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `manifest is fetched from manifest dot json`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"com.x","version":"1.0.0","name":"X","types":["movie"],
                   "resources":["catalog"],"catalogs":[{"type":"movie","id":"top"}]}"""
            )
        )
        val manifest = api.fetchManifest(server.url("/").toString())
        assertEquals("/manifest.json", server.takeRequest().path)
        assertEquals("com.x", manifest.id)
    }

    @Test
    fun `catalog with search extra`() = runTest {
        server.enqueue(MockResponse().setBody("""{"metas":[{"id":"tt1","type":"movie","name":"A"}]}"""))
        val response = api.fetchCatalog(
            server.url("/").toString(), "movie", "top", search = "big buck"
        )
        assertEquals("/catalog/movie/top/search=big%20buck.json", server.takeRequest().path)
        assertEquals(1, response.metas.size)
    }

    @Test
    fun `catalog with genre and skip extras`() = runTest {
        server.enqueue(MockResponse().setBody("""{"metas":[]}"""))
        api.fetchCatalog(
            server.url("/").toString(), "series", "all", genre = "Action", skip = 100
        )
        assertEquals("/catalog/series/all/genre=Action&skip=100.json", server.takeRequest().path)
    }

    @Test
    fun `meta 404 returns null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val meta = api.fetchMeta(server.url("/").toString(), "movie", "tt0000001")
        assertNull(meta)
    }

    @Test
    fun `meta success parses wrapper`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"meta":{"id":"tt1","type":"movie","name":"A",
                   "cast":["Someone"],"videos":[]}}"""
            )
        )
        val meta = api.fetchMeta(server.url("/").toString(), "movie", "tt1")
        assertEquals("/meta/movie/tt1.json", server.takeRequest().path)
        assertEquals("A", meta?.name)
        assertEquals(listOf("Someone"), meta?.cast)
    }

    @Test
    fun `stream request keeps episode video id intact`() = runTest {
        server.enqueue(MockResponse().setBody("""{"streams":[{"url":"https://x/v.mp4"}]}"""))
        val response = api.fetchStreams(server.url("/").toString(), "series", "tt0386676:2:4")
        assertEquals("/stream/series/tt0386676:2:4.json", server.takeRequest().path)
        assertTrue(response.streams.first().isDirect)
    }

    @Test
    fun `stream 404 returns empty list`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val response = api.fetchStreams(server.url("/").toString(), "movie", "tt0")
        assertEquals(0, response.streams.size)
    }
}
