package com.streambridge.app.addon

import com.streambridge.app.addon.adapter.AddonAdapterRegistry
import com.streambridge.app.addon.model.AddonSubtitle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** An addon-provided subtitle, attributed to its addon. */
data class ResolvedSubtitle(
    val addonName: String,
    val subtitle: AddonSubtitle
) {
    val id: String get() = "${addonName}::${subtitle.url}"
    val label: String get() = subtitle.displayLabel
}

/**
 * Fans out subtitle requests across every enabled extension that
 * declares the "subtitles" resource, honoring type/idPrefix scopes.
 */
class SubtitleResolver(private val api: AddonApi) {

    suspend fun resolveForMovie(
        extensions: List<InstalledExtension>,
        type: String,
        id: String,
        imdbId: String?
    ): List<ResolvedSubtitle> {
        val candidates = IdMapping.movieVideoIds(id, imdbId)
        return resolve(extensions, type, candidates)
    }

    suspend fun resolveForEpisode(
        extensions: List<InstalledExtension>,
        type: String,
        videoId: String,
        imdbId: String?,
        season: Int,
        episode: Int
    ): List<ResolvedSubtitle> {
        val candidates = IdMapping.episodeVideoIds(videoId, imdbId, season, episode)
        return resolve(extensions, type, candidates)
    }

    private suspend fun resolve(
        extensions: List<InstalledExtension>,
        type: String,
        candidateIds: List<String>
    ): List<ResolvedSubtitle> = coroutineScope {
        extensions
            .filter { extension ->
                extension.enabled &&
                    AddonAdapterRegistry.forEcosystem(extension.ecosystem)
                        .canServe(extension, "subtitles", type, candidateIds.firstOrNull() ?: "")
            }
            .map { extension ->
                async {
                    withTimeoutOrNull(SUBTITLE_TIMEOUT_MS) {
                        candidateIds.flatMap { candidate ->
                            try {
                                api.fetchSubtitles(extension.baseUrl, type, candidate)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    } ?: emptyList()
                }.let { deferred ->
                    deferred.await().map { AddonSubtitleWithAddon(extension.displayName, it) }
                }
            }
            .awaitAll()
            .flatten()
            .let { list ->
                // Preserve addon priority order, dedupe by URL.
                list.distinctBy { it.subtitle.url }
                    .map { ResolvedSubtitle(it.addonName, it.subtitle) }
            }
    }

    private data class AddonSubtitleWithAddon(
        val addonName: String,
        val subtitle: AddonSubtitle
    )

    companion object {
        private const val SUBTITLE_TIMEOUT_MS = 12000L
    }
}
