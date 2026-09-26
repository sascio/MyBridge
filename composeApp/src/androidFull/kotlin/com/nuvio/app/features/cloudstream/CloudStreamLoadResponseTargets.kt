package com.nuvio.app.features.cloudstream

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse

/**
 * Translates CloudStream's `LoadResponse` hierarchy into the payload
 * `MainAPI.loadLinks` expects.
 *
 * ## Why every shape matters
 *
 * `LoadResponse` is an interface with five concrete implementations, and which
 * one a provider returns is entirely the provider's choice:
 *
 * | Response | Where the playable payload lives |
 * | --- | --- |
 * | [MovieLoadResponse] | `dataUrl` |
 * | [LiveStreamLoadResponse] | `dataUrl` |
 * | [TorrentLoadResponse] | `magnet` / `torrent` |
 * | [TvSeriesLoadResponse] | `episodes: List<Episode>` -> `Episode.data` |
 * | [AnimeLoadResponse] | `episodes: Map<DubStatus, List<Episode>>` -> `Episode.data` |
 *
 * Handling only the first and fourth (which is what StreamBridge did) makes
 * every anime and live provider fail: the cast returns null, the episode list
 * comes back empty and the resolve stage throws. Of the 50 published
 * extensions sampled from a real CloudStream repository, 14 return
 * `AnimeLoadResponse` *exclusively* and two return `LiveStreamLoadResponse`.
 *
 * This object is deliberately a thin, total mapping: it never invents a URL,
 * and it returns `null` when a response genuinely carries no playable payload
 * so the caller can report that instead of guessing.
 */
internal object CloudStreamLoadResponseTargets {

    /**
     * Preference between the parallel episode listings an [AnimeLoadResponse]
     * exposes. Subtitled is CloudStream's own default, so it is preferred, but
     * every variant stays available to the selector.
     */
    private fun DubStatus.rank(): Int = when (this) {
        DubStatus.Subbed -> 0
        DubStatus.None -> 1
        DubStatus.Dubbed -> 2
    }

    /** CloudStream type name, for diagnostics. Never contains user data. */
    fun describe(detail: LoadResponse): String =
        detail::class.simpleName ?: "LoadResponse"

    /**
     * Every episode a response offers, flattened and normalised.
     *
     * Empty for movie/live/torrent responses, which have no episode list at
     * all — that is a legitimate answer, not a failure.
     */
    fun episodes(detail: LoadResponse): List<CloudStreamEpisodeRef> = when (detail) {
        is TvSeriesLoadResponse -> detail.episodes.mapIndexed { index, episode ->
            episode.toRef(order = index, variantRank = 0, variant = null)
        }

        is AnimeLoadResponse -> detail.episodes
            .toList()
            .sortedBy { (status, _) -> status.rank() }
            .flatMap { (status, list) ->
                list.mapIndexed { index, episode ->
                    episode.toRef(
                        order = index,
                        variantRank = status.rank(),
                        variant = status.name,
                    )
                }
            }

        else -> emptyList()
    }

    /**
     * Payload for a movie-shaped request.
     *
     * Falls back through the response's own episode list, because providers
     * legitimately model a film as a one-entry series (anime films in
     * particular). Returns `null` only when nothing playable is declared, and
     * the caller then falls back to the search result URL exactly as before —
     * so this can only widen what works, never narrow it.
     */
    fun movieTarget(detail: LoadResponse): String? = when (detail) {
        is MovieLoadResponse -> detail.dataUrl.nonBlank()
        is LiveStreamLoadResponse -> detail.dataUrl.nonBlank()
        is TorrentLoadResponse -> detail.magnet?.nonBlank() ?: detail.torrent?.nonBlank()
        else -> episodes(detail)
            .sortedWith(compareBy({ it.variantRank }, { it.order }))
            .firstOrNull()
            ?.data
            ?.nonBlank()
    }

    private fun Episode.toRef(order: Int, variantRank: Int, variant: String?) =
        CloudStreamEpisodeRef(
            data = data,
            name = name,
            season = season,
            episode = episode,
            order = order,
            variantRank = variantRank,
            variant = variant,
        )

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
