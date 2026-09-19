package com.nuvio.app.features.cloudstream

/**
 * Installation lifecycle for a CloudStream extension package.
 *
 * `Installed` and `Enabled` are deliberately distinct from one another and from
 * `Available`: an extension is only ever shown as installed once its `.cs3` has
 * been downloaded, hash-verified and committed to private storage, and only
 * ever shown as enabled once the user turned it on *after* a successful
 * install. A failed install can never present as enabled.
 */
enum class CloudStreamInstallState {
    /** Discovered in a repository, nothing downloaded yet. */
    AVAILABLE,

    /** The `.cs3` is being fetched. */
    DOWNLOADING,

    /** Downloaded; being verified and committed to private storage. */
    INSTALLING,

    /** Verified and committed. Providers can be loaded from it. */
    INSTALLED,

    /** Installed and switched on by the user; participates in aggregation. */
    ENABLED,

    /** Installed but switched off; must not participate in aggregation. */
    DISABLED,

    /** The most recent install/update attempt failed. See the message. */
    FAILED,

    /**
     * A newer version exists in the repository than the one installed.
     * The installed version keeps working until the update succeeds.
     */
    UPDATE_AVAILABLE,
}

/**
 * Why an install/update attempt failed.
 *
 * Every case maps to a distinct, actionable user-facing message: the point is
 * that the user can tell a dead network apart from a tampered artifact.
 */
enum class CloudStreamInstallError {
    /** Repository or CDN unreachable, DNS failure, connection reset. */
    NETWORK_UNREACHABLE,

    /** The download did not complete in time. */
    TIMEOUT,

    /** Non-2xx HTTP status, or an HTML error page instead of a package. */
    INVALID_RESPONSE,

    /** Digest did not match the repository's published `fileHash`. */
    HASH_MISMATCH,

    /** Not a readable ZIP, or missing `manifest.json` / `classes.dex`. */
    CORRUPT_PACKAGE,

    /** `manifest.json` unreadable or missing required fields. */
    MALFORMED_MANIFEST,

    /** `pluginClassName` absent or not a valid class name. */
    INVALID_PLUGIN_CLASS,

    /** The plugin cannot run on this distribution at all. */
    UNSUPPORTED,

    /** Not enough space to stage and commit the package. */
    INSUFFICIENT_STORAGE,

    /** The extension is no longer present in any configured repository. */
    REPOSITORY_GONE,

    /** Anything else; the message carries the detail. */
    UNKNOWN,
}

/** Outcome of an install, update or removal attempt. */
sealed interface CloudStreamInstallResult {
    data class Success(val pluginId: String, val version: Int?) : CloudStreamInstallResult

    data class Failure(
        val pluginId: String,
        val error: CloudStreamInstallError,
        val message: String,
    ) : CloudStreamInstallResult
}

/**
 * Per-extension installation status held in UI state.
 *
 * [installedVersion] is what is actually on disk, which is what makes an
 * honest "update available" decision possible: it is compared against the
 * version the repository currently advertises.
 */
data class CloudStreamInstallStatus(
    val state: CloudStreamInstallState = CloudStreamInstallState.AVAILABLE,
    val installedVersion: Int? = null,
    val errorMessage: String? = null,
    val error: CloudStreamInstallError? = null,
) {
    val isBusy: Boolean
        get() = state == CloudStreamInstallState.DOWNLOADING ||
            state == CloudStreamInstallState.INSTALLING

    val isInstalled: Boolean
        get() = state == CloudStreamInstallState.INSTALLED ||
            state == CloudStreamInstallState.ENABLED ||
            state == CloudStreamInstallState.DISABLED ||
            state == CloudStreamInstallState.UPDATE_AVAILABLE
}

/**
 * Decides installation state from facts, never from optimism.
 *
 * Kept pure so the rules that stop a failed install from presenting as
 * "Enabled" are unit-testable without storage or networking.
 */
internal object CloudStreamInstallPolicy {

    /**
     * Resolves the state shown for an extension.
     *
     * Ordering matters: a package that is not on disk can never be enabled,
     * and a recorded failure outranks a stale enabled flag.
     */
    fun resolveState(
        isInstalledOnDisk: Boolean,
        isEnabled: Boolean,
        installedVersion: Int?,
        repositoryVersion: Int?,
        failure: CloudStreamInstallError?,
        isBusy: CloudStreamInstallState? = null,
    ): CloudStreamInstallState {
        isBusy?.let { busy ->
            if (busy == CloudStreamInstallState.DOWNLOADING ||
                busy == CloudStreamInstallState.INSTALLING
            ) {
                return busy
            }
        }
        if (!isInstalledOnDisk) {
            return if (failure != null) {
                CloudStreamInstallState.FAILED
            } else {
                CloudStreamInstallState.AVAILABLE
            }
        }
        // Installed on disk. A failed *update* must not hide the working install.
        if (hasUpdate(installedVersion, repositoryVersion)) {
            return CloudStreamInstallState.UPDATE_AVAILABLE
        }
        return if (isEnabled) CloudStreamInstallState.ENABLED else CloudStreamInstallState.DISABLED
    }

    /** True when the repository advertises a strictly newer version. */
    fun hasUpdate(installedVersion: Int?, repositoryVersion: Int?): Boolean {
        if (installedVersion == null || repositoryVersion == null) return false
        return repositoryVersion > installedVersion
    }

    /** Whether a package may be enabled: it must genuinely be installed. */
    fun canEnable(isInstalledOnDisk: Boolean, isExecutable: Boolean): Boolean =
        isInstalledOnDisk && isExecutable

    /** Maps a thrown error to a stable, user-actionable classification. */
    fun classify(error: Throwable): CloudStreamInstallError {
        val message = (error.message ?: "").lowercase()
        return when {
            "sha-256" in message || "hash" in message -> CloudStreamInstallError.HASH_MISMATCH
            "timeout" in message || "timed out" in message -> CloudStreamInstallError.TIMEOUT
            "space" in message || "enospc" in message -> CloudStreamInstallError.INSUFFICIENT_STORAGE
            "manifest" in message -> CloudStreamInstallError.MALFORMED_MANIFEST
            "pluginclassname" in message || "class name" in message ->
                CloudStreamInstallError.INVALID_PLUGIN_CLASS
            "zip" in message || "corrupt" in message || "classes.dex" in message ->
                CloudStreamInstallError.CORRUPT_PACKAGE
            "http" in message || "404" in message || "403" in message || "500" in message ->
                CloudStreamInstallError.INVALID_RESPONSE
            "unable to resolve host" in message || "connect" in message || "network" in message ->
                CloudStreamInstallError.NETWORK_UNREACHABLE
            else -> CloudStreamInstallError.UNKNOWN
        }
    }
}
