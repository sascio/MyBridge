@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.streambridge.app.player

import androidx.media3.common.PlaybackException

/**
 * Nuvio ExoPlayer recovery decisions, extracted as pure logic.
 *
 * From NuvioStreaming `PlayerEngine.android.kt`:
 *  1. On UnrecognizedInputFormat / IO_UNSPECIFIED / BEHIND_LIVE_WINDOW,
 *     probe the real Content-Type and retry the SAME MediaItem with
 *     that MIME — do **not** jump to libmpv yet.
 *  2. On decoder failure while extension renderers are ON, rebuild
 *     once with EXTENSION_RENDERER_MODE_PREFER (only when ffmpeg/av1
 *     extensions are actually on the classpath).
 *  3. Only after those in-ExoPlayer recoveries fail does Auto mode
 *     switch the surface to libmpv.
 */
object Media3RecoveryPolicy {

    fun shouldProbeMimeAndRetry(
        errorCode: Int,
        unrecognizedContainer: Boolean,
        alreadyProbed: Boolean
    ): Boolean {
        if (alreadyProbed) return false
        if (unrecognizedContainer) return true
        return errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
            errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
            errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
    }

    fun shouldUseProbedMime(currentMime: String?, probedMime: String?): Boolean {
        val probed = probedMime?.takeIf { it.isNotBlank() } ?: return false
        val current = currentMime?.takeIf { it.isNotBlank() } ?: return true
        return !current.equals(probed, ignoreCase = true)
    }

    fun isDecoderFailure(errorCode: Int): Boolean = errorCode in DECODER_FAILURE_CODES

    /**
     * Nuvio retries decoder failure once at EXTENSION_RENDERER_MODE_PREFER
     * when the current mode is ON. Without bundled extension AARs the
     * retry is a no-op, so we only recommend it when extensions exist
     * and it has not already been tried.
     */
    fun shouldRetryWithExtensionPrefer(
        decoderFailure: Boolean,
        extensionsPresent: Boolean,
        alreadyRetriedPrefer: Boolean
    ): Boolean = decoderFailure && extensionsPresent && !alreadyRetriedPrefer

    private val DECODER_FAILURE_CODES = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
    )
}

/**
 * libmpv `end-file` reason codes (mpv client.h). Treating an unparsed
 * or pre-playback end-file as a successful EOF is what produced the
 * black screen + Play button: loading stopped, nothing was playing.
 */
object MpvEndFilePolicy {
    const val REASON_EOF = 0L
    const val REASON_STOP = 1L
    const val REASON_QUIT = 2L
    const val REASON_REDIRECT = 3L
    const val REASON_ERROR = 4L

    enum class Decision {
        /** File replace / stop — keep buffering or playing as-is. */
        IGNORE,

        /** Genuine end of a file that actually played. */
        ENDED,

        /** Load/decode failed. Must surface as an error, never idle. */
        FAILED
    }

    fun decide(
        reason: Long?,
        firstFrame: Boolean,
        fileLoaded: Boolean
    ): Decision = when (reason) {
        REASON_ERROR -> Decision.FAILED
        REASON_STOP, REASON_QUIT, REASON_REDIRECT -> Decision.IGNORE
        REASON_EOF -> if (firstFrame || fileLoaded) Decision.ENDED else Decision.FAILED
        null -> if (firstFrame) Decision.ENDED else Decision.FAILED
        else -> if (firstFrame) Decision.IGNORE else Decision.FAILED
    }
}

/**
 * Backend-neutral runtime phase the UI must distinguish. A failed
 * backend must never look like READY/PAUSED/IDLE.
 */
enum class PlaybackRuntimePhase {
    IDLE,
    PREPARING,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
    SWITCHING_BACKEND
}

object PlaybackRuntime {
    fun phase(
        isPlaying: Boolean,
        buffering: Boolean,
        ended: Boolean,
        switchingBackend: Boolean,
        error: Boolean
    ): PlaybackRuntimePhase = when {
        error -> PlaybackRuntimePhase.ERROR
        switchingBackend -> PlaybackRuntimePhase.SWITCHING_BACKEND
        ended -> PlaybackRuntimePhase.ENDED
        buffering && isPlaying -> PlaybackRuntimePhase.BUFFERING
        buffering -> PlaybackRuntimePhase.PREPARING
        isPlaying -> PlaybackRuntimePhase.PLAYING
        else -> PlaybackRuntimePhase.PAUSED
    }

    fun showLoadingSpinner(phase: PlaybackRuntimePhase): Boolean =
        phase == PlaybackRuntimePhase.PREPARING ||
            phase == PlaybackRuntimePhase.BUFFERING ||
            phase == PlaybackRuntimePhase.SWITCHING_BACKEND
}
