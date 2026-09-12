package com.streambridge.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.streambridge.app.R
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.core.TimeFormat
import com.streambridge.app.data.db.LibraryItemEntity
import com.streambridge.app.data.db.WatchProgressEntity
import com.streambridge.app.data.library.LibraryRepository
import com.streambridge.app.data.settings.SettingsRepository
import com.streambridge.app.di.AppContainer
import com.streambridge.app.ui.components.EmptyState
import com.streambridge.app.ui.components.PosterCard
import com.streambridge.app.ui.home.toMediaItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val libraryRepo: LibraryRepository,
    settings: SettingsRepository
) : ViewModel() {

    val favorites = libraryRepo.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchlist = libraryRepo.observeWatchlist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val continueWatching = settings.state
        .map { it.watchedThresholdPercent }
        .distinctUntilChanged()
        .flatMapLatest { threshold -> libraryRepo.observeContinueWatching(threshold) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history = libraryRepo.observeHistory(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeFromContinueWatching(entry: WatchProgressEntity) {
        viewModelScope.launch {
            libraryRepo.clearProgress(entry.metaKey, entry.videoId)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            libraryRepo.clearAllHistory()
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                LibraryViewModel(
                    libraryRepo = container.libraryRepository,
                    settings = container.settingsRepository
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenDetail: (MediaItem) -> Unit,
    onResumePlayback: (WatchProgressEntity) -> Unit
) {
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val watchlist by vm.watchlist.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Favorites", "Watchlist", "Continue", "History")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "Your library", fontWeight = FontWeight.Bold)
                },
                actions = {
                    if (tab == 3 && history.isNotEmpty()) {
                        IconButton(onClick = vm::clearHistory) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Clear history"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    )
                }
            }

            when (tab) {
                0 -> if (favorites.isEmpty()) {
                    LibraryEmpty("Nothing favorited yet", "Tap the heart on any title to keep it here.")
                } else {
                    MediaGrid(items = favorites.map { it.toMediaItem() }, onOpenDetail = onOpenDetail)
                }

                1 -> if (watchlist.isEmpty()) {
                    LibraryEmpty("Watchlist is empty", "Use the bookmark button to save titles for later.")
                } else {
                    MediaGrid(items = watchlist.map { it.toMediaItem() }, onOpenDetail = onOpenDetail)
                }

                2 -> if (continueWatching.isEmpty()) {
                    LibraryEmpty("Nothing in progress", "Start watching something and it will show up here.")
                } else {
                    ContinueList(
                        entries = continueWatching,
                        onResume = onResumePlayback,
                        onRemove = vm::removeFromContinueWatching
                    )
                }

                else -> if (history.isEmpty()) {
                    LibraryEmpty("No history yet", "Everything you watch is saved here, on your device only.")
                } else {
                    HistoryList(entries = history, onOpenDetail = onOpenDetail)
                }
            }
        }
    }
}

@Composable
private fun LibraryEmpty(title: String, body: String) {
    EmptyState(
        iconRes = R.drawable.ic_empty_library,
        title = title,
        body = body
    )
}

@Composable
private fun MediaGrid(items: List<MediaItem>, onOpenDetail: (MediaItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.key + it.name }) { item ->
            PosterCard(
                item = item,
                onClick = { onOpenDetail(item) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ContinueList(
    entries: List<WatchProgressEntity>,
    onResume: (WatchProgressEntity) -> Unit,
    onRemove: (WatchProgressEntity) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(entries, key = { it.metaKey + ":" + it.videoId }) { entry ->
            val fraction = TimeFormat.progressFraction(entry.positionMs, entry.durationMs)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onResume(entry) }
            ) {
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val thumb = entry.backdrop ?: entry.poster
                    if (thumb != null) {
                        AsyncImage(
                            model = thumb,
                            contentDescription = entry.metaName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Surface(
                        color = androidx.compose.ui.graphics.Color(0x99000000),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(androidx.compose.ui.graphics.Color(0x66000000))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.metaName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (entry.season > 0) {
                        Text(
                            text = "S%02d E%02d · %s".format(
                                entry.season,
                                entry.episode,
                                TimeFormat.msToDurationLabel(entry.durationMs - entry.positionMs) +
                                    " left"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = TimeFormat.msToDurationLabel(entry.durationMs - entry.positionMs) + " left",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { onRemove(entry) }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryList(
    entries: List<WatchProgressEntity>,
    onOpenDetail: (MediaItem) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 20.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(entries, key = { it.metaKey + ":" + it.videoId + ":" + it.updatedAt }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenDetail(entry.toMediaItem()) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(46.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (entry.poster != null) {
                        AsyncImage(
                            model = entry.poster,
                            contentDescription = entry.metaName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.metaName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            if (entry.season > 0) "S%02d E%02d".format(entry.season, entry.episode) else null,
                            relativeTime(entry.updatedAt)
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        diff < 30 * 86_400_000L -> "${diff / 86_400_000L}d ago"
        else -> "a while ago"
    }
}
