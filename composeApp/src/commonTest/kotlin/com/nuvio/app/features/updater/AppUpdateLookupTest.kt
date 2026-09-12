package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateLookupTest {

    @Test
    fun remote_0_1_02_is_newer_than_local_0_1_01() {
        assertTrue(AppUpdateVersion.isRemoteNewer("0.1.02", "0.1.01"))
        assertTrue(AppUpdateVersion.isRemoteNewer("v0.1.02", "0.1.01"))
    }

    @Test
    fun same_0_1_01_is_not_newer() {
        assertFalse(AppUpdateVersion.isRemoteNewer("0.1.01", "0.1.01"))
        assertFalse(AppUpdateVersion.isRemoteNewer("v0.1.01", "0.1.01"))
        assertFalse(AppUpdateVersion.isRemoteNewer("0.1.00", "0.1.01"))
    }

    @Test
    fun empty_or_prerelease_or_draft_is_no_compatible_update() {
        assertEquals(
            AppUpdateLookup.NoCompatibleUpdate,
            AppUpdateReleaseSelector.classifyDecodedReleases(emptyList(), arm64Abis),
        )
        assertEquals(
            AppUpdateLookup.NoCompatibleUpdate,
            AppUpdateReleaseSelector.classifyDecodedReleases(
                listOf(release(tag = "0.1.02", prerelease = true, apk = true)),
                arm64Abis,
            ),
        )
        assertEquals(
            AppUpdateLookup.NoCompatibleUpdate,
            AppUpdateReleaseSelector.classifyDecodedReleases(
                listOf(release(tag = "0.1.02", draft = true, apk = true)),
                arm64Abis,
            ),
        )
    }

    @Test
    fun missing_apk_on_stable_release_is_no_compatible_update() {
        assertEquals(
            AppUpdateLookup.NoCompatibleUpdate,
            AppUpdateReleaseSelector.classifyDecodedReleases(
                listOf(release(tag = "0.1.02", apk = false)),
                arm64Abis,
            ),
        )
    }

    @Test
    fun http_error_is_request_failed_not_up_to_date() {
        val lookup = AppUpdateReleaseSelector.classifyHttpStatus(500, "GitHub releases API error: 500")
        assertTrue(lookup is AppUpdateLookup.RequestFailed)
    }

    @Test
    fun rejects_http_apk_assets() {
        val selected = AppUpdateReleaseSelector.chooseBestApkAsset(
            assets = listOf(
                AppUpdateAssetCandidate(
                    name = "StreamBridge-arm64-v8a.apk",
                    browserDownloadUrl = "http://evil.example/StreamBridge.apk",
                ),
            ),
            supportedAbis = arm64Abis,
        )
        assertEquals(null, selected)
    }

    @Test
    fun prefers_matching_abi_apk_over_universal() {
        val selected = AppUpdateReleaseSelector.chooseBestApkAsset(
            assets = listOf(
                AppUpdateAssetCandidate(
                    name = "StreamBridge-universal.apk",
                    browserDownloadUrl = "https://example.test/universal.apk",
                ),
                AppUpdateAssetCandidate(
                    name = "StreamBridge-arm64-v8a.apk",
                    browserDownloadUrl = "https://example.test/arm64.apk",
                ),
            ),
            supportedAbis = arm64Abis,
        )
        assertEquals("StreamBridge-arm64-v8a.apk", selected?.name)
    }

    @Test
    fun prefers_nuvio_style_arm64_filename_over_x86() {
        val selected = AppUpdateReleaseSelector.chooseBestApkAsset(
            assets = listOf(
                AppUpdateAssetCandidate(
                    name = "androidApp-full-x86_64-release.apk",
                    browserDownloadUrl = "https://example.test/x86_64.apk",
                ),
                AppUpdateAssetCandidate(
                    name = "androidApp-full-armeabi-v7a-release.apk",
                    browserDownloadUrl = "https://example.test/v7a.apk",
                ),
                AppUpdateAssetCandidate(
                    name = "androidApp-full-arm64-v8a-release.apk",
                    browserDownloadUrl = "https://example.test/arm64.apk",
                ),
            ),
            supportedAbis = arm64Abis,
        )
        assertEquals("androidApp-full-arm64-v8a-release.apk", selected?.name)
    }

    @Test
    fun x86_abi_does_not_select_x86_64_apk() {
        val selected = AppUpdateReleaseSelector.chooseBestApkAsset(
            assets = listOf(
                AppUpdateAssetCandidate(
                    name = "androidApp-full-x86_64-release.apk",
                    browserDownloadUrl = "https://example.test/x86_64.apk",
                ),
                AppUpdateAssetCandidate(
                    name = "androidApp-full-x86-release.apk",
                    browserDownloadUrl = "https://example.test/x86.apk",
                ),
            ),
            supportedAbis = listOf("x86"),
        )
        assertEquals("androidApp-full-x86-release.apk", selected?.name)
    }

    @Test
    fun skips_prerelease_then_picks_first_stable() {
        val lookup = AppUpdateReleaseSelector.classifyDecodedReleases(
            listOf(
                release(tag = "0.1.03-beta", prerelease = true, apk = true),
                release(tag = "0.1.02", apk = true),
            ),
            arm64Abis,
        )
        val available = lookup as AppUpdateLookup.Available
        assertEquals("0.1.02", available.update.tag)
    }

    @Test
    fun picks_highest_stable_version_even_when_an_older_release_is_listed_first() {
        val lookup = AppUpdateReleaseSelector.classifyDecodedReleases(
            listOf(
                release(tag = "0.1.01", apk = true),
                release(tag = "0.1.02", apk = true),
            ),
            arm64Abis,
        )
        val available = lookup as AppUpdateLookup.Available
        assertEquals("0.1.02", available.update.tag)
    }

    @Test
    fun skips_newer_stable_without_apk_and_picks_next_compatible_stable() {
        val lookup = AppUpdateReleaseSelector.classifyDecodedReleases(
            listOf(
                release(tag = "0.1.03", apk = false),
                release(tag = "0.1.02", apk = true),
            ),
            arm64Abis,
        )
        val available = lookup as AppUpdateLookup.Available
        assertEquals("0.1.02", available.update.tag)
    }

    @Test
    fun manual_check_shows_update_for_0_1_02_and_up_to_date_for_current_or_empty() {
        val newer = AppUpdateLookup.Available(sampleUpdate("0.1.02"))
        val current = AppUpdateLookup.Available(sampleUpdate("0.1.01"))
        assertEquals(
            AppUpdateUserFeedback.ShowUpdate,
            appUpdateFeedback(newer, localVersion = "0.1.01", manual = true),
        )
        assertEquals(
            AppUpdateUserFeedback.UpToDate,
            appUpdateFeedback(current, localVersion = "0.1.01", manual = true),
        )
        assertEquals(
            AppUpdateUserFeedback.UpToDate,
            appUpdateFeedback(AppUpdateLookup.NoCompatibleUpdate, localVersion = "0.1.01", manual = true),
        )
        assertEquals(
            AppUpdateUserFeedback.CheckFailed,
            appUpdateFeedback(
                AppUpdateLookup.RequestFailed("network"),
                localVersion = "0.1.01",
                manual = true,
            ),
        )
    }

    @Test
    fun auto_check_is_silent_unless_a_newer_apk_exists() {
        assertEquals(
            AppUpdateUserFeedback.ShowUpdate,
            appUpdateFeedback(
                AppUpdateLookup.Available(sampleUpdate("0.1.02")),
                localVersion = "0.1.01",
                manual = false,
            ),
        )
        assertEquals(
            AppUpdateUserFeedback.Silent,
            appUpdateFeedback(
                AppUpdateLookup.Available(sampleUpdate("0.1.01")),
                localVersion = "0.1.01",
                manual = false,
            ),
        )
        assertEquals(
            AppUpdateUserFeedback.Silent,
            appUpdateFeedback(AppUpdateLookup.NoCompatibleUpdate, localVersion = "0.1.01", manual = false),
        )
        assertEquals(
            AppUpdateUserFeedback.Silent,
            appUpdateFeedback(
                AppUpdateLookup.RequestFailed("network"),
                localVersion = "0.1.01",
                manual = false,
            ),
        )
    }

    private fun sampleUpdate(tag: String): AppUpdate = AppUpdate(
        tag = tag,
        title = "StreamBridge $tag",
        notes = "",
        releaseUrl = "https://github.com/sascio/MyBridge/releases/tag/$tag",
        assetName = "StreamBridge-$tag-arm64-v8a.apk",
        assetUrl = "https://example.test/StreamBridge-$tag.apk",
        assetSizeBytes = 10L,
    )

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        apk: Boolean,
    ): AppUpdateReleaseCandidate = AppUpdateReleaseCandidate(
        tagName = tag,
        name = tag,
        draft = draft,
        prerelease = prerelease,
        htmlUrl = "https://github.com/sascio/MyBridge/releases/tag/$tag",
        assets = if (apk) {
            listOf(
                AppUpdateAssetCandidate(
                    name = "StreamBridge-$tag-arm64-v8a.apk",
                    browserDownloadUrl = "https://example.test/$tag.apk",
                    size = 10L,
                ),
            )
        } else {
            emptyList()
        },
    )

    companion object {
        private val arm64Abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    }
}
