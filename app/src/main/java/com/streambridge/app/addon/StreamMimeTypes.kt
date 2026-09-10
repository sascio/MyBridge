package com.streambridge.app.addon

/**
 * Reliable stream container/manifest detection (shared by the provider
 * layer and the player).
 *
 * WHY THIS EXISTS: Media3's DefaultMediaSourceFactory classifies a URL by
 * its PATH EXTENSION only. Real provider URLs frequently carry their
 * manifest type elsewhere — in a query parameter
 * (".../manifest?output=m3u8"), as a path token ("/hls/token123"), or
 * with no marker at all (tokenized CDN links). An extension-less HLS
 * or DASH URL was therefore built as a progressive source, whose
 * extractors cannot recognize a text manifest — surfacing to the user
 * as "The stream's container format is not supported by this player"
 * even though the exact same URL plays fine in Nuvio.
 *
 * This cascade mirrors that reference behavior:
 *   1. a provider-supplied type hint (the provider knows its own links)
 *   2. the URL itself: extension, then query parameters, then
 *      delimited tokens anywhere in the URL
 *   3. (caller-side) the server's actual Content-Type / file name
 *
 * The result is attached to the MediaItem as its MIME type, which
 * DefaultMediaSourceFactory honors over extension sniffing. Unknown
 * stays unknown (null) — progressive playback then sniffs the real
 * bytes, and NO MIME is ever invented.
 *
 * The literal MIME strings are identical to media3's MimeTypes
 * constants. Pure JVM logic — unit tested.
 */
object StreamMimeTypes {

    const val APPLICATION_M3U8 = "application/x-mpegurl"
    const val APPLICATION_MPD = "application/dash+xml"
    const val APPLICATION_SS = "application/vnd.ms-sstr+xml"
    const val VIDEO_MP4 = "video/mp4"
    const val VIDEO_WEBM = "video/webm"
    const val VIDEO_MATROSKA = "video/x-matroska"
    const val VIDEO_MP2T = "video/mp2t"
    const val VIDEO_AVI = "video/avi"
    const val VIDEO_MPEG = "video/mpeg"
    const val VIDEO_QUICKTIME = "video/quicktime"

    /** Manifest MIME types — the ones that change the MediaSource type. */
    val MANIFEST_MIMES = setOf(APPLICATION_M3U8, APPLICATION_MPD, APPLICATION_SS)

    /**
     * Provider-supplied type/format hint (e.g. Nuvio stream "format").
     * Only clean, known values map to a MIME; anything else maps to
     * null (never guessed).
     */
    fun fromProviderHint(hint: String?): String? {
        val normalized = hint?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return when (normalized) {
            "hls", "m3u8" -> APPLICATION_M3U8
            "dash", "mpd" -> APPLICATION_MPD
            "smoothstreaming", "ss" -> APPLICATION_SS
            "mp4", "m4v" -> VIDEO_MP4
            "mkv" -> VIDEO_MATROSKA
            "webm" -> VIDEO_WEBM
            "ts", "mts", "m2ts" -> VIDEO_MP2T
            "mov" -> VIDEO_QUICKTIME
            "avi" -> VIDEO_AVI
            "mpeg", "mpg" -> VIDEO_MPEG
            else -> null
        }
    }

    /**
     * The server's actual Content-Type header, normalized. HTML and
     * other non-media types return null — they are NOT forced onto the
     * MediaItem (no fake MIME).
     */
    fun fromContentType(contentType: String?): String? {
        val normalized = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when (normalized) {
            "application/vnd.apple.mpegurl",
            "application/mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl",
            "application/m3u8" -> APPLICATION_M3U8

            "application/dash+xml",
            "video/vnd.mpeg.dash.mpd" -> APPLICATION_MPD

            "application/vnd.ms-sstr+xml" -> APPLICATION_SS

            "video/mp4", "application/mp4", "video/x-m4v" -> VIDEO_MP4
            "video/webm", "audio/webm" -> VIDEO_WEBM
            "video/x-matroska", "audio/x-matroska",
            "video/mkv", "audio/mkv" -> VIDEO_MATROSKA
            "video/mp2t", "video/mpegts", "video/ts" -> VIDEO_MP2T
            "video/quicktime" -> VIDEO_QUICKTIME
            "video/avi", "video/x-msvideo" -> VIDEO_AVI
            "video/mpeg" -> VIDEO_MPEG
            else -> null
        }
    }

