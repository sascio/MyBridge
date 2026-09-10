package com.streambridge.app.data.integrations

import com.streambridge.app.addon.SbHttpClient
import com.streambridge.app.addon.model.Episode
import com.streambridge.app.addon.model.LenientStringSerializer
import com.streambridge.app.addon.model.MediaDetails
import com.streambridge.app.addon.model.MediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Optional TMDB integration.
 *
 * OFF by default. Requires the user to enter their own API key in
 * Settings; no key is bundled with the app. When active it contributes
 * trending/popular rails and search results.
 */
class TmdbClient(
    private val http: SbHttpClient,
    private val json: Json
) {

    @Serializable
    private data class TmdbListResponse(val results: List<TmdbItem> = emptyList())

    @Serializable
    private data class TmdbItem(
        val id: Long = 0,
        @Serializable(with = LenientStringSerializer::class) val media_type: String = "",
        @Serializable(with = LenientStringSerializer::class) val title: String = "",
        @Serializable(with = LenientStringSerializer::class) val name: String = "",
        @Serializable(with = LenientStringSerializer::class) val poster_path: String = "",
        @Serializable(with = LenientStringSerializer::class) val backdrop_path: String = "",
        @Serializable(with = LenientStringSerializer::class) val release_date: String = "",
        @Serializable(with = LenientStringSerializer::class) val first_air_date: String = "",
        @Serializable(with = LenientStringSerializer::class) val overview: String = "",
        val vote_average: Double? = null
    )

    @Serializable
    private data class TmdbGenre(
        @Serializable(with = LenientStringSerializer::class) val name: String = ""
    )

    @Serializable
    private data class TmdbSeasonSummary(
        val season_number: Int = 0,
        @Serializable(with = LenientStringSerializer::class) val name: String = "",
        val episode_count: Int = 0
    )

    @Serializable
    private data class TmdbCastMember(
        @Serializable(with = LenientStringSerializer::class) val name: String = ""
    )

    @Serializable
    private data class TmdbCredits(val cast: List<TmdbCastMember> = emptyList())

    @Serializable
    private data class TmdbExternalIds(
        @Serializable(with = LenientStringSerializer::class) val imdb_id: String = ""
    )

    @Serializable
    private data class TmdbDetails(
        val id: Long = 0,
        @Serializable(with = LenientStringSerializer::class) val title: String = "",
        @Serializable(with = LenientStringSerializer::class) val name: String = "",
        @Serializable(with = LenientStringSerializer::class) val overview: String = "",
        @Serializable(with = LenientStringSerializer::class) val poster_path: String = "",
        @Serializable(with = LenientStringSerializer::class) val backdrop_path: String = "",
        @Serializable(with = LenientStringSerializer::class) val release_date: String = "",
        @Serializable(with = LenientStringSerializer::class) val first_air_date: String = "",
        @Serializable(with = LenientStringSerializer::class) val tagline: String = "",
        val runtime: Int? = null,
        val episode_run_time: List<Int> = emptyList(),
        val genres: List<TmdbGenre> = emptyList(),
        val number_of_seasons: Int? = null,
        val seasons: List<TmdbSeasonSummary> = emptyList(),
        val vote_average: Double? = null,
        val credits: TmdbCredits? = null,
        val external_ids: TmdbExternalIds? = null
    )

    @Serializable
    private data class TmdbEpisode(
        val season_number: Int = 0,
        val episode_number: Int = 0,
        @Serializable(with = LenientStringSerializer::class) val name: String = "",
        @Serializable(with = LenientStringSerializer::class) val overview: String = "",
        @Serializable(with = LenientStringSerializer::class) val still_path: String = "",
        @Serializable(with = LenientStringSerializer::class) val air_date: String = ""
    )

    @Serializable
    private data class TmdbSeasonResponse(val episodes: List<TmdbEpisode> = emptyList())

    // ------------------------------------------------------------------

    suspend fun trending(apiKey: String): List<MediaItem> =
        fetchList("/3/trending/all/week", apiKey)

    suspend fun popularMovies(apiKey: String): List<MediaItem> =
        fetchList("/3/movie/popular", apiKey, forceType = "movie")

    suspend fun popularSeries(apiKey: String): List<MediaItem> =
        fetchList("/3/tv/popular", apiKey, forceType = "series")

    suspend fun search(apiKey: String, query: String): List<MediaItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return fetchList("/3/search/multi?query=$encoded", apiKey)
    }

    /** Full details for a movie or series. `tmdbId` is the numeric TMDB id. */
    suspend fun details(apiKey: String, type: String, tmdbId: Long): MediaDetails? {
        val path = if (type == "series") "/3/tv/$tmdbId" else "/3/movie/$tmdbId"
        val body = http.get("$BASE$path?api_key=${enc(apiKey)}&append_to_response=credits,external_ids")
        val details = json.decodeFromString(TmdbDetails.serializer(), body)
        if (details.id == 0L) return null
        val isSeries = type == "series"
        val imdbId = details.external_ids?.imdb_id?.takeIf { it.isNotBlank() }
        val year = if (isSeries) details.first_air_date else details.release_date
        return MediaDetails(
            id = "tmdb:${details.id}",
            imdbId = imdbId,
            type = if (isSeries) "series" else "movie",
            name = (if (isSeries) details.name else details.title).ifBlank { "TMDB #${details.id}" },
            poster = details.poster_path.takeIf { it.isNotBlank() }?.let { "$IMAGE_BASE_W500$it" },
            backdrop = details.backdrop_path.takeIf { it.isNotBlank() }?.let { "$IMAGE_BASE_W780$it" },
            logo = null,
            description = details.overview.takeIf { it.isNotBlank() },
            releaseInfo = year?.take(4)?.takeIf { it.isNotBlank() },
            runtime = when {
                !isSeries && (details.runtime ?: 0) > 0 -> "${details.runtime}"
                isSeries && details.episode_run_time.isNotEmpty() -> "${details.episode_run_time.first()}"
                else -> null
            },
            rating = details.vote_average?.let { if (it > 0.0) String.format(java.util.Locale.US, "%.1f", it) else null },
            genres = details.genres.map { it.name }.filter { it.isNotBlank() },
            cast = details.credits?.cast?.map { it.name }?.filter { it.isNotBlank() } ?: emptyList(),
            director = emptyList(),
            country = null,
            awards = null,
            episodes = emptyList(),
            sourceAddonBase = null
        )
    }

    /** Episodes of a TMDB series season. */
    suspend fun seasonEpisodes(apiKey: String, tmdbId: Long, season: Int): List<Episode> {
        val body = http.get("$BASE/3/tv/$tmdbId/season/$season?api_key=${enc(apiKey)}")
        val response = json.decodeFromString(TmdbSeasonResponse.serializer(), body)
        return response.episodes.map { episode ->
            Episode(
                id = "tmdb:$tmdbId:$season:${episode.episode_number}",
                title = episode.name.ifBlank { "Episode ${episode.episode_number}" },
                season = season,
                number = episode.episode_number,
                overview = episode.overview.takeIf { it.isNotBlank() },
                thumbnail = episode.still_path.takeIf { it.isNotBlank() }?.let { "$IMAGE_BASE_W300$it" },
                released = episode.air_date.takeIf { it.isNotBlank() }
            )
        }
    }

    // ------------------------------------------------------------------

    private suspend fun fetchList(
        path: String,
        apiKey: String,
        forceType: String? = null
    ): List<MediaItem> {
        val separator = if (path.contains('?')) '&' else '?'
        val body = http.get("$BASE$path${separator}api_key=${enc(apiKey)}")
        val response = json.decodeFromString(TmdbListResponse.serializer(), body)
        return response.results
            .filter { it.id != 0L }
            .mapNotNull { item ->
                val type = forceType ?: when (item.media_type) {
                    "movie" -> "movie"
                    "tv" -> "series"
                    else -> return@mapNotNull null
                }
                val year = (if (type == "series") item.first_air_date else item.release_date).take(4)
                MediaItem(
                    id = "tmdb:${item.id}",
                    imdbId = null,
                    type = type,
                    name = item.name.ifBlank { item.title },
                    poster = item.poster_path.takeIf { it.isNotBlank() }?.let { "$IMAGE_BASE_W500$it" },
                    backdrop = item.backdrop_path.takeIf { it.isNotBlank() }?.let { "$IMAGE_BASE_W780$it" },
                    releaseInfo = year.takeIf { it.isNotBlank() },
                    rating = item.vote_average?.let { if (it > 0.0) String.format(java.util.Locale.US, "%.1f", it) else null },
                    description = item.overview.takeIf { it.isNotBlank() },
                    genres = emptyList(),
                    source = SOURCE
                )
            }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    companion object {
        const val SOURCE = "tmdb"
        const val SOURCE_PREFIX = "tmdb:"
        private const val BASE = "https://api.themoviedb.org"
        private const val IMAGE_BASE_W300 = "https://image.tmdb.org/t/p/w300"
        private const val IMAGE_BASE_W500 = "https://image.tmdb.org/t/p/w500"
        private const val IMAGE_BASE_W780 = "https://image.tmdb.org/t/p/w780"
    }
}
