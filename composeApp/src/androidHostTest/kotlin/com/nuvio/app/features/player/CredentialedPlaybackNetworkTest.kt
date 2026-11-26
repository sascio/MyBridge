package com.nuvio.app.features.player

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class CredentialedPlaybackNetworkTest {
    @Test fun sameOriginRedirectRetainsAuthorization() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/audio"))
            server.enqueue(MockResponse().setBody("audio"))
            PlayerPlaybackNetworking.credentialedPlaybackHttpClient.newCall(
                Request.Builder().url(server.url("/start")).header("Authorization", "Basic test-only").build(),
            ).execute().use { assertEquals(200, it.code) }
            assertEquals("Basic test-only", server.takeRequest().getHeader("Authorization"))
            assertEquals("Basic test-only", server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun crossOriginRedirectCannotReintroduceAuthorization() {
        MockWebServer().use { source -> MockWebServer().use { destination ->
            destination.enqueue(MockResponse().setBody("public"))
            source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/other")))
            PlayerPlaybackNetworking.credentialedPlaybackHttpClient.newCall(
                Request.Builder().url(source.url("/start")).header("Authorization", "Basic test-only").build(),
            ).execute().use { assertEquals(200, it.code) }
            assertEquals("Basic test-only", source.takeRequest().getHeader("Authorization"))
            assertNull(destination.takeRequest().getHeader("Authorization"))
        } }
    }
    @Test fun credentialedClientUsesPlatformHostnameVerifierAndNoTlsDowngrade() {
        val client = PlayerPlaybackNetworking.credentialedPlaybackHttpClient
        assertFalse(client.followSslRedirects)
        assertSame(okhttp3.OkHttpClient().hostnameVerifier, client.hostnameVerifier)
    }
}
