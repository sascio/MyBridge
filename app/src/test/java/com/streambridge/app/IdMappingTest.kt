package com.streambridge.app

import com.streambridge.app.addon.IdMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdMappingTest {

    @Test
    fun `canServeId respects prefixes`() {
        assertTrue(IdMapping.canServeId("tt123", "movie", emptyList(), listOf("tt")))
        assertTrue(IdMapping.canServeId("tt123", "movie", emptyList(), emptyList()))
        assertFalse(IdMapping.canServeId("foo123", "movie", emptyList(), listOf("tt")))
    }

    @Test
    fun `canServeId respects types`() {
        assertTrue(IdMapping.canServeId("tt123", "movie", listOf("movie", "series"), emptyList()))
        assertFalse(IdMapping.canServeId("tt123", "series", listOf("movie"), emptyList()))
    }

    @Test
    fun `movie candidates list own id first then imdb`() {
        assertEquals(
            listOf("tmdb:55", "tt123"),
            IdMapping.movieVideoIds("tmdb:55", "tt123")
        )
        assertEquals(listOf("tt1"), IdMapping.movieVideoIds("tt1", "tt1"))
        assertEquals(listOf("tt1"), IdMapping.movieVideoIds("tt1", null))
    }

    @Test
    fun `episode candidates use stremio convention and dedupe`() {
        assertEquals(
            listOf("custom:1:2", "tt456:1:2"),
            IdMapping.episodeVideoIds("custom", "tt456", 1, 2)
        )
        // Same imdb meta id: only one candidate
        assertEquals(
            listOf("tt456:1:2"),
            IdMapping.episodeVideoIds("tt456", "tt456", 1, 2)
        )
    }

    @Test
    fun `imdb extraction`() {
        assertEquals("tt0133093", IdMapping.imdbFrom("tt0133093"))
        assertNull(IdMapping.imdbFrom("tmdb:55"))
        assertNull(IdMapping.imdbFrom(null))
        assertNull(IdMapping.imdbFrom("tt"))
    }

    @Test
    fun `meta key is stable`() {
        assertEquals("movie:tt1", IdMapping.metaKey("movie", "tt1"))
    }
}
