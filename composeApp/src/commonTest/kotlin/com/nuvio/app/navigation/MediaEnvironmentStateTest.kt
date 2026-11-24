package com.nuvio.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MediaEnvironmentStateTest {
    @Test
    fun streamBridgeIsDefaultAndKeepsItsExistingBackDispatcher() {
        val state = MediaEnvironmentState()
        assertEquals(MediaEnvironment.StreamBridge, state.active)
        assertNull(state.handleBack())
        assertSame(state, state.exit())
    }

    @Test
    fun bothEnvironmentsExitBackToStreamBridge() {
        for (environment in listOf(MediaEnvironment.Music, MediaEnvironment.YouTube)) {
            val opened = MediaEnvironmentState().open(environment)
            assertEquals(environment, opened.active)
            assertEquals(MediaEnvironmentState(), opened.exit())
            assertEquals(opened.exit(), opened.handleBack())
        }
    }

    @Test
    fun openingDoesNotNestOrSwitchDedicatedEnvironments() {
        val music = MediaEnvironmentState().open(MediaEnvironment.Music)
        assertSame(music, music.open(MediaEnvironment.YouTube))
        assertSame(music, music.open(MediaEnvironment.Music))
        assertSame(music, music.open(MediaEnvironment.StreamBridge))
        assertEquals(MediaEnvironment.YouTube, music.exit().open(MediaEnvironment.YouTube).active)
    }

    @Test
    fun openingStreamBridgeDoesNotCountAsExit() {
        val main = MediaEnvironmentState()
        assertSame(main, main.open(MediaEnvironment.StreamBridge))
    }

    @Test
    fun repeatedEntryAndExitAreIdempotent() {
        val opened = MediaEnvironmentState().open(MediaEnvironment.YouTube)
        repeat(100) {
            assertSame(opened, opened.open(MediaEnvironment.YouTube))
            assertSame(opened, opened.open(MediaEnvironment.Music))
        }
        val closed = opened.exit()
        repeat(100) { assertSame(closed, closed.exit()) }
    }

}
