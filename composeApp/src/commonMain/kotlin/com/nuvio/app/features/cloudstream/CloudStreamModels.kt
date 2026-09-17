package com.nuvio.app.features.cloudstream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data model for CloudStream repository interoperability.
 *
 * The schema mirrors the real CloudStream repository contract as published by
 * community repositories:
 *
 *  - `repo.json`    -> [CloudStreamRepositoryManifest]
 *  - `plugins.json` -> list of [CloudStreamPluginManifest]
 *
 * Every field is optional except the ones CloudStream itself treats as
 * mandatory, because third-party repositories are frequently incomplete. A
 * missing optional field must degrade the entry, never fail the whole
 * repository.
 */

/** Highest `manifestVersion` of the CloudStream repository format we understand. */
internal const val CLOUDSTREAM_SUPPORTED_MANIFEST_VERSION = 1

/** Highest CloudStream plugin `apiVersion` this compatibility layer models. */
internal const val CLOUDSTREAM_SUPPORTED_PLUGIN_API_VERSION = 1

/**
 * `repo.json` — the entry point a user adds by URL.
 *
 * `pluginLists` holds URLs of `plugins.json` documents; a repository may split
 * its catalogue across several lists.
 */
@Serializable
data class CloudStreamRepositoryManifest(
    val name: String? = null,
    val description: String? = null,
    @SerialName("manifestVersion") val manifestVersion: Int? = null,
    @SerialName("pluginLists") val pluginLists: List<String> = emptyList(),
    val iconUrl: String? = null,
)

/**
 * A single entry of `plugins.json`.
 *
 * [url] points at the compiled `.cs3` artifact. StreamBridge records it for
 * provenance and integrity reporting but never downloads and executes it; see
 * [CloudStreamCompatibility] for the rationale.
 */
@Serializable
data class CloudStreamPluginManifest(
    val name: String? = null,
    @SerialName("internalName") val internalName: String? = null,
    val url: String? = null,
    val version: Int? = null,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    val status: Int? = null,
    @SerialName("tvTypes") val tvTypes: List<String> = emptyList(),
    val language: String? = null,
    @SerialName("iconUrl") val iconUrl: String? = null,
    @SerialName("repositoryUrl") val repositoryUrl: String? = null,
    @SerialName("fileSize") val fileSize: Long? = null,
    @SerialName("fileHash") val fileHash: String? = null,
    @SerialName("apiVersion") val apiVersion: Int? = null,
    @SerialName("requiresResources") val requiresResources: Boolean? = null,
)

/**
 * `manifest.json` stored inside a `.cs3` archive.
 *
 * Modelled for completeness and for integrity/compatibility reporting. The
 * archive's sibling entry is `classes.dex`, which StreamBridge does not load.
 */
@Serializable
data class CloudStreamPluginArchiveManifest(
    @SerialName("pluginClassName") val pluginClassName: String? = null,
    val name: String? = null,
    val version: Int? = null,
    @SerialName("requiresResources") val requiresResources: Boolean? = null,
)

/**
 * How well a CloudStream plugin can be represented by StreamBridge.
 *
 * These states are reported honestly to the user. StreamBridge never presents
 * an extension as working when its provider logic cannot actually run.
 */
enum class CloudStreamCompatibility {
    /** Metadata is complete and the declared API version is understood. */
    COMPATIBLE,

    /**
     * Usable metadata, but something is missing or newer than we model
     * (unknown `apiVersion`, no artifact URL, no declared content types).
     */
    PARTIALLY_COMPATIBLE,

    /**
     * Understood, but StreamBridge cannot execute it. CloudStream ships
     * provider logic exclusively as compiled Android DEX bytecode; running it
     * would mean executing arbitrary remote code, which StreamBridge does not
     * do.
     */
    UNSUPPORTED,

    /** The entry could not be parsed or the repository could not be read. */
    FAILED,
}

/** Why a plugin is not fully usable. Surfaced to the user verbatim. */
enum class CloudStreamCompatibilityReason {
    /** Nothing blocking was detected at the metadata level. */
    NONE,

    /** Provider logic is compiled DEX; StreamBridge will not execute it. */
    REQUIRES_NATIVE_EXECUTION,

    /** Declared `apiVersion` is newer than this compatibility layer models. */
    UNSUPPORTED_API_VERSION,

    /** Entry lacks fields required to identify or fetch the plugin. */
    INCOMPLETE_METADATA,

    /** The plugin list or repository manifest could not be parsed/reached. */
    MALFORMED_OR_UNREACHABLE,
}

/**
 * A CloudStream plugin after normalisation and compatibility classification.
 *
 * [installed] is only ever true when an install genuinely succeeded; discovery
 * alone never marks a plugin installed.
 */
data class CloudStreamPlugin(
    val id: String,
    val displayName: String,
    val version: Int?,
    val description: String?,
    val authors: List<String>,
    val language: String?,
    val tvTypes: List<String>,
    val iconUrl: String?,
    val artifactUrl: String?,
    val repositoryUrl: String?,
    val fileSize: Long?,
    val fileHash: String?,
    val apiVersion: Int?,
    val compatibility: CloudStreamCompatibility,
    val compatibilityReason: CloudStreamCompatibilityReason,
    val installed: Boolean = false,
) {
    /** True when StreamBridge can actually execute this plugin's provider logic. */
    val isExecutable: Boolean
        get() = compatibility == CloudStreamCompatibility.COMPATIBLE &&
            compatibilityReason == CloudStreamCompatibilityReason.NONE
}

/** Outcome of loading one repository URL. */
data class CloudStreamRepository(
    val url: String,
    val name: String,
    val description: String? = null,
    val plugins: List<CloudStreamPlugin> = emptyList(),
    val compatibility: CloudStreamCompatibility = CloudStreamCompatibility.COMPATIBLE,
    val compatibilityReason: CloudStreamCompatibilityReason = CloudStreamCompatibilityReason.NONE,
    /** Human-readable failure text when the repository could not be read. */
    val errorMessage: String? = null,
) {
    val isUsable: Boolean
        get() = compatibility != CloudStreamCompatibility.FAILED
}
