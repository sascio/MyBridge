package com.streambridge.app.player

/**
 * Structured, REDACTED playback diagnostics. The user-facing UI stays
 * clean; these facts preserve the REAL cause of a failure for logs and
 * support: source, backend, container, codecs, tracks, HTTP status,
 * error cause and every fallback attempt.
 *
 * Redaction rules (absolute — see the tests):
 *  - URLs: host name only. Paths and queries (signed tokens!) never
 *    appear anywhere in diagnostics or logs.
 *  - Request headers: NAMES only. Values (cookies, authorization,
 *    tokens) are never stored.
 *  - Causes: exception CLASS names only; exception messages can embed
 *    URLs and are dropped.
 */
data class PlaybackDiagnostics(
    val backendId: String,
    val sourceName: String? = null,
    val containerMime: String? = null,
    val streamHost: String? = null,
    val headerNames: List<String> = emptyList(),
    val videoMimeType: String? = null,
    val videoCodec: String? = null,
    val selectedVideo: String? = null,
    val audioMimeType: String? = null,
    val audioCodec: String? = null,
    val selectedAudio: String? = null,
    val availableAudioTracks: List<String> = emptyList(),
    val httpStatus: Int? = null,
    val errorCategory: PlaybackFailureCategory? = null,
    val errorCause: String? = null,
    val fallbackAttempts: List<String> = emptyList(),
    val notes: List<String> = emptyList()
) {

    /** One redacted line for the log — safe to print as-is. */
    fun toLogString(): String = buildString {
        append("PlaybackDiagnostics(backend=").append(backendId)
        sourceName?.let { append(", source=").append(it) }
        containerMime?.let { append(", container=").append(it) }
        streamHost?.let { append(", host=").append(it) }
        if (headerNames.isNotEmpty()) append(", headers=").append(headerNames.joinToString("/"))
        videoMimeType?.let { append(", video=").append(it) }
        videoCodec?.let { append(", vcodec=").append(it) }
        selectedVideo?.let { append(", selectedVideo=").append(it) }
        audioMimeType?.let { append(", audio=").append(it) }
        audioCodec?.let { append(", acodec=").append(it) }
        selectedAudio?.let { append(", selectedAudio=").append(it) }
        if (availableAudioTracks.isNotEmpty()) {
            append(", audioTracks=[").append(availableAudioTracks.joinToString("; ")).append("]")
        }
        httpStatus?.let { append(", http=").append(it) }
        errorCategory?.let { append(", category=").append(it) }
        errorCause?.let { append(", cause=").append(it) }
        if (fallbackAttempts.isNotEmpty()) {
            append(", fallbacks=").append(fallbackAttempts.joinToString(" -> "))
        }
        if (notes.isNotEmpty()) append(", notes=").append(notes.joinToString("; "))
        append(")")
    }

    companion object {
        /**
         * Host name of a playback URL, or null. Deliberately JVM-pure
         * (no android.net.Uri) so redaction is unit-testable, and
         * deliberately drops everything except the host.
         */
        fun hostOf(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return runCatching {
                java.net.URI(url).host?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }
}
