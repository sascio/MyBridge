package com.nuvio.app.features.cloudstream

import platform.Foundation.NSUserDefaults

internal actual object CloudStreamStorage {
    private const val repositoriesKey = "cloudstream_repositories"
    private const val sourceStatesKey = "cloudstream_source_states"
    private const val configurationKey = "cloudstream_configuration"

    actual fun loadRepositories(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(repositoriesKey)

    actual fun saveRepositories(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = repositoriesKey)
    }

    actual fun loadSourceStates(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(sourceStatesKey)

    actual fun saveSourceStates(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = sourceStatesKey)
    }

    actual fun loadConfiguration(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(configurationKey)

    actual fun saveConfiguration(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = configurationKey)
    }
}
