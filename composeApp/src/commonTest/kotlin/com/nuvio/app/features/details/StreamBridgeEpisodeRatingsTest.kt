package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamBridgeEpisodeRatingsTest {
    @Test
    fun `fetched episode rating takes priority over both addon fallbacks`() {
        assertEquals(
            EpisodeBadgeRatings(tmdbRating = null, imdbRating = 8.7),
            resolveEpisodeBadgeRatings(tmdbRating = 6.0, imdbRating = 7.1, fetchedImdbRating = 8.7),
        )
    }

    @Test
    fun `addon ratings remain available when no fetched score exists`() {
        assertEquals(
            EpisodeBadgeRatings(tmdbRating = 6.0, imdbRating = 7.1),
            resolveEpisodeBadgeRatings(tmdbRating = 6.0, imdbRating = 7.1, fetchedImdbRating = null),
        )
    }

    @Test
    fun `unconfigured episode ratings remain empty`() {
        assertEquals(EpisodeBadgeRatings(null, null), resolveEpisodeBadgeRatings(null, null, null))
    }
}
