package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LightweightMetaOutcomeTest {

    @Test
    fun notFoundOnEveryAddonIsUnavailableNotAFailure() {
        // Addons answering 404 do not have the title. That must not be shown as a genre failure.
        assertEquals(MetaLookupOutcome.Unavailable, lightweightOutcomeWithoutMeta(throttledUntil = null, sawTransientFailure = false))
    }

    @Test
    fun transientFailureIsRetryableFailure() {
        // Timeouts, network errors, 5xx and unparseable bodies stay eligible for a later retry.
        assertEquals(MetaLookupOutcome.Failed, lightweightOutcomeWithoutMeta(throttledUntil = null, sawTransientFailure = true))
    }

    @Test
    fun rateLimitWinsOverFailureAndIsRetriedLater() {
        val until = 123_456L
        assertIs<MetaLookupOutcome.Throttled>(lightweightOutcomeWithoutMeta(throttledUntil = until, sawTransientFailure = false))
        val throttled = lightweightOutcomeWithoutMeta(throttledUntil = until, sawTransientFailure = true)
        assertEquals(until, (throttled as MetaLookupOutcome.Throttled).retryAtEpochMs)
    }
}
