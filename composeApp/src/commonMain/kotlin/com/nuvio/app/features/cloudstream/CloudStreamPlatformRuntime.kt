package com.nuvio.app.features.cloudstream

/**
 * Platform execution boundary for CloudStream extensions.
 *
 * ## Security boundary — read before changing
 *
 * Running a CloudStream provider means executing compiled third-party DEX that
 * was downloaded at runtime. That capability is confined to exactly one
 * distribution:
 *
 * | Target                     | [supportsExecution] | Behaviour                    |
 * | -------------------------- | ------------------- | ---------------------------- |
 * | Android **full** (sideload)| `true`              | Controlled, verified loading |
 * | Android **playstore**      | `false`             | Hard no-op stub              |
 * | iOS / desktop              | `false`             | Hard no-op stub              |
 *
 * The stubs are separate source sets, so the Play Store and iOS builds do not
 * merely *avoid calling* an executor — they are compiled without one, and
 * without the CloudStream runtime dependency that would make execution
 * possible at all.
 *
 * This is intentionally **not** a general "run some DEX" facility. The only
 * entry point takes a [CloudStreamPlugin] that came from a user-added
 * repository, and every implementation must verify the artifact before loading
 * it. Do not widen this interface to accept arbitrary file paths or class
 * names from unrelated callers.
 */
internal expect object CloudStreamPlatformRuntime {

    /** Whether this build can genuinely execute CloudStream provider code. */
    val supportsExecution: Boolean

    /** Supplies the platform context needed to load packages. No-op where unsupported. */
    fun initialize(context: Any?)

    /**
     * Returns the execution backend, or `null` when this build cannot execute
     * CloudStream extensions.
     *
     * A `null` result is the honest signal that leads the aggregator to
     * contribute no streams, rather than fabricating any.
     */
    fun executor(): CloudStreamPluginExecutor?
}
