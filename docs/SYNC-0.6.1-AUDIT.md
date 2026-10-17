# Upstream sync audit: NuvioMobile Official and Enhanced 0.6.1-beta

Status: AUDIT ONLY. No upstream code has been merged into StreamBridge yet.

## 1. Verified upstream revisions (checked 2026-10-17)

| Source | Repo | Tag | Peeled commit | Tag type | GitHub release |
|---|---|---|---|---|---|
| Official | https://github.com/NuvioMedia/NuvioMobile | `0.6.1-beta` | `79b750f3ed9e196c600bc1f5fd97979d6336760f` (2026-10-16, "bump version") | lightweight | published, not prerelease |
| Enhanced | https://github.com/luqmanfadlli/NuvioMobile-Enhanced | `0.6.1-beta` | `504014d360bf1a1f844246f527c27c208738246e` (2026-10-16, "fix(ios): merge duplicate MetalLayer.nextDrawable overrides") | lightweight | published, not prerelease |

Both tags exist and are different commits. Versions are not assumed to match across repos.

Current StreamBridge pins (streambridge.version.properties):
- Official: `0.5.5-beta` = `5c6028f24d3017f27fc4aeb0b6234b18fcfd2ee8`
- Enhanced: `8b3bd867e172757c9fd7373da301e6489914f3fc` (release label says 0.5.5-beta)
- Enhanced `8b3bd867` is an ancestor of Enhanced `0.6.1-beta`.
- Official `0.6.0-beta` = `f2e537861f34a2807cc66dda995ed3adb1658315` (from the earlier audit).

## 2. Size of the upstream deltas

| Delta | Files | Insertions | Deletions | Commits |
|---|---|---|---|---|
| Official `5c6028f2` → `0.6.1-beta` | 343 | +32501 | −854 | 218 |
| Enhanced `8b3bd867` → `0.6.1-beta` | 501 | +54704 | −3784 | 423 |

## 3. Three-way merge audit (git merge-tree, no worktree or branch changes)

StreamBridge does not share git history with upstream (it was built as an overlay copy), so a synthetic commit was created: StreamBridge HEAD tree on top of the upstream base commit. This lets `git merge-tree` compute a real three-way merge. Synthetic commits are local objects only and are not pushed.

- Official: base `5c6028f2`, ours = StreamBridge `b629fae`, theirs = `79b750f3`. **44 conflicting files.**
- Enhanced: base `8b3bd867`, ours = StreamBridge `b629fae`, theirs = `504014d3`. **25 conflicting files.** 13 of these also conflict in the Official merge.

Enhanced-only conflicts, which should NOT be taken from upstream:
- `.github/workflows/ios-test-build.yml`, `.github/workflows/mobile-release.yml`, and `scripts/generate-release-notes.sh` (upstream deleted these; StreamBridge has its own CI and release workflows).
- `MPVKit` (modify/delete). StreamBridge's iOS player setup differs.

## 4. Excluded scope (must not change)

Official 0.6.1 changes these MDBList files, which are out of scope: `features/mdblist/*` (7 source files), `features/settings/MdbListLibraryListsPicker.kt`, `commonTest/.../mdblist/*` (6 test files), `values-it|vi/strings_mdblist.xml`.
Policy: keep the StreamBridge version of any MDBList file in a merge. Document any compile-only adjustment.
No Trakt or Simkl paths are in the upstream diff conflicts.

## 5. Official conflict list (44)

- `composeApp/src/androidFull/kotlin/com/nuvio/app/features/details/components/HeroTrailerPlayerSurface.android.kt`
- `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/PlayerAutoPlayTest.kt`
- `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt`
- `composeApp/src/commonMain/composeResources/values-bn/strings.xml`
- `composeApp/src/commonMain/composeResources/values/strings.xml`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/AppGate.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/PlayerDestination.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/StreamDestination.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/network/SupabaseProvider.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/catalog/CatalogRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/catalog/CatalogTarget.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsScreen.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/DetailActionButtons.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeHeroSection.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibrarySavedContent.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryScreen.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControlActions.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControls.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreen.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeEffects.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeGestureActions.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeState.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeUi.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerStreamsRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/AvatarRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/AppearanceSettingsPage.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/ContentDiscoverySettingsPage.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsModels.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsRootPage.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsScreen.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/PlaybackAvailability.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/ProviderFilterRow.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamsRepository.kt`
- `composeApp/src/fullCommonMain/kotlin/com/nuvio/app/features/trailer/InAppYouTubeExtractor.kt`
- `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerBridge.kt`
- `iosApp/Configuration/Version.xcconfig`
- `iosApp/iosApp/Player/MPVPlayerBridge.swift`
- `iosApp/iosApp/Player/MetalLayer.swift`
- `iosApp/iosApp/SystemUI.swift`
- `store.json`

Plus MPVKit (modify/delete, see section 3).

## 6. Enhanced conflict list (25)

- `CONFLICT (modify/delete): .github/workflows/ios-test-build.yml deleted in 762d1e5e5565ffd250fb9b6428364877c3993ac0 and modified in 504014d360bf1a1f844246f527c27c208738246e.  Version 504014d360bf1a1f844246f527c27c20873
- `CONFLICT (modify/delete): .github/workflows/mobile-release.yml deleted in 762d1e5e5565ffd250fb9b6428364877c3993ac0 and modified in 504014d360bf1a1f844246f527c27c208738246e.  Version 504014d360bf1a1f844246f527c27c20873
- `CONFLICT (modify/delete): MPVKit deleted in 762d1e5e5565ffd250fb9b6428364877c3993ac0 and modified in 504014d360bf1a1f844246f527c27c208738246e.  Version 504014d360bf1a1f844246f527c27c208738246e of MPVKit left in tree.`
- `README.md`
- `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/PlayerAutoPlayTest.kt`
- `composeApp/src/commonMain/composeResources/values-bn/strings.xml`
- `composeApp/src/commonMain/composeResources/values/strings.xml`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/AppNavigationSupport.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/AppShellComponents.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimePlaybackActions.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/AvatarRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/ContentDiscoverySettingsPage.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsModels.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsRootPage.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsScreen.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsSearch.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/PlaybackAvailability.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamsRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/updater/AppUpdaterRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/navigation/Routes.kt`
- `iosApp/Configuration/Version.xcconfig`
- `scripts/build-ios-ipa.sh`
- `CONFLICT (modify/delete): scripts/generate-release-notes.sh deleted in 762d1e5e5565ffd250fb9b6428364877c3993ac0 and modified in 504014d360bf1a1f844246f527c27c208738246e.  Version 504014d360bf1a1f844246f527c27c20873824
- `store.json`
