package com.streambridge.app.addon

import com.streambridge.app.addon.model.AddonCatalog
import com.streambridge.app.addon.model.AddonManifest
import com.streambridge.app.addon.model.CatalogRef
import com.streambridge.app.data.db.ExtensionDao
import com.streambridge.app.data.db.ExtensionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * A single installed extension with its parsed manifest.
 */
data class InstalledExtension(
    val addonId: String,
    val name: String,
    val version: String,
    val description: String,
    val baseUrl: String,
    val logo: String?,
    val background: String?,
    val types: List<String>,
    val resources: List<String>,
    val idPrefixes: List<String>,
    val catalogs: List<AddonCatalog>,
    val enabled: Boolean,
    val installedAt: Long,
    val updatedAt: Long
) {
    val supportsCatalog: Boolean get() = resources.any { it.equals("catalog", ignoreCase = true) }
    val supportsMeta: Boolean get() = resources.any { it.equals("meta", ignoreCase = true) }
    val supportsStream: Boolean get() = resources.any { it.equals("stream", ignoreCase = true) }
    val displayName: String get() = name.ifBlank { addonId }
}

sealed interface InstallOutcome {
    data class Success(val extension: InstalledExtension) : InstallOutcome
    data class Failure(val reason: String) : InstallOutcome
}

/**
 * Owns the list of installed extensions.
 *
 * Stream Bridge ships with ZERO extensions: the list starts empty and the
 * user is the only one who can install, enable, disable or remove one.
 */
