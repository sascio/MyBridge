package com.streambridge.app.server

import com.streambridge.app.core.NetworkMonitor
import com.streambridge.app.data.settings.SettingsRepository
import com.streambridge.app.data.settings.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the lifecycle of the LAN bridge server: starts/stops it based on
 * the persisted setting and keeps the advertised address fresh when the
 * network changes.
 */
class ServerManager(
    settingsRepository: SettingsRepository,
    networkMonitor: NetworkMonitor,
    provider: BridgeContentProvider,
    scope: CoroutineScope
) {

    private val settingsState = settingsRepository.state
        .stateIn(scope, SharingStarted.Eagerly, SettingsState())

    val server = BridgeServer(
        portProvider = { settingsState.value.serverPort },
        providerFactory = { provider }
    )

    val state = server.state

    init {
        scope.launch {
            combine(
                settingsState.map { it.serverEnabled },
                settingsState.map { it.serverPort }
            ) { enabled, port -> enabled to port }
                .distinctUntilChanged()
                .collect { (enabled, _) ->
                    if (enabled) server.start() else server.stop()
                }
        }
        scope.launch {
            networkMonitor.connected.collect {
                server.refreshAddress()
            }
        }
    }

    val isRunning: Boolean get() = server.state.value.running
}
