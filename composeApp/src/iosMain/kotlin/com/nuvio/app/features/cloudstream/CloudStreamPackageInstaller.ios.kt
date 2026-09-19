package com.nuvio.app.features.cloudstream

/**
 * iOS: CloudStream packages are **never downloaded or installed**.
 *
 * CloudStream ships provider logic as Android DEX bytecode, which iOS cannot
 * load under any circumstances, and the platform forbids executing downloaded
 * code regardless. Installing would write permanently unusable files, so the
 * capability is absent rather than merely disabled.
 */
internal actual object CloudStreamPackageInstaller {

    actual val supportsInstallation: Boolean = false

    actual fun isInstalled(plugin: CloudStreamPlugin): Boolean = false

    actual fun installedVersion(plugin: CloudStreamPlugin): Int? = null

    actual suspend fun install(plugin: CloudStreamPlugin): CloudStreamInstallResult =
        CloudStreamInstallResult.Failure(
            pluginId = plugin.id,
            error = CloudStreamInstallError.UNSUPPORTED,
            message = "CloudStream extensions are Android-only and cannot be installed on iOS.",
        )

    actual fun remove(plugin: CloudStreamPlugin): Boolean = false
}
