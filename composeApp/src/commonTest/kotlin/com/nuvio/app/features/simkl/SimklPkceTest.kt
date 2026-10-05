package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimklPkceTest {
    @Test
    fun `base64 url encoding is unpadded and url safe`() {
        assertEquals("", byteArrayOf().base64UrlWithoutPadding())
        assertEquals("Zg", "f".encodeToByteArray().base64UrlWithoutPadding())
        assertEquals("Zm8", "fo".encodeToByteArray().base64UrlWithoutPadding())
        assertEquals("Zm9v", "foo".encodeToByteArray().base64UrlWithoutPadding())
        assertEquals("-_8", byteArrayOf(0xfb.toByte(), 0xff.toByte()).base64UrlWithoutPadding())
    }

    @Test
    fun `pkce material uses compliant verifier and S256 challenge`() {
        val material = createSimklPkceMaterial(
            verifierEntropy = ByteArray(32) { it.toByte() },
            stateEntropy = ByteArray(32) { (it + 32).toByte() },
        )

        assertEquals(43, material.verifier.length)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", material.verifier)
        assertEquals("6oZqdX5MOLq_qBJ8vppAnT4fk6AP8UiP9zX8-Rev_9A", material.challenge)
        assertTrue(material.verifier.all { it.isLetterOrDigit() || it in "-._~" })
        assertFalse('=' in material.verifier)
        assertEquals(43, material.challenge.length)
        assertTrue(material.state.length >= 22)
    }

    @Test
    fun `oauth state comparison requires an exact value`() {
        assertTrue(constantTimeEquals("same-state", "same-state"))
        assertFalse(constantTimeEquals("same-state", "different-state"))
        assertFalse(constantTimeEquals("same-state", "same-state-extra"))
    }

    @Test
    fun `authorization URL uses the AUTH V2 endpoint and exact S256 method`() {
        val url = buildSimklAuthorizationUrl(
            clientId = "client id",
            redirectUri = "nuvio://auth/simkl",
            appName = "nuvio",
            appVersion = "1.2.3",
            material = SimklPkceMaterial("verifier", "challenge", "state"),
        )

        // Simkl rejects V2 clients on the V1 path with {"error":"unauthorized_client"}.
        assertTrue(url.startsWith("https://simkl.com/oauth2/authorize?"))
        assertFalse(url.startsWith("https://simkl.com/oauth/authorize?"))
        assertTrue("client_id=client+id" in url || "client_id=client%20id" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("redirect_uri=nuvio%3A%2F%2Fauth%2Fsimkl" in url)
    }

    @Test
    fun `authorization URL requests read and write scope`() {
        val url = buildSimklAuthorizationUrl(
            clientId = "client-id",
            redirectUri = "nuvio://auth/simkl",
            appName = "nuvio",
            appVersion = "1.2.3",
            material = SimklPkceMaterial("verifier", "challenge", "state"),
        )

        // Omitting scope yields a read-only token, which breaks scrobbling and
        // watched-status writes, so it has to be on the authorize URL.
        assertTrue("scope=media%3Aread+media%3Awrite" in url || "scope=media%3Aread%20media%3Awrite" in url)
        assertTrue("response_type=code" in url)
    }

    @Test
    fun `token form is url encoded and drops blank fields`() {
        val form = buildSimklTokenForm(
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to "client-id",
                // Both of these would corrupt a form body if left raw.
                "refresh_token" to "a+b/c=d",
                "redirect_uri" to "",
            ),
        )

        assertEquals(
            "grant_type=refresh_token&client_id=client-id&refresh_token=a%2Bb%2Fc%3Dd",
            form,
        )
    }

    @Test
    fun `token form encodes spaces either as plus or percent twenty`() {
        val form = buildSimklTokenForm(mapOf("scope" to "media:read media:write"))
        assertTrue(
            form == "scope=media%3Aread+media%3Awrite" ||
                form == "scope=media%3Aread%20media%3Awrite",
        )
    }

    @Test
    fun `callback parser rejects other routes and missing state`() {
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback("nuvio://auth/trakt?code=a&state=b", "nuvio://auth/simkl"),
        )
        assertIs<SimklAuthCallback.Invalid>(
            parseSimklAuthCallback("nuvio://auth/simkl?code=a", "nuvio://auth/simkl"),
        )
        assertEquals(
            SimklAuthCallback.AuthorizationCode(code = "a", state = "b"),
            parseSimklAuthCallback("nuvio://auth/simkl?code=a&state=b", "nuvio://auth/simkl"),
        )
    }

    @Test
    fun `callback parser validates the AUTH V2 issuer when present`() {
        assertEquals(
            SimklAuthCallback.AuthorizationCode(code = "a", state = "b"),
            parseSimklAuthCallback(
                "nuvio://auth/simkl?code=a&state=b&iss=https%3A%2F%2Fsimkl.com",
                "nuvio://auth/simkl",
            ),
        )
        // A response claiming to be from somewhere else must not be exchanged.
        assertIs<SimklAuthCallback.Invalid>(
            parseSimklAuthCallback(
                "nuvio://auth/simkl?code=a&state=b&iss=https%3A%2F%2Fsimkl.com.evil.test",
                "nuvio://auth/simkl",
            ),
        )
        // Absent iss is still accepted so nothing regresses.
        assertEquals(
            SimklAuthCallback.AuthorizationCode(code = "a", state = "b"),
            parseSimklAuthCallback("nuvio://auth/simkl?code=a&state=b", "nuvio://auth/simkl"),
        )
    }

    @Test
    fun `pending authorization expires after five minutes`() {
        assertFalse(isSimklAuthorizationExpired(1_000L, 301_000L))
        assertTrue(isSimklAuthorizationExpired(1_000L, 301_001L))
        assertTrue(isSimklAuthorizationExpired(null, 1_000L))
        assertTrue(isSimklAuthorizationExpired(2_000L, 1_000L))
    }
}
