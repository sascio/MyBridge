package com.nuvio.app.features.media.service

import kotlin.test.*

class MediaSessionStoreTest {
    private val storage = mutableMapOf<String, String>()
    private val store = MediaSessionStore("owner", storage::get) { key, value -> if (value == null) storage.remove(key) else storage[key] = value }
    private fun session(id: String) = MediaAccountSession(id, "Account $id", "credential-$id")
    @Test fun sessionsAreIndependentByProfileAndService() {
        store.save(1, MediaServiceId.YouTube, session("video"))
        store.save(1, MediaServiceId.YouTubeMusic, session("music"))
        store.save(2, MediaServiceId.YouTube, session("other"))
        assertEquals("video", store.active(1, MediaServiceId.YouTube)?.accountId)
        assertEquals("music", store.active(1, MediaServiceId.YouTubeMusic)?.accountId)
        assertEquals("other", store.active(2, MediaServiceId.YouTube)?.accountId)
    }
    @Test fun switchRemoveAndReloadPreserveOtherAccounts() {
        store.save(1, MediaServiceId.YouTube, session("a")); store.save(1, MediaServiceId.YouTube, session("b"))
        store.select(1, MediaServiceId.YouTube, "a")
        val reloaded = MediaSessionStore("owner", storage::get) { _, _ -> error("Read only") }
        assertEquals("a", reloaded.active(1, MediaServiceId.YouTube)?.accountId)
        store.remove(1, MediaServiceId.YouTube, "a")
        assertEquals("b", store.active(1, MediaServiceId.YouTube)?.accountId)
        store.remove(1, MediaServiceId.YouTube, "b"); assertNull(store.active(1, MediaServiceId.YouTube))
    }
    @Test fun unknownAccountCannotBeSelected() { assertFailsWith<IllegalArgumentException> { store.select(1, MediaServiceId.YouTube, "missing") } }
    @Test fun clearingProfileDoesNotClearOtherProfile() {
        store.save(1, MediaServiceId.YouTube, session("a")); store.save(2, MediaServiceId.YouTube, session("b"))
        store.clearProfile(1); assertNull(store.active(1, MediaServiceId.YouTube)); assertNotNull(store.active(2, MediaServiceId.YouTube))
    }
    @Test fun expiryHasSafetyMargin() { assertFalse(session("a").expired(10)); assertTrue(MediaAccountSession("id", "name", "token", expiresAtEpochMs = 60_000).expired(30_000)) }
    @Test fun invalidStoredPayloadDoesNotBecomeAnAuthenticatedSession() {
        store.save(1, MediaServiceId.YouTube, session("a"))
        storage[storage.keys.single()] = "broken"
        assertFails { store.active(1, MediaServiceId.YouTube) }
    }
    @Test fun ownerNamespacesPreventCrossAccountReuse() {
        store.save(1, MediaServiceId.YouTube, session("a"))
        val other = MediaSessionStore("other-owner", storage::get) { _, _ -> error("Read only") }
        assertNull(other.active(1, MediaServiceId.YouTube))
    }


    private fun track(id: String) = MediaEntry(MediaKey(MediaServiceId.WebDav, "https://example.org/music/$id.mp3"), MediaKind.Track, id)
    @Test fun queueSurvivesReloadAndTokenUpdate() {
        store.save(1, MediaServiceId.WebDav, session("a"))
        val queue = MediaQueue(listOf(track("one"), track("two")), 1, QueueRepeat.All)
        store.saveQueue(1, MediaServiceId.WebDav, "a", queue)
        store.save(1, MediaServiceId.WebDav, MediaAccountSession("a", "Updated", "new-token"))
        val reloaded = MediaSessionStore("owner", storage::get) { _, _ -> error("Read only") }
        assertEquals(queue, reloaded.queue(1, MediaServiceId.WebDav, "a"))
    }
    @Test fun queueIsIsolatedAndRemovedWithAccount() {
        store.save(1, MediaServiceId.WebDav, session("a"))
        store.saveQueue(1, MediaServiceId.WebDav, "a", MediaQueue(listOf(track("one")), 0))
        store.save(1, MediaServiceId.WebDav, session("b"))
        assertTrue(store.queue(1, MediaServiceId.WebDav, "b").entries.isEmpty())
        assertTrue(store.queue(2, MediaServiceId.WebDav, "a").entries.isEmpty())
        store.remove(1, MediaServiceId.WebDav, "a")
        assertTrue(store.queue(1, MediaServiceId.WebDav, "a").entries.isEmpty())
    }
    @Test fun staleAccountCannotOverwriteQueueAfterSwitchOrLogout() {
        store.save(1, MediaServiceId.WebDav, session("a"))
        store.save(1, MediaServiceId.WebDav, session("b"))
        assertFailsWith<IllegalArgumentException> { store.saveQueue(1, MediaServiceId.WebDav, "a", MediaQueue()) }
        store.remove(1, MediaServiceId.WebDav, "b")
        store.remove(1, MediaServiceId.WebDav, "a")
        assertFailsWith<IllegalArgumentException> { store.saveQueue(1, MediaServiceId.WebDav, "a", MediaQueue()) }
    }
    @Test fun queueRejectsCrossProviderAndOversizedCollections() {
        store.save(1, MediaServiceId.WebDav, session("a"))
        assertFailsWith<IllegalArgumentException> { store.saveQueue(1, MediaServiceId.WebDav, "a", MediaQueue(List(301) { track("$it") }, 0)) }
        val youtube = MediaEntry(MediaKey(MediaServiceId.YouTube, "video:id"), MediaKind.Video, "Video")
        assertFailsWith<IllegalArgumentException> { store.saveQueue(1, MediaServiceId.WebDav, "a", MediaQueue(listOf(youtube), 0)) }
    }

    @Test fun browsingPreferencesAndSearchesAreScopedAndPreserved() {
        store.save(1, MediaServiceId.YouTube, session("a"))
        val state = MediaBrowseState(YouTubePreferences(rememberTab = true, lastTab = YouTubeFeed.News)).searched("News")
        store.saveBrowsing(1, MediaServiceId.YouTube, "a", state)
        store.save(1, MediaServiceId.YouTube, session("a"))
        assertEquals(state, store.browsing(1, MediaServiceId.YouTube, "a"))
        assertTrue(store.browsing(1, MediaServiceId.YouTubeMusic, "a").searches.isEmpty())
        store.remove(1, MediaServiceId.YouTube, "a")
        assertTrue(store.browsing(1, MediaServiceId.YouTube, "a").searches.isEmpty())
    }
}
