package com.nuvio.app.features.settings

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.mdblist.MdbListTracker
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SimklSyncRepository
import com.nuvio.app.features.simkl.toProfileInsightsWatchedItems
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingLibraryProvider
import com.nuvio.app.features.tracking.TrackingProgressProvider
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingWatchedProvider
import com.nuvio.app.features.watched.WatchedInsightsSourceSnapshot
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watched.WatchedClock
import com.nuvio.app.features.wetrakr.WeTrakrTracker
import com.nuvio.app.features.wetrakr.toProfileInsightsWatchedItems
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ProfileInsightsRefreshResult(
    val attemptedCapabilityCount: Int = 0,
    val successfulCapabilityCount: Int = 0,
    val nuvioFullSnapshotSucceeded: Boolean = false,
) {
    val hasAnySuccess: Boolean get() = successfulCapabilityCount > 0
}

/**
 * Reads all authenticated profile-scoped sources without changing the user's selected Library,
 * Watched, or Progress source. Provider repositories remain the owners of their caches and policy.
 */
internal object ProfileInsightsSourcesRepository {
    private const val WATCHED_PAGE_SIZE = 900
    private const val AUTOMATIC_REFRESH_COOLDOWN_MS = 15L * 60L * 1_000L
    private const val CAPABILITY_LIBRARY = "library"
    private const val CAPABILITY_WATCHED = "watched"
    private const val CAPABILITY_PROGRESS = "progress"

    private val log = Logger.withTag("ProfileInsightsSources")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bindingLock = SynchronizedObject()
    private val refreshMutex = Mutex()
    private val watchedCacheLock = SynchronizedObject()
    private val _state = MutableStateFlow(ProfileInsightsSourcesState())
    val state: StateFlow<ProfileInsightsSourcesState> = _state.asStateFlow()

    private var boundProfileId: Int? = null
    private var bindingGeneration = 0L
    private var observationJob: Job? = null
    private val watchedCacheByProfile = mutableMapOf<Int, MutableMap<TrackingProviderId, CachedWatchedItems>>()
    private val lastAutomaticAttemptByProfile = mutableMapOf<Int, Long>()

    private data class CachedWatchedItems(
        val items: List<WatchedItem>,
        val fullyWatchedSeriesKeys: Set<String>,
        val refreshedAtEpochMs: Long,
        val accountGeneration: Long,
        val accountIdentity: String?,
    )

    fun bind(profileId: Int) {
        ensureTrackingProvidersRegistered()
        val newBinding = synchronized(bindingLock) {
            if (boundProfileId == profileId && observationJob?.isActive == true) return
            if (boundProfileId != profileId) {
                bindingGeneration += 1L
                boundProfileId = profileId
                observationJob?.cancel()
                _state.value = ProfileInsightsSourcesState(profileId = profileId)
            } else if (observationJob?.isActive != true) {
                bindingGeneration += 1L
                observationJob?.cancel()
            }
            bindingGeneration
        }
        observationJob = scope.launch {
            reloadCachedSources(profileId, newBinding)
            observeSourceChanges(profileId, newBinding)
        }
    }

    fun unbind(profileId: Int) {
        val invalidated = synchronized(bindingLock) {
            if (boundProfileId != profileId) return
            observationJob?.cancel()
            observationJob = null
            boundProfileId = null
            bindingGeneration += 1L
            true
        }
        if (invalidated) {
            _state.update { current ->
                if (current.profileId == profileId) current.copy(isRefreshing = false) else current
            }
        }
    }

    suspend fun refresh(
        profileId: Int,
        intent: TrackingRefreshIntent,
        forceNuvioSnapshot: Boolean,
    ): ProfileInsightsRefreshResult = refreshMutex.withLock {
        if (boundProfileId != profileId || observationJob?.isActive != true) bind(profileId)
        val requestGeneration = synchronized(bindingLock) { bindingGeneration }
        if (ProfileRepository.activeProfileId != profileId || !isBindingCurrent(profileId, requestGeneration)) {
            return@withLock ProfileInsightsRefreshResult()
        }
        val now = WatchedClock.nowEpochMs()
        if (intent == TrackingRefreshIntent.AUTOMATIC) {
            val lastAttempt = synchronized(bindingLock) { lastAutomaticAttemptByProfile[profileId] }
            if (!shouldRefreshProfileInsightsCache(
                    lastSuccessAtEpochMs = lastAttempt,
                    nowEpochMs = now,
                    maxAgeMs = AUTOMATIC_REFRESH_COOLDOWN_MS,
                    userInitiated = false,
                )
            ) {
                return@withLock ProfileInsightsRefreshResult()
            }
            synchronized(bindingLock) { lastAutomaticAttemptByProfile[profileId] = now }
        }

        markConnectedCapabilitiesRefreshing(profileId, requestGeneration)
        try {
            val results = coroutineScope {
                val nuvio = async {
                    refreshSafely(profileId, requestGeneration, PROFILE_INSIGHTS_NUVIO_SOURCE_ID, "Nuvio", 3) {
                        refreshNuvio(profileId, requestGeneration, forceNuvioSnapshot)
                    }
                }
                val providers = TrackingProviderId.entries.map { providerId ->
                    async {
                        refreshSafely(profileId, requestGeneration, providerId.storageId, providerId.storageId, 3) {
                            refreshProvider(profileId, requestGeneration, providerId, intent)
                        }
                    }
                }
                (listOf(nuvio) + providers).awaitAll()
            }
            val attempted = results.sumOf(ProfileInsightsRefreshResult::attemptedCapabilityCount)
            val successful = results.sumOf(ProfileInsightsRefreshResult::successfulCapabilityCount)
            val nuvioSnapshotSucceeded = results.firstOrNull()?.nuvioFullSnapshotSucceeded == true
            val completedAt = WatchedClock.nowEpochMs()
            if (isBindingCurrent(profileId, requestGeneration)) {
                _state.update { current ->
                    if (current.profileId != profileId) current else current.copy(
                        isRefreshing = false,
                        lastAttemptAtEpochMs = completedAt,
                        lastSuccessAtEpochMs = completedAt.takeIf { successful > 0 } ?: current.lastSuccessAtEpochMs,
                    )
                }
                reloadCachedSources(profileId, requestGeneration)
            }
            ProfileInsightsRefreshResult(
                attemptedCapabilityCount = attempted,
                successfulCapabilityCount = successful,
                nuvioFullSnapshotSucceeded = forceNuvioSnapshot && nuvioSnapshotSucceeded,
            )
        } catch (error: CancellationException) {
            markRefreshFinished(profileId, requestGeneration, WatchedClock.nowEpochMs(), succeeded = false)
            throw error
        } catch (error: Exception) {
            log.e(error) { "Profile source refresh failed for profile=$profileId" }
            markRefreshFinished(profileId, requestGeneration, WatchedClock.nowEpochMs(), succeeded = false)
            ProfileInsightsRefreshResult()
        }
    }

