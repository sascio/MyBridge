package com.nuvio.app.features.details

/** Keep StreamBridge's released fetched-rating priority across every episode layout. */
internal data class EpisodeBadgeRatings(
    val tmdbRating: Double?,
    val imdbRating: Double?,
)

internal fun resolveEpisodeBadgeRatings(
    tmdbRating: Double?,
    imdbRating: Double?,
    fetchedImdbRating: Double?,
): EpisodeBadgeRatings = EpisodeBadgeRatings(
    // A fetched IMDb/OMDB episode score is authoritative. Do not simultaneously
    // present an older addon/TMDB fallback as though it were the current score.
    tmdbRating = tmdbRating.takeIf { fetchedImdbRating == null },
    imdbRating = fetchedImdbRating ?: imdbRating,
)
