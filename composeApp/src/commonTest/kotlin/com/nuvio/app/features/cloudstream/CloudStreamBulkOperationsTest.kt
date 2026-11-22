package com.nuvio.app.features.cloudstream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudStreamBulkOperationsTest {
    private fun extension(id: String) = CloudStreamExtension(
        plugin = CloudStreamPlugin(
            id = id, displayName = id, version = 1, description = null,
            authors = emptyList(), language = null, tvTypes = emptyList(), iconUrl = null,
            artifactUrl = null, repositoryUrl = null, fileSize = null, fileHash = null,
            apiVersion = 1, compatibility = CloudStreamCompatibility.COMPATIBLE,
            compatibilityReason = CloudStreamCompatibilityReason.NONE,
        ),
        repositoryUrl = "https://example.com/repo",
    )

    @Test fun bulkContinuesAfterFailuresAndReportsEachResult() = runBlocking {
        val calls = mutableListOf<String>()
        val states = mutableListOf<CloudStreamBulkStatus>()
        runCloudStreamBulk(CloudStreamBulkAction.INSTALL, listOf(extension("one"), extension("two"), extension("three")),
            perform = { item -> calls += item.id; if (item.id == "two") "not authorized" else null },
            report = { states.add(it) },
        )
        assertEquals(listOf("one", "two", "three"), calls)
        val result = states.last()
        assertEquals(3, result.completed)
        assertEquals(3, result.total)
        assertFalse(result.running)
        assertEquals(listOf(CloudStreamBulkFailure("two", "not authorized")), result.failures)
        assertTrue(states.any { it.current == "two" && it.running })
    }

    @Test fun cancellationIsNotMisreportedAsACompletedFailure(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            runCloudStreamBulk(CloudStreamBulkAction.UPDATE, listOf(extension("one")),
                perform = { throw CancellationException("cancelled") }, report = {})
        }
    }

    @Test fun duplicateOrSanitizedFilenameCollisionsAreAmbiguous() {
        assertFalse(cloudStreamIdentityAmbiguous("movie", listOf("movie", "series")))
        assertTrue(cloudStreamIdentityAmbiguous("movie", listOf("movie", "movie")))
        assertTrue(cloudStreamIdentityAmbiguous("a.b", listOf("a.b", "a_b")))
        assertTrue(cloudStreamIdentityAmbiguous("", listOf("")))
    }
}
