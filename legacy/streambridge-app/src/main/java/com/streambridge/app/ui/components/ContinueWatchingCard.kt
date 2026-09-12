package com.streambridge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streambridge.app.core.TimeFormat
import com.streambridge.app.data.db.WatchProgressEntity

/**
 * Premium "continue watching" card: landscape artwork in a rounded,
 * softly elevated frame, a dark cinematic gradient, text over the
 * artwork, a compact glass episode badge (or a Next Up badge for the
 * most recent entry) and a thin integrated progress bar driven by the
 * user's REAL playback position.
 */
@Composable
fun ContinueWatchingCard(
    entry: WatchProgressEntity,
    modifier: Modifier = Modifier,
    width: Dp = 280.dp,
    isNextUp: Boolean = false,
    onClick: () -> Unit
) {
    val pressInteraction = remember { MutableInteractionSource() }
    val fraction = TimeFormat.progressFraction(entry.positionMs, entry.durationMs)

    Box(
        modifier = modifier
            .width(width)
            .pressScale(pressInteraction)
            .clickable(
                interactionSource = pressInteraction,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val artwork = entry.backdrop ?: entry.poster
            if (!artwork.isNullOrBlank()) {
                // Decode at display resolution (16:9 card), not source size.
                val (cardWidthPx, cardHeightPx) = with(LocalDensity.current) {
                    width.roundToPx() to (width * 9f / 16f).roundToPx()
                }
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(artwork)
                        .size(cardWidthPx, cardHeightPx)
                        .crossfade(true)
                        .build(),
                    contentDescription = entry.metaName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Cinematic gradient for text legibility.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x66000000),
                            0.35f to Color.Transparent,
                            0.62f to Color(0x40000000),
                            1f to Color(0xE6000000)
                        )
                    )
            )

            // Top-start: Next Up badge or the episode position chip.
            if (isNextUp) {
                NextUpBadge(modifier = Modifier.padding(10.dp))
            } else if (entry.season > 0) {
                GlassBadge(
                    text = "S%02d E%02d".format(entry.season, entry.episode),
                    modifier = Modifier.padding(10.dp)
                )
            }

            // Bottom: title + episode title over the artwork.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp, top = 30.dp)
            ) {
                Text(
                    text = entry.metaName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!entry.episodeTitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = entry.episodeTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCFCFCF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Thin, modern, integrated progress bar.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 6.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color(0x40FFFFFF)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                        .fillMaxSize()
                        .background(Color(0xFFF2F2F2))
                )
            }
        }
    }
}

/** Compact "Next Up" overlay badge with a glassy dark appearance. */
@Composable
fun NextUpBadge(modifier: Modifier = Modifier) {
    GlassBadge(text = "Next Up", modifier = modifier, prominent = true)
}

/** Small translucent pill used for badges over artwork. */
@Composable
fun GlassBadge(
    text: String,
    modifier: Modifier = Modifier,
    prominent: Boolean = false
) {
    androidx.compose.material3.Surface(
        color = if (prominent) Color(0xCC16161C) else Color(0x9916161C),
        contentColor = Color.White,
        shape = RoundedCornerShape(percent = 50),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x26FFFFFF)),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (prominent) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
