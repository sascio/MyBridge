package com.nuvio.app.features.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.media.service.*
import com.nuvio.app.features.player.LocalPlayerMiniPresentation
import com.nuvio.app.features.player.MiniPlayerController
import com.nuvio.app.features.player.PlayerScreen
import com.nuvio.app.features.player.closeBackgroundPlayerForNewPlayback
import com.nuvio.app.navigation.MediaEnvironment
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal class MediaPlaybackRequest(
    val ownerId: String, val profileId: Int, val entry: MediaEntry, val media: ResolvedMedia,
    val previous: (() -> Unit)? = null, val next: (() -> Unit)? = null,
)
internal val LocalPlayMedia = staticCompositionLocalOf<((MediaPlaybackRequest) -> Unit)?> { null }

/** Existing PlayerScreen/engine only. Browsing stays composed but unplaced while playing,
 * retaining local navigation/scroll state without a second application navigator or player. */
@Composable
internal fun MediaEnvironmentContent(environment: MediaEnvironment, onExit: () -> Unit) {
    var playback by remember(environment) { mutableStateOf<MediaPlaybackRequest?>(null) }
    val auth by com.nuvio.app.core.auth.AuthRepository.state.collectAsState()
    val profiles by com.nuvio.app.features.profiles.ProfileRepository.state.collectAsState()
    val owner = (auth as? com.nuvio.app.core.auth.AuthState.Authenticated)?.userId
    LaunchedEffect(owner, profiles.activeProfile) {
        playback?.let { if (it.ownerId != owner || it.profileId != profiles.activeProfile?.profileIndex) playback = null }
    }
    CompositionLocalProvider(LocalPlayMedia provides { request ->
        if (request.ownerId != owner || request.profileId != profiles.activeProfile?.profileIndex || MiniPlayerController.isActive)
            throw MediaServiceException(MediaFailure.Unavailable)
        closeBackgroundPlayerForNewPlayback()
        playback = request
    }) {
        Box(Modifier.fillMaxSize()) {
            // No placement means no drawing/hit testing/focus target. Semantics are cleared
            // while hidden. The owning platform modal still handles environment dismissal.
            Box(if (playback == null) Modifier.fillMaxSize() else Modifier.clearAndSetSemantics {}.layout { measurable, constraints ->
                measurable.measure(constraints)
                layout(0, 0) {}
            }) { MediaEnvironmentBrowser(environment, onExit) }
            playback?.let { request ->
                Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        TextButton(onClick = onExit) { Text(stringResource(Res.string.media_exit)) }
                        TextButton(onClick = { playback = null }) { Text(stringResource(Res.string.media_return_library)) }
                    }
                    Row {
                        request.previous?.let { action -> TextButton(onClick = action) { Text(stringResource(Res.string.media_queue_previous)) } }
                        request.next?.let { action -> TextButton(onClick = action) { Text(stringResource(Res.string.media_queue_next)) } }
                    }
                    key(request.entry.key, request.media.url) {
                        CompositionLocalProvider(LocalPlayerMiniPresentation provides null) {
                            PlayerScreen(
                                profileId = request.profileId, title = request.entry.title,
                                sourceUrl = request.media.url, sourceAudioUrl = request.media.audioUrl,
                                sourceHeaders = request.media.headers, providerName = "StreamBridge Music",
                                streamTitle = request.entry.title, streamSubtitle = request.entry.subtitle,
                                onBack = { playback = null }, modifier = Modifier.weight(1f).fillMaxWidth(),
                                parentMetaId = "media:${request.entry.key.service}:${request.entry.key.id}",
                                parentMetaType = "dedicated-media", contentType = "music", providerAddonId = "media:library",
                            )
                        }
                    }
                }
            }
        }
    }
}
