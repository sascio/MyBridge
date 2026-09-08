package com.streambridge.app.addon.plugin

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.pluginDataStore by preferencesDataStore(name = "stream_bridge_plugins")

/**
 * One installed Nuvio plugin repository: its manifest URL, the last
 * fetched provider list, and the user's enable/disable choices.
 */
@Serializable
data class NuvioInstalledRepository(
    val manifestUrl: String,
    /** Repository display name (host of the manifest, or manifest "name"). */
    val name: String,
    /** Providers discovered during the last successful refresh. */
    val providers: List<StoredProvider>,
    /** Provider ids the user has enabled (user choice wins over manifest). */
    val enabledProviderIds: List<String>,
    val addedAt: Long,
    val lastUpdated: Long,
    /** Last refresh error, if any; cleared on the next successful refresh. */
    val lastError: String = ""
) {
    @Serializable
    data class StoredProvider(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val author: String,
        val supportedTypes: List<String>,
        val filename: String,
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

    val enabledProviders: List<StoredProvider>
        get() = providers.filter { it.id in enabledProviderIds }

    fun isEnabled(providerId: String): Boolean = providerId in enabledProviderIds
}

/**
 * Persists installed Nuvio plugin repositories and their provider
 * enable/disable state in DataStore. Uses its own store file so the
 * existing settings/library data is never touched (no migration risk).
 */
class NuvioPluginStore(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val REPOSITORIES = stringPreferencesKey("installed_repositories")
    }

    private val _repositories = MutableStateFlow<List<NuvioInstalledRepository>>(emptyList())
    val repositories: StateFlow<List<NuvioInstalledRepository>> = _repositories.asStateFlow()

    init {
        // Mirror the persisted state into a hot StateFlow for the UI.
        scope.launch {
            context.pluginDataStore.data
                .map { prefs -> decode(prefs[Keys.REPOSITORIES]) }
                .collect { _repositories.value = it }
        }
    }

    private fun decode(raw: String?): List<NuvioInstalledRepository> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(NuvioInstalledRepository.serializer()), raw)
        } catch (_: Exception) {
            // Corrupt state must never crash the app; start fresh.
            emptyList()
        }
    }

    private suspend fun update(transform: (List<NuvioInstalledRepository>) -> List<NuvioInstalledRepository>) {
        context.pluginDataStore.edit { prefs ->
            val next = transform(decode(prefs[Keys.REPOSITORIES]))
            prefs[Keys.REPOSITORIES] =
                json.encodeToString(ListSerializer(NuvioInstalledRepository.serializer()), next)
        }
    }

    suspend fun upsertRepository(repository: NuvioInstalledRepository) {
        update { list ->
            list.filterNot { it.manifestUrl == repository.manifestUrl } + repository
        }
    }

    suspend fun removeRepository(manifestUrl: String) {
        update { list -> list.filterNot { it.manifestUrl == manifestUrl } }
    }

    suspend fun setRecordedError(manifestUrl: String, error: String) {
        update { list ->
            list.map { if (it.manifestUrl == manifestUrl) it.copy(lastError = error) else it }
        }
    }

    suspend fun setProviderEnabled(manifestUrl: String, providerId: String, enabled: Boolean) {
        update { list ->
            list.map { repo ->
                if (repo.manifestUrl != manifestUrl) {
                    repo
                } else {
                    val next = if (enabled) {
                        (repo.enabledProviderIds + providerId).distinct()
                    } else {
                        repo.enabledProviderIds - providerId
                    }
                    repo.copy(enabledProviderIds = next)
                }
            }
        }
    }

    /** Waits until the initial state has been loaded from disk. */
    suspend fun awaitLoaded() {
        _repositories.first()
    }

    companion object {
        fun repositoryNameFor(manifestUrl: String): String =
            manifestUrl
                .removePrefix("https://").removePrefix("http://")
                .substringBefore('/')
                .ifBlank { manifestUrl.take(40) }
    }
}
