package com.nuvio.app.features.cloudstream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSurfaceCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cloudstream_authorization_required_body
import nuvio.composeapp.generated.resources.cloudstream_authorization_required_title
import nuvio.composeapp.generated.resources.cloudstream_authorize
import nuvio.composeapp.generated.resources.cloudstream_cancel
import nuvio.composeapp.generated.resources.cloudstream_revoke
import nuvio.composeapp.generated.resources.cloudstream_trust_chip_authorized
import nuvio.composeapp.generated.resources.cloudstream_trust_chip_unauthorized
import nuvio.composeapp.generated.resources.cloudstream_trust_confirm_scope
import nuvio.composeapp.generated.resources.cloudstream_trust_device_note
import nuvio.composeapp.generated.resources.cloudstream_trust_scope
import nuvio.composeapp.generated.resources.cloudstream_trust_section
import nuvio.composeapp.generated.resources.cloudstream_trust_state_authorized
import nuvio.composeapp.generated.resources.cloudstream_trust_state_unauthorized
import nuvio.composeapp.generated.resources.cloudstream_trust_title
import nuvio.composeapp.generated.resources.cloudstream_trust_warning
import org.jetbrains.compose.resources.stringResource

/**
 * Explicit consent dialog for one repository.
 *
 * Authorization is what permits third-party `.cs3` code to be downloaded and
 * executed, so it always requires a deliberate confirmation that names the
 * repository and states that every extension it provides is covered. There is
 * intentionally no "authorize all" action.
 */
@Composable
internal fun CloudStreamTrustDialog(
    repositoryUrl: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.cloudstream_trust_title)) },
        text = {
            Text(
                stringResource(Res.string.cloudstream_trust_confirm_scope, repositoryUrl) +
                    "\n\n" +
                    stringResource(Res.string.cloudstream_trust_warning),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.cloudstream_authorize)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cloudstream_cancel)) }
        },
    )
}

/**
 * Repository authorization surface.
 *
 * Rendered on the extension details screen and on every repository card, so
 * the action that the install path requires is always visible where the user
 * looks for it. Authorization is per repository URL: authorizing here covers
 * every extension the repository lists, which is what the installer checks.
 */
@Composable
internal fun CloudStreamRepositoryAuthorizationCard(
    repositoryUrl: String,
    authorized: Boolean,
    modifier: Modifier = Modifier,
) {
    var showConfirm by remember(repositoryUrl) { mutableStateOf(false) }

    if (showConfirm) {
        CloudStreamTrustDialog(
            repositoryUrl = repositoryUrl,
            onDismiss = { showConfirm = false },
            onConfirm = {
                CloudStreamExtensionsRepository.setRepositoryTrusted(repositoryUrl, true)
                showConfirm = false
            },
        )
    }

    NuvioSurfaceCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioSectionLabel(
                text = stringResource(Res.string.cloudstream_trust_section),
                modifier = Modifier.weight(1f),
            )
            MetaChip(
                stringResource(
                    if (authorized) Res.string.cloudstream_trust_chip_authorized
                    else Res.string.cloudstream_trust_chip_unauthorized,
                ),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (authorized) Res.string.cloudstream_trust_state_authorized
                else Res.string.cloudstream_trust_state_unauthorized,
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = if (authorized) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error,
        )

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = repositoryUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.cloudstream_trust_scope),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.cloudstream_trust_device_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (authorized) {
                OutlinedButton(
                    onClick = { CloudStreamExtensionsRepository.setRepositoryTrusted(repositoryUrl, false) },
                ) { Text(stringResource(Res.string.cloudstream_revoke)) }
            } else {
                Button(onClick = { showConfirm = true }) {
                    Text(stringResource(Res.string.cloudstream_authorize))
                }
            }
        }
    }
}

/** Compact one-line authorization state for a repository summary row. */
@Composable
internal fun CloudStreamRepositoryAuthorizationChip(authorized: Boolean) {
    MetaChip(
        stringResource(
            if (authorized) Res.string.cloudstream_trust_chip_authorized
            else Res.string.cloudstream_trust_chip_unauthorized,
        ),
    )
}

/**
 * Error banner shown when an install or enable was refused because the
 * repository is not authorized.
 *
 * Carries the action itself: the refusal and the way to resolve it are never
 * separated, so the error can never read as a dead end.
 */
@Composable
internal fun AuthorizationRequiredCard(
    url: String,
    onAuthorize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NuvioSurfaceCard(modifier = modifier) {
        Text(
            text = stringResource(Res.string.cloudstream_authorization_required_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.cloudstream_authorization_required_body, url),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            Button(onClick = onAuthorize) {
                Text(stringResource(Res.string.cloudstream_authorize))
            }
        }
    }
}
