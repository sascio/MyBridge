package com.streambridge.app.player

/**
 * Maps Media3 PlaybackException error codes to short, human-readable,
 * actionable messages. Users should never see raw codec/HTTP jargon.
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
            2001 -> "Could not connect to the stream server. The source may be down or blocked."
            2002 -> "The stream server took too long to respond."
            2003 -> "The server sent a content type this player cannot play."
            2004 -> "The stream server refused the request (HTTP error)."
            2005 -> "The stream is no longer available at this address."
            2006 -> "The stream server denied access to this content."
            2007 -> "This stream is served over plain http and blocked by Android's security policy."
            // ---- Parsing family (3xxx) ----
            3001 -> "The stream's container format is not supported by this player."
            3002 -> "The stream's manifest format is not supported by this player."
            3003 -> "The stream's data appears to be corrupted."
            3004 -> "The stream's manifest appears to be malformed."
            // ---- Decoding family (4xxx) ----
            4001 -> "This device failed to initialize the video/audio decoder."
            4002 -> "This device could not query a decoder for this stream."
            4003 -> "Decoding failed while playing this stream."
            4004 -> "This stream's quality is too high for this device to decode."
            4005 -> "This stream uses a codec this device cannot decode."
            // ---- Audio track (5xxx) ----
            5001 -> "The audio track could not be initialized."
            5002 -> "Audio playback failed for this stream."
            // ---- DRM family (6xxx) ----
            in 6000..6999 -> "This stream is DRM-protected. DRM streams are not supported."
            // ---- Video frame processing (7xxx) ----
            in 7000..7999 -> "This stream could not be processed for playback on this device."
            // ---- Unexpected runtime errors (1xxx) ----
            in 1000..1999 -> "The player hit an unexpected error with this stream."
            else -> "This stream could not be played."
        }
        return if (detail.isNullOrBlank()) base else "$base\n($detail.take(140))"
    }

    /** Message for errors thrown synchronously by player commands. */
    fun forException(e: Exception): String =
        "This stream could not be opened. (${(e.message ?: e.javaClass.simpleName).take(140)})"
}
