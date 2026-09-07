package com.streambridge.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.core.NetworkMonitor
import com.streambridge.app.data.settings.SettingsRepository
import com.streambridge.app.data.settings.SettingsState
import com.streambridge.app.di.AppContainer
import com.streambridge.app.server.BridgeServerState
import com.streambridge.app.server.ServerManager
import com.streambridge.app.ui.components.QrCode
import com.streambridge.app.ui.theme.AccentTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val serverManager: ServerManager,
    networkMonitor: NetworkMonitor,
    extensionManager: ExtensionManager
) : ViewModel() {

    val settings: StateFlow<SettingsState> = settingsRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsState())

    val serverState: StateFlow<BridgeServerState> = serverManager.state

    val networkConnected: StateFlow<Boolean> = networkMonitor.connected

    val extensionCount: StateFlow<Int> = extensionManager.extensions
        .map { list -> list.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setAccent(key: String) = viewModelScope.launch { settingsRepository.setAccent(key) }
    fun setPureBlack(value: Boolean) = viewModelScope.launch { settingsRepository.setPureBlack(value) }
    fun setAutoplayNext(value: Boolean) =
        viewModelScope.launch { settingsRepository.setAutoplayNext(value) }

    fun setWatchedThreshold(value: Int) =
        viewModelScope.launch { settingsRepository.setWatchedThresholdPercent(value) }

    fun setTmdbEnabled(value: Boolean) =
        viewModelScope.launch { settingsRepository.setTmdbEnabled(value) }

    fun setTmdbKey(value: String) = viewModelScope.launch { settingsRepository.setTmdbApiKey(value) }

    fun setMdblistEnabled(value: Boolean) =
        viewModelScope.launch { settingsRepository.setMdblistEnabled(value) }

    fun setMdblistKey(value: String) =
        viewModelScope.launch { settingsRepository.setMdblistApiKey(value) }

    fun setServerEnabled(value: Boolean) =
        viewModelScope.launch { settingsRepository.setServerEnabled(value) }

    fun setServerPort(value: Int) =
        viewModelScope.launch { settingsRepository.setServerPort(value) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    serverManager = container.serverManager,
                    networkMonitor = container.networkMonitor,
                    extensionManager = container.extensionManager
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenExtensions: () -> Unit
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val settings by vm.settings.collectAsStateWithLifecycle()
    val serverState by vm.serverState.collectAsStateWithLifecycle()
    val connected by vm.networkConnected.collectAsStateWithLifecycle()
    val extensionCount by vm.extensionCount.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(text = "Settings", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsCard(title = "Appearance") {
                Text(
                    text = "Accent color",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccentTheme.entries.forEach { accent ->
                        val selected = settings.accent == accent.key
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(accent.primary, CircleShape)
                                .clickable { vm.setAccent(accent.key) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(MaterialTheme.colorScheme.background, CircleShape)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                ToggleRow(
                    title = "Pure black background",
                    subtitle = "OLED-friendly true black surfaces",
                    checked = settings.pureBlack,
                    onChecked = vm::setPureBlack
                )
            }

            SettingsCard(title = "Playback") {
                ToggleRow(
                    title = "Autoplay next episode",
                    subtitle = "Start the next episode when one finishes",
                    checked = settings.autoplayNext,
                    onChecked = vm::setAutoplayNext
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Mark as watched at ${settings.watchedThresholdPercent}%",
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    value = settings.watchedThresholdPercent.toFloat(),
                    onValueChange = { vm.setWatchedThreshold(it.toInt()) },
                    valueRange = 50f..99f
                )
                Text(
                    text = "Playback progress above this percentage hides the item from Continue Watching.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsCard(title = "Extensions") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$extensionCount installed",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Manage the addons that power Stream Bridge",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onOpenExtensions) { Text(text = "Open") }
                }
            }

            SettingsCard(title = "Integrations") {
                Text(
                    text = "Optional. Disabled by default. Keys are stored only on this device " +
                        "and are never bundled with the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                IntegrationSection(
                    name = "TMDB",
                    description = "Trending & popular rails, search and metadata",
                    enabled = settings.tmdbEnabled,
                    keyValue = settings.tmdbApiKey,
                    enabledValid = settings.tmdbApiKey.isNotBlank(),
                    signupUrl = "https://www.themoviedb.org/settings/api",
                    onEnabled = vm::setTmdbEnabled,
                    onKey = vm::setTmdbKey
                )
                Spacer(modifier = Modifier.height(16.dp))
                IntegrationSection(
                    name = "MDBList",
                    description = "Your MDBList lists appear as Home rails",
                    enabled = settings.mdblistEnabled,
                    keyValue = settings.mdblistApiKey,
                    enabledValid = settings.mdblistApiKey.isNotBlank(),
                    signupUrl = "https://mdblist.com/preferences/",
                    onEnabled = vm::setMdblistEnabled,
                    onKey = vm::setMdblistKey
                )
            }

            SettingsCard(title = "Network & LAN bridge") {
                Text(
                    text = "Expose your installed extensions as one Stremio-compatible addon " +
                        "on your local network. Other devices (TV, desktop) can add it by URL or QR code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                ToggleRow(
                    title = "LAN bridge server",
                    subtitle = if (connected) "Wi-Fi / network connected" else "No network connection",
                    checked = settings.serverEnabled,
                    onChecked = vm::setServerEnabled
                )
                Spacer(modifier = Modifier.height(12.dp))
                var portText by remember(settings.serverPort) {
                    mutableStateOf(if (settings.serverPort == 0) "" else settings.serverPort.toString())
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = { value ->
                        portText = value.filter { it.isDigit() }.take(5)
                    },
                    label = { Text(text = "Port (empty = automatic)") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = {
                            val port = portText.toIntOrNull() ?: 0
                            vm.setServerPort(port)
                        }) { Text(text = "Apply") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
                if (settings.serverEnabled) {
                    if (serverState.running && serverState.addonUrl != null) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Bridge is live",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                QrCode(content = serverState.addonUrl ?: "")
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = serverState.addonUrl ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                ServerUrlActions(url = serverState.addonUrl ?: "")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "${serverState.requestCount} requests served · port ${serverState.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = serverState.lastError ?: "Starting bridge…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            SettingsCard(title = "About") {
                Text(
                    text = "Stream Bridge 1.0.0",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "A cinematic, extension-driven media client. Stream Bridge provides no " +
                        "content of its own — everything comes from extensions you choose to install.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Credits",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Built with Jetpack Compose, Media3 / ExoPlayer, Room, DataStore, " +
                        "OkHttp, kotlinx.serialization, Coil and ZXing. Compatible with the " +
                        "open Stremio addon protocol. All Stream Bridge code, branding and " +
                        "artwork are original.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Use only content you are legally entitled to access. " +
                        "Stream Bridge does not bundle, host or recommend any content source.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun IntegrationSection(
    name: String,
    description: String,
    enabled: Boolean,
    keyValue: String,
    enabledValid: Boolean,
    signupUrl: String,
    onEnabled: (Boolean) -> Unit,
    onKey: (String) -> Unit
) {
    val context = LocalContext.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))
            var key by remember(keyValue) { mutableStateOf(keyValue) }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(text = "$name API key") },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { onKey(key) }) { Text(text = "Save") }
                },
                supportingText = {
                    Text(
                        text = if (enabledValid && keyValue.isNotBlank()) {
                            "Key saved on this device"
                        } else {
                            "Enter your own API key to activate"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(signupUrl)))
                } catch (_: Exception) {
                }
            }) {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Get an API key")
            }
        }
    }
}

@Composable
private fun ServerUrlActions(url: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.clickable {
                clipboard.setText(AnnotatedString(url))
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Copy URL", style = MaterialTheme.typography.labelLarge)
            }
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.clickable {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Add my Stream Bridge addon: $url"
                    )
                }
                try {
                    context.startActivity(Intent.createChooser(send, "Share bridge URL"))
                } catch (_: Exception) {
                }
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Share", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
