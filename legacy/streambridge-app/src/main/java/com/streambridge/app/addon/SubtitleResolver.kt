package com.streambridge.app.addon

import com.streambridge.app.addon.adapter.AddonAdapterRegistry
import com.streambridge.app.addon.model.AddonSubtitle
import com.streambridge.app.data.integrations.OpenSubtitlesClient
import com.streambridge.app.data.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** An addon-provided subtitle, attributed to its addon. */
data class ResolvedSubtitle(
    val addonName: String,
    val subtitle: AddonSubtitle,
    /** Headers the subtitle download URL needs (e.g. OpenSubtitles UA). */
    val headers: Map<String, String> = emptyMap()
) {
    val id: String get() = "${addonName}::${subtitle.url}"
    val label: String get() = subtitle.displayLabel
}

/**
 * Fans out subtitle requests across every enabled extension that
 * declares the "subtitles" resource, honoring type/idPrefix scopes.
 * When the built-in Open Subtitles V3 addon is enabled and configured,
 * its results are appended (never replacing addon results).
 */
class SubtitleResolver(
    private val api: AddonApi,
    private val openSubtitles: OpenSubtitlesClient? = null,
    private val settingsRepository: SettingsRepository? = null
) {

    suspend fun resolveForMovie(
        extensions: List<InstalledExtension>,
        type: String,
        id: String,
        imdbId: String?
    ): List<ResolvedSubtitle> {
        val candidates = IdMapping.movieVideoIds(id, imdbId)
        return resolve(extensions, type, candidates, imdbId, season = null, episode = null)
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
        return resolve(extensions, type, candidates, imdbId, season, episode)
    }

    private suspend fun resolve(
        extensions: List<InstalledExtension>,
        type: String,
        candidateIds: List<String>,
        imdbId: String?,
        season: Int?,
        episode: Int?
    ): List<ResolvedSubtitle> = coroutineScope {
        extensions
            .filter { extension ->
                extension.enabled &&
                    AddonAdapterRegistry.forEcosystem(extension.ecosystem)
                        .canServe(extension, "subtitles", type, candidateIds.firstOrNull() ?: "")
            }
            .map { extension ->
                async<List<AddonSubtitleWithAddon>> {
                    val fetched: List<AddonSubtitle> = withTimeoutOrNull(SUBTITLE_TIMEOUT_MS) {
                        candidateIds.flatMap { candidate ->
                            try {
                                api.fetchSubtitles(extension.baseUrl, type, candidate)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    } ?: emptyList()
                    fetched.map { subtitle ->
                        AddonSubtitleWithAddon(extension.displayName, subtitle)
                    }
                }
            }
            .awaitAll()
            .flatten()
            .let { list ->
                // Preserve addon priority order, dedupe by URL.
                val resolved = list.distinctBy { it.subtitle.url }
                    .map { ResolvedSubtitle(it.addonName, it.subtitle) }
                    .toMutableList()
                resolved += openSubtitlesResults(imdbId, season, episode)
                resolved
            }
    }

    /** Built-in OpenSubtitles V3 source; failures are fully contained. */
    private suspend fun openSubtitlesResults(
        imdbId: String?,
        season: Int?,
        episode: Int?
    ): List<ResolvedSubtitle> {
        val client = openSubtitles ?: return emptyList()
        val settings = settingsRepository ?: return emptyList()
        return try {
            val state = settings.state.first()
            if (!state.opensubtitlesActive) return emptyList()
            client.search(
                apiKey = state.opensubtitlesApiKey,
                username = state.opensubtitlesUsername,
                password = state.opensubtitlesPassword,
                imdbId = imdbId,
                season = season,
                episode = episode
            ).map { result ->
                ResolvedSubtitle(
                    addonName = "Open Subtitles V3",
                    subtitle = AddonSubtitle(
                        url = result.url,
                        lang = result.language,
                        id = "os-${result.fileId}",
                        label = result.label,
                        format = "srt"
                    ),
                    headers = result.headers
                )
            }
        } catch (_: Exception) {
            emptyList()
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
