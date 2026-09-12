package com.nuvio.app.features.updater

internal object AppUpdateVersion {
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun parseVersionParts(raw: String?): List<Int>? {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return null

        val parts = normalized.split('.', '-', '_')
            .filter { it.isNotBlank() }
            .mapNotNull { token -> token.takeWhile { it.isDigit() }.toIntOrNull() }

        return parts.takeIf { it.isNotEmpty() }
    }

    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val remoteParts = parseVersionParts(remote)
        val localParts = parseVersionParts(local)

        if (remoteParts == null || localParts == null) {
            val remoteValue = normalize(remote)
            val localValue = normalize(local)
            return remoteValue.isNotBlank() && localValue.isNotBlank() && remoteValue != localValue
        }

        val maxSize = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until maxSize) {
            val remoteValue = remoteParts.getOrElse(index) { 0 }
            val localValue = localParts.getOrElse(index) { 0 }
            if (remoteValue != localValue) return remoteValue > localValue
        }
        return false
    }
}

internal data class AppUpdateReleaseCandidate(
    val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val htmlUrl: String? = null,
    val assets: List<AppUpdateAssetCandidate> = emptyList(),
)

internal data class AppUpdateAssetCandidate(
    val name: String,
    val browserDownloadUrl: String,
    val size: Long? = null,
    val contentType: String? = null,
)

internal sealed class AppUpdateLookup {
    data class Available(val update: AppUpdate) : AppUpdateLookup()
    data object NoCompatibleUpdate : AppUpdateLookup()
    data class RequestFailed(val message: String) : AppUpdateLookup()
}

internal enum class AppUpdateUserFeedback {
    ShowUpdate,
    UpToDate,
    CheckFailed,
    Silent,
}

internal fun assetNameMatchesAbi(name: String, abi: String): Boolean {
    val haystack = name.lowercase()
    val needle = abi.lowercase()
    if (needle == "x86") {
        return haystack.contains("x86") && !haystack.contains("x86_64")
    }
    return haystack.contains(needle)
}

internal object AppUpdateReleaseSelector {
    fun firstStableRelease(
        releases: List<AppUpdateReleaseCandidate>,
    ): AppUpdateReleaseCandidate? = releases.firstOrNull { !it.draft && !it.prerelease }

    fun chooseBestApkAsset(
        assets: List<AppUpdateAssetCandidate>,
        supportedAbis: List<String>,
    ): AppUpdateAssetCandidate? {
        val apkAssets = assets.filter { asset ->
            val isApk = asset.name.endsWith(".apk", ignoreCase = true) ||
                asset.contentType == "application/vnd.android.package-archive"
            isApk && asset.browserDownloadUrl.startsWith("https://", ignoreCase = true)
        }
        if (apkAssets.isEmpty()) return null
        if (apkAssets.size == 1) return apkAssets.first()

        for (abi in supportedAbis) {
            val candidate = apkAssets.firstOrNull { asset ->
                assetNameMatchesAbi(asset.name, abi)
            }
            if (candidate != null) return candidate
        }

        return apkAssets.firstOrNull { asset ->
            val name = asset.name.lowercase()
            name.contains("universal") || name.contains("all")
        } ?: apkAssets.first()
    }

    fun classifyHttpStatus(status: Int, errorMessage: String): AppUpdateLookup? {
        if (status in 200..299) return null
        return AppUpdateLookup.RequestFailed(errorMessage)
    }

    fun classifyDecodedReleases(
        releases: List<AppUpdateReleaseCandidate>,
        supportedAbis: List<String>,
    ): AppUpdateLookup {
        val release = firstStableRelease(releases) ?: return AppUpdateLookup.NoCompatibleUpdate
        val tag = release.tagName?.takeIf { it.isNotBlank() }
            ?: release.name?.takeIf { it.isNotBlank() }
            ?: return AppUpdateLookup.NoCompatibleUpdate
        val asset = chooseBestApkAsset(release.assets, supportedAbis)
            ?: return AppUpdateLookup.NoCompatibleUpdate
        return AppUpdateLookup.Available(
            AppUpdate(
                tag = tag,
                title = release.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = release.body.orEmpty(),
                releaseUrl = release.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size,
            ),
        )
    }
}

internal fun appUpdateFeedback(
    lookup: AppUpdateLookup,
    localVersion: String,
    manual: Boolean,
): AppUpdateUserFeedback {
    return when (lookup) {
        is AppUpdateLookup.Available -> {
            if (AppUpdateVersion.isRemoteNewer(lookup.update.tag, localVersion)) {
                AppUpdateUserFeedback.ShowUpdate
            } else if (manual) {
                AppUpdateUserFeedback.UpToDate
            } else {
                AppUpdateUserFeedback.Silent
            }
        }
        AppUpdateLookup.NoCompatibleUpdate -> {
            if (manual) AppUpdateUserFeedback.UpToDate else AppUpdateUserFeedback.Silent
        }
        is AppUpdateLookup.RequestFailed -> {
            if (manual) AppUpdateUserFeedback.CheckFailed else AppUpdateUserFeedback.Silent
        }
    }
}
