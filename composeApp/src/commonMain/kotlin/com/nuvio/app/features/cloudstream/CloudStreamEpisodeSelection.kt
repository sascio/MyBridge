package com.nuvio.app.features.cloudstream

/**
 * Pure mapping from a StreamBridge (season, episode) request onto the episode
 * list a CloudStream provider actually returned.
 *
 * ## Why this exists
 *
 * StreamBridge asks for content by IMDb/TMDB season and episode numbers.
 * CloudStream providers are site scrapers: `Episode.season` is a *nullable*
 * field that a large share of real providers never populate, because the site
 * they scrape has no notion of seasons (single-season shows, and virtually all
 * anime, which numbers episodes absolutely).
 *
 * Requiring `episode.season == requestedSeason` therefore fails on providers
 * that are working perfectly, and the failure surfaces to the user as "the
 * installed stream addons failed to return a valid stream response".
 *
 * The rules below are ordered from most specific to least, and each one is only
 * applied when it cannot be ambiguous. When nothing matches the selector
 * returns `null` — the caller must report that honestly rather than guessing an
 * episode, because playing the wrong episode is worse than reporting none.
 */

/**
 * One episode offered by a provider, normalised away from CloudStream types so
 * the selection rules stay testable without the Android runtime.
 *
 * @param data opaque provider payload handed back to `loadLinks`
 * @param order position in the provider's own listing, used as the tie-break
 * @param variantRank preference between parallel listings of the same show
 *   (CloudStream anime responses expose Subbed/Dubbed/None separately); lower
 *   wins, so the host's preferred variant is chosen deterministically
 */
internal data class CloudStreamEpisodeRef(
    val data: String,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val description: String? = null,
    val runTime: Int? = null,
    val rating: Double? = null,
    val order: Int = 0,
    val variantRank: Int = 0,
    val variant: String? = null,
)

internal object CloudStreamEpisodeSelector {

    /**
     * Picks the episode matching [season]/[episode], or `null` when the
     * provider genuinely does not offer it.
     *
     * Rules, applied in order:
     *
     *  1. **Exact.** `season` and `episode` both match what the provider declared.
     *  2. **Undeclared season means the provider's only season.** A provider
     *     that leaves `season` null is describing a flat episode list, which is
     *     season 1 as far as StreamBridge is concerned.
     *  3. **Single-season listing.** When every episode the provider returned
     *     belongs to one season (or declares none), match on episode number
     *     alone — but only when the requested season is that season, or 1.
     *  4. **Unnumbered listing.** Some providers return an ordered list with no
     *     numbers at all; then and only then, position is the number.
     *
     * A multi-season provider that declares seasons and has no entry for the
     * requested one falls through to `null`. That is deliberate: guessing
     * across seasons silently plays the wrong episode.
     */
    fun select(
        episodes: List<CloudStreamEpisodeRef>,
        season: Int?,
        episode: Int?,
    ): CloudStreamEpisodeRef? {
        if (episodes.isEmpty()) return null
        val ordered = episodes.sortedWith(compareBy({ it.variantRank }, { it.order }))

        // Nothing to disambiguate on: the provider's first entry is the answer.
        if (season == null && episode == null) return ordered.first()

        if (episode != null) {
            // 1. exact
            if (season != null) {
                ordered.firstOrNull { it.season == season && it.episode == episode }
                    ?.let { return it }

                // 2. undeclared season == the provider's only season
                ordered.firstOrNull { it.season == null && it.episode == episode && season == 1 }
                    ?.let { return it }
            } else {
                ordered.firstOrNull { it.episode == episode }?.let { return it }
            }

            // 3. single-season listing
            val declaredSeasons = ordered.mapNotNull { it.season }.distinct()
            val singleSeason = declaredSeasons.size <= 1
            val seasonIsAddressable =
                season == null || season == 1 || declaredSeasons.singleOrNull() == season
            if (singleSeason && seasonIsAddressable) {
                ordered.firstOrNull { it.episode == episode }?.let { return it }
            }

            // 4. unnumbered listing: position is the number
            val unnumbered = ordered.all { it.episode == null }
            if (unnumbered && singleSeason && seasonIsAddressable && episode >= 1) {
                return ordered.getOrNull(episode - 1)
            }
            return null
        }

        // Season only: the first episode of that season is the best answer.
        return ordered.firstOrNull { it.season == season }
            ?: ordered.firstOrNull { it.season == null && season == 1 }
    }

    /**
     * Short, non-sensitive summary of what the provider offered.
     *
     * Used in error text so a failure says *why* it could not be satisfied
     * instead of collapsing into a generic "no streams".
     */
    fun describe(episodes: List<CloudStreamEpisodeRef>): String {
        if (episodes.isEmpty()) return "no episodes"
        val seasons = episodes.mapNotNull { it.season }.distinct().sorted()
        val seasonText = if (seasons.isEmpty()) "no seasons declared" else "seasons ${seasons.joinToString(", ")}"
        val numbers = episodes.mapNotNull { it.episode }
        val episodeText = if (numbers.isEmpty()) {
            "unnumbered"
        } else {
            "episodes ${numbers.min()}-${numbers.max()}"
        }
        val variants = episodes.mapNotNull { it.variant }.distinct()
        val variantText = if (variants.isEmpty()) "" else ", variants ${variants.joinToString("/")}"
        return "${episodes.size} entries, $seasonText, $episodeText$variantText"
    }
}
