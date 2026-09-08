package com.streambridge.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.lerp

/** Provided by the app shell so scrollable screens can drive the nav bar. */
val LocalPillNavScroll = staticCompositionLocalOf<PillNavScrollState?> { null }

/** Bottom-navigation layout modes (Appearance > Layout). */
enum class NavLayoutMode(val key: String, val label: String) {
    Adaptive("adaptive", "Adaptive"),
    AlwaysExpanded("expanded", "Always Expanded"),
    AlwaysCompact("compact", "Always Compact"),
    Classic("classic", "Classic");

    companion object {
        fun fromKey(key: String?): NavLayoutMode =
            entries.firstOrNull { it.key == key } ?: Adaptive
    }
}

/**
 * Scroll-driven compaction for the floating navigation: scrolling up
 * (browsing forward) targets the COMPACT state, scrolling back down
 * targets the EXPANDED state. A small accumulated threshold prevents
 * jitter from tiny scroll movements; the bar itself animates smoothly
 * between the two states.
 */
@Stable
class PillNavScrollState {
    /** Target compaction: 0f = expanded, 1f = compact. */
    var targetCompact by mutableFloatStateOf(0f)
        private set

    private var accumulated = 0f

    fun expand() {
        targetCompact = 0f
        accumulated = 0f
    }

    fun collapse() {
        targetCompact = 1f
        accumulated = 0f
    }

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy == 0f) return Offset.Zero
            if (dy < 0f) {
                // Scrolling up / forward: compact the navigation.
                if (targetCompact != 1f) {
                    accumulated += dy
                    if (accumulated < -Threshold) collapse()
                } else {
                    accumulated = 0f
                }
            } else {
                // Scrolling down / back: expand the navigation.
                if (targetCompact != 0f) {
                    accumulated += dy
                    if (accumulated > Threshold) expand()
                } else {
                    accumulated = 0f
                }
            }
            return Offset.Zero
        }
    }

    private companion object {
        const val Threshold = 90f
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
 * Floating capsule navigation bar with an adaptive compact/expanded
 * transformation:
 *
 *  - EXPANDED: full pill with icons above labels, a soft highlight
 *    pill that slides between destinations, soft shadow.
 *  - COMPACT: the bar shrinks slightly and settles closer to the
 *    content; items keep the icon-above-label structure with smaller,
 *    faded labels — never a horizontal icon+label collision.
 *
 * The transition is a continuous interpolation driven by
 * [PillNavScrollState] (or fixed by [layoutMode]); there is never an
 * abrupt show/hide.
 */
@Composable
fun PillNavBar(
    tabs: List<PillTab>,
    selectedRoute: String,
    onSelect: (PillTab) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: PillNavScrollState? = null,
    layoutMode: NavLayoutMode = NavLayoutMode.Adaptive
) {
    val target = when (layoutMode) {
        NavLayoutMode.Adaptive -> scrollState?.targetCompact ?: 0f
        NavLayoutMode.AlwaysCompact -> 1f
        else -> 0f
    }
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "nav-compact-fraction"
    )
    Surface(
        modifier = modifier.graphicsLayer {
            // Subtle overall shrink while compact (keeps it feeling
            // intentional rather than clipped).
            val scale = 1f - (0.06f * fraction)
            scaleX = scale
            scaleY = scale
        },
        shape = RoundedCornerShape(percent = 50),
        // Translucent glass-like surface: solid enough for readability,
        // cheap enough for 60fps on low-end devices (no runtime blur).
        color = Color(0xF214161C),
        border = BorderStroke(1.dp, Color(0x26FFFFFF)),
        shadowElevation = lerp(20.dp, 10.dp, fraction)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = lerp(8.dp, 5.dp, fraction),
                    vertical = lerp(6.dp, 4.dp, fraction)
                )
        ) {
            val itemHeight = lerp(58.dp, 46.dp, fraction)
            val slotWidth = maxWidth / tabs.size
            val selectedIndex = tabs.indexOfFirst { it.route == selectedRoute }
                .coerceAtLeast(0)

            // Sliding selection pill: moves smoothly between destinations.
            val indicatorOffset by animateDpAsState(
                targetValue = slotWidth * selectedIndex,
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "nav-indicator-offset"
            )
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(slotWidth)
                    .height(itemHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color(0x24FFFFFF))
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val selected = tab.route == selectedRoute
                    val tint by animateColorAsState(
                        targetValue = if (selected) {
                            Color(0xFFFFFFFF)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(200),
                        label = "nav-item-tint"
                    )
                    // STRICT vertical layout: icon above, label below —
                    // labels can never sit beside an icon and collide
                    // with a neighboring item. Each slot is equal-width
                    // (weight) and centers its content.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(percent = 50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier.size(lerp(22.dp, 20.dp, fraction))
                        )
                        // The label always stays below the icon. In the
                        // compact state it scales down and fades rather
                        // than disappearing sideways.
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = tint.copy(alpha = 1f - 0.45f * fraction),
                            fontSize = (12f - 2f * fraction).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.padding(top = lerp(4.dp, 2.dp, fraction))
                        )
                    }
                }
            }
        }
    }
}

/**
 * Classic layout mode: a traditional Material bottom bar. Same
 * destinations, same callbacks — presentation only.
 */
@Composable
fun ClassicNavBar(
    tabs: List<PillTab>,
    selectedRoute: String,
    onSelect: (PillTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        tabs.forEach { tab ->
            val selected = tab.route == selectedRoute
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.icon,
                        contentDescription = tab.label
                    )
                },
                label = { Text(text = tab.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}
