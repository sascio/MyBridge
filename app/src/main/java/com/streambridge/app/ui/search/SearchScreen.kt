package com.streambridge.app.ui.search

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streambridge.app.R
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.data.discovery.DiscoveryRepository
import com.streambridge.app.data.settings.SettingsRepository
import com.streambridge.app.data.settings.SettingsState
import com.streambridge.app.di.AppContainer
import com.streambridge.app.ui.components.EmptyState
import com.streambridge.app.ui.components.ErrorState
import com.streambridge.app.ui.components.PosterCard
import com.streambridge.app.ui.components.shimmerBrush
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val movies: List<MediaItem>, val series: List<MediaItem>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val discovery: DiscoveryRepository,
    private val extensionManager: ExtensionManager,
    private val settings: SettingsRepository
) : ViewModel() {

    val query = MutableStateFlow("")

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var attempt = 0

    init {
        viewModelScope.launch {
            combine(
                query.debounce(350).distinctUntilChanged(),
                extensionManager.enabledExtensions
            ) { q, extensions -> q to extensions }
                .flatMapLatest { (q, _) ->
                    if (q.trim().length < 2) {
                        flowOf<SearchUiState>(SearchUiState.Idle)
                    } else {
                        flow {
                            emit(SearchUiState.Loading)
                            val result = try {
                                val settingsSnapshot: SettingsState = settings.state.first()
                                val results = discovery.search(q, settingsSnapshot)
                                SearchUiState.Results(
                                    movies = results.filter { it.type == "movie" },
                                    series = results.filter { it.type == "series" }
                                )
                            } catch (e: Exception) {
                                SearchUiState.Error(e.message ?: "Search failed")
                            }
                            emit(result)
                        }
                    }
                }
                .collect { _state.value = it }
        }
    }

    fun updateQuery(value: String) {
        query.value = value
    }

    fun clear() {
        query.value = ""
    }

    /** Re-runs the search for the current query. */
    fun retry() {
        val current = query.value
        query.value = ""
        query.value = current
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                SearchViewModel(
                    discovery = container.discoveryRepository,
                    extensionManager = container.extensionManager,
                    settings = container.settingsRepository
                )
            }
        }
    }
}

@Composable
fun SearchScreen(
    container: AppContainer,
    onOpenDetail: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = vm::updateQuery,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                placeholder = { Text(text = "Search movies & series…") },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    when {
                        query.isNotEmpty() -> IconButton(onClick = vm::clear) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = "Clear")
                        }

                        state is SearchUiState.Loading -> CircularProgressIndicator(
                            modifier = Modifier
                                .height(22.dp)
                                .padding(horizontal = 2.dp),
                            strokeWidth = 2.dp
                        )
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
        }

        when (val s = state) {
            SearchUiState.Idle -> EmptyState(
                iconRes = R.drawable.ic_empty_search,
                title = "Search across your extensions",
                body = "Results come from the extensions you installed — plus TMDB if you enabled it. " +
                    "Type at least two letters to start."
            )

            SearchUiState.Loading -> SearchLoadingGrid()

            is SearchUiState.Results -> {
                if (s.movies.isEmpty() && s.series.isEmpty()) {
                    EmptyState(
                        iconRes = R.drawable.ic_empty_search,
                        title = "No results",
                        body = "Nothing matched “${query}” in your extensions. Try a different title."
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(
                            start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (s.movies.isNotEmpty()) {
                            item(span = { GridItemSpan(3) }, key = "movies-label") {
                                SectionLabel(text = "Movies (${s.movies.size})")
                            }
                            items(s.movies, key = { "m:" + it.key + it.name }) { item ->
                                PosterCard(
                                    item = item,
                                    onClick = { onOpenDetail(item) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        if (s.series.isNotEmpty()) {
                            item(span = { GridItemSpan(3) }, key = "series-label") {
                                SectionLabel(text = "Series (${s.series.size})")
                            }
                            items(s.series, key = { "s:" + it.key + it.name }) { item ->
                                PosterCard(
                                    item = item,
                                    onClick = { onOpenDetail(item) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            is SearchUiState.Error -> ErrorState(
                title = "Search failed",
                message = s.message,
                onRetry = vm::retry
            )
        }
    }
}

@Composable
private fun SearchLoadingGrid() {
    val brush = shimmerBrush()
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(16.dp))
        repeat(3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(3) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(180.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(brush)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}
