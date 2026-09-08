package com.streambridge.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streambridge.app.addon.SbHttpClient
import com.streambridge.app.addon.StreamHeaders
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A selectable subtitle or audio track exposed by the current stream. */
data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean
)

/**
 * Wrapper around Media3 ExoPlayer.
 *
 * Playback hardening:
 *  - Every URL is validated ([StreamValidator]) before the player sees it;
 *    bad links become error events, never crashes.
 *  - The HTTP stack is the app's shared OkHttpClient (connection reuse,
 *    app user-agent, OkHttp redirect handling incl. cross-protocol).
 *  - Addon-supplied headers are applied per-stream via a ResolvingDataSource,
 *    sanitized through [StreamHeaders].
 *  - setMediaItem()/prepare() build media sources synchronously on the main
 *    thread; Media3 can throw there (e.g. a scheme no module supports).
 *    Those exceptions are converted to error events at this boundary —
 *    the actual cause (missing modules) is fixed in the build, this is the
 *    last line of defense for exotic inputs.
 *  - PlaybackException codes are mapped to readable messages.
 */
@OptIn(UnstableApi::class)
class PlayerHolder(
    context: Context,
    okHttpClient: OkHttpClient,
    private val onPlaybackEvent: (PlaybackEvent) -> Unit
) {

    sealed interface PlaybackEvent {
        data class StateChanged(
            val isPlaying: Boolean,
            val buffering: Boolean,
            val ended: Boolean,
            val positionMs: Long,
            val durationMs: Long
        ) : PlaybackEvent

        data class Error(val message: String) : PlaybackEvent
    }

    /** Headers to attach to the request for the active stream URL only. */
    private val headersByUrl = ConcurrentHashMap<String, Map<String, String>>()

    /** Sanitized headers of the stream currently loaded (for restarts). */
    private var activeHeaders: Map<String, String> = emptyMap()

    // A stream can legitimately be silent for long stretches (slow CDN,
    // paused buffering of live edges); 30 s without a byte is a generous
    // inactivity ceiling. Shares the app's pool/dispatcher via newBuilder().
    private val streamHttpClient = okHttpClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val player: ExoPlayer = buildPlayer(context.applicationContext)

    private fun buildPlayer(appContext: Context): ExoPlayer {
        val httpFactory = OkHttpDataSource.Factory(streamHttpClient)
            .setUserAgent(SbHttpClient.USER_AGENT)

        val resolvingFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val headers = headersByUrl[dataSpec.uri.toString()]
            if (headers.isNullOrEmpty()) {
                dataSpec
            } else {
                dataSpec.buildUpon()
                    .setHttpRequestHeaders(headers + dataSpec.httpRequestHeaders)
                    .build()
            }
        }
        val dataSourceFactory = DefaultDataSource.Factory(appContext, resolvingFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        return ExoPlayer.Builder(appContext, mediaSourceFactory)
            .build()
            .apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        publish()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        publish()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "Playback error ${error.errorCodeName}: ${error.message}")
                        onPlaybackEvent(
                            PlaybackEvent.Error(
                                PlayerErrorMessages.messageFor(
                                    errorCode = error.errorCode,
                                    detail = httpDetailFor(error)
                                )
                            )
                        )
                    }
                })
            }
    }

    private fun publish() {
        val state = player.playbackState
        onPlaybackEvent(
            PlaybackEvent.StateChanged(
                isPlaying = player.isPlaying,
                buffering = state == Player.STATE_BUFFERING,
                ended = state == Player.STATE_ENDED,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = if (player.duration < 0) 0L else player.duration
            )
        )
    }

    /**
     * Starts (or restarts) playback of a direct stream URL, with optional
     * side-loaded subtitles and addon-supplied HTTP headers.
     */
    fun play(
        url: String,
        startPositionMs: Long,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList(),
        speed: Float = 1f,
        headers: Map<String, String> = emptyMap()
    ) {
        when (val verdict = StreamValidator.validate(url)) {
            is StreamValidator.Result.Invalid -> {
                Log.w(TAG, "Rejected stream URL: ${verdict.reason}")
                onPlaybackEvent(PlaybackEvent.Error(verdict.reason))
                return
            }

            is StreamValidator.Result.Valid -> {
                val safeHeaders = StreamHeaders.sanitize(headers)
                Log.d(
                    TAG,
                    "Playing ${verdict.contentType} stream from ${verdict.url.toUriHost()}" +
                        " (${safeHeaders.size} headers)"
                )
                headersByUrl.clear()
                activeHeaders = safeHeaders
                if (safeHeaders.isNotEmpty()) {
                    headersByUrl[verdict.url] = safeHeaders
                }
                try {
                    val mediaItem = MediaItem.Builder()
                        .setUri(Uri.parse(verdict.url))
                        .setSubtitleConfigurations(subtitleConfigurations)
                        .build()
                    player.setMediaItem(mediaItem)
                    if (startPositionMs > 0L) {
                        player.seekTo(startPositionMs)
                    }
                    player.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
                    player.prepare()
                    player.play()
                    publish()
                } catch (e: Exception) {
                    // Media3 builds media sources synchronously inside
                    // setMediaItem; inputs it cannot classify throw here
                    // (previously an app crash). Convert to the standard
                    // error path so the user can pick another stream.
                    Log.e(TAG, "Player rejected the media item", e)
                    onPlaybackEvent(PlaybackEvent.Error(PlayerErrorMessages.forException(e)))
                }
            }
        }
    }

    /** Restarts the current media with side-loaded external subtitles. */
    fun applyExternalSubtitles(
        url: String,
        positionMs: Long,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration>,
        speed: Float
    ) {
        play(url, positionMs, subtitleConfigurations, speed, activeHeaders)
    }

    fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
        publish()
    }

    fun retry() {
        val position = player.currentPosition.coerceAtLeast(0L)
        try {
            player.prepare()
            player.seekTo(position)
            player.play()
            publish()
        } catch (e: Exception) {
            Log.e(TAG, "Retry failed", e)
            onPlaybackEvent(PlaybackEvent.Error(PlayerErrorMessages.forException(e)))
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
        publish()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(
            positionMs.coerceIn(0L, if (player.duration > 0) player.duration else Long.MAX_VALUE)
        )
        publish()
    }

    fun snapshotPosition(): Pair<Long, Long> {
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = if (player.duration > 0) player.duration else 0L
        return position to duration
    }

    // -----------------------------------------------------------------
    // Track selection (subtitles / audio)
    // -----------------------------------------------------------------

    fun textTracks(): List<TrackOption> = collectTracks(C.TRACK_TYPE_TEXT, "Subtitle")

    fun audioTracks(): List<TrackOption> = collectTracks(C.TRACK_TYPE_AUDIO, "Audio")

    private fun collectTracks(
        @androidx.annotation.IntRange(from = 0) type: Int,
        fallbackPrefix: String
    ): List<TrackOption> {
        val result = mutableListOf<TrackOption>()
        val groups = player.currentTracks.groups
        for (groupIndex in groups.indices) {
            val group = groups[groupIndex]
            if (group.getType() != type) continue
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val label = format.label?.takeIf { it.isNotBlank() }
                    ?: format.language?.uppercase()
                    ?: "$fallbackPrefix ${result.size + 1}"
                result += TrackOption(groupIndex, trackIndex, label, group.isTrackSelected(trackIndex))
            }
        }
        return result
    }

    /** Selects a text track, or disables text tracks entirely when null. */
    fun selectTextTrack(option: TrackOption?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex)
            )
        }
        player.trackSelectionParameters = builder.build()
    }

    fun selectAudioTrack(option: TrackOption) {
        val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    fun release() {
        headersByUrl.clear()
        try {
            player.release()
        } catch (e: Exception) {
            Log.w(TAG, "Player release failed", e)
        }
    }

    // -----------------------------------------------------------------
    // Error detail extraction
    // -----------------------------------------------------------------

    /** Pulls the HTTP status code out of bad-status errors, when present. */
    private fun httpDetailFor(error: PlaybackException): String? {
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth < 4) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return "HTTP ${cause.responseCode}"
            }
            cause = cause.cause
            depth++
        }
        return error.message?.take(80)
    }

    private companion object {
        const val TAG = "SBPlayer"
    }
}

/** Host name only — never logged with paths or query strings (token safety). */
private fun String.toUriHost(): String =
    runCatching { Uri.parse(this).host ?: "unknown-host" }.getOrDefault("unknown-host")
