package com.nuvio.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_brand_name
import nuvio.composeapp.generated.resources.app_mark_transparent
import nuvio.composeapp.generated.resources.streambridge_tagline
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Horizontal StreamBridge identity: transparent B mark + wordmark on one line,
 * optional tagline underneath. Used on splash, intro, credits, auth, and profile.
 */
@Composable
internal fun StreamBridgeBrandLockup(
    modifier: Modifier = Modifier,
    markSize: Dp = 48.dp,
    nameFontSize: TextUnit = 22.sp,
    nameFontWeight: FontWeight = FontWeight.SemiBold,
    nameColor: Color = MaterialTheme.colorScheme.onBackground,
    showTagline: Boolean = false,
    taglineFontSize: TextUnit = 13.sp,
    taglineColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    contentDescription: String? = stringResource(Res.string.app_brand_name),
) {
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(Res.drawable.app_mark_transparent),
                contentDescription = contentDescription,
                modifier = Modifier.size(markSize),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = stringResource(Res.string.app_brand_name),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = nameFontSize,
                    fontWeight = nameFontWeight,
                ),
                color = nameColor,
                maxLines = 1,
            )
        }
        if (showTagline) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(Res.string.streambridge_tagline),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = taglineFontSize),
                color = taglineColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}
