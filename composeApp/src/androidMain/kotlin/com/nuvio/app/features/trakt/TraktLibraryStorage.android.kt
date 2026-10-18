package com.nuvio.app.features.trakt

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.trackingAccountCachePartitionKey

internal actual object TraktLibraryStorage {
    private const val preferencesName = "nuvio_trakt_library"
    private const val payloadKey = "trakt_library_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(accountIdentity: String): String? =
        preferences?.getString(ProfileScopedKey.of(scopedPayloadKey(accountIdentity)), null)

    actual fun savePayload(accountIdentity: String, payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(scopedPayloadKey(accountIdentity)), payload)
            ?.apply()
    }

    private fun scopedPayloadKey(accountIdentity: String): String =
        "$payloadKey.${requireNotNull(trackingAccountCachePartitionKey(TrackingProviderId.TRAKT, accountIdentity))}"
}
