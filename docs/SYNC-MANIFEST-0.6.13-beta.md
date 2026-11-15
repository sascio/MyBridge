# Enhanced 0.6.13-beta integration manifest

## Source and review status

- StreamBridge application target: **0.1.18**, version code **118**, tag planned as `v0.1.18`.
- Official core source observed for this review: `0.6.12-beta`, commit
  `4135f96cd29f235b7c412f78dcfc13164c4b1e80`. No Official `0.6.13-beta` tag/release was found;
  the official core pin in StreamBridge therefore remains the verified `0.6.12-beta` pin.
- Enhanced source snapshot: tag `0.6.13-beta`, commit
  `cdab5d66183ddfb927992d2715f3c12231fa2ceb`, repository
  `luqmanfadlli/NuvioMobile-Enhanced`. The GitHub compare API reports 59 commits ahead and 82
  changed paths from the prior Enhanced `0.6.12-beta` pin
  `ac86e820e76362284c9d7524bb523976df32e195`: 7 additions, 74 modified text files, and one
  modified MPVKit gitlink; no removals. The local source snapshot is shallow, so the complete commit list/path inventory is
  taken from GitHub's compare response; file contents were reviewed from both tagged trees.
- **Chronology record:** GitHub currently reports the Enhanced release published at
  `2026-11-15T02:58:11Z`; the tagged commit is dated `2026-11-15T01:42:46Z`. Both exact tag and
  commit resolve in the Enhanced repository. This timestamp is recorded as upstream metadata, not
  used to reject the requested source snapshot; it is distinct from the Official release tag's
  verified timestamp (`0.6.12-beta`, `2026-11-12T00:37:11Z`).
- File-content comparison against the target tree: **55 byte-identical**, **26 with
  reviewed StreamBridge-specific deltas**, and no missing files; the remaining path is the MPVKit
  gitlink. These are source reconciliation results, not build/runtime verification.

## Upstream feature groups reviewed

The target range covers automatic fallback to the next matching source; staged player loading;
keyboard playback controls; Continue Watching open/dismiss behavior; shared stream-search sessions
and debrid task handling; settings and playback changes; hero/catalog loading and grid layouts;
Simkl More Like This source handling; iOS Picture-in-Picture/HDR/Dolby Vision, subtitle and native
player-surface changes; collection/trailer layout fixes; translations; and Android startup/home
state updates. The user-facing, StreamBridge-branded summary is in
[`RELEASE-NOTES-0.1.18.md`](RELEASE-NOTES-0.1.18.md) and will feed the existing GitHub-backed What's
New view when a release is published. The What's New cache, localization, seen and dismissal code
was not changed.

## Merge and ownership review

A snapshot-based three-way comparison of the 74 changed text files that existed in the previous
baseline found 64 non-overlapping files and 10 overlapping paths that required explicit resolution.
The seven new source/test/resource files are present. The 10 overlaps are the Indonesian, Bahasa
Indonesia alias, Slovak and Vietnamese locale files; metadata recommendation settings; Home hero;
player stream repository; streams repository; Xcode version configuration; and `store.json`.

- All target keys in the changed English, French, Indonesian, Bahasa Indonesia alias, Polish,
  Slovak and Vietnamese resource files are present (XML key audit: zero missing target keys).
  StreamBridge branding and translated product wording are retained.
- CloudStream aggregation and `parentMetaId` forwarding remain intact. Search identity includes the
  resolved provider configuration/title/year and debrid configuration. Transient debrid updates do
  not count as extra source tasks; the search ends by clearing groups for tasks that were dropped.
- Two previously missed target behaviors were found during the diff review and integrated here:
  the Simkl recommendation-source authentication bit in the metadata settings fingerprint, and
  pending-group cleanup in the main streams repository. A regression test covers cleanup and keeps
  the concrete provider failure detail.
- `iosApp/Configuration/Version.xcconfig` remains StreamBridge-owned at `0.1.18` / `118`.
  `store.json` remains the last published release entry and will not be advanced until the published
  IPA and digest are verified.
- `iosApp/iosApp/ContentView.swift` now restores fold/vertical-bar support, guarded at iOS 27.1.
  IPA, simulator and production-draft workflows request Xcode 27.1 so those symbols are available;
  the app deployment target remains iOS 16.1. This toolchain change is not yet CI-validated.

## MPVKit dependency: Official baseline plus Enhanced delta

Official Nuvio `0.6.12-beta` points to MPVKit gitlink
`da4b7da256a5d67dee74f576762ceb9067fc7a71` in
`https://github.com/NuvioMedia/MPVKit`. That is the base fetched by
`scripts/prepare-ios-dependencies.sh`; the previously used `359a885...` pin alone was the
Enhanced fork's snapshot and was not the Official pin.

