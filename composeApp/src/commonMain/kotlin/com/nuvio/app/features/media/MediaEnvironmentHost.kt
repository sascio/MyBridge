package com.nuvio.app.features.media

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlin.math.abs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.nuvio.app.navigation.MediaEnvironment

internal val LocalOpenMedia = staticCompositionLocalOf<((MediaEnvironment) -> Unit)?> { null }

/** Platform hosts must retain the origin and prevent outside/interactive dismissal. */
@Composable
internal expect fun MediaEnvironmentHost(environment: MediaEnvironment, onExit: () -> Unit)

@Composable
internal fun MediaEnvironmentBrowser(environment: MediaEnvironment, onExit: () -> Unit) {
    val music = environment == MediaEnvironment.Music
    val accent = if (music) Color(0xFF87E5BE) else Color(0xFFFF6A73)
    val background = Color(0xFF0C0F13)
    var destination by rememberSaveable(environment.name) { mutableStateOf("Home") }
    // Stable saveable identifiers; visible labels come from resources.
    val destinations = if (music) listOf("Home", "Discover", "Library") else listOf("Home", "Explore", "Library")
    val labels = listOf(stringResource(Res.string.media_home), stringResource(if (music) Res.string.media_discover else Res.string.media_explore), stringResource(Res.string.media_library))
    val icons = listOf(Icons.Filled.Home, Icons.Filled.Explore, Icons.Filled.LibraryMusic)
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = background, surface = Color(0xFF191E25))) {
        Column(Modifier.fillMaxSize().background(background).safeDrawingPadding()) {
            // Outside the scroll container: always visible, including on small screens.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onExit, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.media_exit))
                }
                Spacer(Modifier.width(12.dp))
                Text("StreamBridge", style = MaterialTheme.typography.titleMedium)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(if (music) 28.dp else 12.dp),
                    color = Color.Transparent,
                ) {
                    Column(
                        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(accent.copy(alpha = .20f), background))).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(if (music) Icons.Filled.MusicNote else Icons.Filled.PlayArrow, contentDescription = null, tint = accent, modifier = Modifier.size(48.dp))
                        Text(stringResource(if (music) Res.string.media_music_headline else Res.string.media_video_headline), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                        Text(stringResource(if (music) Res.string.media_music_identity else Res.string.media_video_identity), color = accent)
                        Text(stringResource(Res.string.media_motto), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(labels[destinations.indexOf(destination)], style = MaterialTheme.typography.headlineSmall)
                Surface(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MediaServiceBrowser(
                            music = music,
                            destination = destination,
                        )
                    }
                }
                Text(stringResource(Res.string.media_footer), style = MaterialTheme.typography.labelMedium, color = Color(0xFFBEC5CE))
            }
            NavigationBar(containerColor = background) {
                destinations.forEachIndexed { index, page ->
                    NavigationBarItem(
                        selected = destination == page,
                        onClick = { destination = page },
                        icon = { Icon(icons[index], contentDescription = null) },
                        label = { Text(labels[index]) },
                    )
                }
            }
        }
    }
}

/** Only this visible inset handle observes drags; never attach to content or player surfaces. */
@Composable
internal fun MediaEntryBoundary(onOpen: (MediaEnvironment) -> Unit, modifier: Modifier = Modifier) {
    val currentOpen by rememberUpdatedState(onOpen)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }
    Surface(modifier = modifier.padding(horizontal = 16.dp).widthIn(max = 480.dp), shape = RoundedCornerShape(16.dp), color = Color(0xFF202731)) {
        Row(
            Modifier.pointerInput(threshold) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dy = 0f
                    var horizontal = false
                    while (true) {
                        val event = awaitPointerEvent()
                        // Never hand off a two-finger gesture to a remaining pointer.
                        if (event.changes.size != 1) break
                        val change = event.changes.single()
                        if (change.id != down.id || change.isConsumed) break
                        dx += change.position.x - change.previousPosition.x
                        dy += change.position.y - change.previousPosition.y
                        if (!change.pressed) {
                            if (horizontal) mediaSwipeDestination(dx, dy, threshold)?.let(currentOpen)
                            break
                        }
                        if (!horizontal) {
                            if (abs(dy) > viewConfiguration.touchSlop && abs(dy) >= abs(dx)) break
                            horizontal = abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy) * 1.5f
                        }
                        if (horizontal) change.consume()
                    }
                }
            }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { currentOpen(MediaEnvironment.YouTube) }) { Text(stringResource(Res.string.media_video)) }
            Text(stringResource(Res.string.media_swipe_hint), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { currentOpen(MediaEnvironment.Music) }) { Text(stringResource(Res.string.media_music)) }
        }
    }
}
