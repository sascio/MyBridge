package com.nuvio.app.features.cloudstream

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSurfaceCard
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cloudstream_action_add
import nuvio.composeapp.generated.resources.cloudstream_action_refresh
import nuvio.composeapp.generated.resources.cloudstream_action_remove
import nuvio.composeapp.generated.resources.cloudstream_action_install
import nuvio.composeapp.generated.resources.cloudstream_action_retry
import nuvio.composeapp.generated.resources.cloudstream_action_uninstall
import nuvio.composeapp.generated.resources.cloudstream_action_update
import nuvio.composeapp.generated.resources.cloudstream_install_unsupported
import nuvio.composeapp.generated.resources.cloudstream_authors_format
import nuvio.composeapp.generated.resources.cloudstream_empty_description
import nuvio.composeapp.generated.resources.cloudstream_empty_title
import nuvio.composeapp.generated.resources.cloudstream_error_enter_url
import nuvio.composeapp.generated.resources.cloudstream_headline
import nuvio.composeapp.generated.resources.cloudstream_loading
import nuvio.composeapp.generated.resources.cloudstream_overview_active
import nuvio.composeapp.generated.resources.cloudstream_overview_catalogs
import nuvio.composeapp.generated.resources.cloudstream_overview_extensions
import nuvio.composeapp.generated.resources.cloudstream_refreshing
import nuvio.composeapp.generated.resources.cloudstream_repository_hint
import nuvio.composeapp.generated.resources.cloudstream_section_extensions
import nuvio.composeapp.generated.resources.cloudstream_section_overview
import nuvio.composeapp.generated.resources.cloudstream_section_repository
import nuvio.composeapp.generated.resources.cloudstream_sources_format
import nuvio.composeapp.generated.resources.cloudstream_version_format
import org.jetbrains.compose.resources.stringResource

/**
 * CloudStream Extensions screen.
 *
 * Deliberately mirrors the existing Addons settings page: same
 * [NuvioSurfaceCard] surfaces, [NuvioSectionLabel] section headers, Overview
 * stat row, typography and spacing, so it reads as a native part of
 * StreamBridge rather than a bolted-on experiment.
 */
@Composable
internal fun CloudStreamExtensionsPageContent(
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        CloudStreamExtensionsRepository.initialize()
    }

    val uiState by CloudStreamExtensionsRepository.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var repositoryUrl by rememberSaveable { mutableStateOf("") }
    var formMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedExtensionId by rememberSaveable { mutableStateOf<String?>(null) }
    val enterUrlMessage = stringResource(Res.string.cloudstream_error_enter_url)

    val selectedExtension = remember(uiState.extensions, selectedExtensionId) {
        uiState.extensions.firstOrNull { it.id == selectedExtensionId }
    }

    // Detail screen for one extension, with its own Sources list.
    if (selectedExtension != null) {
        CloudStreamExtensionDetail(
            extension = selectedExtension,
            modifier = modifier,
            onBack = { selectedExtensionId = null },
        )
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.cloudstream_headline),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        NuvioSectionLabel(text = stringResource(Res.string.cloudstream_section_overview))
        OverviewCard(overview = uiState.overview)

        NuvioSectionLabel(text = stringResource(Res.string.cloudstream_section_repository))
        AddRepositoryCard(
            repositoryUrl = repositoryUrl,
            formMessage = formMessage ?: uiState.errorMessage,
            isBusy = uiState.isLoading || uiState.isRefreshing,
            onUrlChange = {
                repositoryUrl = it
                formMessage = null
            },
            onAddClick = {
                val requested = repositoryUrl.trim()
                if (requested.isEmpty()) {
                    formMessage = enterUrlMessage
                    return@AddRepositoryCard
                }
                formMessage = null
                coroutineScope.launch {
                    when (val result = CloudStreamExtensionsRepository.addRepository(requested)) {
                        is AddCloudStreamRepositoryResult.Success -> repositoryUrl = ""
                        is AddCloudStreamRepositoryResult.Error -> formMessage = result.message
                    }
                }
            },
            onRefreshClick = { CloudStreamExtensionsRepository.refresh() },
        )

        // Surface every repository that could not be read, with its own action.
        uiState.failedRepositories.forEach { repository ->
            RepositoryProblemCard(
                repository = repository,
                onRemove = { CloudStreamExtensionsRepository.removeRepository(repository.url) },
            )
        }

        NuvioSectionLabel(text = stringResource(Res.string.cloudstream_section_extensions))

        when {
            uiState.isLoading && !uiState.hasLoadedOnce -> StatusCard(stringResource(Res.string.cloudstream_loading))
            uiState.isRefreshing -> StatusCard(stringResource(Res.string.cloudstream_refreshing))
            uiState.isEmpty -> EmptyStateCard()
            else -> uiState.extensions.forEach { extension ->
                ExtensionCard(
                    extension = extension,
                    onClick = { selectedExtensionId = extension.id },
                    onInstall = { CloudStreamExtensionsRepository.installExtension(extension.id) },
                    onUninstall = { CloudStreamExtensionsRepository.removeExtension(extension.id) },
                )
            }
        }
    }
}

