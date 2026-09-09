package com.streambridge.app.addon.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * JSON models for the Stremio-compatible addon protocol.
 *
 * The addon protocol is intentionally liberal with types: many community
 * addons send numbers where the spec says strings, objects where the spec
 * says arrays of names, etc. The serializers below accept the common
 * real-world shapes without ever crashing a whole response.
 */

/** Accepts a JSON string/number/boolean (or null) and produces a String. */
object LenientStringSerializer : KSerializer<String> {
    private val delegate = String.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder
        if (input == null) return decoder.decodeString()
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> element.contentOrNull ?: ""
            else -> ""
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

/** Accepts a JSON number (int-like), string or null and produces an Int. */
object LenientIntSerializer : KSerializer<Int> {
    private val delegate = Int.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Int {
        val input = decoder as? JsonDecoder
        if (input == null) return decoder.decodeInt()
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> element.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
            else -> 0
        }
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

/**
 * Accepts an array of strings OR an array of objects that contain a `name`
 * field (used by some addons for `cast`, `director` and `resources`).
 */
object LenientStringListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val input = decoder as? JsonDecoder
        if (input == null) return delegate.deserialize(decoder)
        return when (val element = input.decodeJsonElement()) {
            is JsonArray -> element.mapNotNull { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull
                    is JsonObject -> (item["name"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }
            }
            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        delegate.serialize(encoder, value)
    }
}

@Serializable
data class AddonManifestBehaviorHints(
    val adult: Boolean = false,
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false
)

@Serializable
data class AddonCatalogExtra(
    val name: String = "",
    val isRequired: Boolean = false,
    val options: List<String> = emptyList()
)

@Serializable
data class AddonCatalog(
    val type: String = "",
    val id: String = "",
    val name: String? = null,
    val extraRequired: List<String> = emptyList(),
    val extraSupported: List<String> = emptyList(),
    val extra: List<AddonCatalogExtra> = emptyList()
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: id
    val effectiveExtraSupported: List<String>
        get() = if (extraSupported.isNotEmpty()) extraSupported else extra.map { it.name }
    val effectiveExtraRequired: List<String>
        get() = if (extraRequired.isNotEmpty()) extraRequired else extra.filter { it.isRequired }.map { it.name }
}

@Serializable
data class AddonManifest(
    val id: String = "",
    val version: String = "",
    val name: String = "",
    val description: String = "",
    @Serializable(with = LenientStringSerializer::class) val logo: String = "",
    @Serializable(with = LenientStringSerializer::class) val background: String = "",
    val types: List<String> = emptyList(),
    @Serializable(with = LenientStringListSerializer::class) val resources: List<String> = emptyList(),
    val catalogs: List<AddonCatalog> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    val behaviorHints: AddonManifestBehaviorHints? = null,
    /** Addon-provided catalogs OF addons (community lists). */
    val addonCatalogs: List<AddonCatalog> = emptyList()
) {
    val effectiveAddonCatalogs: List<AddonCatalog>
        get() = addonCatalogs
}

@Serializable
data class AddonMetaPreview(
    val id: String = "",
    @JsonNames("imdb_id")
    val imdbId: String? = null,
    val type: String = "",
    val name: String = "",
    @Serializable(with = LenientStringSerializer::class) val poster: String = "",
    @Serializable(with = LenientStringSerializer::class) val logo: String = "",
    @Serializable(with = LenientStringSerializer::class) val background: String = "",
    @Serializable(with = LenientStringSerializer::class) val description: String = "",
    @Serializable(with = LenientStringSerializer::class) val releaseInfo: String = "",
    @Serializable(with = LenientStringSerializer::class) val imdbRating: String = "",
    @Serializable(with = LenientStringSerializer::class) val runtime: String = "",
    /** Transport URL, present on addon_catalog entries. */
    @Serializable(with = LenientStringSerializer::class) val transportUrl: String = "",
    val genres: List<String> = emptyList()
)

@Serializable
data class AddonMetaBehaviorHints(
    @Serializable(with = LenientStringSerializer::class) val defaultVideoId: String = ""
)

/** A YouTube-style trailer reference (`{"source": "<ytId>", "type": "Trailer"}`). */
@Serializable
data class AddonTrailerRef(
    @Serializable(with = LenientStringSerializer::class) val source: String = "",
    @Serializable(with = LenientStringSerializer::class) val type: String = ""
)

/** Stremio's `trailerStreams` shape (`{"title": ..., "ytId": ...}`). */
@Serializable
data class AddonTrailerStream(
    @Serializable(with = LenientStringSerializer::class) val title: String = "",
    @Serializable(with = LenientStringSerializer::class) val ytId: String = "",
    @SerialName("yt_id")
    @Serializable(with = LenientStringSerializer::class) val ytIdLegacy: String = ""
) {
    val youtubeId: String get() = ytId.ifBlank { ytIdLegacy }
}

@Serializable
data class AddonVideo(
    val id: String = "",
    // Real-world episodes (Cinemeta) name the title field "name".
    @JsonNames("name")
    @Serializable(with = LenientStringSerializer::class) val title: String = "",
    @Serializable(with = LenientStringSerializer::class) val released: String = "",
    @Serializable(with = LenientStringSerializer::class) val thumbnail: String = "",
    @Serializable(with = LenientIntSerializer::class) val season: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val episode: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val number: Int = 0,
    // Some addons only send "description" on episodes.
    @JsonNames("description")
    @Serializable(with = LenientStringSerializer::class) val overview: String = "",
    val available: Boolean? = null
) {
    val episodeNumber: Int get() = if (episode > 0) episode else number
}

@Serializable
data class AddonMeta(
    val id: String = "",
    @JsonNames("imdb_id")
    val imdbId: String? = null,
    val type: String = "",
    val name: String = "",
    @Serializable(with = LenientStringSerializer::class) val poster: String = "",
    @Serializable(with = LenientStringSerializer::class) val logo: String = "",
    @Serializable(with = LenientStringSerializer::class) val background: String = "",
    @Serializable(with = LenientStringSerializer::class) val description: String = "",
    @Serializable(with = LenientStringSerializer::class) val releaseInfo: String = "",
    @Serializable(with = LenientStringSerializer::class) val imdbRating: String = "",
    @Serializable(with = LenientStringSerializer::class) val runtime: String = "",
    val genres: List<String> = emptyList(),
    @Serializable(with = LenientStringListSerializer::class) val cast: List<String> = emptyList(),
    @Serializable(with = LenientStringListSerializer::class) val director: List<String> = emptyList(),
    @Serializable(with = LenientStringListSerializer::class) val writer: List<String> = emptyList(),
    @Serializable(with = LenientStringSerializer::class) val country: String = "",
    @Serializable(with = LenientStringSerializer::class) val awards: String = "",
    @Serializable(with = LenientStringSerializer::class) val trailer: String = "",
    /** IMDb-style trailer refs (`{"source": ytId}`), used when `trailer` is absent. */
    val trailers: List<AddonTrailerRef> = emptyList(),
    /** Stremio `trailerStreams` refs, used when `trailer` is absent. */
    val trailerStreams: List<AddonTrailerStream> = emptyList(),
    @Serializable(with = LenientStringSerializer::class) val released: String = "",
    @Serializable(with = LenientStringSerializer::class) val language: String = "",
    val videos: List<AddonVideo> = emptyList(),
    val behaviorHints: AddonMetaBehaviorHints? = null
) {
    /**
     * Best trailer URL: a direct URL when the addon sent one, otherwise
     * the first YouTube reference (Cinemeta sends `trailers`/`trailerStreams`
     * instead of a URL). Bare ids are shaped into watch URLs.
     */
    val effectiveTrailer: String
        get() {
            if (trailer.isNotBlank()) return trailer
            val ytId = trailerStreams.firstOrNull { it.youtubeId.isNotBlank() }?.youtubeId
                ?: trailers.firstOrNull { it.source.isNotBlank() }?.source
                ?: return ""
            return if (ytId.startsWith("http")) ytId else "https://www.youtube.com/watch?v=$ytId"
        }
}

@Serializable
data class CatalogResponse(
    val metas: List<AddonMetaPreview> = emptyList()
)

/**
 * The shape real-world `addon_catalog` endpoints answer with (Cinemeta,
 * the official catalog, uses this): a list of addons, each carrying its
 * transport URL and its FULL embedded manifest. Accepted in addition to
 * the `{"metas":[...]}` preview shape.
 */
@Serializable
data class AddonCatalogEntry(
    @Serializable(with = LenientStringSerializer::class) val transportUrl: String = "",
    @Serializable(with = LenientStringSerializer::class) val transportName: String = "",
    val manifest: AddonManifest? = null
)

@Serializable
data class AddonCatalogResponse(
    val addons: List<AddonCatalogEntry> = emptyList()
)

@Serializable
data class MetaResponse(
    val meta: AddonMeta? = null
)

@Serializable
data class AddonStreamBehaviorHints(
    val notWebReady: Boolean? = null,
    @Serializable(with = LenientStringSerializer::class) val bingeGroup: String = "",
    /** Headers the addon requires for its (proxied) stream URL. */
    val proxyHeaders: AddonProxyHeaders? = null
)

/**
 * Stremio's proxyHeaders shape: `{"request": {"User-Agent": ..., ...}}`.
 * Some addons send odd value types; the lenient serializer coerces
 * primitives to strings instead of failing the whole stream response.
 */
@Serializable
data class AddonProxyHeaders(
    @Serializable(with = LenientStringMapSerializer::class)
    val request: Map<String, String> = emptyMap()
)

/** Accepts a JSON object of primitives (or anything else) as a string map. */
object LenientStringMapSerializer : KSerializer<Map<String, String>> {
    private val delegate =
        kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val input = decoder as? JsonDecoder
        if (input == null) return delegate.deserialize(decoder)
        return when (val element = input.decodeJsonElement()) {
            is JsonObject -> element.entries.mapNotNull { (key, value) ->
                when (value) {
                    is JsonPrimitive -> key to (value.contentOrNull ?: return@mapNotNull null)
                    else -> null
                }
            }.toMap()
            else -> emptyMap()
        }
    }

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        delegate.serialize(encoder, value)
    }
}

