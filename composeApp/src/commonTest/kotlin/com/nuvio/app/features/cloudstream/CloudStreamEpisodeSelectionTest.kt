package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression guard for the defect that produced
 * "The installed stream addons failed to return a valid stream response".
 *
 * StreamBridge previously required `episode.season == requestedSeason`. Real
 * CloudStream providers leave `Episode.season` null whenever the site they
 * scrape has no season concept — single-season shows, and effectively every
 * anime provider. Those providers therefore never matched, the resolve stage
 * threw, and the source picker reported a generic failure.
 *
 * These cases are shaped after what published extensions actually return.
 */
class CloudStreamEpisodeSelectionTest {

    private fun ep(
        data: String,
        season: Int? = null,
        episode: Int? = null,
        order: Int = 0,
        variantRank: Int = 0,
        variant: String? = null,
    ) = CloudStreamEpisodeRef(
        data = data,
        season = season,
        episode = episode,
        order = order,
        variantRank = variantRank,
        variant = variant,
    )

    @Test
    fun `exact season and episode wins`() {
        val episodes = listOf(
            ep("s1e1", season = 1, episode = 1, order = 0),
            ep("s1e2", season = 1, episode = 2, order = 1),
            ep("s2e2", season = 2, episode = 2, order = 2),
        )
        assertEquals("s2e2", CloudStreamEpisodeSelector.select(episodes, 2, 2)?.data)
        assertEquals("s1e2", CloudStreamEpisodeSelector.select(episodes, 1, 2)?.data)
    }

    @Test
    fun `an undeclared season is treated as the provider's only season`() {
        // This is the exact shape that used to fail: a flat episode list with
        // no season numbers, requested as S1.
        val episodes = listOf(
            ep("e1", episode = 1, order = 0),
            ep("e2", episode = 2, order = 1),
            ep("e3", episode = 3, order = 2),
        )
        assertEquals("e3", CloudStreamEpisodeSelector.select(episodes, 1, 3)?.data)
    }

    @Test
    fun `a single declared season is addressable as season one`() {
        // Providers that hardcode season = 1 on every entry, requested as S1.
        val episodes = listOf(
            ep("a", season = 1, episode = 1, order = 0),
            ep("b", season = 1, episode = 2, order = 1),
        )
        assertEquals("b", CloudStreamEpisodeSelector.select(episodes, 1, 2)?.data)
    }

    @Test
    fun `an unnumbered ordered list falls back to position`() {
        val episodes = listOf(
            ep("first", order = 0),
            ep("second", order = 1),
            ep("third", order = 2),
        )
        assertEquals("third", CloudStreamEpisodeSelector.select(episodes, 1, 3)?.data)
        assertNull(CloudStreamEpisodeSelector.select(episodes, 1, 9))
    }

    @Test
    fun `position fallback is not used when the provider numbers its episodes`() {
        // If numbers exist they are authoritative; guessing by position could
        // silently play a different episode than the user asked for.
        val episodes = listOf(
            ep("e5", episode = 5, order = 0),
            ep("e6", episode = 6, order = 1),
        )
        assertNull(CloudStreamEpisodeSelector.select(episodes, 1, 1))
        assertEquals("e6", CloudStreamEpisodeSelector.select(episodes, 1, 6)?.data)
    }

    @Test
    fun `a multi season provider never guesses across seasons`() {
        val episodes = listOf(
            ep("s1e1", season = 1, episode = 1, order = 0),
            ep("s2e1", season = 2, episode = 1, order = 1),
        )
        assertNull(CloudStreamEpisodeSelector.select(episodes, 3, 1))
    }

    @Test
    fun `anime dub variants are resolved deterministically to the preferred one`() {
        // AnimeLoadResponse exposes parallel listings keyed by DubStatus; the
        // same episode number appears more than once and the choice must be
        // stable rather than dependent on map iteration order.
        val episodes = listOf(
            ep("subbed-1", episode = 1, order = 0, variantRank = 0, variant = "Subbed"),
            ep("dubbed-1", episode = 1, order = 0, variantRank = 2, variant = "Dubbed"),
        )
        assertEquals("subbed-1", CloudStreamEpisodeSelector.select(episodes, 1, 1)?.data)
        assertEquals(
            "subbed-1",
            CloudStreamEpisodeSelector.select(episodes.reversed(), 1, 1)?.data,
        )
    }

    @Test
    fun `an empty episode list resolves to nothing rather than a guess`() {
        assertNull(CloudStreamEpisodeSelector.select(emptyList(), 1, 1))
    }

    @Test
    fun `a movie style request takes the provider's first entry`() {
        val episodes = listOf(ep("only", order = 0), ep("other", order = 1))
        assertEquals("only", CloudStreamEpisodeSelector.select(episodes, null, null)?.data)
    }

    @Test
    fun `describe reports what the provider actually offered`() {
        val flat = listOf(ep("a", episode = 1), ep("b", episode = 2))
        val described = CloudStreamEpisodeSelector.describe(flat)
        assertTrue("no seasons declared" in described, described)
        assertTrue("episodes 1-2" in described, described)

        val seasoned = listOf(ep("a", season = 2, episode = 4))
        assertTrue("seasons 2" in CloudStreamEpisodeSelector.describe(seasoned))
        assertEquals("no episodes", CloudStreamEpisodeSelector.describe(emptyList()))
    }
}
