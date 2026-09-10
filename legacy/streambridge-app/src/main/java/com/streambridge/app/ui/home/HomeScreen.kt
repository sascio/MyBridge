package com.streambridge.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streambridge.app.R
import com.streambridge.app.addon.model.HomeSection
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.data.db.LibraryItemEntity
import com.streambridge.app.data.db.WatchProgressEntity
import com.streambridge.app.di.AppContainer
import com.streambridge.app.ui.components.ContinueWatchingCard
import com.streambridge.app.ui.components.EmptyState
import com.streambridge.app.ui.components.ErrorChip
import com.streambridge.app.ui.components.ErrorState
import com.streambridge.app.ui.components.Hero
import com.streambridge.app.ui.components.PosterCard
import com.streambridge.app.ui.components.Rail
import com.streambridge.app.ui.components.RailLoadingPlaceholder
import com.streambridge.app.ui.theme.PillShape

/**
 * Cinematic, edge-to-edge Home: the hero artwork extends behind the
 * status bar and a transparent upper-navigation overlay; content rails
 * flow underneath with generous spacing. No solid top app bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenDetail: (MediaItem) -> Unit,
    onOpenExtensions: () -> Unit,
    onResumePlayback: (WatchProgressEntity) -> Unit,
    onBrowseGenre: (String) -> Unit,
    onPlayItem: (MediaItem) -> Unit,
    onOpenCatalog: (com.streambridge.app.addon.model.CatalogRef) -> Unit
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val recentlyAdded by vm.recentlyAdded.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val uiSettings by vm.uiSettings.collectAsStateWithLifecycle()
    val catalogRefs by vm.catalogRefs.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { if (uiState !is HomeUiState.Loading) vm.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            when (val state = uiState) {
                HomeUiState.NoExtensions -> EmptyState(
                    iconRes = R.drawable.ic_empty_extensions,
                    title = "No addons yet",
                    body = "Stream Bridge starts completely empty — nothing is bundled or preinstalled. " +
                        "Add a Stremio-compatible addon to bring in catalogs, " +
                        "metadata and streams.",
                    actionLabel = "Add your first addon",
                    onAction = onOpenExtensions,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(top = 72.dp)
                )

                HomeUiState.Loading -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(top = 64.dp)
                ) {
                    repeat(3) {
                        Spacer(modifier = Modifier.height(if (it == 0) 8.dp else 22.dp))
                        RailLoadingPlaceholder()
                    }
                }

                is HomeUiState.Failed -> ErrorState(
                    message = state.message,
                    onRetry = vm::retry,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(top = 72.dp)
                )

                is HomeUiState.Ready -> {
                    val pillScroll = com.streambridge.app.ui.components.LocalPillNavScroll.current
                    val listModifier = if (pillScroll != null) {
                        Modifier.nestedScroll(pillScroll.nestedScrollConnection)
                    } else {
                        Modifier
                    }
                    // Derived state hoisted OUT of the LazyColumn scope:
                    // LazyListScope is not a composable scope, and these
                    // would be re-computed on every lazy-list rebuild.
                    val sections = remember(state.data.sections, uiSettings.homeShowRecommendations) {
                        state.data.sections.filterNot { section ->
                            section is HomeSection.Rail &&
                                section.key == "recommended" &&
                                !uiSettings.homeShowRecommendations
                        }
                    }
                    val seeAllBySection = remember(catalogRefs) {
                        catalogRefs.associateBy { ref ->
                            "${ref.addonId}::${ref.catalogId}:${ref.type}"
                        }
                    }
                    val posterWidth = when (uiSettings.posterSize) {
                        "small" -> 104.dp
                        "large" -> 140.dp
                        else -> 122.dp
                    }
                    LazyColumn(
                        modifier = listModifier.fillMaxSize(),
                        // Room for the navigation bar that overlays the
                        // content (floating pill, or the classic bar in
                        // the traditional layout mode).
                        contentPadding = PaddingValues(
                            bottom = if (uiSettings.navLayout == "classic") 152.dp else 128.dp
                        )
                    ) {
                        if (sections.none { it is HomeSection.Rail || it is HomeSection.Hero }) {
                            item {
                                EmptyState(
                                    iconRes = R.drawable.ic_empty_search,
                                    title = "Nothing to show yet",
                                    body = "Your addons returned no content. Check that they are " +
                                        "enabled and reachable, then refresh.",
                                    actionLabel = "Retry",
                                    onAction = vm::retry,
                                    modifier = Modifier
                                        .statusBarsPadding()
                                        .padding(top = 72.dp)
                                )
                            }
                        } else {
                            sections.forEach { section ->
                                when (section) {
                                    is HomeSection.Hero -> item(key = "hero") {
                                        Hero(
                                            items = section.items,
                                            onPlay = onPlayItem,
                                            onOpenDetails = onOpenDetail
                                        )
                                    }

                                    is HomeSection.Genres -> item(key = "genres") {
                                        GenresRow(genres = section.genres, onBrowseGenre = onBrowseGenre)
                                    }

                                    is HomeSection.Rail -> item(key = section.key) {
                                        val seeAllRef = seeAllBySection[section.key]
                                        Rail(
                                            title = section.title,
                                            subtitle = section.subtitle,
                                            onSeeAll = seeAllRef?.let { ref ->
                                                { onOpenCatalog(ref) }
                                            }
                                        ) {
                                            items(section.items, key = { it.key + it.name }) { item ->
                                                PosterCard(
                                                    item = item,
                                                    width = posterWidth,
                                                    onClick = { onOpenDetail(item) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (continueWatching.isNotEmpty() && uiSettings.homeShowContinueWatching) {
                                item(key = "continue-watching") {
                                    ContinueWatchingRail(
                                        entries = continueWatching,
                                        onResume = onResumePlayback
                                    )
                                }
                            }

                            if (recentlyAdded.isNotEmpty() && uiSettings.homeShowRecentlyAdded) {
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

        // Upper navigation: transparent overlay floating ON the hero
        // artwork — never a solid bar above it. Search lives in the
        // bottom navigation and plugin/addon management in
        // Settings > Content & Discovery, so this stays brand-only.
        HomeTopOverlay()
    }
}

/**
 * Transparent upper navigation. Renders over whatever is on screen
 * (hero artwork when at the top of Home) with a soft gradient scrim
 * for readability.
 */
@Composable
private fun HomeTopOverlay() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xB3000000),
                    1f to Color(0x00000000)
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "STREAM BRIDGE",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    text = "Your media, your bridges",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xB3CFCFCF)
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingRail(
    entries: List<WatchProgressEntity>,
    onResume: (WatchProgressEntity) -> Unit
) {
    Rail(title = "Continue watching", subtitle = "Pick up where you left off") {
        itemsIndexed(entries, key = { _, entry -> entry.metaKey + ":" + entry.videoId }) { index, entry ->
            ContinueWatchingCard(
                entry = entry,
                width = 280.dp,
                // The most recent unfinished item is what plays next.
                isNextUp = index == 0,
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
                Surface(
                    onClick = { onBrowseGenre(genre.name) },
                    shape = PillShape,
                    color = Color(0x14FFFFFF),
                    contentColor = Color(0xFFEFEFEF),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1FFFFFFF))
                ) {
                    Text(
                        text = "${genre.name} · ${genre.count}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
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
