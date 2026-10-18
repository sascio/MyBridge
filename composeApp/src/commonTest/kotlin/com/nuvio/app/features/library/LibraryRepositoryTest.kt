package com.nuvio.app.features.library

import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.library.sync.toLibrarySyncKey
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRepositoryTest {

    @Test
    fun `display title uses exact type formatting`() {
        assertEquals("Movie", "movie".toLibraryDisplayTitle())
        assertEquals("Anime Series", "anime-series".toLibraryDisplayTitle())
        assertEquals("Tv", "tv".toLibraryDisplayTitle())
        assertEquals("Other", "".toLibraryDisplayTitle())
    }

    @Test
    fun `meta preview mapping preserves exact type and poster shape`() {
        val item = LibraryItem(
            id = "tt1",
            type = "anime-series",
            name = "Title",
            poster = "poster",
            banner = "banner",
            logo = "logo",
            description = "desc",
            releaseInfo = "2024",
            imdbRating = "8.4",
            genres = listOf("Drama"),
            posterShape = PosterShape.Poster,
            savedAtEpochMs = 1L,
        )

        val preview = item.toMetaPreview()

        assertEquals("anime-series", preview.type)
        assertEquals(PosterShape.Poster, preview.posterShape)
        assertEquals("banner", preview.banner)
    }

    @Test
    fun `metadata mappings keep imdb ids for Trakt-compatible sync`() {
        val previewItem = MetaPreview(
            id = "tt1234567",
            type = "movie",
            name = "Movie",
        ).toLibraryItem(savedAtEpochMs = 1L)
        val detailsItem = MetaDetails(
            id = "tt7654321",
            type = "series",
            name = "Show",
        ).toLibraryItem(savedAtEpochMs = 2L)
        val tmdbOnlyItem = MetaPreview(
            id = "tmdb:42",
            type = "movie",
            name = "TMDB",
        ).toLibraryItem(savedAtEpochMs = 3L)

        assertEquals("tt1234567", previewItem.imdbId)
        assertEquals("tt7654321", detailsItem.imdbId)
        assertEquals(null, tmdbOnlyItem.imdbId)
    }

    @Test
    fun `library tabs include local Nuvio library before Trakt tabs`() {
        val traktTab = TrackingLibraryTab(
            key = "trakt:watchlist",
            title = "Watchlist",
            providerId = TrackingProviderId.TRAKT,
            kind = TrackingLibraryTabKind.WATCHLIST,
        )

        val tabs = libraryTabsWithLocal(listOf(traktTab))

        assertEquals(listOf("local", "trakt:watchlist"), tabs.map { it.key })
        assertEquals("Nuvio Library", tabs.first().title)
    }

    @Test
    fun `library membership always includes local state before Trakt membership`() {
        val membership = libraryMembershipWithLocal(
            inLocal = true,
            providerMembership = mapOf("trakt:watchlist" to false),
        )

        assertEquals(
            mapOf(
                "local" to true,
                "trakt:watchlist" to false,
            ),
            membership,
        )
    }

    @Test
    fun `stored Nuvio library payload round trips its account identity`() {
        val state = LibraryLocalState()
        val load = state.beginProfileLoad(profileId = 1).snapshot
        val snapshot = assertNotNull(
            state.completeProfileLoad(
                token = load.token,
                activeProfileId = 1,
                items = listOf(libraryItem(id = "cached", savedAtEpochMs = 1L)),
            ),
        )

        val restored = LibraryStoragePayloadCodec.decode(
            LibraryStoragePayloadCodec.encode(snapshot, nuvioRemoteAccountId = "user-a"),
        )

        assertEquals("user-a", restored.nuvioRemoteAccountId)
        assertEquals(snapshot.items, restored.items)
    }

    @Test
    fun `same Nuvio account retains cached library when a refresh fails`() {
        val remote = libraryItem(id = "remote", savedAtEpochMs = 1L)
        val payload = StoredLibraryPayload(
            items = listOf(remote),
            deltaCursorEventId = 42L,
            deltaInitialized = true,
            nuvioRemoteAccountId = "user-a",
        )

        val scoped = scopeLibraryPayloadToNuvioAccount(payload, activeAccountId = "user-a")

        assertEquals(payload, scoped)
    }

    @Test
    fun `Nuvio account change removes old remote rows but keeps pending local additions`() {
        val remote = libraryItem(id = "private-to-a", savedAtEpochMs = 1L)
        val local = libraryItem(id = "local-pending", savedAtEpochMs = 2L)
        val payload = StoredLibraryPayload(
            items = listOf(remote, local),
            deltaCursorEventId = 42L,
            deltaInitialized = true,
            pendingUpsertKeys = listOf(local.toLibrarySyncKey()),
            pendingDeleteKeys = listOf(libraryItem(id = "deleted-on-a", savedAtEpochMs = 3L).toLibrarySyncKey()),
            nuvioRemoteAccountId = "user-a",
        )

        val scoped = scopeLibraryPayloadToNuvioAccount(payload, activeAccountId = "user-b")

        assertEquals(listOf(local), scoped.items)
        assertEquals(listOf(local.toLibrarySyncKey()), scoped.pendingUpsertKeys)
        assertTrue(scoped.pendingDeleteKeys.isEmpty())
        assertEquals(0L, scoped.deltaCursorEventId)
        assertFalse(scoped.deltaInitialized)
        assertEquals("user-b", scoped.nuvioRemoteAccountId)
    }

    @Test
    fun `legacy local library is kept and queued when first attached to a remote account`() {
        val local = libraryItem(id = "offline-save", savedAtEpochMs = 1L)
        val payload = StoredLibraryPayload(
            items = listOf(local),
            pendingDeleteKeys = listOf(libraryItem(id = "local-delete", savedAtEpochMs = 2L).toLibrarySyncKey()),
        )

        val scoped = scopeLibraryPayloadToNuvioAccount(payload, activeAccountId = "user-a")

        assertEquals(listOf(local), scoped.items)
        assertEquals(listOf(local.toLibrarySyncKey()), scoped.pendingUpsertKeys)
        assertTrue(scoped.pendingDeleteKeys.isEmpty())
        assertEquals("user-a", scoped.nuvioRemoteAccountId)
    }

    @Test
    fun `unknown auth hides remote cache in memory without changing persisted snapshot`() {
        val remote = libraryItem(id = "cached-remote", savedAtEpochMs = 1L)
        val local = libraryItem(id = "local-pending", savedAtEpochMs = 2L)
        val payload = StoredLibraryPayload(
            items = listOf(remote, local),
            deltaCursorEventId = 42L,
            deltaInitialized = true,
            pendingUpsertKeys = listOf(local.toLibrarySyncKey()),
            nuvioRemoteAccountId = "user-a",
        )

        val hidden = scopeLibraryPayloadToNuvioAccount(
            payload = payload,
            activeAccountId = null,
            accountScopeKnown = false,
        )

        assertEquals(listOf(local), hidden.items)
        assertEquals("user-a", hidden.nuvioRemoteAccountId)
        assertEquals(listOf(remote, local), payload.items)
        assertEquals(42L, payload.deltaCursorEventId)
    }

    @Test
    fun `legacy remote cache without an identity is not restored to another account`() {
        val remote = libraryItem(id = "legacy-remote", savedAtEpochMs = 1L)
        val local = libraryItem(id = "legacy-local-pending", savedAtEpochMs = 2L)
        val payload = StoredLibraryPayload(
            items = listOf(remote, local),
            deltaCursorEventId = 42L,
            deltaInitialized = true,
            pendingUpsertKeys = listOf(local.toLibrarySyncKey()),
        )

        val scoped = scopeLibraryPayloadToNuvioAccount(payload, activeAccountId = "user-b")

        assertEquals(listOf(local), scoped.items)
        assertEquals(listOf(local.toLibrarySyncKey()), scoped.pendingUpsertKeys)
        assertEquals(0L, scoped.deltaCursorEventId)
    }

    @Test
    fun `late library request is scoped to the exact auth state`() {
        val userA = AuthState.Authenticated("user-a", "a@example.test", isAnonymous = false)
        val userB = AuthState.Authenticated("user-b", "b@example.test", isAnonymous = false)

        assertTrue(libraryAuthScopeMatches(userA, userA.copy()))
        assertTrue(libraryAuthScopeMatches(userA, userA.copy(email = "new@example.test")))
        assertFalse(libraryAuthScopeMatches(userA, userB))
        assertFalse(libraryAuthScopeMatches(userA, AuthState.Unauthenticated))
        assertFalse(libraryAuthScopeMatches(userA, AuthState.Loading))
    }

    @Test
    fun `account transition invalidates a pending pull and preserves only unsynced local saves`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "old-account", savedAtEpochMs = 1L)),
            deltaCursorEventId = 12L,
            deltaInitialized = true,
        )
        val local = libraryItem(id = "local-pending", savedAtEpochMs = 2L)
        state.upsert(local)
        val stalePull = assertNotNull(state.markPullStarted(token))

        val transition = state.resetForNuvioAccountChange()

        assertFalse(state.isContentCurrent(stalePull))
        assertEquals(listOf(local), transition.snapshot.items)
        assertEquals(listOf(local.toLibrarySyncKey()), transition.snapshot.pendingUpsertKeys)
        assertTrue(transition.snapshot.pendingDeleteKeys.isEmpty())
        assertEquals(0L, transition.snapshot.deltaCursorEventId)
        assertFalse(transition.snapshot.deltaInitialized)
    }

    @Test
    fun `local snapshot remains attached to its profile after replacement`() {
        val state = LibraryLocalState()
        val profileOneLoad = state.beginProfileLoad(profileId = 1).snapshot
        assertFalse(profileOneLoad.hasLoaded)
        assertTrue(profileOneLoad.isLoading)

        val profileOneSnapshot = assertNotNull(
            state.completeProfileLoad(
                token = profileOneLoad.token,
                activeProfileId = 1,
                items = listOf(libraryItem(id = "profile-one", savedAtEpochMs = 1L)),
            ),
        )
        assertTrue(profileOneSnapshot.hasLoaded)
        assertFalse(profileOneSnapshot.isLoading)

        val profileTwoToken = state.beginProfileLoad(profileId = 2).snapshot.token
        state.completeProfileLoad(
            token = profileTwoToken,
            activeProfileId = 2,
            items = listOf(libraryItem(id = "profile-two", savedAtEpochMs = 2L)),
        )
        val profileTwoSnapshot = state.snapshot()

        assertEquals(1, profileOneSnapshot.token.profileId)
        assertEquals(listOf("profile-one"), profileOneSnapshot.items.map { it.id })
        assertEquals(2, profileTwoSnapshot.token.profileId)
        assertEquals(listOf("profile-two"), profileTwoSnapshot.items.map { it.id })
    }

    @Test
    fun `stale profile load cannot replace current profile items`() {
        val state = LibraryLocalState()
        val staleToken = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = staleToken,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "old", savedAtEpochMs = 1L)),
        )
        val staleSnapshot = state.snapshot()

        val currentToken = state.beginProfileLoad(profileId = 2).snapshot.token
        state.completeProfileLoad(
            token = currentToken,
            activeProfileId = 2,
            items = listOf(libraryItem(id = "current", savedAtEpochMs = 2L)),
        )

        val staleCommit = state.completeProfileLoad(
            token = staleToken,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "stale", savedAtEpochMs = 3L)),
        )

        assertNull(staleCommit)
        var stalePersistenceAccepted = false
        assertFalse(
            state.runIfContentCurrent(staleSnapshot) {
                stalePersistenceAccepted = true
            },
        )
        assertFalse(stalePersistenceAccepted)
        assertEquals(2, state.snapshot().token.profileId)
        assertEquals(listOf("current"), state.snapshot().items.map { it.id })
    }

    @Test
    fun `stale publication revision is rejected after same profile mutation`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "first", savedAtEpochMs = 1L)),
        )
        val staleSnapshot = state.snapshot()
        state.upsert(libraryItem(id = "second", savedAtEpochMs = 2L))

        var committed = false
        val accepted = state.runIfCurrent(staleSnapshot) {
            committed = true
        }

        assertFalse(accepted)
        assertFalse(committed)
        assertEquals(setOf("first", "second"), state.snapshot().items.map { it.id }.toSet())
    }

    @Test
    fun `pull lifecycle does not invalidate pending local push`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = emptyList(),
        )
        val localSnapshot = state.upsert(libraryItem(id = "local", savedAtEpochMs = 1L))

        state.markPullStarted(token)

        assertTrue(state.isContentCurrent(localSnapshot))
    }

    @Test
    fun `server pull preserves local items awaiting push`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
        )
        state.upsert(libraryItem(id = "local-new", savedAtEpochMs = 2L))
        val pullSnapshot = assertNotNull(state.markPullStarted(token))

        val result = assertNotNull(
            state.applyServerItems(
                pullSnapshot = pullSnapshot,
                serverItems = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
            ),
        )

        assertTrue(result.preservedLocalItems)
        assertEquals(
            setOf("remote-old", "local-new"),
            result.snapshot.items.map { it.id }.toSet(),
        )
    }

    @Test
    fun `server pull preserves a local mutation made while pulling`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
        )
        val pullSnapshot = assertNotNull(state.markPullStarted(token))
        state.upsert(libraryItem(id = "local-new", savedAtEpochMs = 2L))

        val result = assertNotNull(
            state.applyServerItems(
                pullSnapshot = pullSnapshot,
                serverItems = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
            ),
        )

        assertTrue(result.preservedLocalItems)
        assertEquals(
            setOf("remote-old", "local-new"),
            result.snapshot.items.map { it.id }.toSet(),
        )
    }

    @Test
    fun `server pull applies after local push completes`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
        )
        val localSnapshot = state.upsert(libraryItem(id = "local-new", savedAtEpochMs = 2L))
        state.markPushCompleted(localSnapshot)
        val pullSnapshot = assertNotNull(state.markPullStarted(token))

        val result = assertNotNull(
            state.applyServerItems(
                pullSnapshot = pullSnapshot,
                serverItems = listOf(libraryItem(id = "remote-new", savedAtEpochMs = 3L)),
            ),
        )

        assertFalse(result.preservedLocalItems)
        assertEquals(listOf("remote-new"), result.snapshot.items.map { it.id })
    }

    private fun libraryItem(id: String, savedAtEpochMs: Long): LibraryItem =
        LibraryItem(
            id = id,
            type = "movie",
            name = id,
            savedAtEpochMs = savedAtEpochMs,
        )
}
