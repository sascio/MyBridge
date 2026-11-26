package com.nuvio.app.features.media.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GoogleDeviceAuthTest {
    private val registration = GoogleOAuthRegistration("owner.apps.googleusercontent.com", "owner-secret")
    @Test fun challengeUsesGoogleEndpointAndOwnClient() = runTest {
        HttpClient(MockEngine { request ->
            assertEquals("oauth2.googleapis.com", request.url.host)
            assertEquals("/device/code", request.url.encodedPath)
            val fields = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            assertEquals(registration.clientId, fields["client_id"])
            assertTrue(request.url.parameters.isEmpty())
            respond("""{"device_code":"device","user_code":"ABCD","verification_url":"https://www.google.com/device","expires_in":600,"interval":5}""")
        }) { followRedirects = false }.use { client ->
            val result = GoogleDeviceAuthService(client, now = { 1000 }).begin(registration)
            assertEquals(601_000L, result.expiresAtEpochMs); assertEquals("ABCD", result.userCode)
            assertEquals("GoogleDeviceChallenge(redacted)", result.toString())
        }
    }
    @Test fun arbitraryVerificationOriginsAreRejected() = runTest {
        HttpClient(MockEngine { respond("""{"device_code":"secret","user_code":"code","verification_url":"https://evil.example/login","expires_in":600}""") }).use { client ->
            assertEquals(MediaFailure.InvalidResponse, assertFailsWith<MediaServiceException> { GoogleDeviceAuthService(client).begin(registration) }.reason)
        }
    }
    @Test fun pendingAndSlowDownPollAtServerPace() = runTest {
        var clock = 0L; var requests = 0; val waits = mutableListOf<Long>()
        HttpClient(MockEngine {
            when (++requests) {
                1 -> respond("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest)
                2 -> respond("""{"error":"slow_down"}""", HttpStatusCode.BadRequest)
                else -> respond("""{"access_token":"access","refresh_token":"refresh","token_type":"Bearer","expires_in":3600}""")
            }
        }).use { client ->
            val result = GoogleDeviceAuthService(client, now = { clock }, wait = { waits += it; clock += it })
                .awaitToken(registration, GoogleDeviceChallenge("device", "code", "https://www.google.com/device", 60_000, 5))
            assertEquals(listOf(5000L, 5000L, 10000L), waits); assertEquals("access", result.accessToken)
        }
    }
    @Test fun denialIsNotAnAuthenticatedSession() = runTest {
        HttpClient(MockEngine { respond("""{"error":"access_denied"}""", HttpStatusCode.BadRequest) }).use { client ->
            assertFailsWith<MediaServiceException> {
                GoogleDeviceAuthService(client, now = { 0 }, wait = {}).awaitToken(registration, GoogleDeviceChallenge("device", "code", "https://www.google.com/device", 60_000, 5))
            }
        }
    }
    @Test fun expiredChallengeNeverSendsRequest() = runTest {
        HttpClient(MockEngine { error("Must not send") }).use { client ->
            assertFailsWith<MediaServiceException> { GoogleDeviceAuthService(client, now = { 1000 }).awaitToken(registration, GoogleDeviceChallenge("device", "code", "https://www.google.com/device", 1000, 5)) }
        }
    }
    @Test fun cancellationPropagates() = runTest {
        HttpClient(MockEngine { error("Must not send") }).use { client ->
            assertFailsWith<CancellationException> { GoogleDeviceAuthService(client, now = { 0 }, wait = { throw CancellationException() }).awaitToken(registration, GoogleDeviceChallenge("device", "code", "https://www.google.com/device", 60_000, 5)) }
        }
    }
    @Test fun refreshUsesFormNotQueryAndRequiresBearer() = runTest {
        HttpClient(MockEngine { request ->
            assertTrue(request.url.parameters.isEmpty())
            val fields = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            assertEquals("refresh_token", fields["grant_type"]); assertEquals("refresh", fields["refresh_token"])
            respond("""{"access_token":"new","token_type":"Bearer","expires_in":3600}""")
        }).use { client ->
            assertEquals("new", GoogleDeviceAuthService(client).refresh(GoogleRefreshCredential(registration, "refresh")).accessToken)
        }
    }
}
