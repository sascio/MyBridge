package com.nuvio.app.features.cloudstream

import co.touchlab.kermit.Logger
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

        val extensions = repositories.flatMap { repository ->
            repository.plugins.map { plugin ->
                CloudStreamExtensionMapping.toExtension(
                    plugin = plugin,
                    repositoryUrl = repository.url,
                    states = sourceStates,
                    configuration = configuration,
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
            extension.copy(
                sources = extension.sources.map { source ->
                    val persisted = sourceStates[source.id]
                    val canActivate = source.canActivate
                    source.copy(
                        installed = canActivate && persisted?.installed == true,
                        enabled = canActivate && persisted?.enabled == true,
                    )
                },
            )
        }
        _uiState.value = _uiState.value.copy(extensions = refreshed)
    }

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
        _uiState.value = CloudStreamUiState()
    }
}
