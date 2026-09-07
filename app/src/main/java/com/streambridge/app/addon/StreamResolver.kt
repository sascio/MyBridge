package com.streambridge.app.addon

import com.streambridge.app.addon.model.toStreamOption
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves playable streams for a movie or a specific series episode by
 * asking every enabled extension, mirroring how Stremio fans out stream
 * requests across addons.
 */
class StreamResolver(private val api: AddonApi) {

    /** Resolves streams for a movie (or any single-video item). */
    suspend fun resolveMovie(
        extensions: List<InstalledExtension>,
        type: String,
        id: String,
        imdbId: String?
    ): List<StreamOption> {
        val candidates = buildList {
            add(id)
            if (!imdbId.isNullOrBlank() && !imdbId.equals(id, ignoreCase = true)) {
                add(imdbId)
            }
        }.distinct()
        return resolve(extensions, type, candidates)
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
        val candidates = buildList {
            add(videoId)
            if (!imdbId.isNullOrBlank()) {
                val alternative = "$imdbId:$season:$episode"
                if (none { it.equals(alternative, ignoreCase = true) }) add(alternative)
            }
        }.distinct()
        return resolve(extensions, type, candidates)
    }

    private suspend fun resolve(
        extensions: List<InstalledExtension>,
        type: String,
        candidateIds: List<String>
    ): List<StreamOption> = coroutineScope {
        extensions
            .filter { it.enabled && it.supportsStream }
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
            .distinctBy { it.id }
            .sortedWith(
                compareBy(
                    { if (it.isPlayable) 0 else 1 }, // direct playable first
                    { if (it.isTorrent) 1 else 0 },  // then external links, torrents last
                    { it.addonName }
                )
            )
    }

    companion object {
        private const val STREAM_TIMEOUT_MS = 20000L
    }
}
