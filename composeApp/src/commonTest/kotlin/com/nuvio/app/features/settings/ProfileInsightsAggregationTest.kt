package com.nuvio.app.features.settings

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressSourceNuvioSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileInsightsAggregationTest {
    private fun source(
        id: String,
        connected: Boolean = true,
        libraryItems: List<LibraryItem> = emptyList(),
        watchedItems: List<WatchedItem> = emptyList(),
        progressEntries: List<WatchProgressEntry> = emptyList(),
        fullyWatchedSeriesKeys: Set<String> = emptySet(),
        libraryStatus: ProfileInsightsFetchStatus = ProfileInsightsFetchStatus.AVAILABLE,
        watchedStatus: ProfileInsightsFetchStatus = ProfileInsightsFetchStatus.AVAILABLE,
        progressStatus: ProfileInsightsFetchStatus = ProfileInsightsFetchStatus.AVAILABLE,
        libraryError: String? = null,
        watchedError: String? = null,
        progressError: String? = null,
    ) = ProfileInsightsSourceSnapshot(
        sourceId = id,
        displayName = id,
        connected = connected,
        library = ProfileInsightsCapabilityState(libraryStatus, libraryItems.size, libraryError),
        watched = ProfileInsightsCapabilityState(watchedStatus, watchedItems.size, watchedError),
        progress = ProfileInsightsCapabilityState(progressStatus, progressEntries.size, progressError),
        libraryItems = libraryItems,
        watchedItems = watchedItems,
        progressEntries = progressEntries,
        fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
    )

    private fun libraryItem(
        id: String,
        type: String = "movie",
        name: String = id,
        releaseInfo: String? = null,
        genres: List<String> = emptyList(),
        imdbId: String? = null,
        tmdbId: Int? = null,
        savedAt: Long = 1L,
    ) = LibraryItem(
        id = id,
        type = type,
        name = name,
        releaseInfo = releaseInfo,
        genres = genres,
        imdbId = imdbId,
        tmdbId = tmdbId,
        savedAtEpochMs = savedAt,
    )

    private fun watched(
        id: String,
        type: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        name: String = id,
        markedAt: Long = 1L,
        providerItemId: String? = null,
    ) = WatchedItem(
        id = id,
        type = type,
        name = name,
        season = season,
        episode = episode,
        markedAtEpochMs = markedAt,
        trackingProviderItemId = providerItemId,
    )

    private fun progress(
        id: String,
        source: String,
        type: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        progressPercent: Float? = null,
        updatedAt: Long = 1L,
        providerItemId: String? = null,
        completed: Boolean = false,
    ) = WatchProgressEntry(
        contentType = type,
        parentMetaId = id,
        parentMetaType = type,
        videoId = if (season != null && episode != null) "$id:$season:$episode" else id,
        title = id,
        seasonNumber = season,
        episodeNumber = episode,
        lastPositionMs = positionMs,
        durationMs = durationMs,
        lastUpdatedEpochMs = updatedAt,
        isCompleted = completed,
        progressPercent = progressPercent,
        source = source,
        trackingProviderItemId = providerItemId,
    )

    private fun aggregate(vararg sources: ProfileInsightsSourceSnapshot) =
        aggregateProfileInsights(sources.toList())

    private fun metrics(
        aggregate: ProfileInsightsAggregate,
        facts: Map<String, ProfileTitleFacts> = emptyMap(),
        now: Long = 2_000_000_000L,
        recentWindow: Long = 7L * 24L * 60L * 60L * 1_000L,
    ) = calculateProfileInsightsMetrics(aggregate, facts, now, recentWindow)

    private fun facts(
        genres: List<String> = emptyList(),
        runtimeMinutes: Int? = null,
        episodeRuntimes: Map<Long, Int> = emptyMap(),
        partial: Boolean = false,
    ) = ProfileTitleFacts(
        genres = genres,
        runtimeMinutes = runtimeMinutes,
        episodeRuntimeMinutes = episodeRuntimes,
        resolvedAtEpochMs = 1L,
        partial = partial,
    )

    @Test
    fun duplicateProviderRecordsReconcileThroughSharedExternalIds() {
        val firstLibrary = libraryItem(
            id = "tt0000001",
            name = "Example Film",
            releaseInfo = "2024",
            imdbId = "tt0000001",
            tmdbId = 42,
        )
        val secondLibrary = libraryItem(
            id = "tmdb:42",
            name = "Example Film",
            releaseInfo = "2024",
            tmdbId = 42,
            savedAt = 2L,
        )
        val aggregate = aggregate(
            source("trakt", libraryItems = listOf(firstLibrary), watchedItems = listOf(watched("tt0000001", providerItemId = "tmdb:42"))),
            source("simkl", libraryItems = listOf(secondLibrary), watchedItems = listOf(watched("tmdb:42", providerItemId = "imdb:tt0000001"))),
        )

        val result = metrics(aggregate)

        assertEquals(1, result.libraryCount)
        assertEquals(1, result.watchedMovieCount)
        assertEquals(1, result.watchedTitleCount)
        assertEquals("tt0000001", aggregate.libraryItems.single().id)
        assertEquals("tt0000001", aggregate.watchedItems.single().id)
    }

    @Test
    fun savedLibraryItemsDoNotCountAsWatched() {
        val aggregate = aggregate(
            source("nuvio", libraryStatus = ProfileInsightsFetchStatus.LOCAL, libraryItems = listOf(libraryItem("tt0000002"))),
        )

        val result = metrics(aggregate)

        assertEquals(1, result.libraryCount)
        assertEquals(0, result.watchedMovieCount)
        assertEquals(0, result.watchedTitleCount)
        assertEquals(0, result.completedSeriesCount)
    }

    @Test
    fun episodeHistoryCountsEpisodesAndOneOngoingSeriesNotACompletedSeries() {
        val aggregate = aggregate(
            source(
                "trakt",
                watchedItems = listOf(
                    watched("tmdb:200", type = "series", season = 1, episode = 1),
                    watched("tmdb:200", type = "series", season = 1, episode = 2, markedAt = 2L),
                ),
            ),
        )

        val result = metrics(aggregate)

        assertEquals(2, result.episodesWatchedCount)
        assertEquals(1, result.watchedTitleCount)
        assertEquals(1, result.ongoingSeriesCount)
        assertEquals(0, result.completedSeriesCount)
        assertEquals(0, result.watchedMovieCount)
    }

    @Test
    fun explicitNuvioWholeSeriesMarkerCompletesOneSeriesWithoutCountingExtraEpisodes() {
        val fullKey = watchedItemKey("series", "tmdb:201")
        val aggregate = aggregate(
            source(
                "nuvio",
                watchedItems = listOf(watched("tmdb:201", type = "series", season = 1, episode = 1)),
                fullyWatchedSeriesKeys = setOf(fullKey),
            ),
        )

        val result = metrics(aggregate)

        assertEquals(1, result.completedSeriesCount)
        assertEquals(0, result.ongoingSeriesCount)
        assertEquals(1, result.episodesWatchedCount)
        assertEquals(1, result.watchedTitleCount)
    }

    @Test
    fun disconnectedProviderCacheIsExcludedAtTheAccountBoundary() {
        val aggregate = aggregate(
            source("trakt", connected = false, watchedItems = listOf(watched("tt0000003"))),
        )

        assertTrue(aggregate.watchedItems.isEmpty())
        assertEquals(0, metrics(aggregate).watchedTitleCount)
    }

    @Test
    fun connectedStaleProviderCacheRemainsUsableAndReportsPartialFailure() {
        val stale = source(
            "simkl",
            watchedItems = listOf(watched("tt0000004")),
            watchedStatus = ProfileInsightsFetchStatus.STALE,
            watchedError = "timeout",
        )
        val aggregate = aggregate(stale)
        val state = ProfileInsightsSourcesState(profileId = 3, sources = listOf(stale))

        assertEquals(1, metrics(aggregate).watchedMovieCount)
        assertEquals(1, state.unavailableConnectedCapabilityCount)
        assertTrue(state.hasPartialFailure)
    }

    @Test
    fun aniListIdentityAndIMDbMappingReconcileAcrossSourcesWithoutTreatingItsListAsHistory() {
        val aniListItem = libraryItem(
            id = "202",
            name = "Example Anime",
            releaseInfo = "2024",
            imdbId = "tt0012345",
        ).copy(
            trackingProviderId = "anilist",
            trackingProviderItemId = "anilist:202",
        )
        val traktItem = libraryItem(
            id = "trakt:900",
            name = "Example Anime",
            releaseInfo = "2024",
            imdbId = "tt0012345",
        )
        val aggregate = aggregate(
            source("anilist", libraryItems = listOf(aniListItem)),
            source("trakt", libraryItems = listOf(traktItem)),
        )

        assertEquals(1, aggregate.libraryItems.size)
        assertTrue(aggregate.watchedItems.isEmpty())
    }

    @Test
    fun aniListListStatusAndProgressNeverBecomeWatchHistory() {
        val aggregate = aggregate(
            source(
                "anilist",
                libraryItems = listOf(libraryItem("mal:123", type = "series")),
                watchedItems = listOf(watched("mal:123", type = "series", season = 1, episode = 12)),
                progressEntries = listOf(progress("mal:123", source = "anilist_list_progress", type = "series", season = 1, episode = 12)),
                watchedStatus = ProfileInsightsFetchStatus.UNSUPPORTED,
                progressStatus = ProfileInsightsFetchStatus.UNSUPPORTED,
            ),
        )

        assertEquals("series", profileInsightsKind("anime"))
        assertEquals(1, metrics(aggregate).libraryCount)
        assertEquals(0, metrics(aggregate).watchedTitleCount)
        assertTrue(aggregate.watchedItems.isEmpty())
        assertTrue(aggregate.progressEntries.isEmpty())
    }

    @Test
    fun mdblistLibraryMembershipAndHistoryAreSeparateCapabilities() {
        val aggregate = aggregate(
            source(
                "mdblist",
                libraryItems = listOf(libraryItem("tmdb:301", type = "movie", name = "Saved only")),
                progressEntries = listOf(progress("tmdb:301", source = "mdblist_history", type = "movie", positionMs = 50_000L)),
            ),
        )

        assertEquals(1, metrics(aggregate).libraryCount)
        assertEquals(0, metrics(aggregate).watchedMovieCount)
        assertTrue(aggregate.progressEntries.isEmpty())
    }

    @Test
    fun providerDerivedNextUpSeedsAreNotPlaybackOrWatchedHistory() {
        val aggregate = aggregate(
            source(
                "wetrakr",
                progressEntries = listOf(progress("tmdb:302", source = "wetrakr_progress", type = "series", season = 1, episode = 2, positionMs = 45_000L)),
            ),
        )

        assertTrue(aggregate.progressEntries.isEmpty())
        assertEquals(0, metrics(aggregate).continueCount)
        assertEquals(0, metrics(aggregate).watchedTitleCount)
    }

    @Test
    fun playbackProgressIsNotWatchedAndIsExplicitlyQualifiedAsEstimate() {
        val aggregate = aggregate(
            source(
                "trakt",
                progressEntries = listOf(
                    progress("tt0000005", source = "trakt_playback", positionMs = 30L * 60_000L, durationMs = 60L * 60_000L, progressPercent = 50f),
                ),
            ),
        )

        val result = metrics(aggregate)

        assertEquals(1, result.continueCount)
        assertEquals(0, result.watchedMovieCount)
        assertEquals(0, result.watchedTitleCount)
        assertEquals(30L * 60_000L, result.watchTime.totalMs)
        assertEquals(result.watchTime.totalMs, result.watchTime.estimatedMs)
        assertEquals(0L, result.watchTime.recordedMs)
    }

    @Test
    fun nuvioRemoteProgressIsIncludedWithoutBecomingWatchedHistory() {
        val aggregate = aggregate(
            source(
                "nuvio",
                progressEntries = listOf(
                    progress(
                        "tt0000050",
                        source = WatchProgressSourceNuvioSync,
                        positionMs = 30_000L,
                        durationMs = 60_000L,
                    ),
                ),
            ),
        )

        val result = metrics(aggregate)

        assertEquals(1, aggregate.progressEntries.size)
        assertEquals(1, result.continueCount)
        assertEquals(0, result.watchedTitleCount)
        assertEquals(30_000L, result.watchTime.estimatedMs)
    }

    @Test
    fun completionThresholdDoesNotPromoteNinetyPercentPositionToFullRuntimeOrHistory() {
        val aggregate = aggregate(
            source(
                "nuvio",
                progressEntries = listOf(
                    progress("tt0000006", source = "local", positionMs = 54L * 60_000L, durationMs = 60L * 60_000L, progressPercent = 90f, completed = true),
                ),
            ),
        )

        val result = metrics(aggregate)

        assertEquals(0, result.continueCount)
        assertEquals(0, result.watchedMovieCount)
        assertEquals(0, result.watchedTitleCount)
        assertEquals(54L * 60_000L, result.watchTime.totalMs)
        assertEquals(54L * 60_000L, result.watchTime.estimatedMs)
    }

    @Test
    fun missingRuntimeAndMetadataNeverUseDefaultMovieOrEpisodeDurations() {
        val aggregate = aggregate(
            source(
                "trakt",
                watchedItems = listOf(watched("tt0000007")),
                progressEntries = listOf(progress("tt0000008", source = "trakt_playback", progressPercent = 65f)),
            ),
        )

        val result = metrics(aggregate)

        assertEquals(0L, result.watchTime.totalMs)
        assertEquals(0, result.tasteKnownTitleCount)
        assertTrue(result.tasteSegments.isEmpty())
    }

    @Test
    fun recordedPositionWithoutRuntimeIsRetainedOnlyAsAnEstimate() {
        val aggregate = aggregate(
            source("nuvio", progressEntries = listOf(progress("tt0000009", source = "local", positionMs = 7L * 60_000L))),
        )

        val result = metrics(aggregate)

        assertEquals(7L * 60_000L, result.watchTime.totalMs)
        assertEquals(7L * 60_000L, result.watchTime.estimatedMs)
        assertEquals(1, result.continueCount)
    }

    @Test
    fun historyRuntimeAndDuplicateProviderProgressAreNotDoubleCounted() {
        val watched = watched("tt0000010", providerItemId = "tmdb:410")
        val progress = progress(
            "tmdb:410",
            source = "trakt_playback",
            positionMs = 50L * 60_000L,
            durationMs = 100L * 60_000L,
            progressPercent = 50f,
            providerItemId = "imdb:tt0000010",
        )
        val aggregate = aggregate(
            source("trakt", watchedItems = listOf(watched)),
            source("mdblist", progressEntries = listOf(progress)),
        )

        val result = metrics(
            aggregate,
            facts = mapOf("movie:tt0000010" to facts(runtimeMinutes = 100)),
        )

        assertEquals(1, result.watchedTitleCount)
        assertEquals(100L * 60_000L, result.watchTime.totalMs)
        assertEquals(100L * 60_000L, result.watchTime.estimatedMs)
    }

    @Test
    fun partialMetadataCannotSupplyGenresOrRuntime() {
        val aggregate = aggregate(source("trakt", watchedItems = listOf(watched("tt0000011"))))

        val result = metrics(
            aggregate,
            facts = mapOf("movie:tt0000011" to facts(genres = listOf("Action"), runtimeMinutes = 90, partial = true)),
        )

        assertEquals(0L, result.watchTime.totalMs)
        assertEquals(0, result.tasteKnownTitleCount)
        assertTrue(result.tasteSegments.isEmpty())
    }

    @Test
    fun genresAreNormalizedAndWeightedOncePerUniqueWatchedTitle() {
        val aggregate = aggregate(
            source(
                "trakt",
                libraryItems = listOf(
                    libraryItem("tt0000012", name = "Movie", genres = listOf("Drama", "drama", "Action & Adventure")),
                    libraryItem("tt0000013", type = "series", name = "Series", genres = listOf("Drama")),
                ),
                watchedItems = listOf(
                    watched("tt0000012", name = "Movie"),
                    watched("tt0000013", type = "series", season = 1, episode = 1, name = "Series"),
                ),
            ),
        )

        val result = metrics(aggregate)
        val shares = result.tasteSegments.associate { it.label to it.share }

        assertEquals(2, result.tasteKnownTitleCount)
        assertEquals(3, shares.size)
        assertTrue(kotlin.math.abs((shares["Drama"] ?: 0f) - (2f / 3f)) < 0.0001f)
        assertTrue(kotlin.math.abs((shares["Action"] ?: 0f) - (1f / 6f)) < 0.0001f)
        assertTrue(kotlin.math.abs((shares["Adventure"] ?: 0f) - (1f / 6f)) < 0.0001f)
        assertTrue(kotlin.math.abs(result.tasteSegments.sumOf { it.share.toDouble() } - 1.0) < 0.0001)
    }

    @Test
    fun tasteDenominatorIncludesOnlyUniqueTitlesWithVerifiedGenres() {
        val aggregate = aggregate(
            source(
                "simkl",
                libraryItems = listOf(libraryItem("tt0000014", genres = listOf("Comedy"))),
                watchedItems = listOf(watched("tt0000014"), watched("tt0000015"), watched("tt0000015", markedAt = 2L)),
            ),
        )

        val result = metrics(aggregate)

        assertEquals(2, result.watchedMovieCount)
        assertEquals(1, result.tasteKnownTitleCount)
        assertEquals(1, result.tasteSegments.size)
        assertEquals(1f, result.tasteSegments.single().share)
    }

    @Test
    fun titleFallbackRequiresExactNormalizedTitleAndKnownYear() {
        val aggregate = aggregate(
            source(
                "trakt",
                libraryItems = listOf(
                    libraryItem("local-a", name = "The Example!", releaseInfo = "2020"),
                    libraryItem("local-b", name = "the-example", releaseInfo = "2020"),
                    libraryItem("local-c", name = "The Example!", releaseInfo = null),
                    libraryItem("local-d", name = "The Example!", releaseInfo = "2021"),
                ),
            ),
        )

        assertEquals(3, aggregate.libraryItems.size)
    }

    @Test
    fun ambiguousTitleYearDoesNotMergeSeparateExternalIdentityGroups() {
        val aggregate = aggregate(
            source(
                "trakt",
                libraryItems = listOf(
                    libraryItem("tt0000016", name = "Same Film", releaseInfo = "2020", imdbId = "tt0000016"),
                    libraryItem("tt0000017", name = "Same Film", releaseInfo = "2020", imdbId = "tt0000017"),
                    libraryItem("local-id", name = "Same Film", releaseInfo = "2020"),
                ),
            ),
        )

        assertEquals(3, aggregate.libraryItems.size)
    }

    @Test
    fun refreshAgeAndUserInitiatedBypassAreDeterministic() {
        assertFalse(shouldRefreshProfileInsightsCache(950L, nowEpochMs = 1_000L, maxAgeMs = 100L, userInitiated = false))
        assertTrue(shouldRefreshProfileInsightsCache(900L, nowEpochMs = 1_000L, maxAgeMs = 100L, userInitiated = false))
        assertTrue(shouldRefreshProfileInsightsCache(999L, nowEpochMs = 1_000L, maxAgeMs = 100L, userInitiated = true))
        assertTrue(shouldRefreshProfileInsightsCache(null, nowEpochMs = 1_000L, maxAgeMs = 100L, userInitiated = false))
    }

    @Test
    fun staleRefreshResponsesCannotCrossProfileGenerationOrProviderAccount() {
        val current = isProfileInsightsRefreshCurrent(
            requestedProfileId = 2,
            activeProfileId = 2,
            requestedGeneration = 8L,
            currentGeneration = 8L,
            requestedAccountGeneration = 4L,
            currentAccountGeneration = 4L,
            isAuthenticated = true,
        )
        val changedAccount = isProfileInsightsRefreshCurrent(
            requestedProfileId = 2,
            activeProfileId = 2,
            requestedGeneration = 8L,
            currentGeneration = 8L,
            requestedAccountGeneration = 4L,
            currentAccountGeneration = 5L,
            isAuthenticated = true,
        )

        assertTrue(current)
        assertFalse(changedAccount)
        assertTrue(
            isProfileInsightsRefreshCurrent(
                requestedProfileId = 2,
                activeProfileId = 2,
                requestedGeneration = 8L,
                currentGeneration = 8L,
                requestedAccountGeneration = 4L,
                currentAccountGeneration = 4L,
                isAuthenticated = true,
                requestedAccountIdentity = "simkl:42",
                currentAccountIdentity = "simkl:42",
            ),
        )
        assertFalse(
            isProfileInsightsRefreshCurrent(
                requestedProfileId = 2,
                activeProfileId = 2,
                requestedGeneration = 8L,
                currentGeneration = 8L,
                requestedAccountGeneration = 4L,
                currentAccountGeneration = 4L,
                isAuthenticated = true,
                requestedAccountIdentity = "simkl:42",
                currentAccountIdentity = "simkl:43",
            ),
        )
        assertFalse(isProfileInsightsRefreshCurrent(2, 3, 8L, 8L, 4L, 4L, true))
        assertFalse(isProfileInsightsRefreshCurrent(2, 2, 8L, 9L, 4L, 4L, true))
        assertFalse(isProfileInsightsRefreshCurrent(2, 2, 8L, 8L, 4L, 4L, false))
    }

    @Test
    fun capabilityStatusDistinguishesUnsupportedDisconnectedAndPartialFailure() {
        assertEquals(ProfileInsightsFetchStatus.UNSUPPORTED, profileInsightsFetchStatus(false, false, false, false, false, null))
        assertEquals(ProfileInsightsFetchStatus.NOT_CONNECTED, profileInsightsFetchStatus(false, true, false, false, false, null))
        assertEquals(ProfileInsightsFetchStatus.FAILED, profileInsightsFetchStatus(true, true, false, false, false, "offline"))
        assertEquals(ProfileInsightsFetchStatus.STALE, profileInsightsFetchStatus(true, true, true, false, true, "offline"))
        assertEquals(ProfileInsightsFetchStatus.REFRESHING, profileInsightsFetchStatus(true, true, false, true, false, null))
        assertEquals(ProfileInsightsFetchStatus.AVAILABLE, profileInsightsFetchStatus(true, true, true, false, false, null))
        assertEquals(ProfileInsightsFetchStatus.NOT_LOADED, profileInsightsFetchStatus(true, true, false, false, false, null))
    }

    @Test
    fun unsupportedCapabilitiesWithExplanatoryMessagesAreNotPartialFailures() {
        val aniList = source(
            "anilist",
            watchedStatus = ProfileInsightsFetchStatus.UNSUPPORTED,
            progressStatus = ProfileInsightsFetchStatus.UNSUPPORTED,
            watchedError = "AniList lists are not watch history",
            progressError = "List progress is not playback history",
        )
        val state = ProfileInsightsSourcesState(profileId = 4, sources = listOf(aniList))

        assertEquals(0, state.unavailableConnectedCapabilityCount)
        assertFalse(state.hasPartialFailure)

        val failed = source(
            "trakt",
            watchedStatus = ProfileInsightsFetchStatus.FAILED,
            watchedError = "offline",
        )
        val partial = ProfileInsightsSourcesState(profileId = 4, sources = listOf(aniList, failed))
        assertEquals(1, partial.unavailableConnectedCapabilityCount)
        assertTrue(partial.hasPartialFailure)
    }

    @Test
    fun cachedRowsSurviveFetchFailureAndSuccessfulEmptySnapshotsClearThem() {
        val cached = listOf("cached title")
        assertEquals(
            cached,
            profileInsightsSnapshotItems(
                hasLoaded = false,
                errorMessage = "offline",
                snapshotItems = emptyList(),
                cachedItems = cached,
            ),
        )
        assertEquals(
            emptyList<String>(),
            profileInsightsSnapshotItems(
                hasLoaded = true,
                errorMessage = null,
                snapshotItems = emptyList(),
                cachedItems = cached,
            ),
        )
        assertEquals(
            ProfileInsightsFetchStatus.CACHED,
            profileInsightsFetchStatus(
                connected = true,
                supported = true,
                hasLoaded = false,
                isLoading = false,
                hasData = true,
                errorMessage = null,
            ),
        )

        val recovered = recoverProfileInsightsRefreshingStatus(
            ProfileInsightsCapabilityState(
                status = ProfileInsightsFetchStatus.REFRESHING,
                itemCount = cached.size,
                errorMessage = null,
            ),
        )
        assertEquals(ProfileInsightsFetchStatus.CACHED, recovered.status)
        assertNull(recovered.errorMessage)
    }

    @Test
    fun recentActivityCountsUniqueActivityKeysWithinTheFixedWindow() {
        val now = 2_000_000_000L
        val aggregate = aggregate(
            source(
                "trakt",
                watchedItems = listOf(
                    watched("tt0000018", markedAt = now - 10_000L),
                    watched("tt0000018", markedAt = now - 9_000L),
                    watched("tt0000019", markedAt = now - 8L * 24L * 60L * 60L * 1_000L),
                ),
            ),
        )

        assertEquals(1, metrics(aggregate, now = now).recentActivityCount)
    }

    @Test
    fun durationFormattingUsesDeterministicMinuteHourAndDayBoundaries() {
        val underHour = profileDurationDisplayParts(59L * 60_000L)
        assertEquals(59L, underHour.totalMinutes)
        assertEquals(59, underHour.minutes)

        val oneHour = profileDurationDisplayParts(60L * 60_000L)
        assertEquals(1L, oneHour.hours)
        assertEquals(0L, oneHour.days)

        val oneDayAndOneHour = profileDurationDisplayParts(25L * 60L * 60_000L)
        assertEquals(1L, oneDayAndOneHour.days)
        assertEquals(1L, oneDayAndOneHour.hours)

        val empty = profileDurationDisplayParts(-1L)
        assertEquals(0L, empty.totalMinutes)
        assertEquals(0, empty.minutes)
    }

    @Test
    fun emptyAndUnsupportedHistoryProduceNoSyntheticMetrics() {
        val aggregate = aggregate(
            source(
                "anilist",
                watchedStatus = ProfileInsightsFetchStatus.UNSUPPORTED,
                progressStatus = ProfileInsightsFetchStatus.UNSUPPORTED,
            ),
        )
        val result = metrics(aggregate)

        assertEquals(0, result.libraryCount)
        assertEquals(0, result.watchedTitleCount)
        assertEquals(0L, result.watchTime.totalMs)
        assertNull(result.topType)
    }
}
