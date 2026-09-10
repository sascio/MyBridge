package com.streambridge.app.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi

/**
 * Normalized playback failure categories. Every player failure maps to
 * exactly one of these; the UI message and the source-fallback policy
 * are driven by the category, never by raw exception text.
 */
enum class PlaybackFailureCategory {
    /** The stream server refused the request (HTTP 403). */
    HTTP_403,

    /** Any other HTTP-level failure. */
    HTTP_OTHER,

    /** The container/manifest could not be recognized or read. */
    CONTAINER_UNSUPPORTED,

    /** The device cannot decode the stream's video codec. */
    VIDEO_DECODER_UNSUPPORTED,

    /** The device cannot decode the stream's audio codec. */
    AUDIO_DECODER_UNSUPPORTED,

    /** A decoder was found but its configuration/initialization failed. */
    CODEC_CONFIGURATION_FAILED,

    /** The media data itself is malformed. */
    MALFORMED_MEDIA,

    /** Connectivity-level failure (DNS, socket, timeout). */
    NETWORK_FAILURE,

    /** The address no longer serves the content (404/410 — expired link). */
    SOURCE_EXPIRED,

    /** Anything else. */
    UNKNOWN_PLAYBACK_ERROR
}

/**
 * Classifies Media3 playback failures into [PlaybackFailureCategory]
 * with a clean user message (no stack traces, no codec jargon beyond
 * a friendly codec name).
 *
 * The classifier entry [classify] walks the exception cause chain of a
 * real PlaybackException. [classifyFrom] is the same decision logic on
 * plain inputs, so the classification contract is unit-testable on the
 * JVM without constructing Media3 objects.
 */
object PlaybackFailureClassifier {

    data class Result(
        val category: PlaybackFailureCategory,
        /** Clean, human-readable message (the only thing users see). */
        val message: String,
        /** HTTP status when the failure was HTTP-level, else null. */
        val httpStatus: Int? = null,
        /** Decoder MIME from a decoder-init failure, when known. */
        val decoderMimeType: String? = null
    )

