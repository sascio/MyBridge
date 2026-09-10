package com.streambridge.app.ui.details

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.streambridge.app.R
import com.streambridge.app.addon.model.Episode
import com.streambridge.app.addon.model.MediaDetails
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.core.TimeFormat
import com.streambridge.app.di.AppContainer
import com.streambridge.app.player.PlaybackRequest
import com.streambridge.app.ui.components.ErrorState
import com.streambridge.app.ui.components.FullScreenLoading
import com.streambridge.app.ui.components.PosterCard
import com.streambridge.app.ui.components.Rail

// Local palette for the redesigned detail page. The screen stays dark
// and cinematic: near-black surfaces, white primary text, grays for
// secondary text, and one accent (the theme primary) for active states.
private val DetailTextPrimary = Color(0xFFF6F6F6)
private val DetailTextSecondary = Color(0xFFC9C9C9)
private val DetailTextTertiary = Color(0xFF9C9C9C)
private val DetailChipSurface = Color(0xE61B1B22)
private val DetailChipSurfaceActive = Color(0xFF2C2C36)
private val DetailCardSurface = Color(0xFF16161C)
private val DetailDivider = Color(0xFF232329)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenSources: (PlaybackRequest, String, String) -> Unit,
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
    val pendingRequest by vm.pendingRequest.collectAsStateWithLifecycle()
    val watchedThreshold by vm.watchedThreshold.collectAsStateWithLifecycle()
    val isWatched = progressEntries.any { entry ->
        TimeFormat.isFinished(entry.positionMs, entry.durationMs, watchedThreshold)
    }
    val snackbarHostState = remember { SnackbarHostState() }

    // Adaptive background: the dominant color of this title's artwork,
    // behind everything, fading into the base theme color. Extracted
    // asynchronously and cached per title (see AdaptiveBackground.kt).
    val item = vm.item
    val headerArtwork = when (val state = detailState) {
        is DetailUiState.Ready ->
            state.details.backdrop ?: state.details.poster ?: item.backdrop ?: item.poster
        else -> item.backdrop ?: item.poster
    }
    val adaptiveColor by rememberAdaptiveBackgroundColor(
        imageUrl = headerArtwork,
        mediaKey = item.key
    )

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is DetailEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    LaunchedEffect(pendingRequest) {
        pendingRequest?.let { request ->
            val header = vm.pendingHeaderInfo.value
            onOpenSources(request, header.releaseInfo, header.rating)
            vm.consumePendingRequest()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Base + adaptive tint: strongest at the top behind the hero,
            // fully faded by ~46% height; the rest of the page is the
            // untouched base color.
            AdaptiveBackgroundGradient(
                adaptiveColor = adaptiveColor,
                baseColor = MaterialTheme.colorScheme.background
            )
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
                    isWatched = isWatched,
                    progressEntries = progressEntries,
                    selectedSeason = selectedSeason,
                    episodes = episodes,
                    related = related,
                    vm = vm,
                    onBack = onBack,
                    onOpenDetail = onOpenDetail,
                    onBrowseGenre = onBrowseGenre
                )
            }
        }
    }
}


