package com.nuvio.app

import com.nuvio.app.features.profiles.MAX_PROFILES

/**
 * What the app gate should do with the screen it was showing when Android restored the activity.
 */
internal enum class RestoredSessionGateAction {
    /**
     * The saved gate is still valid for the profile that is being restored, so keep showing it
     * instead of replaying the cold-start profile gate.
     */
    KeepRestoredMain,

    /** Leave the decision to the normal cold-start auth/profile routing. */
    ReplayColdStartGate,
}

/**
 * Decides whether a restored composition may stay on [AppGateScreen.Main] while the auth state is
 * still resolving.
 *
 * Backgrounding the app while a video is paused is enough for Android to reclaim the process on
 * lower-memory devices. When the user comes back, Compose correctly restores the gate to `Main`
 * together with its navigation back stack, but `AuthRepository` starts over at
 * [com.nuvio.app.core.auth.AuthState.Loading] and the profile snapshot is re-read from disk. The
 * gate used to treat that transient `Loading` as a fresh start and re-derive the profile gate,
 * which tore `MainAppContent` down (dropping the restored back stack) and dropped the user either
 * back on profile selection or on Home.
 *
 * Keeping the restored gate is only safe when *all* of the following hold:
 * - the composition really was restored from saved instance state (never on a normal cold launch,
 *   so profile selection still behaves exactly as before),
 * - the saved gate was `Main`,
 * - auth is still pending — once it resolves, the regular routing takes over again, so a real
 *   sign-out (which ends in `Unauthenticated`) always reaches the auth/profile gate,
 * - the gate recorded which profile it belonged to, and that is still the active profile. A
 *   restored `Main` is never shown for a different (or unknown) profile.
 */
internal fun restoredSessionGateAction(
    restoredFromSavedState: Boolean,
    gateScreenIsMain: Boolean,
    authPending: Boolean,
    mainGateProfileIndex: Int,
    activeProfileIndex: Int?,
): RestoredSessionGateAction =
    if (
        restoredFromSavedState &&
        gateScreenIsMain &&
        authPending &&
        mainGateProfileIndex.isValidProfileIndex() &&
        activeProfileIndex == mainGateProfileIndex
    ) {
        RestoredSessionGateAction.KeepRestoredMain
    } else {
        RestoredSessionGateAction.ReplayColdStartGate
    }

/** Profile indexes are 1-based; anything else means "no profile recorded yet". */
internal fun Int.isValidProfileIndex(): Boolean = this in 1..MAX_PROFILES

/** Sentinel for "the gate has not been tied to a profile yet". Profile indexes start at 1. */
internal const val NO_PROFILE_INDEX = 0