    /**
     * A Content-Disposition header value ("attachment; filename=movie.mkv")
     * to a file-name MIME, when the file name carries a media extension.
     */
    fun fromContentDisposition(value: String?): String? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        // RFC 5987 ext-value first, then the plain filename parameter.
        val filename = raw
            .substringAfter("filename*=", missingDelimiterValue = "")
            .substringAfterLast("''", missingDelimiterValue = "")
            .ifBlank { raw.substringAfter("filename=", missingDelimiterValue = "") }
            .trim()
            .trim('"', '\'')
            .takeIf { it.isNotBlank() }
            ?: return null
        return fromFileExtension(filename.substringAfterLast('.', missingDelimiterValue = ""))
    }

    /** Full URL inference: extension → query parameter → delimited token. */
    fun fromUrl(url: String): String? {
        val lower = url.trim().lowercase().substringBefore('#')
        if (lower.isEmpty()) return null
        val path = lower.substringBefore('?')
        val query = lower.substringAfter('?', missingDelimiterValue = "")

        val fileName = path.substringAfterLast('/')
        fromFileExtension(fileName.substringAfterLast('.', missingDelimiterValue = ""))
            ?.let { return it }

        fromQueryParameters(query)?.let { return it }
        fromDelimitedToken(path)?.let { return it }
        return fromDelimitedToken(query)
    }

    private fun fromFileExtension(extension: String): String? = when (extension) {
        "m3u8" -> APPLICATION_M3U8
        "mpd" -> APPLICATION_MPD
        "ism", "isml" -> APPLICATION_SS
        "mkv" -> VIDEO_MATROSKA
        "webm" -> VIDEO_WEBM
        "mp4", "m4v" -> VIDEO_MP4
        "ts", "mts", "m2ts" -> VIDEO_MP2T
        "mov" -> VIDEO_QUICKTIME
        "avi" -> VIDEO_AVI
        "mpeg", "mpg" -> VIDEO_MPEG
        else -> null
    }

    /**
     * Manifest type carried in a query parameter, e.g.
     * "?output=m3u8", "?type=mpd", "?extension=mkv", "?mime=hls".
     */
    private fun fromQueryParameters(query: String): String? {
        if (query.isBlank()) return null
        for (parameter in query.split('&')) {
            val key = parameter.substringBefore('=').trim()
            val value = parameter.substringAfter('=', missingDelimiterValue = "").trim()
            if (key.isBlank() || value.isBlank()) continue
            val mime = when (key) {
                "format", "mime", "mime_type", "contenttype", "content_type",
                "type", "ext", "extension", "output" -> {
                    // Accepts both "m3u8" and "application/x-mpegurl" shapes.
                    fromProviderHint(value)
                        ?: fromContentType(value)
                        ?: fromFileExtension(value.substringAfterLast('/').substringAfterLast('.'))
                }
                else -> null
            }
            if (mime != null) return mime
        }
        return null
    }

    /**
     * Manifest tokens appearing as their own path/query segment:
     * "/hls/playlist", "/mpd/xyz", "/hls?token=1" — the pattern real
     * CDNs use for tokenized manifests without extensions.
     */
    private fun fromDelimitedToken(value: String): String? = when {
        DELIMITED_M3U8.containsMatchIn(value) -> APPLICATION_M3U8
        DELIMITED_HLS.containsMatchIn(value) -> APPLICATION_M3U8
        DELIMITED_MPD.containsMatchIn(value) -> APPLICATION_MPD
        DELIMITED_SS.containsMatchIn(value) -> APPLICATION_SS
        else -> null
    }

    private val DELIMITED_M3U8 = Regex("(^|[/=_.?&%-])m3u8($|[/=_.?&%-])")
    private val DELIMITED_HLS = Regex("(^|[/=_.?&%-])hls($|[/=_.?&%-])")
    private val DELIMITED_MPD = Regex("(^|[/=_.?&%-])mpd($|[/=_.?&%-])")
    private val DELIMITED_SS = Regex("(^|[/=_.?&%-])(ism|isml)($|[/=_.?&%-])")
}
