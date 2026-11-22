package com.nuvio.app.features.details

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeriesGraphEpisodeRatingsTest {
    @Test
    fun parsesValidEpisodeRatingsAndIgnoresInvalidEntries() {
        val ratings = parseSeriesGraphRatings("""[{"episodes":[
          {"season_number":1,"episode_number":2,"vote_average":8.4},
          {"season_number":1,"episode_number":0,"vote_average":7.0},
          {"season_number":-1,"episode_number":3,"vote_average":7.0},
          {"season_number":1,"episode_number":4,"vote_average":11.0}]}]""")
        assertEquals(mapOf((1 to 2) to 8.4), ratings)
        assertEquals(emptyMap(), parseSeriesGraphRatings("not json"))
    }

    @Test
    fun triesFallbackOnlyWhenPrimaryHasNoRatings() = runTest {
        val seen = mutableListOf<String>()
        val ratings = firstAvailableSeriesGraphRatings(listOf("primary", "fallback")) { url ->
            seen += url
            if (url == "primary") emptyMap() else mapOf((1 to 2) to 8.0)
        }
        assertEquals(listOf("primary", "fallback"), seen)
        assertEquals(8.0, ratings[1 to 2])
        seen.clear()
        firstAvailableSeriesGraphRatings(listOf("primary", "fallback")) { url ->
            seen += url
            mapOf((1 to 1) to 7.0)
        }
        assertEquals(listOf("primary"), seen)
    }

    @Test
    fun fillsOnlyMissingRatingsWithoutOverwritingOrMixingAnimeTitles() {
        val meta = MetaDetails(id = "tt123", type = "series", name = "Show", videos = listOf(
            MetaVideo(id = "one", title = "One", season = 1, episode = 1),
            MetaVideo(id = "two", title = "Two", season = 1, episode = 2, imdbRating = 9.0),
            MetaVideo(id = "anime", title = "Anime", season = 1, episode = 1, imdbId = "tt999"),
            MetaVideo(id = "mapped", title = "Mapped", season = 2, episode = 10, imdbSeason = 1, imdbEpisode = 2, imdbId = "tt123"),
            MetaVideo(id = "mal:456:1", title = "Unmapped anime", season = 1, episode = 1),
        ))
        val result = applySeriesGraphRatings(meta, mapOf((1 to 1) to 7.5, (1 to 2) to 8.0), "tt123")
        assertEquals(7.5, result.videos[0].imdbRating)
        assertEquals(9.0, result.videos[1].imdbRating)
        assertNull(result.videos[2].imdbRating)
        assertEquals(8.0, result.videos[3].imdbRating)
        assertNull(result.videos[4].imdbRating)
    }
}
