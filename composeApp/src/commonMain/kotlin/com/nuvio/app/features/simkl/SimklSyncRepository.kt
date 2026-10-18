package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingProfileStore
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SimklSyncRepository : TrackingProfileStore {
    override val providerId: TrackingProviderId = TrackingProviderId.SIMKL

    private val log = Logger.withTag("SimklSync")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshGate = SimklRefreshGate()
    private val snapshotMutex = Mutex()
    private val refreshRequestSequence = atomic(0L)
    private val engine = SimklSyncEngine(
        remote = SimklApiSyncRemote(),
        nowEpochMs = SimklPlatformClock::nowEpochMs,
    )

    private val _state = MutableStateFlow(SimklSyncUiState())
    val state: StateFlow<SimklSyncUiState> = _state.asStateFlow()

    private var hasLoaded = false
    private var profileGeneration = 0L
    private var loadedAuthGeneration = Long.MIN_VALUE
    private var loadedAccountId: Long? = null

    init {
        TrackingProviderRegistry.registerProfileStore(this)
    }

    fun ensureLoaded() {
        SimklAuthRepository.ensureLoaded()
        val authGeneration = SimklAuthRepository.accountGeneration
        val accountId = SimklAuthRepository.stableAccountId
        if (hasLoaded && loadedAuthGeneration == authGeneration && loadedAccountId == accountId) return
        profileGeneration += 1L
        hasLoaded = true
        loadedAuthGeneration = authGeneration
        loadedAccountId = accountId
        val snapshot = if (SimklAuthRepository.isAuthenticated.value && accountId != null) {
            SimklSyncStorage.loadPayload()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { payload ->
                    runCatching { json.decodeFromString<SimklSyncSnapshot>(payload) }
                        .onFailure { error -> log.w { "Failed to parse Simkl sync snapshot: ${error.message}" } }
                        .getOrNull()
                }
                ?.takeIf { it.accountId == accountId }
                ?: SimklSyncSnapshot(accountId = accountId)
        } else {
            SimklSyncSnapshot(accountId = accountId)
        }
        _state.value = SimklSyncUiState(snapshot = snapshot, hasLoaded = true)
        SimklWatchDiagnostics.logSnapshot(stage = "cache-load", snapshot = snapshot)
    }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        refreshAsync(intent, SimklRefreshOrigin.UNKNOWN)
    }

    internal fun refreshAsync(
        intent: TrackingRefreshIntent,
        origin: SimklRefreshOrigin,
    ) {
        scope.launch { refresh(intent, origin) }
    }

    suspend fun refresh(intent: TrackingRefreshIntent): Boolean =
        refresh(intent, SimklRefreshOrigin.MANUAL_SYNC)

    internal suspend fun refresh(
        intent: TrackingRefreshIntent,
        origin: SimklRefreshOrigin,
    ): Boolean {
        ensureLoaded()
        val requestId = refreshRequestSequence.incrementAndGet()
        val requestedGeneration = profileGeneration
        val requestedProfileId = ProfileRepository.activeProfileId
        val requestedAuthGeneration = SimklAuthRepository.accountGeneration
        val requestedAccountId = SimklAuthRepository.stableAccountId
        val before = _state.value
        SimklWatchDiagnostics.logRefreshRequest(
            requestId = requestId,
            origin = origin,
            intent = intent,
            profileId = requestedProfileId,
            profileGeneration = requestedGeneration,
            authenticated = SimklAuthRepository.isAuthenticated.value,
            snapshot = before.snapshot,
            errorMessage = before.errorMessage,
        )
        val outcome = refreshGate.runIfNeeded(
            profileGeneration = requestedGeneration,
            shouldRun = {
                val current = _state.value
                val authenticated = SimklAuthRepository.isAuthenticated.value
                val nowEpochMs = SimklPlatformClock.nowEpochMs()
                val eligible = isRequestCurrent(
                    profileGeneration = requestedGeneration,
                    profileId = requestedProfileId,
                    authGeneration = requestedAuthGeneration,
                    accountId = requestedAccountId,
                ) && authenticated &&
                    shouldRunSimklRefresh(
                        intent = intent,
                        lastCheckedAtEpochMs = current.snapshot.lastCheckedAtEpochMs,
                        nowEpochMs = nowEpochMs,
                        hasError = current.errorMessage != null,
                    )
                SimklWatchDiagnostics.logRefreshDecision(
                    requestId = requestId,
                    origin = origin,
                    intent = intent,
                    nowEpochMs = nowEpochMs,
                    lastCheckedAtEpochMs = current.snapshot.lastCheckedAtEpochMs,
                    authenticated = authenticated,
                    hasError = current.errorMessage != null,
                    eligible = eligible,
                )
                eligible
            },
        ) {
            refreshSnapshot(
                generation = requestedGeneration,
                profileId = requestedProfileId,
                authGeneration = requestedAuthGeneration,
                accountId = requestedAccountId,
            )
        }
        SimklWatchDiagnostics.logRefreshCompletion(
            requestId = requestId,
            origin = origin,
            intent = intent,
            outcome = outcome,
            before = before,
            after = _state.value,
        )
        val completed = _state.value
        return isRequestCurrent(
            profileGeneration = requestedGeneration,
            profileId = requestedProfileId,
            authGeneration = requestedAuthGeneration,
            accountId = requestedAccountId,
        ) && completed.hasLoaded && completed.errorMessage == null
    }

    private suspend fun refreshSnapshot(
        generation: Long,
        profileId: Int,
        authGeneration: Long,
        accountId: Long?,
    ) = snapshotMutex.withLock {
        val previous = _state.value
        _state.value = previous.copy(isLoading = true, errorMessage = null)

        val result = try {
            engine.synchronize(previous.snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Simkl sync failed: ${error.message}" }
            if (isRequestCurrent(generation, profileId, authGeneration, accountId)) {
                _state.value = previous.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = error.message ?: "Unable to sync Simkl",
                )
            }
            return@withLock
        }

        if (!isRequestCurrent(generation, profileId, authGeneration, accountId)) {
            return@withLock
        }
        SimklAuthRepository.synchronizeUserSettings(result.activities?.settings?.all)
        if (!isRequestCurrent(generation, profileId, authGeneration, accountId)) {
            return@withLock
        }
        val currentAccountId = SimklAuthRepository.stableAccountId
        val scopedResult = result.copy(accountId = currentAccountId ?: accountId)
        if (scopedResult.accountId != null) {
            SimklSyncStorage.savePayload(json.encodeToString(scopedResult))
        }
        _state.value = SimklSyncUiState(
            snapshot = scopedResult,
            hasLoaded = true,
        )
        SimklWatchDiagnostics.logSnapshot(stage = "network-commit", snapshot = result)
    }

    internal suspend fun commitPlaybackRemoval(sessionIds: Set<Long>) {
        if (sessionIds.isEmpty()) return
        ensureLoaded()
        val generation = profileGeneration
        val profileId = ProfileRepository.activeProfileId
        val authGeneration = SimklAuthRepository.accountGeneration
        val accountId = SimklAuthRepository.stableAccountId
        snapshotMutex.withLock {
            if (!isRequestCurrent(generation, profileId, authGeneration, accountId)) return@withLock
            val current = _state.value
            val updatedPlayback = current.snapshot.playback.filterNot { session -> session.id in sessionIds }
            if (updatedPlayback.size == current.snapshot.playback.size) return@withLock
            val updatedSnapshot = current.snapshot.copy(playback = updatedPlayback)
            if (updatedSnapshot.accountId != null) SimklSyncStorage.savePayload(json.encodeToString(updatedSnapshot))
            if (!isRequestCurrent(generation, profileId, authGeneration, accountId)) return@withLock
            _state.value = current.copy(snapshot = updatedSnapshot)
            SimklWatchDiagnostics.logSnapshot(stage = "playback-removal", snapshot = updatedSnapshot)
        }
    }

    internal suspend fun commitScrobble(result: SimklScrobbleResult) {
        ensureLoaded()
        val generation = profileGeneration
        val profileId = ProfileRepository.activeProfileId
        val authGeneration = SimklAuthRepository.accountGeneration
        val accountId = SimklAuthRepository.stableAccountId
        snapshotMutex.withLock {
            if (!isRequestCurrent(generation, profileId, authGeneration, accountId)) {
                return@withLock
            }
            val current = _state.value
            val snapshot = current.snapshot.applyScrobbleResult(
                result = result,
                committedAtEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (snapshot == current.snapshot) return@withLock
            if (snapshot.accountId != null) SimklSyncStorage.savePayload(json.encodeToString(snapshot))
            if (isRequestCurrent(generation, profileId, authGeneration, accountId)) {
                _state.value = current.copy(snapshot = snapshot)
                SimklWatchDiagnostics.logSnapshot(stage = "scrobble-commit", snapshot = snapshot)
            }
        }
    }

    internal suspend fun commitMutation(receipt: SimklMutationReceipt) {
        ensureLoaded()
        val generation = profileGeneration
        val profileId = ProfileRepository.activeProfileId
        val authGeneration = SimklAuthRepository.accountGeneration
        val accountId = SimklAuthRepository.stableAccountId
        snapshotMutex.withLock {
            if (!isRequestCurrent(generation, profileId, authGeneration, accountId)) {
                return@withLock
            }
            val current = _state.value
            val snapshot = current.snapshot.applyMutationReceipt(
                receipt = receipt,
                committedAtEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (snapshot == current.snapshot) return@withLock
            if (snapshot.accountId != null) SimklSyncStorage.savePayload(json.encodeToString(snapshot))
            if (isRequestCurrent(generation, profileId, authGeneration, accountId)) {
                _state.value = current.copy(snapshot = snapshot)
                SimklWatchDiagnostics.logSnapshot(stage = "mutation-commit", snapshot = snapshot)
            }
        }
    }

    private fun isRequestCurrent(
        profileGeneration: Long,
        profileId: Int,
        authGeneration: Long,
        accountId: Long?,
    ): Boolean = profileGeneration == this.profileGeneration &&
        profileId == ProfileRepository.activeProfileId &&
        authGeneration == SimklAuthRepository.accountGeneration &&
        SimklAuthRepository.isAuthenticated.value &&
        (accountId == null || accountId == SimklAuthRepository.stableAccountId)

    override fun onProfileChanged() {
        profileGeneration += 1L
        hasLoaded = false
        _state.value = SimklSyncUiState()
        ensureLoaded()
    }

    override fun clearLocalState() {
        profileGeneration += 1L
        hasLoaded = false
        loadedAuthGeneration = Long.MIN_VALUE
        loadedAccountId = null
        _state.value = SimklSyncUiState()
    }

    /**
     * Bumps the projection version to force downstream collectors (CW, library, watched badges)
     * to recompute their projections without re-fetching from the network.
     * Call this when a setting that affects projection output changes (e.g. anime ID preference).
     */
    fun invalidateProjections() {
        val current = _state.value
        _state.value = current.copy(projectionVersion = current.projectionVersion + 1L)
    }

    override fun removeStoredProfile(profileId: Int) {
        SimklSyncStorage.removeProfile(profileId)
    }
}
