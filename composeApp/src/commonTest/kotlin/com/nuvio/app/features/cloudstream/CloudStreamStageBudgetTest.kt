package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A provider may declare how long its stages need; the host decides how long
 * the user may be left waiting. This is the contract between the two.
 */
class CloudStreamStageBudgetTest {

    private val hostMax = 90_000L

    @Test
    fun `a provider that declares nothing gets the host maximum`() {
        assertEquals(hostMax, CloudStreamStageBudget.reconcile(null, hostMax))
    }

    @Test
    fun `a provider asking for less than the host maximum is honoured`() {
        assertEquals(20_000L, CloudStreamStageBudget.reconcile(20_000L, hostMax))
    }

    @Test
    fun `a provider cannot extend beyond the host maximum`() {
        assertEquals(hostMax, CloudStreamStageBudget.reconcile(10 * 60_000L, hostMax))
        assertEquals(hostMax, CloudStreamStageBudget.reconcile(Long.MAX_VALUE, hostMax))
    }

    @Test
    fun `an unusable declared budget is raised to the floor rather than obeyed`() {
        assertEquals(
            CloudStreamStageBudget.MIN_STAGE_TIMEOUT_MS,
            CloudStreamStageBudget.reconcile(1L, hostMax),
        )
    }

    @Test
    fun `zero and negative declarations fall back to the host maximum`() {
        assertEquals(hostMax, CloudStreamStageBudget.reconcile(0L, hostMax))
        assertEquals(hostMax, CloudStreamStageBudget.reconcile(-1L, hostMax))
    }

    @Test
    fun `the clamp never inverts when the host maximum is below the floor`() {
        // Must not throw, and must not hand out more than the host allows.
        assertEquals(1_000L, CloudStreamStageBudget.reconcile(null, 1_000L))
        assertEquals(1_000L, CloudStreamStageBudget.reconcile(50_000L, 1_000L))
        assertEquals(1_000L, CloudStreamStageBudget.reconcile(1L, 1_000L))
    }
}
