package com.streambridge.app.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.ResolvedSubtitle
import com.streambridge.app.addon.StreamResolver
import com.streambridge.app.addon.SubtitleResolver
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.core.TimeFormat
import com.streambridge.app.data.library.LibraryRepository
import com.streambridge.app.data.settings.SettingsRepository
import com.streambridge.app.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything the player needs to play one movie or episode. */
data class PlaybackRequest(
    val type: String,
    val metaId: String,
    val metaName: String,
    val imdbId: String?,
    val poster: String?,
    val backdrop: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?
) {
    val metaKey: String get() = "$type:$metaId"
    val episodeLabel: String?
        get() = if (season > 0) "S%02d E%02d".format(season, episode) else null
}

sealed interface PlayerPhase {
    data object Resolving : PlayerPhase
    data class Picking(val streams: List<StreamOption>) : PlayerPhase
    data class Playing(val stream: StreamOption) : PlayerPhase
    data class NoStreams(val message: String) : PlayerPhase
    data class Error(val message: String) : PlayerPhase
}

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val buffering: Boolean = true,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0
) {
    val progress: Float get() = TimeFormat.progressFraction(positionMs, durationMs)
}

sealed interface PlayerEvent {
    data class Message(val text: String) : PlayerEvent
    data class OpenExternal(val url: String) : PlayerEvent
}

