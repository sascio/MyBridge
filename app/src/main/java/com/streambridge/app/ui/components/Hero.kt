package com.streambridge.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.ui.theme.LocalAccent
import kotlinx.coroutines.delay

/**
 * Cinematic hero banner: auto-rotating backdrop with a deep gradient
 * scrim, title, meta line, accent-gradient play button, capsule info
 * button and page dots.
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
    val accent = LocalAccent.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(440.dp)
    ) {
        Crossfade(targetState = item, label = "hero") { current ->
            val artwork = current.backdrop ?: current.poster
            if (!artwork.isNullOrBlank()) {
                AsyncImage(
                    model = artwork,
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

        // Scrims: bottom anchor gradient + subtle top shade for status bar.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x66000000),
                        0.35f to Color.Transparent,
                        0.62f to Color(0x59000000),
                        1f to Color(0xF2050505)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 18.dp)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            val meta = listOfNotNull(
                item.releaseInfo?.takeIf { it.isNotBlank() },
                item.rating?.takeIf { it.isNotBlank() }
            )
            if (meta.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.rating?.takeIf { it.isNotBlank() }?.let { rating ->
                        Surface(
                            color = Color(0xCC141414),
                            contentColor = accent.primary,
                            shape = CircleShape
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = rating,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (item.releaseInfo?.isNotBlank() == true) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                    item.releaseInfo?.takeIf { it.isNotBlank() }?.let { year ->
                        Text(
                            text = year,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFFCFCFCF),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Play: accent-gradient capsule.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Brush.horizontalGradient(accent.gradient))
                        .clickable { onPlay(item) }
                        .padding(horizontal = 26.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF141414),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Play",
                            color = Color(0xFF141414),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Info: glassy capsule.
                Surface(
                    color = Color(0x66141414),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(percent = 50),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, Color(0x59FFFFFF)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onOpenDetails(item) }
                            .padding(horizontal = 22.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Details",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
            }

            if (items.size > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    repeat(items.size) { dot ->
                        Box(
                            modifier = Modifier
                                .size(width = if (dot == index) 16.dp else 6.dp, height = 6.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (dot == index) accent.primary else Color(0x80FFFFFF)
                                )
                        )
                    }
                }
            }
        }
    }
}
