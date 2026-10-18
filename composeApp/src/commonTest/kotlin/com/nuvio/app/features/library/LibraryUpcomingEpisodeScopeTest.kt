package com.nuvio.app.features.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryUpcomingEpisodeScopeTest {
    @Test
    fun `upcoming calendar events are exposed only for the matching profile and aggregate items`() {
        val series = LibraryItem(
            id = "tmdb:42",
            type = "series",
            name = "Example Series",
            releaseInfo = "2026",
            savedAtEpochMs = 1L,
        )
        val cachedEpisodes = listOf(
            LibraryUpcomingEpisode(
                key = "episode:tmdb:42:2:3",
                item = series,
                dateIso = "2026-10-21",
                subtitle = "S2E3",
                imageUrl = "poster",
                seasonNumber = 2,
                episodeNumber = 3,
            ),
        )
        val months = setOf("2026-10", "2026-11", "2026-12")
        val cachedKey = libraryReleaseCalendarCacheKey(
            profileId = 1,
            nearbyMonthKeys = months,
            items = listOf(series),
        )

        assertEquals(
            cachedEpisodes,
            libraryReleaseCalendarCacheValueForKey(
                cachedKey = cachedKey,
                expectedKey = cachedKey,
                cachedValue = cachedEpisodes,
            ),
        )

        val otherProfileKey = libraryReleaseCalendarCacheKey(
            profileId = 2,
            nearbyMonthKeys = months,
            items = listOf(series),
        )
        assertNull(
            libraryReleaseCalendarCacheValueForKey(
                cachedKey = cachedKey,
                expectedKey = otherProfileKey,
                cachedValue = cachedEpisodes,
            ),
        )

        val otherLibraryKey = libraryReleaseCalendarCacheKey(
            profileId = 1,
            nearbyMonthKeys = months,
            items = listOf(series.copy(id = "tmdb:43", name = "Other Series")),
        )
        assertNull(
            libraryReleaseCalendarCacheValueForKey(
                cachedKey = cachedKey,
                expectedKey = otherLibraryKey,
                cachedValue = cachedEpisodes,
            ),
        )
    }
}
