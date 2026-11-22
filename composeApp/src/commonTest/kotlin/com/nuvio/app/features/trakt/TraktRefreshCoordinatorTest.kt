package com.nuvio.app.features.trakt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TraktRefreshCoordinatorTest {
    @Test
    fun `automatic refreshes do not queue and manual refresh waits for running work`() = runTest {
        val coordinator = TraktRefreshCoordinator(backgroundScope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val first = async {
            coordinator.run(false) {
                requests++
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        val automatic = async { coordinator.run(false) { requests++ } }
        val manual = async { coordinator.run(true) { requests++ } }
        val membership = async { coordinator.run(waitForOngoing = true) { requests++ } }
        runCurrent()
        assertTrue(automatic.isCompleted)
        assertFalse(manual.isCompleted)
        assertFalse(membership.isCompleted)
        assertEquals(1, requests)
        release.complete(Unit)
        first.await()
        manual.await()
        membership.await()
        coordinator.run(false) { requests++ }
        assertEquals(2, requests)
    }
}
