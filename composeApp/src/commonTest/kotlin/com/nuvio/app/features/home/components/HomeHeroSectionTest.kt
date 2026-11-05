package com.nuvio.app.features.home.components

import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeHeroSectionTest {

    @Test
    fun `first and last indicators select adjacent pages across the loop`() {
        assertEquals(79, heroPageForItem(currentPage = 80, itemIndex = 7, itemCount = 8))
        assertEquals(88, heroPageForItem(currentPage = 87, itemIndex = 0, itemCount = 8))
    }

    @Test
    fun `indicators keep the current page when selecting the active title`() {
        assertEquals(83, heroPageForItem(currentPage = 83, itemIndex = 3, itemCount = 8))
    }

    @Test
    fun `indicators select the closest occurrence of a title`() {
        assertEquals(85, heroPageForItem(currentPage = 83, itemIndex = 5, itemCount = 8))
        assertEquals(81, heroPageForItem(currentPage = 83, itemIndex = 1, itemCount = 8))
    }

    @Test
    fun `content crossfade follows the reference curve`() {
        // The reference curve: a layer fades out through the first half of the
        // swipe away, holds fully transparent from the midpoint to the end of
        // the swipe, and the incoming layer fades in through its final half.
        // Outgoing and incoming content therefore never blend at partial
        // opacity while both are moving fastest.
        assertEquals(1f, heroContentAlpha(pageOffset = 0f))
        assertEquals(0.5f, heroContentAlpha(pageOffset = 0.25f))
        assertEquals(0f, heroContentAlpha(pageOffset = 0.5f))
        assertEquals(0f, heroContentAlpha(pageOffset = 0.75f))
        assertEquals(0f, heroContentAlpha(pageOffset = 1f))
        assertEquals(0.5f, heroContentAlpha(pageOffset = -0.25f))
        assertEquals(heroContentAlpha(pageOffset = 0.25f), heroContentAlpha(pageOffset = -0.25f), "curve must be symmetric")
    }

    @Test
    fun `prefetch covers the neighbours of the settled page`() {
        assertEquals(listOf(1), heroPrefetchItemIndices(settledPage = 0, itemCount = 4))
        assertEquals(listOf(3, 1), heroPrefetchItemIndices(settledPage = 2, itemCount = 4))
    }

    @Test
    fun `prefetch dedupes when the hero has only two items`() {
        val indices = heroPrefetchItemIndices(settledPage = 0, itemCount = 2)
        assertTrue(indices.size == 1, "two-item hero has one distinct neighbour, got $indices")
    }

    @Test
    fun `prefetch is a no-op for a single-item hero`() {
        assertTrue(heroPrefetchItemIndices(settledPage = 0, itemCount = 1).isEmpty())
    }

    @Test
    fun `prefetch never resolves a negative or out-of-range item`() {
        val indices = heroPrefetchItemIndices(settledPage = 0, itemCount = 3)
        assertTrue(indices.all { it in 0 until 3 }, "got $indices")
    }

    @Test
    fun `mobile hero height stays compact without continue watching`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 844f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(585f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `tablet hero height remains width driven even with viewport height`() {
        val layout = homeHeroLayout(
            maxWidthDp = 840f,
            viewportHeightDp = 1200f,
        )

        assertEquals(true, layout.isTablet)
        assertEquals(386.4f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `mobile hero stays width driven when continue watching is present`() {
        val viewportHeight = 844f
        val continueWatchingLayout = rememberContinueWatchingLayout(maxWidthDp = 390f)
        val continueWatchingHeight = continueWatchingSectionHeightEstimate(
            style = ContinueWatchingSectionStyle.Card,
            layout = continueWatchingLayout,
            basePosterWidthDp = 110,
        )
        val reserveHeight = continueWatchingHeroViewportReserveHeight(
            style = ContinueWatchingSectionStyle.Card,
            layout = continueWatchingLayout,
            basePosterWidthDp = 110,
        )
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = viewportHeight,
            mobileBelowSectionHeightHintDp = reserveHeight.value,
        )

        assertEquals(585f, layout.heroHeight.value, 0.001f)
        assertEquals(74.966095f, viewportHeight - layout.heroHeight.value - continueWatchingHeight.value, 0.001f)
    }

    @Test
    fun `mobile hero remains width driven in a short viewport`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 568f,
            mobileBelowSectionHeightHintDp = 300f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(585f, layout.heroHeight.value, 0.001f)
    }
}
