package com.streambridge.app.ui.sources

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.streambridge.app.addon.AddonApi
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.IdMapping
import com.streambridge.app.addon.SourceResult
import com.streambridge.app.addon.SourceStatus
import com.streambridge.app.addon.StremioAddonSource
import com.streambridge.app.addon.StreamSource
import com.streambridge.app.addon.StreamSourceAggregator
import com.streambridge.app.addon.adapter.AddonAdapterRegistry
import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.addon.plugin.NuvioPluginManager
import com.streambridge.app.di.AppContainer
import com.streambridge.app.player.PlaybackCache
import com.streambridge.app.player.PlaybackRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SourceSelectionEvent {
    data class Message(val text: String) : SourceSelectionEvent
}

/**
 * State holder for the dedicated source-selection screen.
 *
 * Provider Runtime → per-source results (StreamSourceAggregator) →
 * this state (SourceSelectionUiState) → the Nuvio-style UI. All
 * sources execute concurrently; results land here progressively and
 * the UI reacts without ever recreating the screen.
 */
class SourceSelectionViewModel(
    savedStateHandle: SavedStateHandle,
    private val extensionManager: ExtensionManager,
    private val pluginManager: NuvioPluginManager,
    private val api: AddonApi
) : ViewModel() {

    val request: PlaybackRequest = PlaybackRequest(
        type = savedStateHandle.get<String>("type") ?: "movie",
        metaId = savedStateHandle.get<String>("metaId") ?: "",
        metaName = savedStateHandle.get<String>("name") ?: "",
        imdbId = savedStateHandle.get<String>("imdbId")?.takeIf { it.isNotBlank() },
        poster = savedStateHandle.get<String>("poster")?.takeIf { it.isNotBlank() },
        backdrop = savedStateHandle.get<String>("backdrop")?.takeIf { it.isNotBlank() },
        videoId = savedStateHandle.get<String>("videoId") ?: "",
        season = savedStateHandle.get<String>("season")?.toIntOrNull() ?: 0,
        episode = savedStateHandle.get<String>("episode")?.toIntOrNull() ?: 0,
        episodeTitle = savedStateHandle.get<String>("episodeTitle")?.takeIf { it.isNotBlank() }
    )

    /** Extra header info (release year span, rating) — display only. */
    val releaseInfo: String = savedStateHandle.get<String>("releaseInfo")?.takeIf { it.isNotBlank() } ?: ""
    val rating: String = savedStateHandle.get<String>("rating")?.takeIf { it.isNotBlank() } ?: ""

    private val isEpisode: Boolean =
        request.type == "series" && request.videoId.isNotBlank()

    /** Candidate ids for this item, identical to the player's resolution. */
    private val candidateIds: List<String> =
        if (isEpisode) {
            IdMapping.episodeVideoIds(request.videoId, request.imdbId, request.season, request.episode)
        } else {
            IdMapping.movieVideoIds(request.metaId, request.imdbId)
        }

    private val _state = MutableStateFlow(SourceSelectionUiState(request))
    val state: StateFlow<SourceSelectionUiState> = _state.asStateFlow()

    private val _pendingRequest = MutableStateFlow<PlaybackRequest?>(null)
    val pendingRequest: StateFlow<PlaybackRequest?> = _pendingRequest.asStateFlow()

    private val _events = MutableSharedFlow<SourceSelectionEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SourceSelectionEvent> = _events.asSharedFlow()

    private val aggregator = StreamSourceAggregator()
    private var resolutionJob: Job? = null
    private var serial: Int = 0

    init {
        reload()
    }

    /**
     * (Re)runs every eligible source concurrently. The current results
     * are cleared, tabs are rebuilt as fresh results arrive, and the
     * user stays on this screen. Failed sources are simply re-attempted
     * — nothing is uninstalled or disabled by a failure.
     */
    fun reload() {
        resolutionJob?.cancel()
        val sources = buildSources()
        val passSerial = ++serial
        _state.value = _state.value.restart(
            loading = sources.map { source ->
                SourceResult(source.id, source.name, source.origin, SourceStatus.Loading)
            },
            serial = passSerial
        )
        resolutionJob = viewModelScope.launch {
            try {
                aggregator.aggregate(sources) { result ->
                    _state.update { current -> current.applyResult(result, passSerial) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The aggregator isolates per-source failures; reaching
                // this point means the fan-out itself failed, which the
                // finished-but-empty state already communicates honestly.
            }
        }
    }

    /** Switches the selector tab; never re-requests streams. */
    fun selectTab(tabId: String) {
        _state.update { current -> current.withSelectedTab(tabId) }
    }

    /**
     * Hands the chosen stream to the existing player, without
     * re-resolving. The session's OTHER playable streams travel along
     * as bounded fallbacks: if this source fails in the player, the
     * next one is tried once — without ever leaving the session.
     */
    fun play(stream: StreamOption) {
        when {
            stream.isPlayable -> {
                PlaybackCache.preselectedStream = stream
                PlaybackCache.alternateStreams = state.value.groups
                    .flatMap { group -> group.streams }
                    .filter { it.isPlayable && it.id != stream.id }
                _pendingRequest.value = request
            }
            stream.isTorrent -> _events.tryEmit(
                SourceSelectionEvent.Message("Torrent streams cannot be played by the built-in player.")
            )
            stream.isExternal -> _events.tryEmit(
                SourceSelectionEvent.Message("This stream opens outside Stream Bridge.")
            )
        }
    }

    fun consumePendingRequest() {
        _pendingRequest.value = null
    }

    /**
     * Every source eligible for this request: enabled Stremio-style
     * addons that can serve this type, plus every enabled Nuvio plugin
     * provider matching the media type. Independent of each other.
     */
    private fun buildSources(): List<StreamSource> {
        val extensions = extensionManager.enabledExtensions.value
        val primaryId = candidateIds.firstOrNull() ?: ""
        val addonSources = extensions
            .filter { extension ->
                extension.enabled && AddonAdapterRegistry.forEcosystem(extension.ecosystem)
                    .canServe(extension, "stream", request.type, primaryId)
            }
            .map { extension -> StremioAddonSource(api, extension, request.type, candidateIds) }

        // Nuvio plugin providers key their scrapes off TMDB ids; extract
        // one when this item carries it ("tmdb:550" or "tmdb:550:1:2").
        val tmdbId = primaryId
            .takeIf { it.startsWith("tmdb:") }
            ?.removePrefix("tmdb:")
            ?.substringBefore(':')
            ?.takeIf { it.isNotBlank() }
        val pluginSources = pluginManager.providerSources(
            type = request.type,
            tmdbId = tmdbId,
            imdbId = request.imdbId,
            metaId = primaryId,
            season = if (isEpisode) request.season else null,
            episode = if (isEpisode) request.episode else null
        )
        return addonSources + pluginSources
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                SourceSelectionViewModel(
                    savedStateHandle = this.createSavedStateHandle(),
                    extensionManager = container.extensionManager,
                    pluginManager = container.pluginManager,
                    api = container.addonApi
                )
            }
        }
    }
}
