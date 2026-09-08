package com.streambridge.app.ui.plugins

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.streambridge.app.addon.adapter.CloudstreamAdapter
import com.streambridge.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PluginsUiState {
    data object Idle : PluginsUiState
    data object Loading : PluginsUiState
    data class Ready(val repository: CloudstreamAdapter.CloudstreamRepository) : PluginsUiState
    data class Failed(val message: String) : PluginsUiState
}

/**
 * Cloudstream repository browser. Cloudstream plugins are compiled
 * (.cs3) code packages; Stream Bridge deliberately does NOT execute
 * them — this screen lists what a repository offers and explains the
 * limitation honestly. No fake install/play buttons.
 */
class PluginsViewModel(
    private val adapter: CloudstreamAdapter
) : ViewModel() {

    private val _state = MutableStateFlow<PluginsUiState>(PluginsUiState.Idle)
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    fun loadRepository(url: String) {
        if (url.isBlank()) return
        _state.value = PluginsUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                PluginsUiState.Ready(adapter.loadRepository(url))
            } catch (e: Exception) {
                PluginsUiState.Failed(e.message ?: "Could not load the repository")
            }
        }
    }

    fun reset() {
        _state.value = PluginsUiState.Idle
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                PluginsViewModel(CloudstreamAdapter.instance)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val vm: PluginsViewModel = viewModel(factory = PluginsViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

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
                .padding(horizontal = 20.dp)
        ) {
            var repoUrl by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf("")
            }

            // Security explainer — honest and prominent.
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Cloudstream repositories (read-only)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "You can browse any Cloudstream repository here. " +
                                "Cloudstream plugins are compiled code packages, and Stream " +
                                "Bridge never executes downloaded code — so plugins cannot be " +
                                "installed or run in this app. Add HTTP addons (Stremio or " +
                                "Nuvio compatible) on the Extensions screen instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = repoUrl,
                onValueChange = { repoUrl = it },
                label = { Text("Repository URL (repo.json or plugins.json)") },
                placeholder = { Text("https://example.com/repo.json") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { vm.loadRepository(repoUrl) },
                enabled = repoUrl.isNotBlank()
            ) {
                Text(text = "Load repository")
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (val s = state) {
                PluginsUiState.Idle -> Unit

                PluginsUiState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                is PluginsUiState.Failed -> Text(
                    text = s.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )

                is PluginsUiState.Ready -> {
                    Text(
                        text = s.repository.name.ifBlank { "Repository" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${s.repository.plugins.size} plugins listed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (s.repository.plugins.isEmpty()) {
                        Text(
                            text = "This repository lists no plugins.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    s.repository.plugins.forEach { plugin ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = plugin.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Surface(
                                        shape = com.streambridge.app.ui.theme.PillShape,
                                        color = if (plugin.isOperational) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ) {
                                        Text(
                                            text = if (plugin.isOperational) "OK" else "Down",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                if (plugin.description.isNotBlank()) {
                                    Text(
                                        text = plugin.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = listOfNotNull(
                                        "v${plugin.version}",
                                        plugin.language.takeIf { it.isNotBlank() }?.uppercase(),
                                        plugin.tvTypes.joinToString("/").takeIf { it.isNotBlank() }
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
