package com.streambridge.app.addon

import com.streambridge.app.addon.adapter.AddonAdapterRegistry
import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.addon.plugin.NuvioPluginManager
import com.streambridge.app.addon.model.toStreamOption
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves playable streams for a movie or a specific series episode by
 * asking every enabled extension, mirroring how Stremio fans out stream
 * requests across addons. Uses the adapter layer for capability
 * filtering (resources, types, idPrefixes) and enriches every result
 * into the unified stream model.
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
        extensions
            .filter { extension ->
                extension.enabled && AddonAdapterRegistry.forEcosystem(extension.ecosystem)
                    .canServe(extension, "stream", type, primaryId)
            }
            .map { extension ->
                async {
                    withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                        candidateIds.mapNotNull { candidate ->
                            try {
                                api.fetchStreams(extension.baseUrl, type, candidate)
                            } catch (_: Exception) {
                                null
                            }
                        }
                            .flatMap { response -> response.streams }
                            .mapNotNull { stream -> stream.toStreamOption(extension.displayName) }
                    } ?: emptyList()
                }
            }
            .awaitAll()
            .flatten()
            .toMutableList()
            .apply {
                // Nuvio plugin providers key their scrapes off TMDB ids;
                // extract one when this item carries it. TMDB ids may be
                // series-level ("tmdb:550") or episode-level
                // ("tmdb:550:1:2") — providers want the bare series id.
                val firstId = candidateIds.firstOrNull()
                val tmdbId = firstId
                    ?.takeIf { it.startsWith("tmdb:") }
                    ?.removePrefix("tmdb:")
                    ?.substringBefore(':')
                    ?.takeIf { it.isNotBlank() }
                pluginManager?.let { plugins ->
                    addAll(
                        try {
                            plugins.resolveStreams(
                                type = type,
                                tmdbId = tmdbId,
                                imdbId = imdbId,
                                metaId = firstId,
                                season = season,
                                episode = episode
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }
                    )
                }
            }
            .distinctBy { it.id }
            .let { StreamEnrichment.enrichAll(it) }
            .let { StreamEnrichment.sortForPicker(it) }
    }

    companion object {
        private const val STREAM_TIMEOUT_MS = 20000L
    }
}
