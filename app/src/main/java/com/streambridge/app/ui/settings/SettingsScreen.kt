package com.streambridge.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
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
import com.streambridge.app.ui.theme.PillShape
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

    /** Enabled addons, for the preferred-metadata picker. */
    val enabledAddons: StateFlow<List<com.streambridge.app.addon.InstalledExtension>> =
        extensionManager.enabledExtensions

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

    fun setMdblistKey(value: String) = viewModelScope.launch { settingsRepository.setMdblistApiKey(value) }

    fun setServerEnabled(value: Boolean) =
        viewModelScope.launch { settingsRepository.setServerEnabled(value) }

    fun setServerPort(value: Int) =
        viewModelScope.launch { settingsRepository.setServerPort(value) }

    fun setStartupTab(value: String) =
        viewModelScope.launch { settingsRepository.setStartupTab(value) }

    fun setPosterSize(value: String) =
        viewModelScope.launch { settingsRepository.setPosterSize(value) }

    fun setHomeShowContinueWatching(value: Boolean) =
        viewModelScope.launch { settingsRepository.setHomeShowContinueWatching(value) }

    fun setHomeShowRecommendations(value: Boolean) =
        viewModelScope.launch { settingsRepository.setHomeShowRecommendations(value) }

    fun setHomeShowRecentlyAdded(value: Boolean) =
        viewModelScope.launch { settingsRepository.setHomeShowRecentlyAdded(value) }

    fun setPreferredMetadataAddon(value: String) =
        viewModelScope.launch { settingsRepository.setPreferredMetadataAddon(value) }

    fun setPreferredSubtitleLanguage(value: String) =
        viewModelScope.launch { settingsRepository.setPreferredSubtitleLanguage(value) }

    fun setPreferredAudioLanguage(value: String) =
        viewModelScope.launch { settingsRepository.setPreferredAudioLanguage(value) }

    fun setSubtitleScale(value: Float) =
        viewModelScope.launch { settingsRepository.setSubtitleScale(value) }

    fun setDefaultPlaybackSpeed(value: Float) =
        viewModelScope.launch { settingsRepository.setDefaultPlaybackSpeed(value) }

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

