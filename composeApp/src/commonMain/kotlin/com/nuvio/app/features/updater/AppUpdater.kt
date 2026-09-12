package com.nuvio.app.features.updater

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private const val gitHubOwner = "sascio"
private const val gitHubRepo = "MyBridge"
private const val gitHubApiBase = "https://api.github.com"

data class AppUpdate(
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?,
)

data class AppUpdaterUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showDialog: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null,
    val isDebugTest: Boolean = false,
)

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("target_commitish") val targetCommitish: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
private data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
)

private val appUpdaterJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private object AppUpdaterRepository {
    suspend fun lookupLatestUpdate(): AppUpdateLookup {
        val response = try {
            httpRequestRaw(
                method = "GET",
                url = "$gitHubApiBase/repos/$gitHubOwner/$gitHubRepo/releases?per_page=20",
                headers = mapOf(
                    "Accept" to "application/vnd.github+json",
                    "User-Agent" to "StreamBridge",
                ),
                body = "",
            )
        } catch (error: Throwable) {
            return AppUpdateLookup.RequestFailed(
                error.message?.takeIf { it.isNotBlank() } ?: getString(Res.string.updates_check_failed),
            )
        }

        AppUpdateReleaseSelector.classifyHttpStatus(
            status = response.status,
            errorMessage = getString(Res.string.updates_github_api_error, response.status),
        )?.let { return it }

        val releases = try {
            appUpdaterJson.decodeFromString<List<GitHubReleaseDto>>(response.body)
        } catch (error: Throwable) {
            return AppUpdateLookup.RequestFailed(
                error.message?.takeIf { it.isNotBlank() } ?: getString(Res.string.updates_check_failed),
            )
        }

        return AppUpdateReleaseSelector.classifyDecodedReleases(
            releases = releases.map { release ->
                AppUpdateReleaseCandidate(
                    tagName = release.tagName,
                    name = release.name,
                    body = release.body,
                    draft = release.draft,
                    prerelease = release.prerelease,
                    htmlUrl = release.htmlUrl,
                    assets = release.assets.map { asset ->
                        AppUpdateAssetCandidate(
                            name = asset.name,
                            browserDownloadUrl = asset.browserDownloadUrl,
                            size = asset.size,
                            contentType = asset.contentType,
                        )
                    },
                )
            },
            supportedAbis = AppUpdaterPlatform.getSupportedAbis(),
        )
    }
}

