package com.streambridge.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streambridge.app.addon.model.MediaItem
import kotlinx.coroutines.delay

/**
 * Cinematic hero carousel: a full-bleed, edge-to-edge rotating backdrop
 * that extends behind the status bar and the top navigation overlay.
 * Content sits low on the artwork — large title, a Type • Genre • Year
 * metadata line, a white pill CTA to details plus a glass play action —
 * and a modern pagination indicator (active dot expands into a pill).
 *
 * All content comes from the app's real catalog data; nothing is
 * hard-coded. Auto-rotates every 7 s while more than one item exists.
 */
@Composable
fun Hero(
    items: List<MediaItem>,
    modifier: Modifier = Modifier,
    onPlay: (MediaItem) -> Unit,
    onOpenDetails: (MediaItem) -> Unit
) {
    if (items.isEmpty()) return
    var index by remember(items) { mutableIntStateOf(0) }
    LaunchedEffect(items.size) {
        while (items.size > 1) {
            delay(7000)
            index = (index + 1) % items.size
        }
    }
    val item = items[index.coerceIn(0, items.lastIndex)]

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(500.dp)
    ) {
        Crossfade(
            targetState = item,
            animationSpec = tween(600),
            label = "hero-artwork"
        ) { current ->
            val artwork = current.backdrop ?: current.poster
            if (!artwork.isNullOrBlank()) {
                // Decode at roughly the on-screen size: a long edge of
                // ~1080 px and the hero height. Keeps memory in check on
                // large devices without visible quality loss.
                val heroHeightPx = with(LocalDensity.current) { 500.dp.roundToPx() }
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(artwork)
                        .size(1080, heroHeightPx)
                        .crossfade(true)
                        .build(),
                    contentDescription = current.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                )
            }
        }

        // Scrims: a soft shade at the very top (status bar / nav overlay
        // readability) and a strong cinematic fade into the app background.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x99000000),
                        0.30f to Color(0x14000000),
                        0.55f to Color(0x33000000),
                        0.78f to Color(0xB3000000),
                        1f to Color(0xF2050505)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 22.dp)
        ) {
            // Large cinematic title.
            Text(
                text = item.name,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp),
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Type • first genre • year, from the real metadata.
            val metaLine = listOfNotNull(
                item.type.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() },
                item.genres.firstOrNull { it.isNotBlank() },
                item.releaseInfo?.takeIf { it.isNotBlank() }
            ).joinToString("  •  ")
            if (metaLine.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFCFCFCF),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Primary CTA: white pill, black text, subtle press scale.
                val detailsInteraction = remember { MutableInteractionSource() }
                Surface(
                    onClick = { onOpenDetails(item) },
                    interactionSource = detailsInteraction,
                    shape = RoundedCornerShape(percent = 50),
                    color = Color(0xFFF2F2F2),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .pressScale(detailsInteraction)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = Color(0xFF101014),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "View Details",
                            color = Color(0xFF101014),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Secondary action: glass capsule play button.
                val playInteraction = remember { MutableInteractionSource() }
                Surface(
                    onClick = { onPlay(item) },
                    interactionSource = playInteraction,
                    shape = CircleShape,
                    color = Color(0x59141414),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, Color(0x59FFFFFF)
                    ),
                    modifier = Modifier
                        .size(50.dp)
                        .pressScale(playInteraction, pressedScale = 0.93f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            // Pagination: active indicator expands into a rounded pill.
            if (items.size > 1) {
                Spacer(modifier = Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(items.size) { dot ->
                        val active = dot == index
                        val dotWidth by animateDpAsState(
                            targetValue = if (active) 24.dp else 6.dp,
                            animationSpec = spring(
                                dampingRatio = 0.75f,
                                stiffness = 400f
                            ),
                            label = "hero-dot-width"
                        )
                        Box(
                            modifier = Modifier
                                .size(width = dotWidth, height = 6.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (active) Color(0xFFF2F2F2) else Color(0x59FFFFFF)
                                )
                        )
                    }
                }
            }
        }
    }
}
