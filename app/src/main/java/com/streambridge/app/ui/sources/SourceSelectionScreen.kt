package com.streambridge.app.ui.sources

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.di.AppContainer
import com.streambridge.app.player.PlaybackRequest
import com.streambridge.app.ui.components.pressScale
import com.streambridge.app.ui.components.shimmer

// ---------------------------------------------------------------------
// Local palette — the same near-black cinematic tones as the detail
// page, so the source screen reads as one continuous experience.
// ---------------------------------------------------------------------
private val SourceScreenBg = Color(0xFF0D0D0D)
private val SourceCardSurface = Color(0xFF16161C)
private val SourceChipSurface = Color(0xFF232329)
private val SourceTextPrimary = Color(0xFFF6F6F6)
private val SourceTextSecondary = Color(0xFFC9C9C9)
private val SourceTextTertiary = Color(0xFF9C9C9C)
private val SourcePillActive = Color(0xFFF2F2F2)
private val SourcePillActiveContent = Color(0xFF101014)

/**
 * The dedicated source-selection screen (Nuvio-style).
 *
 * Opens IMMEDIATELY when the user presses Play — no waiting on this
 * screen for providers. Every installed source (Stremio-style addons
 * and Nuvio plugin providers) runs concurrently and results appear
 * progressively: sources that return nothing usable simply have no
 * selector tab for this request; they are re-attempted on Reload.
 *
 * Layout: cinematic artwork header with a proper gradient fade, then
 * the horizontal selector — Reload, ALL, then the sources that
 * actually returned streams — and below it the compact grouped
 * stream list. Selecting a stream hands it to the existing player.
 */
