package com.streambridge.app

import com.streambridge.app.addon.model.GenreInfo
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.data.discovery.MetaMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaMergerTest {

    private fun item(
        id: String,
        imdbId: String? = null,
        type: String = "movie",
        name: String = "Item $id",
        poster: String? = null,
        genres: List<String> = emptyList()
    ) = MediaItem(
        id = id,
        imdbId = imdbId,
        type = type,
        name = name,
        poster = poster,
        backdrop = null,
        genres = genres,
        source = "test"
    )

    @Test
    fun `items with the same imdb id merge`() {
        val merged = MetaMerger.merge(
            listOf(
                item("tmdb:1", imdbId = "tt1", poster = null),
                item("tt1", imdbId = "tt1", poster = "https://x/p.jpg")
            )
        )
        assertEquals(1, merged.size)
        assertEquals("https://x/p.jpg", merged.first().poster)
    }

    @Test
    fun `items with different ids do not merge`() {
        val merged = MetaMerger.merge(
            listOf(item("tt1"), item("tt2"), item("tt3", type = "series"))
        )
        assertEquals(3, merged.size)
    }

    @Test
    fun `artwork wins during merge`() {
        val merged = MetaMerger.merge(
            listOf(
                item("tt1", poster = "https://x/p.jpg"),
                item("tt1") // same id, no artwork
            )
        )
        assertEquals(1, merged.size)
        assertEquals("https://x/p.jpg", merged.first().poster)
    }

    @Test
    fun `top genres are counted and ordered`() {
        val items = listOf(
            item("1", genres = listOf("Action", "Thriller")),
            item("2", genres = listOf("Action")),
            item("3", genres = listOf("Action", "Comedy")),
            item("4", genres = listOf("Comedy", "Drama"))
        )
        val genres: List<GenreInfo> = MetaMerger.topGenres(items, 2)
        assertEquals(listOf("Action", "Comedy"), genres.map { it.name })
        assertEquals(3, genres[0].count)
        assertEquals(2, genres[1].count)
    }

    @Test
    fun `genre counts ignore blanks and trim`() {
        val items = listOf(
            item("1", genres = listOf("  ", "Drama ")),
            item("2", genres = listOf(" Drama"))
        )
        val genres = MetaMerger.topGenres(items, 10)
        assertEquals(1, genres.size)
        assertEquals("Drama", genres.first().name)
        assertEquals(2, genres.first().count)
    }

    @Test
    fun `recommendations match watched genres and exclude watched items`() {
        val candidates = listOf(
            item("tt1", genres = listOf("Action")),
            item("tt2", genres = listOf("Action", "Sci-Fi")),
            item("tt3", genres = listOf("Romance")),
            item("tt4", genres = listOf("Sci-Fi"))
        )
        val watchedGenres = setOf("action", "sci-fi")
        val watchedKeys = setOf("movie:tt4")

        val recommendations = MetaMerger.recommendations(
            candidates, watchedGenres, watchedKeys, max = 10
        )

        assertEquals(listOf("tt2", "tt1"), recommendations.map { it.id })
    }

    @Test
    fun `no watched genres means no recommendations`() {
        val recommendations = MetaMerger.recommendations(
            listOf(item("tt1", genres = listOf("Action"))),
            emptySet(), emptySet(), 10
        )
        assertTrue(recommendations.isEmpty())
    }
}
