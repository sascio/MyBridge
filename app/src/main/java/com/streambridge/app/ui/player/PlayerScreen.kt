package com.streambridge.app.ui.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.core.TimeFormat
import com.streambridge.app.di.AppContainer
import com.streambridge.app.player.PlayerEvent
import com.streambridge.app.player.PlayerPhase
import com.streambridge.app.player.PlayerViewModel
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(container, appContext))

    val phase by vm.phase.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val request by vm.currentRequest.collectAsStateWithLifecycle()
    val sourceLabel by vm.sourceLabel.collectAsStateWithLifecycle()
    val showPicker by vm.showPicker.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()

    // Immersive mode: hide system bars, keep the screen on, landscape.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.context.findActivity()?.requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.context.findActivity()?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // One-shot events: snackbar messages + external links.
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is PlayerEvent.Message -> {
                    // surfaced in-player as a toast-like caption; kept minimal
                }

                is PlayerEvent.OpenExternal -> {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(event.url))
                        )
                    } catch (_: ActivityNotFoundException) {
                    }
                }
            }
        }
    }

    // Ended -> persist + autoplay next.
    LaunchedEffect(playback.ended) {
        if (playback.ended) {
            vm.onPlaybackEnded()
        }
    }

    // Persist progress when the app goes to the background.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                vm.persistProgressNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Video surface is present whenever we are playing or picking.
        if (phase is PlayerPhase.Playing || phase is PlayerPhase.Picking) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        setUseController(false)
                        this.player = vm.holder.player
                    }
                },
                update = { playerView ->
                    playerView.player = vm.holder.player
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        when (val p = phase) {
            PlayerPhase.Resolving -> CenterCaption(
                title = "Resolving streams…",
                body = "Asking ${sourceLabel.ifBlank { "your extensions" }} for playable links"
            )

            is PlayerPhase.Picking -> Surface(
                color = Color(0xAA000000),
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(0.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Multiple streams available",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Choose which source to watch.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFACA5C2),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(onClick = vm::openPicker) {
                        Text(text = "Choose stream")
                    }
                }
            }

            is PlayerPhase.Playing -> PlayerControls(
                vm = vm,
                playback = playback,
                request = request,
                sourceLabel = sourceLabel,
                hasNext = vm.hasNextEpisode,
                hasPrevious = vm.hasPreviousEpisode,
                onBack = onBack
            )

            is PlayerPhase.NoStreams -> CenterCaption(
                title = "No playable stream",
                body = p.message,
                actionLabel = "Try again",
                onAction = vm::retryPlayback
            )

            is PlayerPhase.Error -> CenterCaption(
                title = "Playback error",
                body = p.message,
                actionLabel = "Retry",
                onAction = vm::retryPlayback
            )
        }
    }

    if (showPicker) {
        ModalBottomSheet(onDismissRequest = vm::dismissPicker) {
            StreamPickerSheet(
                streams = currentStreamsOf(vm),
                onSelect = vm::selectStream
            )
        }
    }
}

private fun currentStreamsOf(vm: PlayerViewModel): List<StreamOption> = vm.streamsSnapshot

@Composable
private fun CenterCaption(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFACA5C2),
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onAction, shape = RoundedCornerShape(14.dp)) {
                    Text(text = actionLabel, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(
    vm: PlayerViewModel,
    playback: com.streambridge.app.player.PlaybackUiState,
    request: com.streambridge.app.player.PlaybackRequest,
    sourceLabel: String,
    hasNext: Boolean,
    hasPrevious: Boolean,
    onBack: () -> Unit
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(0f) }

    // Auto-hide the controls a few seconds after the last interaction.
    LaunchedEffect(controlsVisible, playback.isPlaying) {
        if (controlsVisible) {
            delay(4000)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { controlsVisible = !controlsVisible }
    ) {
        if (playback.buffering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(52.dp))
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                onClick = vm::togglePlayPause,
                shape = CircleShape,
                color = Color(0x66000000),
                modifier = Modifier.size(76.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color(0xD9000000)
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = request.metaName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    request.episodeLabel?.let { label ->
                        Spacer(modifier = Modifier.width(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF0B0A10),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (sourceLabel.isNotBlank()) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "via $sourceLabel",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFACA5C2)
                        )
                    }
                }

                request.episodeTitle?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFACA5C2),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val positionText =
                        TimeFormat.msToClock(if (dragging) dragPosition.toLong() else playback.positionMs)
                    Text(
                        text = positionText,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                    Slider(
                        value = if (dragging) dragPosition else playback.positionMs.toFloat(),
                        onValueChange = { value ->
                            dragging = true
                            dragPosition = value
                        },
                        onValueChangeFinished = {
                            vm.seekTo(dragPosition.toLong())
                            dragging = false
                        },
                        valueRange = 0f..maxOf(playback.durationMs.toFloat(), 1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color(0x55FFFFFF)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                    )
                    Text(
                        text = TimeFormat.msToClock(playback.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PlayerIconButton(
                        icon = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous episode",
                        enabled = hasPrevious,
                        onClick = vm::playPreviousEpisode
                    )
                    Spacer(modifier = Modifier.width(26.dp))
                    PlayerIconButton(
                        icon = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play",
                        onClick = vm::togglePlayPause
                    )
                    Spacer(modifier = Modifier.width(26.dp))
                    PlayerIconButton(
                        icon = Icons.Filled.SkipNext,
                        contentDescription = "Next episode",
                        enabled = hasNext,
                        onClick = vm::playNextEpisode
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color(0x99000000),
                            1f to Color.Transparent
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = vm::openPicker) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = "Switch stream",
                        tint = Color.White
                    )
                }
            }
        }
    }

    // Back press while controls are visible hides them first.
    BackHandler(enabled = controlsVisible) {
        controlsVisible = false
    }
}

@Composable
private fun PlayerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (enabled) Color.White else Color(0x55FFFFFF)
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(34.dp)
        )
    }
}

@Composable
private fun StreamPickerSheet(
    streams: List<StreamOption>,
    onSelect: (StreamOption) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Text(
            text = "Switch stream",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
        if (streams.isEmpty()) {
            Text(
                text = "No streams resolved yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        } else {
            streams.forEach { stream ->
                val (badgeColor, badgeText) = when {
                    stream.isPlayable -> MaterialTheme.colorScheme.primary to "HTTP"
                    stream.isTorrent -> MaterialTheme.colorScheme.error to "TORRENT"
                    else -> MaterialTheme.colorScheme.tertiary to "WEB"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(stream) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                            maxLines = 1
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
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
