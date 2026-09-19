package com.nuvio.app.features.cloudstream

/**
 * Play Store distribution: CloudStream packages are **never downloaded or
 * installed**.
 *
 * This build cannot execute a `.cs3` (see [CloudStreamPlatformRuntime]), so
 * installing one would only write unusable code to storage. The capability is
 * absent from the binary rather than disabled by a flag, and the reported
 * failure states the truth instead of pretending the install merely failed.
 */
internal actual object CloudStreamPackageInstaller {

    actual val supportsInstallation: Boolean = false

    actual fun isInstalled(plugin: CloudStreamPlugin): Boolean = false

    actual fun installedVersion(plugin: CloudStreamPlugin): Int? = null

    actual suspend fun install(plugin: CloudStreamPlugin): CloudStreamInstallResult =
        CloudStreamInstallResult.Failure(
            pluginId = plugin.id,
            error = CloudStreamInstallError.UNSUPPORTED,
            message = "This build cannot run CloudStream extensions, so they cannot be installed.",
        )

    actual fun remove(plugin: CloudStreamPlugin): Boolean = false
}