@Composable
private fun DetailContent(
    details: MediaDetails,
    item: MediaItem,
    flags: LibraryFlags,
    isWatched: Boolean,
    progressEntries: List<com.streambridge.app.data.db.WatchProgressEntity>,
    selectedSeason: Int?,
    episodes: List<Episode>,
    related: List<MediaItem>,
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
        // Hero backdrop: full-bleed artwork, dimmed for readability,
        // with the title (official logo art when the addon provides it)
        // and genre line centered over it.
        // -------------------------------------------------------------
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
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
                // Readability scrim: darker at the very top (over the
                // status-bar icons), breathing room mid-frame, solid
                // fade into the page background at the bottom edge.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0x96000000),
                                0.34f to Color(0x24000000),
                                0.62f to Color(0x66000000),
                                1f to MaterialTheme.colorScheme.background
                            )
                        )
                )
                // Back navigation (existing handler), glass circle.
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = Color(0x59000000),
                    border = BorderStroke(1.dp, Color(0x40FFFFFF)),
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(start = 14.dp, top = 6.dp)
                        .size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                }
                // Brand mark centered at the top of the hero.
                Box(
                    modifier = Modifier
                        .statusBarsPadding()
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                ) {
                    DetailBrandMark()
                }
                // Title block: logo art (or bold white title) + genres.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val logo = details.logo
                    if (!logo.isNullOrBlank()) {
                        AsyncImage(
                            model = logo,
                            contentDescription = details.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .heightIn(max = 72.dp)
                        )
                    } else {
                        Text(
                            text = details.name,
                            style = MaterialTheme.typography.displaySmall.copy(fontSize = 32.sp),
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (details.genres.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        // Genre tags as a centered "A • B • C" line; each
                        // tag still opens genre browse (existing handler).
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            details.genres.forEachIndexed { index, genre ->
                                if (index > 0) {
                                    Text(
                                        text = "  •  ",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = DetailTextSecondary
                                    )
                                }
                                Text(
                                    text = genre,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = DetailTextSecondary,
                                    maxLines = 1,
                                    modifier = Modifier.clickable { onBrowseGenre(genre) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // Action buttons: white Play pill + circular toggles. Every
        // onClick is the pre-existing handler, unchanged.
        // -------------------------------------------------------------
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = vm::playPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF2F2F2),
                        contentColor = Color(0xFF101014)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val label = primaryActionLabel(details, progressEntries)
                    Text(text = label, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(12.dp))
                DetailCircleButton(
                    onClick = { if (isWatched) vm.markUnwatched() else vm.markWatched() },
                    active = isWatched,
                    contentDescription = if (isWatched) "Mark unwatched" else "Mark watched"
                ) {
                    Icon(
                        imageVector = if (isWatched) Icons.Filled.TaskAlt else Icons.Outlined.TaskAlt,
                        contentDescription = null,
                        tint = if (isWatched) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                DetailCircleButton(
                    onClick = vm::toggleWatchlist,
                    active = flags.watchlist,
                    contentDescription = if (flags.watchlist) "Remove from watchlist" else "Add to watchlist"
                ) {
                    Icon(
                        imageVector = if (flags.watchlist) Icons.Filled.Check else Icons.Filled.Add,
                        contentDescription = null,
                        tint = if (flags.watchlist) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                DetailCircleButton(
                    onClick = vm::toggleFavorite,
                    active = flags.favorite,
                    contentDescription = if (flags.favorite) "Remove from favorites" else "Add to favorites"
                ) {
                    Icon(
                        imageVector = if (flags.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = if (flags.favorite) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // Metadata row: year • runtime • type badge, then rating badge.
        // -------------------------------------------------------------
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    details.releaseInfo?.takeIf { it.isNotBlank() }?.let { release ->
                        Text(
                            text = release,
                            style = MaterialTheme.typography.titleSmall,
                            color = DetailTextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "  •  ",
                            style = MaterialTheme.typography.titleSmall,
                            color = DetailTextTertiary
                        )
                    }
                    TimeFormat.minutesToLabel(TimeFormat.runtimeToMinutes(details.runtime))
                        .takeIf { it.isNotBlank() }
                        ?.let { runtime ->
                            Text(
                                text = runtime,
                                style = MaterialTheme.typography.titleSmall,
                                color = DetailTextSecondary
                            )
                            Text(
                                text = "  •  ",
                                style = MaterialTheme.typography.titleSmall,
                                color = DetailTextTertiary
                            )
                        }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Color(0xFF7A7A85))
                    ) {
                        Text(
                            text = if (details.isSeries) "SERIES" else "MOVIE",
                            style = MaterialTheme.typography.labelMedium,
                            color = DetailTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                details.rating?.takeIf { it.isNotBlank() }?.let { rating ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DetailChipSurface
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFD166),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "$rating / 10",
                                style = MaterialTheme.typography.titleSmall,
                                color = DetailTextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }
        }

        // -------------------------------------------------------------
        // Crew: gray label + white names on one line each.
        // -------------------------------------------------------------
        val crew = buildList {
            if (details.director.isNotEmpty()) add("Director" to details.director)
            if (details.writer.isNotEmpty()) add("Writers" to details.writer)
        }
        if (crew.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    crew.forEach { (role, names) ->
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = DetailTextTertiary,
                                        fontWeight = FontWeight.Medium
                                    )
                                ) { append("$role:   ") }
                                withStyle(
                                    SpanStyle(
                                        color = DetailTextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                ) { append(names.joinToString(", ")) }
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // -------------------------------------------------------------
        // Synopsis: light gray, clamped to 4 lines, expandable
        // (existing expand/collapse state).
        // -------------------------------------------------------------
        if (!details.description.isNullOrBlank()) {
            item {
                var expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = details.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = DetailTextSecondary,
                        maxLines = if (expanded.value) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { expanded.value = !expanded.value }) {
                        Text(
                            text = if (expanded.value) "Show Less \u25B4" else "Show More \u25BE",
                            color = DetailTextPrimary
                        )
                    }
                }
            }
        }

        // Trailer (external player, existing intent) as a pill row.
        if (!details.trailer.isNullOrBlank()) {
            item {
                val context = LocalContext.current
                Surface(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(details.trailer)
                                )
                            )
                        }
                    },
                    shape = RoundedCornerShape(percent = 50),
                    color = Color(0x26FFFFFF),
                    border = BorderStroke(1.dp, Color(0x40FFFFFF)),
                    modifier = Modifier.padding(horizontal = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayCircleOutline,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Watch trailer",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // -------------------------------------------------------------
        // Cast: horizontal circular avatars (initials — the protocol
        // provides names, not headshots), white bold names.
        // -------------------------------------------------------------
        if (details.cast.isNotEmpty()) {
            item {
                Column {
                    Text(
                        text = "Cast",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = DetailTextPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(details.cast.take(20)) { name ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = CircleShape,
                                    color = DetailChipSurface,
                                    border = BorderStroke(1.dp, Color(0x26FFFFFF))
                                ) {
                                    Box(
                                        modifier = Modifier.size(64.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = name.take(1).uppercase(),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = DetailTextPrimary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = DetailTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(72.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        // -------------------------------------------------------------
        // Seasons & episodes (series) — unchanged behavior.
        // -------------------------------------------------------------
        if (details.isSeries) {
            item {
                Column {
                    Text(
                        text = "Episodes",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = DetailTextPrimary,
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
                        color = DetailTextTertiary,
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
        // Details card: key-value list with thin dividers, built only
        // from the fields this title's metadata actually provides.
        // -------------------------------------------------------------
        val detailRows = buildList {
            add("Type" to if (details.isSeries) "Series" else "Movie")
            details.releaseInfo?.takeIf { it.isNotBlank() }?.let { add("Release" to it) }
            TimeFormat.minutesToLabel(TimeFormat.runtimeToMinutes(details.runtime))
                .takeIf { it.isNotBlank() }
                ?.let { add("Runtime" to it) }
            details.country?.takeIf { it.isNotBlank() }?.let { add("Country" to it) }
            if (details.genres.isNotEmpty()) add("Genres" to details.genres.joinToString(", "))
            details.awards?.takeIf { it.isNotBlank() }?.let { add("Awards" to it) }
            sourceHost(details.sourceAddonBase)?.let { add("Source" to it) }
        }
        if (detailRows.size > 1) {
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = if (details.isSeries) "Series Details" else "Movie Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = DetailTextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = DetailCardSurface
                    ) {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            detailRows.forEachIndexed { index, (label, value) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = DetailTextTertiary,
                                        modifier = Modifier.weight(0.34f)
                                    )
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = DetailTextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(0.66f)
                                    )
                                }
                                if (index < detailRows.lastIndex) {
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 16.dp)
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(DetailDivider)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        // -------------------------------------------------------------
        // More like this (existing rail + navigation).
        // -------------------------------------------------------------
        if (related.isNotEmpty()) {
            item {
                Rail(title = "More Like This") {
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

        // -------------------------------------------------------------
        // Footer: honest attribution to the addon that provided the
        // metadata for this title.
        // -------------------------------------------------------------
        sourceHost(details.sourceAddonBase)?.let { host ->
            item {
                Text(
                    text = "Metadata by $host",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7C7C86),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                )
            }
        }
    }
}

/** Small rounded brand mark shown centered at the top of the hero. */
@Composable
private fun DetailBrandMark() {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF6E5BFF), Color(0xFF3FA9F5)))
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Circular secondary action button (dark surface, white icon; the theme
 * accent marks the active state). Purely visual — the onClick is the
 * caller's existing handler.
 */
@Composable
private fun DetailCircleButton(
    onClick: () -> Unit,
    active: Boolean,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) DetailChipSurfaceActive else DetailChipSurface,
        border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
        modifier = Modifier.size(52.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/** Display host of an addon base URL, for honest attribution. */
private fun sourceHost(baseUrl: String?): String? =
    baseUrl
        ?.removePrefix("https://")
        ?.removePrefix("http://")
        ?.substringBefore('/')
        ?.takeIf { it.isNotBlank() }

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
                    DetailChipSurface
                },
                modifier = Modifier.clickable { onSelect(season) }
            ) {
                Text(
                    text = if (season == 0) "Specials" else "Season $season",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Color(0xFF0B0A10) else DetailTextPrimary,
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
                color = DetailTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!episode.overview.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = DetailTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
