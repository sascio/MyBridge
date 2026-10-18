package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.trackingAccountCachePartitionKey
import platform.Foundation.NSUserDefaults

internal actual object TraktLibraryStorage {
    private const val payloadKey = "trakt_library_payload"

    actual fun loadPayload(accountIdentity: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(scopedPayloadKey(accountIdentity)))

    actual fun savePayload(accountIdentity: String, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(scopedPayloadKey(accountIdentity)))
    }

    private fun scopedPayloadKey(accountIdentity: String): String =
        "$payloadKey.${requireNotNull(trackingAccountCachePartitionKey(TrackingProviderId.TRAKT, accountIdentity))}"
}