class AppUpdaterController internal constructor(
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(AppUpdaterUiState())
    val uiState: StateFlow<AppUpdaterUiState> = _uiState.asStateFlow()

    private var autoCheckStarted = false

    fun ensureAutoCheckStarted() {
        if (autoCheckStarted || !AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            return
        }
        autoCheckStarted = true
        checkForUpdates(force = false, showNoUpdateFeedback = false)
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            if (showNoUpdateFeedback) {
                scope.launch {
                    NuvioToastController.show(getString(Res.string.updates_not_available))
                }
            }
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isChecking = true,
                    errorMessage = null,
                    showUnknownSourcesDialog = false,
                    isDebugTest = false,
                )
            }

            val ignoredTag = AppUpdaterPlatform.getIgnoredTag()
            val lookup = AppUpdaterRepository.lookupLatestUpdate()
            val feedback = appUpdateFeedback(
                lookup = lookup,
                localVersion = AppVersionConfig.VERSION_NAME,
                manual = showNoUpdateFeedback,
            )

            when (feedback) {
                AppUpdateUserFeedback.ShowUpdate -> {
                    val update = (lookup as AppUpdateLookup.Available).update
                    val ignored = ignoredTag != null && ignoredTag == update.tag
                    _uiState.update { state ->
                        state.copy(
                            isChecking = false,
                            update = update,
                            isUpdateAvailable = true,
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = state.downloadedApkPath,
                            showDialog = force || !ignored,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                        )
                    }
                }
                AppUpdateUserFeedback.UpToDate -> {
                    _uiState.update { state ->
                        state.copy(
                            isChecking = false,
                            update = null,
                            isUpdateAvailable = false,
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            showDialog = false,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                        )
                    }
                    NuvioToastController.show(getString(Res.string.updates_latest_version))
                }
                AppUpdateUserFeedback.CheckFailed -> {
                    val message = (lookup as? AppUpdateLookup.RequestFailed)?.message
                        ?: getString(Res.string.updates_check_failed)
                    _uiState.update { state ->
                        state.copy(
                            isChecking = false,
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            update = null,
                            isUpdateAvailable = false,
                            showDialog = false,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                        )
                    }
                    NuvioToastController.show(message)
                }
                AppUpdateUserFeedback.Silent -> {
                    _uiState.update { state ->
                        state.copy(
                            isChecking = false,
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            update = null,
                            isUpdateAvailable = false,
                            showDialog = false,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                        )
                    }
                }
            }
        }
    }

    fun dismissDialog() {
        _uiState.update { state ->
            state.copy(
                showDialog = false,
                showUnknownSourcesDialog = false,
                errorMessage = null,
            )
        }
    }

    fun ignoreThisVersion() {
        val tag = _uiState.value.update?.tag ?: return
        AppUpdaterPlatform.setIgnoredTag(tag)
        dismissDialog()
    }

    fun downloadUpdate() {
        val update = _uiState.value.update ?: return
        if (_uiState.value.isDebugTest) {
            runDebugDownloadTest()
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    errorMessage = null,
                )
            }

            AppUpdaterPlatform.downloadApk(
                assetUrl = update.assetUrl,
                assetName = update.assetName,
            ) { downloadedBytes, totalBytes ->
                val progress = if (totalBytes != null && totalBytes > 0L) {
                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                _uiState.update { state -> state.copy(downloadProgress = progress) }
            }.onSuccess { path ->
                _uiState.update { state ->
                    state.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = path,
                        errorMessage = null,
                    )
                }
                installDownloadedUpdate()
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = null,
                        errorMessage = error.message ?: getString(Res.string.updates_download_failed),
                        showDialog = true,
                    )
                }
            }
        }
    }

    fun installDownloadedUpdate() {
        val apkPath = _uiState.value.downloadedApkPath ?: return
        if (!AppUpdaterPlatform.canRequestPackageInstalls()) {
            _uiState.update { state -> state.copy(showUnknownSourcesDialog = true, showDialog = true) }
            return
        }

        AppUpdaterPlatform.installDownloadedApk(apkPath).onSuccess {
            _uiState.update { state -> state.copy(showUnknownSourcesDialog = false) }
        }.onFailure { error ->
            scope.launch {
                val fallbackMessage = error.message ?: getString(Res.string.updates_install_failed)
                _uiState.update { state ->
                    state.copy(
                        errorMessage = fallbackMessage,
                        showDialog = true,
                    )
                }
            }
        }
    }

    fun resumeInstallation() {
        if (AppUpdaterPlatform.canRequestPackageInstalls()) {
            installDownloadedUpdate()
        } else {
            AppUpdaterPlatform.openUnknownSourcesSettings()
        }
    }

    fun showDebugTestUpdate() {
        if (!AppUpdaterPlatform.isDebugBuild || !AppUpdaterPlatform.isSupported) return

        _uiState.value = AppUpdaterUiState(
            update = AppUpdate(
                tag = "9.9.9",
                title = "StreamBridge 9.9.9",
                notes = """
                    A local preview of the new update experience.

                    - The banner pushes the app content down.
                    - Download progress fills the banner with the primary accent.
                    - Release notes live behind the info button.
                """.trimIndent(),
                releaseUrl = null,
                assetName = "StreamBridge-debug-preview.apk",
                assetUrl = "debug://update-preview",
                assetSizeBytes = 185L * 1024L * 1024L,
            ),
            isUpdateAvailable = true,
            showDialog = true,
            isDebugTest = true,
        )
    }

    private fun runDebugDownloadTest() {
        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    errorMessage = null,
                )
            }

            for (step in 1..100) {
                delay(35)
                _uiState.update { state -> state.copy(downloadProgress = step / 100f) }
            }

            _uiState.update { state ->
                state.copy(
                    isDownloading = false,
                    isUpdateAvailable = false,
                    downloadProgress = 1f,
                )
            }
        }
    }
}

@Composable
fun rememberAppUpdaterController(): AppUpdaterController {
    val scope = rememberCoroutineScope()
    return remember(scope) { AppUpdaterController(scope) }
}

internal fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val units = listOf("B", "KB", "MB", "GB")
    var value = sizeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val roundedValue = if (value >= 10 || unitIndex == 0) {
        value.toInt().toString()
    } else {
        ((value * 10).toInt() / 10.0).toString()
    }
    return "$roundedValue ${localizedByteUnit(units[unitIndex])}"
}
