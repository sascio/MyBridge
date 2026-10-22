package com.nuvio.app

import com.nuvio.app.features.profiles.MAX_PROFILES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the "pause, switch apps, come back and you are on profile selection /
 * Home" bug. See [restoredSessionGateAction].
 */
class AppGateSessionRestoreTest {
    private fun action(
        restoredFromSavedState: Boolean = true,
        gateScreenIsMain: Boolean = true,
        authPending: Boolean = true,
        mainGateProfileIndex: Int = 2,
        activeProfileIndex: Int? = 2,
    ) = restoredSessionGateAction(
        restoredFromSavedState = restoredFromSavedState,
        gateScreenIsMain = gateScreenIsMain,
        authPending = authPending,
        mainGateProfileIndex = mainGateProfileIndex,
        activeProfileIndex = activeProfileIndex,
    )

    @Test
    fun restoredMainGateIsKeptWhileAuthResolves() {
        assertEquals(RestoredSessionGateAction.KeepRestoredMain, action())
    }

    @Test
    fun coldLaunchStillReplaysTheProfileGate() {
        // Without a saved instance state this is a normal start, so the profile gate must run even
        // though the persisted cache already knows the last profile.
        assertEquals(
            RestoredSessionGateAction.ReplayColdStartGate,
            action(restoredFromSavedState = false),
        )
    }

    @Test
    fun restoredGateIsNotKeptForADifferentProfile() {
        assertEquals(
            RestoredSessionGateAction.ReplayColdStartGate,
            action(activeProfileIndex = 3),
        )
    }

    @Test
    fun restoredGateIsNotKeptWithoutARecordedProfile() {
        assertEquals(
            RestoredSessionGateAction.ReplayColdStartGate,
            action(mainGateProfileIndex = NO_PROFILE_INDEX),
        )
    }

    @Test
    fun restoredGateIsNotKeptWhileTheActiveProfileIsUnknown() {
        assertEquals(
            RestoredSessionGateAction.ReplayColdStartGate,
            action(activeProfileIndex = null),
        )
    }

    @Test
    fun restoredGateIsNotKeptOnceAuthHasResolved() {
        // ReplayColdStartGate hands control back to the regular routing, which keeps an existing
        // Main gate untouched but still sends a real sign-out to the auth/profile gate.
        assertEquals(
            RestoredSessionGateAction.ReplayColdStartGate,
            action(authPending = false),
        )
    }

    @Test
    fun restoredGateIsOnlyHandledForTheMainScreen() {
        assertEquals(
            RestoredSessionGateAction.ReplayColdStartGate,
            action(gateScreenIsMain = false),
        )
    }

    @Test
    fun profileIndexValidationMatchesTheSupportedRange() {
        assertFalse(NO_PROFILE_INDEX.isValidProfileIndex())
        assertTrue(1.isValidProfileIndex())
        assertTrue(MAX_PROFILES.isValidProfileIndex())
        assertFalse((MAX_PROFILES + 1).isValidProfileIndex())
    }
}
