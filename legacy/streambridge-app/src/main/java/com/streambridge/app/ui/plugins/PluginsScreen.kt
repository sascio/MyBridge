package com.streambridge.app.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streambridge.app.addon.UrlValidator
import com.streambridge.app.addon.adapter.CloudstreamAdapter
import com.streambridge.app.addon.model.PluginListing
import com.streambridge.app.addon.plugin.NuvioInstalledRepository
import com.streambridge.app.addon.plugin.NuvioPluginManager
import com.streambridge.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * View state for the Nuvio plugin manager. Every asynchronous operation
 * is reflected here so the UI never shows a blank or indefinite state.
 */
data class PluginsUiState(
    val repositories: List<NuvioInstalledRepository> = emptyList(),
    val busy: Boolean = false,
    /** Human-readable failure of the last add/refresh attempt, if any. */
    val operationError: String = "",
    /** A Cloudstream repository being browsed (read-only listing). */
    val cloudstream: CloudstreamBrowse = CloudstreamBrowse.Hidden
) {
    val hasContent: Boolean get() = repositories.isNotEmpty()
}

/**
 * Browsing a Cloudstream repository (repo.json / plugins.json). The
 * plugins it lists are compiled Cloudstream code and can never be
 * executed here — this is a read-only information view.
 */
sealed interface CloudstreamBrowse {
    data object Hidden : CloudstreamBrowse
    data class Loading(val url: String) : CloudstreamBrowse
    data class Failed(val reason: String) : CloudstreamBrowse
    data class Ready(val repository: CloudstreamAdapter.CloudstreamRepository) : CloudstreamBrowse
}

class PluginsViewModel(
    private val manager: NuvioPluginManager,
    private val cloudstreamAdapter: CloudstreamAdapter = CloudstreamAdapter.instance
) : ViewModel() {

    private val _state = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            manager.repositories.collect { repositories ->
                _state.value = _state.value.copy(repositories = repositories)
            }
        }
    }

    fun addRepository(url: String) {
        if (url.isBlank()) return
        // Cloudstream repo.json / plugins.json URLs open the read-only
        // repository browser instead of the Nuvio installer.
        if (cloudstreamAdapter.acceptsUrl(url)) {
            browseCloudstream(url)
            return
        }
        _state.value = _state.value.copy(busy = true, operationError = "")
        viewModelScope.launch {
            val result = manager.addRepository(url)
            _state.value = _state.value.copy(
                busy = false,
                operationError = result.exceptionOrNull()?.message ?: ""
            )
        }
    }

    /** Loads a Cloudstream repository listing (never installed/executed). */
    fun browseCloudstream(url: String) {
        _state.value = _state.value.copy(
            busy = true,
            operationError = "",
            cloudstream = CloudstreamBrowse.Loading(url)
        )
        viewModelScope.launch {
            when (val verdict = UrlValidator.validate(url)) {
                is UrlValidator.Result.Invalid -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        cloudstream = CloudstreamBrowse.Failed(verdict.reason)
                    )
                }

                is UrlValidator.Result.Valid -> {
                    try {
                        val repository = cloudstreamAdapter.loadRepository(verdict.url)
                        _state.value = _state.value.copy(
                            busy = false,
                            cloudstream = CloudstreamBrowse.Ready(repository)
                        )
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            busy = false,
                            cloudstream = CloudstreamBrowse.Failed(e.message ?: "Could not load the repository")
                        )
                    }
                }
            }
        }
    }

    fun closeCloudstream() {
        _state.value = _state.value.copy(cloudstream = CloudstreamBrowse.Hidden)
    }

    fun refreshRepository(manifestUrl: String) {
        _state.value = _state.value.copy(busy = true, operationError = "")
        viewModelScope.launch {
            val result = manager.refreshRepository(manifestUrl)
            _state.value = _state.value.copy(
                busy = false,
                operationError = result.exceptionOrNull()?.message ?: ""
            )
        }
    }

    fun removeRepository(manifestUrl: String) {
        viewModelScope.launch { manager.removeRepository(manifestUrl) }
    }

    fun setProviderEnabled(manifestUrl: String, providerId: String, enabled: Boolean) {
        viewModelScope.launch { manager.setProviderEnabled(manifestUrl, providerId, enabled) }
    }

    fun dismissError() {
        _state.value = _state.value.copy(operationError = "")
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                PluginsViewModel(
                    manager = container.pluginManager,
                    cloudstreamAdapter = CloudstreamAdapter.instance
                )
            }
        }
    }
}

