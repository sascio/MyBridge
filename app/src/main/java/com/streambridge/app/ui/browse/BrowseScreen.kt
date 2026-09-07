package com.streambridge.app.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.data.discovery.DiscoveryRepository
import com.streambridge.app.data.settings.SettingsRepository
import com.streambridge.app.di.AppContainer
import com.streambridge.app.ui.components.EmptyState
import com.streambridge.app.ui.components.ErrorState
import com.streambridge.app.ui.components.PosterCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface BrowseUiState {
    data object Loading : BrowseUiState
    data class Ready(val items: List<MediaItem>) : BrowseUiState
    data class Failed(val message: String) : BrowseUiState
}

class BrowseViewModel(
    savedStateHandle: SavedStateHandle,
    private val discovery: DiscoveryRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    val genre: String = savedStateHandle.get<String>("genre") ?: ""

    private val _state = MutableStateFlow<BrowseUiState>(BrowseUiState.Loading)
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = BrowseUiState.Loading
            _state.value = try {
                val settingsSnapshot = settings.state.first()
                BrowseUiState.Ready(discovery.browseGenre(genre, settingsSnapshot))
            } catch (e: Exception) {
                BrowseUiState.Failed(e.message ?: "Could not browse this genre")
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                BrowseViewModel(
                    savedStateHandle = this.createSavedStateHandle(),
                    discovery = container.discoveryRepository,
                    settings = container.settingsRepository
                )
            }
        }
    }
}

@Composable
fun BrowseScreen(
    container: AppContainer,
    onOpenDetail: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    val vm: BrowseViewModel = viewModel(factory = BrowseViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Column(modifier = Modifier.padding(top = 12.dp, start = 4.dp)) {
                Text(
                    text = vm.genre,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Across your extensions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when (val s = state) {
            BrowseUiState.Loading -> {
                Spacer(modifier = Modifier.height(40.dp))
                com.streambridge.app.ui.components.FullScreenLoading(label = "Browsing ${vm.genre}…")
            }

            is BrowseUiState.Ready -> {
                if (s.items.isEmpty()) {
                    EmptyState(
                        iconRes = com.streambridge.app.R.drawable.ic_empty_search,
                        title = "Nothing here",
                        body = "No ${vm.genre} titles were found in your extensions' catalogs."
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(
                            start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(s.items, key = { it.key + it.name }) { item ->
                            PosterCard(
                                item = item,
                                onClick = { onOpenDetail(item) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            is BrowseUiState.Failed -> ErrorState(
                message = s.message,
                onRetry = vm::load
            )
        }
    }
}
