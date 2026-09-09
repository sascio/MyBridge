package com.streambridge.app.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.addon.model.toMediaItem
import com.streambridge.app.data.discovery.DiscoveryRepository
import com.streambridge.app.di.AppContainer
import com.streambridge.app.ui.components.ErrorState
import com.streambridge.app.ui.components.PosterCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CatalogGridUiState {
    data object Loading : CatalogGridUiState
    data class Ready(
        val items: List<MediaItem>,
        val endReached: Boolean,
        val loadingMore: Boolean
    ) : CatalogGridUiState

    data class Failed(val message: String) : CatalogGridUiState
}

/**
 * Paginated grid over one concrete addon catalog, reached via "See all"
 * on a Home rail. Pages with the `skip` extra as the user scrolls.
 */
class CatalogGridViewModel(
    savedStateHandle: SavedStateHandle,
    private val discovery: DiscoveryRepository
) : ViewModel() {

    private val addonId: String = savedStateHandle.get<String>("addon") ?: ""
    private val type: String = savedStateHandle.get<String>("type") ?: "movie"
    private val catalogId: String = savedStateHandle.get<String>("id") ?: ""
    val title: String = savedStateHandle.get<String>("name") ?: "Catalog"
    private val baseUrl: String = savedStateHandle.get<String>("base") ?: ""

    private val _state = MutableStateFlow<CatalogGridUiState>(CatalogGridUiState.Loading)
    val state: StateFlow<CatalogGridUiState> = _state.asStateFlow()

    private var loaded = 0
    private var items = mutableListOf<MediaItem>()

    init {
        loadMore()
    }

    fun loadMore(force: Boolean = false) {
        val current = _state.value
        if (current is CatalogGridUiState.Failed) return
        if (current is CatalogGridUiState.Ready && (current.endReached || current.loadingMore)) return
        if (baseUrl.isBlank()) {
            _state.value = CatalogGridUiState.Failed("This catalog is no longer available")
            return
        }
        if (current is CatalogGridUiState.Ready) {
            _state.value = current.copy(loadingMore = true)
        }
        viewModelScope.launch {
            try {
                // Cached + deduplicated with the rest of the app; pages
                // are keyed by `skip`, so back-and-forth navigation does
                // not re-download what the Home screen already fetched.
                val response = discovery.fetchCatalogCached(
                    baseUrl, type, catalogId, skip = loaded, force = force
                )
                val fresh = response.metas.map { it.toMediaItem(addonId) }
                    .filter { freshItem -> items.none { it.key == freshItem.key } }
                items.addAll(fresh)
                loaded += response.metas.size
                _state.value = CatalogGridUiState.Ready(
                    items = items.toList(),
                    endReached = response.metas.isEmpty() || fresh.isEmpty(),
                    loadingMore = false
                )
            } catch (e: Exception) {
                _state.value = if (items.isEmpty()) {
                    CatalogGridUiState.Failed(e.message ?: "Could not load the catalog")
                } else {
                    CatalogGridUiState.Ready(items.toList(), endReached = false, loadingMore = false)
                }
            }
        }
    }

    fun retry() {
        _state.value = CatalogGridUiState.Loading
        items = mutableListOf()
        loaded = 0
        // An explicit retry must bypass the cache TTL, otherwise it can
        // re-serve the very page that just failed.
        loadMore(force = true)
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                CatalogGridViewModel(
                    savedStateHandle = this.createSavedStateHandle(),
                    discovery = container.discoveryRepository
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogGridScreen(
    container: AppContainer,
    onOpenDetail: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    val vm: CatalogGridViewModel = viewModel(factory = CatalogGridViewModel.factory(container))
    // Appearance > Poster size drives the grid density (same mapping as Home).
    val settings by container.settingsRepository.state
        .collectAsStateWithLifecycle(initialValue = com.streambridge.app.data.settings.SettingsState())
    val gridMinSize = when (settings.posterSize) {
        "small" -> 96.dp
        "large" -> 140.dp
        else -> 116.dp
    }
    val posterWidth = when (settings.posterSize) {
        "small" -> 104.dp
        "large" -> 140.dp
        else -> 122.dp
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    // Infinite scroll: load the next page when close to the end.
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 8
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) vm.loadMore()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(text = vm.title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        when (val s = state) {
            CatalogGridUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            is CatalogGridUiState.Failed -> ErrorState(
                message = s.message,
                onRetry = vm::retry,
                modifier = Modifier.padding(padding)
            )

            is CatalogGridUiState.Ready -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = gridMinSize),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(s.items, key = { it.key + it.name }) { item ->
                    PosterCard(
                        item = item,
                        width = posterWidth,
                        onClick = { onOpenDetail(item) }
                    )
                }
                if (s.loadingMore && !s.endReached) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
