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

    // -----------------------------------------------------------------
    // Metadata merging (detail pages)
    // -----------------------------------------------------------------

    private fun details(
        id: String,
        poster: String? = null,
        backdrop: String? = null,
        logo: String? = null,
        description: String? = null,
        rating: String? = null,
        runtime: String? = null,
        cast: List<String> = emptyList(),
        director: List<String> = emptyList(),
        trailer: String? = null,
        country: String? = null,
        awards: String? = null,
        episodes: List<com.streambridge.app.addon.model.Episode> = emptyList()
    ) = com.streambridge.app.addon.model.MediaDetails(
        id = id,
        imdbId = null,
        type = "movie",
        name = "Details $id",
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        description = description,
        releaseInfo = null,
        runtime = runtime,
        rating = rating,
        genres = emptyList(),
        cast = cast,
        director = director,
        writer = emptyList(),
        trailer = trailer,
        country = country,
        awards = awards,
        episodes = episodes,
        sourceAddonBase = "https://$id.example.com"
    )

    @Test
    fun `mergeDetails fills gaps from fallbacks in order`() {
        val primary = details("primary", poster = "https://x/p.jpg", rating = "8.1")
        val fallbackA = details(
            "a",
            backdrop = "https://x/b.jpg",
            description = "From A",
            cast = listOf("Actor A"),
            director = listOf("Director A"),
            awards = "3 wins"
        )
        val fallbackB = details(
            "b",
            description = "From B",
            trailer = "https://x/t.mp4",
            runtime = "120 min"
        )

        val merged = MetaMerger.mergeDetails(primary, listOf(fallbackA, fallbackB))

        // Primary stays authoritative where it has data.
        assertEquals("https://x/p.jpg", merged.poster)
        assertEquals("8.1", merged.rating)
        // Gaps filled from the first fallback that has them.
        assertEquals("https://x/b.jpg", merged.backdrop)
        assertEquals("From A", merged.description)
        assertEquals(listOf("Actor A"), merged.cast)
        assertEquals(listOf("Director A"), merged.director)
        assertEquals("3 wins", merged.awards)
        assertEquals("https://x/t.mp4", merged.trailer)
        assertEquals("120 min", merged.runtime)
        // Identity never changes.
        assertEquals("primary", merged.id)
        assertEquals("https://primary.example.com", merged.sourceAddonBase)
    }

    @Test
    fun `mergeDetails takes episodes only when the primary has none`() {
        val episode = com.streambridge.app.addon.model.Episode(
            id = "s:1:1", title = "E1", season = 1, number = 1,
            overview = null, thumbnail = null, released = null
        )
        val withOwn = details("primary", episodes = listOf(episode.copy(id = "own:1:1")))
        val other = details("other", episodes = listOf(episode))

        val merged = MetaMerger.mergeDetails(withOwn, listOf(other))
        assertEquals(listOf("own:1:1"), merged.episodes.map { it.id })

        val empty = details("empty")
        val filled = MetaMerger.mergeDetails(empty, listOf(other))
        assertEquals(listOf("s:1:1"), filled.episodes.map { it.id })
    }

    @Test
    fun `mergeDetails never invents data`() {
        val merged = MetaMerger.mergeDetails(details("primary"), listOf(details("a"), details("b")))
        assertEquals(null, merged.poster)
        assertEquals(null, merged.description)
        assertTrue(merged.cast.isEmpty())
        assertTrue(merged.episodes.isEmpty())
    }
}
