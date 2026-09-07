package com.streambridge.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.net.Uri

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

    /** Starts (or restarts) playback of a direct stream URL. */
    fun play(url: String, startPositionMs: Long) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.prepare()
        player.play()
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

    fun release() {
        try {
            player.release()
        } catch (_: Exception) {
        }
    }
}
