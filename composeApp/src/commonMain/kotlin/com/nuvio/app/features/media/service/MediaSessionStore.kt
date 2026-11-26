package com.nuvio.app.features.media.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Native implementations fail closed: no plaintext fallback or sensitive error logging. */
internal expect object MediaCredentialVault {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

@Serializable
internal class MediaAccountSession(
    val accountId: String,
    val displayName: String,
    val credential: String,
    val refreshCredential: String? = null,
    val expiresAtEpochMs: Long? = null,
    val channelId: String? = null,
) {
    init {
        require(accountId.isNotBlank() && credential.isNotBlank())
        require(expiresAtEpochMs == null || expiresAtEpochMs >= 0)
    }
    fun expired(nowEpochMs: Long): Boolean {
        require(nowEpochMs >= 0)
        return expiresAtEpochMs?.let { it <= nowEpochMs || it - nowEpochMs <= 30_000L } ?: false
    }
    override fun toString(): String = "MediaAccountSession(redacted)"
}

@Serializable
internal data class MediaAccountSummary(val accountId: String, val displayName: String, val channelId: String?)

@Serializable
private data class SessionPayload(val activeId: String? = null, val accounts: List<MediaAccountSession> = emptyList())

/** Stores explicit profile/service namespaces. There is no ambient profile that can change mid-request. */
internal class MediaSessionStore(
    private val ownerId: String,
    private val read: (String) -> String? = MediaCredentialVault::read,
    private val write: (String, String?) -> Unit = MediaCredentialVault::write,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(profileId: Int, service: MediaServiceId): String {
        require(profileId >= 0 && ownerId.isNotBlank() && ownerId.length <= 512)
        val owner = ownerId.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return "media.v1.$owner.$profileId.${service.name}"
    }
    private fun load(profileId: Int, service: MediaServiceId): SessionPayload =
        read(key(profileId, service))?.let { json.decodeFromString<SessionPayload>(it) } ?: SessionPayload()

    fun accounts(profileId: Int, service: MediaServiceId): List<MediaAccountSummary> =
        load(profileId, service).accounts.map { MediaAccountSummary(it.accountId, it.displayName, it.channelId) }

    fun active(profileId: Int, service: MediaServiceId): MediaAccountSession? {
        val payload = load(profileId, service)
        return payload.accounts.firstOrNull { it.accountId == payload.activeId }
    }

    fun save(profileId: Int, service: MediaServiceId, session: MediaAccountSession) {
        require(session.accountId.isNotBlank() && session.credential.isNotBlank())
        val previous = load(profileId, service)
        val accounts = previous.accounts.filterNot { it.accountId == session.accountId } + session
        require(accounts.size <= 20) { "Account limit reached" }
        write(key(profileId, service), json.encodeToString(SessionPayload(session.accountId, accounts)))
    }

    fun select(profileId: Int, service: MediaServiceId, accountId: String) {
        val payload = load(profileId, service)
        require(payload.accounts.any { it.accountId == accountId })
        write(key(profileId, service), json.encodeToString(payload.copy(activeId = accountId)))
    }

    fun remove(profileId: Int, service: MediaServiceId, accountId: String) {
        val payload = load(profileId, service)
        val remaining = payload.accounts.filterNot { it.accountId == accountId }
        val active = payload.activeId?.takeIf { id -> remaining.any { it.accountId == id } } ?: remaining.firstOrNull()?.accountId
        write(key(profileId, service), if (remaining.isEmpty()) null else json.encodeToString(SessionPayload(active, remaining)))
    }

    fun clearProfile(profileId: Int) = MediaServiceId.entries.forEach { write(key(profileId, it), null) }
}
