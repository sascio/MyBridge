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

            val result = CloudStreamPackageInstaller.install(extension.plugin)

            when (result) {
                is CloudStreamInstallResult.Success -> {
                    updateInstallStatus(pluginId) {
                        CloudStreamInstallStatus(
                            state = CloudStreamInstallState.INSTALLED,
                            installedVersion = CloudStreamPackageInstaller
                                .installedVersion(extension.plugin) ?: result.version,
                        )
                    }
                    log.i { "CloudStream extension '$pluginId' installed" }
                }

                is CloudStreamInstallResult.Failure -> {
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
            CloudStreamPackageInstaller.remove(extension.plugin)

            extension.sources.forEach { source ->
                sourceStates[source.id] = (sourceStates[source.id] ?: CloudStreamSourceState(source.id))
                    .copy(enabled = false, installed = false)
            }
            persistSourceStates()

            updateInstallStatus(pluginId) { CloudStreamInstallStatus() }
            applyPersistedState()
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
        val backend = activeExecutor() ?: return Result.success(emptyList())
        val extension = _uiState.value.extensions.firstOrNull { it.id == target.extensionId }
            ?: return Result.success(emptyList())

        // Re-check executability at request time: persisted state must never be
        // able to activate a plugin the runtime cannot actually run.
        if (!extension.plugin.isExecutable) return Result.success(emptyList())

        // The package must actually be on disk: the runtime loads providers from
        // the installed `.cs3`, so targeting an uninstalled extension could only
        // ever fail.
        if (!extension.installStatus.isInstalled) return Result.success(emptyList())

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
            CloudStreamProviderAdapter.adaptLinks(
                links = resolved.links,
                pluginName = target.addonName,
                pluginId = extension.plugin.internalNameOrId(),
                subtitles = resolved.subtitles,
                pluginLogo = target.iconUrl,
                addonId = target.addonId,
            )
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            log.w { "CloudStream provider '${target.addonId}' failed: ${error.message}" }
        }
    }

    // --- internals ---------------------------------------------------------

    private suspend fun reload() {
        val urls = repositoryUrls
        val repositories = urls.map { url ->
            // Isolated: one bad repository cannot break discovery for the rest.
            runCatching { loader.load(url) }.getOrElse { error ->
                log.w { "CloudStream repository '$url' failed: ${error.message}" }
                CloudStreamRepository(
                    url = url,
                    name = url,
                    compatibility = CloudStreamCompatibility.FAILED,
                    compatibilityReason = CloudStreamCompatibilityReason.MALFORMED_OR_UNREACHABLE,
                    errorMessage = "The repository could not be read.",
                )
            }
        }

        // Preserve in-flight install status across a refresh so a download
        // running while the user pulls to refresh is not reported as Available.
        val previousStatuses = _uiState.value.extensions.associate { it.id to it.installStatus }

        val extensions = repositories.flatMap { repository ->
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
        }.sortedBy { it.name.lowercase() }

        _uiState.value = CloudStreamUiState(
            isLoading = false,
            isRefreshing = false,
            hasLoadedOnce = true,
            repositories = repositories,
            extensions = extensions,
            errorMessage = null,
        )
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
