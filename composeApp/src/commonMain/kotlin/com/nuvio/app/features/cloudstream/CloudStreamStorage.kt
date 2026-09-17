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
 */
internal expect object CloudStreamStorage {
    fun loadRepositories(): String?
    fun saveRepositories(payload: String)

    fun loadSourceStates(): String?
    fun saveSourceStates(payload: String)

    fun loadConfiguration(): String?
    fun saveConfiguration(payload: String)
}
