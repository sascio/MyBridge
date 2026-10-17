package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileInsightsProgressTest {

    private fun fetched(genres: List<String> = listOf("Drama")) = ProfileTitleFacts(
        genres = genres,
        runtimeMinutes = 42,
        episodeRuntimeMinutes = emptyMap(),
        resolvedAtEpochMs = 1L,
        partial = false,
    )

    private fun partialFacts() = ProfileTitleFacts(
        genres = emptyList(),
        runtimeMinutes = null,
        episodeRuntimeMinutes = emptyMap(),
        resolvedAtEpochMs = 1L,
        partial = true,
    )

    private fun request(
        titles: List<String>,
        genreTargets: List<String> = emptyList(),
        fetchTargets: List<String> = titles,
    ) = ProfileTitleHydrationRequest(
        seedKeys = titles,
        genreTitleKeys = titles,
        genreTargetKeys = genreTargets,
        fetchTargetKeys = fetchTargets,
    )

    @Test
    fun validDataCompletesEveryCounter() {
        val req = request(listOf("movie:tt1", "series:tt2"), genreTargets = listOf("movie:tt1", "series:tt2"))
        val facts = mapOf("movie:tt1" to fetched(), "series:tt2" to fetched())

        val progress = profileTitleFactsProgress(req, facts, failedKeys = emptySet())

        assertEquals(2, progress.detailsResolved)
        assertEquals(2, progress.detailsTotal)
        assertFalse(progress.detailsPending)
        assertEquals(2, progress.genreResolved)
        assertFalse(progress.genresPending)
    }

    @Test
    fun partialFailureKeepsSuccessfulTitlesAndSettlesWithoutFabrication() {
        val req = request(listOf("movie:tt1", "movie:tt2", "movie:tt3"), genreTargets = listOf("movie:tt1", "movie:tt2", "movie:tt3"))
        val facts = mapOf("movie:tt1" to fetched(listOf("Action")), "movie:tt2" to fetched(listOf("Comedy")))
        val failed = setOf("movie:tt3")

        val progress = profileTitleFactsProgress(req, facts, failed)

        // Two titles are real data; the failed one is counted once and does not block completion.
        assertEquals(2, progress.detailsResolved)
        assertFalse(progress.detailsPending)
        assertEquals(2, progress.genreResolved)
        assertFalse(progress.genresPending)
        assertEquals(3, progress.genreTotal)
    }

    @Test
    fun missingAndPartialEntriesStayPendingUntilSettled() {
        val req = request(listOf("movie:tt1", "movie:tt2"), genreTargets = listOf("movie:tt1", "movie:tt2"))
        val facts = mapOf("movie:tt1" to partialFacts())

        val progress = profileTitleFactsProgress(req, facts, failedKeys = emptySet())

        assertEquals(0, progress.detailsResolved)
        assertTrue(progress.detailsPending)
        assertTrue(progress.genresPending)
    }

    @Test
    fun duplicateKeysAreCountedOnce() {
        // A title listed twice must not inflate totals or the failure count.
        val req = request(
            titles = listOf("movie:tt1", "movie:tt1", "movie:tt2"),
            genreTargets = listOf("movie:tt1", "movie:tt2"),
        )
        val facts = mapOf("movie:tt1" to fetched())

        val progress = profileTitleFactsProgress(req, facts, failedKeys = setOf("movie:tt2"))

        assertEquals(2, progress.detailsTotal)
        assertEquals(2, progress.genreTotal)
        assertEquals(1, progress.detailsResolved)
        assertFalse(progress.detailsPending)
        assertFalse(progress.genresPending)
    }

    @Test
    fun titlesWithLibraryGenresAreNotHydratedForGenres() {
        // Titles that already have genres from the library are not genre targets.
        val req = request(listOf("movie:tt1", "movie:tt2"), genreTargets = listOf("movie:tt2"))

        val progress = profileTitleFactsProgress(req, facts = emptyMap(), failedKeys = emptySet())

        assertEquals(2, progress.genreTotal)
        assertEquals(1, progress.genreResolved)
        assertTrue(progress.genresPending)
    }

    @Test
    fun emptyRequestIsSettled() {
        val progress = profileTitleFactsProgress(
            request(emptyList()),
            facts = emptyMap(),
            failedKeys = emptySet(),
        )

        assertEquals(0, progress.detailsTotal)
        assertEquals(0, progress.genreTotal)
        assertFalse(progress.detailsPending)
        assertFalse(progress.genresPending)
    }
}
