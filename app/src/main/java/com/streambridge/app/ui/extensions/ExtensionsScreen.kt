package com.streambridge.app.ui.extensions

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.streambridge.app.R
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.InstalledExtension
import com.streambridge.app.addon.InstallOutcome
import com.streambridge.app.addon.model.AddonManifest
import com.streambridge.app.di.AppContainer
import com.streambridge.app.ui.components.EmptyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Dialog state machine for adding an extension. */
sealed interface AddDialogState {
    data object Idle : AddDialogState
    data object Checking : AddDialogState
    data class Confirm(val manifest: AddonManifest, val baseUrl: String) : AddDialogState
    data object Installing : AddDialogState
    data class Done(val name: String) : AddDialogState
    data class Failed(val message: String) : AddDialogState
}

class ExtensionsViewModel(
    private val extensionManager: ExtensionManager
) : ViewModel() {

    val extensions = extensionManager.extensions
    val busy = extensionManager.busy

    private val _addState = MutableStateFlow<AddDialogState>(AddDialogState.Idle)
    val addState: StateFlow<AddDialogState> = _addState.asStateFlow()

    private val _banner = MutableStateFlow<String?>(null)
    val banner: StateFlow<String?> = _banner.asStateFlow()

    fun checkUrl(rawUrl: String) {
        if (rawUrl.isBlank()) {
            _addState.value = AddDialogState.Failed("Enter a URL first")
            return
        }
        _addState.value = AddDialogState.Checking
        viewModelScope.launch {
            when (val result = extensionManager.check(rawUrl)) {
                is ExtensionManager.CheckResult.InvalidUrl ->
                    _addState.value = AddDialogState.Failed(result.reason)

                is ExtensionManager.CheckResult.Unreachable ->
                    _addState.value = AddDialogState.Failed(result.reason)

                is ExtensionManager.CheckResult.InvalidManifest ->
                    _addState.value = AddDialogState.Failed(
                        "Manifest validation failed: ${result.issues.joinToString("; ")}"
                    )

                is ExtensionManager.CheckResult.Ok ->
                    _addState.value = AddDialogState.Confirm(result.manifest, result.baseUrl)
            }
        }
    }

    fun installConfirmed(manifest: AddonManifest, baseUrl: String) {
        _addState.value = AddDialogState.Installing
        viewModelScope.launch {
            val installed = extensionManager.installChecked(manifest, baseUrl)
            _addState.value = AddDialogState.Done(installed.displayName)
        }
    }

    fun dismissDialog() {
        _addState.value = AddDialogState.Idle
    }

    fun resetAddState() {
        _addState.value = AddDialogState.Idle
    }

    fun setEnabled(extension: InstalledExtension, enabled: Boolean) {
        viewModelScope.launch {
            extensionManager.setEnabled(extension.addonId, enabled)
        }
    }

    fun remove(extension: InstalledExtension) {
        viewModelScope.launch {
            extensionManager.remove(extension.addonId)
            _banner.value = "Removed ${extension.displayName}"
        }
    }

    fun refresh(extension: InstalledExtension) {
        viewModelScope.launch {
            when (val outcome = extensionManager.refresh(extension.addonId)) {
                is InstallOutcome.Success ->
                    _banner.value = "Updated ${outcome.extension.displayName} to v${outcome.extension.version}"

                is InstallOutcome.Failure ->
                    _banner.value = outcome.reason
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            val outcomes = extensionManager.refreshAll()
            val failures = outcomes.count { it is InstallOutcome.Failure }
            _banner.value = when {
                outcomes.isEmpty() -> "Nothing to refresh"
                failures == 0 -> "All extensions refreshed"
                else -> "$failures of ${outcomes.size} extensions failed to refresh"
            }
        }
    }

    fun consumeBanner() {
        _banner.value = null
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                ExtensionsViewModel(container.extensionManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val vm: ExtensionsViewModel = viewModel(factory = ExtensionsViewModel.factory(container))
    val extensions by vm.extensions.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val addState by vm.addState.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(text = "Extensions", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (extensions.isNotEmpty()) {
                        IconButton(onClick = vm::refreshAll) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh all"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true; vm.resetAddState() },
                icon = { Icon(imageVector = Icons.Filled.Add, contentDescription = null) },
                text = { Text(text = "Add extension", fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color(0xFF0B0A10)
            )
        },
        snackbarHost = {
            if (banner != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = banner ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = vm::consumeBanner) { Text(text = "OK") }
                    }
                }
            }
        }
    ) { padding ->
        if (extensions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyState(
                        iconRes = R.drawable.ic_empty_extensions,
                        title = "No extensions installed",
                        body = "Stream Bridge ships empty on purpose. Add any Stremio-compatible " +
                            "addon by its manifest URL to bring in catalogs, metadata and streams.",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "An extension URL looks like https://example.com/manifest.json",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(extensions, key = { it.addonId }) { extension ->
                    ExtensionCard(
                        extension = extension,
                        refreshing = busy.contains(extension.addonId),
                        onToggle = { enabled -> vm.setEnabled(extension, enabled) },
                        onRemove = { vm.remove(extension) },
                        onRefresh = { vm.refresh(extension) }
                    )
                }
                item {
                    Text(
                        text = "Extensions are Stremio-compatible addons. Stream Bridge never " +
                            "installs or activates anything without you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddExtensionDialog(
            state = addState,
            onDismiss = { showAddDialog = false; vm.dismissDialog() },
            onCheck = vm::checkUrl,
            onInstall = vm::installConfirmed,
            onReset = vm::resetAddState
        )
    }
}

@Composable
private fun ExtensionCard(
    extension: InstalledExtension,
    refreshing: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (!extension.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = extension.logo,
                            contentDescription = extension.displayName,
                            modifier = Modifier.size(46.dp)
                        )
                    } else {
                        Text(
                            text = extension.displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = extension.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "v${extension.version} · ${extension.catalogs.size} catalog(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Switch(checked = extension.enabled, onCheckedChange = onToggle)
            }
            if (extension.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = extension.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "resources: " + extension.resources.joinToString(", ").ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (extension.types.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "types: " + extension.types.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Refresh")
                }
                TextButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AddExtensionDialog(
    state: AddDialogState,
    onDismiss: () -> Unit,
    onCheck: (String) -> Unit,
    onInstall: (AddonManifest, String) -> Unit,
    onReset: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (state) {
                    is AddDialogState.Confirm -> "Install this extension?"
                    else -> "Add extension"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            when (state) {
                AddDialogState.Idle, is AddDialogState.Failed -> {
                    Column {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text(text = "Extension / manifest URL") },
                            placeholder = { Text(text = "https://example.com/manifest.json") },
                            singleLine = true,
                            isError = state is AddDialogState.Failed,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = {
                            clipboard.getText()?.text?.let { url = it.trim() }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Paste from clipboard")
                        }
                        if (state is AddDialogState.Failed) {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                AddDialogState.Checking, AddDialogState.Installing -> Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (state == AddDialogState.Checking) {
                            "Fetching and validating the manifest…"
                        } else {
                            "Installing…"
                        }
                    )
                }

                is AddDialogState.Confirm -> {
                    val manifest = state.manifest
                    Column {
                        Text(text = manifest.name.ifBlank { manifest.id }, fontWeight = FontWeight.Bold)
                        Text(
                            text = "v${manifest.version} · ${manifest.id}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (manifest.description.isNotBlank()) {
                            Text(
                                text = manifest.description,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            text = buildString {
                                append("Resources: ")
                                append(manifest.resources.joinToString(", ").ifBlank { "none" })
                                append("\nTypes: ")
                                append(manifest.types.joinToString(", ").ifBlank { "none" })
                                append("\nCatalogs: ")
                                append(
                                    manifest.catalogs.joinToString(", ") { it.displayName }.ifBlank { "none" }
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is AddDialogState.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = "${state.name} installed")
                }
            }
        },
        confirmButton = {
            when (state) {
                AddDialogState.Idle -> TextButton(
                    onClick = { onCheck(url) },
                    enabled = url.isNotBlank()
                ) { Text(text = "Check manifest") }

                is AddDialogState.Failed -> TextButton(onClick = { onCheck(url) }) {
                    Text(text = "Try again")
                }

                is AddDialogState.Confirm -> TextButton(
                    onClick = { onInstall(state.manifest, state.baseUrl) }
                ) { Text(text = "Install") }

                is AddDialogState.Done -> TextButton(onClick = onDismiss) {
                    Text(text = "Done")
                }

                AddDialogState.Checking, AddDialogState.Installing -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is AddDialogState.Confirm -> TextButton(onClick = onReset) {
                    Text(text = "Back")
                }

                AddDialogState.Checking, AddDialogState.Installing -> Unit

                else -> TextButton(onClick = onDismiss) { Text(text = "Cancel") }
            }
        }
    )
}
