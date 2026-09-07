package com.streambridge.app.ui.details

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.streambridge.app.addon.model.Episode
import com.streambridge.app.addon.model.MediaDetails
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.core.TimeFormat
import com.streambridge.app.di.AppContainer
import com.streambridge.app.player.PlaybackRequest
import com.streambridge.app.ui.components.ErrorState
import com.streambridge.app.ui.components.FullScreenLoading
import com.streambridge.app.ui.components.PosterCard
import com.streambridge.app.ui.components.Rail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPlayer: (PlaybackRequest) -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    onBrowseGenre: (String) -> Unit
) {
    val vm: DetailViewModel = viewModel(factory = DetailViewModel.factory(container))
    val detailState by vm.detailState.collectAsStateWithLifecycle()
    val flags by vm.libraryFlags.collectAsStateWithLifecycle()
    val progressEntries by vm.progressEntries.collectAsStateWithLifecycle()
    val selectedSeason by vm.selectedSeason.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val related by vm.related.collectAsStateWithLifecycle()
    val streamSheet by vm.streamSheet.collectAsStateWithLifecycle()
    val pendingRequest by vm.pendingRequest.collectAsStateWithLifecycle()
    val resolving by vm.resolving.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is DetailEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    LaunchedEffect(pendingRequest) {
        pendingRequest?.let { request ->
            onOpenPlayer(request)
            vm.consumePendingRequest()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = detailState) {
                DetailUiState.Loading -> FullScreenLoading(label = "Loading details…")

                is DetailUiState.Failed -> ErrorState(
                    title = "Details unavailable",
                    message = state.message,
                    onRetry = vm::loadDetails
                )

                is DetailUiState.Ready -> DetailContent(
                    details = state.details,
                    item = vm.item,
                    flags = flags,
                    progressEntries = progressEntries,
                    selectedSeason = selectedSeason,
                    episodes = episodes,
                    related = related,
                    resolving = resolving,
                    vm = vm,
                    onBack = onBack,
                    onOpenDetail = onOpenDetail,
                    onBrowseGenre = onBrowseGenre
                )
            }
        }
    }

    val sheetStreams = streamSheet
    if (sheetStreams != null) {
        ModalBottomSheet(onDismissRequest = vm::dismissStreamSheet) {
            StreamPickerContent(
                streams = sheetStreams,
                onSelect = vm::onStreamSelected
            )
        }
    }
}

