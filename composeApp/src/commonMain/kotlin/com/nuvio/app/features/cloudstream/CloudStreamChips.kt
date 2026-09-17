package com.nuvio.app.features.cloudstream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cloudstream_compat_compatible
import nuvio.composeapp.generated.resources.cloudstream_compat_failed
import nuvio.composeapp.generated.resources.cloudstream_compat_partial
import nuvio.composeapp.generated.resources.cloudstream_reason_api_version
import nuvio.composeapp.generated.resources.cloudstream_reason_incomplete
import nuvio.composeapp.generated.resources.cloudstream_reason_native_execution
import nuvio.composeapp.generated.resources.cloudstream_reason_unreachable
import org.jetbrains.compose.resources.stringResource

/**
 * Compatibility badge.
 *
 * For unsupported extensions the specific reason is shown rather than a bare
 * "Unsupported", so a `.cs3` that needs DEX execution reads exactly as
 * "Unsupported — requires native execution".
 */
@Composable
internal fun CompatibilityChip(
    compatibility: CloudStreamCompatibility,
    reason: CloudStreamCompatibilityReason,
) {
    val label = compatibilityLabel(compatibility, reason)
    val color = when (compatibility) {
        CloudStreamCompatibility.COMPATIBLE -> Color(0xFF4CAF50)
        CloudStreamCompatibility.PARTIALLY_COMPATIBLE -> Color(0xFFFFB300)
        CloudStreamCompatibility.UNSUPPORTED -> Color(0xFF9E9E9E)
        CloudStreamCompatibility.FAILED -> MaterialTheme.colorScheme.error
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Neutral metadata chip (language, content type, source count). */
@Composable
internal fun MetaChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * Resolves the user-facing compatibility label.
 *
 * An explicit reason always wins over the generic state, so the user is told
 * *why* something is unusable.
 */
@Composable
private fun compatibilityLabel(
    compatibility: CloudStreamCompatibility,
    reason: CloudStreamCompatibilityReason,
): String = when (reason) {
    CloudStreamCompatibilityReason.REQUIRES_NATIVE_EXECUTION ->
        stringResource(Res.string.cloudstream_reason_native_execution)

    CloudStreamCompatibilityReason.UNSUPPORTED_API_VERSION ->
        stringResource(Res.string.cloudstream_reason_api_version)

    CloudStreamCompatibilityReason.INCOMPLETE_METADATA ->
        stringResource(Res.string.cloudstream_reason_incomplete)

    CloudStreamCompatibilityReason.MALFORMED_OR_UNREACHABLE ->
        stringResource(Res.string.cloudstream_reason_unreachable)

    CloudStreamCompatibilityReason.NONE -> when (compatibility) {
        CloudStreamCompatibility.COMPATIBLE -> stringResource(Res.string.cloudstream_compat_compatible)
        CloudStreamCompatibility.PARTIALLY_COMPATIBLE -> stringResource(Res.string.cloudstream_compat_partial)
        CloudStreamCompatibility.FAILED -> stringResource(Res.string.cloudstream_compat_failed)
        CloudStreamCompatibility.UNSUPPORTED -> stringResource(Res.string.cloudstream_compat_failed)
    }
}
