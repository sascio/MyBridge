package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rules that keep the installation lifecycle honest.
 *
 * The central property under test: a package that is not genuinely on disk can
 * never be reported as installed or enabled, no matter what was persisted or
 * what the user just tried to do.
 */
class CloudStreamInstallPolicyTest {

    // ---- state resolution -------------------------------------------------

    @Test
    fun `a plugin that is not on disk is available`() {
        assertEquals(
            CloudStreamInstallState.AVAILABLE,
            CloudStreamInstallPolicy.resolveState(
                isInstalledOnDisk = false,
                isEnabled = false,
                installedVersion = null,
                repositoryVersion = 3,
                failure = null,
            ),
        )
    }

    @Test
    fun `a failed install never reports as enabled`() {
        val state = CloudStreamInstallPolicy.resolveState(
            isInstalledOnDisk = false,
            // Deliberately hostile input: a stale enabled flag must not win.
            isEnabled = true,
            installedVersion = null,
            repositoryVersion = 3,
            failure = CloudStreamInstallError.HASH_MISMATCH,
        )
        assertEquals(CloudStreamInstallState.FAILED, state)
    }

    @Test
    fun `an installed but switched off plugin is disabled not available`() {
        assertEquals(
            CloudStreamInstallState.DISABLED,
            CloudStreamInstallPolicy.resolveState(
                isInstalledOnDisk = true,
                isEnabled = false,
                installedVersion = 3,
                repositoryVersion = 3,
                failure = null,
            ),
        )
    }

    @Test
    fun `an installed and switched on plugin is enabled`() {
        assertEquals(
            CloudStreamInstallState.ENABLED,
            CloudStreamInstallPolicy.resolveState(
                isInstalledOnDisk = true,
                isEnabled = true,
                installedVersion = 3,
                repositoryVersion = 3,
                failure = null,
            ),
        )
    }

    @Test
    fun `a newer published version surfaces as update available`() {
        assertEquals(
            CloudStreamInstallState.UPDATE_AVAILABLE,
            CloudStreamInstallPolicy.resolveState(
                isInstalledOnDisk = true,
                isEnabled = true,
                installedVersion = 2,
                repositoryVersion = 5,
                failure = null,
            ),
        )
    }

    @Test
    fun `a failed update keeps the working installation visible`() {
        // The package is still on disk, so the user must not be told it is gone.
        val state = CloudStreamInstallPolicy.resolveState(
            isInstalledOnDisk = true,
            isEnabled = true,
            installedVersion = 2,
            repositoryVersion = 2,
            failure = CloudStreamInstallError.NETWORK_UNREACHABLE,
        )
        assertEquals(CloudStreamInstallState.ENABLED, state)
    }

    @Test
    fun `an in-flight download outranks every other state`() {
        assertEquals(
            CloudStreamInstallState.DOWNLOADING,
            CloudStreamInstallPolicy.resolveState(
                isInstalledOnDisk = false,
                isEnabled = false,
                installedVersion = null,
                repositoryVersion = 1,
                failure = null,
                isBusy = CloudStreamInstallState.DOWNLOADING,
            ),
        )
    }

    // ---- update detection -------------------------------------------------

    @Test
    fun `update detection needs both versions and a strictly newer remote`() {
        assertTrue(CloudStreamInstallPolicy.hasUpdate(1, 2))
        assertFalse(CloudStreamInstallPolicy.hasUpdate(2, 2))
        // A downgrade is not an update.
        assertFalse(CloudStreamInstallPolicy.hasUpdate(3, 2))
        // Unknown versions must not fabricate an update prompt.
        assertFalse(CloudStreamInstallPolicy.hasUpdate(null, 2))
        assertFalse(CloudStreamInstallPolicy.hasUpdate(1, null))
    }

    // ---- enablement -------------------------------------------------------

    @Test
    fun `enabling requires both an installed package and an executable plugin`() {
        assertTrue(CloudStreamInstallPolicy.canEnable(isInstalledOnDisk = true, isExecutable = true))
        assertFalse(CloudStreamInstallPolicy.canEnable(isInstalledOnDisk = false, isExecutable = true))
        assertFalse(CloudStreamInstallPolicy.canEnable(isInstalledOnDisk = true, isExecutable = false))
    }

    // ---- error classification ---------------------------------------------

    @Test
    fun `failures are classified into distinct actionable causes`() {
        assertEquals(
            CloudStreamInstallError.HASH_MISMATCH,
            CloudStreamInstallPolicy.classify(
                IllegalStateException("failed SHA-256 verification; refusing to install"),
            ),
        )
        assertEquals(
            CloudStreamInstallError.TIMEOUT,
            CloudStreamInstallPolicy.classify(RuntimeException("Read timed out")),
        )
        assertEquals(
            CloudStreamInstallError.INSUFFICIENT_STORAGE,
            CloudStreamInstallPolicy.classify(RuntimeException("No space left on device")),
        )
        assertEquals(
            CloudStreamInstallError.MALFORMED_MANIFEST,
            CloudStreamInstallPolicy.classify(RuntimeException("manifest is unreadable")),
        )
        assertEquals(
            CloudStreamInstallError.CORRUPT_PACKAGE,
            CloudStreamInstallPolicy.classify(RuntimeException("zip file is corrupt")),
        )
        assertEquals(
            CloudStreamInstallError.INVALID_RESPONSE,
            CloudStreamInstallPolicy.classify(RuntimeException("http 404 returned")),
        )
    }

    @Test
    fun `an unrecognised failure is reported as unknown rather than guessed`() {
        assertEquals(
            CloudStreamInstallError.UNKNOWN,
            CloudStreamInstallPolicy.classify(RuntimeException("something unexpected")),
        )
    }

    // ---- status model -----------------------------------------------------

    @Test
    fun `busy covers downloading and installing only`() {
        assertTrue(CloudStreamInstallStatus(CloudStreamInstallState.DOWNLOADING).isBusy)
        assertTrue(CloudStreamInstallStatus(CloudStreamInstallState.INSTALLING).isBusy)
        assertFalse(CloudStreamInstallStatus(CloudStreamInstallState.INSTALLED).isBusy)
        assertFalse(CloudStreamInstallStatus(CloudStreamInstallState.FAILED).isBusy)
    }

    @Test
    fun `isInstalled is true for every state that implies a package on disk`() {
        assertTrue(CloudStreamInstallStatus(CloudStreamInstallState.INSTALLED).isInstalled)
        assertTrue(CloudStreamInstallStatus(CloudStreamInstallState.ENABLED).isInstalled)
        assertTrue(CloudStreamInstallStatus(CloudStreamInstallState.DISABLED).isInstalled)
        assertTrue(CloudStreamInstallStatus(CloudStreamInstallState.UPDATE_AVAILABLE).isInstalled)

        assertFalse(CloudStreamInstallStatus(CloudStreamInstallState.AVAILABLE).isInstalled)
        assertFalse(CloudStreamInstallStatus(CloudStreamInstallState.DOWNLOADING).isInstalled)
        assertFalse(CloudStreamInstallStatus(CloudStreamInstallState.FAILED).isInstalled)
    }
}