Enhanced `0.6.13-beta` points to `359a885342d4eeda757f8e1d2c24fb01fe6d39c2` in
`https://github.com/luqmanfadlli/MPVKit`. Its reviewed delta against Official `da4b7da...` is
four paths: the fork-only MPVKit build-release workflow, and three app dependency files. The
build-release workflow is library CI, not an app dependency, so it is not imported. The three
app dependency changes are preserved as the checked-in patch
[`scripts/patches/mpvkit-enhanced-0.6.13.patch`](../scripts/patches/mpvkit-enhanced-0.6.13.patch),
SHA-256 `cc058085ecfaf65459b71c1d0b3e197cf4f3424a43680a12ebb33f178e0c1c36`:

1. `Package.swift` switches eight Libmpv/FFmpeg binary targets to the Enhanced `nuvio.7` release
   and its SHA-256 pins.
2. `Sources/BuildScripts/XCFrameworkBuild/main.swift` enables the `acompressor` and `alimiter`
   FFmpeg filters.
3. `0007-optional-ios-picture-in-picture.patch` retains the Official PiP bridge and adds the
   Dolby Vision RPU metadata callback and serialization consumed by StreamBridge's PiP converter.

Official's `0008-replay-gop-after-videotoolbox-session-loss.patch` and
`0009-keep-host-audio-session-category.patch` are already present at the official base and remain
unchanged. The patch applies cleanly in the dependency-preparation test, which confirmed the base
commit, both-pin marker, and all three resulting feature checks; a second invocation is idempotent.
The eight Enhanced binary-target checksums match the GitHub release API digests. Direct binary
archive download previously ended with EOF, so downloaded ZIP integrity is **not** locally
verified; the iOS CI build must resolve and checksum-verify them with SwiftPM.

## Validation status

- No Kotlin compilation or iOS build has run against the current dirty worktree. This sandbox has no
  Java runtime; compile/test validation must use Actions with the selected Xcode/JDK/Gradle toolchain.
- The previous successful PR IPA job measured 118m53s for the IPA step and the simulator job
  measured 41m41s on an earlier commit. The current workflow runs those jobs in parallel, but the
  Xcode 27.1 configuration has not yet been measured. A separate Enhanced build measured 51m12s;
  that is not a controlled StreamBridge comparison. No performance-parity claim is made.
- The required final gate remains: a valid unsigned IPA and the full simulator-test job must both
  succeed for the same commit, with the simulator suite neither weakened nor skipped.
- `v0.1.18`, code `118`, and the planned `v0.1.18` tag agree in source metadata. No `v0.1.18`
  release/assets, production Android build verification, merge, or Telegram delivery is claimed here.

## Complete 82-path inventory

`A`/`M` are the target tree's status relative to the prior pin. The disposition column records the
local comparison and the StreamBridge-specific resolution for divergent files.

| Target | Path | Disposition |
|---|---|---|

