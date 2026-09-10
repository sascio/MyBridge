package com.streambridge.app.player

import androidx.media3.common.PlaybackException

/**
 * Maps Media3 PlaybackException error codes to short, human-readable,
 * actionable messages. Users should never see raw codec/HTTP jargon.
 *
 * Codes are referenced symbolically (verified against media3 1.5.1) so
 * the mapping cannot drift from Media3's constants.
 *
 * Pure logic — unit tested.
 */
object PlayerErrorMessages {

    /**
     * @param errorCode PlaybackException.getErrorCode()
     * @param detail optional detail line from the exception (already trimmed)
     */
    fun messageFor(errorCode: Int, detail: String? = null): String {
        val base = when (errorCode) {
            // ---- IO / network family (2xxx) ----
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "Could not connect to the stream server. The source may be down or blocked."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "The stream server took too long to respond."
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                "The server sent a content type this player cannot play."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "The stream server refused the request (HTTP error)."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "The stream is no longer available at this address."
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                "The stream server denied access to this content."
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                "This stream is served over plain http and blocked by Android's security policy."
            // ---- Parsing family (3xxx) ----
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                "The stream's data appears to be corrupted."
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                "The stream's manifest appears to be malformed."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                "The stream's container format is not supported by this player."
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                "The stream's manifest format is not supported by this player."
            // ---- Decoding family (4xxx) ----
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                "This device failed to initialize the video/audio decoder."
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                "This device could not query a decoder for this stream."
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "Decoding failed while playing this stream."
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                "This stream's quality is too high for this device to decode."
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "This stream uses a codec this device cannot decode."
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
                "The system reclaimed the video decoder for another app. Try playing again."
            // ---- Audio track (5xxx) ----
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED ->
                "The audio track could not be initialized."
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED ->
                "Audio playback failed for this stream."
            // ---- DRM family (6xxx) ----
            in 6000..6999 -> "This stream is DRM-protected. DRM streams are not supported."
            // ---- Video frame processing (7xxx) ----
            in 7000..7999 -> "This stream could not be processed for playback on this device."
            // ---- Unexpected runtime errors (1xxx) ----
            in 1000..1999 -> "The player hit an unexpected error with this stream."
            else -> "This stream could not be played."
        }
        return if (detail.isNullOrBlank()) {
            base
        } else {
            "$base\n(${detail.take(140)})"
        }
    }

    /** Message for errors thrown synchronously by player commands. */
    fun forException(e: Exception): String =
        "This stream could not be opened. (${(e.message ?: e.javaClass.simpleName).take(140)})"
}
