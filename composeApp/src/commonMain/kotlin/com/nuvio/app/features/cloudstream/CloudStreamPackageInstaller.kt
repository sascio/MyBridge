package com.nuvio.app.features.cloudstream

/**
 * Platform installer for CloudStream `.cs3` packages.
 *
 * Mirrors the execution boundary in [CloudStreamPlatformRuntime]: only a
 * distribution permitted to *run* extensions is permitted to *install* them.
 * Where execution is impossible, installing would simply litter storage with
 * code that can never be used, so those targets report
 * [CloudStreamInstallError.UNSUPPORTED] instead.
 *
 * Implementations must verify before committing: a package that fails its
 * hash or structural checks must never end up in the install directory.
 */
internal expect object CloudStreamPackageInstaller {

    /** Whether this distribution can install CloudStream packages at all. */
    val supportsInstallation: Boolean

    /** True when a verified package for [plugin] is present on disk. */
    fun isInstalled(plugin: CloudStreamPlugin): Boolean

    /** Version recorded at install time, or null when not installed. */
    fun installedVersion(plugin: CloudStreamPlugin): Int?

    /**
     * Downloads, verifies and installs [plugin].
     *
     * Existing installations are only replaced once the replacement has passed
     * verification, so a failed update leaves the working package in place.
     */
    suspend fun install(plugin: CloudStreamPlugin): CloudStreamInstallResult

    /** Removes an installed package. Returns true when something was removed. */
    fun remove(plugin: CloudStreamPlugin): Boolean
}
