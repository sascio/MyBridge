package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.StreamBridgeBrandLockup
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.about_us_community_section
import nuvio.composeapp.generated.resources.about_us_goal_body
import nuvio.composeapp.generated.resources.about_us_goal_title
import nuvio.composeapp.generated.resources.about_us_page_title
import nuvio.composeapp.generated.resources.about_us_telegram_description
import nuvio.composeapp.generated.resources.about_us_telegram_title
import nuvio.composeapp.generated.resources.about_us_what_body
import nuvio.composeapp.generated.resources.about_us_what_title
import org.jetbrains.compose.resources.stringResource

/** StreamBridge community channel. Opened in the external browser, never in-app. */
internal const val STREAMBRIDGE_TELEGRAM_URL = "https://t.me/StreamBridge_App"

@Composable
fun AboutUsSettingsScreen(
    onBack: () -> Unit,
) {
    NuvioScreen(
        modifier = Modifier.fillMaxSize(),
    ) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.about_us_page_title),
                onBack = onBack,
            )
        }
        aboutUsContent(isTablet = false)
    }
}

internal fun LazyListScope.aboutUsContent(
    isTablet: Boolean,
) {
    item {
        AboutUsBody(isTablet = isTablet)
    }
}

@Composable
private fun AboutUsBody(isTablet: Boolean) {
    val contentPadding = if (isTablet) 24.dp else 16.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = contentPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StreamBridgeBrandLockup(
                markSize = 44.dp,
                nameFontSize = 20.sp,
                showTagline = true,
            )
        }

        AboutUsCard(
            title = stringResource(Res.string.about_us_what_title),
            body = stringResource(Res.string.about_us_what_body),
        )
        AboutUsCard(
            title = stringResource(Res.string.about_us_goal_title),
            body = stringResource(Res.string.about_us_goal_body),
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.about_us_community_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            TelegramRow()
        }
    }
}

@Composable
private fun AboutUsCard(title: String, body: String) {
    NuvioSurfaceCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TelegramRow() {
    // LocalUriHandler is the mechanism the rest of the app already uses for external
    // links (tracking provider websites, license repositories), so no new dependency.
    val uriHandler = LocalUriHandler.current
    NuvioSurfaceCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(Res.string.about_us_telegram_description),
                ) {
                    runCatching { uriHandler.openUri(STREAMBRIDGE_TELEGRAM_URL) }
                }
                .padding(PaddingValues(16.dp)),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.about_us_telegram_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.about_us_telegram_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
