package com.streambridge.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.staticCompositionLocalOf

/** Provided by the app shell so scrollable screens can fold the pill bar labels. */
val LocalPillNavScroll = staticCompositionLocalOf<PillNavScrollState?> { null }

/**
 * Scroll-aware label collapse for the floating pill navigation bar:
 * scrolling down folds the labels away (icons only), scrolling back up
 * (or reaching the top) unfolds them again.
 */
@Stable
class PillNavScrollState {
    var labelFraction by mutableFloatStateOf(1f)
        private set

    private var accumulated = 0f

    fun expand() {
        labelFraction = 1f
        accumulated = 0f
    }

    fun collapse() {
        labelFraction = 0f
        accumulated = 0f
    }

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy == 0f) return Offset.Zero
            accumulated += dy
            if (accumulated < -Threshold && labelFraction != 0f) {
                labelFraction = 0f
                accumulated = 0f
            } else if (accumulated > Threshold && labelFraction != 1f) {
                labelFraction = 1f
                accumulated = 0f
            }
            if (dy < 0f && accumulated > 0f) accumulated = dy
            if (dy > 0f && accumulated < 0f) accumulated = dy
            return Offset.Zero
        }
    }

    private companion object {
        const val Threshold = 60f
    }
}

@Composable
fun rememberPillNavScrollState(): PillNavScrollState = remember { PillNavScrollState() }

data class PillTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

/**
 * Floating capsule navigation bar. Sits detached above the content with
 * a translucent surface, subtle border and soft shadow; the active tab
 * keeps its label, the others reveal theirs while expanded.
 */
@Composable
fun PillNavBar(
    tabs: List<PillTab>,
    selectedRoute: String,
    onSelect: (PillTab) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: PillNavScrollState? = null
) {
    val fraction by animateFloatAsState(
        targetValue = scrollState?.labelFraction ?: 1f,
        animationSpec = tween(220),
        label = "pill-label-fraction"
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shadowElevation = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val selected = tab.route == selectedRoute
                val iconTint by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(200),
                    label = "pill-icon-tint"
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(tab) }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                }
                            )
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = tab.label,
                            tint = iconTint,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = selected || fraction > 0.5f,
                        enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(tween(180)),
                        exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(tween(150))
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = iconTint,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp, end = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
