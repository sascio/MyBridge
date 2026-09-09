package com.streambridge.app.ui.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.model.Episode
import com.streambridge.app.addon.model.MediaDetails
import com.streambridge.app.addon.model.MediaItem
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val savedStateHandle: SavedStateHandle,
    private val discovery: DiscoveryRepository,
    private val extensionManager: ExtensionManager,
    private val library: LibraryRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    private fun arg(name: String): String = savedStateHandle.get<String>(name) ?: ""

    private fun com.streambridge.app.addon.model.MediaItem.metaKeyValue(): String = "$type:$id"

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

    /**
     * Same data keyed by videoId. Episode rows look up progress per row,
     * so the per-row cost must stay O(1) instead of a linear scan over
     * every entry (O(episodes x entries) per list render).
     */
    val progressByVideoId: StateFlow<Map<String, WatchProgressEntity>> = _progressEntries
        .map { entries -> entries.associateBy { it.videoId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _selectedSeason = MutableStateFlow<Int?>(null)
    val selectedSeason: StateFlow<Int?> = _selectedSeason.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _related = MutableStateFlow<List<MediaItem>>(emptyList())
    val related: StateFlow<List<MediaItem>> = _related.asStateFlow()

    private val _pendingRequest = MutableStateFlow<PlaybackRequest?>(null)
    val pendingRequest: StateFlow<PlaybackRequest?> = _pendingRequest.asStateFlow()

    /** Extra header info for the source-selection screen (never played). */
    data class HeaderInfo(val releaseInfo: String, val rating: String)

    private val _pendingHeaderInfo = MutableStateFlow(HeaderInfo("", ""))
    val pendingHeaderInfo: StateFlow<HeaderInfo> = _pendingHeaderInfo.asStateFlow()

    val watchedThreshold: StateFlow<Int> = settings.state
        .map { it.watchedThresholdPercent }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 95)

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

    /** Marks the current title fully watched (progress at 100%). */
    fun markWatched() {
        viewModelScope.launch {
            val details = (_detailState.value as? DetailUiState.Ready)?.details ?: return@launch
            if (details.isSeries) {
                details.episodes.forEach { episode ->
                    library.saveProgress(
                        details.toItem(details.id),
                        videoId = episode.id,
                        season = episode.season,
                        episode = episode.number,
                        episodeTitle = episode.title,
                        positionMs = 100,
                        durationMs = 100
                    )
                }
            } else {
                library.saveProgress(
                    details.toItem(details.id),
                    videoId = "",
                    season = 0,
                    episode = 0,
                    episodeTitle = null,
                    positionMs = 100,
                    durationMs = 100
                )
            }
            _events.tryEmit(DetailEvent.Message("Marked as watched"))
        }
    }

    /** Clears all watch progress for this title. */
    fun markUnwatched() {
        viewModelScope.launch {
            val entries = library.observeProgressForMeta(item.metaKeyValue()).first()
            entries.forEach { entry ->
                library.clearProgress(entry.metaKey, entry.videoId)
            }
            _events.tryEmit(DetailEvent.Message("Watch state cleared"))
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

        openSourceSelection(
            videoId = episode.id,
            season = episode.season,
            episodeNumber = episode.number,
            episodeTitle = episode.title
        )
    }

    private fun playMovie(details: MediaDetails) {
        PlaybackCache.pendingQueue = emptyList()
        openSourceSelection(videoId = "", season = 0, episodeNumber = 0, episodeTitle = null)
    }

    /**
     * Opens the dedicated source-selection screen IMMEDIATELY — streams
     * are not resolved here. The source screen runs every provider
     * concurrently and shows results progressively as they arrive.
     */
    private fun openSourceSelection(
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
        _pendingHeaderInfo.value = HeaderInfo(
            releaseInfo = details.releaseInfo ?: item.releaseInfo ?: "",
            rating = details.rating ?: item.rating ?: ""
        )
    }

    fun consumePendingRequest() {
        _pendingRequest.value = null
        _pendingHeaderInfo.value = HeaderInfo("", "")
    }

    fun progressFor(videoId: String): WatchProgressEntity? = progressByVideoId.value[videoId]

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
                    library = container.libraryRepository,
                    settings = container.settingsRepository
                )
            }
        }
    }
}
