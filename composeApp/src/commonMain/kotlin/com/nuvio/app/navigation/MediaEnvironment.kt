package com.nuvio.app.navigation

/**
 * Destination outside StreamBridge's route stack. These are environments, not tabs:
 * opening one must never push, replace or pop a StreamBridge [AppRoute].
 *
 * The shell presents dedicated modal UI without replacing its existing route stack.
 * Catalogue and playback adapters are deliberately not part of this state model.
 */
internal enum class MediaEnvironment {
    StreamBridge,
    Music,
    YouTube,
}

/**
 * Pure environment transitions. The shell remains the owner of its existing route stack,
 * selected tab and composed UI state. In particular, Exit does not navigate to TabsRoute.
 *
 * Back from either dedicated environment has the same meaning as Exit, independent of
 * the environment's internal browsing stack. Back in StreamBridge is left to its navigator.
 */
internal data class MediaEnvironmentState(
    val active: MediaEnvironment = MediaEnvironment.StreamBridge,
) {
    fun open(environment: MediaEnvironment): MediaEnvironmentState =
        if (active == MediaEnvironment.StreamBridge && environment != MediaEnvironment.StreamBridge) {
            copy(active = environment)
        } else {
            this
        }

    fun exit(): MediaEnvironmentState =
        if (active == MediaEnvironment.StreamBridge) this else copy(active = MediaEnvironment.StreamBridge)

    /** Null means the existing StreamBridge back dispatcher must handle this event. */
    fun handleBack(): MediaEnvironmentState? =
        if (active == MediaEnvironment.StreamBridge) null else exit()
}
