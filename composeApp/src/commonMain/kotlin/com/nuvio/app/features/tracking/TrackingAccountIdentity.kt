package com.nuvio.app.features.tracking

/**
 * A stable provider-owned account identity. This must never contain an access token, refresh token,
 * API key, or other credential. A null identity means persisted private data cannot be safely reused.
 */
internal fun trackingAccountCacheMatches(cachedIdentity: String?, activeIdentity: String?): Boolean {
    val active = activeIdentity?.trim()?.takeIf(String::isNotEmpty) ?: return false
    return cachedIdentity?.trim() == active
}

/** A collision-free, storage-key-safe partition suffix for a stable account identity. */
internal fun trackingAccountCachePartitionKey(
    providerId: TrackingProviderId,
    accountIdentity: String?,
): String? {
    val identity = accountIdentity?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val encoded = identity.encodeToByteArray().joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return "${providerId.storageId}.$encoded"
}

/**
 * Checks both the Nuvio profile/request generation and the tracking account scope after an await.
 * When the caller has no provider identity, its generation still scopes that in-flight request.
 */
internal fun isTrackingAccountRequestCurrent(
    requestedProfileId: Int,
    activeProfileId: Int,
    requestedGeneration: Long,
    currentGeneration: Long,
    requestedAccountIdentity: String?,
    currentAccountIdentity: String?,
    isAuthenticated: Boolean,
): Boolean = requestedProfileId == activeProfileId &&
    requestedGeneration == currentGeneration &&
    isAuthenticated &&
    (requestedAccountIdentity == null || requestedAccountIdentity == currentAccountIdentity)