    /** Classifies a real Media3 error by first extracting the plain facts. */
    @OptIn(UnstableApi::class)
    fun classify(error: PlaybackException): Result {
        var cause: Throwable? = error
        var httpStatus: Int? = null
        var decoderMime: String? = null
        var unrecognizedContainer = false
        var depth = 0
        while (cause != null && depth < 6) {
            val c = cause
            if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                httpStatus = c.responseCode
            }
            if (c is androidx.media3.exoplayer.audio.AudioSink.ConfigurationException ||
                c is androidx.media3.exoplayer.audio.AudioSink.InitializationException
            ) {
                if (decoderMime == null) decoderMime = "audio"
            }
            // Decoder init failures carry the exact codec MIME.
            val decoderInit =
                c as? androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
            if (decoderInit != null && decoderMime == null) {
                decoderMime = decoderInit.mimeType
            }
            // "None of the available extractors could read the stream" —
            // the classic extension-less manifest routed to progressive.
            if (c is androidx.media3.exoplayer.source.UnrecognizedInputFormatException) {
                unrecognizedContainer = true
            }
            cause = c.cause
            depth++
        }
        return classifyFrom(
            errorCode = error.errorCode,
            httpStatus = httpStatus,
            decoderMimeType = decoderMime,
            unrecognizedContainer = unrecognizedContainer
        )
    }

    /**
     * The classification decision itself.
     *
     * @param errorCode PlaybackException.getErrorCode()
     * @param httpStatus status of the failing HTTP response, if any
     * @param decoderMimeType the MIME of a codec that failed to
     *        initialize ("audio/eac3", "video/hevc"…), if any
     * @param unrecognizedContainer true when no extractor could read
     *        the stream bytes (the classic extension-less manifest)
     */
    fun classifyFrom(
        errorCode: Int,
        httpStatus: Int? = null,
        decoderMimeType: String? = null,
        unrecognizedContainer: Boolean = false
    ): Result {
        val category = when {
            httpStatus == 403 -> PlaybackFailureCategory.HTTP_403
            unrecognizedContainer -> PlaybackFailureCategory.CONTAINER_UNSUPPORTED
            httpStatus == 404 || httpStatus == 410 -> PlaybackFailureCategory.SOURCE_EXPIRED
            errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                PlaybackFailureCategory.SOURCE_EXPIRED
            errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                httpStatus != null -> PlaybackFailureCategory.HTTP_OTHER
            decoderMimeType != null && decoderMimeType.startsWith("audio/") ->
                PlaybackFailureCategory.AUDIO_DECODER_UNSUPPORTED
            decoderMimeType != null && decoderMimeType.startsWith("video/") ->
                PlaybackFailureCategory.VIDEO_DECODER_UNSUPPORTED
            errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                decoderMimeType != null -> PlaybackFailureCategory.CODEC_CONFIGURATION_FAILED
            errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
                errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                PlaybackFailureCategory.CONTAINER_UNSUPPORTED
            errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                PlaybackFailureCategory.MALFORMED_MEDIA
            errorCode in IO_NETWORK_CODES -> PlaybackFailureCategory.NETWORK_FAILURE
            else -> PlaybackFailureCategory.UNKNOWN_PLAYBACK_ERROR
        }
        return Result(
            category = category,
            message = messageFor(category, httpStatus, decoderMimeType),
            httpStatus = httpStatus,
            decoderMimeType = decoderMimeType
        )
    }

    private val IO_NETWORK_CODES = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_TIMEOUT
    )

    /** Clean messages per category — the only text users ever see. */
    private fun messageFor(
        category: PlaybackFailureCategory,
        httpStatus: Int?,
        decoderMimeType: String?
    ): String = when (category) {
        PlaybackFailureCategory.HTTP_403 ->
            "This source refused the request (HTTP 403). " +
                "Pick another source — this one did not accept our playback request."
        PlaybackFailureCategory.HTTP_OTHER ->
            "This source returned an error" +
                (httpStatus?.let { " (HTTP $it)" } ?: "") +
                ". Pick another source."
        PlaybackFailureCategory.CONTAINER_UNSUPPORTED ->
            "This stream's format could not be recognized for playback. " +
                "Pick another source."
        PlaybackFailureCategory.VIDEO_DECODER_UNSUPPORTED ->
            "This device cannot decode this stream's video" +
                friendlyCodec(decoderMimeType) + ". Pick another source."
        PlaybackFailureCategory.AUDIO_DECODER_UNSUPPORTED ->
            "This device cannot decode this stream's audio" +
                friendlyCodec(decoderMimeType) + ". " +
                "Pick another source, or retry to play the video without that audio track."
        PlaybackFailureCategory.CODEC_CONFIGURATION_FAILED ->
            "This stream's codec could not be set up on this device. Pick another source."
        PlaybackFailureCategory.MALFORMED_MEDIA ->
            "This stream's data appears to be corrupted. Pick another source."
        PlaybackFailureCategory.NETWORK_FAILURE ->
            "Could not reach the stream server. Check your connection and try again."
        PlaybackFailureCategory.SOURCE_EXPIRED ->
            "This stream link has expired or is gone. Pick another source."
        PlaybackFailureCategory.UNKNOWN_PLAYBACK_ERROR ->
            "This stream could not be played. Pick another source."
    }

    /** " (E-AC-3 audio)" style suffix — friendly names for known codecs. */
    private fun friendlyCodec(mime: String?): String = when (mime?.lowercase()) {
        "audio/eac3" -> " (E-AC-3 audio)"
        "audio/ac3" -> " (AC-3 audio)"
        "audio/truehd" -> " (Dolby TrueHD audio)"
        "audio/dts" -> " (DTS audio)"
        "audio/eac3-joc" -> " (Dolby Atmos audio)"
        "video/hevc" -> " (HEVC video)"
        "video/dolby-vision" -> " (Dolby Vision video)"
        "video/av01" -> " (AV1 video)"
        "video/x-vnd.on2.vp9" -> " (VP9 video)"
        else -> ""
    }
}
