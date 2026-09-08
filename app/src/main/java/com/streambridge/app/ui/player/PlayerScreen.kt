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
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.streambridge.app.player.TrackOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(container, appContext))

    val phase by vm.phase.collectAsStateWithLifecycle()
    val request by vm.currentRequest.collectAsStateWithLifecycle()
    val externalSubtitles by vm.externalSubtitles.collectAsStateWithLifecycle()
    val selectedExternalSubtitle by vm.selectedExternalSubtitle.collectAsStateWithLifecycle()
    val playbackSpeed by vm.playbackSpeed.collectAsStateWithLifecycle()
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

    // Ended -> persist + autoplay next. Observed as a cold flow so the
    // 500 ms progress ticker never recomposes this whole screen.
    LaunchedEffect(Unit) {
        vm.playback
            .map { it.ended }
            .distinctUntilChanged()
            .filter { it }
            .collect { vm.onPlaybackEnded() }
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
                    // Subtitle text scale from settings.
                    val scale = vm.subtitleScale
                    playerView.subtitleView?.setFractionalTextSize(
                        androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale
                    )
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
                request = request,
                sourceLabel = sourceLabel,
                hasNext = vm.hasNextEpisode,
                hasPrevious = vm.hasPreviousEpisode,
                externalSubtitles = externalSubtitles,
                selectedExternalSubtitle = selectedExternalSubtitle,
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlayerControls(
    vm: PlayerViewModel,
    request: com.streambridge.app.player.PlaybackRequest,
    sourceLabel: String,
    hasNext: Boolean,
    hasPrevious: Boolean,
    externalSubtitles: List<com.streambridge.app.addon.ResolvedSubtitle>,
    selectedExternalSubtitle: String?,
    onBack: () -> Unit
) {
    // Collected here (not at screen level) so the 500 ms ticker only
    // recomposes these controls, never the whole player screen.
    val playback by vm.playback.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(0f) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var showSubtitles by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var nextCardDismissed by remember { mutableStateOf(false) }

    // Auto-hide the gesture hint shortly after the last update.
    LaunchedEffect(gestureHint) {
        if (gestureHint != null) {
            delay(700)
            gestureHint = null
        }
    }

    val nextEpisode = queue?.let { (episodes, index) -> episodes.getOrNull(index + 1) }
    val remainingMs = playback.durationMs - playback.positionMs
    if (remainingMs > 90_000) nextCardDismissed = false
    val showNextCard = hasNext && !nextCardDismissed &&
        playback.durationMs > 0 && remainingMs in 0..60_000

    // Auto-hide the controls a few seconds after the last interaction.
    LaunchedEffect(controlsVisible, playback.isPlaying) {
        if (controlsVisible) {
            delay(4000)
            controlsVisible = false
        }
    }

    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        val forward = offset.x >= size.width / 2f
                        vm.seekBy(if (forward) 10_000L else -10_000L)
                        gestureHint = if (forward) "10s forward" else "10s back"
                    }
                )
            }
            .pointerInput(Unit) {
                val audioManager =
                    context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                val maxVolume = audioManager?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0

                fun activityWindow(): android.view.Window? {
                    var ctx = context
                    while (ctx is android.content.ContextWrapper) {
                        if (ctx is android.app.Activity) return ctx.window
                        ctx = ctx.baseContext
                    }
                    return null
                }

                var mode: PlayerGesture? = null
                var totalX = 0f
                var totalY = 0f
                var startX = 0f
                var seekFrom = 0L
                var startBrightness = 0.5f
                var startVolume = 0

                detectDragGestures(
                    onDragStart = { offset ->
                        mode = null
                        totalX = 0f
                        totalY = 0f
                        startX = offset.x
                        seekFrom = vm.holder.player.currentPosition.coerceAtLeast(0L)
                        startBrightness = activityWindow()?.attributes?.screenBrightness
                            ?.takeIf { it >= 0f } ?: 0.5f
                        startVolume = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        totalX += amount.x
                        totalY += amount.y
                        if (mode == null) {
                            val absX = kotlin.math.abs(totalX)
                            val absY = kotlin.math.abs(totalY)
                            if (absX < 14f && absY < 14f) return@detectDragGestures
                            mode = if (absX > absY) {
                                PlayerGesture.Seek
                            } else if (startX < size.width / 2f) {
                                PlayerGesture.Brightness
                            } else {
                                PlayerGesture.Volume
                            }
                        }
                        when (mode) {
                            PlayerGesture.Seek -> {
                                val seconds = with(density) { totalX.toDp().value }.toLong()
                                gestureHint = (if (seconds >= 0) "+" else "") + "${seconds}s" +
                                    " (" + com.streambridge.app.core.TimeFormat.msToClock(
                                        (seekFrom + seconds * 1000).coerceAtLeast(0L)
                                    ) + ")"
                            }
                            PlayerGesture.Brightness -> {
                                val window = activityWindow() ?: return@detectDragGestures
                                val delta = with(density) { (-totalY).toDp().value } / 320f
                                val value = (startBrightness + delta).coerceIn(0.02f, 1f)
                                val attrs = window.attributes
                                attrs.screenBrightness = value
                                window.attributes = attrs
                                gestureHint = "Brightness ${(value * 100).toInt()}%"
                            }
                            PlayerGesture.Volume -> {
                                val audio = audioManager ?: return@detectDragGestures
                                if (maxVolume <= 0) return@detectDragGestures
                                val steps = with(density) { (-totalY).toDp().value } / 24f
                                val target = (startVolume + steps.toInt()).coerceIn(0, maxVolume)
                                if (target != audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) {
                                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
                                }
                                gestureHint = "Volume ${target * 100 / maxVolume}%"
                            }
                            null -> Unit
                        }
                    },
                    onDragEnd = {
                        if (mode == PlayerGesture.Seek) {
                            val seconds = with(density) { totalX.toDp().value }.toLong()
                            if (seconds != 0L) {
                                vm.seekBy(seconds * 1000L)
                            }
                        }
                        mode = null
                        gestureHint = null
                    },
                    onDragCancel = {
                        mode = null
                        gestureHint = null
                    }
                )
            }
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

        gestureHint?.let { hint ->
            Surface(
                shape = com.streambridge.app.ui.theme.PillShape,
                color = Color(0xB3141414),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x40FFFFFF)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = hint,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }

        // Skip-intro capsule during the opening minutes of long content.
        val showSkipIntro = !dragging && playback.durationMs > 8 * 60_000L &&
            playback.positionMs in 25_000..210_000
        AnimatedVisibility(
            visible = showSkipIntro,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 108.dp)
        ) {
            Surface(
                shape = com.streambridge.app.ui.theme.PillShape,
                color = Color(0xD9141414),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x59FFFFFF)),
                onClick = { vm.seekBy(90_000L) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.FastForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Skip intro",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // Up-next card in the final minute of an episode.
        AnimatedVisibility(
            visible = showNextCard,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xF0141414),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x40FFFFFF))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Up next",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = nextEpisode?.title?.takeIf { it.isNotBlank() }
                                ?: "Next episode",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        shape = com.streambridge.app.ui.theme.PillShape,
                        color = MaterialTheme.colorScheme.primary,
                        onClick = { vm.playNextEpisode() }
                    ) {
                        Text(
                            text = "Play now",
                            color = Color(0xFF141414),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { nextCardDismissed = true }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            tint = Color(0xFF9C9C9C)
                        )
                    }
                }
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
                IconButton(onClick = { showSpeed = true }) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = "Playback speed",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { showSubtitles = true }) {
                    Icon(
                        imageVector = Icons.Filled.Subtitles,
                        contentDescription = "Subtitles",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { showAudio = true }) {
                    Icon(
                        imageVector = Icons.Filled.Audiotrack,
                        contentDescription = "Audio track",
                        tint = Color.White
                    )
                }
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

    // Subtitle / audio track selection sheets.
    if (showSubtitles) {
        ModalBottomSheet(onDismissRequest = { showSubtitles = false }) {
            SubtitleSheetContent(
                vm = vm,
                externalSubtitles = externalSubtitles,
                selectedExternalSubtitle = selectedExternalSubtitle,
                onDismiss = { showSubtitles = false }
            )
        }
    }
    if (showSpeed) {
        ModalBottomSheet(onDismissRequest = { showSpeed = false }) {
            SpeedSheetContent(
                current = vm.playbackSpeed.value,
                onSelect = { speed ->
                    vm.setPlaybackSpeed(speed)
                    showSpeed = false
                }
            )
        }
    }
    if (showAudio) {
        ModalBottomSheet(onDismissRequest = { showAudio = false }) {
            TrackSelectionSheet(
                title = "Audio",
                allowOff = false,
                options = vm.holder.audioTracks(),
                onSelect = { option ->
                    if (option != null) {
                        vm.holder.selectAudioTrack(option)
                    }
                    showAudio = false
                }
            )
        }
    }

    // Back press while controls are visible hides them first.
    BackHandler(enabled = controlsVisible) {
        controlsVisible = false
    }
}

