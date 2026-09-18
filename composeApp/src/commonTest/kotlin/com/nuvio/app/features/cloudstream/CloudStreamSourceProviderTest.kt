package com.nuvio.app.features.cloudstream

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Provider execution: failure isolation and the no-fake-success guarantee. */
class CloudStreamSourceProviderTest {

    private fun plugin(
        id: String,
        compatibility: CloudStreamCompatibility = CloudStreamCompatibility.COMPATIBLE,
        reason: CloudStreamCompatibilityReason = CloudStreamCompatibilityReason.NONE,
    ) = CloudStreamPlugin(
        id = id,
        displayName = id,
        version = 1,
        description = null,
        authors = emptyList(),
        language = null,
        tvTypes = emptyList(),
        iconUrl = null,
        artifactUrl = "https://e.com/$id.cs3",
        repositoryUrl = null,
        fileSize = null,
        fileHash = null,
        apiVersion = 1,
        compatibility = compatibility,
        compatibilityReason = reason,
    )

    private val query = CloudStreamStreamQuery(url = "https://e.com/show", season = 1, episode = 2)

    /** Stands in for a sanctioned execution backend. Test-only. */
    private class FakeExecutor(
        private val onLoad: (CloudStreamPlugin) -> CloudStreamLinkResult,
    ) : CloudStreamPluginExecutor {
        override suspend fun search(plugin: CloudStreamPlugin, query: String) = emptyList<CloudStreamSearchResult>()
        override suspend fun loadEpisodes(plugin: CloudStreamPlugin, url: String) = emptyList<CloudStreamEpisode>()
        override suspend fun loadLinks(plugin: CloudStreamPlugin, query: CloudStreamStreamQuery) = onLoad(plugin)
        override suspend fun resolve(request: CloudStreamResolveRequest) = onLoad(request.plugin)
    }

    @Test
    fun `with no execution backend nothing is returned and nothing is faked`() = runBlocking {
        val streams = CloudStreamSourceProvider().resolveStreams(listOf(plugin("A")), query)
        assertTrue(streams.isEmpty())
    }

    @Test
    fun `unsupported plugins are never executed`() = runBlocking {
        var executed = false
        val provider = CloudStreamSourceProvider(
            FakeExecutor {
                executed = true
                CloudStreamLinkResult()
            },
        )
        val unsupported = plugin(
            "A",
            CloudStreamCompatibility.UNSUPPORTED,
            CloudStreamCompatibilityReason.REQUIRES_NATIVE_EXECUTION,
        )
        assertTrue(provider.resolveStreams(listOf(unsupported), query).isEmpty())
        assertTrue(!executed)
    }

    @Test
    fun `an executable plugin's links are mapped through the adapter`() = runBlocking {
        val provider = CloudStreamSourceProvider(
            FakeExecutor {
                CloudStreamLinkResult(
                    links = listOf(
                        CloudStreamLink(
                            name = "S1",
                            url = "https://cdn.e.com/v.m3u8",
                            referer = "https://e.com",
                            quality = 1080,
                            isM3u8 = true,
                        ),
                    ),
                    subtitles = listOf(CloudStreamSubtitleFile("en", "https://e.com/en.vtt")),
                )
            },
        )
        val stream = provider.resolveStreams(listOf(plugin("A")), query).single()
        assertEquals("https://cdn.e.com/v.m3u8", stream.url)
        assertEquals("hls", stream.streamType)
        assertEquals("https://e.com", stream.behaviorHints.proxyHeaders?.request?.get("Referer"))
        assertEquals(1, stream.externalSubtitles.size)
    }

    @Test
    fun `one throwing plugin does not break the others`() = runBlocking {
        val provider = CloudStreamSourceProvider(
            FakeExecutor { p ->
                if (p.id == "Broken") error("provider exploded")
                CloudStreamLinkResult(
                    links = listOf(CloudStreamLink(name = p.id, url = "https://cdn.e.com/${p.id}.mp4")),
                )
            },
        )
        val streams = provider.resolveStreams(
            listOf(plugin("Broken"), plugin("Working")),
            query,
        )
        assertEquals(1, streams.size)
        assertEquals("https://cdn.e.com/Working.mp4", streams.single().url)
    }

    @Test
    fun `a plugin returning nothing contributes nothing`() = runBlocking {
        val provider = CloudStreamSourceProvider(FakeExecutor { CloudStreamLinkResult() })
        assertTrue(provider.resolveStreams(listOf(plugin("A")), query).isEmpty())
    }

    @Test
    fun `results from several plugins are aggregated`() = runBlocking {
        val provider = CloudStreamSourceProvider(
            FakeExecutor { p ->
                CloudStreamLinkResult(
                    links = listOf(CloudStreamLink(name = p.id, url = "https://cdn.e.com/${p.id}.mp4")),
                )
            },
        )
        val streams = provider.resolveStreams(listOf(plugin("A"), plugin("B")), query)
        assertEquals(2, streams.size)
        assertEquals(
            listOf("cloudstream:A", "cloudstream:B"),
            streams.map { it.addonId },
        )
    }

    @Test
    fun `an empty plugin list resolves to no streams`() = runBlocking {
        val provider = CloudStreamSourceProvider(FakeExecutor { CloudStreamLinkResult() })
        assertTrue(provider.resolveStreams(emptyList(), query).isEmpty())
    }
}
