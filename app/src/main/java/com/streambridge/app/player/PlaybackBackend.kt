package com.streambridge.app.player

import androidx.media3.common.MimeTypes
import com.streambridge.app.addon.StreamMimeTypes

/**
 * A playback engine that can play a prepared stream. Media3/ExoPlayer is
 * the primary backend today; the reference app reaches Nuvio-level codec
 * coverage through a SECOND tier of software decoding (media3 extension
 * renderers built from ffmpeg/av1, with libmpv behind those). A backend
 * with that capability plugs in here with the same contract — the choice
 * is decided by FORMAT CAPABILITY, never by provider or URL.
 */
interface PlaybackBackend {
    val id: String

    /** Container/manifest-level support for a prepared stream. */
    fun supportsContainer(mimeType: String?): DecoderSupport

    /** Codec-level support on this device (null MIME → UNKNOWN). */
    fun supportsCodec(mimeType: String?): DecoderSupport
}

/**
 * The media3/ExoPlayer backend: every container StreamBridge can name,
 * with the device's MediaCodec decoders (hardware and software) behind
 * it. Codec support is queried per device at runtime — no hardcoded
 * blacklists — so the same build behaves like a capable device on a
 * capable device and degrades honestly elsewhere.
 */
class Media3PlaybackBackend(private val registry: DecoderRegistry) : PlaybackBackend {

    override val id: String = ID

    override fun supportsContainer(mimeType: String?): DecoderSupport {
        if (mimeType == null) return DecoderSupport.UNKNOWN // progressive: sniffing decides
        // MIME comparison is case-insensitive by spec (RFC 6838) and in
        // practice: our own cascade emits lowercase, media3's constants
        // are mixed case ("application/x-mpegURL"), servers send anything.
        val normalized = mimeType.trim().lowercase()
        return when {
            normalized in MANIFEST_CONTAINER_MIMES -> DecoderSupport.SUPPORTED
            normalized in PROGRESSIVE_CONTAINER_MIMES -> DecoderSupport.SUPPORTED
            else -> DecoderSupport.UNKNOWN // never claim unsupported from a hint alone
        }
    }

    override fun supportsCodec(mimeType: String?): DecoderSupport =
        PlaybackCapabilities.supportFor(registry, mimeType)

    companion object {
        const val ID = "media3"

        private val MANIFEST_CONTAINER_MIMES =
            StreamMimeTypes.MANIFEST_MIMES.map { it.lowercase() }.toSet()

        /**
         * Progressive containers media3's bundled extractors read with
         * certainty (sniffing still governs everything else at runtime).
         */
        private val PROGRESSIVE_CONTAINER_MIMES = setOf(
            MimeTypes.APPLICATION_MP4,
            MimeTypes.VIDEO_MP4,
            MimeTypes.VIDEO_MP2T,
            MimeTypes.APPLICATION_MATROSKA,
            MimeTypes.VIDEO_MATROSKA,
            MimeTypes.VIDEO_WEBM,
            MimeTypes.AUDIO_MPEG,
            MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_FLAC,
            MimeTypes.AUDIO_OPUS,
            MimeTypes.AUDIO_VORBIS,
            MimeTypes.AUDIO_RAW
        ).map { it.lowercase() }.toSet()
    }
}

/**
 * Which playback backend plays a prepared stream. Capability-based and
 * provider-agnostic: the same container/codec from any provider yields
 * the same decision. Primary (media3) is preferred; a registered
 * secondary wins exactly when the primary cannot handle the format.
 */
object PlaybackBackendSelector {

    data class Decision(
        val backend: PlaybackBackend,
        val containerSupport: DecoderSupport,
        /** Ids of every backend considered, in preference order. */
        val considered: List<String>
    )

    fun select(
        preparation: PlaybackPreparation,
        backends: List<PlaybackBackend>
    ): Decision {
        if (backends.isEmpty()) {
            throw IllegalArgumentException("No playback backend registered")
        }
        val mime = preparation.mimeType
        val chosen = backends.firstOrNull { it.supportsContainer(mime) == DecoderSupport.SUPPORTED }
            ?: backends.firstOrNull { it.supportsContainer(mime) != DecoderSupport.UNSUPPORTED }
            ?: backends.first()
        return Decision(
            backend = chosen,
            containerSupport = chosen.supportsContainer(mime),
            considered = backends.map { it.id }
        )
    }
}

/**
 * Detects media3 software-decoder extension renderers on the classpath
 * (ffmpeg/av1/mpegh — the reference app ships these as locally built
 * AARs; they are NOT published on Maven). When present, the player
 * automatically enables them as fallback decoders. Today they are not
 * bundled with StreamBridge, so this honestly reports none.
 */
object SoftwareDecoderExtensions {

    val DEFAULT_RENDERER_CLASSES = listOf(
        "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer",
        "androidx.media3.decoder.ffmpeg.FfmpegVideoRenderer",
        "androidx.media3.decoder.av1.Gav1VideoRenderer",
        "androidx.media3.decoder.mpegh.MpeghAudioRenderer"
    )

    /** The extension renderer classes actually present on the classpath. */
    fun present(
        candidateClasses: List<String> = DEFAULT_RENDERER_CLASSES,
        classLoader: ClassLoader? = null
    ): List<String> = candidateClasses.filter { className ->
        runCatching {
            Class.forName(
                className,
                /* initialize = */ false,
                classLoader ?: SoftwareDecoderExtensions::class.java.classLoader
            )
        }.isSuccess
    }
}
