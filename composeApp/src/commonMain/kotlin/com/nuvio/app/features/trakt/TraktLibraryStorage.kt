package com.nuvio.app.features.trakt

internal expect object TraktLibraryStorage {
    fun loadPayload(accountIdentity: String): String?
    fun savePayload(accountIdentity: String, payload: String)
}
