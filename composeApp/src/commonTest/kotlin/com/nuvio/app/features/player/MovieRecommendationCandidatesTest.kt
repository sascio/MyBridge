package com.nuvio.app.features.player

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watched.watchedItemKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class MovieRecommendationCandidatesTest {
    @Test
    fun preferredResultsExcludeCurrentAndWatchedTitlesAndAreLimited() {
        val items = (0..13).map { MetaPreview("tt$it", "movie", "Movie $it") }
        val watched = watchedItemKeys(type = "movie", id = "tt1").toSet()
        val result = movieRecommendationCandidates(items, emptyList(), "tt0", watched)
        assertEquals((2..11).map { "tt$it" }, result.map { it.id })
    }

    @Test
    fun fallbackIsUsedOnlyUntilPreferredResultsAreAvailable() {
        val fallback = listOf(MetaPreview("tt2", "movie", "Fallback"))
        assertEquals(fallback, movieRecommendationCandidates(null, fallback, "tt1", emptySet()))
        assertEquals(emptyList(), movieRecommendationCandidates(emptyList(), fallback, "tt1", emptySet()))
    }
}
