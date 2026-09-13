package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_privacy_policy
import nuvio.composeapp.generated.resources.privacy_policy_network_addons_body
import nuvio.composeapp.generated.resources.privacy_policy_network_addons_title
import nuvio.composeapp.generated.resources.privacy_policy_network_debrid_body
import nuvio.composeapp.generated.resources.privacy_policy_network_debrid_title
import nuvio.composeapp.generated.resources.privacy_policy_network_livetv_body
import nuvio.composeapp.generated.resources.privacy_policy_network_livetv_title
import nuvio.composeapp.generated.resources.privacy_policy_network_nuvio_body
import nuvio.composeapp.generated.resources.privacy_policy_network_nuvio_title
import nuvio.composeapp.generated.resources.privacy_policy_network_optional_body
import nuvio.composeapp.generated.resources.privacy_policy_network_optional_title
import nuvio.composeapp.generated.resources.privacy_policy_network_skipintro_body
import nuvio.composeapp.generated.resources.privacy_policy_network_skipintro_title
import nuvio.composeapp.generated.resources.privacy_policy_section_control_body
import nuvio.composeapp.generated.resources.privacy_policy_section_control_title
import nuvio.composeapp.generated.resources.privacy_policy_section_crash_body
import nuvio.composeapp.generated.resources.privacy_policy_section_crash_title
import nuvio.composeapp.generated.resources.privacy_policy_section_local_body
import nuvio.composeapp.generated.resources.privacy_policy_section_local_title
import nuvio.composeapp.generated.resources.privacy_policy_section_network_intro
import nuvio.composeapp.generated.resources.privacy_policy_section_network_title
import nuvio.composeapp.generated.resources.privacy_policy_section_overview_body
import nuvio.composeapp.generated.resources.privacy_policy_section_overview_title
import nuvio.composeapp.generated.resources.privacy_policy_section_p2p_body
import nuvio.composeapp.generated.resources.privacy_policy_section_p2p_title
import nuvio.composeapp.generated.resources.privacy_policy_section_supporter_body
import nuvio.composeapp.generated.resources.privacy_policy_section_supporter_title
import nuvio.composeapp.generated.resources.privacy_policy_section_updates_body
import nuvio.composeapp.generated.resources.privacy_policy_section_updates_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun PrivacyPolicySettingsScreen(
    onBack: () -> Unit,
) {
    NuvioScreen(
        modifier = Modifier.fillMaxSize(),
    ) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_page_privacy_policy),
                onBack = onBack,
            )
        }
        privacyPolicyContent(isTablet = false)
    }
}

internal fun LazyListScope.privacyPolicyContent(
    isTablet: Boolean,
) {
    item {
        PrivacyPolicyBody(isTablet = isTablet)
    }
}

@Composable
private fun PrivacyPolicyBody(
    isTablet: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (isTablet) 28.dp else 24.dp),
    ) {
        PolicyStack(
            title = stringResource(Res.string.privacy_policy_section_overview_title),
            isTablet = isTablet,
        ) {
            PolicyText(
                text = stringResource(Res.string.privacy_policy_section_overview_body),
                isTablet = isTablet,
            )
        }

        PolicyStack(
            title = stringResource(Res.string.privacy_policy_section_local_title),
            isTablet = isTablet,
        ) {
            PolicyText(
                text = stringResource(Res.string.privacy_policy_section_local_body),
                isTablet = isTablet,
            )
        }

        PolicyStack(
            title = stringResource(Res.string.privacy_policy_section_network_title),
            isTablet = isTablet,
        ) {
            PolicyText(
                text = stringResource(Res.string.privacy_policy_section_network_intro),
                isTablet = isTablet,
            )
            PolicyEntry(
                title = stringResource(Res.string.privacy_policy_network_nuvio_title),
                body = stringResource(Res.string.privacy_policy_network_nuvio_body),
                isTablet = isTablet,
            )
            PolicyEntry(
                title = stringResource(Res.string.privacy_policy_network_addons_title),
                body = stringResource(Res.string.privacy_policy_network_addons_body),
                isTablet = isTablet,
            )
            PolicyEntry(
                title = stringResource(Res.string.privacy_policy_network_optional_title),
                body = stringResource(Res.string.privacy_policy_network_optional_body),
                isTablet = isTablet,
            )
            PolicyEntry(
                title = stringResource(Res.string.privacy_policy_network_debrid_title),
                body = stringResource(Res.string.privacy_policy_network_debrid_body),
                isTablet = isTablet,
            )
            PolicyEntry(
                title = stringResource(Res.string.privacy_policy_network_livetv_title),
                body = stringResource(Res.string.privacy_policy_network_livetv_body),
                isTablet = isTablet,
            )
            PolicyEntry(
                title = stringResource(Res.string.privacy_policy_network_skipintro_title),
                body = stringResource(Res.string.privacy_policy_network_skipintro_body),
                isTablet = isTablet,
            )
        }

        PolicyStack(
            title = stringResource(Res.string.privacy_policy_section_p2p_title),
            isTablet = isTablet,
        ) {
            PolicyText(
                text = stringResource(Res.string.privacy_policy_section_p2p_body),
                isTablet = isTablet,
            )
        }

        PolicyStack(
            title = stringResource(Res.string.privacy_policy_section_updates_title),
            isTablet = isTablet,
        ) {
            PolicyText(
                text = stringResource(Res.string.privacy_policy_section_updates_body),
                isTablet = isTablet,
            )
        }

        PolicyStack(
            title = stringResource(Res.string.privacy_policy_section_crash_title),
            isTablet = isTablet,
        ) {
            PolicyText(
                text = stringResource(Res.string.privacy_policy_section_crash_body),
                isTablet = isTablet,
            )
        }

        PolicyStack(
            title = stringResource(Res.string.privacy_policy_section_supporter_title),
            isTablet = isTablet,
        ) {
            PolicyText(
                text = stringResource(Res.string.privacy_policy_section_supporter_body),
                isTablet = isTablet,
            )
        }

        PolicyStack(
            title = stringResource(Res.string.privacy_policy_section_control_title),
            isTablet = isTablet,
        ) {
            PolicyText(
                text = stringResource(Res.string.privacy_policy_section_control_body),
                isTablet = isTablet,
            )
        }
    }
}

@Composable
private fun PolicyStack(
    title: String,
    isTablet: Boolean,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(if (isTablet) 12.dp else 10.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (isTablet) 16.dp else 12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun PolicyText(
    text: String,
    isTablet: Boolean,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun PolicyEntry(
    title: String,
    body: String,
    isTablet: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(if (isTablet) 6.dp else 4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
