package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingRatingException
import com.nuvio.app.features.tracking.TrackingRatingScope
import com.nuvio.app.features.tracking.TrackingRatingTarget
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklRatingsProviderTest {
    @Test
    fun animeRatingAndRemovalUseTheirRespectiveSimklBuckets() {
        val target = TrackingRatingTarget(
            scope = TrackingRatingScope.SHOW,
            kind = TrackingMediaKind.ANIME,
            ids = TrackingExternalIds(mal = 5114),
            title = "Example",
        )
        val added = buildSimklRatingBody(target, 9)
        assertEquals("9", added.getValue("anime").jsonArray.single().jsonObject.getValue("rating").jsonPrimitive.content)
        val removed = buildSimklRatingBody(target, null)
        assertTrue("shows" in removed)
        assertFalse("rating" in removed.getValue("shows").jsonArray.single().jsonObject)
        assertFalse("anime" in removed)
    }

    @Test
    fun cachedAnimeEntryEnrichesOrdinaryShowAndMovieTargetsWithoutGuessingOnAmbiguity() {
        val media = SimklMedia(title = "Anime", year = 2024, ids = mapOf(
            "imdb" to JsonPrimitive("tt123"), "simkl" to JsonPrimitive(42),
        ))
        val show = SimklLibraryEntry(mediaType = SimklMediaType.ANIME, show = media)
        val showTarget = TrackingRatingTarget(TrackingRatingScope.SHOW, TrackingMediaKind.SHOW, TrackingExternalIds(imdb = "tt123"))
        val resolved = resolveSimklRatingTarget(showTarget, listOf(show))
        assertEquals(TrackingMediaKind.ANIME, resolved.kind)
        assertEquals(42L, resolved.ids.simkl)
        assertEquals("Anime", resolved.title)
        assertTrue("anime" in buildSimklRatingBody(resolved, 8))
        assertTrue("shows" in buildSimklRatingBody(resolved, null))
        assertEquals(showTarget, resolveSimklRatingTarget(showTarget, listOf(show, show)))

        val animeMovie = show.copy(animeType = "movie")
        val movieTarget = showTarget.copy(scope = TrackingRatingScope.MOVIE, kind = TrackingMediaKind.MOVIE)
        val resolvedMovie = resolveSimklRatingTarget(movieTarget, listOf(animeMovie))
        assertTrue("anime" in buildSimklRatingBody(resolvedMovie, 8))
        assertTrue("shows" in buildSimklRatingBody(resolvedMovie, null))
        assertEquals(movieTarget, resolveSimklRatingTarget(movieTarget, listOf(show)))
    }

    @Test
    fun moviesAndShowsKeepTheirBucketsAndEpisodesAreRejected() {
        val movie = TrackingRatingTarget(TrackingRatingScope.MOVIE, TrackingMediaKind.MOVIE, TrackingExternalIds(imdb = "tt1"))
        val show = TrackingRatingTarget(TrackingRatingScope.SHOW, TrackingMediaKind.SHOW, TrackingExternalIds(imdb = "tt2"))
        assertTrue("movies" in buildSimklRatingBody(movie, 8))
        assertTrue("movies" in buildSimklRatingBody(movie, null))
        assertTrue("shows" in buildSimklRatingBody(show, 8))
        assertFailsWith<TrackingRatingException> { buildSimklRatingBody(show.copy(scope = TrackingRatingScope.EPISODE), 8) }
    }
}
