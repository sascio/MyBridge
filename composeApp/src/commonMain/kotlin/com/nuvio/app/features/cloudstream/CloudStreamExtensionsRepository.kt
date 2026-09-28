package com.nuvio.app.features.cloudstream

import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * State holder for the CloudStream Extensions screen.
 *
 * Responsibilities:
 *  - own the list of user-added repositories and persist it
 *  - discover extensions through [CloudStreamRepositoryLoader] (existing HTTP stack)
 *  - derive the live Overview counters
 *  - persist per-source enable/disable and configuration
 *
 * Honesty rules enforced here:
 *  - a source can only be enabled when it is genuinely activatable
 *  - `.cs3` plugins needing DEX execution can never become installed/enabled
 */
internal object CloudStreamExtensionsRepository {

    private val log = Logger.withTag("CloudStreamExtensions")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val loader = CloudStreamRepositoryLoader()

    private val _uiState = MutableStateFlow(CloudStreamUiState())
    val uiState: StateFlow<CloudStreamUiState> = _uiState.asStateFlow()

    private var repositoryUrls: List<String> = emptyList()
    private var sourceStates: MutableMap<String, CloudStreamSourceState> = mutableMapOf()
    private var configuration: MutableMap<String, String> = mutableMapOf()
    private var hasInitialized = false

    /** Loads persisted state and performs the first discovery pass. */
    fun initialize() {
        if (hasInitialized) return
        hasInitialized = true

        repositoryUrls = decodeList(CloudStreamStorage.loadRepositories())
        sourceStates = decodeSourceStates(CloudStreamStorage.loadSourceStates())
        configuration = decodeConfiguration(CloudStreamStorage.loadConfiguration())

        // Restore installed extensions synchronously, BEFORE any network work.
        //
        // This is what makes an installed provider usable at app start and
        // offline. `refresh()` below is asynchronous, so callers that read
        // `uiState.value` immediately -- notably StreamsRepository when the
        // user presses Play -- would otherwise see an empty extension list and
        // silently drop every CloudStream provider from source aggregation.
        restoreInstalledExtensions()

        if (repositoryUrls.isEmpty()) {
            _uiState.value = _uiState.value.copy(hasLoadedOnce = true)
            return
        }
        refresh()
    }

    /** Re-fetches every repository. Coalesced: a refresh in flight is not duplicated. */
    fun refresh() {
        if (_uiState.value.isLoading || _uiState.value.isRefreshing) return
        val hasLoaded = _uiState.value.hasLoadedOnce
        _uiState.value = _uiState.value.copy(
            isLoading = !hasLoaded,
            isRefreshing = hasLoaded,
            errorMessage = null,
        )
        scope.launch { reload() }
    }

    suspend fun addRepository(rawUrl: String): AddCloudStreamRepositoryResult {
        val url = rawUrl.trim()
        if (url.isEmpty()) {
            return AddCloudStreamRepositoryResult.Error("Enter a repository URL.")
        }
        if (repositoryUrls.any { it.equals(url, ignoreCase = true) }) {
            return AddCloudStreamRepositoryResult.Error("That repository has already been added.")
        }

        val repository = loader.load(url)
        if (repository.compatibility == CloudStreamCompatibility.FAILED) {
            return AddCloudStreamRepositoryResult.Error(
                repository.errorMessage ?: "The repository could not be read.",
            )
        }

        mutex.withLock {
            repositoryUrls = repositoryUrls + url
            persistRepositories()
        }
        reload()
        return AddCloudStreamRepositoryResult.Success(repository)
    }

    fun removeRepository(url: String) {
        scope.launch {
            mutex.withLock {
                repositoryUrls = repositoryUrls.filterNot { it.equals(url, ignoreCase = true) }
                persistRepositories()
            }
            reload()
        }
    }

    // --- installation -------------------------------------------------------

    /** Extensions whose install is currently in flight, keyed by plugin id. */
    private val inFlightInstalls = mutableSetOf<String>()

    /**
     * Downloads, verifies and installs an extension's `.cs3` package.
     *
     * This is the step that makes a discovered extension genuinely usable: the
     * runtime can only load providers from a package that is present in
     * app-private storage. Progress is reflected through distinct
     * Downloading / Installing / Installed states, and a failure never
     * presents as installed or enabled.
     *
     * Concurrent requests for the same extension are coalesced.
     */
    fun installExtension(pluginId: String) {
        scope.launch { installExtensionNow(pluginId) }
    }

