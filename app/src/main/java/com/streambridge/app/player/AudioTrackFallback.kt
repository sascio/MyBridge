package com.streambridge.app.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * One selectable audio track of the current stream, together with its
 * decode capability on the active backend.
 *
 * The MIME used for capability decisions is the sample MIME, falling
 * back to the codec string ("ec-3", "mp4a.40.2"…) resolved through
 * media3's own [MimeTypes.getMediaMimeType] — so HLS/DASH manifests
 * that only carry codec strings are covered too.
 */
data class AudioTrackCandidate(
    val groupIndex: Int,
    val trackIndex: Int,
    val format: Format,
    val support: DecoderSupport,
    val mimeType: String? = format.sampleMimeType
        ?: format.codecs?.let { MimeTypes.getMediaMimeType(it) }
) {
    /** Stable per-stream identity of this track (used to bound retries). */
    val key: String get() = "$groupIndex:$trackIndex"

    val channels: Int get() = format.channelCount

    /** Human-readable summary for diagnostics and user notices. */
    val summary: String
        get() {
            val codec = friendlyCodecName(mimeType)
            val name = format.label?.takeIf { it.isNotBlank() }
                ?: format.language?.takeIf { it.isNotBlank() }?.uppercase()
                ?: codec
                ?: "audio"
            val channelsText = if (channels > 0) " ${channels}ch" else ""
            return if (codec != null && name != codec) "$name ($codec$channelsText)" else "$name$channelsText"
        }
}

/**
 * Capability-aware audio track policy (pure, unit-testable):
 *
 *  - PREEMPTIVE: when the track Media3 selected is KNOWN-undecodable
 *    (UNSUPPORTED) and a KNOWN-decodable alternative exists, switch
 *    before a decoder failure ever happens. A supported preferred
 *    track is never downgraded — E-AC-3 5.1 stays selected when the
 *    device can decode it.
 *  - POST-FAILURE: after a decoder-init failure, pick the next best
 *    candidate that is not known-undecodable and not already tried,
 *    preferring the failed track's language, then channel count, then
 *    bitrate.
 *
 * Muting is NEVER chosen here — only by the caller when this policy
 * returns null (no candidate worth trying is left).
 */
object AudioTrackPolicy {

    /**
     * The alternative to switch to when the currently selected audio
     * track is known-undecodable, or null when the selection should be
     * left alone (supported, unknown, or nothing strictly better).
     */
    fun preemptiveSwitch(
        selected: AudioTrackCandidate?,
        candidates: List<AudioTrackCandidate>
    ): AudioTrackCandidate? {
        if (selected == null || selected.support != DecoderSupport.UNSUPPORTED) return null
        return bestAlternative(
            candidates = candidates,
            excludeKeys = setOf(selected.key),
            requireSupported = true
        )
    }

    /**
     * Best remaining candidate after failures:
     *  - excludes UNSUPPORTED tracks and already-tried keys/mimes
     *  - ranks KNOWN-decodable above unknown-capability tracks
     *  - prefers the failed selection's language, then more channels,
     *    then higher bitrate, then stream order for determinism
     */
    fun bestAlternative(
        candidates: List<AudioTrackCandidate>,
        excludeMimeTypes: Set<String> = emptySet(),
        excludeKeys: Set<String> = emptySet(),
        preferLanguageOf: Format? = null,
        requireSupported: Boolean = false
    ): AudioTrackCandidate? {
        val language = preferLanguageOf?.language?.takeIf { it.isNotBlank() }?.lowercase()
        return candidates
            .asSequence()
            .filter { candidate ->
                candidate.support != DecoderSupport.UNSUPPORTED &&
                    candidate.key !in excludeKeys &&
                    (candidate.mimeType?.lowercase() ?: "") !in excludeMimeTypes
            }
            .filter { candidate -> !requireSupported || candidate.support == DecoderSupport.SUPPORTED }
            .sortedWith(
                compareByDescending<AudioTrackCandidate> { it.support == DecoderSupport.SUPPORTED }
                    .thenByDescending { language != null && it.format.language?.lowercase() == language }
                    .thenByDescending { it.channels }
                    .thenByDescending { it.format.bitrate }
                    .thenBy { it.groupIndex }
                    .thenBy { it.trackIndex }
            )
            .firstOrNull()
    }
}

/** Friendly codec names for diagnostics and user notices. */
fun friendlyCodecName(mimeType: String?): String? = when (mimeType?.lowercase()) {
    MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
    MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
    MimeTypes.AUDIO_AC3 -> "AC-3"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS, "audio/dts-hd", "audio/dts-hd-ma", "audio/dts-x" -> "DTS"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_ALAC -> "ALAC"
    MimeTypes.AUDIO_RAW -> "PCM"
    MimeTypes.VIDEO_H265, MimeTypes.VIDEO_DOLBY_VISION -> "HEVC"
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.VIDEO_VP8 -> "VP8"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    MimeTypes.VIDEO_MP4V -> "MPEG-4"
    else -> null
}
