package com.streambridge.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.model.CatalogRef
import com.streambridge.app.addon.model.HomeData
import com.streambridge.app.data.db.LibraryItemEntity
import com.streambridge.app.data.db.WatchProgressEntity
import com.streambridge.app.data.discovery.DiscoveryRepository
import com.streambridge.app.data.library.LibraryRepository
import com.streambridge.app.data.settings.SettingsRepository
import com.streambridge.app.data.settings.SettingsState
import com.streambridge.app.di.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    /** No extensions installed: attractive onboarding empty state. */
    data object NoExtensions : HomeUiState

    data object Loading : HomeUiState

    data class Ready(val data: HomeData) : HomeUiState

    data class Failed(val message: String) : HomeUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val discovery: DiscoveryRepository,
    private val extensionManager: ExtensionManager,
    private val library: LibraryRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _continueWatching = MutableStateFlow<List<WatchProgressEntity>>(emptyList())
    val continueWatching: StateFlow<List<WatchProgressEntity>> = _continueWatching.asStateFlow()

    private val _recentlyAdded = MutableStateFlow<List<LibraryItemEntity>>(emptyList())
    val recentlyAdded: StateFlow<List<LibraryItemEntity>> = _recentlyAdded.asStateFlow()

    /** Live settings for layout/visibility preferences. */
    val uiSettings: StateFlow<SettingsState> = settings.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    /** Catalog references matching Home rail keys (for See-all). */
    val catalogRefs: StateFlow<List<CatalogRef>> = extensionManager.extensions
        .map { list -> extensionManager.catalogRefs(list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val refreshTrigger = MutableStateFlow(0)

    /** Pull-to-refresh / retry bypass the catalog cache TTL once. */
    private var forceNextRefresh = false

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                extensionManager.enabledExtensions,
                settings.state
                    .map { s ->
                        s.tmdbActive.toString() + "|" + s.mdblistActive
                    }
                    .distinctUntilChanged(),
                refreshTrigger
            ) { extensions, integrationKey, _ ->
                extensions to integrationKey
            }.distinctUntilChanged()
                .collectLatest { (extensions, _) ->
                    if (extensions.none { it.supportsCatalog }) {
                        _uiState.value = HomeUiState.NoExtensions
                    } else {
                        // Keep the current content on screen while a
                        // refresh is in flight (no full-screen flash).
                        _isRefreshing.value = true
                        if (_uiState.value !is HomeUiState.Ready) {
                            _uiState.value = HomeUiState.Loading
                        }
                        try {
                            val settingsSnapshot = settings.state.first()
                            val watchedKeys = library.observeHistory(80).first()
                                .map { it.metaKey }
                                .toSet()
                            val force = forceNextRefresh
                            forceNextRefresh = false
                            // Progressive: every catalog that answers
                            // updates the screen immediately; a slow or
                            // dead addon can't hold Home hostage.
                            discovery.loadHomeProgressive(
                                settingsSnapshot,
                                watchedKeys,
                                force = force
                            ).collect { data ->
                                _uiState.value = HomeUiState.Ready(data)
                            }
                        } catch (e: Exception) {
                            // Only surface a full-screen failure when we
                            // never managed to show any content.
                            if (_uiState.value !is HomeUiState.Ready) {
                                _uiState.value = HomeUiState.Failed(
                                    e.message ?: "Could not load home content"
                                )
                            }
                        } finally {
                            _isRefreshing.value = false
                        }
                    }
                }
        }

        viewModelScope.launch {
            settings.state
                .map { it.watchedThresholdPercent }
                .distinctUntilChanged()
                .flatMapLatest { threshold -> library.observeContinueWatching(threshold) }
                .collect { entries -> _continueWatching.value = entries }
        }

        viewModelScope.launch {
            library.observeRecentlyAdded(20).collect { entries -> _recentlyAdded.value = entries }
        }
    }

    fun retry() {
        forceNextRefresh = true
        refreshTrigger.value = refreshTrigger.value + 1
    }

    /** Pull-to-refresh entry point (keeps current content visible). */
    fun refresh() {
        forceNextRefresh = true
        refreshTrigger.value = refreshTrigger.value + 1
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                HomeViewModel(
                    discovery = container.discoveryRepository,
                    extensionManager = container.extensionManager,
                    library = container.libraryRepository,
                    settings = container.settingsRepository
                )
            }
        }
    }
}
