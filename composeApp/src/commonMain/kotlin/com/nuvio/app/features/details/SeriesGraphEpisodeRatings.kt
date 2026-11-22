package com.nuvio.app.features.details

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.tmdb.TmdbSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Official 1.0.0 episode ratings, used only to fill gaps after StreamBridge's OMDb mapping. */
internal object SeriesGraphEpisodeRatings {
    private const val CACHE_TTL_MS = 30L * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Pair<Long, Map<Pair<Int, Int>, Double>>>()
    private val imdbPattern = Regex("(?i)^tt[0-9]+$")
    private val tmdbPattern = Regex("(?i)^tmdb[:_]([0-9]+)(?:[:_].*)?$")

    private fun imdbId(meta: MetaDetails, fallbackId: String): String? =
        listOf(meta.imdbId, meta.id, fallbackId).firstNotNullOfOrNull {
            it?.trim()?.substringBefore(':')?.takeIf(imdbPattern::matches)?.lowercase()
        }

    private fun tmdbId(meta: MetaDetails, fallbackId: String): String? =
        listOf(meta.id, fallbackId).firstNotNullOfOrNull {
            tmdbPattern.matchEntire(it.trim())?.groupValues?.get(1)?.toLongOrNull()?.takeIf { id -> id > 0 }?.toString()
        }

    fun shouldApply(meta: MetaDetails, fallbackId: String, settings: TmdbSettings): Boolean {
        if (meta.type.equals("movie", ignoreCase = true) || (settings.enabled && !settings.useEpisodeRatings)) return false
        if (meta.videos.none { it.imdbRating == null && ((it.season != null && it.episode != null) || (it.imdbSeason != null && it.imdbEpisode != null)) }) return false
        return (ImdbEpisodeRatingsConfig.IMDB_TAPFRAME_API_BASE_URL.isNotBlank() && imdbId(meta, fallbackId) != null) ||
            (ImdbEpisodeRatingsConfig.IMDB_RATINGS_API_BASE_URL.isNotBlank() && tmdbId(meta, fallbackId) != null)
    }

    suspend fun applyTo(meta: MetaDetails, fallbackId: String, settings: TmdbSettings): MetaDetails {
        if (!shouldApply(meta, fallbackId, settings)) return meta
        val imdb = imdbId(meta, fallbackId)
        val tmdb = tmdbId(meta, fallbackId)
        val candidates = listOfNotNull(
            imdb?.let { candidateUrl(ImdbEpisodeRatingsConfig.IMDB_TAPFRAME_API_BASE_URL, it) },
            tmdb?.let { candidateUrl(ImdbEpisodeRatingsConfig.IMDB_RATINGS_API_BASE_URL, it) },
        )
        val ratings = firstAvailableSeriesGraphRatings(candidates, ::fetch)
        return if (ratings.isEmpty()) meta else applySeriesGraphRatings(meta, ratings, imdb)
    }

    private fun candidateUrl(base: String, id: String): String? {
        val trimmed = base.trim().trimEnd('/')
        if (!(trimmed.startsWith("https://") || trimmed.startsWith("http://"))) return null
        return "$trimmed/api/shows/$id/season-ratings"
    }

    private suspend fun fetch(url: String): Map<Pair<Int, Int>, Double> {
        val now = LibraryClock.nowEpochMs()
        mutex.withLock { cache[url]?.takeIf { it.first > now }?.let { return it.second } }
        val ratings = try {
            withTimeoutOrNull(10_000L) {
                val response = httpRequestRaw("GET", url, mapOf("Accept" to "application/json"), "", maxResponseBodyBytes = 2 * 1024 * 1024)
                if (response.status !in 200..299) emptyMap() else parseSeriesGraphRatings(response.body, json)
            }.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        if (ratings.isNotEmpty()) mutex.withLock { cache[url] = (LibraryClock.nowEpochMs() + CACHE_TTL_MS) to ratings }
        return ratings
    }
}

internal suspend fun firstAvailableSeriesGraphRatings(
    urls: List<String>,
    fetch: suspend (String) -> Map<Pair<Int, Int>, Double>,
): Map<Pair<Int, Int>, Double> {
    for (url in urls) {
        val ratings = fetch(url)
        if (ratings.isNotEmpty()) return ratings
    }
    return emptyMap()
}

@Serializable
internal data class SeriesGraphSeason(val episodes: List<SeriesGraphEpisode>? = null)

@Serializable
internal data class SeriesGraphEpisode(
    @SerialName("season_number") val season: Int? = null,
    @SerialName("episode_number") val episode: Int? = null,
    @SerialName("vote_average") val rating: Double? = null,
)

internal fun parseSeriesGraphRatings(payload: String, json: Json = Json { ignoreUnknownKeys = true }): Map<Pair<Int, Int>, Double> {
    val seasons = runCatching { json.decodeFromString<List<SeriesGraphSeason>>(payload) }.getOrElse { return emptyMap() }
    return buildMap {
        for (season in seasons) for (episode in season.episodes.orEmpty()) {
            val s = episode.season?.takeIf { it >= 0 } ?: continue
            val e = episode.episode?.takeIf { it > 0 } ?: continue
            val rating = episode.rating?.takeIf { it > 0.0 && it <= 10.0 } ?: continue
            put(s to e, rating)
        }
    }
}

internal fun applySeriesGraphRatings(meta: MetaDetails, ratings: Map<Pair<Int, Int>, Double>, showImdbId: String?): MetaDetails =
    meta.copy(videos = meta.videos.map { video ->
        // Per-video IDs and explicit IMDb mappings may refer to a different anime
        // season/show. Never apply a series-level map to a different IMDb title.
        val mappedId = video.imdbId?.trim()?.substringBefore(':')
        val animeId = video.id.substringBefore(':').lowercase() in setOf("mal", "kitsu", "anidb", "anilist")
        val explicitMapping = video.imdbSeason != null && video.imdbEpisode != null && mappedId != null
        val matchesShow = (mappedId == null || (showImdbId != null && mappedId.equals(showImdbId, ignoreCase = true))) &&
            (!animeId || explicitMapping)
        val key = if (video.imdbSeason != null && video.imdbEpisode != null) {
            video.imdbSeason to video.imdbEpisode
        } else if (video.season != null && video.episode != null) video.season to video.episode else null
        val rating = if (matchesShow && video.imdbRating == null && key != null) ratings[key] else null
        if (rating != null) video.copy(imdbRating = rating) else video
    })
