package com.streambridge.app

import com.streambridge.app.addon.AddonApi
import com.streambridge.app.addon.InstalledExtension
import com.streambridge.app.addon.SourceResult
import com.streambridge.app.addon.SourceStatus
import com.streambridge.app.addon.StremioAddonSource
import com.streambridge.app.addon.StreamSource
import com.streambridge.app.addon.StreamSourceAggregator
import com.streambridge.app.addon.model.AddonCatalog
import com.streambridge.app.addon.model.AddonManifest
import com.streambridge.app.addon.model.AddonMeta
import com.streambridge.app.addon.model.AddonStream
import com.streambridge.app.addon.model.CatalogResponse
import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.addon.model.StreamResponse
import com.streambridge.app.addon.plugin.ProviderFailure
import com.streambridge.app.addon.plugin.providerFailures
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progressive source aggregation: every source runs concurrently,
 * results are emitted the moment a source settles, and failures are
 * classified statuses — never exceptions that could break the others.
 */
class SourceAggregatorTest {

    // -----------------------------------------------------------------
    // Fakes
    // -----------------------------------------------------------------

    private class FakeSource(
        override val id: String,
        override val name: String = id,
        override val origin: String = "origin-$id",
        private val block: suspend () -> SourceStatus
    ) : StreamSource {
        override suspend fun resolve(): SourceResult = SourceResult(id, name, origin, block())
    }

    private fun playable(vararg urls: String): SourceStatus.Success =
        SourceStatus.Success(urls.map { playableStream(it) })

    private fun playableStream(url: String, label: String = "1080p WEB-DL") = StreamOption(
        id = "url:$url",
        label = label,
        description = null,
        url = url,
        infoHash = null,
        externalUrl = null,
        addonName = "test",
        isTorrent = false,
        isExternal = false,
        bingeGroup = ""
    )

    private class FakeAddonApi(
        private val responses: MutableMap<String, StreamResponse> = mutableMapOf(),
        private val delayMs: Long = 0L
    ) : AddonApi {
        val requestedUrls = mutableListOf<String>()

        override suspend fun fetchManifest(baseUrl: String): AddonManifest =
            AddonManifest(id = baseUrl, name = baseUrl, version = "1.0.0")

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
            if (delayMs > 0) delay(delayMs)
            return responses["$baseUrl/$id"] ?: StreamResponse()
        }

        override suspend fun fetchRaw(baseUrl: String, path: String): String = "{}"

