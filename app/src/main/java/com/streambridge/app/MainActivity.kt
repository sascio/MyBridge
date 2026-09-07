package com.streambridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambridge.app.data.settings.SettingsState
import com.streambridge.app.di.AppContainer
import com.streambridge.app.ui.navigation.StreamBridgeRoot
import com.streambridge.app.ui.theme.StreamBridgeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as StreamBridgeApp).container

        setContent {
            val settings by container.settingsRepository.state
                .collectAsStateWithLifecycle(initialValue = SettingsState())

            StreamBridgeTheme(
                accentKey = settings.accent,
                pureBlack = settings.pureBlack
            ) {
                StreamBridgeRoot(container = container)
            }
        }
    }
}
