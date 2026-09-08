package com.streambridge.app.player

import java.net.URI

/**
 * Validates a stream URL before it is handed to the player, and infers
 * the content type (progressive / HLS / DASH / SmoothStreaming / RTSP).
 *
 * Media3 builds media sources synchronously on the main thread when
 * setMediaItem() is called; a URL it cannot even classify (or a scheme
 * with no supporting module) used to escape as an IllegalStateException
 * and crash the app. Validation happens here first so every bad URL
 * becomes a readable error instead.
 *
 * Parsing is deliberately lenient-but-safe: strict java.net.URI first,
 * with a conservative manual fallback for the mildly-legal URLs real CDNs
 * emit. Pure JVM logic — unit tested.
 */
object StreamValidator {

    /** Content kinds the player can be asked to open. */
    enum class ContentType { PROGRESSIVE, HLS, DASH, SMOOTH_STREAMING, RTSP }

    sealed interface Result {
        data class Valid(
            val url: String,
            val contentType: ContentType
        ) : Result

        data class Invalid(val reason: String) : Result
    }

    private const val MAX_URL_LENGTH = 2048
    private val SUPPORTED_SCHEMES = setOf("http", "https", "rtsp", "rtp")
    private val CONTROL_OR_SPACE = Regex("[\\s\\x00-\\x1F\\x7F]")
    private val SCHEME_PREFIX = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

    fun validate(rawUrl: String?): Result {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return Result.Invalid("The stream link is empty")
        if (url.length > MAX_URL_LENGTH) {
            return Result.Invalid("The stream link is unusually long and was rejected")
        }
        if (CONTROL_OR_SPACE.containsMatchIn(url)) {
            return Result.Invalid("The stream link contains invalid characters")
        }

        val scheme = SCHEME_PREFIX.find(url)?.groupValues?.get(1)?.lowercase()
            ?: url.substringBefore(':', missingDelimiterValue = "").lowercase()
                .takeIf { url.startsWith("$it:") && it.isNotBlank() }
        if (scheme.isNullOrBlank()) {
            return Result.Invalid("The stream link has no http(s) address")
        }
        if (scheme !in SUPPORTED_SCHEMES) {
            return Result.Invalid(
                "Unsupported link type ($scheme). Only http, https and rtsp streams can be played"
            )
        }
        if (scheme == "http" || scheme == "https") {
            val host = extractHost(url)
            if (host.isNullOrEmpty()) {
                return Result.Invalid("The stream link has no host name")
            }
        }

        return Result.Valid(url, inferContentType(url, mime = null))
    }

    /** Host extraction: strict URI first, conservative string fallback. */
    private fun extractHost(url: String): String? {
        val strict = try {
            URI(url).host
        } catch (_: Exception) {
            null
        }
        if (!strict.isNullOrBlank()) return strict
        // Lenient fallback: scheme://host[:port]/...
        val afterScheme = url.substringAfter("://", missingDelimiterValue = url)
        val hostPort = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = hostPort.substringBefore(':')
        return host.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' } }
    }

    /**
     * Content type inference mirroring Media3's Util.inferContentTypeForUriAndMimeType:
     * extension first, an explicit MIME type (validated) wins when supplied.
     */
    fun inferContentType(url: String, mime: String?): ContentType {
        if (!mime.isNullOrBlank()) {
            when (mime.trim().lowercase()) {
                "application/x-mpegurl", "application/vnd.apple.mpegurl" -> return ContentType.HLS
                "application/dash+xml" -> return ContentType.DASH
                "application/vnd.ms-sstr+xml" -> return ContentType.SMOOTH_STREAMING
            }
        }
        val lower = url.substringBefore('?').lowercase()
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        return when {
            scheme == "rtsp" || scheme == "rtp" -> ContentType.RTSP
            lower.endsWith(".m3u8") -> ContentType.HLS
            lower.endsWith(".mpd") -> ContentType.DASH
            lower.endsWith(".ism") || lower.endsWith(".isml") ||
                lower.endsWith(".ism/manifest") || lower.endsWith(".isml/manifest") ->
                ContentType.SMOOTH_STREAMING
            else -> ContentType.PROGRESSIVE
        }
    }
}
