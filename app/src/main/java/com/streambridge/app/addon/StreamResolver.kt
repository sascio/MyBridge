package com.streambridge.app.addon

import com.streambridge.app.addon.adapter.AddonAdapterRegistry
import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.addon.plugin.NuvioPluginManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Resolves playable streams for a movie or a specific series episode by
 * asking every enabled extension, mirroring how Stremio fans out stream
 * requests across addons. Uses the adapter layer for capability
 * filtering (resources, types, idPrefixes) and enriches every result
 * into the unified stream model. Addon extensions and Nuvio plugin
 * providers run CONCURRENTLY as independent sources — one slow source
 * never delays the others.
 */
class StreamResolver(
    private val api: AddonApi,
    /** Optional Nuvio plugin source; null keeps legacy behavior unchanged. */
    private val pluginManager: NuvioPluginManager? = null
) {

    /** Resolves streams for a movie (or any single-video item). */
    suspend fun resolveMovie(
        extensions: List<InstalledExtension>,
        type: String,
        id: String,
        imdbId: String?
    ): List<StreamOption> {
        val candidates = IdMapping.movieVideoIds(id, imdbId)
        return resolve(extensions, type, candidates, imdbId = imdbId)
    }

    /** Resolves streams for a specific episode of a series. */
    suspend fun resolveEpisode(
        extensions: List<InstalledExtension>,
        type: String,
        videoId: String,
        imdbId: String?,
        season: Int,
        episode: Int
    ): List<StreamOption> {
        val candidates = IdMapping.episodeVideoIds(videoId, imdbId, season, episode)
        return resolve(extensions, type, candidates, imdbId = imdbId, season = season, episode = episode)
    }

    private suspend fun resolve(
        extensions: List<InstalledExtension>,
        type: String,
        candidateIds: List<String>,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null
    ): List<StreamOption> = coroutineScope {
        val primaryId = candidateIds.firstOrNull() ?: ""
        val addonSources = extensions
            .filter { extension ->
                extension.enabled && AddonAdapterRegistry.forEcosystem(extension.ecosystem)
                    .canServe(extension, "stream", type, primaryId)
            }
            .map { extension -> StremioAddonSource(api, extension, type, candidateIds) }

        // Nuvio plugin providers key their scrapes off TMDB ids;
        // extract one when this item carries it. TMDB ids may be
        // series-level ("tmdb:550") or episode-level
        // ("tmdb:550:1:2") — providers want the bare series id.
        val tmdbId = primaryId
            .takeIf { it.startsWith("tmdb:") }
            ?.removePrefix("tmdb:")
            ?.substringBefore(':')
            ?.takeIf { it.isNotBlank() }
        val pluginSources = pluginManager?.providerSources(
            type = type,
            tmdbId = tmdbId,
            imdbId = imdbId,
            metaId = primaryId,
            season = season,
            episode = episode
        ) ?: emptyList()

        // Addons and plugin providers execute CONCURRENTLY (they are
        // independent); each source carries its own timeout and failure
        // isolation.
        val results = (addonSources + pluginSources)
            .map { source -> async { source.resolve() } }
            .awaitAll()

        // Record plugin failures for the picker (unchanged contract),
        // then combine everything the sources returned.
        pluginManager?.finishResolution(results.subList(addonSources.size, results.size))

        results
            .flatMap { result -> (result.status as? SourceStatus.Success)?.streams ?: emptyList() }
            .distinctBy { it.id }
            .let { StreamEnrichment.sortForPicker(it) }
    }
}
