package com.streambridge.app.ui.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.StreamResolver
import com.streambridge.app.addon.model.Episode
import com.streambridge.app.addon.model.MediaDetails
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.data.db.LibraryItemEntity
import com.streambridge.app.data.db.WatchProgressEntity
import com.streambridge.app.data.discovery.DiscoveryRepository
import com.streambridge.app.data.integrations.TmdbClient
import com.streambridge.app.data.library.LibraryRepository
import com.streambridge.app.data.settings.SettingsRepository
import com.streambridge.app.di.AppContainer
import com.streambridge.app.player.PlaybackCache
import com.streambridge.app.player.PlaybackRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Ready(val details: MediaDetails) : DetailUiState
    data class Failed(val message: String) : DetailUiState
}

data class LibraryFlags(val favorite: Boolean = false, val watchlist: Boolean = false)

sealed interface DetailEvent {
    data class Message(val text: String) : DetailEvent
}

class DetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val discovery: DiscoveryRepository,
    private val extensionManager: ExtensionManager,
    private val streamResolver: StreamResolver,
    private val library: LibraryRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    private fun arg(name: String): String = savedStateHandle.get<String>(name) ?: ""

    /** The item as passed from the calling screen (may be a preview). */
    val item: MediaItem = MediaItem(
        id = arg("id"),
        imdbId = arg("imdbId").takeIf { it.isNotBlank() },
        type = arg("type").ifBlank { "movie" },
        name = arg("name"),
        poster = arg("poster").takeIf { it.isNotBlank() },
        backdrop = arg("backdrop").takeIf { it.isNotBlank() },
        releaseInfo = arg("releaseInfo").takeIf { it.isNotBlank() },
        rating = arg("rating").takeIf { it.isNotBlank() },
        description = null,
        genres = emptyList(),
        source = arg("source")
    )

    private val _detailState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val detailState: StateFlow<DetailUiState> = _detailState.asStateFlow()

    private val _libraryFlags = MutableStateFlow(LibraryFlags())
    val libraryFlags: StateFlow<LibraryFlags> = _libraryFlags.asStateFlow()

    private val _progressEntries = MutableStateFlow<List<WatchProgressEntity>>(emptyList())
    val progressEntries: StateFlow<List<WatchProgressEntity>> = _progressEntries.asStateFlow()

    private val _selectedSeason = MutableStateFlow<Int?>(null)
    val selectedSeason: StateFlow<Int?> = _selectedSeason.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _related = MutableStateFlow<List<MediaItem>>(emptyList())
    val related: StateFlow<List<MediaItem>> = _related.asStateFlow()

    private val _streamSheet = MutableStateFlow<List<StreamOption>?>(null)
    val streamSheet: StateFlow<List<StreamOption>?> = _streamSheet.asStateFlow()

    private val _pendingRequest = MutableStateFlow<PlaybackRequest?>(null)
    val pendingRequest: StateFlow<PlaybackRequest?> = _pendingRequest.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

    private val isTmdbSource: Boolean
        get() = item.source == TmdbClient.SOURCE || item.id.startsWith(TmdbClient.SOURCE_PREFIX)

    init {
        loadDetails()

        viewModelScope.launch {
            library.observeItem(item.key).collect { entity: LibraryItemEntity? ->
                _libraryFlags.value = LibraryFlags(
                    favorite = entity?.favorite == true,
                    watchlist = entity?.watchlist == true
                )
            }
        }
        viewModelScope.launch {
            library.observeProgressForMeta(item.key).collect { entries ->
                _progressEntries.value = entries
            }
        }
    }

    fun loadDetails() {
        viewModelScope.launch {
            _detailState.value = DetailUiState.Loading
            val extensions = extensionManager.enabledExtensions.value
            val settingsSnapshot = settings.state.first()
            val details = try {
                discovery.loadDetails(item, extensions, settingsSnapshot)
            } catch (e: Exception) {
                null
            }
            if (details == null) {
                _detailState.value = DetailUiState.Failed(
                    "Could not load details for “${item.name}”. " +
                        "Install a metadata extension (for example one that provides IMDb data), " +
                        "then try again."
                )
                return@launch
            }

            _detailState.value = DetailUiState.Ready(details)
            selectInitialSeason(details)
            loadRelated(details)
        }
    }

    private fun selectInitialSeason(details: MediaDetails) {
        val seasons = details.seasons
        if (seasons.isEmpty()) {
            _selectedSeason.value = null
            _episodes.value = emptyList()
            return
        }
        // Continue from the season with the most recent progress, else season 1.
        val latestProgress = _progressEntries.value.firstOrNull()
        val season = latestProgress?.takeIf { it.season > 0 }?.season
            ?: seasons.firstOrNull { it > 0 }
            ?: seasons.first()
        selectSeason(season)
    }

    fun selectSeason(season: Int) {
        _selectedSeason.value = season
        viewModelScope.launch {
            val details = (_detailState.value as? DetailUiState.Ready)?.details ?: return@launch
            val fromMeta = details.episodes.filter { it.season == season }
            if (fromMeta.isNotEmpty()) {
                _episodes.value = fromMeta
            } else if (isTmdbSource) {
                val settingsSnapshot = settings.state.first()
                _episodes.value = discovery.loadTmdbSeason(item, season, settingsSnapshot)
            } else {
                _episodes.value = fromMeta
            }
        }
    }

    private fun loadRelated(details: MediaDetails) {
        val genre = details.genres.firstOrNull() ?: return
        viewModelScope.launch {
            val settingsSnapshot = settings.state.first()
            _related.value = try {
                discovery.browseGenre(genre, settingsSnapshot)
                    .filter { it.key != item.key && it.name != item.name }
                    .take(14)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    // -----------------------------------------------------------------
    // Library actions
    // -----------------------------------------------------------------

    fun toggleFavorite() {
        viewModelScope.launch {
            val details = (_detailState.value as? DetailUiState.Ready)?.details
            val flags = _libraryFlags.value
            val target = !flags.favorite
            if (details != null) {
                library.setFavorite(details, target)
            } else {
                library.setFavorite(item, target)
            }
        }
    }

    fun toggleWatchlist() {
        viewModelScope.launch {
            val details = (_detailState.value as? DetailUiState.Ready)?.details
            val flags = _libraryFlags.value
            val target = !flags.watchlist
            if (details != null) {
                library.setWatchlist(details, target)
            } else {
                library.setWatchlist(item, target)
            }
        }
    }

    // -----------------------------------------------------------------
    // Playback
    // -----------------------------------------------------------------

    /** Primary action: play the movie or continue/start the series. */
    fun playPrimary() {
        val details = (_detailState.value as? DetailUiState.Ready)?.details ?: return
        if (details.isSeries) {
            val episode = continueEpisode(details) ?: defaultEpisode(details)
            if (episode != null) {
                playEpisode(episode)
            } else {
                _events.tryEmit(DetailEvent.Message("No episodes are listed for this series."))
            }
        } else {
            playMovie(details)
        }
    }

    fun playEpisode(episode: Episode) {
        val details = (_detailState.value as? DetailUiState.Ready)?.details ?: return

        // Park the full episode list so the player can offer next/previous.
        val allEpisodes = if (details.episodes.isNotEmpty()) details.episodes else _episodes.value
        PlaybackCache.pendingQueue = allEpisodes
            .sortedWith(compareBy({ it.season }, { it.number }))
            .map { PlaybackCache.QueueEpisode(it.id, it.season, it.number, it.title) }

        resolveAndPlay(
            videoId = episode.id,
            season = episode.season,
            episodeNumber = episode.number,
            episodeTitle = episode.title
        )
    }

    private fun playMovie(details: MediaDetails) {
        PlaybackCache.pendingQueue = emptyList()
        resolveAndPlay(videoId = "", season = 0, episodeNumber = 0, episodeTitle = null)
    }

    private fun resolveAndPlay(
        videoId: String,
        season: Int,
        episodeNumber: Int,
        episodeTitle: String?
    ) {
        val details = (_detailState.value as? DetailUiState.Ready)?.details ?: return
        viewModelScope.launch {
            _resolving.value = true
            try {
                val extensions = extensionManager.enabledExtensions.value
                val streams = if (videoId.isNotBlank()) {
                    streamResolver.resolveEpisode(
                        extensions, details.type, videoId, details.imdbId ?: item.imdbId,
                        season, episodeNumber
                    )
                } else {
                    streamResolver.resolveMovie(
                        extensions, details.type, details.id, details.imdbId ?: item.imdbId
                    )
                }
                val playable = streams.filter { it.isPlayable }
                when {
                    streams.isEmpty() -> _events.tryEmit(
                        DetailEvent.Message(
                            "No streams found. Install a stream extension and make sure it covers this title."
                        )
                    )

                    playable.isEmpty() -> _events.tryEmit(
                        DetailEvent.Message(
                            "Found ${streams.size} stream(s), but none are direct HTTP streams the built-in player can open."
                        )
                    )

                    playable.size == 1 -> navigateToPlayer(videoId, season, episodeNumber, episodeTitle)

                    else -> {
                        pendingSelection = PendingSelection(videoId, season, episodeNumber, episodeTitle)
                        _streamSheet.value = streams
                    }
                }
            } finally {
                _resolving.value = false
            }
        }
    }

    private data class PendingSelection(
        val videoId: String,
        val season: Int,
        val episode: Int,
        val episodeTitle: String?
    )

    private var pendingSelection: PendingSelection? = null

    fun onStreamSelected(option: StreamOption) {
        val current = pendingSelection ?: return
        dismissStreamSheet()
        if (option.isPlayable) {
            navigateToPlayer(current.videoId, current.season, current.episode, current.episodeTitle)
        } else if (option.isTorrent) {
            _events.tryEmit(
                DetailEvent.Message("Torrent streams cannot be played by the built-in player.")
            )
        } else if (option.isExternal) {
            _events.tryEmit(DetailEvent.Message("This stream opens outside Stream Bridge."))
        }
    }

    private fun navigateToPlayer(
        videoId: String,
        season: Int,
        episodeNumber: Int,
        episodeTitle: String?
    ) {
        val details = (_detailState.value as? DetailUiState.Ready)?.details ?: return
        _pendingRequest.value = PlaybackRequest(
            type = details.type,
            metaId = details.id,
            metaName = details.name,
            imdbId = details.imdbId ?: item.imdbId,
            poster = details.poster ?: item.poster,
            backdrop = details.backdrop ?: item.backdrop,
            videoId = videoId,
            season = season,
            episode = episodeNumber,
            episodeTitle = episodeTitle
        )
    }

    fun consumePendingRequest() {
        _pendingRequest.value = null
    }

    fun dismissStreamSheet() {
        _streamSheet.value = null
    }

    fun progressFor(videoId: String): WatchProgressEntity? {
        return _progressEntries.value.firstOrNull { it.videoId == videoId }
    }

    /** Most recent unfinished episode, if any. */
    private fun continueEpisode(details: MediaDetails): Episode? {
        val entries = _progressEntries.value
        if (entries.isEmpty()) return null
        val latest = entries.firstOrNull() ?: return null
        val pool = if (details.episodes.isNotEmpty()) details.episodes else _episodes.value
        return pool.firstOrNull { it.id == latest.videoId }
    }

    private fun defaultEpisode(details: MediaDetails): Episode? {
        val pool = if (details.episodes.isNotEmpty()) details.episodes else _episodes.value
        val season = _selectedSeason.value ?: 1
        return pool.firstOrNull { it.season == season }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                DetailViewModel(
                    savedStateHandle = this.createSavedStateHandle(),
                    discovery = container.discoveryRepository,
                    extensionManager = container.extensionManager,
                    streamResolver = container.streamResolver,
                    library = container.libraryRepository,
                    settings = container.settingsRepository
                )
            }
        }
    }
}