@Composable
fun SourceSelectionScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPlayer: (PlaybackRequest) -> Unit
) {
    val vm: SourceSelectionViewModel = viewModel(factory = SourceSelectionViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val pendingRequest by vm.pendingRequest.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is SourceSelectionEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    // The user picked a stream: hand it to the player without any
    // second resolution (the exact stream travels with the request).
    LaunchedEffect(pendingRequest) {
        pendingRequest?.let { request ->
            onOpenPlayer(request)
            vm.consumePendingRequest()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.navigationBarsPadding()) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SourceScreenBg)
        ) {
            SourceHeader(
                request = vm.request,
                releaseInfo = vm.releaseInfo,
                rating = vm.rating,
                onBack = onBack
            )

            SourceSelectorRow(
                tabs = state.tabs,
                selectedTab = state.selectedTab,
                running = state.running,
                onSelect = vm::selectTab,
                onReload = vm::reload
            )

            Box(modifier = Modifier.weight(1f)) {
                SourceListContent(
                    state = state,
                    onPlay = vm::play
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------

@Composable
private fun SourceHeader(
    request: PlaybackRequest,
    releaseInfo: String,
    rating: String,
    onBack: () -> Unit
) {
    val artwork = request.backdrop ?: request.poster
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(268.dp)
    ) {
        if (artwork != null) {
            AsyncImage(
                model = artwork,
                contentDescription = request.metaName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // No artwork available: an honest, calm gradient instead of
            // an invented placeholder image.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1A1A22), Color(0xFF101014))
                        )
                    )
            )
        }

        // Cinematic treatment: a top scrim so the status bar and back
        // button stay readable, and a bottom fade that dissolves the
        // artwork into the screen background.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x99000000), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        0.82f to Color(0xD90D0D0D),
                        1f to SourceScreenBg
                    )
                )
        )

        // Back: a circular translucent control in the top corner.
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, top = 10.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0x66000000))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = SourceTextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }

        // Title block anchored to the bottom edge of the artwork.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (request.poster != null) {
                AsyncImage(
                    model = request.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(52.dp)
                        .height(78.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                if (request.season > 0 && request.episode > 0) {
                    Text(
                        text = "S%02d E%02d".format(request.season, request.episode),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = request.metaName.ifBlank { "Select a source" },
                    style = MaterialTheme.typography.headlineMedium,
                    color = SourceTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val episodeTitle = request.episodeTitle
                if (!episodeTitle.isNullOrBlank()) {
                    Text(
                        text = episodeTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SourceTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val metaLine = listOfNotNull(
                    if (request.type.equals("series", true)) "Series" else "Movie",
                    releaseInfo.takeIf { it.isNotBlank() },
                    rating.takeIf { it.isNotBlank() }?.let { "★ $it" }
                ).joinToString("  ·  ")
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = SourceTextTertiary
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// Source selector:  ↻ Reload | ALL | available sources
// ---------------------------------------------------------------------

@Composable
private fun SourceSelectorRow(
    tabs: List<SourceTab>,
    selectedTab: String,
    running: Boolean,
    onSelect: (String) -> Unit,
    onReload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReloadControl(running = running, onReload = onReload)
        Spacer(modifier = Modifier.width(10.dp))
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 0.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(tabs, key = { tab -> tab.id }) { tab ->
                SourcePill(
                    label = tab.label,
                    selected = tab.id == selectedTab,
                    onClick = { onSelect(tab.id) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

/** The circular ↻ control — part of the selector row, not a button elsewhere. */
@Composable
private fun ReloadControl(running: Boolean, onReload: () -> Unit) {
    val spin = rememberInfiniteTransition(label = "reload-spin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "reload-angle"
    )
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .padding(start = 20.dp)
            .size(38.dp)
            .pressScale(interactionSource)
            .clip(CircleShape)
            .background(
                if (running) Color(0xFF2C2C36) else SourceChipSurface
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !running,
                onClick = onReload
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "Reload sources",
            tint = if (running) MaterialTheme.colorScheme.primary else SourceTextSecondary,
            modifier = Modifier
                .size(19.dp)
                .graphicsLayer { rotationZ = if (running) angle else 0f }
        )
    }
}

/** One selector pill. Active = filled light pill, compact, no hard borders. */
@Composable
private fun SourcePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container by animateColorAsState(
        targetValue = if (selected) SourcePillActive else SourceChipSurface,
        animationSpec = tween(durationMillis = 220),
        label = "pill-container"
    )
    val content by animateColorAsState(
        targetValue = if (selected) SourcePillActiveContent else SourceTextSecondary,
        animationSpec = tween(durationMillis = 220),
        label = "pill-content"
    )
    Box(
        modifier = modifier
            .height(38.dp)
            .widthIn(max = 176.dp)
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------
// Stream list
// ---------------------------------------------------------------------

@Composable
private fun SourceListContent(
    state: SourceSelectionUiState,
    onPlay: (StreamOption) -> Unit
) {
    when {
        // No eligible sources at all — honest explanation, no fake data.
        state.results.isEmpty() -> CenteredNote(
            icon = { Icon(Icons.Filled.SearchOff, contentDescription = null, tint = SourceTextTertiary, modifier = Modifier.size(40.dp)) },
            title = "No stream sources",
            message = "Install a stream extension or a plugin repository, then play this title again."
        )

        // Everything settled and nothing usable came back.
        state.finished && !state.hasStreams -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            Icon(
                imageVector = Icons.Filled.SearchOff,
                contentDescription = null,
                tint = SourceTextTertiary,
                modifier = Modifier.size(42.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No streams found",
                style = MaterialTheme.typography.titleMedium,
                color = SourceTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "None of your sources returned a playable stream for this title.",
                style = MaterialTheme.typography.bodyMedium,
                color = SourceTextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Tap ↻ to try again",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(40.dp))
        }

        else -> LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!state.hasStreams && state.running) {
                // First results still on their way: a calm skeleton,
                // not a full-screen spinner.
                items(4) {
                    SkeletonCard()
                }
            } else {
                state.groups.forEach { group ->
                    item(key = "header:${group.sourceId}") {
                        GroupHeader(title = group.title, count = group.streams.size)
                    }
                    items(group.streams, key = { stream -> "${group.sourceId}:${stream.id}" }) { stream ->
                        StreamCard(
                            stream = stream,
                            showProviderChip = state.selectedTab == SourceSelectionUiState.TAB_ALL,
                            onClick = { onPlay(stream) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            if (state.running) {
                item(key = "finding-more") {
                    FindingMoreRow(pending = state.pendingCount)
                }
            }

            if (state.finished && state.hiddenCount > 0) {
                item(key = "hidden-note") {
                    Text(
                        text = "${state.hiddenCount} " +
                            (if (state.hiddenCount == 1) "source returned" else "sources returned") +
                            " no streams",
                        style = MaterialTheme.typography.labelSmall,
                        color = SourceTextTertiary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredNote(
    icon: @Composable () -> Unit,
    title: String,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = SourceTextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = SourceTextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun GroupHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = SourceTextTertiary,
            letterSpacing = 1.1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = SourceTextTertiary
        )
    }
}

/** Compact stream card: quality tile, real label, only-provided metadata chips. */
@Composable
private fun StreamCard(
    stream: StreamOption,
    showProviderChip: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interactionSource, pressedScale = 0.98f)
            .clip(RoundedCornerShape(14.dp))
            .background(SourceCardSurface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        QualityTile(stream)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stream.shortLabel,
                style = MaterialTheme.typography.titleSmall,
                color = SourceTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(5.dp))
            MetadataChips(stream = stream, showProviderChip = showProviderChip)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Leading tile: the resolution when it is known ("1080p"), else the
 * first short quality tag, else a neutral play glyph. Never invented.
 */
@Composable
private fun QualityTile(stream: StreamOption) {
    val text = when {
        stream.resolution > 0 -> "${stream.resolution}p"
        stream.quality.isNotBlank() -> stream.quality
            .split(" ", "-", "_")
            .firstOrNull { it.isNotBlank() && it.length <= 6 }
            ?.uppercase()
        else -> null
    }
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Metadata chips — every value comes from the source; absent values are absent. */
@Composable
private fun MetadataChips(stream: StreamOption, showProviderChip: Boolean) {
    val codec = when {
        stream.quality.contains("HEVC", ignoreCase = true) -> "HEVC"
        stream.quality.contains("AVC", ignoreCase = true) -> "AVC"
        else -> null
    }
    val chips = buildList {
        if (showProviderChip && stream.addonName.isNotBlank()) add(stream.addonName)
        if (stream.language.isNotBlank()) add(stream.language.uppercase())
        if (stream.sizeLabel.isNotBlank()) add(stream.sizeLabel)
        codec?.let { add(it) }
    }
    if (chips.isEmpty()) {
        // Nothing provided: a quiet provider-less line, not fake data.
        if (!showProviderChip && stream.addonName.isNotBlank()) {
            Text(
                text = stream.addonName,
                style = MaterialTheme.typography.labelSmall,
                color = SourceTextTertiary
            )
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { chip ->
            val isProvider = chip == stream.addonName
            Text(
                text = if (chip.length > 18) chip.take(17) + "…" else chip,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isProvider) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isProvider) MaterialTheme.colorScheme.primary else SourceTextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isProvider) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            SourceChipSurface
                        }
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun SkeletonCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SourceCardSurface)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .shimmer()
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmer()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.38f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmer()
            )
        }
    }
}

@Composable
private fun FindingMoreRow(pending: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(15.dp),
            strokeWidth = 2.dp,
            color = SourceTextTertiary
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (pending > 1) "Finding more sources…" else "Finding sources…",
            style = MaterialTheme.typography.bodySmall,
            color = SourceTextSecondary
        )
    }
}
