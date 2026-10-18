package com.nuvio.app.features.library

import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.library.sync.LibrarySyncKey
import com.nuvio.app.features.library.sync.toLibrarySyncKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class StoredLibraryPayload(
    val items: List<LibraryItem> = emptyList(),
    val deltaCursorEventId: Long = 0L,
    val deltaInitialized: Boolean = false,
    val pendingUpsertKeys: List<LibrarySyncKey> = emptyList(),
    val pendingDeleteKeys: List<LibrarySyncKey> = emptyList(),
    val nuvioRemoteAccountId: String? = null,
)

/**
 * Keeps account-owned Nuvio snapshots private while retaining unsynced local edits. A legacy
 * payload with no account/cursor is treated as local-only; once attached to an account its items
 * are made pending so the first account snapshot cannot silently discard them.
 */
internal fun scopeLibraryPayloadToNuvioAccount(
    payload: StoredLibraryPayload,
    activeAccountId: String?,
    accountScopeKnown: Boolean = true,
): StoredLibraryPayload {
    val active = activeAccountId?.trim()?.takeIf(String::isNotEmpty)
    val cached = payload.nuvioRemoteAccountId?.trim()?.takeIf(String::isNotEmpty)
    val hasRemoteState = cached != null || payload.deltaInitialized || payload.deltaCursorEventId > 0L

    if (!accountScopeKnown) {
        return payload.retainPendingLocalItems(remoteAccountId = cached)
    }

    val isSameAccount = (cached != null && active == cached) || (cached == null && !hasRemoteState)
    if (isSameAccount) {
        val isLegacyLocalSnapshot = cached == null && !hasRemoteState && active != null
        return payload.copy(
            nuvioRemoteAccountId = active,
            pendingUpsertKeys = if (isLegacyLocalSnapshot) {
                (payload.pendingUpsertKeys + payload.items.map { it.toLibrarySyncKey() }).distinctBy { key ->
                    libraryItemKey(key.contentId, key.contentType)
                }
            } else {
                payload.pendingUpsertKeys
            },
            pendingDeleteKeys = if (isLegacyLocalSnapshot) emptyList() else payload.pendingDeleteKeys,
        )
    }

    return payload.retainPendingLocalItems(remoteAccountId = active)
}

private fun StoredLibraryPayload.retainPendingLocalItems(remoteAccountId: String?): StoredLibraryPayload {
    val pendingKeys = pendingUpsertKeys
        .distinctBy { key -> libraryItemKey(key.contentId, key.contentType) }
        .associateBy { key -> libraryItemKey(key.contentId, key.contentType) }
    val localItems = items
        .filter { item -> libraryItemKey(item.id, item.type) in pendingKeys }
        .distinctBy { item -> libraryItemKey(item.id, item.type) }
    val localKeys = localItems
        .map { item -> libraryItemKey(item.id, item.type) }
        .toSet()
    return copy(
        items = localItems,
        deltaCursorEventId = 0L,
        deltaInitialized = false,
        pendingUpsertKeys = pendingKeys.filterKeys { key -> key in localKeys }.values.toList(),
        pendingDeleteKeys = emptyList(),
        nuvioRemoteAccountId = remoteAccountId,
    )
}

internal fun libraryAuthScopeMatches(requested: AuthState, current: AuthState): Boolean {
    if (requested == AuthState.Loading || current == AuthState.Loading) return false
    if (requested is AuthState.Authenticated && current is AuthState.Authenticated) {
        return requested.userId == current.userId && requested.isAnonymous == current.isAnonymous
    }
    return requested == current
}

internal object LibraryStoragePayloadCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(payload: String): StoredLibraryPayload =
        runCatching {
            json.decodeFromString<StoredLibraryPayload>(payload)
        }.getOrDefault(StoredLibraryPayload())

    fun encode(
        snapshot: LibraryLocalSnapshot,
        nuvioRemoteAccountId: String? = null,
    ): String =
        json.encodeToString(
            StoredLibraryPayload(
                items = snapshot.items.sortedByDescending(LibraryItem::savedAtEpochMs),
                deltaCursorEventId = snapshot.deltaCursorEventId,
                deltaInitialized = snapshot.deltaInitialized,
                pendingUpsertKeys = snapshot.pendingUpsertKeys,
                pendingDeleteKeys = snapshot.pendingDeleteKeys,
                nuvioRemoteAccountId = nuvioRemoteAccountId,
            ),
        )
}