    private suspend fun refreshSafely(
        profileId: Int,
        requestGeneration: Long,
        sourceId: String,
        label: String,
        attemptedCapabilities: Int,
        block: suspend () -> ProfileInsightsRefreshResult,
    ): ProfileInsightsRefreshResult = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error) { "$label Profile source refresh failed" }
        markSourceRefreshFailed(profileId, requestGeneration, sourceId, error.message)
        ProfileInsightsRefreshResult(attemptedCapabilityCount = attemptedCapabilities)
    }

    private fun markSourceRefreshFailed(
        profileId: Int,
        requestGeneration: Long,
        sourceId: String,
        errorMessage: String?,
    ) {
        val source = currentSource(profileId, sourceId) ?: return
        if (!source.connected) return
        fun fail(capability: ProfileInsightsCapabilityState) =
            if (capability.status == ProfileInsightsFetchStatus.REFRESHING) {
                failedStatus(capability.itemCount, errorMessage)
            } else capability
        updateSource(profileId, requestGeneration, source.copy(
            library = fail(source.library),
            watched = fail(source.watched),
            progress = fail(source.progress),
        ))
    }

    private suspend fun refreshNuvio(
        profileId: Int,
        requestGeneration: Long,
        forceSnapshot: Boolean,
    ): ProfileInsightsRefreshResult {
        if (!isBindingCurrent(profileId, requestGeneration)) return ProfileInsightsRefreshResult()
        val authState = AuthRepository.state.value
        val hasRemoteAccount = authState is AuthState.Authenticated && !authState.isAnonymous
        val before = currentSource(profileId, PROFILE_INSIGHTS_NUVIO_SOURCE_ID)
            ?: nuvioSourceSnapshot(profileId, authState, null)
        if (!hasRemoteAccount) {
            updateSource(profileId, requestGeneration, before.copy(
                library = before.library.copy(status = ProfileInsightsFetchStatus.LOCAL, errorMessage = null),
                watched = before.watched.copy(status = ProfileInsightsFetchStatus.LOCAL, errorMessage = null),
                progress = before.progress.copy(status = ProfileInsightsFetchStatus.LOCAL, errorMessage = null),
            ))
            return ProfileInsightsRefreshResult(attemptedCapabilityCount = 3, successfulCapabilityCount = 3)
        }

        var successes = 0
        var libraryOk = false
        var watchedOk = false
        var progressOk = false
        updateSource(profileId, requestGeneration, before.copy(
            library = before.library.copy(status = ProfileInsightsFetchStatus.REFRESHING, errorMessage = null),
            watched = before.watched.copy(status = ProfileInsightsFetchStatus.REFRESHING, errorMessage = null),
            progress = before.progress.copy(status = ProfileInsightsFetchStatus.REFRESHING, errorMessage = null),
        ))

        try {
            libraryOk = LibraryRepository.refreshNuvioForProfileInsights(profileId, forceSnapshot)
            if (!isBindingCurrent(profileId, requestGeneration)) return ProfileInsightsRefreshResult(3, successes)
            successes += libraryOk.asInt()
            updateNuvioCapability(profileId, requestGeneration, CAPABILITY_LIBRARY, libraryOk, null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Nuvio Library refresh failed for profile=$profileId" }
            updateNuvioCapability(profileId, requestGeneration, CAPABILITY_LIBRARY, false, error.message)
        }
        try {
            watchedOk = WatchedRepository.refreshNuvioForProfileInsights(profileId, forceSnapshot)
            if (!isBindingCurrent(profileId, requestGeneration)) return ProfileInsightsRefreshResult(3, successes)
            successes += watchedOk.asInt()
            updateNuvioCapability(profileId, requestGeneration, CAPABILITY_WATCHED, watchedOk, null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Nuvio watched history refresh failed for profile=$profileId" }
            updateNuvioCapability(profileId, requestGeneration, CAPABILITY_WATCHED, false, error.message)
        }
        try {
            progressOk = WatchProgressRepository.refreshNuvioForProfileInsights(profileId, forceSnapshot)
            if (!isBindingCurrent(profileId, requestGeneration)) return ProfileInsightsRefreshResult(3, successes)
            successes += progressOk.asInt()
            updateNuvioCapability(profileId, requestGeneration, CAPABILITY_PROGRESS, progressOk, null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Nuvio watch progress refresh failed for profile=$profileId" }
            updateNuvioCapability(profileId, requestGeneration, CAPABILITY_PROGRESS, false, error.message)
        }
        return ProfileInsightsRefreshResult(
            attemptedCapabilityCount = 3,
            successfulCapabilityCount = successes,
            nuvioFullSnapshotSucceeded = libraryOk && watchedOk && progressOk,
        )
    }

    private suspend fun refreshProvider(
        profileId: Int,
        requestGeneration: Long,
        providerId: TrackingProviderId,
        intent: TrackingRefreshIntent,
    ): ProfileInsightsRefreshResult {
        if (!isBindingCurrent(profileId, requestGeneration)) return ProfileInsightsRefreshResult()
        val auth = TrackingProviderRegistry.authProvider(providerId) ?: return ProfileInsightsRefreshResult()
        auth.ensureLoaded()
        val accountGeneration = auth.accountGeneration
        val accountIdentity = auth.accountIdentity
        if (!auth.isAuthenticated.value) {
            clearWatchedCache(profileId, providerId)
            val disconnected = providerSourceSnapshot(profileId, providerId, accountGeneration)
            updateSource(profileId, requestGeneration, disconnected)
            return ProfileInsightsRefreshResult()
        }
        var source = providerSourceSnapshot(profileId, providerId, accountGeneration)
        var attempted = 0
        var successes = 0

        val libraryProvider = TrackingProviderRegistry.libraryProvider(providerId)
        val librarySupported = TrackingCapability.LIBRARY_READ in auth.descriptor.capabilities && libraryProvider != null
        if (librarySupported) {
            attempted += 1
            source = source.copy(library = source.library.copy(status = ProfileInsightsFetchStatus.REFRESHING, errorMessage = null))
            updateSource(profileId, requestGeneration, source)
            try {
                libraryProvider!!.refresh(intent)
                if (!isProviderRequestCurrent(profileId, requestGeneration, providerId, accountGeneration, accountIdentity)) return ProfileInsightsRefreshResult(attempted, successes)
                val snapshot = libraryProvider.snapshot()
                val error = snapshot.errorMessage
                val updatedItems = profileInsightsSnapshotItems(
                    hasLoaded = snapshot.hasLoaded,
                    errorMessage = error,
                    snapshotItems = snapshot.items,
                    cachedItems = source.libraryItems,
                )
                val status = providerStatus(
                    hasLoaded = snapshot.hasLoaded,
                    isLoading = snapshot.isLoading,
                    hasData = updatedItems.isNotEmpty(),
                    errorMessage = error,
                    accountGeneration = accountGeneration,
                    providerId = providerId,
                    capability = CAPABILITY_LIBRARY,
                )
                val refreshedAt = WatchedClock.nowEpochMs().takeIf { error == null && snapshot.hasLoaded }
                source = source.copy(
                    library = ProfileInsightsCapabilityState(status, updatedItems.size, error, refreshedAt ?: source.library.updatedAtEpochMs),
                    libraryItems = updatedItems,
                )
                successes += (error == null && snapshot.hasLoaded).asInt()
                updateSource(profileId, requestGeneration, source)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "${providerId.storageId} Library refresh failed" }
                source = source.copy(library = failedStatus(source.libraryItems.size, error.message))
                updateSource(profileId, requestGeneration, source)
            }
        }

        val progressProvider = TrackingProviderRegistry.progressProvider(providerId)
        val providerProgressSupported = TrackingCapability.PROGRESS_READ in auth.descriptor.capabilities && progressProvider != null
        val progressSemanticallySupported = providerId != TrackingProviderId.ANILIST
        if (providerProgressSupported) {
            attempted += 1
            source = source.copy(progress = source.progress.copy(
                status = if (progressSemanticallySupported) ProfileInsightsFetchStatus.REFRESHING else ProfileInsightsFetchStatus.UNSUPPORTED,
                errorMessage = if (progressSemanticallySupported) null else "List progress is not playback history",
            ))
            updateSource(profileId, requestGeneration, source)
            if (progressSemanticallySupported) {
                try {
                    progressProvider!!.refresh(
                        force = intent == TrackingRefreshIntent.USER_INITIATED,
                        sourceChanged = false,
                    )
                    if (!isProviderRequestCurrent(profileId, requestGeneration, providerId, accountGeneration, accountIdentity)) return ProfileInsightsRefreshResult(attempted, successes)
                    val snapshot = progressProvider.snapshot()
                    val error = snapshot.errorMessage
                    val updatedEntries = profileInsightsSnapshotItems(
                        hasLoaded = snapshot.hasLoadedRemoteProgress,
                        errorMessage = error,
                        snapshotItems = snapshot.entries,
                        cachedItems = source.progressEntries,
                    )
                    val status = providerStatus(
                        hasLoaded = snapshot.hasLoadedRemoteProgress,
                        isLoading = false,
                        hasData = updatedEntries.isNotEmpty(),
                        errorMessage = error,
                        accountGeneration = accountGeneration,
                        providerId = providerId,
                        capability = CAPABILITY_PROGRESS,
                    )
                    val refreshedAt = WatchedClock.nowEpochMs().takeIf { error == null && snapshot.hasLoadedRemoteProgress }
                    source = source.copy(
                        progress = ProfileInsightsCapabilityState(status, updatedEntries.size, error, refreshedAt ?: source.progress.updatedAtEpochMs),
                        progressEntries = updatedEntries,
                    )
                    successes += (error == null && snapshot.hasLoadedRemoteProgress).asInt()
                    updateSource(profileId, requestGeneration, source)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.w(error) { "${providerId.storageId} progress refresh failed" }
                    source = source.copy(progress = failedStatus(source.progressEntries.size, error.message))
                    updateSource(profileId, requestGeneration, source)
                }
            }
        }

        val watchedProvider = TrackingProviderRegistry.watchedProvider(providerId)
        val watchedSupported = TrackingCapability.WATCHED_READ in auth.descriptor.capabilities && watchedProvider != null
        val historySemanticallySupported = providerId != TrackingProviderId.ANILIST
        if (watchedSupported) {
            attempted += 1
            source = source.copy(watched = source.watched.copy(
                status = if (historySemanticallySupported) ProfileInsightsFetchStatus.REFRESHING else ProfileInsightsFetchStatus.UNSUPPORTED,
                errorMessage = if (historySemanticallySupported) null else "AniList list progress is not watch history",
            ))
            updateSource(profileId, requestGeneration, source)
            if (historySemanticallySupported) {
                try {
                    val projected = watchedProvider!!.pull(profileId, WATCHED_PAGE_SIZE)
                    val historyRead = readVerifiedWatchedHistory(providerId, projected)
                    if (!isProviderRequestCurrent(profileId, requestGeneration, providerId, accountGeneration, accountIdentity)) {
                        clearWatchedCacheIfAccountChanged(profileId, providerId, auth.accountGeneration)
                        return ProfileInsightsRefreshResult(attempted, successes)
                    }
                    val items = profileInsightsSnapshotItems(
                        hasLoaded = historyRead.hasLoaded,
                        errorMessage = historyRead.errorMessage,
                        snapshotItems = historyRead.items,
                        cachedItems = source.watchedItems,
                    )
                    val refreshedAt = WatchedClock.nowEpochMs().takeIf { historyRead.errorMessage == null && historyRead.hasLoaded }
                    val watchedStatus = when {
                        historyRead.errorMessage != null -> failedStatus(items.size, historyRead.errorMessage)
                        !historyRead.hasLoaded -> ProfileInsightsCapabilityState(
                            status = if (items.isNotEmpty()) ProfileInsightsFetchStatus.CACHED else ProfileInsightsFetchStatus.NOT_LOADED,
                            itemCount = items.size,
                            updatedAtEpochMs = source.watched.updatedAtEpochMs,
                        )
                        else -> ProfileInsightsCapabilityState(
                            status = ProfileInsightsFetchStatus.AVAILABLE,
                            itemCount = items.size,
                            updatedAtEpochMs = refreshedAt,
                        )
                    }
                    val fullyWatched = source.fullyWatchedSeriesKeys
                    if (historyRead.hasLoaded && historyRead.errorMessage == null) {
                        storeWatchedCache(
                            profileId,
                            providerId,
                            items,
                            fullyWatched,
                            refreshedAt ?: WatchedClock.nowEpochMs(),
                            accountGeneration,
                            accountIdentity,
                        )
                        successes += 1
                    }
                    source = source.copy(
                        watched = watchedStatus,
                        watchedItems = items,
                    )
                    updateSource(profileId, requestGeneration, source)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.w(error) { "${providerId.storageId} watched-history refresh failed" }
                    source = source.copy(watched = failedStatus(source.watchedItems.size, error.message))
                    updateSource(profileId, requestGeneration, source)
                }
            }
        }
        return ProfileInsightsRefreshResult(attempted, successes)
    }

    private data class VerifiedProfileHistory(
        val items: List<WatchedItem>,
        val hasLoaded: Boolean,
        val errorMessage: String? = null,
    )

    private suspend fun readVerifiedWatchedHistory(
        providerId: TrackingProviderId,
        projected: List<WatchedItem>,
    ): VerifiedProfileHistory = when (providerId) {
        TrackingProviderId.ANILIST -> VerifiedProfileHistory(
            items = emptyList(),
            hasLoaded = false,
            errorMessage = "AniList lists are not watch history",
        )
        TrackingProviderId.SIMKL -> {
            SimklSyncRepository.ensureLoaded()
            val state = SimklSyncRepository.state.value
            VerifiedProfileHistory(
                items = state.snapshot.toProfileInsightsWatchedItems(),
                hasLoaded = state.hasLoaded,
                errorMessage = state.errorMessage,
            )
        }
        TrackingProviderId.WETRAKR -> {
            WeTrakrTracker.sync.ensureLoaded()
            val state = WeTrakrTracker.sync.state.value
            VerifiedProfileHistory(
                items = WeTrakrTracker.sync.currentSnapshot()?.toProfileInsightsWatchedItems().orEmpty(),
                hasLoaded = state.hasLoaded,
                errorMessage = state.error?.name,
            )
        }
        TrackingProviderId.MDBLIST -> {
            MdbListTracker.sync.ensureLoaded()
            val state = MdbListTracker.sync.state.value
            VerifiedProfileHistory(
                items = projected,
                hasLoaded = state.hasLoaded,
                errorMessage = state.error?.name,
            )
        }
        TrackingProviderId.TRAKT -> VerifiedProfileHistory(projected, hasLoaded = true)
    }

    private suspend fun reloadCachedSources(profileId: Int, requestGeneration: Long) {
        if (!isBindingCurrent(profileId, requestGeneration) || ProfileRepository.activeProfileId != profileId) return
        try {
            ensureTrackingProvidersRegistered()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Failed to register Profile source readers for profile=$profileId" }
        }
        listOf<suspend () -> Unit>(
            { LibraryRepository.ensureLoaded() },
            { WatchedRepository.ensureLoaded() },
            { WatchProgressRepository.ensureLoaded() },
        ).forEach { initialize ->
            try {
                initialize()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Failed to initialize a Profile source reader for profile=$profileId" }
            }
        }

        try {
            val authState = AuthRepository.state.value
            val nuvio = nuvioSourceSnapshot(
                profileId,
                authState,
                currentSource(profileId, PROFILE_INSIGHTS_NUVIO_SOURCE_ID),
            )
            updateSource(profileId, requestGeneration, nuvio)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Failed to read Nuvio Profile sources for profile=$profileId" }
            recordSourceSnapshotFailure(
                profileId = profileId,
                requestGeneration = requestGeneration,
                sourceId = PROFILE_INSIGHTS_NUVIO_SOURCE_ID,
                errorMessage = error.message,
            )
        }

        TrackingProviderId.entries.forEach { providerId ->
            if (!isBindingCurrent(profileId, requestGeneration)) return
            val auth = TrackingProviderRegistry.authProvider(providerId) ?: return@forEach
            try {
                auth.ensureLoaded()
                val accountGeneration = auth.accountGeneration
                if (!auth.isAuthenticated.value) {
                    clearWatchedCache(profileId, providerId)
                }
                updateSource(
                    profileId,
                    requestGeneration,
                    providerSourceSnapshot(profileId, providerId, accountGeneration),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Failed to read ${providerId.storageId} Profile sources" }
                recordSourceSnapshotFailure(
                    profileId = profileId,
                    requestGeneration = requestGeneration,
                    sourceId = providerId.storageId,
                    errorMessage = error.message,
                )
            }
        }
        _state.update { current ->
            if (current.profileId == profileId && isBindingCurrent(profileId, requestGeneration)) {
                current.copy(sources = current.sources.sortedBy { sourceOrder(it.sourceId) })
            } else current
        }
    }

    private fun recordSourceSnapshotFailure(
        profileId: Int,
        requestGeneration: Long,
        sourceId: String,
        errorMessage: String?,
    ) {
        val providerId = TrackingProviderId.fromStorage(sourceId)
        val auth = providerId?.let(TrackingProviderRegistry::authProvider)
        val connected = sourceId == PROFILE_INSIGHTS_NUVIO_SOURCE_ID || auth?.isAuthenticated?.value == true
        val accountGeneration = auth?.accountGeneration
        val accountIdentity = auth?.accountIdentity
        val previous = currentSource(profileId, sourceId)?.takeIf { source ->
            source.connected == connected &&
                (sourceId == PROFILE_INSIGHTS_NUVIO_SOURCE_ID ||
                    (source.accountGeneration == accountGeneration && source.accountIdentity == accountIdentity))
        }
        val descriptor = auth?.descriptor
        val supportedLibrary = sourceId == PROFILE_INSIGHTS_NUVIO_SOURCE_ID ||
            (descriptor?.capabilities?.contains(TrackingCapability.LIBRARY_READ) == true &&
                providerId?.let(TrackingProviderRegistry::libraryProvider) != null)
        val supportedWatched = sourceId == PROFILE_INSIGHTS_NUVIO_SOURCE_ID ||
            (descriptor?.capabilities?.contains(TrackingCapability.WATCHED_READ) == true &&
                providerId?.let(TrackingProviderRegistry::watchedProvider) != null)
        val supportedProgress = sourceId == PROFILE_INSIGHTS_NUVIO_SOURCE_ID ||
            (descriptor?.capabilities?.contains(TrackingCapability.PROGRESS_READ) == true &&
                providerId?.let(TrackingProviderRegistry::progressProvider) != null)

        fun initialStatus(supported: Boolean): ProfileInsightsCapabilityState = when {
            !supported -> ProfileInsightsCapabilityState(ProfileInsightsFetchStatus.UNSUPPORTED)
            !connected -> ProfileInsightsCapabilityState(ProfileInsightsFetchStatus.NOT_CONNECTED)
            else -> failedStatus(0, errorMessage)
        }
        fun failed(capability: ProfileInsightsCapabilityState, supported: Boolean): ProfileInsightsCapabilityState = when {
            !supported -> capability.copy(status = ProfileInsightsFetchStatus.UNSUPPORTED, errorMessage = null)
            !connected -> capability.copy(status = ProfileInsightsFetchStatus.NOT_CONNECTED, itemCount = 0, errorMessage = null)
            else -> failedStatus(capability.itemCount, errorMessage)
        }

        val source = if (previous == null) {
            ProfileInsightsSourceSnapshot(
                sourceId = sourceId,
                displayName = if (sourceId == PROFILE_INSIGHTS_NUVIO_SOURCE_ID) "Nuvio Library & Sync"
                    else descriptor?.displayName ?: providerId?.displayName ?: sourceId,
                connected = connected,
                library = initialStatus(supportedLibrary),
                watched = if (providerId == TrackingProviderId.ANILIST) {
                    ProfileInsightsCapabilityState(ProfileInsightsFetchStatus.UNSUPPORTED, errorMessage = "AniList lists are not watch history")
                } else initialStatus(supportedWatched),
                progress = if (providerId == TrackingProviderId.ANILIST) {
                    ProfileInsightsCapabilityState(ProfileInsightsFetchStatus.UNSUPPORTED, errorMessage = "List progress is not playback history")
                } else initialStatus(supportedProgress),
                accountGeneration = accountGeneration,
                accountIdentity = accountIdentity,
            )
        } else {
            previous.copy(
                library = failed(previous.library, supportedLibrary),
                watched = failed(previous.watched, supportedWatched),
                progress = failed(previous.progress, supportedProgress),
            )
        }
        updateSource(profileId, requestGeneration, source)
    }

    private fun nuvioSourceSnapshot(
        profileId: Int,
        authState: AuthState,
        previous: ProfileInsightsSourceSnapshot?,
    ): ProfileInsightsSourceSnapshot {
        val items = LibraryRepository.profileInsightsLocalItems(profileId)
        val watched = WatchedRepository.profileInsightsSnapshot(profileId).nuvio
        val progress = WatchProgressRepository.profileInsightsNuvioEntries(profileId)
        val hasRemoteAccount = authState is AuthState.Authenticated && !authState.isAnonymous
        fun localOrRemoteStatus(previousStatus: ProfileInsightsCapabilityState?, hasLoaded: Boolean, hasData: Boolean): ProfileInsightsCapabilityState {
            if (!hasRemoteAccount) return ProfileInsightsCapabilityState(ProfileInsightsFetchStatus.LOCAL, if (hasLoaded) 1 else 0)
            val previousError = previousStatus?.errorMessage
            if (previousError != null) return failedStatus(if (hasData) 1 else 0, previousError)
            val previousSuccess = previousStatus?.updatedAtEpochMs
            return ProfileInsightsCapabilityState(
                status = when {
                    previousSuccess != null -> ProfileInsightsFetchStatus.AVAILABLE
                    hasLoaded -> ProfileInsightsFetchStatus.CACHED
                    else -> ProfileInsightsFetchStatus.NOT_LOADED
                },
                itemCount = if (hasData) 1 else 0,
                updatedAtEpochMs = previousSuccess,
            )
        }
        val previousLibrary = previous?.library
        val previousWatched = previous?.watched
        val previousProgress = previous?.progress
        return ProfileInsightsSourceSnapshot(
            sourceId = PROFILE_INSIGHTS_NUVIO_SOURCE_ID,
            displayName = "Nuvio Library & Sync",
            connected = true,
            library = localOrRemoteStatus(previousLibrary, hasLoaded = true, hasData = items.isNotEmpty()).copy(itemCount = items.size),
            watched = localOrRemoteStatus(previousWatched, watched.isLoaded, watched.items.isNotEmpty()).copy(
                itemCount = watched.items.size,
                status = if (!hasRemoteAccount) ProfileInsightsFetchStatus.LOCAL else localOrRemoteStatus(previousWatched, watched.isLoaded, watched.items.isNotEmpty()).status,
            ),
            progress = localOrRemoteStatus(previousProgress, hasLoaded = true, hasData = progress.isNotEmpty()).copy(itemCount = progress.size),
            libraryItems = items,
            watchedItems = watched.items,
            progressEntries = progress,
            fullyWatchedSeriesKeys = watched.fullyWatchedSeriesKeys,
        )
    }

    private fun providerLoadError(label: String, block: () -> Unit): String? = try {
        block()
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error) { "Failed to load $label snapshot" }
        error.message ?: "Snapshot load failed"
    }

    private fun <T : Any> providerSnapshotSafely(
        label: String,
        block: () -> T?,
    ): Pair<T?, String?> = try {
        block() to null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error) { "Failed to read $label snapshot" }
        null to (error.message ?: "Snapshot read failed")
    }

    private fun providerSourceSnapshot(
        profileId: Int,
        providerId: TrackingProviderId,
        accountGeneration: Long,
    ): ProfileInsightsSourceSnapshot {
        val auth = TrackingProviderRegistry.authProvider(providerId)
        val connected = auth?.isAuthenticated?.value == true
        val accountIdentity = auth?.accountIdentity
        val previous = currentSource(profileId, providerId.storageId)
            ?.takeIf {
                it.accountGeneration == accountGeneration &&
                    it.accountIdentity == accountIdentity &&
                    it.connected == connected
            }
        val descriptor = auth?.descriptor
        val libraryProvider = TrackingProviderRegistry.libraryProvider(providerId)
        val progressProvider = TrackingProviderRegistry.progressProvider(providerId)
        val watchedProvider = TrackingProviderRegistry.watchedProvider(providerId)
        val librarySupported = connected && descriptor?.capabilities?.contains(TrackingCapability.LIBRARY_READ) == true && libraryProvider != null
        val watchedSupported = connected && descriptor?.capabilities?.contains(TrackingCapability.WATCHED_READ) == true && watchedProvider != null
        val progressSupported = connected && descriptor?.capabilities?.contains(TrackingCapability.PROGRESS_READ) == true && progressProvider != null
        val historySemanticallySupported = providerId != TrackingProviderId.ANILIST
        val progressSemanticallySupported = providerId != TrackingProviderId.ANILIST

        val libraryLoadError = libraryProvider?.let { provider ->
            providerLoadError("${providerId.storageId} library") { provider.ensureLoaded() }
        }
        val progressLoadError = progressProvider?.let { provider ->
            providerLoadError("${providerId.storageId} progress") { provider.ensureLoaded() }
        }
        val libraryRead = providerSnapshotSafely("${providerId.storageId} library") {
            libraryProvider?.snapshot()
        }
        val progressRead = providerSnapshotSafely("${providerId.storageId} progress") {
            progressProvider?.snapshot()
        }
        val librarySnapshot = libraryRead.first
        val progressSnapshot = progressRead.first
        val watchedCache = readWatchedCache(profileId, providerId, accountGeneration, accountIdentity)
        val watchedRepositoryCache = WatchedRepository.profileInsightsSnapshot(profileId).providers[providerId]
        val watchedItems = if (!connected || !historySemanticallySupported) {
            emptyList()
        } else {
            watchedCache.first.takeIf { watchedCache.second != null }
                ?: cachedVerifiedProviderHistory(providerId, watchedRepositoryCache)
        }
        val libraryError = librarySnapshot?.errorMessage ?: libraryLoadError ?: libraryRead.second
        val progressError = progressSnapshot?.errorMessage ?: progressLoadError ?: progressRead.second
        val watchedError = providerHistoryErrorMessage(providerId)
        val libraryItems = when {
            !connected -> emptyList()
            librarySnapshot?.hasLoaded == true && libraryError == null -> librarySnapshot.items
            else -> librarySnapshot?.items?.takeIf { it.isNotEmpty() } ?: previous?.libraryItems.orEmpty()
        }
        val progressEntries = when {
            !connected -> emptyList()
            progressSnapshot?.hasLoadedRemoteProgress == true && progressError == null -> progressSnapshot.entries
            else -> progressSnapshot?.entries?.takeIf { it.isNotEmpty() } ?: previous?.progressEntries.orEmpty()
        }
        val previousLibraryStatus = previous?.library?.status
        val previousWatchedStatus = previous?.watched?.status
        val previousProgressStatus = previous?.progress?.status
        val libraryStatus = when {
            !connected -> if (descriptor?.capabilities?.contains(TrackingCapability.LIBRARY_READ) == true) ProfileInsightsFetchStatus.NOT_CONNECTED else ProfileInsightsFetchStatus.UNSUPPORTED
            !librarySupported -> ProfileInsightsFetchStatus.UNSUPPORTED
            libraryError != null -> if (libraryItems.isNotEmpty()) ProfileInsightsFetchStatus.STALE else ProfileInsightsFetchStatus.FAILED
            previousLibraryStatus in setOf(ProfileInsightsFetchStatus.AVAILABLE, ProfileInsightsFetchStatus.STALE, ProfileInsightsFetchStatus.REFRESHING) -> previousLibraryStatus ?: ProfileInsightsFetchStatus.NOT_LOADED
            librarySnapshot?.hasLoaded == true || libraryItems.isNotEmpty() -> ProfileInsightsFetchStatus.CACHED
            else -> ProfileInsightsFetchStatus.NOT_LOADED
        }
        val watchedStatus = when {
            !connected -> if (descriptor?.capabilities?.contains(TrackingCapability.WATCHED_READ) == true) ProfileInsightsFetchStatus.NOT_CONNECTED else ProfileInsightsFetchStatus.UNSUPPORTED
            !watchedSupported -> ProfileInsightsFetchStatus.UNSUPPORTED
            !historySemanticallySupported -> ProfileInsightsFetchStatus.UNSUPPORTED
            watchedError != null -> if (watchedItems.isNotEmpty()) ProfileInsightsFetchStatus.STALE else ProfileInsightsFetchStatus.FAILED
            previousWatchedStatus in setOf(ProfileInsightsFetchStatus.AVAILABLE, ProfileInsightsFetchStatus.STALE, ProfileInsightsFetchStatus.REFRESHING) -> previousWatchedStatus ?: ProfileInsightsFetchStatus.NOT_LOADED
            watchedCache.second != null -> ProfileInsightsFetchStatus.AVAILABLE
            watchedRepositoryCache?.hasLoadedRemoteItems == true -> ProfileInsightsFetchStatus.AVAILABLE
            hasCachedWatchedSnapshot(providerId) || watchedRepositoryCache?.isLoaded == true -> ProfileInsightsFetchStatus.CACHED
            else -> ProfileInsightsFetchStatus.NOT_LOADED
        }
        val progressStatus = when {
            !connected -> if (descriptor?.capabilities?.contains(TrackingCapability.PROGRESS_READ) == true) ProfileInsightsFetchStatus.NOT_CONNECTED else ProfileInsightsFetchStatus.UNSUPPORTED
            !progressSupported -> ProfileInsightsFetchStatus.UNSUPPORTED
            !progressSemanticallySupported -> ProfileInsightsFetchStatus.UNSUPPORTED
            progressError != null -> if (progressEntries.isNotEmpty()) ProfileInsightsFetchStatus.STALE else ProfileInsightsFetchStatus.FAILED
            previousProgressStatus in setOf(ProfileInsightsFetchStatus.AVAILABLE, ProfileInsightsFetchStatus.STALE, ProfileInsightsFetchStatus.REFRESHING) -> previousProgressStatus ?: ProfileInsightsFetchStatus.NOT_LOADED
            progressSnapshot?.hasLoadedRemoteProgress == true || progressEntries.isNotEmpty() -> ProfileInsightsFetchStatus.CACHED
            else -> ProfileInsightsFetchStatus.NOT_LOADED
        }
        return ProfileInsightsSourceSnapshot(
            sourceId = providerId.storageId,
            displayName = descriptor?.displayName ?: providerId.displayName,
            connected = connected,
            library = ProfileInsightsCapabilityState(libraryStatus, libraryItems.size, libraryError, previous?.library?.updatedAtEpochMs),
            watched = ProfileInsightsCapabilityState(watchedStatus, watchedItems.size, watchedError ?: previous?.watched?.errorMessage, watchedCache.second?.refreshedAtEpochMs ?: previous?.watched?.updatedAtEpochMs),
            progress = ProfileInsightsCapabilityState(progressStatus, progressEntries.size, progressError, previous?.progress?.updatedAtEpochMs),
            libraryItems = libraryItems,
            watchedItems = watchedItems,
            progressEntries = progressEntries,
            fullyWatchedSeriesKeys = watchedCache.first.takeIf { watchedCache.second != null }
                ?.let { watchedCache.second?.fullyWatchedSeriesKeys.orEmpty() }
                ?: watchedRepositoryCache?.fullyWatchedSeriesKeys.orEmpty(),
            accountGeneration = accountGeneration,
            accountIdentity = accountIdentity,
        )
    }

    private fun cachedVerifiedProviderHistory(
        providerId: TrackingProviderId,
        repositoryCache: WatchedInsightsSourceSnapshot?,
    ): List<WatchedItem> {
        if (repositoryCache == null || !repositoryCache.isLoaded) return emptyList()
        return when (providerId) {
            TrackingProviderId.ANILIST -> emptyList()
            TrackingProviderId.SIMKL -> SimklSyncRepository.state.value.snapshot.toProfileInsightsWatchedItems()
            TrackingProviderId.WETRAKR -> WeTrakrTracker.sync.currentSnapshot()?.toProfileInsightsWatchedItems().orEmpty()
            TrackingProviderId.TRAKT, TrackingProviderId.MDBLIST -> repositoryCache.items
        }
    }

    private fun hasCachedWatchedSnapshot(providerId: TrackingProviderId): Boolean = when (providerId) {
        TrackingProviderId.SIMKL -> SimklSyncRepository.state.value.hasLoaded
        TrackingProviderId.WETRAKR -> WeTrakrTracker.sync.state.value.hasLoaded
        TrackingProviderId.MDBLIST -> MdbListTracker.sync.state.value.hasLoaded
        else -> false
    }

    private fun providerHistoryErrorMessage(providerId: TrackingProviderId): String? = when (providerId) {
        TrackingProviderId.SIMKL -> SimklSyncRepository.state.value.errorMessage
        TrackingProviderId.WETRAKR -> WeTrakrTracker.sync.state.value.error?.name
        TrackingProviderId.MDBLIST -> MdbListTracker.sync.state.value.error?.name
        TrackingProviderId.TRAKT,
        TrackingProviderId.ANILIST,
        -> null
    }

    private fun readWatchedCache(
        profileId: Int,
        providerId: TrackingProviderId,
        accountGeneration: Long,
        accountIdentity: String?,
    ): Pair<List<WatchedItem>, CachedWatchedItems?> = synchronized(watchedCacheLock) {
        val cached = watchedCacheByProfile[profileId]?.get(providerId)
        if (cached == null ||
            cached.accountGeneration != accountGeneration ||
            cached.accountIdentity != accountIdentity
        ) {
            watchedCacheByProfile[profileId]?.remove(providerId)
            emptyList<WatchedItem>() to null
        } else {
            cached.items to cached
        }
    }

    private fun storeWatchedCache(
        profileId: Int,
        providerId: TrackingProviderId,
        items: List<WatchedItem>,
        fullyWatchedSeriesKeys: Set<String>,
        refreshedAtEpochMs: Long,
        accountGeneration: Long,
        accountIdentity: String?,
    ) {
        synchronized(watchedCacheLock) {
            watchedCacheByProfile.getOrPut(profileId, ::mutableMapOf)[providerId] = CachedWatchedItems(
                items = items,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                refreshedAtEpochMs = refreshedAtEpochMs,
                accountGeneration = accountGeneration,
                accountIdentity = accountIdentity,
            )
        }
    }

    private fun clearWatchedCache(profileId: Int, providerId: TrackingProviderId) {
        synchronized(watchedCacheLock) {
            watchedCacheByProfile[profileId]?.remove(providerId)
        }
    }

    private fun clearWatchedCacheIfAccountChanged(
        profileId: Int,
        providerId: TrackingProviderId,
        accountGeneration: Long,
    ) {
        synchronized(watchedCacheLock) {
            val cached = watchedCacheByProfile[profileId]?.get(providerId) ?: return
            if (cached.accountGeneration != accountGeneration) watchedCacheByProfile[profileId]?.remove(providerId)
        }
    }

    private fun markConnectedCapabilitiesRefreshing(profileId: Int, requestGeneration: Long) {
        if (!isBindingCurrent(profileId, requestGeneration)) return
        _state.update { current ->
            if (current.profileId != profileId) return@update current
            current.copy(
                isRefreshing = true,
                sources = current.sources.map { source ->
                    if (!source.connected || source.sourceId == PROFILE_INSIGHTS_NUVIO_SOURCE_ID) source else source.copy(
                        library = refreshStatusIfSupported(source.library),
                        watched = refreshStatusIfSupported(source.watched),
                        progress = refreshStatusIfSupported(source.progress),
                    )
                },
            )
        }
    }

    private fun refreshStatusIfSupported(status: ProfileInsightsCapabilityState): ProfileInsightsCapabilityState =
        if (status.status in setOf(ProfileInsightsFetchStatus.UNSUPPORTED, ProfileInsightsFetchStatus.NOT_CONNECTED)) status
        else status.copy(status = ProfileInsightsFetchStatus.REFRESHING, errorMessage = null)

    private fun updateNuvioCapability(
        profileId: Int,
        requestGeneration: Long,
        capability: String,
        succeeded: Boolean,
        errorMessage: String?,
    ) {
        val refreshedAt = WatchedClock.nowEpochMs().takeIf { succeeded }
        val source = nuvioSourceSnapshot(profileId, AuthRepository.state.value, currentSource(profileId, PROFILE_INSIGHTS_NUVIO_SOURCE_ID))
        val status = when {
            !succeeded -> failedStatus(sourceCapabilityCount(source, capability), errorMessage)
            AuthRepository.state.value !is AuthState.Authenticated || (AuthRepository.state.value as? AuthState.Authenticated)?.isAnonymous == true -> ProfileInsightsCapabilityState(ProfileInsightsFetchStatus.LOCAL, sourceCapabilityCount(source, capability))
            else -> ProfileInsightsCapabilityState(ProfileInsightsFetchStatus.AVAILABLE, sourceCapabilityCount(source, capability), updatedAtEpochMs = refreshedAt)
        }
        val updated = when (capability) {
            CAPABILITY_LIBRARY -> source.copy(library = status)
            CAPABILITY_WATCHED -> source.copy(watched = status)
            else -> source.copy(progress = status)
        }
        updateSource(profileId, requestGeneration, updated)
    }

    private fun sourceCapabilityCount(source: ProfileInsightsSourceSnapshot, capability: String): Int = when (capability) {
        CAPABILITY_LIBRARY -> source.libraryItems.size
        CAPABILITY_WATCHED -> source.watchedItems.size
        else -> source.progressEntries.size
    }

    private fun providerStatus(
        hasLoaded: Boolean,
        isLoading: Boolean,
        hasData: Boolean,
        errorMessage: String?,
        accountGeneration: Long,
        providerId: TrackingProviderId,
        capability: String,
    ): ProfileInsightsFetchStatus {
        val previous = currentSource(ProfileRepository.activeProfileId, providerId.storageId)
            ?.takeIf { it.accountGeneration == accountGeneration }
            ?.let { source -> capabilityState(source, capability) }
        if (errorMessage == null && previous?.status != ProfileInsightsFetchStatus.STALE) {
            if (hasLoaded && !isLoading) return ProfileInsightsFetchStatus.AVAILABLE
            if (isLoading) return ProfileInsightsFetchStatus.REFRESHING
        }
        return profileInsightsFetchStatus(
            connected = true,
            supported = true,
            hasLoaded = hasLoaded,
            isLoading = isLoading,
            hasData = hasData,
            errorMessage = errorMessage,
        )
    }

    private fun failedStatus(itemCount: Int, errorMessage: String?): ProfileInsightsCapabilityState =
        ProfileInsightsCapabilityState(
            status = if (itemCount > 0) ProfileInsightsFetchStatus.STALE else ProfileInsightsFetchStatus.FAILED,
            itemCount = itemCount,
            errorMessage = errorMessage ?: "Refresh did not complete",
        )

    private fun capabilityState(source: ProfileInsightsSourceSnapshot, capability: String): ProfileInsightsCapabilityState = when (capability) {
        CAPABILITY_LIBRARY -> source.library
        CAPABILITY_WATCHED -> source.watched
        else -> source.progress
    }

    private fun updateSource(
        profileId: Int,
        requestGeneration: Long,
        source: ProfileInsightsSourceSnapshot,
    ) {
        if (!isBindingCurrent(profileId, requestGeneration)) return
        _state.update { current ->
            if (current.profileId != profileId) return@update current
            val sources = current.sources.filterNot { it.sourceId == source.sourceId } + source
            current.copy(sources = sources.sortedBy { sourceOrder(it.sourceId) })
        }
    }

    private fun currentSource(profileId: Int, sourceId: String): ProfileInsightsSourceSnapshot? =
        _state.value.takeIf { it.profileId == profileId }?.sources?.firstOrNull { it.sourceId == sourceId }

    private fun markRefreshFinished(profileId: Int, requestGeneration: Long, atEpochMs: Long, succeeded: Boolean) {
        if (!isBindingCurrent(profileId, requestGeneration)) return
        _state.update { current ->
            if (current.profileId != profileId) current else current.copy(
                isRefreshing = false,
                lastAttemptAtEpochMs = atEpochMs,
                lastSuccessAtEpochMs = atEpochMs.takeIf { succeeded } ?: current.lastSuccessAtEpochMs,
                sources = if (succeeded) current.sources else current.sources.map { source ->
                    source.copy(
                        library = recoverProfileInsightsRefreshingStatus(source.library),
                        watched = recoverProfileInsightsRefreshingStatus(source.watched),
                        progress = recoverProfileInsightsRefreshingStatus(source.progress),
                    )
                },
            )
        }
    }

    private suspend fun observeSourceChanges(profileId: Int, requestGeneration: Long) {
        val flows = mutableListOf<Flow<Unit>>()
        flows += LibraryRepository.profileInsightsRevision.map { Unit }
        flows += WatchedRepository.profileInsightsRevision.map { Unit }
        flows += WatchProgressRepository.profileInsightsRevision.map { Unit }
        flows += TrackingProviderRegistry.connectedProviderIds.map { Unit }.distinctUntilChanged()
        TrackingProviderRegistry.libraryProviders().forEach { provider -> flows += provider.changes }
        TrackingProviderRegistry.progressProviders().forEach { provider -> flows += provider.changes }
        try {
            merge(*flows.toTypedArray()).collect {
                if (!isBindingCurrent(profileId, requestGeneration)) return@collect
                reloadCachedSources(profileId, requestGeneration)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Profile source observation stopped for profile=$profileId" }
        }
    }

    private fun isBindingCurrent(profileId: Int, requestGeneration: Long): Boolean =
        synchronized(bindingLock) { boundProfileId == profileId && bindingGeneration == requestGeneration }

    private fun isProviderRequestCurrent(
        profileId: Int,
        requestGeneration: Long,
        providerId: TrackingProviderId,
        accountGeneration: Long,
        accountIdentity: String?,
    ): Boolean {
        val auth = TrackingProviderRegistry.authProvider(providerId) ?: return false
        auth.ensureLoaded()
        return ProfileRepository.activeProfileId == profileId &&
            isBindingCurrent(profileId, requestGeneration) &&
            isProfileInsightsRefreshCurrent(
                requestedProfileId = profileId,
                activeProfileId = ProfileRepository.activeProfileId,
                requestedGeneration = requestGeneration,
                currentGeneration = synchronized(bindingLock) { bindingGeneration },
                requestedAccountGeneration = accountGeneration,
                currentAccountGeneration = auth.accountGeneration,
                isAuthenticated = auth.isAuthenticated.value,
                requestedAccountIdentity = accountIdentity,
                currentAccountIdentity = auth.accountIdentity,
            )
    }

    private fun sourceOrder(sourceId: String): Int = when (sourceId) {
        PROFILE_INSIGHTS_NUVIO_SOURCE_ID -> 0
        else -> TrackingProviderId.entries.firstOrNull { it.storageId == sourceId }?.ordinal?.plus(1) ?: Int.MAX_VALUE
    }

    private fun Boolean.asInt(): Int = if (this) 1 else 0
}
