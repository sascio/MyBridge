package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountScopedProgressTest {
    @Test
    fun `remote progress restores for the same authenticated account`() {
        assertTrue(shouldRestoreNuvioRemoteProgress("user-a", "user-a"))
    }

    @Test
    fun `remote progress from another account is rejected`() {
        assertFalse(shouldRestoreNuvioRemoteProgress("user-a", "user-b"))
    }

    @Test
    fun `remote progress is not restored without a stable active account identity`() {
        assertFalse(shouldRestoreNuvioRemoteProgress("user-a", null))
        assertFalse(shouldRestoreNuvioRemoteProgress(null, "user-a"))
    }

    @Test
    fun `remote account matching ignores surrounding whitespace but remains exact`() {
        assertTrue(shouldRestoreNuvioRemoteProgress(" user-a ", "user-a"))
        assertFalse(shouldRestoreNuvioRemoteProgress("user-a", "user-ab"))
    }

    @Test
    fun `account change drops remote progress but preserves legitimate local entries`() {
        val entries = listOf(
            entry(id = "local-item", source = WatchProgressSourceLocal),
            entry(id = "remote-item", source = WatchProgressSourceNuvioSync),
        )

        val restored = restorableNuvioProgressEntries(
            entries = entries,
            cachedAccountId = "user-a",
            activeAccountId = "user-b",
        )

        assertEquals(listOf("local-item"), restored.map(WatchProgressEntry::parentMetaId))
    }

    @Test
    fun `late request remains current for the same authenticated account scope`() {
        val scope = NuvioAuthScope(
            stateKind = NuvioAuthStateKind.AUTHENTICATED,
            userId = "user-a",
            isAnonymous = false,
        )

        assertTrue(isNuvioAuthRequestCurrent(scope, scope.copy()))
    }

    @Test
    fun `late request is rejected after authenticated account changes`() {
        val request = NuvioAuthScope(
            stateKind = NuvioAuthStateKind.AUTHENTICATED,
            userId = "user-a",
            isAnonymous = false,
        )
        val current = request.copy(userId = "user-b")

        assertFalse(isNuvioAuthRequestCurrent(request, current))
        assertFalse(isNuvioAuthRequestCurrent(request, NuvioAuthScope(NuvioAuthStateKind.UNAUTHENTICATED)))
    }

    private fun entry(id: String, source: String) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = id,
        parentMetaType = "series",
        videoId = "video-$id",
        title = id,
        lastPositionMs = 1_000L,
        durationMs = 10_000L,
        lastUpdatedEpochMs = 1L,
        source = source,
    )
}
