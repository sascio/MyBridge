package com.streambridge.app.addon

import com.streambridge.app.addon.adapter.AddonAdapterRegistry
import com.streambridge.app.addon.model.AddonCatalog
import com.streambridge.app.addon.model.AddonManifest
import com.streambridge.app.addon.plugin.NuvioManifest
import com.streambridge.app.addon.model.CatalogRef
import com.streambridge.app.data.db.ExtensionDao
import com.streambridge.app.data.db.ExtensionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    val addonCatalogs: List<AddonCatalog> = emptyList(),
    val enabled: Boolean,
    val ecosystem: String = "stremio",
    val adultContent: Boolean = false,
    val configurable: Boolean = false,
    val sortOrder: Int = 0,
    val installedAt: Long,
    val updatedAt: Long
) {
    val supportsCatalog: Boolean get() = resources.any { it.equals("catalog", ignoreCase = true) }
    val supportsMeta: Boolean get() = resources.any { it.equals("meta", ignoreCase = true) }
    val supportsStream: Boolean get() = resources.any { it.equals("stream", ignoreCase = true) }
    val supportsSubtitles: Boolean get() = resources.any { it.equals("subtitles", ignoreCase = true) }
    val supportsAddonCatalog: Boolean get() = resources.any { it.equals("addon_catalog", ignoreCase = true) }
    val displayName: String get() = name.ifBlank { addonId }

    /** Addon's own configure page (when behaviorHints.configurable). */
    val configureUrl: String? get() = if (configurable) "$baseUrl/configure" else null
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
        /**
         * @param isNuvioPlugin true when the manifest failed Stremio
         * validation but parses as a Nuvio plugin repository — the user
         * should be pointed to the Plugin screen instead.
         */
        data class InvalidManifest(
            val issues: List<String>,
            val isNuvioPlugin: Boolean = false
        ) : CheckResult
        data class Ok(
            val manifest: AddonManifest,
            val baseUrl: String,
            val ecosystem: String = "stremio"
        ) : CheckResult
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
            is ManifestValidator.Result.Invalid -> {
                // Only on the failure path: re-read the raw text and check
                // whether this is actually a Nuvio plugin repository, so
                // the user can be pointed to the Plugin screen.
                val isNuvioPlugin = try {
                    val raw = api.fetchManifestText(baseUrl)
                    raw.isNotBlank() &&
                        NuvioManifest.parse(raw) is NuvioManifest.ParseResult.Valid
                } catch (_: Exception) {
                    false
                }
                CheckResult.InvalidManifest(verdict.issues, isNuvioPlugin)
            }

            ManifestValidator.Result.Valid -> {
                val ecosystem = AddonAdapterRegistry.forUrl(baseUrl).ecosystem
                CheckResult.Ok(manifest, baseUrl, ecosystem)
            }
        }
    }

    /**
     * Persists an extension that passed [check]. Defense in depth: the
     * URL and the manifest are re-validated here too, so no call path
     * (including installs discovered inside an addon catalog) can
     * persist an unvalidated or non-local-http source.
     */
    suspend fun installChecked(
        manifest: AddonManifest,
        baseUrl: String,
        ecosystem: String = "stremio"
    ): InstallOutcome {
        when (val urlVerdict = UrlValidator.validate(baseUrl)) {
            is UrlValidator.Result.Invalid ->
                return InstallOutcome.Failure("Extension URL rejected: ${urlVerdict.reason}")
            is UrlValidator.Result.Valid -> Unit
        }
        if (ManifestValidator.validate(manifest) is ManifestValidator.Result.Invalid) {
            return InstallOutcome.Failure("Manifest failed validation before install")
        }
        val now = System.currentTimeMillis()
        val existing = dao.byId(manifest.id)
        val entity = ExtensionEntity(
            addonId = manifest.id,
            name = manifest.name.ifBlank { manifest.id },
            version = manifest.version,
            baseUrl = baseUrl,
            manifestJson = json.encodeToString(AddonManifest.serializer(), manifest),
            enabled = true,
            ecosystem = ecosystem,
            sortOrder = existing?.sortOrder ?: ((dao.maxSortOrder() ?: -1) + 1),
            installedAt = existing?.installedAt ?: now,
            updatedAt = now
        )
        dao.upsert(entity)
        return InstallOutcome.Success(entity.toInstalledExtension())
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

            is CheckResult.Ok -> installChecked(result.manifest, result.baseUrl, result.ecosystem)
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

    // -----------------------------------------------------------------
    // Ordering (affects request priority and Home rail order)
    // -----------------------------------------------------------------

    /** Moves an extension up in priority (lower sortOrder). */
    suspend fun moveUp(addonId: String) = reorder(addonId, -1)

    /** Moves an extension down in priority. */
    suspend fun moveDown(addonId: String) = reorder(addonId, +1)

    private suspend fun reorder(addonId: String, direction: Int) {
        val all = dao.observeAll().first()
        if (all.isEmpty()) return
        val index = all.indexOfFirst { it.addonId == addonId }
        if (index < 0) return
        val target = index + direction
        if (target < 0 || target >= all.size) return
        val a = all[index]
        val b = all[target]
        dao.setSortOrder(a.addonId, b.sortOrder)
        dao.setSortOrder(b.addonId, a.sortOrder)
    }

    // -----------------------------------------------------------------
    // Addon catalogs (addons that list other addons)
    // -----------------------------------------------------------------

    /** Catalog refs for browsing installable addons from installed addons. */
    fun addonCatalogRefs(extensions: List<InstalledExtension>): List<CatalogRef> {
        return extensions
            .filter { it.enabled && it.supportsAddonCatalog && it.addonCatalogs.isNotEmpty() }
            .flatMap { extension ->
                extension.addonCatalogs.map { catalog ->
                    CatalogRef(
                        addonId = extension.addonId,
                        addonName = extension.displayName,
                        baseUrl = extension.baseUrl,
                        type = catalog.type.ifBlank { "all" },
                        catalogId = catalog.id,
                        catalogName = catalog.displayName,
                        extraSupported = catalog.effectiveExtraSupported
                    )
                }
            }
    }

    /** An installable addon discovered inside an addon catalog. */
    data class CatalogEntry(
        val manifest: AddonManifest,
        val transportUrl: String
    )

    /**
     * Fetches the addons listed in an addon catalog. Two real-world
     * response shapes are accepted:
     *  - `{"metas":[{..., "transportUrl": ...}]}` — preview entries whose
     *    manifests must be fetched from their transport URL;
     *  - `{"addons":[{"transportUrl": ..., "manifest": {...}}]}` — the
     *    envelope Cinemeta (the official addon catalog) answers with,
     *    carrying each manifest inline.
     * Transport URLs are validated with the same install rules (plain
     * http only for local networks) before anything is fetched.
     */
    suspend fun fetchAddonCatalogEntries(
        baseUrl: String,
        type: String,
        catalogId: String
    ): List<CatalogEntry> {
        val body = try {
            api.fetchRaw(baseUrl, "addon_catalog/${type}/${catalogId}.json")
        } catch (_: Exception) {
            return emptyList()
        }

        // Inline-manifest entries (Cinemeta's "addons" envelope).
        val inline = try {
            json.decodeFromString(
                com.streambridge.app.addon.model.AddonCatalogResponse.serializer(),
                body
            ).addons
        } catch (_: Exception) {
            emptyList()
        }
        val inlineEntries = inline.mapNotNull { entry ->
            val base = transportBaseOrNull(entry.transportUrl) ?: return@mapNotNull null
            val manifest = entry.manifest ?: return@mapNotNull null
            if (ManifestValidator.validate(manifest) is ManifestValidator.Result.Invalid) {
                null
            } else {
                CatalogEntry(manifest, base)
            }
        }
        if (inlineEntries.isNotEmpty()) return inlineEntries

        // Preview entries: fetch each manifest from its transport URL.
        val metas = try {
            json.decodeFromString(
                com.streambridge.app.addon.model.CatalogResponse.serializer(),
                body
            ).metas
        } catch (_: Exception) {
            emptyList()
        }
        return metas.mapNotNull { preview ->
            val base = transportBaseOrNull(preview.transportUrl) ?: return@mapNotNull null
            val manifest = try {
                api.fetchManifest(base)
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            if (ManifestValidator.validate(manifest) is ManifestValidator.Result.Invalid) {
                null
            } else {
                CatalogEntry(manifest, base)
            }
        }
    }

    /**
     * Normalizes and validates a discovered transport URL against the
     * install rules; null when the URL is unusable (invalid, or plain
     * http on a public host).
     */
    private fun transportBaseOrNull(transportUrl: String): String? {
        if (transportUrl.isBlank()) return null
        val base = HttpAddonApi.normalizeBase(transportUrl)
        return when (val verdict = UrlValidator.validate(base)) {
            is UrlValidator.Result.Valid -> verdict.url
            is UrlValidator.Result.Invalid -> null
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
            addonCatalogs = manifest?.addonCatalogs ?: emptyList(),
            enabled = enabled,
            ecosystem = ecosystem,
            adultContent = manifest?.behaviorHints?.adult == true,
            configurable = manifest?.behaviorHints?.configurable == true,
            sortOrder = sortOrder,
            installedAt = installedAt,
            updatedAt = updatedAt
        )
    }
}
