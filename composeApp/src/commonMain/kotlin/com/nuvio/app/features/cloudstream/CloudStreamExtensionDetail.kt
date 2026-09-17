package com.nuvio.app.features.cloudstream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSurfaceCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cloudstream_action_back
import nuvio.composeapp.generated.resources.cloudstream_action_configure
import nuvio.composeapp.generated.resources.cloudstream_authors_format
import nuvio.composeapp.generated.resources.cloudstream_metadata_unavailable
import nuvio.composeapp.generated.resources.cloudstream_section_sources
import nuvio.composeapp.generated.resources.cloudstream_sources_format
import nuvio.composeapp.generated.resources.cloudstream_state_disabled
import nuvio.composeapp.generated.resources.cloudstream_state_enabled
import nuvio.composeapp.generated.resources.cloudstream_version_format
import org.jetbrains.compose.resources.stringResource

/**
 * Detail screen for a single CloudStream extension.
 *
 * Headline is the extension name, metadata comes first, then the extension's
 * individual Sources — each independently manageable. This is what keeps
 * Extension and Source separate concepts rather than one flattened card.
 */
@Composable
internal fun CloudStreamExtensionDetail(
    extension: CloudStreamExtension,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NuvioIconActionButton(
                icon = Icons.Rounded.ArrowBack,
                contentDescription = stringResource(Res.string.cloudstream_action_back),
                onClick = onBack,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = extension.name,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        MetadataCard(extension = extension)

        NuvioSectionLabel(text = stringResource(Res.string.cloudstream_section_sources))
        extension.sources.forEach { source ->
            SourceCard(
                source = source,
                onEnabledChange = { enabled ->
                    CloudStreamExtensionsRepository.setSourceEnabled(source.id, enabled)
                },
                onConfigurationChange = { key, value ->
                    CloudStreamExtensionsRepository.setConfigurationValue(source.id, key, value)
                },
            )
        }
    }
}

@Composable
private fun MetadataCard(extension: CloudStreamExtension) {
    val plugin = extension.plugin
    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            ExtensionIconBadge(imageUrl = plugin.iconUrl)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                plugin.version?.let { version ->
                    Text(
                        text = stringResource(Res.string.cloudstream_version_format, version.toString()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                plugin.authors.takeIf { it.isNotEmpty() }?.let { authors ->
                    Text(
                        text = stringResource(
                            Res.string.cloudstream_authors_format,
                            authors.joinToString(", "),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = plugin.description?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.cloudstream_metadata_unavailable),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompatibilityChip(
                compatibility = extension.compatibility,
                reason = extension.compatibilityReason,
            )
            plugin.language?.takeIf { it.isNotBlank() }?.let { MetaChip(it.uppercase()) }
            plugin.tvTypes.forEach { type -> MetaChip(type) }
            MetaChip(stringResource(Res.string.cloudstream_sources_format, extension.sourceCount))
        }
    }
}

@Composable
private fun SourceCard(
    source: CloudStreamSource,
    onEnabledChange: (Boolean) -> Unit,
    onConfigurationChange: (String, String) -> Unit,
) {
    var showConfiguration by remember { mutableStateOf(false) }

    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (source.enabled) {
                        stringResource(Res.string.cloudstream_state_enabled)
                    } else {
                        stringResource(Res.string.cloudstream_state_disabled)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // Only a genuinely activatable source can be switched on. For a
            // .cs3 needing DEX execution this stays disabled rather than
            // offering a control that cannot work.
            Switch(
                checked = source.enabled,
                enabled = source.canActivate,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompatibilityChip(
                compatibility = source.compatibility,
                reason = source.compatibilityReason,
            )
            source.contentType?.let { MetaChip(it) }
        }

        // Configure appears only when the source really exposes configuration.
        if (source.supportsConfiguration) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showConfiguration = !showConfiguration }) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.cloudstream_action_configure))
                }
            }

            if (showConfiguration) {
                source.configuration.forEach { field ->
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = field.value,
                        onValueChange = { onConfigurationChange(field.key, it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(field.label) },
                    )
                }
            }
        }
    }
}
