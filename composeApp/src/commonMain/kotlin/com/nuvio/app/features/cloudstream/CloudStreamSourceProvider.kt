package com.nuvio.app.features.cloudstream

import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException

/**
 * Entry point used by StreamBridge's source aggregation for CloudStream
 * extensions.
 *
 * ## Failure isolation
 *
 * Every plugin is resolved independently and every failure is contained: a
 * throwing or unsupported plugin yields an empty result for that plugin only,
 * never an exception into the aggregator. One broken extension can therefore
 * not break source aggregation as a whole.
 *
 * ## No fabricated results
 *
 * Plugins that cannot actually execute return **no** streams. StreamBridge
 * reports them through [CloudStreamCompatibility] instead of inventing
 * placeholder sources. Because CloudStream provider logic ships only as
 * compiled DEX — which StreamBridge does not run — this is currently the
 * outcome for every real-world plugin, and [resolveStreams] returning empty is
 * the correct, honest behaviour rather than a bug.
 */
internal class CloudStreamSourceProvider(
    /**
     * Supplies streams for an executable plugin. Absent by default: there is
     * no sanctioned way to execute CloudStream DEX, and injecting a resolver
     * is what lets the mapping be exercised deterministically in tests.
     */
    private val executor: CloudStreamPluginExecutor? = null,
) {
    private val log = Logger.withTag("CloudStreamSource")

    /**
     * Resolves streams for [query] across [plugins].
     *
     * Plugins are filtered to those that are genuinely executable before any
     * work is attempted, so unsupported extensions cost nothing.
     */
    suspend fun resolveStreams(
        plugins: List<CloudStreamPlugin>,
        query: CloudStreamStreamQuery,
    ): List<StreamItem> {
        val runner = executor ?: return emptyList()
        val executable = plugins.filter { it.isExecutable }
        if (executable.isEmpty()) return emptyList()

        return executable.flatMap { plugin -> resolveOne(runner, plugin, query) }
    }

    /** Resolves a single plugin with full failure containment. */
    private suspend fun resolveOne(
        runner: CloudStreamPluginExecutor,
        plugin: CloudStreamPlugin,
        query: CloudStreamStreamQuery,
    ): List<StreamItem> = try {
        val result = runner.loadLinks(plugin, query)
        CloudStreamProviderAdapter.adaptLinks(
            links = result.links,
            pluginName = plugin.displayName,
            pluginId = plugin.id,
            subtitles = result.subtitles,
            pluginLogo = plugin.iconUrl,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        // Contained on purpose: isolate this provider, keep the others alive.
        log.w { "CloudStream plugin '${plugin.id}' failed: ${error.message}" }
        emptyList()
    }
}

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
 * Implementations run real CloudStream provider logic. There is deliberately
 * no implementation in `commonMain`: any backend capable of running a real
 * `.cs3` must be platform-specific and confined to a distribution that permits
 * it, so the no-execution boundary stays explicit on every other target.
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
