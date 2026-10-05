package com.nuvio.app.features.simkl

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

internal const val SIMKL_AUTHORIZATION_TIMEOUT_MS = 5L * 60L * 1_000L

internal fun parseSimklAuthCallback(
    callbackUrl: String,
    redirectUri: String,
): SimklAuthCallback {
    if (callbackUrl != redirectUri && !callbackUrl.startsWith("$redirectUri?")) {
        return SimklAuthCallback.NotSimkl
    }
    val parsed = runCatching { Url(callbackUrl) }.getOrNull() ?: return SimklAuthCallback.Invalid
    // AUTH V2 returns `iss` alongside code and state. When it is present it must match
    // Simkl's issuer exactly; that is what tells a Simkl response apart from another
    // authorization server's, which matters here because StreamBridge also talks to
    // Trakt and MDBList. Absent `iss` is still accepted so nothing regresses.
    parsed.parameters["iss"]?.trim()?.takeIf(String::isNotBlank)?.let { issuer ->
        if (issuer != SIMKL_ISSUER) return SimklAuthCallback.Invalid
    }
    val code = parsed.parameters["code"].orEmpty().trim()
    val state = parsed.parameters["state"].orEmpty().trim()
    if (code.isBlank() || state.isBlank()) return SimklAuthCallback.Invalid
    return SimklAuthCallback.AuthorizationCode(code = code, state = state)
}

internal fun isSimklAuthorizationExpired(
    startedAtEpochMs: Long?,
    nowEpochMs: Long,
): Boolean = startedAtEpochMs == null ||
    nowEpochMs < startedAtEpochMs ||
    nowEpochMs - startedAtEpochMs > SIMKL_AUTHORIZATION_TIMEOUT_MS

internal fun constantTimeEquals(left: String, right: String): Boolean {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    val length = maxOf(leftBytes.size, rightBytes.size)
    var difference = leftBytes.size xor rightBytes.size
    for (index in 0 until length) {
        val leftByte = leftBytes.getOrElse(index) { 0 }.toInt()
        val rightByte = rightBytes.getOrElse(index) { 0 }.toInt()
        difference = difference or (leftByte xor rightByte)
    }
    return difference == 0
}

internal fun buildSimklAuthorizationUrl(
    clientId: String,
    redirectUri: String,
    appName: String,
    appVersion: String,
    material: SimklPkceMaterial,
    scope: String = SIMKL_AUTH_SCOPE,
): String = buildString {
    append(SIMKL_AUTHORIZE_URL)
    append("?response_type=code")
    append("&client_id=")
    append(clientId.encodeURLParameter())
    append("&redirect_uri=")
    append(redirectUri.encodeURLParameter())
    append("&code_challenge=")
    append(material.challenge.encodeURLParameter())
    append("&code_challenge_method=S256")
    append("&state=")
    append(material.state.encodeURLParameter())
    append("&scope=")
    append(scope.encodeURLParameter())
    append("&app-name=")
    append(appName.encodeURLParameter())
    append("&app-version=")
    append(appVersion.encodeURLParameter())
}

/**
 * Encodes an AUTH V2 token-endpoint body. Simkl's `/oauth2/token` is a standard
 * OAuth 2.0 endpoint and takes `application/x-www-form-urlencoded`, not the JSON
 * body V1 used. Blank values are dropped so optional fields (e.g. redirect_uri on
 * a refresh) never appear as empty parameters.
 */
internal fun buildSimklTokenForm(fields: Map<String, String>): String =
    fields.entries
        .filter { (_, value) -> value.isNotBlank() }
        .joinToString("&") { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
