package com.streambridge.app.addon.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Nuvio-compatible plugin repository models.
 *
 * A plugin repository is a manifest (JSON) that lists JavaScript
 * providers. Each provider is a CommonJS module exporting
 * `getStreams(tmdbId, mediaType, season, episode)` and returning an
 * array of `{ name, title, url, quality, headers? }` stream objects.
 *
 * Parsing is deliberately lenient: manifests in the wild carry extra
 * fields, mixed types and missing keys. Nothing here may throw on
 * unknown or absent data — unknown fields are ignored and optional
 * fields fall back to sane defaults.
 */
object NuvioManifest {

    /** One provider entry inside a repository manifest. */
    data class Provider(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val author: String,
        /** "movie" and/or "tv" */
        val supportedTypes: List<String>,
        /** Path relative to the manifest's directory, e.g. "providers/x.js". */
        val filename: String,
        /** Manifest's suggested default state (user choice wins). */
        val enabled: Boolean,
        val hasSettings: Boolean,
        val formats: List<String>,
        val logo: String,
        val contentLanguage: List<String>,
        val limited: Boolean,
        val resources: List<String>
    ) {
        val displayName: String get() = name.ifBlank { id }
        fun supportsType(type: String): Boolean =
            supportedTypes.isEmpty() || supportedTypes.any { it.equals(type, ignoreCase = true) }
    }

    sealed interface ParseResult {
        data class Valid(val providers: List<Provider>) : ParseResult
        data class Invalid(val reason: String) : ParseResult
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses a Nuvio repository manifest. Accepts both common shapes:
     * a top-level array of providers, or an object with a
     * `providers`/`plugins`/`addons` array.
     */
    fun parse(manifestText: String): ParseResult {
        val root = try {
            json.parseToJsonElement(manifestText)
        } catch (e: Exception) {
            return ParseResult.Invalid("The manifest is not valid JSON: ${e.message?.take(80)}")
        }
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val container = root.keys.firstNotNullOfOrNull { key ->
                    val value = root[key]
                    if (value is JsonArray && key in CONTAINER_KEYS) key else null
                }
                    ?: return ParseResult.Invalid(
                        "The manifest does not contain a provider list " +
                            "(expected a JSON array or an object with a \"providers\" array)"
                    )
                (root[container] as? JsonArray) ?: JsonArray(emptyList())
            }
            else -> return ParseResult.Invalid("The manifest has an unexpected shape")
        }

        val providers = array
            .mapNotNull { element -> element as? JsonObject }
            .mapIndexedNotNull { index, obj -> parseProvider(obj, index) }

        if (providers.isEmpty()) {
            return ParseResult.Invalid("The manifest does not contain any usable providers")
        }
        return ParseResult.Valid(providers)
    }

    private val CONTAINER_KEYS = setOf("providers", "plugins", "addons")

    private fun parseProvider(obj: JsonObject, index: Int): Provider? {
        val id = obj.string("id").ifBlank { obj.string("name").ifBlank { "provider-$index" } }
        val filename = obj.string("filename")
        if (filename.isBlank()) return null // provider without code is unusable
        return Provider(
            id = id,
            name = obj.string("name"),
            description = obj.string("description"),
            version = obj.string("version"),
            author = obj.string("author"),
            supportedTypes = obj.stringList("supportedTypes", "types"),
            filename = filename,
            enabled = obj.boolean("enabled") ?: false,
            hasSettings = obj.boolean("hasSettings") ?: false,
            formats = obj.stringList("formats"),
            logo = obj.string("logo"),
            contentLanguage = obj.stringList("contentLanguage", "languages"),
            limited = obj.boolean("limited") ?: false,
            resources = obj.stringList("resources")
        )
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim() ?: ""

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.stringList(vararg keys: String): List<String> {
        for (key in keys) {
            when (val value = this[key]) {
                is JsonArray -> {
                    val list = value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                        .filter { it.isNotBlank() }
                    if (list.isNotEmpty()) return list
                }
                is JsonPrimitive -> {
                    val single = value.contentOrNull?.trim()
                    if (!single.isNullOrBlank()) return listOf(single)
                }
                else -> Unit
            }
        }
        return emptyList()
    }

    /**
     * Resolves a provider's code URL from the repository manifest URL:
     * `https://host/repo/manifest.json` + `providers/x.js` →
     * `https://host/repo/providers/x.js`. Absolute filenames are taken
     * as full URLs.
     */
    fun providerCodeUrl(manifestUrl: String, filename: String): String? {
        val trimmed = filename.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val base = manifestUrl.substringBeforeLast('/', missingDelimiterValue = "")
        if (base.isBlank()) return null
        val clean = trimmed.removePrefix("./").removePrefix("/")
        return "$base/$clean"
    }
}