@Composable
private fun DetailContent(
    details: MediaDetails,
    item: MediaItem,
    flags: LibraryFlags,
    progressEntries: List<com.streambridge.app.data.db.WatchProgressEntity>,
    selectedSeason: Int?,
    episodes: List<Episode>,
    related: List<MediaItem>,
    resolving: Boolean,
    vm: DetailViewModel,
    onBack: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    onBrowseGenre: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // -------------------------------------------------------------
        // Backdrop header
        // -------------------------------------------------------------
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
            ) {
                val headerImage = details.backdrop ?: details.poster ?: item.backdrop ?: item.poster
                if (headerImage != null) {
                    AsyncImage(
                        model = headerImage,
                        contentDescription = details.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0x50070609),
                                0.5f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.background
                            )
                        )
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // Poster + title block
        // -------------------------------------------------------------
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .offset(y = (-52).dp)
            ) {
                val posterUrl = details.poster ?: item.poster
                Box(
                    modifier = Modifier
                        .width(118.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (posterUrl != null) {
                        AsyncImage(
                            model = posterUrl,
                            contentDescription = details.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.padding(top = 52.dp)) {
                    Text(
                        text = details.name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val chips = buildList {
                            details.releaseInfo?.let { add(it) }
                            TimeFormat.minutesToLabel(TimeFormat.runtimeToMinutes(details.runtime))
                                .takeIf { it.isNotBlank() }
                                ?.let { add(it) }
                            add(if (details.isSeries) "Series" else "Movie")
                        }
                        Text(
                            text = chips.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    details.rating?.let { rating ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFD166),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$rating / 10",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFFFFD166)
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // Actions
        // -------------------------------------------------------------
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .offset(y = (-28).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = vm::playPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color(0xFF0B0A10)
                    )
                ) {
                    if (resolving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF0B0A10)
                        )
                    } else {
                        Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        val label = primaryActionLabel(details, progressEntries)
                        Text(text = label, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(
                    onClick = vm::toggleFavorite,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = if (flags.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (flags.favorite) "Remove from favorites" else "Add to favorites",
                        tint = if (flags.favorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                IconButton(
                    onClick = vm::toggleWatchlist,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = if (flags.watchlist) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = if (flags.watchlist) "Remove from watchlist" else "Add to watchlist",
                        tint = if (flags.watchlist) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // Genres
        // -------------------------------------------------------------
        if (details.genres.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(details.genres) { genre ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.clickable { onBrowseGenre(genre) }
                        ) {
                            Text(
                                text = genre,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }
        }

        // -------------------------------------------------------------
        // Description
        // -------------------------------------------------------------
        if (!details.description.isNullOrBlank()) {
            item {
                var expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = "Overview",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = details.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded.value) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { expanded.value = !expanded.value }) {
                        Text(text = if (expanded.value) "Show less" else "Show more")
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // Cast
        // -------------------------------------------------------------
        if (details.cast.isNotEmpty()) {
            item {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Cast",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(details.cast.take(20)) { name ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Box(
                                        modifier = Modifier.size(56.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = name.take(1).uppercase(),
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(72.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                }
            }
        }

        // -------------------------------------------------------------
        // Seasons & episodes
        // -------------------------------------------------------------
        if (details.isSeries) {
            item {
                Column {
                    Text(
                        text = "Episodes",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    if (details.seasons.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        SeasonChips(
                            seasons = details.seasons,
                            selected = selectedSeason,
                            onSelect = vm::selectSeason
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            if (episodes.isEmpty()) {
                item {
                    Text(
                        text = "No episodes listed for this season.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            } else {
                items(episodes, key = { it.id }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        progress = vm.progressFor(episode.id),
                        onClick = { vm.playEpisode(episode) }
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // Related
        // -------------------------------------------------------------
        if (related.isNotEmpty()) {
            item {
                Rail(title = "More like this") {
                    items(related, key = { it.key + it.name }) { relatedItem ->
                        PosterCard(
                            item = relatedItem,
                            width = 122.dp,
                            onClick = { onOpenDetail(relatedItem) }
                        )
                    }
                }
            }
        }
    }
}

private fun primaryActionLabel(
    details: MediaDetails,
    progressEntries: List<com.streambridge.app.data.db.WatchProgressEntity>
): String {
    if (details.isSeries) {
        val latest = progressEntries.firstOrNull()
        if (latest != null && latest.season > 0 && latest.positionMs < latest.durationMs * 95 / 100) {
            return "Resume  S%02d E%02d".format(latest.season, latest.episode)
        }
        return "Play first episode"
    }
    return "Play"
}

@Composable
private fun SeasonChips(
    seasons: List<Int>,
    selected: Int?,
    onSelect: (Int) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(seasons) { season ->
            val isSelected = season == selected
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.clickable { onSelect(season) }
            ) {
                Text(
                    text = if (season == 0) "Specials" else "Season $season",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Color(0xFF0B0A10) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    progress: com.streambridge.app.data.db.WatchProgressEntity?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(128.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (!episode.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = episode.thumbnail,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Surface(
                color = Color(0x99000000),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            if (progress != null) {
                val fraction = TimeFormat.progressFraction(progress.positionMs, progress.durationMs)
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x66000000))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "S%02d E%02d".format(episode.season, episode.number),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!episode.overview.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StreamPickerContent(
    streams: List<StreamOption>,
    onSelect: (StreamOption) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            text = "Choose a stream",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
        Text(
            text = "${streams.size} streams from your extensions",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        streams.forEach { stream ->
            StreamOptionRow(stream = stream, onClick = { onSelect(stream) })
        }
    }
}

@Composable
private fun StreamOptionRow(stream: StreamOption, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (badgeColor, badgeText) = when {
            stream.isPlayable -> MaterialTheme.colorScheme.primary to "HTTP"
            stream.isTorrent -> MaterialTheme.colorScheme.error to "TOR"
            else -> MaterialTheme.colorScheme.tertiary to "WEB"
        }
        Surface(
            color = badgeColor.copy(alpha = 0.18f),
            contentColor = badgeColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stream.shortLabel,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    stream.addonName,
                    stream.description?.take(70)
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (stream.isTorrent || stream.isExternal) {
            Icon(
                imageVector = Icons.Filled.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
