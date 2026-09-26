package com.nuvio.app.features.cloudstream

/**
 * Reconciles a provider's declared stage budget with the host's maximum.
 *
 * CloudStream lets a provider state how long its own stages need through
 * `MainAPI.searchTimeoutMs`, `loadTimeoutMs` and `loadLinksTimeoutMs`. Slow
 * scrapers — multi-hop extractors, providers that solve a challenge before
 * they can resolve anything — rely on it. StreamBridge previously ignored
 * those fields and cut every provider off at a fixed host timeout, which fails
 * providers that are working, just slowly.
 *
 * This is a **clamp, not trust**. The host owns how long the source picker may
 * appear stuck, so a provider (or a hostile repository that authored one)
 * cannot ask for an unbounded budget. A declared value below the floor is
 * treated as unusable rather than honoured, since no network stage can complete
 * in a few milliseconds and obeying it would just guarantee a timeout.
 *
 * Pure and platform-independent so the whole table is unit-testable.
 */
internal object CloudStreamStageBudget {

    /** Shortest budget that could plausibly let a network stage finish. */
    const val MIN_STAGE_TIMEOUT_MS = 5_000L

    fun reconcile(providerDeclaredMs: Long?, hostMaximumMs: Long): Long {
        val declared = providerDeclaredMs?.takeIf { it > 0 } ?: return hostMaximumMs
        // minOf keeps the range non-empty when the host maximum is itself
        // below the floor; the host limit must always win.
        val floor = minOf(MIN_STAGE_TIMEOUT_MS, hostMaximumMs)
        return declared.coerceIn(floor, hostMaximumMs)
    }
}