/**
 * Nuvio-compatible plugin management. Plugins execute locally in a
 * sandboxed JavaScript runtime — this screen installs repositories,
 * discovers their providers and lets the user enable them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val vm: PluginsViewModel = viewModel(factory = PluginsViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(text = "Plugins", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add plugin repository"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        when {
            state.busy -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            !state.hasContent -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(52.dp)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "No plugins installed",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Add a Nuvio-compatible plugin repository to get started. " +
                        "Plugins run locally in a sandboxed JavaScript runtime — nothing is " +
                        "bundled or preinstalled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    onClick = { showAddDialog = true },
                    shape = RoundedCornerShape(percent = 50),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        text = "Add repository",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (state.operationError.isNotBlank()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Something went wrong",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = state.operationError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
                items(state.repositories, key = { it.manifestUrl }) { repo ->
                    RepositoryCard(
                        repository = repo,
                        onRefresh = { vm.refreshRepository(repo.manifestUrl) },
                        onRemove = { vm.removeRepository(repo.manifestUrl) },
                        onToggleProvider = { providerId, enabled ->
                            vm.setProviderEnabled(repo.manifestUrl, providerId, enabled)
                        }
                    )
                }
                item {
                    Text(
                        text = "Plugins are untrusted code. They run in a sandboxed runtime " +
                            "with no file or Android access, network limits and strict " +
                            "timeouts; a failing plugin never affects the rest of the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(text = "Add plugin repository") },
            text = {
                Column {
                    Text(
                        text = "Paste a Nuvio-compatible repository manifest URL. " +
                            "A Cloudstream repo.json / plugins.json URL opens a read-only listing.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = { Text(text = "https://example.com/manifest.json") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.operationError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.operationError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.addRepository(url)
                        showAddDialog = false
                    },
                    enabled = url.isNotBlank()
                ) { Text(text = "Install") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text(text = "Cancel") }
            }
        )
    }

    when (val browse = state.cloudstream) {
        is CloudstreamBrowse.Ready -> {
            AlertDialog(
                onDismissRequest = vm::closeCloudstream,
                title = { Text(text = "Cloudstream repository") },
                text = {
                    Column {
                        Text(
                            text = browse.repository.name.ifBlank { "Repository" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (browse.repository.description.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = browse.repository.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${browse.repository.plugins.size} plugins — read-only listing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Cloudstream plugins are compiled code and cannot run in Stream Bridge. They are listed for information only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(browse.repository.plugins) { plugin ->
                                CloudstreamPluginRow(plugin = plugin)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = vm::closeCloudstream) { Text(text = "Close") }
                }
            )
        }

        is CloudstreamBrowse.Failed -> {
            AlertDialog(
                onDismissRequest = vm::closeCloudstream,
                title = { Text(text = "Cloudstream repository") },
                text = {
                    Text(
                        text = browse.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                confirmButton = {
                    TextButton(onClick = vm::closeCloudstream) { Text(text = "Close") }
                }
            )
        }

        CloudstreamBrowse.Hidden, is CloudstreamBrowse.Loading -> Unit
    }
}

@Composable
private fun CloudstreamPluginRow(plugin: PluginListing) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = plugin.name.ifBlank { plugin.internalName },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val status = when {
                plugin.isOperational -> "operational"
                plugin.status == 0 -> "offline"
                else -> "status ${plugin.status}"
            }
            Text(
                text = listOfNotNull(
                    "v${plugin.version}".takeIf { plugin.version > 0 },
                    plugin.language.takeIf { it.isNotBlank() },
                    status
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (plugin.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RepositoryCard(
    repository: NuvioInstalledRepository,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onToggleProvider: (String, Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = repository.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${repository.providers.size} providers · enabled ${repository.enabledProviderIds.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh repository",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Remove repository",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (repository.lastError.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Last refresh failed: ${repository.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (repository.providers.isEmpty()) {
                Text(
                    text = "This repository exposes no providers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                repository.providers.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = provider.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val meta = listOfNotNull(
                                provider.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                                provider.author.takeIf { it.isNotBlank() },
                                provider.supportedTypes.joinToString("/").takeIf { it.isNotBlank() },
                                provider.contentLanguage.joinToString(", ")
                                    .takeIf { it.isNotBlank() }?.uppercase()
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (provider.description.isNotBlank()) {
                                Text(
                                    text = provider.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Switch(
                            checked = repository.isEnabled(provider.id),
                            onCheckedChange = { enabled -> onToggleProvider(provider.id, enabled) }
                        )
                    }
                }
            }
        }
    }
}
