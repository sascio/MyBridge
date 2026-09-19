package com.nuvio.app.features.cloudstream

/**
 * Persistence for CloudStream user state.
 *
 * Follows the same `expect object` + platform `SharedPreferences`/`NSUserDefaults`
 * pattern already used by StreamBridge settings storages (e.g.
 * `OmdbSettingsStorage`), rather than introducing a new preferences framework.
 *
 * Only small JSON documents are stored:
 *  - the list of repository URLs the user added
 *  - per-source enabled/disabled state
 *  - supported configuration values
 *  - metadata for extensions whose package is installed
 *
 * The installed-plugin cache matters for correctness, not just speed: provider
 * discovery must work at app start and offline. Without it the extension list
 * exists only after a network refresh has completed, so an installed, enabled
 * provider is invisible to source aggregation on a cold start.
 */
internal expect object CloudStreamStorage {
    fun loadRepositories(): String?
    fun saveRepositories(payload: String)

    fun loadSourceStates(): String?
    fun saveSourceStates(payload: String)

    fun loadConfiguration(): String?
    fun saveConfiguration(payload: String)

    /** Metadata for extensions with an installed package, as a JSON array. */
    fun loadInstalledPlugins(): String?
    fun saveInstalledPlugins(payload: String)
}