@Composable
private fun SubtitleSheetContent(
    vm: PlayerViewModel,
    externalSubtitles: List<com.streambridge.app.addon.ResolvedSubtitle>,
    selectedExternalSubtitle: String?,
    onDismiss: () -> Unit
) {
    val embedded = vm.holder.textTracks()

    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Text(
            text = "Subtitles",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )

        if (embedded.isEmpty() && externalSubtitles.isEmpty()) {
            Text(
                text = "No subtitles available for this stream yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }

        if (embedded.isNotEmpty()) {
            Text(
                text = "Built into the stream",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        vm.clearExternalSubtitle()
                        vm.holder.selectTextTrack(null)
                    }
                    .padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Off", modifier = Modifier.weight(1f))
                if (embedded.none { it.selected } && selectedExternalSubtitle == null) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            embedded.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.clearExternalSubtitle()
                            vm.holder.selectTextTrack(option)
                        }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = option.label,
                        modifier = Modifier.weight(1f),
                        fontWeight = if (option.selected) FontWeight.Bold else FontWeight.Normal
                    )
                    if (option.selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (externalSubtitles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "From your extensions",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            externalSubtitles.forEach { resolved ->
                val isSelected = resolved.subtitle.url == selectedExternalSubtitle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.applyExternalSubtitle(resolved) }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = resolved.label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = "via ${resolved.addonName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedSheetContent(
    current: Float,
    onSelect: (Float) -> Unit
) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Text(
            text = "Playback speed",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
        speeds.forEach { speed ->
            val selected = kotlin.math.abs(speed - current) < 0.01f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(speed) }
                    .padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (speed == 1f) "Normal" else String.format("%.2fx", speed),
                    modifier = Modifier.weight(1f),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private enum class PlayerGesture { Seek, Brightness, Volume }

@Composable
private fun TrackSelectionSheet(
    title: String,
    allowOff: Boolean,
    options: List<TrackOption>,
    onSelect: (TrackOption?) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
        if (options.isEmpty() && !allowOff) {
            Text(
                text = "No tracks available yet. They appear once the stream provides them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
        if (allowOff) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(null) }
                    .padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Off", modifier = Modifier.weight(1f))
                if (options.none { it.selected }) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = option.label,
                    modifier = Modifier.weight(1f),
                    fontWeight = if (option.selected) FontWeight.Bold else FontWeight.Normal
                )
                if (option.selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
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
@Composable
private fun StreamPickerSheet(
    streams: List<StreamOption>,
    onSelect: (StreamOption) -> Unit
) {
    val grouped = remember(streams) {
        com.streambridge.app.addon.StreamEnrichment.groupByProvider(streams)
    }
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp)
    ) {
        Text(
            text = "Choose a source",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
        Text(
            text = "${streams.size} streams from ${grouped.size} providers, sorted by quality",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (streams.isEmpty()) {
            Text(
                text = "No streams resolved yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
        grouped.forEach { (provider, options) ->
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = provider,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "(${options.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            options.forEach { stream ->
                val (badgeColor, badgeText) = when {
                    stream.isPlayable -> MaterialTheme.colorScheme.primary to "DIRECT"
                    stream.isTorrent -> MaterialTheme.colorScheme.error to "TORRENT"
                    else -> MaterialTheme.colorScheme.tertiary to "WEB"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(stream) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = badgeColor.copy(alpha = 0.18f),
                        contentColor = badgeColor,
                        shape = com.streambridge.app.ui.theme.PillShape
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stream.qualityChip.ifBlank { stream.shortLabel },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = listOfNotNull(
                                stream.language.takeIf { it.isNotBlank() }?.uppercase(),
                                stream.sizeLabel.takeIf { it.isNotBlank() },
                                if (stream.seeders > 0) "${stream.seeders} seeds" else null,
                                stream.description?.take(60)
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
