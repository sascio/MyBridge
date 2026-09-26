package com.nuvio.app.features.cloudstream

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real CloudStream `LoadResponse` types from the embedded
 * runtime AAR — not stand-ins — because the bug being guarded against was
 * precisely a mismatch with those types.
 *
 * `resolve()` used to do:
 *
 * ```
 * (detail as? TvSeriesLoadResponse)?.episodes.orEmpty()
 * (detail as? MovieLoadResponse)?.dataUrl ?: match.url
 * ```
 *
 * `LoadResponse` has five implementations. An `AnimeLoadResponse` — returned
 * exclusively by 14 of 50 extensions sampled from a published CloudStream
 * repository — matched neither cast, so the episode list came back empty and
 * the resolve stage threw, which the source picker rendered as
 * "The installed stream addons failed to return a valid stream response".
 *
 * The response constructors are deprecated in favour of CloudStream's
 * `newXLoadResponse` builders, which are suspend extensions on `MainAPI` and
 * exist for plugin authors. A host-side test needs the plain values, so the
 * deprecation is suppressed here rather than in production code.
 */
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class CloudStreamLoadResponseTargetsTest {

    private fun episode(data: String, season: Int? = null, number: Int? = null) =
        Episode(data = data, season = season, episode = number)

    @Test
    fun `a movie response yields its dataUrl`() {
        val detail = MovieLoadResponse(
            name = "Example",
            url = "https://provider.example/movie/example",
            apiName = "Example",
            type = TvType.Movie,
            dataUrl = "https://provider.example/play/example",
        )
        assertEquals("https://provider.example/play/example", CloudStreamLoadResponseTargets.movieTarget(detail))
        assertTrue(CloudStreamLoadResponseTargets.episodes(detail).isEmpty())
        assertEquals("MovieLoadResponse", CloudStreamLoadResponseTargets.describe(detail))
    }

    @Test
    fun `a live stream response yields its dataUrl instead of the page url`() {
        val detail = LiveStreamLoadResponse(
            name = "Channel",
            url = "https://provider.example/channel/1",
            apiName = "Example",
            dataUrl = "https://provider.example/live/1.m3u8",
        )
        assertEquals("https://provider.example/live/1.m3u8", CloudStreamLoadResponseTargets.movieTarget(detail))
    }

    @Test
    fun `a torrent response yields its magnet`() {
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        val detail = TorrentLoadResponse(
            name = "Example",
            url = "https://provider.example/t/1",
            apiName = "Example",
            magnet = magnet,
            torrent = null,
            plot = null,
        )
        assertEquals(magnet, CloudStreamLoadResponseTargets.movieTarget(detail))
    }

    @Test
    fun `a tv series response exposes its episodes in listing order`() {
        val detail = TvSeriesLoadResponse(
            name = "Example",
            url = "https://provider.example/s/1",
            apiName = "Example",
            type = TvType.TvSeries,
            episodes = listOf(
                episode("s1e1", season = 1, number = 1),
                episode("s1e2", season = 1, number = 2),
                episode("s2e1", season = 2, number = 1),
            ),
        )
        val refs = CloudStreamLoadResponseTargets.episodes(detail)
        assertEquals(listOf("s1e1", "s1e2", "s2e1"), refs.map { it.data })
        assertEquals(listOf(0, 1, 2), refs.map { it.order })
        assertEquals("s2e1", CloudStreamEpisodeSelector.select(refs, 2, 1)?.data)
    }

    @Test
    fun `an anime response exposes its episodes, which the old cast could not`() {
        val detail = AnimeLoadResponse(
            name = "Example Anime",
            url = "https://provider.example/anime/1",
            apiName = "Example",
            type = TvType.Anime,
            episodes = mutableMapOf(
                DubStatus.Dubbed to listOf(episode("dub-1", number = 1), episode("dub-2", number = 2)),
                DubStatus.Subbed to listOf(episode("sub-1", number = 1), episode("sub-2", number = 2)),
            ),
        )

        // The behaviour that used to fail.
        assertNull(detail as? TvSeriesLoadResponse)

        val refs = CloudStreamLoadResponseTargets.episodes(detail)
        assertEquals(4, refs.size)
        // Subbed is CloudStream's own default and must come first regardless of
        // map insertion order.
        assertEquals("Subbed", refs.first().variant)
        assertEquals("sub-2", CloudStreamEpisodeSelector.select(refs, 1, 2)?.data)
        assertEquals("sub-1", CloudStreamLoadResponseTargets.movieTarget(detail))
    }

    @Test
    fun `an anime film modelled as a single episode resolves as a movie`() {
        val detail = AnimeLoadResponse(
            name = "Example Film",
            url = "https://provider.example/anime/film",
            apiName = "Example",
            type = TvType.AnimeMovie,
            episodes = mutableMapOf(DubStatus.Subbed to listOf(episode("film-data"))),
        )
        assertEquals("film-data", CloudStreamLoadResponseTargets.movieTarget(detail))
    }

    @Test
    fun `a response with no playable payload declares nothing rather than guessing`() {
        val detail = TvSeriesLoadResponse(
            name = "Example",
            url = "https://provider.example/s/1",
            apiName = "Example",
            type = TvType.TvSeries,
            episodes = emptyList(),
        )
        assertNull(CloudStreamLoadResponseTargets.movieTarget(detail))
        assertTrue(CloudStreamLoadResponseTargets.episodes(detail).isEmpty())
    }

    @Test
    fun `a flat anime listing is addressable as season one`() {
        // The exact real-world shape: no season numbers anywhere.
        val detail = AnimeLoadResponse(
            name = "Example Anime",
            url = "https://provider.example/anime/2",
            apiName = "Example",
            type = TvType.Anime,
            episodes = mutableMapOf(
                DubStatus.Subbed to (1..12).map { episode("ep-$it", number = it) },
            ),
        )
        val refs = CloudStreamLoadResponseTargets.episodes(detail)
        assertEquals("ep-7", CloudStreamEpisodeSelector.select(refs, 1, 7)?.data)
        // And an episode the provider genuinely does not have stays unmatched.
        assertNull(CloudStreamEpisodeSelector.select(refs, 1, 99))
    }
}
