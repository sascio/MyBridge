package com.streambridge.app.addon

import com.streambridge.app.addon.model.AddonManifest
import com.streambridge.app.addon.model.AddonSubtitle
import com.streambridge.app.addon.model.AddonMeta
import com.streambridge.app.addon.model.CatalogResponse
import com.streambridge.app.addon.model.MetaResponse
import com.streambridge.app.addon.model.StreamResponse
import com.streambridge.app.addon.model.SubtitleResponse
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Transport-agnostic access to a Stremio-compatible addon.
 * The interface exists so logic (and unit tests) can run against fakes.
 */
interface AddonApi {
    suspend fun fetchManifest(baseUrl: String): AddonManifest

    /**
     * The raw (unparsed) manifest text, for diagnostics such as
     * recognizing non-Stremio manifests. Empty by default so fakes
     * and tests are unaffected.
     */
    suspend fun fetchManifestText(baseUrl: String): String = ""
    suspend fun fetchCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        search: String? = null,
        genre: String? = null,
        skip: Int? = null
    ): CatalogResponse

    /** Returns null when the addon answers 404 (no meta for this id). */
    suspend fun fetchMeta(baseUrl: String, type: String, id: String): AddonMeta?

    suspend fun fetchStreams(baseUrl: String, type: String, id: String): StreamResponse

    /** Addon-provided external subtitles; empty when unsupported/404. */
    suspend fun fetchSubtitles(baseUrl: String, type: String, id: String): List<AddonSubtitle>

    /** Fetches an arbitrary path under the addon base and returns the raw body. */
    suspend fun fetchRaw(baseUrl: String, path: String): String
}

/**
 * HTTP implementation of [AddonApi] following the Stremio addon protocol:
 *
 *  - `{base}/manifest.json`
 *  - `{base}/catalog/{type}/{catalogId}[/{extra}].json`
 *  - `{base}/meta/{type}/{id}.json` (response: `{"meta": {...}}`)
 *  - `{base}/stream/{type}/{id}.json`
 *
 * where `{extra}` is a query-string-stringified list such as
 * `search=batman&skip=100`.
 */
class HttpAddonApi(
    private val http: SbHttpClient,
    private val json: Json
) : AddonApi {

    override suspend fun fetchManifest(baseUrl: String): AddonManifest {
        val body = http.get(manifestUrl(baseUrl), timeoutMs = 12000L)
        return json.decodeFromString(AddonManifest.serializer(), body)
    }

    override suspend fun fetchManifestText(baseUrl: String): String =
        http.get(manifestUrl(baseUrl), timeoutMs = 12000L)

    override suspend fun fetchCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        search: String?,
        genre: String?,
        skip: Int?
    ): CatalogResponse {
        val url = catalogUrl(baseUrl, type, catalogId, search, genre, skip)
        val body = http.get(url, timeoutMs = 20000L)
        return json.decodeFromString(CatalogResponse.serializer(), body)
    }

    override suspend fun fetchMeta(baseUrl: String, type: String, id: String): AddonMeta? {
        val url = "${normalizeBase(baseUrl)}/meta/${encodePath(type)}/${encodePath(id)}.json"
        return try {
            val body = http.get(url, timeoutMs = 15000L)
            json.decodeFromString(MetaResponse.serializer(), body).meta
        } catch (e: AddonHttpException) {
            if (e.statusCode == 404) null else throw e
        }
    }

    override suspend fun fetchStreams(baseUrl: String, type: String, id: String): StreamResponse {
        val url = "${normalizeBase(baseUrl)}/stream/${encodePath(type)}/${encodePath(id)}.json"
        return try {
            val body = http.get(url, timeoutMs = 20000L)
            json.decodeFromString(StreamResponse.serializer(), body)
        } catch (e: AddonHttpException) {
            if (e.statusCode == 404) StreamResponse() else throw e
        }
    }

    override suspend fun fetchSubtitles(
        baseUrl: String,
        type: String,
        id: String
    ): List<AddonSubtitle> {
        val url = subtitleUrl(baseUrl, type, id)
        return try {
            val body = http.get(url, timeoutMs = 12000L)
            json.decodeFromString(SubtitleResponse.serializer(), body).subtitles
        } catch (e: AddonHttpException) {
            if (e.statusCode == 404) emptyList() else throw e
        }
    }

    override suspend fun fetchRaw(baseUrl: String, path: String): String {
        return http.get("${normalizeBase(baseUrl)}/${path.trimStart('/')}")
    }

    companion object {
        fun manifestUrl(baseUrl: String): String =
            "${normalizeBase(baseUrl)}/manifest.json"

        fun normalizeBase(rawUrl: String): String {
            var base = rawUrl.trim()
            while (base.endsWith("/")) {
                base = base.dropLast(1)
            }
            if (base.endsWith("/manifest.json", ignoreCase = true)) {
                base = base.removeSuffix("/manifest.json")
            }
            return base
        }

        fun catalogUrl(
            baseUrl: String,
            type: String,
            catalogId: String,
            search: String? = null,
            genre: String? = null,
            skip: Int? = null
        ): String {
            val extras = buildList {
                search?.let { add("search=${encodeQueryValue(it)}") }
                genre?.let { add("genre=${encodeQueryValue(it)}") }
                skip?.let { add("skip=$it") }
            }
            val extraPart = if (extras.isEmpty()) "" else "/${extras.joinToString("&")}"
            return "${normalizeBase(baseUrl)}/catalog/${encodePath(type)}/${encodePath(catalogId)}$extraPart.json"
        }

        fun streamUrl(baseUrl: String, type: String, id: String): String =
            "${normalizeBase(baseUrl)}/stream/${encodePath(type)}/${encodePath(id)}.json"

        fun subtitleUrl(baseUrl: String, type: String, id: String): String =
            "${normalizeBase(baseUrl)}/subtitles/${encodePath(type)}/${encodePath(id)}.json"

        fun addonCatalogUrl(baseUrl: String, type: String, catalogId: String): String =
            "${normalizeBase(baseUrl)}/addon_catalog/${encodePath(type)}/${encodePath(catalogId)}.json"

        fun configureUrl(baseUrl: String): String =
            "${normalizeBase(baseUrl)}/configure"

        /** Encodes a single path segment, keeping `:` (used in video ids). */
        fun encodePath(value: String): String =
            URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
                .replace("%3A", ":")

        /** Encodes a query-string value inside the extra segment. */
        fun encodeQueryValue(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
