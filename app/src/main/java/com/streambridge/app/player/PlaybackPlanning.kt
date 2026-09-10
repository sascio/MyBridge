package com.streambridge.app.player

import com.streambridge.app.addon.StreamHeaders
import com.streambridge.app.addon.model.StreamOption

/**
 * Everything the player needs to start ONE stream, decided up front in
 * a pure, unit-testable layer: the exact URL, that source's OWN
 * sanitized request context (headers), and the stream's MIME type.
 *
 * This is the layer where request context used to get lost: headers
 * were applied to one URL only and the container type was never
 * passed to the player. [planPlayback] now resolves both BEFORE the
 * player sees anything, mirroring the reference behavior.
 *
 * @param url validated playback URL
 * @param headers this source's sanitized request headers (Referer,
 *        User-Agent, Cookie, Origin… — exactly what the provider
 *        supplied, nothing global)
 * @param mimeType resolved container/manifest MIME, or null when
 *        genuinely unknown (progressive sniffing then decides — no
 *        fake MIME is ever set)
 */
data class PlaybackPreparation(
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?
) {
    val isManifest: Boolean get() = mimeType in StreamMimeTypes.MANIFEST_MIMES
}

/**
 * Resolves a stream option into a [PlaybackPreparation].
 *
 * MIME resolution order (all evidence-based, first hit wins):
 *  1. the provider-supplied hint ([StreamOption.mimeType]) — the
 *     provider knows its own links best
 *  2. the URL itself: extension, query parameters, delimited tokens
 *  3. an HTTP probe of the server's real Content-Type — only when the
 *     URL carries no evidence, because it costs a network roundtrip.
 *
 * [probe] is injected so planning is fully testable on the JVM; the
 * production probe is [StreamMimeProbe].
 */
object PlaybackPlanning {

    fun planPlayback(
        option: StreamOption,
        probe: suspend (url: String, headers: Map<String, String>) -> String? = { _, _ -> null }
    ): PlaybackPreparation {
        val url = option.url ?: return PlaybackPreparation("", emptyMap(), null)
        val headers = StreamHeaders.sanitize(option.headers)
        val mimeType = resolveMimeType(url, option.mimeType, headers, probe)
        return PlaybackPreparation(url = url, headers = headers, mimeType = mimeType)
    }

    internal suspend fun resolveMimeType(
        url: String,
        providerHint: String,
        headers: Map<String, String>,
        probe: suspend (url: String, headers: Map<String, String>) -> String?
    ): String? {
        // 1. Provider hint (already normalized to a known MIME, or empty).
        if (providerHint in StreamMimeTypes.MANIFEST_MIMES) return providerHint
        // 2. URL evidence — free and reliable.
        StreamMimeTypes.fromUrl(url)?.let { return it }
        // A provider hint for a PROGRESSIVE container does not change the
        // media source type, so it is safe to honor it after URL checks.
        if (providerHint.isNotBlank()) return providerHint
        // 3. Ask the server (HEAD, then a header-only GET). Only for
        //    http(s) links; everything else stays genuinely unknown.
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return runCatching { probe(url, headers) }.getOrNull()
    }

    /**
     * The next untried alternate after a failed source, in session
     * order — bounded by the caller so each source is tried at most
     * once and nothing is hammered.
     */
    fun nextAlternate(
        tried: Set<String>,
        alternates: List<StreamOption>
    ): StreamOption? = alternates.firstOrNull { it.isPlayable && it.id !in tried }
}
