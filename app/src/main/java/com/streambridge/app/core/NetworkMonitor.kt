package com.streambridge.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes device connectivity. Used by the LAN bridge server UI and
 * by screens that need to warn the user when the network goes away.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private val _connected = MutableStateFlow(isCurrentlyConnected())
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _connected.value = true
        }

        override fun onLost(network: Network) {
            _connected.value = isCurrentlyConnected()
        }
    }

    init {
        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // Connectivity service unavailable; keep last known state.
        }
    }

    fun isCurrentlyConnected(): Boolean {
        return try {
            connectivityManager?.activeNetwork != null
        } catch (_: Exception) {
            false
        }
    }
}
