package com.nuvio.app.features.cloudstream

import android.content.Context
import android.content.SharedPreferences

internal actual object CloudStreamStorage {
    private const val preferencesName = "nuvio_cloudstream"
    private const val repositoriesKey = "cloudstream_repositories"
    private const val sourceStatesKey = "cloudstream_source_states"
    private const val configurationKey = "cloudstream_configuration"
    private const val installedPluginsKey = "cloudstream_installed_plugins"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadRepositories(): String? = preferences?.getString(repositoriesKey, null)

    actual fun saveRepositories(payload: String) {
        preferences?.edit()?.putString(repositoriesKey, payload)?.apply()
    }

    actual fun loadSourceStates(): String? = preferences?.getString(sourceStatesKey, null)

    actual fun saveSourceStates(payload: String) {
        preferences?.edit()?.putString(sourceStatesKey, payload)?.apply()
    }

    actual fun loadConfiguration(): String? = preferences?.getString(configurationKey, null)

    actual fun saveConfiguration(payload: String) {
        preferences?.edit()?.putString(configurationKey, payload)?.apply()
    }

    actual fun loadInstalledPlugins(): String? = preferences?.getString(installedPluginsKey, null)

    actual fun saveInstalledPlugins(payload: String) {
        preferences?.edit()?.putString(installedPluginsKey, payload)?.apply()
    }
}
