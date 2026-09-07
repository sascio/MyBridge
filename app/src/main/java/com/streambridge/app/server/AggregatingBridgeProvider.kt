package com.streambridge.app.server

import com.streambridge.app.addon.AddonApi
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.HttpAddonApi
import com.streambridge.app.addon.model.MetaResponse
import com.streambridge.app.addon.model.StreamResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bridges the app's installed extensions onto the LAN as a single
 * Stremio-compatible addon:
 *
 *  - catalogs are exposed as `{addonId}::{catalogId}`
 *  - meta requests fan out across extensions until one answers
 *  - stream requests fan out across ALL enabled extensions and the
 *    stream lists are merged
 */
class AggregatingBridgeProvider(
    private val extensionManager: ExtensionManager,
    private val api: AddonApi,
    private val json: Json,
    private val appVersion: String
) : BridgeContentProvider {

    override fun manifest(): String {
        val extensions = extensionManager.enabledExtensions.value
        val refs = extensionManager.catalogRefs(extensions)
        val types = extensions
            .flatMap { it.types }
            .distinct()
            .filter { it.isNotBlank() }
            .let { if (it.isEmpty()) listOf("movie", "series") else it }

        return buildJsonObject {
            put("id", BRIDGE_ADDON_ID)
            put("version", appVersion)
            put("name", "Stream Bridge")
            put("description", "Aggregates the extensions installed in the Stream Bridge app on this device.")
            put("resources", buildJsonArray {
                add("catalog")
                add("meta")
                add("stream")
            })
            put("types", buildJsonArray { types.forEach { add(it) } })
            put("catalogs", buildJsonArray {
                refs.forEach { ref ->
                    add(
                        buildJsonObject {
                            put("type", ref.type)
                            put("id", ref.compositeId)
                            put("name", "${ref.addonName} · ${ref.catalogName}")
                            if (ref.extraSupported.isNotEmpty()) {
                                put("extraSupported", buildJsonArray {
                                    ref.extraSupported.forEach { add(it) }
                                })
                            }
                        }
                    )
                }
            })
            put("idPrefixes", buildJsonArray {
                extensions.flatMap { it.idPrefixes }.distinct().forEach { add(it) }
            })
        }.toString()
    }

    override fun health(): String {
        return buildJsonObject {
            put("status", "ok")
            put("service", "Stream Bridge")
            put("version", appVersion)
        }.toString()
    }

    override fun landing(baseUrl: String): String {
        return buildJsonObject {
            put("service", "Stream Bridge")
            put("description", "Add this device to another Stremio-compatible app on your network.")
            put("addonUrl", "$baseUrl/manifest.json")
            put("endpoints", buildJsonArray {
                add("/health")
                add("/manifest.json")
                add("/catalog/{type}/{id}.json")
                add("/meta/{type}/{id}.json")
                add("/stream/{type}/{id}.json")
            })
        }.toString()
    }

    override fun catalog(type: String, compositeCatalogId: String, extraSegment: String?): String {
        val parts = compositeCatalogId.split("::", limit = 2)
        if (parts.size != 2) {
            throw BridgeNotFoundException("Unknown catalog: $compositeCatalogId")
        }
        val addonId = parts[0]
        val catalogId = parts[1]
        guardSegment(catalogId)
        guardSegment(type)

        val extension = extensionManager.enabledExtensions.value
            .find { it.addonId == addonId && it.enabled }
            ?: throw BridgeNotFoundException("Extension '$addonId' is not installed or not enabled")

        val extraPath = extraSegment?.takeIf { it.isNotBlank() }?.let { raw ->
            raw.split("/")
                .filter { it.isNotBlank() }
                .joinToString("/") { pair ->
                    guardSegment(pair)
                    val eq = pair.indexOf('=')
                    if (eq <= 0 || eq == pair.length - 1) {
                        throw BridgeBadRequestException("Malformed extra parameter: $pair")
                    }
                    val key = HttpAddonApi.encodePath(pair.substring(0, eq))
                    val value = HttpAddonApi.encodePath(pair.substring(eq + 1))
                    "$key=$value"
                }
        }

        val path = buildString {
            append("catalog/")
            append(HttpAddonApi.encodePath(type))
            append('/')
            append(HttpAddonApi.encodePath(catalogId))
            if (!extraPath.isNullOrBlank()) {
                append('/')
                append(extraPath)
            }
            append(".json")
        }

        return runBlocking {
            withTimeoutOrNull(UPSTREAM_TIMEOUT_MS) { api.fetchRaw(extension.baseUrl, path) }
        } ?: throw BridgeNotFoundException("Upstream catalog did not respond")
    }

    override fun meta(type: String, id: String): String? {
        guardSegment(id)
        guardSegment(type)
        val extensions = extensionManager.enabledExtensions.value.filter { it.supportsMeta }
        for (extension in extensions) {
            val meta = runBlocking {
                withTimeoutOrNull(UPSTREAM_TIMEOUT_MS) { api.fetchMeta(extension.baseUrl, type, id) }
            } ?: continue
            if (meta != null) {
                return json.encodeToString(MetaResponse.serializer(), MetaResponse(meta))
            }
        }
        return null
    }

    override fun stream(type: String, id: String): String {
        guardSegment(id)
        guardSegment(type)
        val extensions = extensionManager.enabledExtensions.value.filter { it.supportsStream }

        val streams = runBlocking {
            coroutineScope {
                extensions.map { extension ->
                    async {
                        try {
                            withTimeoutOrNull(UPSTREAM_TIMEOUT_MS) {
                                api.fetchStreams(extension.baseUrl, type, id)
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }
        }.filterNotNull().flatMap { it.streams }

        return json.encodeToString(StreamResponse.serializer(), StreamResponse(streams))
    }

    /** Rejects path segments that could escape the intended route. */
    private fun guardSegment(segment: String) {
        if (segment.length > 1024 ||
            segment.contains("..") ||
            segment.contains('\\') ||
            segment.contains('\u0000') ||
            segment.startsWith("/")
        ) {
            throw BridgeBadRequestException("Illegal path segment")
        }
    }

    companion object {
        const val BRIDGE_ADDON_ID = "app.streambridge.bridge"
        private const val UPSTREAM_TIMEOUT_MS = 20000L
    }
}
