package com.nuvio.app.features.trakt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coalesce refreshes so automatic callers do not accumulate behind a stalled
 * network request. A manual refresh waits for the in-flight request instead.
 * Adapted from the official 1.0.1 Trakt refresh queue fix.
 */
internal class TraktRefreshCoordinator(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var refreshJob: Job? = null

    suspend fun run(force: Boolean, refresh: suspend () -> Unit) {
        val job = mutex.withLock {
            val running = refreshJob?.takeIf(Job::isActive)
            if (running != null) {
                if (!force) return
                running
            } else {
                scope.launch { refresh() }.also { refreshJob = it }
            }
        }
        job.join()
    }
}
