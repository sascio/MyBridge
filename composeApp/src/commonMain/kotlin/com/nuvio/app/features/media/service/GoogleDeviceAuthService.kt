package com.nuvio.app.features.media.service

import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Parameters
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Only an application owner's registered TV/limited-input OAuth client may be used.
 * No borrowed client registration, cookie capture, password collection or WebView login. */
@Serializable
internal class GoogleOAuthRegistration(val clientId: String, val clientSecret: String) {
    init { require(clientId.endsWith(".apps.googleusercontent.com") && clientId.length <= 512 && clientSecret.isNotBlank()) }
    override fun toString() = "GoogleOAuthRegistration(redacted)"
}

internal class GoogleDeviceChallenge(
    val deviceCode: String, val userCode: String, val verificationUrl: String,
    val expiresAtEpochMs: Long, val intervalSeconds: Long,
) {
    override fun toString() = "GoogleDeviceChallenge(redacted)"
}

@Serializable
internal class GoogleRefreshCredential(val registration: GoogleOAuthRegistration, val refreshToken: String) {
    override fun toString() = "GoogleRefreshCredential(redacted)"
}
internal class GoogleToken(val accessToken: String, val refreshToken: String?, val expiresAtEpochMs: Long) {
    override fun toString() = "GoogleToken(redacted)"
}

internal class GoogleDeviceAuthService(
    private val client: HttpClient,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private suspend fun form(path: String, values: Map<String, String>): Pair<Int, JsonObject> {
        try {
            val response = client.submitForm("https://oauth2.googleapis.com/$path", Parameters.build { values.forEach { (key, value) -> append(key, value) } })
            val text = readBoundedResponseBody(response.bodyAsChannel(), response.headers["Content-Length"]?.toLongOrNull(), 128 * 1024)
            return response.status.value to json.parseToJsonElement(text).jsonObject
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { throw MediaServiceException(MediaFailure.Network) }
    }
    suspend fun begin(registration: GoogleOAuthRegistration): GoogleDeviceChallenge {
        val (status, body) = form("device/code", mapOf("client_id" to registration.clientId, "scope" to "https://www.googleapis.com/auth/youtube"))
        if (status != 200) throw MediaServiceException(MediaFailure.Authentication)
        val verification = body.string("verification_url") ?: body.string("verification_uri") ?: invalid()
        val uri = try { Url(verification) } catch (_: Exception) { invalid() }
        if (uri.protocol.name != "https" || uri.host !in setOf("www.google.com", "google.com", "accounts.google.com") || !uri.user.isNullOrEmpty() || !uri.password.isNullOrEmpty()) invalid()
        val expires = body.long("expires_in")?.takeIf { it in 1..86_400 } ?: invalid()
        return GoogleDeviceChallenge(body.string("device_code") ?: invalid(), body.string("user_code") ?: invalid(), verification,
            now() + expires * 1000, (body.long("interval") ?: 5L).coerceIn(5, 60))
    }
    suspend fun awaitToken(registration: GoogleOAuthRegistration, challenge: GoogleDeviceChallenge): GoogleToken {
        var interval = challenge.intervalSeconds
        while (now() < challenge.expiresAtEpochMs) {
            wait(interval * 1000)
            if (now() >= challenge.expiresAtEpochMs) break
            val (status, body) = form("token", mapOf("client_id" to registration.clientId, "client_secret" to registration.clientSecret,
                "device_code" to challenge.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
            if (status == 200) return token(body)
            when (body.string("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = (interval + 5).coerceAtMost(120)
                "access_denied", "expired_token", "invalid_grant" -> throw MediaServiceException(MediaFailure.Authentication)
                else -> throw MediaServiceException(if (status == 429) MediaFailure.RateLimited else MediaFailure.Authentication)
            }
        }
        throw MediaServiceException(MediaFailure.Authentication)
    }
    suspend fun refresh(credential: GoogleRefreshCredential): GoogleToken {
        val (status, body) = form("token", mapOf("client_id" to credential.registration.clientId, "client_secret" to credential.registration.clientSecret,
            "refresh_token" to credential.refreshToken, "grant_type" to "refresh_token"))
        if (status != 200) throw MediaServiceException(if (status == 429) MediaFailure.RateLimited else MediaFailure.Authentication)
        return token(body)
    }
    private fun token(body: JsonObject): GoogleToken {
        val type = body.string("token_type") ?: invalid()
        if (!type.equals("Bearer", ignoreCase = true)) invalid()
        val access = body.string("access_token")?.takeIf { it.isNotBlank() } ?: invalid()
        val expires = body.long("expires_in")?.takeIf { it in 1..86_400 } ?: invalid()
        return GoogleToken(access, body.string("refresh_token"), now() + expires * 1000)
    }
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = string(key)?.toLongOrNull()
    private fun invalid(): Nothing = throw MediaServiceException(MediaFailure.InvalidResponse)
}

/** One coordinator per connected UI owner. Concurrent requests share a refresh; writes
 * use the captured account/profile/provider, never whichever profile later becomes active. */
internal class GoogleSessionAccess(
    private val store: MediaSessionStore, private val profileId: Int, private val service: MediaServiceId,
    private val accountId: String, private val auth: GoogleDeviceAuthService,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun token(): String = lock.withLock {
        val active = store.active(profileId, service)?.takeIf { it.accountId == accountId } ?: throw MediaServiceException(MediaFailure.Authentication)
        if (!active.expired(now())) return@withLock active.credential
        val refresh = active.refreshCredential?.let { json.decodeFromString<GoogleRefreshCredential>(it) } ?: throw MediaServiceException(MediaFailure.Authentication)
        val next = auth.refresh(refresh)
        // A switch/sign-out during the network request must not reactivate the old account.
        val current = store.active(profileId, service)
        if (current?.accountId != accountId || current.credential != active.credential || current.refreshCredential != active.refreshCredential)
            throw MediaServiceException(MediaFailure.Authentication)
        store.save(profileId, service, MediaAccountSession(active.accountId, active.displayName, next.accessToken,
            json.encodeToString(GoogleRefreshCredential(refresh.registration, next.refreshToken ?: refresh.refreshToken)), next.expiresAtEpochMs, active.channelId))
        next.accessToken
    }
}
