package com.streambridge.app.player

/**
 * Whether the active playback backend can decode a given format.
 *
 * Every decision in this layer is MIME-based — the same codec always
 * gets the same verdict no matter which provider served the stream
 * (no codec blacklists, no provider-specific rules).
 */
enum class DecoderSupport {
    /** A decoder exists — the format should play. */
    SUPPORTED,

    /** No decoder exists on the backend — avoid this format when an alternative exists. */
    UNSUPPORTED,

    /**
     * Capability could not be determined (missing MIME, failed device
     * query). Never treat as a negative: defer to the player's own
     * selection and the decoder-fallback machinery.
     */
    UNKNOWN
}

/** One decoder the backend can use, reduced to the facts that matter. */
data class DecoderDescriptor(
    val mimeType: String,
    val name: String,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean
)

/**
 * Result of asking a backend "which decoders do you have for this MIME?"
 * Tri-state on purpose: an EMPTY list (definitely no decoder) must never
 * be confused with a FAILED query (device codec service unavailable) —
 * the former may deselect a track, the latter must not.
 */
sealed interface DecoderQuery {
    /** The query succeeded; [decoders] may be empty (= unsupported). */
    data class Decoders(val decoders: List<DecoderDescriptor>) : DecoderQuery

    /** The query itself failed — capability is unknown, not unsupported. */
    data object QueryFailed : DecoderQuery
}

/**
 * Source of decoder availability. An interface so the capability
 * DECISIONS are unit-testable on the JVM with fake registries; the
 * production implementation queries the device through Media3.
 */
interface DecoderRegistry {
    fun query(mimeType: String): DecoderQuery
}

/**
 * Device decoder query through Media3's MediaCodecUtil: hardware and
 * software MediaCodec decoders the platform exposes. Results are cached
 * per MIME (codec queries are not free and tracks change often).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaCodecDecoderRegistry : DecoderRegistry {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, DecoderQuery>()

    override fun query(mimeType: String): DecoderQuery = cache.getOrPut(mimeType) {
        try {
            val infos = androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                .getDecoderInfos(mimeType, /* secure = */ false, /* tunneling = */ false)
            DecoderQuery.Decoders(
                infos.map { DecoderDescriptor(mimeType, it.name, it.hardwareAccelerated, it.softwareOnly) }
            )
        } catch (_: Exception) {
            // A broken codec service must never look like "unsupported".
            DecoderQuery.QueryFailed
        }
    }
}

/**
 * Capability-based format decisions: MIME → decoder availability on the
 * active backend. Pure and provider-agnostic.
 */
object PlaybackCapabilities {

    fun supportFor(registry: DecoderRegistry, mimeType: String?): DecoderSupport {
        val mime = mimeType?.trim().orEmpty()
        if (mime.isEmpty()) return DecoderSupport.UNKNOWN
        val query = runCatching { registry.query(mime) }.getOrNull()
        return when (query) {
            is DecoderQuery.Decoders ->
                if (query.decoders.isEmpty()) DecoderSupport.UNSUPPORTED else DecoderSupport.SUPPORTED
            DecoderQuery.QueryFailed, null -> DecoderSupport.UNKNOWN
        }
    }

    fun canDecode(registry: DecoderRegistry, mimeType: String?): Boolean =
        supportFor(registry, mimeType) == DecoderSupport.SUPPORTED
}
