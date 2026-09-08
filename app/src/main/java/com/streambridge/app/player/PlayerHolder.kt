package com.streambridge.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import android.net.Uri

/** A selectable subtitle or audio track exposed by the current stream. */
data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean
)

/**
 * Thin wrapper around Media3 ExoPlayer with a listener that reports
 * state changes and errors through callbacks. Real playback: no
 * placeholders, no fake progress.
 */
class PlayerHolder(
    context: Context,
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

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
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
                    onPlaybackEvent(
                        PlaybackEvent.Error(
                            error.errorCodeName.take(64) + ": " +
                                (error.message ?: "playback failed").take(120)
                        )
                    )
                }
            })
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

    /** Starts (or restarts) playback of a direct stream URL, with optional side-loaded subtitles. */
    fun play(
        url: String,
        startPositionMs: Long,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList(),
        speed: Float = 1f
    ) {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
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
    }

    /** Restarts the current media with side-loaded external subtitles. */
    fun applyExternalSubtitles(
        url: String,
        positionMs: Long,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration>,
        speed: Float
    ) {
        play(url, positionMs, subtitleConfigurations, speed)
    }

    fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
        publish()
    }

    fun retry() {
        val position = player.currentPosition.coerceAtLeast(0L)
        player.prepare()
        player.seekTo(position)
        player.play()
        publish()
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
        player.seekTo(positionMs.coerceIn(0L, if (player.duration > 0) player.duration else Long.MAX_VALUE))
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

    private fun collectTracks(@androidx.annotation.IntRange(from = 0) type: Int, fallbackPrefix: String): List<TrackOption> {
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
        try {
            player.release()
        } catch (_: Exception) {
        }
    }
}
