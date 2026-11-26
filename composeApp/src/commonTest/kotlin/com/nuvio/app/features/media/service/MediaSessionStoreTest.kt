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

}