class PlayerViewModel(
    savedStateHandle: SavedStateHandle,
    context: Context,
    private val streamResolver: StreamResolver,
    private val subtitleResolver: SubtitleResolver,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val extensionManager: ExtensionManager
) : ViewModel() {

    private val request = PlaybackRequest(
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

    val initialRequest: PlaybackRequest get() = request

    val currentRequestValue: PlaybackRequest get() = _currentRequest.value

    private val _phase = MutableStateFlow<PlayerPhase>(PlayerPhase.Resolving)
    val phase: StateFlow<PlayerPhase> = _phase.asStateFlow()

    /** Addon-provided external subtitles for the current item. */
    private val _externalSubtitles = MutableStateFlow<List<ResolvedSubtitle>>(emptyList())
    val externalSubtitles: StateFlow<List<ResolvedSubtitle>> = _externalSubtitles.asStateFlow()

    /** Currently side-loaded external subtitle (url), or null. */
    private val _selectedExternalSubtitle = MutableStateFlow<String?>(null)
    val selectedExternalSubtitle: StateFlow<String?> = _selectedExternalSubtitle.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    /** Episode queue (series only): list + index of the current episode. */
    private val _queue = MutableStateFlow<Pair<List<PlaybackCache.QueueEpisode>, Int>?>(null)
    val queue: StateFlow<Pair<List<PlaybackCache.QueueEpisode>, Int>?> = _queue.asStateFlow()

    private val _showPicker = MutableStateFlow(false)
    val showPicker: StateFlow<Boolean> = _showPicker.asStateFlow()

    private val _currentRequest = MutableStateFlow(request)
    val currentRequest: StateFlow<PlaybackRequest> = _currentRequest.asStateFlow()

    private val _sourceLabel = MutableStateFlow("")
    val sourceLabel: StateFlow<String> = _sourceLabel.asStateFlow()

    val holder: PlayerHolder = PlayerHolder(context) { event ->
        when (event) {
            is PlayerHolder.PlaybackEvent.StateChanged -> _playback.value = PlaybackUiState(
                isPlaying = event.isPlaying,
                buffering = event.buffering,
                ended = event.ended,
                positionMs = event.positionMs,
                durationMs = event.durationMs
            )

            is PlayerHolder.PlaybackEvent.Error -> {
                _phase.value = PlayerPhase.Error(event.message)
            }
        }
    }

    private var progressTicker: Job? = null
    private var activeStream: StreamOption? = null
    private var currentStreams: List<StreamOption> = emptyList()
    private var autoplayNext: Boolean = true

    init {
        restoreQueue()
        viewModelScope.launch {
            autoplayNext = settingsRepository.state.first().autoplayNext
        }
        resolveStreams(autoStart = true)
    }

    /** Re-resolves streams for the current request. */
    fun resolveStreams(autoStart: Boolean) {
        val current = _currentRequest.value
        viewModelScope.launch {
            _phase.value = PlayerPhase.Resolving
            val extensions = extensionManager.enabledExtensions.value
            val streams = if (current.type == "series" && current.videoId.isNotBlank()) {
                streamResolver.resolveEpisode(
                    extensions, current.type, current.videoId, current.imdbId,
                    current.season, current.episode
                )
            } else {
                streamResolver.resolveMovie(
                    extensions, current.type, current.metaId, current.imdbId
                )
            }
            currentStreams = streams
            val playable = streams.filter { it.isPlayable }

            when {
                streams.isEmpty() -> {
                    _phase.value = PlayerPhase.NoStreams(
                        "No streams were found for this title. Install a stream extension, or check that your extensions are enabled."
                    )
                }

                playable.isEmpty() -> {
                    _phase.value = PlayerPhase.NoStreams(
                        "Found ${streams.size} stream(s), but none are direct HTTP streams this player can open. " +
                            "Torrent and external links need a companion app."
                    )
                }

                autoStart && playable.size == 1 -> selectStream(playable.first())

                autoStart && preferredStream(playable) != null ->
                    selectStream(preferredStream(playable)!!)

                else -> {
                    _phase.value = PlayerPhase.Picking(streams)
                    _showPicker.value = true
                }
            }
        }
    }

    private fun preferredStream(playable: List<StreamOption>): StreamOption? {
        val lastAddon = activeStream?.addonName
        val lastBinge = activeStream?.bingeGroup
        return playable.firstOrNull { !lastBinge.isNullOrBlank() && it.bingeGroup == lastBinge }
            ?: playable.firstOrNull { lastAddon != null && it.addonName == lastAddon }
    }

    fun selectStream(option: StreamOption) {
        when {
            option.isPlayable -> startPlayback(option)
            option.isTorrent -> _events.tryEmit(
                PlayerEvent.Message(
                    "Torrent streams are not supported by the built-in player. Stream Bridge does not download torrents."
                )
            )

            option.isExternal -> _events.tryEmit(PlayerEvent.OpenExternal(option.externalUrl ?: return))
        }
    }

    private fun startPlayback(option: StreamOption) {
        val url = option.url ?: return
        activeStream = option
        _sourceLabel.value = option.addonName
        _phase.value = PlayerPhase.Playing(option)
        _showPicker.value = false

        viewModelScope.launch {
            val saved = libraryRepository.progressFor(_currentRequest.value.metaKey, _currentRequest.value.videoId)
            val settings = settingsRepository.state.first()
            val resumePosition = when {
                saved == null -> 0L
                TimeFormat.isFinished(saved.positionMs, saved.durationMs, settings.watchedThresholdPercent) -> 0L
                else -> saved.positionMs
            }
            _playbackSpeed.value = settings.defaultPlaybackSpeed
            holder.play(url, resumePosition, speed = settings.defaultPlaybackSpeed)
            startProgressTicker()
            loadExternalSubtitles()
        }
    }

    /** Fans out addon subtitle requests and auto-selects the preferred language. */
    private fun loadExternalSubtitles() {
        val current = _currentRequest.value
        viewModelScope.launch {
            val extensions = extensionManager.enabledExtensions.value
            val found = try {
                if (current.season > 0) {
                    subtitleResolver.resolveForEpisode(
                        extensions, current.type, current.videoId, current.imdbId,
                        current.season, current.episode
                    )
                } else {
                    subtitleResolver.resolveForMovie(
                        extensions, current.type, current.videoId, current.imdbId
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
            _externalSubtitles.value = found
            // Auto-select when the user has a preferred subtitle language.
            val preferred = settingsRepository.state.first().preferredSubtitleLanguage
            if (preferred.isNotBlank() && _selectedExternalSubtitle.value == null) {
                found.firstOrNull { sub ->
                    sub.subtitle.lang.startsWith(preferred, ignoreCase = true) ||
                        sub.subtitle.label.contains(preferred, ignoreCase = true)
                }?.let { applyExternalSubtitle(it) }
            }
        }
    }

    /** Side-loads an addon subtitle, restarting playback at the current position. */
    fun applyExternalSubtitle(subtitle: ResolvedSubtitle) {
        val stream = activeStream ?: return
        val url = stream.url ?: return
        _selectedExternalSubtitle.value = subtitle.subtitle.url
        val position = holder.player.currentPosition.coerceAtLeast(0L)
        holder.applyExternalSubtitles(
            url = url,
            positionMs = position,
            subtitleConfigurations = listOf(subtitle.toMediaSubtitle()),
            speed = _playbackSpeed.value
        )
    }

    /** Clears any side-loaded external subtitle. */
    fun clearExternalSubtitle() {
        val stream = activeStream ?: return
        val url = stream.url ?: return
        _selectedExternalSubtitle.value = null
        val position = holder.player.currentPosition.coerceAtLeast(0L)
        holder.applyExternalSubtitles(url, position, emptyList(), _playbackSpeed.value)
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        holder.setPlaybackSpeed(speed)
    }

    /** Subtitle text scale from settings (cached at session start). */
    private var cachedSubtitleScale = 1f
    val subtitleScale: Float get() = cachedSubtitleScale

    init {
        viewModelScope.launch {
            settingsRepository.state.first().let {
                cachedSubtitleScale = it.subtitleScale
                _playbackSpeed.value = it.defaultPlaybackSpeed
            }
        }
    }

    fun retryPlayback() {
        when (val phaseValue = _phase.value) {
            is PlayerPhase.Playing -> {
                holder.retry()
                _playback.value = _playback.value.copy(buffering = false)
            }

            is PlayerPhase.Error -> {
                val stream = activeStream
                if (stream?.url != null) {
                    _phase.value = PlayerPhase.Playing(stream)
                    holder.retry()
                } else {
                    resolveStreams(autoStart = true)
                }
            }

            else -> resolveStreams(autoStart = true)
        }
    }

    fun openPicker() {
        if (currentStreams.isNotEmpty()) {
            _showPicker.value = true
        } else {
            resolveStreams(autoStart = false)
            _showPicker.value = true
        }
    }

    fun dismissPicker() {
        _showPicker.value = false
    }

    fun togglePlayPause() = holder.togglePlayPause()

    fun seekTo(positionMs: Long) = holder.seekTo(positionMs)

    /** Relative seek used by double-tap and skip-intro gestures. */
    fun seekBy(deltaMs: Long) {
        val current = holder.player.currentPosition.coerceAtLeast(0L)
        val duration = holder.player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        holder.seekTo((current + deltaMs).coerceIn(0L, duration))
    }

    // -----------------------------------------------------------------
    // Progress persistence
    // -----------------------------------------------------------------

    /** Snapshot of the last resolved stream list (for the picker sheet). */
    val streamsSnapshot: List<StreamOption> get() = currentStreams

    private fun startProgressTicker() {
        progressTicker?.cancel()
        progressTicker = viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(500)
                val (position, duration) = holder.snapshotPosition()
                _playback.value = _playback.value.copy(positionMs = position, durationMs = duration)
                ticks++
                if (ticks % 10 == 0) {
                    persistProgress()
                }
            }
        }
    }

    fun persistProgressNow() {
        persistProgress()
    }

    private fun persistProgress(markFinished: Boolean = false) {
        val current = _currentRequest.value
        if (current.metaId.isBlank()) return
        val stream = activeStream ?: return
        if (_phase.value !is PlayerPhase.Playing && !markFinished) return

        val (position, duration) = holder.snapshotPosition()
        if (duration <= 0L && position <= 0L) return
        val item = current.toMediaItem()
        val finalPosition = if (markFinished && duration > 0) duration else position

        viewModelScope.launch {
            libraryRepository.saveProgress(
                item = item,
                videoId = current.videoId,
                season = current.season,
                episode = current.episode,
                episodeTitle = current.episodeTitle,
                positionMs = finalPosition,
                durationMs = duration
            )
        }
    }

    // -----------------------------------------------------------------
    // Episode queue
    // -----------------------------------------------------------------

    private fun restoreQueue() {
        val queue = PlaybackCache.pendingQueue
        val current = _currentRequest.value
        val index = queue.indexOfFirst { episodeRef ->
            episodeRef.videoId == current.videoId && current.videoId.isNotBlank()
        }
        _queue.value = if (queue.isNotEmpty() && index >= 0) queue to index else null
    }

    val hasNextEpisode: Boolean
        get() = _queue.value?.let { (list, index) -> index < list.size - 1 } ?: false

    val hasPreviousEpisode: Boolean
        get() = _queue.value?.let { (_, index) -> index > 0 } ?: false

    fun playNextEpisode() = moveToEpisode(+1)

    fun playPreviousEpisode() = moveToEpisode(-1)

    private fun moveToEpisode(delta: Int) {
        val queueValue = _queue.value ?: return
        val (list, index) = queueValue
        val nextIndex = index + delta
        if (nextIndex < 0 || nextIndex >= list.size) return
        val target = list[nextIndex]

        persistProgress()
        _queue.value = list to nextIndex
        _currentRequest.value = _currentRequest.value.copy(
            videoId = target.videoId,
            season = target.season,
            episode = target.number,
            episodeTitle = target.title
        )
        _playback.value = PlaybackUiState()
        resolveStreams(autoStart = true)
    }

    /** Called by the UI when playback ends; honors the autoplay setting. */
    fun onPlaybackEnded() {
        persistProgress(markFinished = true)
        if (autoplayNext && hasNextEpisode) {
            playNextEpisode()
        }
    }

    fun snackbarConsumed() {
        // no-op: events are one-shot
    }

    override fun onCleared() {
        persistProgress()
        progressTicker?.cancel()
        holder.release()
        super.onCleared()
    }

    private fun PlaybackRequest.toMediaItem(): MediaItem = MediaItem(
        id = metaId,
        imdbId = imdbId,
        type = type,
        name = metaName,
        poster = poster,
        backdrop = backdrop,
        source = ""
    )

    private fun ResolvedSubtitle.toMediaSubtitle(): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.url))
            .setMimeType(subtitleMimeType(subtitle.url))
            .setLanguage(subtitle.subtitle.lang.ifBlank { null })
            .setSelectionFlags(C.SELECTION_FLAG_AUTOSELECT)
            .build()

    private fun subtitleMimeType(url: String): String {
        val lower = url.substringBefore('?').lowercase()
        return when {
            lower.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            lower.endsWith(".srt") || lower.endsWith(".sub") -> MimeTypes.APPLICATION_SUBRIP
            lower.endsWith(".ttml") || lower.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    companion object {
        fun factory(container: AppContainer, appContext: Context) = viewModelFactory {
            initializer {
                PlayerViewModel(
                    savedStateHandle = this.createSavedStateHandle(),
                    context = appContext,
                    streamResolver = container.streamResolver,
                    subtitleResolver = container.subtitleResolver,
                    libraryRepository = container.libraryRepository,
                    settingsRepository = container.settingsRepository,
                    extensionManager = container.extensionManager
                )
            }
        }
    }
}
