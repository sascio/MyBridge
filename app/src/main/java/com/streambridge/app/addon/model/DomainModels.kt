package com.streambridge.app.addon.model

/**
 * Domain models used across the app. These are independent of the wire
 * format so the UI never depends on JSON specifics.
 */

/** A single piece of media shown in rails, grids and search results. */
data class MediaItem(
    val id: String,
    val imdbId: String?,
    val type: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val releaseInfo: String? = null,
    val rating: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val source: String = ""
) {
    val key: String get() = "$type:$id"
    val isSeries: Boolean get() = type == "series"
}

/** Full metadata for a movie or series, including episodes for series. */
data class MediaDetails(
    val id: String,
    val imdbId: String?,
    val type: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val rating: String?,
    val genres: List<String>,
    val cast: List<String>,
    val director: List<String>,
    val country: String?,
    val awards: String?,
    val episodes: List<Episode>,
    val sourceAddonBase: String?
) {
    val isSeries: Boolean get() = type == "series"
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()

    fun toItem(source: String): MediaItem = MediaItem(
        id = id,
        imdbId = imdbId,
        type = type,
        name = name,
        poster = poster,
        backdrop = backdrop,
        releaseInfo = releaseInfo,
        rating = rating,
        description = description,
        genres = genres,
        source = source
    )
}

/** An episode of a series. */
data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int,
    val overview: String?,
    val thumbnail: String?,
    val released: String?
)

/** A playable stream resolved from the installed extensions. */
data class StreamOption(
    val id: String,
    val label: String,
    val description: String?,
    val url: String?,
    val infoHash: String?,
    val externalUrl: String?,
    val addonName: String,
    val isTorrent: Boolean,
    val isExternal: Boolean,
    val bingeGroup: String
) {
    val isPlayable: Boolean get() = url != null
    val shortLabel: String get() = if (label.isBlank()) addonName else label
}

/** Reference to a concrete catalog inside an installed extension. */
data class CatalogRef(
    val addonId: String,
    val addonName: String,
    val baseUrl: String,
    val type: String,
    val catalogId: String,
    val catalogName: String,
    val extraSupported: List<String>
) {
    val compositeId: String get() = "$addonId::$catalogId"
    val supportsSearch: Boolean get() = extraSupported.any { it.equals("search", ignoreCase = true) }
    val supportsGenre: Boolean get() = extraSupported.any { it.equals("genre", ignoreCase = true) }
}

/** Sections rendered by the Home screen. */
sealed interface HomeSection {
    data class Hero(val items: List<MediaItem>) : HomeSection
    data class Rail(
        val key: String,
        val title: String,
        val subtitle: String?,
        val items: List<MediaItem>,
        val sourceAddonId: String
    ) : HomeSection
    data class Genres(val genres: List<GenreInfo>) : HomeSection
}

data class GenreInfo(val name: String, val count: Int)

data class HomeData(
    val sections: List<HomeSection>,
    val sourceErrors: List<String>
)

// ---------------------------------------------------------------------------
// Wire -> domain mappers
// ---------------------------------------------------------------------------

fun AddonMetaPreview.toMediaItem(sourceAddonId: String): MediaItem = MediaItem(
    id = id,
    imdbId = imdbId,
    type = if (type.isBlank()) "movie" else type,
    name = name,
    poster = poster.ifBlank { null },
    backdrop = background.ifBlank { null },
    releaseInfo = releaseInfo.ifBlank { null },
    rating = imdbRating.ifBlank { null },
    description = description.ifBlank { null },
    genres = genres,
    source = sourceAddonId
)

fun AddonMeta.toMediaDetails(sourceAddonBase: String?): MediaDetails = MediaDetails(
    id = id,
    imdbId = imdbId,
    type = if (type.isBlank()) "movie" else type,
    name = name,
    poster = poster.ifBlank { null },
    backdrop = background.ifBlank { null },
    logo = logo.ifBlank { null },
    description = description.ifBlank { null },
    releaseInfo = releaseInfo.ifBlank { null },
    runtime = runtime.ifBlank { null },
    rating = imdbRating.ifBlank { null },
    genres = genres,
    cast = cast,
    director = director,
    country = country.ifBlank { null },
    awards = awards.ifBlank { null },
    episodes = videos
        .filter { it.id.isNotBlank() }
        .map { video ->
            Episode(
                id = video.id,
                title = video.title.ifBlank { "Episode ${video.episodeNumber}" },
                season = video.season,
                number = video.episodeNumber,
                overview = video.overview.ifBlank { null },
                thumbnail = video.thumbnail.ifBlank { null },
                released = video.released.ifBlank { null }
            )
        },
    sourceAddonBase = sourceAddonBase
)

fun AddonStream.toStreamOption(addonName: String): StreamOption? {
    return when {
        isDirect -> StreamOption(
            id = "url:$url",
            label = name,
            description = displayDescription.ifBlank { null },
            url = url,
            infoHash = null,
            externalUrl = null,
            addonName = addonName,
            isTorrent = false,
            isExternal = false,
            bingeGroup = bingeGroup
        )
        isTorrent -> StreamOption(
            id = "torrent:${torrentHash.lowercase()}:${torrentFileIndex}",
            label = name,
            description = displayDescription.ifBlank { null },
            url = null,
            infoHash = torrentHash.lowercase(),
            externalUrl = null,
            addonName = addonName,
            isTorrent = true,
            isExternal = false,
            bingeGroup = bingeGroup
        )
        isExternal -> StreamOption(
            id = "external:$externalLink",
            label = name,
            description = displayDescription.ifBlank { null },
            url = null,
            infoHash = null,
            externalUrl = externalLink,
            addonName = addonName,
            isTorrent = false,
            isExternal = true,
            bingeGroup = ""
        )
        else -> null // ytId and exotic sources are not supported
    }
}