class ExtensionManager(
    private val dao: ExtensionDao,
    private val api: AddonApi,
    private val json: Json,
    scope: CoroutineScope
) {

    private val _extensions = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val extensions: StateFlow<List<InstalledExtension>> = _extensions.asStateFlow()

    val enabledExtensions: StateFlow<List<InstalledExtension>> = _extensions
        .map { list -> list.filter { it.enabled } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Keys with an operation in flight (installs/refreshes). */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    init {
        scope.launch {
            dao.observeAll().collect { entities ->
                _extensions.value = entities.map { it.toInstalledExtension() }
            }
        }
    }

    /** Result of a pre-install check of an extension URL. */
    sealed interface CheckResult {
        data class InvalidUrl(val reason: String) : CheckResult
        data class Unreachable(val reason: String) : CheckResult
        data class InvalidManifest(val issues: List<String>) : CheckResult
        data class Ok(val manifest: AddonManifest, val baseUrl: String) : CheckResult
    }

    /** Fetches and validates the manifest without installing it. */
    suspend fun check(rawUrl: String): CheckResult {
        val urlResult = UrlValidator.validate(rawUrl)
        if (urlResult is UrlValidator.Result.Invalid) {
            return CheckResult.InvalidUrl(urlResult.reason)
        }
        val baseUrl = HttpAddonApi.normalizeBase((urlResult as UrlValidator.Result.Valid).url)
        val manifest = try {
            api.fetchManifest(baseUrl)
        } catch (e: AddonHttpException) {
            return CheckResult.Unreachable(
                "The extension answered with HTTP ${e.statusCode}. Check the URL and try again."
            )
        } catch (e: Exception) {
            return CheckResult.Unreachable(
                "Could not reach the extension: ${e.message ?: "network error"}"
            )
        }
        return when (val verdict = ManifestValidator.validate(manifest)) {
            is ManifestValidator.Result.Invalid ->
                CheckResult.InvalidManifest(verdict.issues)

            ManifestValidator.Result.Valid ->
                CheckResult.Ok(manifest, baseUrl)
        }
    }

    /** Persists an extension that passed [check]. */
    suspend fun installChecked(manifest: AddonManifest, baseUrl: String): InstalledExtension {
        val now = System.currentTimeMillis()
        val existing = dao.byId(manifest.id)
        val entity = ExtensionEntity(
            addonId = manifest.id,
            name = manifest.name.ifBlank { manifest.id },
            version = manifest.version,
            baseUrl = baseUrl,
            manifestJson = json.encodeToString(AddonManifest.serializer(), manifest),
            enabled = true,
            installedAt = existing?.installedAt ?: now,
            updatedAt = now
        )
        dao.upsert(entity)
        return entity.toInstalledExtension()
    }

    /**
     * Installs (or updates) an extension from a user-supplied URL.
     * The manifest is fetched and validated before anything is persisted.
     */
    suspend fun install(rawUrl: String): InstallOutcome {
        return when (val result = check(rawUrl)) {
            is CheckResult.InvalidUrl -> InstallOutcome.Failure(result.reason)
            is CheckResult.Unreachable -> InstallOutcome.Failure(result.reason)
            is CheckResult.InvalidManifest -> InstallOutcome.Failure(
                "Not a valid Stremio-compatible addon: ${result.issues.joinToString("; ")}"
            )

            is CheckResult.Ok -> InstallOutcome.Success(installChecked(result.manifest, result.baseUrl))
        }
    }

    suspend fun setEnabled(addonId: String, enabled: Boolean) {
        val entity = dao.byId(addonId) ?: return
        dao.upsert(entity.copy(enabled = enabled))
    }

    suspend fun remove(addonId: String) {
        dao.delete(addonId)
    }

    /** Re-fetches and re-validates the manifest of an installed extension. */
    suspend fun refresh(addonId: String): InstallOutcome {
        val entity = dao.byId(addonId) ?: return InstallOutcome.Failure("Extension not found")
        return withBusy(addonId) {
            try {
                val manifest = api.fetchManifest(entity.baseUrl)
                when (val verdict = ManifestValidator.validate(manifest)) {
                    is ManifestValidator.Result.Invalid -> {
                        InstallOutcome.Failure(
                            "Refreshed manifest is invalid: ${verdict.issues.joinToString("; ")}"
                        )
                    }
                    ManifestValidator.Result.Valid -> {
                        val updated = entity.copy(
                            name = manifest.name.ifBlank { entity.name },
                            version = manifest.version,
                            manifestJson = json.encodeToString(AddonManifest.serializer(), manifest),
                            updatedAt = System.currentTimeMillis()
                        )
                        dao.upsert(updated)
                        InstallOutcome.Success(updated.toInstalledExtension())
                    }
                }
            } catch (e: Exception) {
                InstallOutcome.Failure("Refresh failed: ${e.message ?: "network error"}")
            }
        }
    }

    /** Refreshes every enabled extension concurrently. */
    suspend fun refreshAll(): List<InstallOutcome> = coroutineScope {
        enabledExtensions.value.map { extension ->
            async { refresh(extension.addonId) }
        }.awaitAll()
    }

    /** Builds catalog references for the given enabled extensions. */
    fun catalogRefs(extensions: List<InstalledExtension>): List<CatalogRef> {
        return extensions
            .filter { it.enabled && it.supportsCatalog }
            .flatMap { extension ->
                extension.catalogs.map { catalog ->
                    CatalogRef(
                        addonId = extension.addonId,
                        addonName = extension.displayName,
                        baseUrl = extension.baseUrl,
                        type = catalog.type,
                        catalogId = catalog.id,
                        catalogName = catalog.displayName,
                        extraSupported = catalog.effectiveExtraSupported
                    )
                }
            }
    }

    private suspend fun <T> withBusy(key: String, block: suspend () -> T): T {
        _busy.value = _busy.value + key
        try {
            return block()
        } finally {
            _busy.value = _busy.value - key
        }
    }

    private fun ExtensionEntity.toInstalledExtension(): InstalledExtension {
        val manifest = try {
            json.decodeFromString(AddonManifest.serializer(), manifestJson)
        } catch (_: Exception) {
            null
        }
        return InstalledExtension(
            addonId = addonId,
            name = name,
            version = version,
            description = manifest?.description ?: "",
            baseUrl = baseUrl,
            logo = manifest?.logo?.takeIf { it.isNotBlank() },
            background = manifest?.background?.takeIf { it.isNotBlank() },
            types = manifest?.types ?: emptyList(),
            resources = manifest?.resources ?: emptyList(),
            idPrefixes = manifest?.idPrefixes ?: emptyList(),
            catalogs = manifest?.catalogs ?: emptyList(),
            enabled = enabled,
            installedAt = installedAt,
            updatedAt = updatedAt
        )
    }
}