@Serializable
data class AddonStream(
    @Serializable(with = LenientStringSerializer::class) val url: String = "",
    @Serializable(with = LenientStringSerializer::class) val ytId: String = "",
    @SerialName("yt_id")
    @Serializable(with = LenientStringSerializer::class) val ytIdLegacy: String = "",
    @Serializable(with = LenientStringSerializer::class) val externalUrl: String = "",
    @SerialName("external_url")
    @Serializable(with = LenientStringSerializer::class) val externalUrlLegacy: String = "",
    @Serializable(with = LenientStringSerializer::class) val infoHash: String = "",
    @SerialName("info_hash")
    @Serializable(with = LenientStringSerializer::class) val infoHashLegacy: String = "",
    @Serializable(with = LenientIntSerializer::class) val fileIdx: Int = -1,
    @SerialName("file_idx")
    @Serializable(with = LenientIntSerializer::class) val fileIdxLegacy: Int = -1,
    @Serializable(with = LenientStringSerializer::class) val name: String = "",
    @Serializable(with = LenientStringSerializer::class) val title: String = "",
    @Serializable(with = LenientStringSerializer::class) val description: String = "",
    val sources: List<String> = emptyList(),
    val behaviorHints: AddonStreamBehaviorHints? = null
) {
    val torrentHash: String get() = infoHash.ifBlank { infoHashLegacy }
    val externalLink: String get() = externalUrl.ifBlank { externalUrlLegacy }
    val youtubeId: String get() = ytId.ifBlank { ytIdLegacy }
    val torrentFileIndex: Int get() = if (fileIdx >= 0) fileIdx else fileIdxLegacy
    val displayDescription: String get() = description.ifBlank { title }
    val bingeGroup: String get() = behaviorHints?.bingeGroup ?: ""

    val isDirect: Boolean get() = url.isNotBlank() && url.startsWith("http")
    val isTorrent: Boolean get() = torrentHash.isNotBlank()
    val isExternal: Boolean get() = externalLink.isNotBlank()
    val isYouTube: Boolean get() = youtubeId.isNotBlank()
}

@Serializable
data class StreamResponse(
    val streams: List<AddonStream> = emptyList()
)

/** An addon-provided external subtitle (Stremio /subtitles resource). */
@Serializable
data class AddonSubtitle(
    @Serializable(with = LenientStringSerializer::class) val url: String = "",
    @Serializable(with = LenientStringSerializer::class) val lang: String = "",
    @Serializable(with = LenientStringSerializer::class) val id: String = "",
    @Serializable(with = LenientStringSerializer::class) val label: String = "",
    val format: String = ""
) {
    val displayLabel: String
        get() = label.ifBlank {
            when {
                lang.length == 2 -> lang.uppercase()
                lang.isNotBlank() -> lang
                else -> "Subtitle"
            }
        }
}

@Serializable
data class SubtitleResponse(
    val subtitles: List<AddonSubtitle> = emptyList()
)