    /** Suspending form of [installExtension], used by tests and by updates. */
    suspend fun installExtensionNow(pluginId: String): CloudStreamInstallResult {
        val extension = _uiState.value.extensions.firstOrNull { it.id == pluginId }
            ?: return CloudStreamInstallResult.Failure(
                pluginId,
                CloudStreamInstallError.REPOSITORY_GONE,
                "This extension is no longer offered by any configured repository.",
            )

        if (!CloudStreamPackageInstaller.supportsInstallation) {
            val failure = CloudStreamInstallResult.Failure(
                pluginId,
                CloudStreamInstallError.UNSUPPORTED,
                "This build cannot run CloudStream extensions, so they cannot be installed.",
            )
            updateInstallStatus(pluginId) {
                CloudStreamInstallStatus(
                    state = CloudStreamInstallState.FAILED,
                    error = failure.error,
                    errorMessage = failure.message,
                )
            }
            return failure
        }

        mutex.withLock {
            if (!inFlightInstalls.add(pluginId)) {
                return CloudStreamInstallResult.Failure(
                    pluginId,
                    CloudStreamInstallError.UNKNOWN,
                    "An install for this extension is already running.",
                )
            }
        }

        try {
            updateInstallStatus(pluginId) { current ->
                current.copy(
                    state = CloudStreamInstallState.DOWNLOADING,
                    error = null,
                    errorMessage = null,
                )
            }

            // Release any previously loaded copy first. Providers register into
            // process-global CloudStream registries, so an update that leaves
            // the old registration behind makes the new load look like it
            // registered nothing and permanently breaks the extension until the
            // app is restarted.
            CloudStreamPlatformRuntime.unload(pluginId)

            val result = CloudStreamPackageInstaller.install(extension.plugin)

            when (result) {
                is CloudStreamInstallResult.Success -> {
                    CloudStreamDiagnostics.info(
                        CloudStreamDiagnosticStage.INSTALLATION,
                        extension.name,
                        "Package installed and verified (version ${result.version ?: "unknown"}).",
                    )
                    updateInstallStatus(pluginId) {
                        CloudStreamInstallStatus(
                            state = CloudStreamInstallState.INSTALLED,
                            installedVersion = CloudStreamPackageInstaller
                                .installedVersion(extension.plugin) ?: result.version,
                        )
                    }
                    // Installing is an explicit opt-in, so the extension's
                    // sources are switched on unless the user previously chose
                    // otherwise. Without this a freshly installed provider is
                    // installed-but-disabled and never reaches Play -> Sources,
                    // which reads to the user as the install having done nothing.
                    enableSourcesByDefault(pluginId)
                    // Cache immediately so the provider survives a restart and
                    // is usable offline without waiting for a repository refresh.
                    persistInstalledPlugins()
                    log.i { "CloudStream extension '$pluginId' installed" }
                }

                is CloudStreamInstallResult.Failure -> {
                    CloudStreamDiagnostics.error(
                        CloudStreamDiagnosticStage.INSTALLATION,
                        extension.name,
                        result.message,
                    )
                    // A failed *update* must not erase a working installation.
                    val stillInstalled = CloudStreamPackageInstaller.isInstalled(extension.plugin)
                    updateInstallStatus(pluginId) { current ->
                        current.copy(
                            state = if (stillInstalled) {
                                CloudStreamInstallState.INSTALLED
                            } else {
                                CloudStreamInstallState.FAILED
                            },
                            installedVersion = CloudStreamPackageInstaller
                                .installedVersion(extension.plugin),
                            error = result.error,
                            errorMessage = result.message,
                        )
                    }
                    _uiState.value = _uiState.value.copy(errorMessage = result.message)
                }
            }
            return result
        } finally {
            mutex.withLock { inFlightInstalls.remove(pluginId) }
        }
    }