        override suspend fun fetchSubtitles(
            baseUrl: String,
            type: String,
            id: String
        ): List<com.streambridge.app.addon.model.AddonSubtitle> = emptyList()
    }

    private fun extension(id: String) = InstalledExtension(
        addonId = id,
        name = id.replaceFirstChar { it.uppercase() },
        version = "1.0.0",
        description = "",
        baseUrl = "https://$id.example.com",
        logo = null,
        background = null,
        types = listOf("movie", "series"),
        resources = listOf("catalog", "stream"),
        idPrefixes = emptyList(),
        catalogs = listOf(AddonCatalog(type = "movie", id = "top")),
        enabled = true,
        installedAt = 0L,
        updatedAt = 0L
    )

    // -----------------------------------------------------------------
    // Aggregator behavior
    // -----------------------------------------------------------------

    @Test
    fun `sources execute concurrently not sequentially`() = runTest {
        val started = testScheduler.currentTime
        val results = StreamSourceAggregator().aggregate(
            listOf(
                FakeSource("a") { delay(1000); playable("https://a/v.mp4") },
                FakeSource("b") { delay(1000); playable("https://b/v.mp4") }
            )
        ) { }
        // Both sources ran in parallel: total virtual time is one
        // source's duration, not the sum of both.
        assertEquals(1000L, testScheduler.currentTime - started)
        assertEquals(2, results.size)
    }

    @Test
    fun `results are emitted progressively in completion order`() = runTest {
        val events = mutableListOf<SourceResult>()
        StreamSourceAggregator().aggregate(
            listOf(
                FakeSource("slow") { delay(300); playable("https://slow/v.mp4") },
                FakeSource("fast") { delay(100); playable("https://fast/v.mp4") }
            )
        ) { events.add(it) }

        // Loading first (source order), then results by completion time.
        assertEquals(
            listOf("slow", "fast", "fast", "slow"),
            events.map { it.sourceId }
        )
        assertEquals(
            listOf(true, true, false, false),
            events.map { it.status is SourceStatus.Loading }
        )
        assertEquals("origin-fast", events[2].origin)
    }

    @Test
    fun `one failing source never breaks the others`() = runTest {
        val events = mutableListOf<SourceResult>()
        // These fakes deliberately THROW (violating the StreamSource
        // contract) — the aggregator must still isolate them: a source
        // that crashes must never crash the resolution or the screen.
        val results = StreamSourceAggregator().aggregate(
            listOf(
                FakeSource("good") { playable("https://good/v.mp4") },
                FakeSource("network") { throw java.io.IOException("connection reset") },
                FakeSource("broken") { throw IllegalStateException("bad provider code") }
            )
        ) { events.add(it) }

        assertEquals(3, results.size)
        val network = results.first { it.sourceId == "network" }
        assertTrue(network.status is SourceStatus.NetworkError)
        val broken = results.first { it.sourceId == "broken" }
        assertTrue(broken.status is SourceStatus.Failed)
        assertEquals("bad provider code", (broken.status as SourceStatus.Failed).reason)
        val good = results.first { it.sourceId == "good" }
        assertTrue(good.usable)
        // The good source's result was still delivered progressively.
        assertTrue(events.any { it.sourceId == "good" && it.usable })
    }

    @Test
    fun `empty sources list emits nothing`() = runTest {
        var emissions = 0
        val results = StreamSourceAggregator().aggregate(emptyList()) { emissions++ }
        assertEquals(0, emissions)
        assertTrue(results.isEmpty())
    }

    // -----------------------------------------------------------------
    // Stremio addon source
    // -----------------------------------------------------------------

    @Test
    fun `addon source merges candidate ids and swallows per-candidate failures`() = runTest {
        val api = FakeAddonApi(
            mutableMapOf(
                "https://a.example.com/tt123" to StreamResponse(
                    listOf(AddonStream(url = "https://a.example.com/v.mp4", name = "1080p"))
                )
            )
        )
        val source = StremioAddonSource(api, extension("a"), "movie", listOf("tmdb:55", "tt123"))

        val result = source.resolve()

        // The first candidate has no scripted response (empty, no throw),
        // the second returns a stream: both are requested.
        assertEquals(
            listOf(
                "https://a.example.com|movie|tmdb:55",
                "https://a.example.com|movie|tt123"
            ),
            api.requestedUrls
        )
        assertTrue(result.usable)
        assertEquals(1, result.playableStreams.size)
        // Enrichment ran: the 1080p label was parsed.
        assertEquals(1080, result.playableStreams.first().resolution)
    }

    @Test
    fun `addon source reports honest empty when the addon has nothing`() = runTest {
        val source = StremioAddonSource(FakeAddonApi(), extension("a"), "movie", listOf("tt1"))
        val result = source.resolve()
        assertTrue(result.status is SourceStatus.Empty)
        assertTrue(!result.usable)
    }

    @Test
    fun `addon source classifies timeout`() = runTest {
        val api = FakeAddonApi(delayMs = 500)
        val source = StremioAddonSource(api, extension("slow"), "movie", listOf("tt1"), timeoutMs = 100)
        val result = source.resolve()
        assertTrue(result.status is SourceStatus.Timeout)
    }

    @Test
    fun `addon source with only torrents is not usable but is a success`() = runTest {
        val api = FakeAddonApi(
            mutableMapOf(
                "https://a.example.com/tt1" to StreamResponse(
                    listOf(AddonStream(infoHash = "abcdef0123", name = "720p"))
                )
            )
        )
        val source = StremioAddonSource(api, extension("a"), "movie", listOf("tt1"))
        val result = source.resolve()
        assertTrue(result.status is SourceStatus.Success)
        assertTrue(!result.usable) // nothing the built-in player can open
        assertEquals(1, (result.status as SourceStatus.Success).streams.size)
    }

    @Test
    fun `addon source identity uses stable ids`() {
        val source = StremioAddonSource(FakeAddonApi(), extension("a"), "movie", listOf("tt1"))
        assertEquals("addon:a", source.id)
        assertEquals("A", source.name)
        assertEquals("a.example.com", source.origin)
    }

    // -----------------------------------------------------------------
    // Failure reduction
    // -----------------------------------------------------------------

    @Test
    fun `provider failures map only terminal failures with bounded reasons`() {
        val failures = providerFailures(
            listOf(
                SourceResult("p1", "P1", "repo", playable("https://p1/v.mp4")),
                SourceResult("p2", "P2", "repo", SourceStatus.Empty),
                SourceResult("p3", "P3", "repo", SourceStatus.Loading),
                SourceResult("p4", "P4", "repo", SourceStatus.Timeout),
                SourceResult("p5", "P5", "repo", SourceStatus.NetworkError("host unreachable")),
                SourceResult("p6", "P6", "repo", SourceStatus.Failed("x".repeat(300)))
            )
        )
        assertEquals(
            listOf(
                ProviderFailure("P4", "repo", "timed out"),
                ProviderFailure("P5", "repo", "host unreachable"),
                ProviderFailure("P6", "repo", "x".repeat(120))
            ),
            failures
        )
    }

    @Test
    fun `blank failure reasons fall back to honest defaults`() {
        val failures = providerFailures(
            listOf(
                SourceResult("p1", "P1", "repo", SourceStatus.NetworkError("")),
                SourceResult("p2", "P2", "repo", SourceStatus.Failed(""))
            )
        )
        assertEquals(listOf("network error", "provider failed"), failures.map { it.reason })
    }
}
