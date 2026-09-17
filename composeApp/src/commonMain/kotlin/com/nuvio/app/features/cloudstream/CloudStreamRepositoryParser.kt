package com.nuvio.app.features.cloudstream

import kotlinx.serialization.json.Json

/**
 * Pure parsing/normalisation for CloudStream repository documents.
 *
 * Kept free of I/O so the whole compatibility decision tree is unit-testable
 * with deterministic fixtures. [CloudStreamRepositoryLoader] supplies the
 * network.
 *
 * Hostile-input policy: a single malformed plugin entry must never discard an
 * otherwise good repository. Unparseable entries are surfaced as FAILED rows.
 */
internal object CloudStreamRepositoryParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Parses a `repo.json` document. Returns null when unusable. */
    fun parseRepositoryManifest(raw: String): CloudStreamRepositoryManifest? {
        if (raw.isBlank()) return null
        return runCatching {
            json.decodeFromString<CloudStreamRepositoryManifest>(raw)
        }.getOrNull()
    }

    /**
     * Parses a `plugins.json` document.
     *
     * Returns null only when the document itself is not a usable JSON array.
     * Element-level damage is tolerated and reported per entry.
     */
    fun parsePluginList(raw: String): List<CloudStreamPluginManifest>? {
        if (raw.isBlank()) return null
        // Strict pass: the overwhelmingly common case.
        runCatching {
            return json.decodeFromString<List<CloudStreamPluginManifest>>(raw)
        }
        // Salvage pass: decode element-by-element so one broken object does not
        // cost the user every other plugin in the list.
        return runCatching {
            val array = json.parseToJsonElement(raw)
            val elements = (array as? kotlinx.serialization.json.JsonArray) ?: return null
            elements.mapNotNull { element ->
                runCatching {
                    json.decodeFromJsonElement(CloudStreamPluginManifest.serializer(), element)
                }.getOrNull()
            }
        }.getOrNull()
    }

    /**
     * Classifies a plugin manifest and normalises it.
     *
     * CloudStream provider logic is distributed only as compiled Android DEX
     * (`classes.dex` inside the `.cs3` archive). StreamBridge deliberately does
     * not execute downloaded bytecode, so even a perfectly-formed plugin is
     * reported as [CloudStreamCompatibility.UNSUPPORTED] with reason
     * [CloudStreamCompatibilityReason.REQUIRES_NATIVE_EXECUTION].
     *
     * Metadata-level defects are still classified first, so users get the most
     * specific and actionable reason available.
     */
    fun toPlugin(manifest: CloudStreamPluginManifest): CloudStreamPlugin {
        val displayName = manifest.name?.takeIf { it.isNotBlank() }
            ?: manifest.internalName?.takeIf { it.isNotBlank() }
        val identity = manifest.internalName?.takeIf { it.isNotBlank() }
            ?: manifest.name?.takeIf { it.isNotBlank() }
        val artifactUrl = manifest.url?.trim()?.takeIf { it.isNotEmpty() }

        // A row with no identity at all cannot be represented meaningfully.
        if (identity == null) {
            return CloudStreamPlugin(
                id = "",
                displayName = "Unknown extension",
                version = manifest.version,
                description = manifest.description,
                authors = manifest.authors,
                language = manifest.language,
                tvTypes = manifest.tvTypes,
                iconUrl = manifest.iconUrl,
                artifactUrl = artifactUrl,
                repositoryUrl = manifest.repositoryUrl,
                fileSize = manifest.fileSize,
                fileHash = manifest.fileHash,
                apiVersion = manifest.apiVersion,
                compatibility = CloudStreamCompatibility.FAILED,
                compatibilityReason = CloudStreamCompatibilityReason.INCOMPLETE_METADATA,
            )
        }

        val apiVersion = manifest.apiVersion
        val reason = when {
            apiVersion != null && apiVersion > CLOUDSTREAM_SUPPORTED_PLUGIN_API_VERSION ->
                CloudStreamCompatibilityReason.UNSUPPORTED_API_VERSION

            artifactUrl == null ->
                CloudStreamCompatibilityReason.INCOMPLETE_METADATA

            // Well-formed and understood, but still not executable by design.
            else -> CloudStreamCompatibilityReason.REQUIRES_NATIVE_EXECUTION
        }

        return CloudStreamPlugin(
            id = identity,
            displayName = displayName ?: identity,
            version = manifest.version,
            description = manifest.description,
            authors = manifest.authors,
            language = manifest.language,
            tvTypes = manifest.tvTypes,
            iconUrl = manifest.iconUrl,
            artifactUrl = artifactUrl,
            repositoryUrl = manifest.repositoryUrl,
            fileSize = manifest.fileSize,
            fileHash = manifest.fileHash,
            apiVersion = apiVersion,
            compatibility = CloudStreamCompatibility.UNSUPPORTED,
            compatibilityReason = reason,
            installed = false,
        )
    }

    /** True when we understand the repository manifest version. */
    fun isSupportedManifestVersion(manifest: CloudStreamRepositoryManifest): Boolean {
        val version = manifest.manifestVersion ?: return true
        return version <= CLOUDSTREAM_SUPPORTED_MANIFEST_VERSION
    }
}
