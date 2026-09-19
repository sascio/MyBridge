package com.nuvio.app.features.cloudstream

import kotlinx.serialization.Serializable

/**
 * UI-facing state for the CloudStream Extensions screen.
 *
 * An **Extension** is one CloudStream repository entry; the **Sources** it
 * exposes are the individual providers declared by that extension. The two are
 * deliberately separate concepts and are never flattened together.
 */

/** Live counters shown in the Overview card. All derived, never hardcoded. */
data class CloudStreamOverview(
    val extensionCount: Int = 0,
    val activeCount: Int = 0,
    val catalogCount: Int = 0,
)

/**
 * One provider/source entry exposed by an extension.
 *
 * A source is only [installed] when registration genuinely succeeded. For
 * `.cs3` plugins requiring DEX execution this stays false, because StreamBridge
 * has no sanctioned execution backend.
 */
data class CloudStreamSource(
    val id: String,
    val name: String,
    /** Content type declared by the extension, e.g. Movie / TvSeries / Anime. */
    val contentType: String? = null,
    val language: String? = null,
    val compatibility: CloudStreamCompatibility,
    val compatibilityReason: CloudStreamCompatibilityReason,
    val installed: Boolean = false,
    val enabled: Boolean = false,
    /**
     * Configuration this source genuinely supports. Empty means the Configure
     * action must not be offered at all.
     */
    val configuration: List<CloudStreamConfigField> = emptyList(),
) {
    /** True only when the source can really be turned on. */
    val canActivate: Boolean
        get() = compatibility == CloudStreamCompatibility.COMPATIBLE &&
            compatibilityReason == CloudStreamCompatibilityReason.NONE

    /** Configure is offered only when real configuration exists. */
    val supportsConfiguration: Boolean
        get() = configuration.isNotEmpty()
}

/** A single supported configuration field. */
@Serializable
data class CloudStreamConfigField(
    val key: String,
    val label: String,
    val value: String = "",
    val isSecret: Boolean = false,
)

/** An extension plus the sources it exposes. */
data class CloudStreamExtension(
    val plugin: CloudStreamPlugin,
    val repositoryUrl: String,
    val sources: List<CloudStreamSource> = emptyList(),
    /** Download/install lifecycle for this extension's `.cs3` package. */
    val installStatus: CloudStreamInstallStatus = CloudStreamInstallStatus(),
) {
    val id: String get() = plugin.id
    val name: String get() = plugin.displayName
    val compatibility: CloudStreamCompatibility get() = plugin.compatibility
    val compatibilityReason: CloudStreamCompatibilityReason get() = plugin.compatibilityReason

    /**
     * An extension is active only when its package is genuinely installed *and*
     * at least one of its sources is enabled. A source flag alone is never
     * enough: without an installed package nothing can actually run.
     */
    val isActive: Boolean get() = installStatus.isInstalled && sources.any { it.enabled }

    val sourceCount: Int get() = sources.size

    /** Whether installing (or retrying) is a meaningful action right now. */
    val canInstall: Boolean
        get() = !installStatus.isBusy &&
            !installStatus.isInstalled &&
            !plugin.artifactUrl.isNullOrBlank()

    /** Whether an update is offered. */
    val canUpdate: Boolean
        get() = !installStatus.isBusy &&
            installStatus.state == CloudStreamInstallState.UPDATE_AVAILABLE
}

/** Everything the Extensions screen renders. */
data class CloudStreamUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val repositories: List<CloudStreamRepository> = emptyList(),
    val extensions: List<CloudStreamExtension> = emptyList(),
    /** Set when the most recent user action failed. Actionable text. */
    val errorMessage: String? = null,
) {
    val overview: CloudStreamOverview
        get() = CloudStreamOverview(
            extensionCount = extensions.size,
            activeCount = extensions.count { it.isActive },
            catalogCount = extensions.sumOf { it.sourceCount },
        )

    val isEmpty: Boolean get() = extensions.isEmpty()

    val hasRepositories: Boolean get() = repositories.isNotEmpty()

    /** Repositories that could not be read, for the problem banner. */
    val failedRepositories: List<CloudStreamRepository>
        get() = repositories.filter { it.compatibility == CloudStreamCompatibility.FAILED }
}

/** Persisted per-source user state. */
@Serializable
internal data class CloudStreamSourceState(
    val sourceId: String,
    val enabled: Boolean = false,
    val installed: Boolean = false,
)

/** Result of adding a repository, so the UI can report precisely. */
sealed interface AddCloudStreamRepositoryResult {
    data class Success(val repository: CloudStreamRepository) : AddCloudStreamRepositoryResult
    data class Error(val message: String) : AddCloudStreamRepositoryResult
}
