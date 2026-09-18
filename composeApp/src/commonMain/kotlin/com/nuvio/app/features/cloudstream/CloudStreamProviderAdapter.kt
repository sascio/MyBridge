package com.nuvio.app.features.cloudstream

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.StreamSubtitle

/**
 * Compatibility layer translating CloudStream provider concepts into
 * StreamBridge's unified models.
 *
 * ## Why this exists without an execution backend
 *
 * CloudStream providers are distributed exclusively as compiled Android DEX.
 * StreamBridge does not execute downloaded bytecode, so nothing here ever runs
 * a real plugin today. What this layer does provide is the complete, tested
 * translation contract:
 *
 *   CloudStream search result -> [CloudStreamSearchResult] -> StreamBridge search
 *   CloudStream episode       -> [CloudStreamEpisode]
 *   CloudStream link          -> [CloudStreamLink] -> [StreamItem]
 *   CloudStream subtitle      -> [CloudStreamSubtitleFile] -> [StreamSubtitle]
 *
 * The mapping is the part that is easy to get subtly wrong (dropped Referer,
 * lost cookies, mangled quality), so it is implemented and unit-tested against
 * fixtures now. Should a sanctioned execution backend ever be introduced, it
 * feeds these types and the rest of StreamBridge works unchanged.
 *
 * Nothing in this file fabricates playable media: [adaptLinks] maps only links
 * it is actually given, and returns an empty list for empty input.
 */

/** A CloudStream `SearchResponse`, normalised. */
data class CloudStreamSearchResult(
    val name: String,
    val url: String,
    val posterUrl: String? = null,
    val year: Int? = null,
    /** CloudStream `TvType`: Movie, TvSeries, Anime, Cartoon, ... */
    val type: String? = null,
    /** Headers required to fetch [posterUrl], e.g. a Referer-locked CDN. */
    val posterHeaders: Map<String, String> = emptyMap(),
)

/** A CloudStream `Episode`, normalised. */
data class CloudStreamEpisode(
    val name: String? = null,
    val url: String,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val description: String? = null,
    val rating: Int? = null,
)

/** A CloudStream `ExtractorLink`, normalised. */
data class CloudStreamLink(
    val name: String,
    val url: String,
    /** CloudStream sends Referer separately from [headers]; both are preserved. */
    val referer: String? = null,
    /** CloudStream `Qualities` value, e.g. 1080. */
    val quality: Int? = null,
    /** True for HLS/m3u8 (CloudStream `isM3u8` / type LINK vs M3U8). */
    val isM3u8: Boolean = false,
    val isDash: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    /** Raw Cookie pairs; folded into the request headers on conversion. */
    val cookies: Map<String, String> = emptyMap(),
    /** Provider that produced the link, for source attribution. */
    val source: String? = null,
)

/** A CloudStream `SubtitleFile`, normalised. */
data class CloudStreamSubtitleFile(
    val language: String,
    val url: String,
    val name: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

internal object CloudStreamProviderAdapter {

    /**
     * Converts CloudStream links into StreamBridge [StreamItem]s.
     *
     * Header fidelity is the critical part: many CloudStream CDNs reject
     * requests lacking the exact Referer/User-Agent/Cookie the provider used.
     * All three are merged into [StreamProxyHeaders.request], which is what
     * StreamBridge's player and HTTP layer already honour, so these streams
     * play through the existing player with no CloudStream-specific path.
     */
    fun adaptLinks(
        links: List<CloudStreamLink>,
        pluginName: String,
        pluginId: String,
        subtitles: List<CloudStreamSubtitleFile> = emptyList(),
        pluginLogo: String? = null,
        /**
         * Aggregator group identity. Must be supplied when these streams are
         * published to the source picker so they land in the right group;
         * defaults to the plugin-derived id for standalone mapping/tests.
         */
        addonId: String? = null,
    ): List<StreamItem> {
        if (links.isEmpty()) return emptyList()
        val adaptedSubtitles = adaptSubtitles(subtitles)

        return links.mapNotNull { link ->
            val url = link.url.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val requestHeaders = buildRequestHeaders(link)

            StreamItem(
                name = link.name.takeIf { it.isNotBlank() } ?: pluginName,
                title = buildTitle(link),
                description = qualityLabel(link.quality),
                url = url,
                sourceName = link.source?.takeIf { it.isNotBlank() } ?: pluginName,
                addonName = pluginName,
                addonId = addonId ?: cloudStreamAddonId(pluginId),
                addonLogo = pluginLogo,
                streamType = streamType(link),
                behaviorHints = StreamBehaviorHints(
                    notWebReady = requestHeaders.isNotEmpty(),
                    proxyHeaders = requestHeaders
                        .takeIf { it.isNotEmpty() }
                        ?.let { StreamProxyHeaders(request = it) },
                ),
                externalSubtitles = adaptedSubtitles,
            )
        }
    }

    /** Converts CloudStream subtitle files, preserving any required headers. */
    fun adaptSubtitles(subtitles: List<CloudStreamSubtitleFile>): List<StreamSubtitle> =
        subtitles.mapNotNull { subtitle ->
            val url = subtitle.url.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            StreamSubtitle(
                url = url,
                language = subtitle.language.takeIf { it.isNotBlank() } ?: "unknown",
                name = subtitle.name,
                headers = subtitle.headers.takeIf { it.isNotEmpty() },
            )
        }

    /**
     * Merges Referer, explicit headers and cookies into one header map.
     *
     * Explicit headers win over the derived Referer so a provider can override
     * it deliberately. Cookies are serialised into a single `Cookie` header
     * per RFC 6265, and an existing `Cookie` header is never clobbered.
     */
    fun buildRequestHeaders(link: CloudStreamLink): Map<String, String> {
        val headers = linkedMapOf<String, String>()

        link.referer?.trim()?.takeIf { it.isNotEmpty() }?.let { headers["Referer"] = it }

        link.headers.forEach { (key, value) ->
            val name = key.trim()
            if (name.isNotEmpty()) headers[name] = value
        }

        if (link.cookies.isNotEmpty()) {
            val hasCookieHeader = headers.keys.any { it.equals("Cookie", ignoreCase = true) }
            if (!hasCookieHeader) {
                val cookie = link.cookies
                    .filterKeys { it.isNotBlank() }
                    .entries
                    .joinToString("; ") { (k, v) -> "${k.trim()}=$v" }
                if (cookie.isNotEmpty()) headers["Cookie"] = cookie
            }
        }

        return headers
    }

    /** Stable, namespaced addon id so CloudStream sources are attributable. */
    fun cloudStreamAddonId(pluginId: String): String = "cloudstream:$pluginId"

    private fun buildTitle(link: CloudStreamLink): String? {
        val quality = qualityLabel(link.quality)
        val source = link.source?.takeIf { it.isNotBlank() }
        return listOfNotNull(source, quality).takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }

    private fun streamType(link: CloudStreamLink): String = when {
        link.isM3u8 -> "hls"
        link.isDash -> "dash"
        else -> "http"
    }

    /** CloudStream `Qualities` ints map onto conventional labels. */
    fun qualityLabel(quality: Int?): String? = when {
        quality == null || quality <= 0 -> null
        quality >= 4320 -> "8K"
        quality >= 2160 -> "4K"
        quality >= 1440 -> "1440p"
        quality >= 1080 -> "1080p"
        quality >= 720 -> "720p"
        quality >= 480 -> "480p"
        quality >= 360 -> "360p"
        else -> "${quality}p"
    }
}
