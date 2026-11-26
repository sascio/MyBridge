package com.nuvio.app.features.media.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class GoogleSessionAccessTest {
    private val data = mutableMapOf<String, String>()
    private val store = MediaSessionStore("owner", data::get) { key, value -> if (value == null) data.remove(key) else data[key] = value }
    private val service = MediaServiceId.YouTube
    private val refresh = Json.encodeToString(GoogleRefreshCredential(GoogleOAuthRegistration("test.apps.googleusercontent.com", "test-secret"), "refresh"))
    private fun expired() = MediaAccountSession("google:a", "A", "expired", refresh, 1000)
    private val response = """{"access_token":"renewed","token_type":"Bearer","expires_in":3600}"""

    @Test fun freshTokenDoesNotSendRefreshRequest() = runTest {
        store.save(1, service, MediaAccountSession("google:a", "A", "fresh"))
        HttpClient(MockEngine { error("Must not send") }).use {
            assertEquals("fresh", GoogleSessionAccess(store, 1, service, "google:a", GoogleDeviceAuthService(it), now = { 0 }).token())
        }
    }
    @Test fun concurrentRequestsShareOneRefresh() = runTest {
        store.save(1, service, expired())
        var requests = 0
        HttpClient(MockEngine { requests++; respond(response) }).use {
            val access = GoogleSessionAccess(store, 1, service, "google:a", GoogleDeviceAuthService(it, now = { 0 }), now = { 0 })
            val first = async { access.token() }; val second = async { access.token() }
            assertEquals("renewed", first.await()); assertEquals("renewed", second.await()); assertEquals(1, requests)
            assertNotNull(store.active(1, service)?.refreshCredential)
        }
    }
    @Test fun logoutDuringRefreshCannotResurrectSession() = runTest {
        store.save(1, service, expired())
        HttpClient(MockEngine { store.remove(1, service, "google:a"); respond(response) }).use {
            assertFailsWith<MediaServiceException> { GoogleSessionAccess(store, 1, service, "google:a", GoogleDeviceAuthService(it), now = { 0 }).token() }
            assertNull(store.active(1, service))
        }
    }
    @Test fun accountSwitchDuringRefreshDoesNotSelectOldAccount() = runTest {
        store.save(1, service, expired())
        HttpClient(MockEngine { store.save(1, service, MediaAccountSession("google:b", "B", "other")); respond(response) }).use {
            assertFailsWith<MediaServiceException> { GoogleSessionAccess(store, 1, service, "google:a", GoogleDeviceAuthService(it), now = { 0 }).token() }
            assertEquals("google:b", store.active(1, service)?.accountId)
        }
    }
    @Test fun newGrantDuringRefreshCannotBeOverwritten() = runTest {
        store.save(1, service, expired())
        HttpClient(MockEngine { store.save(1, service, MediaAccountSession("google:a", "A", "new-grant")); respond(response) }).use {
            assertFailsWith<MediaServiceException> { GoogleSessionAccess(store, 1, service, "google:a", GoogleDeviceAuthService(it), now = { 0 }).token() }
            assertEquals("new-grant", store.active(1, service)?.credential)
        }
    }
}
