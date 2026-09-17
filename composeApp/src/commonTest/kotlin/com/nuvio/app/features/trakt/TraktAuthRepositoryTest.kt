package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TraktAuthRepositoryTest {

    @Test
    fun `HTTP 400 permanently invalidates a rejected refresh token`() {
        assertEquals(
            TraktTokenRefreshResponseAction.INVALIDATE,
            traktTokenRefreshResponseAction(400),
        )
    }

    @Test
    fun `successful token responses are accepted`() {
        assertEquals(
            TraktTokenRefreshResponseAction.ACCEPT,
            traktTokenRefreshResponseAction(200),
        )
        assertEquals(
            TraktTokenRefreshResponseAction.ACCEPT,
            traktTokenRefreshResponseAction(201),
        )
    }

    @Test
    fun `non-400 failures preserve credentials for a later attempt`() {
        listOf(401, 429, 500, 503).forEach { status ->
            assertEquals(
                TraktTokenRefreshResponseAction.TRANSIENT_FAILURE,
                traktTokenRefreshResponseAction(status),
            )
        }
    }

    // --- Device authorization polling state machine -------------------------
    // Trakt signals device-flow state purely through HTTP status codes.

    @Test
    fun `a successful device poll accepts the issued token`() {
        listOf(200, 201).forEach { status ->
            assertEquals(TraktDevicePollAction.ACCEPT, traktDevicePollAction(status))
        }
    }

    @Test
    fun `HTTP 400 means the user has not authorized yet and polling continues`() {
        assertEquals(TraktDevicePollAction.PENDING, traktDevicePollAction(400))
    }

    @Test
    fun `HTTP 429 asks the client to slow down rather than give up`() {
        assertEquals(TraktDevicePollAction.SLOW_DOWN, traktDevicePollAction(429))
    }

    @Test
    fun `HTTP 418 reports an explicit user denial`() {
        assertEquals(TraktDevicePollAction.DENIED, traktDevicePollAction(418))
    }

    @Test
    fun `HTTP 410 reports an expired device code`() {
        assertEquals(TraktDevicePollAction.EXPIRED, traktDevicePollAction(410))
    }

    @Test
    fun `unusable device codes stop the polling loop`() {
        // 404 = invalid device code, 409 = code already exchanged.
        listOf(404, 409).forEach { status ->
            assertEquals(TraktDevicePollAction.FAILED, traktDevicePollAction(status))
        }
    }

    @Test
    fun `server errors keep polling until the device code itself expires`() {
        listOf(500, 502, 503).forEach { status ->
            assertEquals(TraktDevicePollAction.PENDING, traktDevicePollAction(status))
        }
    }

    @Test
    fun `denial and expiry are distinguishable outcomes`() {
        // Regression guard: both used to collapse into one generic failure because
        // the poll ran through an HTTP helper that threw on every non-2xx response.
        assertNotEquals(
            traktDevicePollAction(418),
            traktDevicePollAction(410),
        )
    }

    @Test
    fun `every terminal poll outcome is distinct from a retryable one`() {
        val retryable = setOf(TraktDevicePollAction.PENDING, TraktDevicePollAction.SLOW_DOWN)
        listOf(418, 410, 404, 409).forEach { status ->
            assertTrue(
                traktDevicePollAction(status) !in retryable,
                "status $status must terminate polling",
            )
        }
    }
}
