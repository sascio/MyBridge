package com.streambridge.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streambridge.app.R
import com.streambridge.app.addon.model.HomeSection
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.core.TimeFormat
import com.streambridge.app.data.db.LibraryItemEntity
import com.streambridge.app.data.db.WatchProgressEntity
import com.streambridge.app.di.AppContainer
import com.streambridge.app.ui.components.EmptyState
import com.streambridge.app.ui.components.ErrorChip
import com.streambridge.app.ui.components.ErrorState
import com.streambridge.app.ui.components.FullScreenLoading
import com.streambridge.app.ui.components.Hero
import com.streambridge.app.ui.components.PosterCard
import com.streambridge.app.ui.components.Rail
import com.streambridge.app.ui.components.RailLoadingPlaceholder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenDetail: (MediaItem) -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenSearch: () -> Unit,
    onResumePlayback: (WatchProgressEntity) -> Unit,
    onBrowseGenre: (String) -> Unit
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val recentlyAdded by vm.recentlyAdded.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "STREAM BRIDGE",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Your media, your bridges",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onOpenExtensions) {
                        Icon(
                            imageVector = Icons.Filled.Extension,
                            contentDescription = "Extensions",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { if (uiState !is HomeUiState.Loading) vm.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
        when (val state = uiState) {
            HomeUiState.NoExtensions -> EmptyState(
                iconRes = R.drawable.ic_empty_extensions,
                title = "No extensions yet",
                body = "Stream Bridge starts completely empty — nothing is bundled or preinstalled. " +
                    "Add a Stremio-compatible extension to bring in catalogs, " +
                    "metadata and streams.",
                actionLabel = "Add your first extension",
                onAction = onOpenExtensions,
                modifier = Modifier.padding(padding)
            )

            HomeUiState.Loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                repeat(3) {
                    Spacer(modifier = Modifier.height(if (it == 0) 8.dp else 22.dp))
                    RailLoadingPlaceholder()
                }
            }

            is HomeUiState.Failed -> ErrorState(
                message = state.message,
                onRetry = vm::retry,
                modifier = Modifier.padding(padding)
            )

            is HomeUiState.Ready -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                val sections = state.data.sections
                if (sections.none { it is HomeSection.Rail || it is HomeSection.Hero }) {
                    item {
                        EmptyState(
                            iconRes = R.drawable.ic_empty_search,
                            title = "Nothing to show yet",
                            body = "Your extensions returned no content. Check that they are " +
                                "enabled and reachable, then refresh.",
                            actionLabel = "Retry",
                            onAction = vm::retry
                        )
                    }
                } else {
                    sections.forEach { section ->
                        when (section) {
                            is HomeSection.Hero -> item(key = "hero") {
                                Hero(items = section.items, onOpenDetails = onOpenDetail)
                            }

                            is HomeSection.Genres -> item(key = "genres") {
                                GenresRow(genres = section.genres, onBrowseGenre = onBrowseGenre)
                            }

                            is HomeSection.Rail -> item(key = section.key) {
                                Rail(
                                    title = section.title,
                                    subtitle = section.subtitle
                                ) {
                                    items(section.items, key = { it.key + it.name }) { item ->
                                        PosterCard(
                                            item = item,
                                            width = 122.dp,
                                            onClick = { onOpenDetail(item) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (continueWatching.isNotEmpty()) {
                        item(key = "continue-watching") {
                            ContinueWatchingRail(
                                entries = continueWatching,
                                onResume = onResumePlayback,
                                onOpenDetail = onOpenDetail
                            )
                        }
                    }

                    if (recentlyAdded.isNotEmpty()) {
                        item(key = "recently-added") {
                            Rail(title = "Recently added to your library") {
                                items(
                                    recentlyAdded,
                                    key = { it.metaKey }
                                ) { entry ->
                                    PosterCard(
                                        item = entry.toMediaItem(),
                                        width = 122.dp,
                                        onClick = { onOpenDetail(entry.toMediaItem()) }
                                    )
                                }
                            }
                        }
                    }

                    if (state.data.sourceErrors.isNotEmpty()) {
                        item(key = "source-errors") {
                            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                Text(
                                    text = "Some sources had problems",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                state.data.sourceErrors.take(3).forEach { message ->
                                    ErrorChip(message = message)
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ContinueWatchingRail(
    entries: List<WatchProgressEntity>,
    onResume: (WatchProgressEntity) -> Unit,
    onOpenDetail: (MediaItem) -> Unit
) {
    Rail(title = "Continue watching", subtitle = "Pick up where you left off") {
        items(entries, key = { it.metaKey + ":" + it.videoId }) { entry ->
            val fraction = TimeFormat.progressFraction(entry.positionMs, entry.durationMs)
            PosterCard(
                item = entry.toMediaItem(),
                width = 168.dp,
                progress = fraction,
                onClick = { onResume(entry) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenresRow(
    genres: List<com.streambridge.app.addon.model.GenreInfo>,
    onBrowseGenre: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Browse by genre",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            genres.forEach { genre ->
                androidx.compose.material3.SuggestionChip(
                    onClick = { onBrowseGenre(genre.name) },
                    label = { Text(text = "${genre.name} · ${genre.count}") },
                    shape = MaterialTheme.shapes.medium
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
    }
}

internal fun WatchProgressEntity.toMediaItem(): MediaItem = MediaItem(
    id = metaId,
    imdbId = imdbId,
    type = type,
    name = metaName,
    poster = poster,
    backdrop = backdrop,
    releaseInfo = if (season > 0) "S%02d E%02d".format(season, episode) else null,
    rating = null,
    description = episodeTitle,
    genres = emptyList(),
    source = "history"
)

internal fun LibraryItemEntity.toMediaItem(): MediaItem = MediaItem(
    id = id,
    imdbId = if (id.startsWith("tt")) id else null,
    type = type,
    name = name,
    poster = poster,
    backdrop = backdrop,
    releaseInfo = releaseInfo,
    rating = rating,
    description = description,
    genres = genres?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
    source = "library"
)
