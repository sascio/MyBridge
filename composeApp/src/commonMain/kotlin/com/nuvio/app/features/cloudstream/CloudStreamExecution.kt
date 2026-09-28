package com.nuvio.app.features.cloudstream

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaTrailer
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.streams.StreamCastMember
import com.nuvio.app.features.streams.StreamEpisodeMetadata
import com.nuvio.app.features.streams.StreamMediaMetadata
import com.nuvio.app.features.streams.StreamRelatedMedia
import com.nuvio.app.features.streams.StreamTrailer

/**
 * The CloudStream execution seam.
 *
 * These are the types exchanged between StreamBridge and a platform execution
 * backend. There is deliberately **no** implementation in `commonMain`: any
 * backend capable of running a real `.cs3` must be platform-specific and
 * confined to a distribution that permits it, so the no-execution boundary
 * stays explicit on every other target.
 *
 * Aggregation itself lives in `StreamsRepository`, which calls
 * `CloudStreamExtensionsRepository.resolveStreams`. There is intentionally no
 * second aggregation path.
 */

/** What StreamBridge asks a CloudStream provider to resolve. */
data class CloudStreamStreamQuery(
    /** Provider-specific content URL, as produced by search/details. */
    val url: String,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Metadata common to every CloudStream [com.lagradost.cloudstream3.LoadResponse].
 *
 * This is intentionally a host model rather than a CloudStream class. It keeps
 * the AAR boundary in the Android execution source set and gives the catalog,
 * source picker and player one stable representation of the response hierarchy.
 */
data class CloudStreamResponseMetadata(
    val title: String,
    val originalTitle: String? = null,
    val url: String? = null,
    val dataUrl: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val posterHeaders: Map<String, String> = emptyMap(),
    val description: String? = null,
    val year: Int? = null,
    /** CloudStream's score normalised to the conventional 0..10 range. */
    val rating: Double? = null,
    /** CloudStream duration is expressed in minutes. */
    val durationMinutes: Int? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val cast: List<StreamCastMember> = emptyList(),
    val providerName: String? = null,
    val mediaType: String? = null,
    val isLive: Boolean = false,
    val liveStatus: String? = null,
    val channelName: String? = null,
    val contentRating: String? = null,
    val comingSoon: Boolean = false,
    val uniqueUrl: String? = null,
    val syncData: Map<String, String> = emptyMap(),
    val trailers: List<CloudStreamTrailer> = emptyList(),
    val recommendations: List<CloudStreamRelatedItem> = emptyList(),
    val episodes: List<CloudStreamEpisodeMetadata> = emptyList(),
) {
    /**
     * Adapts the normalized CloudStream response to StreamBridge's detail model.
     * Live entries use the same model as movies/series; the `isLive` flag and
     * type remain available to the caller instead of being discarded.
     */
    fun toMetaDetails(id: String = uniqueUrl ?: url ?: title): MetaDetails = MetaDetails(
        id = id,
        type = when {
            isLive -> "live"
            mediaType.equals("TvSeries", ignoreCase = true) ||
                mediaType.equals("Anime", ignoreCase = true) ||
                mediaType.equals("Cartoon", ignoreCase = true) -> "series"
            else -> "movie"
        },
        name = title,
        poster = poster,
        background = backdrop,
        logo = logo,
        description = description,
        releaseInfo = year?.toString(),
        status = liveStatus,
        ageRating = contentRating,
        imdbRating = rating?.toString(),
        runtime = durationMinutes?.toString(),
        genres = genres,
        cast = cast.map { person ->
            MetaPerson(name = person.name, role = person.role, photo = person.image)
        },
        moreLikeThis = recommendations.map { related ->
            MetaPreview(
                id = related.url,
                type = related.mediaType?.lowercase() ?: "movie",
                name = related.title,
                poster = related.poster,
            )
        },
        trailers = trailers.map { trailer ->
            MetaTrailer(
                id = trailer.url,
                key = trailer.url,
                name = trailer.url,
                site = "CloudStream",
            )
        },
        videos = episodes.map { episode ->
            MetaVideo(
                id = episode.data,
                title = episode.title ?: episode.data,
                thumbnail = episode.poster,
                season = episode.season,
                episode = episode.episode,
                overview = episode.description,
                runtime = episode.durationSeconds?.div(60),
                rating = episode.rating,
            )
        },
    )

    fun toStreamMediaMetadata(): StreamMediaMetadata = StreamMediaMetadata(
        title = title,
        originalTitle = originalTitle,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        posterHeaders = posterHeaders,
        description = description,
        year = year,
        rating = rating,
        durationMinutes = durationMinutes,
        genres = genres,
        tags = tags,
        cast = cast,
        providerName = providerName,
        url = url,
        dataUrl = dataUrl,
        mediaType = mediaType,
        isLive = isLive,
        liveStatus = liveStatus,
        channelName = channelName,
        contentRating = contentRating,
        comingSoon = comingSoon,
        uniqueUrl = uniqueUrl,
        syncData = syncData,
        trailers = trailers.map { trailer ->
            StreamTrailer(
                url = trailer.url,
                referer = trailer.referer,
                raw = trailer.raw,
                headers = trailer.headers,
            )
        },
        recommendations = recommendations.map { related ->
            StreamRelatedMedia(
                title = related.title,
                url = related.url,
                poster = related.poster,
                mediaType = related.mediaType,
            )
        },
        episodes = episodes.map { episode ->
            StreamEpisodeMetadata(
                data = episode.data,
                title = episode.title,
                season = episode.season,
                episode = episode.episode,
                poster = episode.poster,
                description = episode.description,
                durationSeconds = episode.durationSeconds,
                rating = episode.rating,
            )
        },
    )
}

data class CloudStreamTrailer(
    val url: String,
    val referer: String? = null,
    val raw: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

data class CloudStreamRelatedItem(
    val title: String,
    val url: String,
    val poster: String? = null,
    val mediaType: String? = null,
)

data class CloudStreamEpisodeMetadata(
    val data: String,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val poster: String? = null,
    val description: String? = null,
    val durationSeconds: Int? = null,
    val rating: Double? = null,
)

/** One item returned by a CloudStream provider's homepage/catalog. */
data class CloudStreamCatalogItem(
    val title: String,
    val url: String,
    val poster: String? = null,
    val posterHeaders: Map<String, String> = emptyMap(),
    val year: Int? = null,
    val mediaType: String? = null,
    val category: String? = null,
    /** Actual CloudStream homepage section path, outermost first. */
    val sectionPath: List<String> = emptyList(),
    val providerName: String,
    val extensionId: String = "",
    val sourceId: String = "",
    val addonId: String = "",
    val metadata: CloudStreamResponseMetadata? = null,
)

/** Compatibility name for the Live TV path. */
typealias CloudStreamLiveCatalogItem = CloudStreamCatalogItem

internal fun CloudStreamCatalogItem.catalogSourceKey(): String =
    listOf(
        extensionId,
        sourceId,
        addonId,
        providerName,
        sectionPath.joinToString("\u001e"),
        category.orEmpty(),
        uiMediaType(),
    ).joinToString("\u001f")

internal fun CloudStreamCatalogItem.uiMediaType(): String = when {
    mediaType.equals("TvSeries", ignoreCase = true) ||
        mediaType.equals("Anime", ignoreCase = true) ||
        mediaType.equals("Cartoon", ignoreCase = true) -> "series"
    mediaType.equals("Live", ignoreCase = true) -> "live"
    else -> "movie"
}

/** Detail/links result for one catalog item. */
data class CloudStreamLiveResolution(
    val metadata: CloudStreamResponseMetadata,
    val links: List<CloudStreamLink> = emptyList(),
    val subtitles: List<CloudStreamSubtitleFile> = emptyList(),
)

/**
 * One end-to-end resolve request for a single provider.
 *
 * Carries what a CloudStream provider needs to run its own search → detail →
 * episode → links flow. The backend owns that flow because providers differ in
 * how they navigate it; StreamBridge only states what it wants resolved.
 */
internal data class CloudStreamResolveRequest(
    val plugin: CloudStreamPlugin,
    /** StreamBridge media type, e.g. `movie` or `series`. */
    val mediaType: String,
    /** StreamBridge content id (IMDb/TMDB style) for the requested title. */
    val videoId: String,
    /**
     * Title used to search the provider.
     *
     * CloudStream providers are site scrapers keyed by title, not by IMDb/TMDB
     * id, so without this no provider can be searched.
     */
    val title: String? = null,
    /** Release year, used to disambiguate remakes with identical titles. */
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Execution backend contract.
 *
 * Implementations run real CloudStream provider logic through the controlled,
 * gated runtime described on `CloudStreamPlatformRuntime`.
 */
internal interface CloudStreamPluginExecutor {
    suspend fun search(plugin: CloudStreamPlugin, query: String): List<CloudStreamSearchResult>

    suspend fun loadEpisodes(plugin: CloudStreamPlugin, url: String): List<CloudStreamEpisode>

    suspend fun loadLinks(
        plugin: CloudStreamPlugin,
        query: CloudStreamStreamQuery,
    ): CloudStreamLinkResult

    /**
     * Runs the provider's full lifecycle and returns its links and subtitles.
     *
     * Returning an empty result is valid and means the provider genuinely found
     * nothing; it must never be used to paper over an error, which should be
     * thrown so the aggregator can report it against this provider only.
     */
    suspend fun resolve(request: CloudStreamResolveRequest): CloudStreamLinkResult

    /**
     * Loads the provider's homepage/catalog. Implementations that cannot
     * execute CloudStream return the empty default; this keeps the Play Store
     * and iOS no-execution boundary explicit.
     */
    suspend fun loadCatalog(plugin: CloudStreamPlugin): List<CloudStreamCatalogItem> = emptyList()

    /** The Live TV projection is filtered from the same generic catalog seam. */
    suspend fun loadLiveCatalog(plugin: CloudStreamPlugin): List<CloudStreamLiveCatalogItem> =
        loadCatalog(plugin).filter { item ->
            item.metadata?.isLive == true || item.mediaType.equals("Live", ignoreCase = true)
        }

    /** Resolves one catalog item through the same provider/runtime path. */
    suspend fun resolveCatalog(
        plugin: CloudStreamPlugin,
        item: CloudStreamCatalogItem,
    ): CloudStreamLiveResolution = error("This build cannot execute CloudStream providers.")

    /** Compatibility entry point for the Live TV path. */
    suspend fun resolveLive(
        plugin: CloudStreamPlugin,
        item: CloudStreamLiveCatalogItem,
    ): CloudStreamLiveResolution = resolveCatalog(plugin, item)
}