| `M` | `MPVKit` | Official base `da4b7da256a5d67dee74f576762ceb9067fc7a71` plus the reviewed three-file Enhanced delta from `359a885342d4eeda757f8e1d2c24fb01fe6d39c2`; source and patch metadata are pinned in the dependency script. |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/PlayerSourceFallbackTest.kt` | Byte-identical to the target tree. |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/streams/StreamSearchSessionsTest.kt` | Extended with CloudStream source-identity and terminal-task-accounting tests; a pending-group cleanup regression test was added during this review. |
| `M` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerSettingsStorage.android.kt` | Whitespace-only delta; settings schema and storage behavior remain the target implementation. |
| `M` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/streams/StreamBadgeSettingsStorage.android.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/composeResources/values-fr/strings.xml` | StreamBridge wording/branding retained. XML key audit found no missing target keys. |
| `M` | `composeApp/src/commonMain/composeResources/values-id/strings.xml` | StreamBridge wording/branding retained. XML key audit found no missing target keys. |
| `M` | `composeApp/src/commonMain/composeResources/values-in/strings.xml` | StreamBridge wording/branding retained. XML key audit found no missing target keys. |
| `M` | `composeApp/src/commonMain/composeResources/values-pl/strings.xml` | StreamBridge wording/branding retained. XML key audit found no missing target keys. |
| `M` | `composeApp/src/commonMain/composeResources/values-sk/strings.xml` | StreamBridge wording and local chapter/ratings translations retained. XML key audit found no missing target keys. |
| `A` | `composeApp/src/commonMain/composeResources/values-sk/strings_ratings.xml` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/composeResources/values-vi/strings.xml` | StreamBridge wording/branding retained. XML key audit found no missing target keys. |
| `M` | `composeApp/src/commonMain/composeResources/values/strings.xml` | English StreamBridge branding and product-specific strings retained; all target keys are present. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/AppGate.kt` | Preserves the StreamBridge saved-session/profile gate correction while integrating the target wordmark preload. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt` | Preserves CloudStream, in-app privacy, app-specific navigation and auth behavior alongside target state-collection and playback changes. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/PlayerDestination.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/StreamDestination.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/core/sync/ProfileSettingsSync.kt` | Retains StreamBridge navigation-style/position profile sync fields. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/Components.kt` | Byte-identical to the target tree. |
| `A` | `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/MappedState.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/background/AppBackground.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/glass/LiquidGlass.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/collection/FolderDetailScreen.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/debrid/LocalDebridAvailabilityService.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsRepository.kt` | Preserves StreamBridge metadata identity, retry and token-safe logging changes; the target Simkl recommendation-source fingerprint is present. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsScreen.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/DetailHero.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/TabletDetailHero.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeHeroMetaRepository.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeRepository.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeHeroSection.kt` | Keeps StreamBridge stable hero behavior and integrates the target native-navigation-aware trailer control placement. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/PosterGrid.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerKeyboardShortcuts.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerLoadingStatus.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerModels.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreen.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenArgs.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeEffects.kt` | Preserves the recommendation-card guard and forwards parentMetaId on refreshed player sources. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeGestureActions.kt` | Byte-identical to the target tree. |
| `A` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeSourceFallback.kt` | Forwards parentMetaId when resolving fallback sources, preserving episode metadata for provider resolution. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeState.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeUi.kt` | Preserves parentMetaId on source-panel and episode-stream requests; import ordering/constant placement are formatting differences. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsStorage.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerStreamList.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerStreamsRepository.kt` | Retains CloudStream aggregation and parentMetaId identity, while using the shared-search completion cleanup. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/seekpreview/SeekPreviewController.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/ProfileRepository.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/search/SearchScreen.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/AppBrandWordmark.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/ContinueWatchingSettingsPage.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/PlaybackPlayerSettings.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/PlaybackStreamSettings.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsComponents.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsFullScreenPages.kt` | Preserves StreamBridge CloudStream and privacy pages and settings layout. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsScreen.kt` | Preserves StreamBridge CloudStream/privacy settings destinations and navigation. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsSearch.kt` | Preserves searchable StreamBridge CloudStream/privacy pages and local settings organization. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/PinnedStreamSourcesRepository.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/ProviderFilterRow.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamBadgeSettingsRepository.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamCard.kt` | Byte-identical to the target tree. |
| `A` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamSearchSessions.kt` | Extends the shared cache key/task set for CloudStream targets, title/year and debrid state; accounts for nonterminal debrid updates and settles dropped groups with provider-detail state. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamsRepository.kt` | Preserves CloudStream and parentMetaId request identity, surfaces provider failure details, and now calls the target pending-group cleanup after a search completes. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamsScreen.kt` | Shows the StreamBridge-specific concrete provider failure detail beneath the generic empty-state message. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchprogress/ContinueWatchingPreferencesRepository.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchprogress/WatchProgressModels.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonTest/kotlin/com/nuvio/app/features/home/HomeScreenTest.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/PlayerLoadingStatusTest.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerBridge.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerEngine.ios.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerSettingsStorage.ios.kt` | Byte-identical to the target tree. |
| `M` | `composeApp/src/iosMain/kotlin/com/nuvio/app/features/streams/StreamBadgeSettingsStorage.ios.kt` | Byte-identical to the target tree. |
| `M` | `iosApp/Configuration/Version.xcconfig` | StreamBridge-owned version remains 0.1.18 / build 118; no core version overwrites it. |
| `M` | `iosApp/iosApp/ContentView.swift` | Byte-identical to the target tree. |
| `M` | `iosApp/iosApp/Player/MPVPlayerBridge.swift` | Byte-identical to the target tree. |
| `M` | `iosApp/iosApp/Player/MetalLayer.swift` | Byte-identical to the target tree. |
| `A` | `iosApp/iosApp/Player/PictureInPictureDolbyVisionConverter.swift` | Byte-identical to the target tree. |
| `M` | `iosApp/iosApp/Player/PictureInPictureSubtitleComposer.swift` | Byte-identical to the target tree. |
| `M` | `iosApp/iosApp/Player/PlayerPictureInPictureController.swift` | Byte-identical to the target tree. |
| `M` | `store.json` | Intentionally remains the last published StreamBridge IPA entry. It is regenerated only after a published unsigned IPA is downloaded and verified. |

## Divergence totals

- Target paths: 82 (7 additions, 75 modifications including the MPVKit gitlink).
- Text files byte-identical to the target: 55.
- Text files with reviewed StreamBridge-specific deltas: 26.
- Missing target paths: 0.