/**
 * Settings hub: a root page linking to focused sub-pages, plus the
 * full-screen extension manager.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    page: String,
    onOpenPage: (String) -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenPlugins: () -> Unit,
    onBack: () -> Unit
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val settings by vm.settings.collectAsStateWithLifecycle()

    when (page) {
        "general" -> SettingsSubPage(title = "General", onBack = onBack) {
            GeneralContent(settings = settings, vm = vm)
        }

        "home" -> SettingsSubPage(title = "Home screen", onBack = onBack) {
            HomeContent(settings = settings, vm = vm)
        }

        "detail" -> SettingsSubPage(title = "Detail pages", onBack = onBack) {
            DetailPageContent(vm = vm)
        }

        "appearance" -> SettingsSubPage(title = "Appearance", onBack = onBack) {
            AppearanceContent(settings = settings, vm = vm)
        }

        "playback" -> SettingsSubPage(title = "Playback", onBack = onBack) {
            PlaybackContent(settings = settings, vm = vm)
        }

        "integrations" -> SettingsSubPage(title = "Integrations", onBack = onBack) {
            IntegrationsContent(settings = settings, vm = vm)
        }

        "network" -> SettingsSubPage(title = "Network & LAN bridge", onBack = onBack) {
            NetworkContent(vm = vm)
        }

        "about" -> SettingsSubPage(title = "About", onBack = onBack) {
            AboutContent()
        }

        else -> SettingsRootContent(
            vm = vm,
            onOpenPage = onOpenPage,
            onOpenExtensions = onOpenExtensions
        )
    }
}

// ---------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRootContent(
    vm: SettingsViewModel,
    onOpenPage: (String) -> Unit,
    onOpenExtensions: () -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val extensionCount by vm.extensionCount.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val accent = com.streambridge.app.ui.theme.LocalAccent.current
            // Brand banner
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(accent.gradient))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SettingsIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Stream Bridge",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Your media, your bridges",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SettingsLinkCard(
                icon = { Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "General",
                subtitle = "Startup tab",
                onClick = { onOpenPage("general") }
            )
            SettingsLinkCard(
                icon = { Icon(Icons.Filled.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "Home screen",
                subtitle = "Sections, recommendations, continue watching",
                onClick = { onOpenPage("home") }
            )
            SettingsLinkCard(
                icon = { Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "Detail pages",
                subtitle = "Metadata priority",
                onClick = { onOpenPage("detail") }
            )
            SettingsLinkCard(
                icon = { Icon(Icons.Filled.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "Appearance",
                subtitle = "Accent color, pure black mode",
                onClick = { onOpenPage("appearance") }
            )
            SettingsLinkCard(
                icon = { Icon(Icons.Filled.OndemandVideo, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "Playback",
                subtitle = "Autoplay, watched threshold",
                onClick = { onOpenPage("playback") }
            )
            SettingsLinkCard(
                icon = { Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "Extensions",
                subtitle = if (extensionCount == 0) {
                    "None installed yet"
                } else {
                    "$extensionCount installed"
                },
                onClick = onOpenExtensions
            )
            SettingsLinkCard(
                icon = { Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "Plugin repositories",
                subtitle = "Browse Cloudstream repositories (read-only)",
                onClick = onOpenPlugins
            )
            SettingsLinkCard(
                icon = { Icon(Icons.Filled.CloudQueue, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "Integrations",
                subtitle = "TMDB, MDBList (off by default)",
                onClick = { onOpenPage("integrations") }
            )
            SettingsLinkCard(
                icon = { Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "Network & LAN bridge",
                subtitle = "Share your addons over Wi-Fi",
                onClick = { onOpenPage("network") }
            )
            SettingsLinkCard(
                icon = { Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "About",
                subtitle = "Version, credits, licenses",
                onClick = { onOpenPage("about") }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------
// Sub-pages
// ---------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceContent(settings: SettingsState, vm: SettingsViewModel) {
    SettingsGroupCard(title = "Accent color") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AccentTheme.entries.forEach { accent ->
                val selected = settings.accent == accent.key
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(accent.gradient))
                            .clickable { vm.setAccent(accent.key) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = accent.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }

    SettingsGroupCard(title = "Poster size") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("small" to "Small", "medium" to "Medium", "large" to "Large").forEach { (key, label) ->
                FilterChip(
                    selected = settings.posterSize == key,
                    onClick = { vm.setPosterSize(key) },
                    label = { Text(text = label) }
                )
            }
        }
    }
    SettingsGroupCard(title = "Theme") {
        SwitchSettingRow(
            title = "Pure black (OLED)",
            subtitle = "True black background, saves power on OLED panels",
            checked = settings.pureBlack,
            onCheckedChange = vm::setPureBlack
        )
    }
}

@Composable
private fun GeneralContent(settings: SettingsState, vm: SettingsViewModel) {
    SettingsGroupCard(title = "Startup") {
        Text(
            text = "Which tab the app opens on",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("home" to "Home", "search" to "Search", "library" to "Library", "settings" to "Settings")
                .forEach { (key, label) ->
                    FilterChip(
                        selected = settings.startupTab == key,
                        onClick = { vm.setStartupTab(key) },
                        label = { Text(text = label) }
                    )
                }
        }
    }
    SettingsGroupCard(title = "Language") {
        Text(
            text = "Stream Bridge follows your Android system language. " +
                "Per-app language can be changed in Android Settings › Apps › Stream Bridge.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HomeContent(settings: SettingsState, vm: SettingsViewModel) {
    SettingsGroupCard(title = "Sections") {
        SwitchSettingRow(
            title = "Continue watching",
            subtitle = "Show titles you started on the Home screen",
            checked = settings.homeShowContinueWatching,
            onCheckedChange = vm::setHomeShowContinueWatching
        )
        SwitchSettingRow(
            title = "Recommended for you",
            subtitle = "Suggestions based on your watch history",
            checked = settings.homeShowRecommendations,
            onCheckedChange = vm::setHomeShowRecommendations
        )
        SwitchSettingRow(
            title = "Recently added",
            subtitle = "Titles you just favorited or watchlisted",
            checked = settings.homeShowRecentlyAdded,
            onCheckedChange = vm::setHomeShowRecentlyAdded
        )
    }
    SettingsGroupCard(title = "Catalog order") {
        Text(
            text = "Rail order follows addon priority. Reorder your addons on the " +
                "Extensions screen with the arrows — the top addon is asked first and " +
                "its catalogs appear first on Home.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DetailPageContent(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val addons by vm.enabledAddons.collectAsStateWithLifecycle()

    SettingsGroupCard(title = "Preferred metadata addon") {
        Text(
            text = "Metadata is merged from your addons automatically. Pick a specific " +
                "addon here to always prefer its details first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        FilterChip(
            selected = settings.preferredMetadataAddon.isBlank(),
            onClick = { vm.setPreferredMetadataAddon("") },
            label = { Text(text = "Automatic") }
        )
        addons.forEach { addon ->
            Spacer(modifier = Modifier.height(6.dp))
            FilterChip(
                selected = settings.preferredMetadataAddon == addon.addonId,
                onClick = { vm.setPreferredMetadataAddon(addon.addonId) },
                label = { Text(text = addon.displayName) }
            )
        }
        if (addons.isEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "No addons installed yet — metadata resolves automatically once you add some.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaybackContent(settings: SettingsState, vm: SettingsViewModel) {
    SettingsGroupCard(title = "Episodes") {
        SwitchSettingRow(
            title = "Autoplay next episode",
            subtitle = "Start the next episode when one ends",
            checked = settings.autoplayNext,
            onCheckedChange = vm::setAutoplayNext
        )
    }
    SettingsGroupCard(title = "Watched threshold") {
        Text(
            text = "Mark a title as watched at ${settings.watchedThresholdPercent}% progress",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = settings.watchedThresholdPercent.toFloat(),
            onValueChange = { vm.setWatchedThreshold(it.toInt()) },
            valueRange = 70f..99f,
            steps = 28
        )
    }
    SettingsGroupCard(title = "Default playback speed") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.75f, 1f, 1.25f, 1.5f).forEach { speed ->
                FilterChip(
                    selected = kotlin.math.abs(settings.defaultPlaybackSpeed - speed) < 0.01f,
                    onClick = { vm.setDefaultPlaybackSpeed(speed) },
                    label = {
                        Text(text = if (speed == 1f) "Normal" else String.format("%.2gx", speed))
                    }
                )
            }
        }
    }
    SettingsGroupCard(title = "Preferred languages") {
        OutlinedTextField(
            value = settings.preferredSubtitleLanguage,
            onValueChange = vm::setPreferredSubtitleLanguage,
            label = { Text("Preferred subtitle language") },
            placeholder = { Text("e.g. en or hi") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = settings.preferredAudioLanguage,
            onValueChange = vm::setPreferredAudioLanguage,
            label = { Text("Preferred audio language") },
            placeholder = { Text("e.g. en") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "When a stream or track list offers your language, it is picked automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    SettingsGroupCard(title = "Subtitle text size") {
        Text(
            text = "Scale of subtitles over the video",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = settings.subtitleScale,
            onValueChange = vm::setSubtitleScale,
            valueRange = 0.6f..1.8f
        )
    }
}

@Composable
private fun IntegrationsContent(settings: SettingsState, vm: SettingsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Text(
        text = "Both integrations are optional and OFF by default. Stream Bridge " +
            "works fully without them. Keys are stored only on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    SettingsGroupCard(title = "TMDB") {
        SwitchSettingRow(
            title = "Enable TMDB",
            subtitle = "Trending & popular rails, richer search",
            checked = settings.tmdbEnabled,
            onCheckedChange = vm::setTmdbEnabled
        )
        if (settings.tmdbEnabled) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = settings.tmdbApiKey,
                onValueChange = vm::setTmdbKey,
                label = { Text("TMDB API key") },
                placeholder = { Text("Paste your key") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            TextButtonRow(
                label = "Get a free key at themoviedb.org",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/settings/api"))
                        )
                    }
                }
            )
        }
    }

    SettingsGroupCard(title = "MDBList") {
        SwitchSettingRow(
            title = "Enable MDBList",
            subtitle = "Your MDBList lists as Home rails",
            checked = settings.mdblistEnabled,
            onCheckedChange = vm::setMdblistEnabled
        )
        if (settings.mdblistEnabled) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = settings.mdblistApiKey,
                onValueChange = vm::setMdblistKey,
                label = { Text("MDBList API key") },
                placeholder = { Text("Paste your key") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            TextButtonRow(
                label = "Get a free key at mdblist.com/api",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://mdblist.com/api/"))
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun NetworkContent(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val serverState by vm.serverState.collectAsStateWithLifecycle()
    val connected by vm.networkConnected.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current

    var portText by remember(settings.serverPort) {
        mutableStateOf(if (settings.serverPort == 0) "" else settings.serverPort.toString())
    }

    SettingsGroupCard(title = "LAN bridge server") {
        SwitchSettingRow(
            title = "Share over Wi-Fi",
            subtitle = "Expose your extensions as one Stremio-compatible addon",
            checked = settings.serverEnabled,
            onCheckedChange = vm::setServerEnabled
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = portText,
            onValueChange = { text ->
                portText = text.filter { it.isDigit() }.take(5)
                vm.setServerPort(portText.toIntOrNull() ?: 0)
            },
            label = { Text("Port (empty = automatic)") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        if (!connected) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You appear to be offline — the bridge will update when the network returns.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (serverState.running) {
        SettingsGroupCard(title = "Bridge running") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(com.streambridge.app.ui.theme.SbSuccess)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Available on your network",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            val addonUrl = serverState.addonUrl ?: ""
            if (addonUrl.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = PillShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = addonUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { clipboard.setText(AnnotatedString(addonUrl)) },
                        shape = PillShape
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy")
                    }
                    androidx.compose.material3.FilledTonalButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, addonUrl)
                                        },
                                        "Share bridge URL"
                                    )
                                )
                            }
                        },
                        shape = PillShape
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share")
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.QrCode2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        QrCode(content = addonUrl, size = 180.dp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Scan on another device to add this bridge",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    } else if (settings.serverEnabled) {
        SettingsGroupCard(title = "Starting…") {
            Text(
                text = serverState.lastError ?: "The bridge is starting…",
                style = MaterialTheme.typography.bodyMedium,
                color = if (serverState.lastError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun AboutContent() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    val accent = com.streambridge.app.ui.theme.LocalAccent.current
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(accent.gradient))
            ) {
                Icon(
                    imageVector = Icons.Filled.SettingsIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Stream Bridge",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Version $version",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    SettingsGroupCard(title = "How it works") {
        Text(
            text = "Stream Bridge ships completely empty: no catalogs, no metadata, " +
                "no streams and no providers are bundled. Everything you see comes " +
                "from Stremio-compatible extensions you install yourself, and the " +
                "app works offline with your local library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    SettingsGroupCard(title = "Credits") {
        Text(
            text = "Built with Jetpack Compose, Media3 / ExoPlayer, Room, DataStore, " +
                "OkHttp, kotlinx.serialization, Coil and ZXing. Stream Bridge " +
                "implements the open Stremio addon protocol and is an independent " +
                "project with original code, branding and artwork.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButtonRow(
            label = "View source on GitHub",
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sascio/MyBridge"))
                    )
                }
            }
        )
    }

    SettingsGroupCard(title = "Disclaimer") {
        Text(
            text = "Stream Bridge provides no content of its own and does not host, " +
                "bundle or recommend any content source. Use only content you are " +
                "legally entitled to access.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------

@Composable
private fun SettingsLinkCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TextButtonRow(label: String, onClick: () -> Unit) {
    Spacer(modifier = Modifier.height(6.dp))
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(text = label, color = MaterialTheme.colorScheme.primary)
    }
}