    /**
     * Switches on every activatable source of a freshly installed extension.
     *
     * Only applied to sources the user has never expressed a preference for, so
     * a deliberate disable is never silently undone by a reinstall or update.
     */
    private fun enableSourcesByDefault(pluginId: String) {
        val extension = _uiState.value.extensions.firstOrNull { it.id == pluginId } ?: return
        var changed = false
        extension.sources.forEach { source ->
            if (!source.canActivate) return@forEach
            if (sourceStates.containsKey(source.id)) return@forEach
            sourceStates[source.id] = CloudStreamSourceState(
                sourceId = source.id,
                enabled = true,
                installed = true,
            )
            changed = true
        }
        if (changed) {
            persistSourceStates()
            applyPersistedState()
        }
    }

    /** Re-runs installation to pick up a newer published version. */
    fun updateExtension(pluginId: String) = installExtension(pluginId)

    /**
     * Removes an installed package and clears the enabled state of its sources.
     *
     * Disabling on removal matters: leaving sources enabled would let the
     * aggregator target providers whose code is no longer present.
     */
    fun removeExtension(pluginId: String) {
        scope.launch {
            val extension = _uiState.value.extensions.firstOrNull { it.id == pluginId } ?: return@launch
            // Stop executing it before its package leaves the disk.
            CloudStreamPlatformRuntime.unload(pluginId)
            CloudStreamPackageInstaller.remove(extension.plugin)

            extension.sources.forEach { source ->
                sourceStates[source.id] = (sourceStates[source.id] ?: CloudStreamSourceState(source.id))
                    .copy(enabled = false, installed = false)
            }
            persistSourceStates()

            updateInstallStatus(pluginId) { CloudStreamInstallStatus() }
            applyPersistedState()
            // Drop it from the cache too, so a restart cannot resurrect it.
            persistInstalledPlugins()
        }
    }

    /** Applies a status change to one extension without disturbing the others. */
    private fun updateInstallStatus(
        pluginId: String,
        transform: (CloudStreamInstallStatus) -> CloudStreamInstallStatus,
    ) {
        _uiState.value = _uiState.value.copy(
            extensions = _uiState.value.extensions.map { extension ->
                if (extension.id == pluginId) {
                    extension.copy(installStatus = transform(extension.installStatus))
                } else {
                    extension
                }
            },
        )
    }

    /** Reads real on-disk installation facts for every discovered extension. */
    private fun installStatusFor(plugin: CloudStreamPlugin): CloudStreamInstallStatus {
        if (!CloudStreamPackageInstaller.supportsInstallation) {
            return CloudStreamInstallStatus()
        }
        val installed = CloudStreamPackageInstaller.isInstalled(plugin)
        if (!installed) return CloudStreamInstallStatus()

        val installedVersion = CloudStreamPackageInstaller.installedVersion(plugin)
        val enabled = sourceStates.values.any { state ->
            state.enabled && state.sourceId.startsWith("${plugin.id}::")
        } || sourceStates[plugin.id]?.enabled == true

        return CloudStreamInstallStatus(
            state = CloudStreamInstallPolicy.resolveState(
                isInstalledOnDisk = true,
                isEnabled = enabled,
                installedVersion = installedVersion,
                repositoryVersion = plugin.version,
                failure = null,
            ),
            installedVersion = installedVersion,
        )
    }

