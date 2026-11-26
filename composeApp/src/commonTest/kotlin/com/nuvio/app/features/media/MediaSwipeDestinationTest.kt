package com.nuvio.app.features.media

import com.nuvio.app.navigation.MediaEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaSwipeDestinationTest {
    @Test
    fun directionsMatchPhysicalSwipeNotLayoutDirection() {
        assertEquals(MediaEnvironment.Music, mediaSwipeDestination(-72f, 0f, 72f))
        assertEquals(MediaEnvironment.YouTube, mediaSwipeDestination(72f, 0f, 72f))
    }

    @Test
    fun shortVerticalAndDiagonalMotionDoesNotNavigate() {
        assertNull(mediaSwipeDestination(71f, 0f, 72f))
        assertNull(mediaSwipeDestination(0f, 120f, 72f))
        assertNull(mediaSwipeDestination(90f, 60f, 72f))
        assertNull(mediaSwipeDestination(-90f, -90f, 72f))
    }

    @Test
    fun invalidMeasurementsDoNotNavigate() {
        assertNull(mediaSwipeDestination(Float.NaN, 0f, 72f))
        assertNull(mediaSwipeDestination(100f, Float.POSITIVE_INFINITY, 72f))
        assertNull(mediaSwipeDestination(100f, 0f, 0f))
    }
}
