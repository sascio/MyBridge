package com.streambridge.app.addon

/**
 * Central ID resolution. Providers use different identifier schemes
 * (IMDb "tt…", TMDB "tmdb:…", addon-local ids). Addons declare
 * `idPrefixes` and `types` that scope what they can serve; this maps
 * a media reference onto the concrete ids worth requesting.
 */
object IdMapping {

    /** True when the addon's declared prefixes/types cover this media id. */
    fun canServeId(id: String, type: String, addonTypes: List<String>, idPrefixes: List<String>): Boolean {
        if (addonTypes.isNotEmpty() && !addonTypes.any { it.equals(type, ignoreCase = true) }) {
            return false
        }
        if (idPrefixes.isEmpty()) return true
        return idPrefixes.any { prefix ->
            prefix.isBlank() || id.startsWith(prefix, ignoreCase = true)
        }
    }

    /** Candidate video ids for a movie (its own id plus the IMDb id). */
    fun movieVideoIds(metaId: String, imdbId: String?): List<String> =
        buildList {
            if (metaId.isNotBlank()) add(metaId)
            if (!imdbId.isNullOrBlank() && imdbId != metaId) add(imdbId)
        }

    /**
     * Candidate video ids for an episode. Addons construct episode ids
     * as complete ids (Stremio convention "{seriesId}:{s}:{e}", often
     * IMDb-based but frequently addon-local), so the episode's own id is
     * used as-is; the IMDb fallback follows the "{imdb}:{s}:{e}"
     * convention for cross-addon resolution.
     */
    fun episodeVideoIds(
        videoId: String,
        imdbId: String?,
        season: Int,
        episode: Int
    ): List<String> {
        val fallback = if (!imdbId.isNullOrBlank()) "$imdbId:$season:$episode" else null
        return listOfNotNull(
            videoId.takeIf { it.isNotBlank() },
            fallback?.takeIf { it != videoId }
        )
    }

    /** Best-effort IMDb extraction from any shape of id. */
    fun imdbFrom(id: String?): String? =
        id?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) && it.length >= 3 }

    /** Stable library key for a media reference. */
    fun metaKey(type: String, id: String): String = "$type:$id"
}
