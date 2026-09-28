package com.nuvio.app.features.cloudstream

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudStreamDiagnosticsTest {
    @AfterTest
    fun clearJournal() {
        CloudStreamDiagnostics.clear()
    }

    @Test
    fun failureRetainsStageAndRedactsProviderInput() {
        CloudStreamDiagnostics.error(
            CloudStreamDiagnosticStage.LOAD_LINKS,
            provider = "Provider",
            message = "cookie=secret https://example.test/stream?token=private",
        )

        val event = CloudStreamDiagnostics.events.value.single()
        assertEquals(CloudStreamDiagnosticLevel.ERROR, event.level)
        assertEquals(CloudStreamDiagnosticStage.LOAD_LINKS, event.stage)
        assertFalse("secret" in event.message)
        assertFalse("https://" in event.message)
        assertTrue(CloudStreamDiagnostics.latestFailureSummary()!!.contains("LOAD LINKS"))
    }

    @Test
    fun adjacentDuplicateEventsAreCollapsedAndClearRemovesThem() {
        repeat(2) {
            CloudStreamDiagnostics.warning(
                CloudStreamDiagnosticStage.PERSISTENCE,
                provider = "cache",
                message = "retained",
            )
        }
        assertEquals(1, CloudStreamDiagnostics.events.value.size)
        CloudStreamDiagnostics.clear()
        assertTrue(CloudStreamDiagnostics.events.value.isEmpty())
    }
}
