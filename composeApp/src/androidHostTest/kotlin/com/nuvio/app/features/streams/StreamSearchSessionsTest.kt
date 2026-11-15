package com.nuvio.app.features.streams

import android.app.Application
import com.nuvio.app.features.cloudstream.CloudStreamAggregatorBridge
import com.nuvio.app.features.servers.ServerStreamSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreamSearchSessionsTest {
    private var loads = 0

    @Before
    fun reset() = runBlocking {
        StreamSearchSessions.clear()
        loads = 0
    }

    @Test
    fun aSecondSearchForTheSameEpisodeReusesTheResults() = runBlocking {
        val first = StreamSearchSessions.search(PROFILE, targets(), forceRefresh = false).toList()
        val second = StreamSearchSessions.search(PROFILE, targets(), forceRefresh = false).toList()

        assertEquals(1, loads)
        assertEquals(first, second)
    }

    @Test
    fun forceRefreshStartsANewSearch() = runBlocking {
        StreamSearchSessions.search(PROFILE, targets(), forceRefresh = false).toList()
        StreamSearchSessions.search(PROFILE, targets(), forceRefresh = true).toList()

        assertEquals(2, loads)
    }

    @Test
    fun anotherEpisodeIsASeparateSearch() = runBlocking {
        StreamSearchSessions.search(PROFILE, targets(episode = 1), forceRefresh = false).toList()
        StreamSearchSessions.search(PROFILE, targets(episode = 2), forceRefresh = false).toList()

        assertEquals(2, loads)
    }

    @Test
    fun anotherProfileIsASeparateSearch() = runBlocking {
        StreamSearchSessions.search(PROFILE, targets(), forceRefresh = false).toList()
        StreamSearchSessions.search(PROFILE + 1, targets(), forceRefresh = false).toList()

        assertEquals(2, loads)
    }

    @Test
    fun aChangedSourceSetupIsASeparateSearch() = runBlocking {
        StreamSearchSessions.search(PROFILE, targets(), forceRefresh = false).toList()
        StreamSearchSessions.search(PROFILE, targets(sourceId = "server:other"), forceRefresh = false).toList()

        assertEquals(2, loads)
    }

    @Test
    fun droppedSearchSettlesPendingGroupsAndKeepsTheProviderFailureDetail() {
        val settled = StreamsUiState(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Server",
                    addonId = "server:test",
                    streams = emptyList(),
                    isLoading = true,
                    error = "Provider timed out",
                ),
            ),
            isAnyLoading = true,
        ).withoutPendingGroups()

        assertEquals(false, settled.isAnyLoading)
        assertEquals(false, settled.groups.single().isLoading)
        assertEquals(StreamsEmptyStateReason.StreamFetchFailed, settled.emptyStateReason)
        assertEquals("Provider timed out", settled.emptyStateDetail)
    }

    @Test
    fun debridCheckingUpdateDoesNotCountAsASecondSourceTask() {
        val group = AddonStreamGroup(
            addonName = "Addon",
            addonId = "addon:test",
            streams = emptyList(),
        )
        val emissions = listOf(
            StreamLoadCompletion.Addon(group, isTerminal = false),
            StreamLoadCompletion.Addon(group),
        )

        assertEquals(1, emissions.count { it.completesTask })
    }

    @Test
    fun cloudStreamProviderAndSearchMetadataArePartOfTheSourceConfiguration() {
        val provider = CloudStreamAggregatorBridge.Target(
            addonId = "cloudstream:example:movie",
            addonName = "Example",
            extensionId = "example",
            sourceId = "movie",
            contentType = "movie",
            iconUrl = null,
        )
        val original = StreamSearchTargets(
            type = "movie",
            videoId = "tt2026001",
            season = null,
            episode = null,
            streamAddons = emptyList(),
            serverSources = emptyList(),
            pluginProviderGroups = emptyList(),
            cloudStreamTargets = listOf(provider),
            cloudStreamTitle = "Example Film",
            cloudStreamYear = 2026,
        )
        val changedProvider = original.copyForTest(
            cloudStreamTargets = listOf(provider.copy(sourceId = "series")),
        )
        val changedMetadata = original.copyForTest(cloudStreamTitle = "Different Film")

        assertNotEquals(original.sourceConfiguration, changedProvider.sourceConfiguration)
        assertNotEquals(original.sourceConfiguration, changedMetadata.sourceConfiguration)
    }

    private fun StreamSearchTargets.copyForTest(
        cloudStreamTargets: List<CloudStreamAggregatorBridge.Target> = this.cloudStreamTargets,
        cloudStreamTitle: String? = this.cloudStreamTitle,
    ) = StreamSearchTargets(
        type = type,
        videoId = videoId,
        season = season,
        episode = episode,
        streamAddons = streamAddons,
        serverSources = serverSources,
        pluginProviderGroups = pluginProviderGroups,
        cloudStreamTargets = cloudStreamTargets,
        cloudStreamTitle = cloudStreamTitle,
        cloudStreamYear = cloudStreamYear,
        debridConfiguration = debridConfiguration,
    )

    private fun targets(episode: Int = 1, sourceId: String = "server:test") = StreamSearchTargets(
        type = "series",
        videoId = "tt2026:1:$episode",
        season = 1,
        episode = episode,
        streamAddons = emptyList(),
        serverSources = listOf(
            ServerStreamSource(addonId = sourceId, addonName = "Server", preferred = false) {
                loads++
                listOf(StreamItem(name = "1080p", url = "https://example.com/$episode.mkv", addonName = "Server", addonId = sourceId))
            },
        ),
        pluginProviderGroups = emptyList(),
    )

    private companion object {
        const val PROFILE = 1
    }
}
