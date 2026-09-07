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
    val behaviorHints: AddonManifestBehaviorHints? = null
)

@Serializable
data class AddonMetaPreview(
    val id: String = "",
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
    val genres: List<String> = emptyList()
)

@Serializable
data class AddonMetaBehaviorHints(
    @Serializable(with = LenientStringSerializer::class) val defaultVideoId: String = ""
)

@Serializable
data class AddonVideo(
    val id: String = "",
    @Serializable(with = LenientStringSerializer::class) val title: String = "",
    @Serializable(with = LenientStringSerializer::class) val released: String = "",
    @Serializable(with = LenientStringSerializer::class) val thumbnail: String = "",
    @Serializable(with = LenientIntSerializer::class) val season: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val episode: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val number: Int = 0,
    @Serializable(with = LenientStringSerializer::class) val overview: String = "",
    val available: Boolean? = null
) {
    val episodeNumber: Int get() = if (episode > 0) episode else number
}

@Serializable
data class AddonMeta(
    val id: String = "",
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
    @Serializable(with = LenientStringSerializer::class) val country: String = "",
    @Serializable(with = LenientStringSerializer::class) val awards: String = "",
    @Serializable(with = LenientStringSerializer::class) val released: String = "",
    @Serializable(with = LenientStringSerializer::class) val language: String = "",
    val videos: List<AddonVideo> = emptyList(),
    val behaviorHints: AddonMetaBehaviorHints? = null
)

@Serializable
data class CatalogResponse(
    val metas: List<AddonMetaPreview> = emptyList()
)

@Serializable
data class MetaResponse(
    val meta: AddonMeta? = null
)

@Serializable
data class AddonStreamBehaviorHints(
    val notWebReady: Boolean? = null,
    @Serializable(with = LenientStringSerializer::class) val bingeGroup: String = ""
)

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
