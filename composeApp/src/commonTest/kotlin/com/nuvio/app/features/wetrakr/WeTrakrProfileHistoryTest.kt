package com.nuvio.app.features.wetrakr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeTrakrProfileHistoryTest {
    @Test
    fun profileHistoryRequiresPositiveMoviePlaysOrWatchedEpisodeEvidence() {
        val listOnlyMovie = WeTrakrMedia(
            id = 101L,
            type = WeTrakrMediaType.MOVIE,
            title = "List only",
            ids = WeTrakrIds(tmdb = 101L),
        )
        val playedMovie = WeTrakrMedia(
            id = 102L,
            type = WeTrakrMediaType.MOVIE,
            title = "Played",
            ids = WeTrakrIds(tmdb = 102L),
        )
        val show = WeTrakrMedia(
            id = 200L,
            type = WeTrakrMediaType.SHOW,
            title = "Show",
            ids = WeTrakrIds(tmdb = 200L),
        )
        val snapshot = WeTrakrSyncSnapshot(
            userId = 9L,
            entries = listOf(
                WeTrakrListEntry(listOnlyMovie, WeTrakrStatus.WATCHED, watchedAtEpochMs = 50L, watchedCount = 0),
                WeTrakrListEntry(playedMovie, WeTrakrStatus.WATCHED, watchedAtEpochMs = 100L, watchedCount = 2),
                WeTrakrListEntry(show, WeTrakrStatus.WATCHED, watchedAtEpochMs = 110L, watchedCount = 12),
                WeTrakrListEntry(
                    WeTrakrMedia(id = 103L, type = WeTrakrMediaType.MOVIE, title = "Planning", ids = WeTrakrIds(tmdb = 103L)),
                    WeTrakrStatus.PLANNING,
                    watchedAtEpochMs = 120L,
                    watchedCount = 5,
                ),
            ),
            watchedEpisodes = listOf(
                WeTrakrWatchedEpisode(
                    showId = show.id,
                    showIds = show.ids,
                    showTitle = show.title,
                    season = 1,
                    number = 2,
                    watchedAtEpochMs = 130L,
                ),
            ),
            playback = listOf(
                WeTrakrPlayback(
                    type = WeTrakrMediaType.MOVIE,
                    media = listOnlyMovie,
                    progressPercent = 40.0,
                    trackedAtEpochMs = 140L,
                ),
            ),
        )

        val history = snapshot.toProfileInsightsWatchedItems()

        assertEquals(2, history.size)
        assertTrue(history.any { it.id == "tmdb:102" && it.type == "movie" })
        assertTrue(history.any { it.id == "tmdb:200" && it.season == 1 && it.episode == 2 })
        assertTrue(history.none { it.id == "tmdb:101" })
        assertTrue(history.none { it.id == "tmdb:103" })
    }
}