@Composable
private fun OverviewCard(overview: CloudStreamOverview) {
    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverviewStat(
                value = overview.extensionCount.toString(),
                label = stringResource(Res.string.cloudstream_overview_extensions),
                modifier = Modifier.weight(1f),
            )
            VerticalSeparator()
            OverviewStat(
                value = overview.activeCount.toString(),
                label = stringResource(Res.string.cloudstream_overview_active),
                modifier = Modifier.weight(1f),
            )
            VerticalSeparator()
            OverviewStat(
                value = overview.catalogCount.toString(),
                label = stringResource(Res.string.cloudstream_overview_catalogs),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OverviewStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VerticalSeparator() {
    Box(
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .width(1.dp)
            .height(64.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

@Composable
private fun AddRepositoryCard(
    repositoryUrl: String,
    formMessage: String?,
    isBusy: Boolean,
    onUrlChange: (String) -> Unit,
    onAddClick: () -> Unit,
    onRefreshClick: () -> Unit,
) {
    NuvioSurfaceCard {
        OutlinedTextField(
            value = repositoryUrl,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(Res.string.cloudstream_repository_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAddClick() }),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onRefreshClick, enabled = !isBusy) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.cloudstream_action_refresh))
            }
            Button(onClick = onAddClick, enabled = !isBusy) {
                Text(stringResource(Res.string.cloudstream_action_add))
            }
        }
        if (!formMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RepositoryProblemCard(
    repository: CloudStreamRepository,
    onRemove: () -> Unit,
) {
    NuvioSurfaceCard {
        Text(
            text = repository.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = repository.errorMessage.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            Button(onClick = onRemove) {
                Text(stringResource(Res.string.cloudstream_action_remove))
            }
        }
    }
}

@Composable
private fun StatusCard(message: String) {
    NuvioSurfaceCard {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyStateCard() {
    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.cloudstream_empty_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.cloudstream_empty_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExtensionCard(
    extension: CloudStreamExtension,
    onClick: () -> Unit,
    onInstall: () -> Unit = {},
    onUninstall: () -> Unit = {},
) {
    NuvioSurfaceCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            ExtensionIconBadge(imageUrl = extension.plugin.iconUrl)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                extension.plugin.version?.let { version ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(Res.string.cloudstream_version_format, version.toString()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                extension.plugin.authors.takeIf { it.isNotEmpty() }?.let { authors ->
                    Text(
                        text = stringResource(
                            Res.string.cloudstream_authors_format,
                            authors.joinToString(", "),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        extension.plugin.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompatibilityChip(
                compatibility = extension.compatibility,
                reason = extension.compatibilityReason,
            )
            InstallStateChip(extension.installStatus)
            extension.plugin.language?.takeIf { it.isNotBlank() }?.let { MetaChip(it.uppercase()) }
            MetaChip(stringResource(Res.string.cloudstream_sources_format, extension.sourceCount))
        }

        // The failure reason is shown verbatim so the user can tell a dead
        // network apart from a package that failed verification.
        extension.installStatus.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }

        InstallActions(
            extension = extension,
            onInstall = onInstall,
            onUninstall = onUninstall,
        )
    }
}

/**
 * Install / update / uninstall controls.
 *
 * Nothing is offered that cannot genuinely be done: an extension this build
 * cannot execute gets an explanation instead of a dead Install button, and no
 * action is offered while one is already running.
 */
@Composable
private fun InstallActions(
    extension: CloudStreamExtension,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    if (!extension.plugin.isExecutable) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.cloudstream_install_unsupported),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Spacer(modifier = Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (extension.installStatus.isInstalled) {
            Button(onClick = onUninstall, enabled = !extension.installStatus.isBusy) {
                Text(stringResource(Res.string.cloudstream_action_uninstall))
            }
        }
        when {
            extension.canUpdate -> Button(onClick = onInstall) {
                Text(stringResource(Res.string.cloudstream_action_update))
            }

            extension.installStatus.state == CloudStreamInstallState.FAILED ->
                Button(onClick = onInstall) {
                    Text(stringResource(Res.string.cloudstream_action_retry))
                }

            extension.canInstall -> Button(onClick = onInstall) {
                Text(stringResource(Res.string.cloudstream_action_install))
            }
        }
    }
}

@Composable
internal fun ExtensionIconBadge(imageUrl: String?) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Extension,
                contentDescription = null,
                tint = Color(0xFF71BDE8),
                modifier = Modifier.size(34.dp),
            )
        }
    }
}