    /**
     * Enables or disables one source.
     *
     * Refuses to enable a source that cannot genuinely be activated, so the UI
     * can never show a non-functional provider as active.
     */
    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        val source = findSource(sourceId) ?: return
        if (enabled && !source.canActivate) {
            log.w { "Refusing to enable non-activatable CloudStream source '$sourceId'" }
            return
        }
        // Enabling requires a package that is genuinely installed; otherwise the
        // aggregator would target a provider whose code is not on disk.
        if (enabled && !isExtensionInstalled(sourceId)) {
            log.w { "Refusing to enable CloudStream source '$sourceId': package is not installed" }
            _uiState.value = _uiState.value.copy(
                errorMessage = "Install this extension before enabling its sources.",
            )
            return
        }
        val existing = sourceStates[sourceId] ?: CloudStreamSourceState(sourceId)
        sourceStates[sourceId] = existing.copy(enabled = enabled, installed = existing.installed || enabled)
        persistSourceStates()
        applyPersistedState()
    }

    /** Stores a supported configuration value. */
    fun setConfigurationValue(sourceId: String, key: String, value: String) {
        configuration["$sourceId::$key"] = value
        persistConfiguration()
        applyPersistedState()
    }

    fun configurationValue(sourceId: String, key: String): String =
        configuration["$sourceId::$key"].orEmpty()

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    // --- aggregator integration --------------------------------------------

    /**
     * Execution backend used to resolve real streams.
     *
     * Resolved from [CloudStreamPlatformRuntime], which only provides one on a
     * distribution permitted to execute CloudStream extensions. Everywhere else
     * it stays null and every provider resolves to "no streams" rather than
     * inventing results.
     */
    private var executorOverride: CloudStreamPluginExecutor? = null

    /** Injection point for tests. Production uses the platform runtime. */
    fun registerExecutor(backend: CloudStreamPluginExecutor?) {
        executorOverride = backend
    }

    private fun activeExecutor(): CloudStreamPluginExecutor? =
        executorOverride ?: CloudStreamPlatformRuntime.executor()

    /**
     * Resolves real streams for one CloudStream provider.
     *
     * Called by `StreamsRepository` from inside the existing aggregation scope,
     * so cancellation and concurrency are inherited. Failures are returned as a
     * failed [Result] rather than thrown, keeping one broken provider isolated
     * from the rest of the aggregation.
     *
     * No fabricated streams: without an execution backend, or when the provider
     * legitimately has nothing, the result is an empty list.
     */
    suspend fun resolveStreams(
        target: CloudStreamAggregatorBridge.Target,
        mediaType: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        title: String? = null,
        year: Int? = null,
    ): Result<List<StreamItem>> {
        // These three guards are defensive: CloudStreamAggregatorBridge has
        // already applied them when it built the targets, so reaching one means
        // state drifted mid-request. Reporting that is honest; returning an
        // empty success would present a broken provider as "this title has no
        // sources here", which is a different and misleading statement.
        val backend = activeExecutor() ?: run {
            CloudStreamDiagnostics.error(
                CloudStreamDiagnosticStage.PLAYBACK,
                target.addonName,
                "No CloudStream execution backend is available.",
            )
            return Result.failure(IllegalStateException("This build cannot execute CloudStream extensions."))
        }
        val extension = _uiState.value.extensions.firstOrNull { it.id == target.extensionId }
            ?: return Result.failure(
                IllegalStateException(
                    "Extension '${target.extensionId}' is no longer available.",
                ),
            )

        // Re-check executability at request time: persisted state must never be
        // able to activate a plugin the runtime cannot actually run.
        if (!extension.plugin.isExecutable) {
            return Result.failure(
                IllegalStateException(
                    "Extension '${extension.name}' cannot be executed by this build.",
                ),
            )
        }

        // The package must actually be on disk: the runtime loads providers from
        // the installed `.cs3`, so targeting an uninstalled extension could only
        // ever fail.
        if (!extension.installStatus.isInstalled) {
            return Result.failure(
                IllegalStateException("Extension '${extension.name}' is not installed."),
            )
        }

        return runCatching {
            val request = CloudStreamResolveRequest(
                plugin = extension.plugin,
                mediaType = mediaType,
                videoId = videoId,
                title = title,
                year = year,
                season = season,
                episode = episode,
            )
            val resolved = backend.resolve(request)
            // Preserve the selected MainAPI identity even when CloudStream's
            // ExtractorLink omitted its source label. The extension/addon is
            // still the source-picker group; this name is the provider within
            // that group and is useful for diagnostics and link attribution.
            val attributedLinks = resolved.links.map { link ->
                if (link.source.isNullOrBlank() && !resolved.providerName.isNullOrBlank()) {
                    link.copy(source = resolved.providerName)
                } else {
                    link
                }
            }
            CloudStreamProviderAdapter.adaptLinks(
                links = attributedLinks,
                pluginName = target.addonName,
                pluginId = extension.plugin.internalNameOrId(),
                subtitles = resolved.subtitles,
                pluginLogo = target.iconUrl,
                addonId = target.addonId,
                metadata = resolved.metadata,
            )
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            CloudStreamDiagnostics.error(
                CloudStreamDiagnosticStage.NORMALIZATION,
                target.addonName,
                error,
            )
            log.w(error) {
                "CloudStream provider '${target.addonId}' failed: " +
                    "${error::class.simpleName}: ${error.message}"
            }
        }
    }

    /**
     * Loads all enabled CloudStream homepage catalog entries.
     *
     * The result is deliberately a list of normalized catalog items, not raw
     * AAR objects. A provider may publish several homepage sections and several
     * providers may publish the same title; the provider/addon identity remains
     * attached to every item so the generic Home and Live TV projections keep
     * them separate.
     */
    suspend fun loadCatalog(): List<CloudStreamCatalogItem> {
        initialize()
        val backend = activeExecutor() ?: run {
            CloudStreamDiagnostics.error(
                CloudStreamDiagnosticStage.CLASSLOADER,
                provider = "runtime",
                message = "No CloudStream execution backend is available in this build.",
            )
            return emptyList()
        }
        val targets = CloudStreamAggregatorBridge
            // Homepage catalogs are a capability of the loaded MainAPI, not a
            // manifest content-type declaration. Some valid live providers
            // publish only Movie/TvSeries in their repository metadata while
            // returning Live items from getMainPage().
            .resolveTargets(_uiState.value.extensions, "catalog")
        if (targets.isEmpty() && _uiState.value.extensions.any { it.isActive }) {
            CloudStreamDiagnostics.error(
                CloudStreamDiagnosticStage.UI,
                provider = "catalog targets",
                message = "Installed and enabled extensions produced no executable catalog targets.",
            )
        }

        // One extension can declare several tvTypes. Execute its homepage once
        // and project the returned items onto only the compatible source types;
        // invoking getMainPage once per tvType duplicated every section and made
        // one MainAPI appear to overwrite another provider's catalog.
        val rawItems = targets.groupBy { it.extensionId }.flatMap { (extensionId, extensionTargets) ->
            val extension = _uiState.value.extensions.firstOrNull { it.id == extensionId }
                ?: return@flatMap emptyList()
            runCatching { backend.loadCatalog(extension.plugin) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    CloudStreamDiagnostics.error(
                        CloudStreamDiagnosticStage.GET_MAIN_PAGE,
                        extension.name,
                        error,
                    )
                    log.w(error) { "CloudStream catalog failed for ${extension.name}" }
                }
                .getOrDefault(emptyList())
                .flatMap { item ->
                    val matchingTargets = extensionTargets.filter { target ->
                        catalogItemMatchesSource(item, target.contentType)
                    }
                    matchingTargets.map { target ->
                        item.copy(
                            extensionId = extension.id,
                            sourceId = target.sourceId,
                            addonId = target.addonId,
                            providerName = item.providerName
                                .takeIf { it.isNotBlank() }
                                ?: target.addonName,
                        )
                    }
                }
        }
        val normalized = rawItems
            .filter { it.title.isNotBlank() && it.url.isNotBlank() }
            // Keep the provider section/source identity in the key. The same
            // title may intentionally appear in two homepage rails, and
            // flattening those rails loses the hierarchy the provider exposed.
            .distinctBy { listOf(it.catalogSourceKey(), it.url, it.title) }
        if (normalized.size != rawItems.size) {
            CloudStreamDiagnostics.warning(
                CloudStreamDiagnosticStage.NORMALIZATION,
                provider = "catalog",
                message = "Dropped ${rawItems.size - normalized.size} catalog item(s) with missing identity or duplicate provider keys.",
            )
        }
        return normalized
    }

    /** Resolves a normalized homepage catalog item through its owning provider. */
    suspend fun resolveCatalog(item: CloudStreamCatalogItem): Result<CloudStreamLiveResolution> {
        initialize()
        val backend = activeExecutor() ?: return Result.failure(
            IllegalStateException("This build cannot execute CloudStream extensions."),
        )
        val extension = _uiState.value.extensions.firstOrNull { it.id == item.extensionId }
            ?: return Result.failure(IllegalStateException("CloudStream extension is no longer available."))
        if (!extension.plugin.isExecutable || !extension.installStatus.isInstalled) {
            return Result.failure(IllegalStateException("CloudStream extension is not installed."))
        }
        return runCatching { backend.resolveCatalog(extension.plugin, item) }
            .onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                CloudStreamDiagnostics.error(
                    CloudStreamDiagnosticStage.LOAD_LINKS,
                    item.providerName,
                    error,
                )
                log.w(error) { "CloudStream catalog item failed for ${item.addonId}" }
            }
    }

    /** Resolves a catalog item directly for the existing movie/series player source picker. */
    suspend fun resolveCatalogItem(item: CloudStreamCatalogItem): Result<List<StreamItem>> {
        initialize()
        val backend = activeExecutor() ?: return Result.failure(
            IllegalStateException("This build cannot execute CloudStream extensions."),
        )
        val extension = _uiState.value.extensions.firstOrNull { it.id == item.extensionId }
            ?: return Result.failure(IllegalStateException("CloudStream extension is no longer available."))
        if (!extension.plugin.isExecutable || !extension.installStatus.isInstalled) {
            return Result.failure(IllegalStateException("CloudStream extension is not installed."))
        }
        return runCatching {
            val resolved = backend.resolveCatalog(extension.plugin, item)
            CloudStreamProviderAdapter.adaptLinks(
                links = resolved.links,
                pluginName = item.providerName,
                pluginId = extension.plugin.internalNameOrId(),
                subtitles = resolved.subtitles,
                addonId = item.addonId,
                metadata = resolved.metadata,
            )
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            CloudStreamDiagnostics.error(
                CloudStreamDiagnosticStage.LOAD_LINKS,
                item.providerName,
                error,
            )
            log.w(error) { "CloudStream catalog item failed for ${item.addonId}" }
        }
    }

    /** Compatibility entry point for Live TV resolution. */
    suspend fun resolveLive(item: CloudStreamLiveCatalogItem): Result<CloudStreamLiveResolution> =
        resolveCatalog(item)

    /** --- live projection ------------------------------------------------- */

    suspend fun loadLiveCatalog(): List<CloudStreamLiveCatalogItem> {
        val catalog = loadCatalog()
        val live = catalog.filter { item ->
            item.metadata?.isLive == true || item.mediaType.equals("Live", ignoreCase = true)
        }
        if (catalog.isNotEmpty() && live.isEmpty()) {
            CloudStreamDiagnostics.warning(
                CloudStreamDiagnosticStage.NORMALIZATION,
                provider = "live projection",
                message = "Catalog returned ${catalog.size} item(s), but none normalized as LiveStreamLoadResponse/live.",
            )
        }
        return live
    }

    // --- internals ---------------------------------------------------------

    /**
     * Matches a real LoadResponse/SearchResponse type to a declared source.
     * Unknown/blank provider types remain visible under every declared source:
     * dropping them would be worse than a duplicate because CloudStream
     * repositories frequently omit tvTypes on older providers.
     */
    private fun catalogItemMatchesSource(item: CloudStreamCatalogItem, sourceType: String?): Boolean {
        val actual = (item.metadata?.mediaType ?: item.mediaType)?.trim().orEmpty()
        val declared = sourceType?.trim().orEmpty()
        if (declared.isBlank() || actual.isBlank()) return true
        // Homepage capability is intentionally broader than manifest tvTypes:
        // a LiveStreamLoadResponse is frequently emitted by providers whose
        // repository entry only declares Movie/TvSeries. Do not make that
        // provider invisible merely because the metadata was incomplete.
        if (actual.equals("Live", ignoreCase = true)) return true
        return actual.equals(declared, ignoreCase = true)
    }

    private suspend fun reload() {
        val urls = repositoryUrls
        val repositories = urls.map { url ->
            // Isolated: one bad repository cannot break discovery for the rest.
            runCatching { loader.load(url) }.getOrElse { error ->
                CloudStreamDiagnostics.error(
                    CloudStreamDiagnosticStage.DISCOVERY,
                    provider = "repository",
                    message = "Repository discovery failed: ${error.cloudStreamDiagnosticMessage()}",
                )
                log.w { "CloudStream repository discovery failed: ${error.message}" }
                CloudStreamRepository(
                    url = url,
                    name = url,
                    compatibility = CloudStreamCompatibility.FAILED,
                    compatibilityReason = CloudStreamCompatibilityReason.MALFORMED_OR_UNREACHABLE,
                    errorMessage = "The repository could not be read.",
                )
            }
        }

        CloudStreamDiagnostics.info(
            CloudStreamDiagnosticStage.DISCOVERY,
            provider = "repository",
            message = "Discovered ${repositories.sumOf { it.plugins.size }} extension manifest(s) from ${repositories.size} repository(ies).",
        )

        // Preserve in-flight install status across a refresh so a download
        // running while the user pulls to refresh is not reported as Available.
        val previousStatuses = _uiState.value.extensions.associate { it.id to it.installStatus }

        val discoveredExtensions = repositories.flatMap { repository ->
            repository.plugins.map { plugin ->
                val mapped = CloudStreamExtensionMapping.toExtension(
                    plugin = plugin,
                    repositoryUrl = repository.url,
                    states = sourceStates,
                    configuration = configuration,
                )
                val previous = previousStatuses[plugin.id]
                mapped.copy(
                    installStatus = if (previous?.isBusy == true) {
                        previous
                    } else {
                        installStatusFor(plugin)
                    },
                )
            }
        }

        // A transient repository outage must not turn a verified, installed
        // extension into "No Active Addons" or erase its restart cache. The
        // package and cached manifest are the authoritative offline discovery
        // path until a repository refresh succeeds again.
        // Collapse mirror entries before applying install/source state. The
        // aggregator also deduplicates defensively, but doing it here keeps the
        // UI, on-disk package identity and provider target selection aligned on
        // the same highest-version extension.
        val deduplicatedDiscovered = CloudStreamProviderIdentity.deduplicate(discoveredExtensions)
        val discoveredIds = deduplicatedDiscovered.mapTo(mutableSetOf(), CloudStreamExtension::id)
        val discoveredKeys = deduplicatedDiscovered
            .mapTo(mutableSetOf()) { CloudStreamProviderIdentity.extensionKey(it.plugin) }
        val cachedInstalled = _uiState.value.extensions.filter { extension ->
            extension.installStatus.isInstalled &&
                extension.id !in discoveredIds &&
                CloudStreamProviderIdentity.extensionKey(extension.plugin) !in discoveredKeys
        }
        if (cachedInstalled.isNotEmpty()) {
            CloudStreamDiagnostics.warning(
                CloudStreamDiagnosticStage.PERSISTENCE,
                provider = "installed extensions",
                message = "Repository refresh did not list ${cachedInstalled.size} installed extension(s); retained cached metadata and on-disk packages.",
            )
        }
        val extensions = (deduplicatedDiscovered + cachedInstalled)
            .distinctBy { CloudStreamProviderIdentity.extensionKey(it.plugin) }
            .sortedBy { it.name.lowercase() }

        _uiState.value = CloudStreamUiState(
            isLoading = false,
            isRefreshing = false,
            hasLoadedOnce = true,
            repositories = repositories,
            extensions = extensions,
            errorMessage = null,
        )
        // Compatibility with installs created before source preferences were
        // persisted: a package that is already on disk is enabled by default,
        // while an explicit disabled preference remains untouched.
        extensions.filter { it.installStatus.isInstalled }
            .forEach { extension -> enableSourcesByDefault(extension.id) }
        persistInstalledPlugins()
    }

    /**
     * Rebuilds installed extensions from the on-disk cache.
     *
     * Only extensions whose package is genuinely present are restored: the
     * cache records metadata, while [CloudStreamPackageInstaller] remains the
     * single source of truth for whether something is installed. A cache entry
     * for a package the user removed is therefore ignored, not trusted.
     */
    private fun restoreInstalledExtensions() {
        val cached = decodeInstalledPlugins(CloudStreamStorage.loadInstalledPlugins())
        if (cached.isEmpty()) return

        val extensions = cached.mapNotNull { entry ->
            val plugin = CloudStreamRepositoryParser.toPlugin(entry.manifest)
            if (plugin.id.isEmpty()) return@mapNotNull null
            if (!CloudStreamPackageInstaller.isInstalled(plugin)) return@mapNotNull null

            CloudStreamExtensionMapping.toExtension(
                plugin = plugin,
                repositoryUrl = entry.repositoryUrl,
                states = sourceStates,
                configuration = configuration,
            ).copy(installStatus = installStatusFor(plugin))
        }.sortedBy { it.name.lowercase() }

        if (extensions.isEmpty()) {
            CloudStreamDiagnostics.warning(
                CloudStreamDiagnosticStage.PERSISTENCE,
                provider = "installed extensions",
                message = "Persisted extension metadata was present, but no matching verified package was found on disk.",
            )
            return
        }
        CloudStreamDiagnostics.info(
            CloudStreamDiagnosticStage.PERSISTENCE,
            provider = "installed extensions",
            message = "Restored ${extensions.size} installed extension(s) from private storage.",
        )
        log.i { "Restored ${extensions.size} installed CloudStream extension(s) from cache" }
        _uiState.value = _uiState.value.copy(extensions = extensions)
        // Older installs may predate persisted per-source activation state.
        // Treat an installed source with no recorded preference like a fresh
        // install, while preserving an explicit disabled preference.
        extensions.forEach { extension ->
            enableSourcesByDefault(extension.id)
        }
    }

    /** Caches metadata for every installed extension so it survives restarts. */
    private fun persistInstalledPlugins() {
        val installed = _uiState.value.extensions
            .filter { it.installStatus.isInstalled }
            .map { extension ->
                CloudStreamInstalledPlugin(
                    repositoryUrl = extension.repositoryUrl,
                    manifest = extension.plugin.toManifest(),
                )
            }
        runCatching {
            CloudStreamStorage.saveInstalledPlugins(
                json.encodeToString(
                    ListSerializer(CloudStreamInstalledPlugin.serializer()),
                    installed,
                ),
            )
        }.onFailure { error ->
            log.w { "Could not cache installed CloudStream extensions: ${error.message}" }
        }
    }

    private fun decodeInstalledPlugins(payload: String?): List<CloudStreamInstalledPlugin> {
        if (payload.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(
                ListSerializer(CloudStreamInstalledPlugin.serializer()),
                payload,
            )
        }.getOrElse { emptyList() }
    }

    private fun applyPersistedState() {
        val refreshed = _uiState.value.extensions.map { extension ->
            // A source can only report installed/enabled when the extension's
            // package is genuinely on disk, so stale persisted flags from a
            // removed or failed install can never resurrect a dead provider.
            val packageInstalled = extension.installStatus.isInstalled
            extension.copy(
                sources = extension.sources.map { source ->
                    val persisted = sourceStates[source.id]
                    val usable = source.canActivate && packageInstalled
                    source.copy(
                        installed = usable && persisted?.installed == true,
                        enabled = usable && persisted?.enabled == true,
                    )
                },
            )
        }
        _uiState.value = _uiState.value.copy(extensions = refreshed)
    }

    /** True when the extension owning [sourceId] has a package on disk. */
    private fun isExtensionInstalled(sourceId: String): Boolean =
        _uiState.value.extensions
            .firstOrNull { extension -> extension.sources.any { it.id == sourceId } }
            ?.installStatus
            ?.isInstalled == true

    private fun findSource(sourceId: String): CloudStreamSource? =
        _uiState.value.extensions.firstNotNullOfOrNull { extension ->
            extension.sources.firstOrNull { it.id == sourceId }
        }

    private fun persistRepositories() {
        runCatching {
            CloudStreamStorage.saveRepositories(
                json.encodeToString(ListSerializer(String.serializer()), repositoryUrls),
            )
        }
    }

    private fun persistSourceStates() {
        runCatching {
            CloudStreamStorage.saveSourceStates(
                json.encodeToString(
                    ListSerializer(CloudStreamSourceState.serializer()),
                    sourceStates.values.toList(),
                ),
            )
        }
    }

    private fun persistConfiguration() {
        runCatching {
            CloudStreamStorage.saveConfiguration(
                json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    configuration,
                ),
            )
        }
    }

    private fun decodeList(payload: String?): List<String> {
        if (payload.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), payload)
        }.getOrElse { emptyList() }
    }

    private fun decodeSourceStates(payload: String?): MutableMap<String, CloudStreamSourceState> {
        if (payload.isNullOrBlank()) return mutableMapOf()
        return runCatching {
            json.decodeFromString(ListSerializer(CloudStreamSourceState.serializer()), payload)
                .associateBy { it.sourceId }
                .toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun decodeConfiguration(payload: String?): MutableMap<String, String> {
        if (payload.isNullOrBlank()) return mutableMapOf()
        return runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                payload,
            ).toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    /** Test seam: restores the object to a pristine state. */
    internal fun resetForTesting() {
        hasInitialized = false
        repositoryUrls = emptyList()
        sourceStates = mutableMapOf()
        configuration = mutableMapOf()
        inFlightInstalls.clear()
        _uiState.value = CloudStreamUiState()
    }
}
