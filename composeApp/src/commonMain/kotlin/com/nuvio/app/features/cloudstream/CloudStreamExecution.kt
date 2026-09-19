package com.nuvio.app.features.cloudstream

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

/** Links plus subtitles returned by one plugin. */
data class CloudStreamLinkResult(
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
}
