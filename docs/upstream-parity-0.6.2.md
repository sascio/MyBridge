# Upstream parity — Nuvio Enhanced 0.6.2

Audit of the changes between the two upstream pins this repository tracks, and how each
one was reconciled into StreamBridge for the **0.1.11** release.

| | |
|---|---|
| Upstream feature reference | `luqmanfadlli/NuvioMobile-Enhanced`, tag `0.6.2-beta` |
| Enhanced commit | `5d978096f9eb04a62a6bc3a5d6999bdccab4c9cb` (merge of official 0.6.2 into `enhanced`) |
| Official NuvioMobile reference | `NuvioMedia/NuvioMobile`, tag `0.6.2-beta` |
| Official 0.6.2 commit | `1cfae0fe7402f5e6f1fedad393d50d83775586fa` |
| Previous pin (baseline) | Enhanced `0.6.1-beta` = `504014d360bf1a1f844246f527c27c208738246e` |
| Commits between pins | **60** (`compare/0.6.1-beta...0.6.2-beta`, ahead 60 / behind 0) |
| Files changed between pins | **162** (120 modified, 39 added, 3 deleted) |
| Reconciliation against StreamBridge | **162** changed paths: **39** new files present, **120** modified files reconciled, **3** deletions applied; measured file-by-file against the 0.6.2 tree (see [Verification](#verification-against-the-062-tree)) |

Method: the pristine 0.6.2 tree (`luqmanfadlli/NuvioMobile-Enhanced` at `5d978096`) and the
official 0.6.2 tree (`NuvioMedia/NuvioMobile` at `1cfae0fe`) were both checked out, and the
repository was compared file by file against them. Where upstream changed a file StreamBridge
had never touched, the upstream file was taken as-is. Where StreamBridge had customised it, the
change was applied on top with `git merge-file` using the official 0.6.2 file as the base, so
StreamBridge-only behaviour survives. StreamBridge owner files were deliberately **not**
overwritten: `store.json`, `Version.xcconfig` and `streambridge.version.properties` (version),
the release workflows, branding assets and the `README`/`NOTICE`.

Status legend: **Integrated** (ported and compiled in this tree), **Merged** (ported on top of a
StreamBridge customisation), **Upstream-only** (deliberately not shipped), **Blocked**.

## Verification against the 0.6.2 tree

Measured on this tree at `0.1.12`, comparing every Kotlin source file with the upstream 0.6.2
checkout:

| Check | Result |
|---|---|
| Kotlin files identical to upstream 0.6.2 | **1325** |
| Kotlin files that differ | **99** (13 of them are files upstream changed in 0.6.2; the rest predate it) |
| Upstream Kotlin files absent here | **2** (`SupportersContributorsPage.kt`, replaced by StreamBridge's About page; `MdbListLibraryListsPicker.kt`, superseded by StreamBridge's picker) |
| StreamBridge-only Kotlin files | **215** (CloudStream runtime, custom avatar/GIF search, MDBList HTTP engine, updater, branding, tests) |
| Lines added by upstream 0.6.2 that are missing here | **0** in source files — the only misses are branding strings, `store.json` and the version file, which StreamBridge owns |

The remaining 86 pre-0.6.2 divergences are fork decisions rather than porting gaps: StreamBridge
keeps its own home hero (library button, dynamic scrim) instead of upstream's artwork-background
variant, its own MDList HTTP client, its own Trakt/Simkl PKCE flows, its own profiles and avatar
pipeline, and its CloudStream playback availability layer. They are listed in
`docs/UPSTREAM-SYNC.md` when they affect an upstream merge.

Status legend: **Integrated** (ported and compiled in this tree), **Merged** (ported on top of a
StreamBridge customisation), **Upstream-only** (deliberately not shipped), **Blocked**.

## Feature matrix

| # | Upstream feature / change | Upstream evidence | StreamBridge baseline | Status | Files affected | Verification | Remaining limitation |
|---|---|---|---|---|---|---|---|
| 1 | **Watch Party chat** — message send/list RPCs, realtime `chat` event, unread badge, chat tab in the party panel | `b253a7c5` (19 files), `PartyChat.kt`, `PartyChatContent.kt`, `strings_party.xml` keys `party_tab_chat`…`party_chat_new_messages` | None | Integrated | `features/party/PartyChat.kt`, `PartyChatContent.kt`, `PartyApi.kt`, `PartyChannel.kt`, `PartyModels.kt`, `PartyPanel.kt`, `PartyRepository.kt`, `PartyScreen.kt`, `commonTest/.../PartyChatTest.kt` | `PartyChatTest` in `:composeApp:check`; CI run 39364540708 | Needs a live Supabase backend to exercise end-to-end |
| 2 | **Watch Party fixes** — no-addons toast, episode selector for shows, stream list when the host shares a stream | `dcc32c0d`, `3c30a7a1`, `74e9f96c`, `07f293b2`, `7411ec72` | None | Integrated | `PartyScreen.kt`, `PartyPanel.kt`, `StreamsScreen.kt`, `PlayerScreenRuntime*.kt`, `StreamsRepository.kt` | CI compile + unit tests | Multi-device flows need manual verification |
| 3 | **Watch Party + PiP sync on iOS** | `3715e4c8`, `22d195d9` | None | Integrated | `iosApp/iosApp/Player/PlayerPictureInPictureController.swift`, `Player/PlayerPictureInPictureParking.swift` (new), `PlayerScreenRuntimeParty.kt` | iOS workflow (not a release gate) | Not device-tested here |
| 4 | **Liquid Glass UI on Android** with a global toggle, replacing Haze | `5fedbcda` (47 files), `b9937242`; dependency `dev.chrisbanes.haze:haze:1.7.2` → `io.github.kyant0:backdrop:2.0.1` | Haze-based sheet blur | Integrated | `core/ui/glass/LiquidGlass.kt` (new), `GlassBarSurface.kt`, `BottomSheet.kt`, `NavigationBar.kt`, `FloatingNavigationBar.kt`, `Main*`, settings/appearance/player surfaces; `composeApp/build.gradle.kts`; 3 platform glass files deleted | CI compile; visual check on device outstanding | No emulator in this environment — layout/visual regressions not observed directly |
| 5 | **Player glass softening** on controls | `186ccbfa` | n/a | Integrated | `PlayerControls.kt`, `PlayerLiquidGlass.kt`, `PlayerScreenContent.kt`, `PlayerSidePanel.kt`, `PlayerSpeedPopup.kt` | CI compile | Visual only |
| 6 | **Player zoom / stretch** — Fill stretch on MPV, pinch-to-zoom, custom zoom keeping aspect, ExoPlayer pinch past black bars | `5633d492`, `7b304df3`, `25c0b565` | Fill = fit crop | Integrated | `PlayerSurfaceGestures.kt`, `PlayerEngine.kt`, `PlayerEngine.android.kt`, `PlayerEngine.ios.kt`, `PlayerBridge.kt`, `MPVPlayerBridge.swift`, player settings | CI compile + `PlayerSettingsStorage` migrations | Gesture feel needs a device |
| 7 | **Orientation toggle for portrait videos** | `d786b526`, `8cc035a3` | n/a | Integrated | `PlayerControls.kt`, `PlayerControlActions.kt`, `PlayerSettingsRepository.kt`, `OrientationLockCoordinator.swift` | CI compile | Device-only behaviour |
| 8 | **Optional libmpv libass renderer** for ASS/SSA on ExoPlayer | `f070b9ac` (22 files), `f428c47b` | libass overlay via existing engine | Integrated | `aiostreams/` package (7 files, new), `androidApp/src/main/cpp/` (CMake + JNI, new), `PlayerEngine.android.kt`, playback settings + search row, `proguard-rules.pro` (`-keep …aiostreams.AssSurface`) | CI compile; JNI library built by CMake in release and debug | Requires NDK/CMake 3.22.1 in CI (added to the workflow); runtime subtitle rendering needs device validation |
| 9 | **Anime ID mapper** for Trakt / MDBList / WeTrakr (+ AniList rating reset, exact season resolver) | `d0d00e0e`, `ac32603b`, `d955445a`, `1f24a881`, `1f0d8841`, `3397ac2c`, `98a18696`, `89c7d720`, `08ecd061`, `0c405c26`, `5f534e30` | Title/ID heuristics without a shared mapper | Integrated | `tracking/TrackingAnimeIdMapper.kt` (new), `TraktRatingsProvider.kt`, `TraktScrobbleRepository.kt`, `TraktWatchedSyncAdapter.kt`, `MdbListTrackingWrites.kt`, `MdbListWatchedSyncAdapter.kt`, `WeTrakr*.kt`, `AniListRatingsProvider.kt` | CI compile | Mapping tables are upstream data; no cross-service live test |
| 10 | **Trakt anime watched sync** | `c84c1578` | Partial | Integrated | `TraktWatchedSyncAdapter.kt`, `trakt/TraktScrobbleRepository.kt` | CI compile | Needs Trakt account to verify end-to-end |
| 11 | **Simkl** — multi-season anime dedupe (series content-id index), next-up seed resolution, retained ids across refreshes, and move explicitly watched series to Completed | `84b39783`, `c6b52f2b` | Simkl marked episodes only | Integrated | `features/simkl/SimklApplicationAdapters.kt`, `commonTest/.../SimklHistoryPushGuardTest.kt` | CI compile + existing unit tests | Needs Simkl account |
| 12 | **Binge-group selection by filename + infoHash**, prefetch fixes | `d2cc0fb8`, `a137b1e3` | Episode/season heuristics | Integrated | `streams/FilenameEpisodeMatcher.kt` (new), `StreamsRepository.kt`, `BingeGroupCacheRepository.kt`, `StreamsScreen.kt`, `StreamsTabletLayout.kt`, `StreamCard.kt`, `ProviderFilterRow.kt` | CI compile | Provider-specific file naming remains heuristic |
| 13 | **Settings sliders show the value while dragging** | `c6242210` | Value on release | Integrated | `settings/PlaybackSettingsPage.kt`, `AppearanceSettingsPage.kt`, `HomescreenSettingsPage.kt`, `MetaScreenSettingsPage.kt`, `NavigationBarSettingsSheet.kt` | CI compile | — |
| 14 | **Library section titles** resolver | `a137b1e3` | Hard-coded `displayTitle` | Integrated | `library/LibrarySectionTitles.kt` (new), `LibraryScreen.kt`, `LibrarySavedContent.kt`, `MainAppContent.kt` | CI compile | — |
| 15 | **iOS** — Liquid Glass tab bar on iPadOS 26+, mini player, PiP tied to mini player, external-player probing, simulator build fix | `fec3566b`, `6c6e24d4`, `ec7b3162`, `eaac8d96`, `f89d569f`, `e5bb7780` | None | Integrated (source), not release-gated | `iosApp/iosApp/NuvioGlassTabBar.swift`, `ContentView.kt`, `Player/MetalLayer.swift`, `NowPlayingController.swift`, `PlayerBridge.kt`, `ThemeSettingsStorage.ios.kt` | iOS workflow compiles independently | No iOS device in this environment; Android release does not wait for it |
| 16 | **Localisation** — pt-BR completion, Greek, Italian fixes, escaped apostrophes | `9c30ec1a`, `c65e7ade`, `06bef8fb`, `2099bbfd`, `e15ddaf0`, `f7407232`, `a9e21f64` | Partial locales | Integrated | 14 locale XML sets incl. new `values-el/*`, `values-pt-rBR/*`, `values-it/strings_party.xml`, base `values/strings.xml` | CI compile | Machine-checked only (resource presence); no linguistic review |
| 17 | **Version bump / store publication** | `1cfae0fe`, `324afcff`, `229c083c` | 0.1.10 / 110 | Upstream-only (StreamBridge keeps its own versioning) | `streambridge.version.properties`, `iosApp/Configuration/Version.xcconfig` | Identity test asserts StreamBridge never ships Nuvio's version | — |
| 18 | **AIOStreams attribution stub** (`third_party/AIOStreams`) | `5d978096` tree | None | Integrated + licence completed | `third_party/AIOStreams/README.md`, `third_party/AIOStreams/LICENSE` (AGPL-3.0 text added), `NOTICE` | Review | AGPL-3.0 §13 permits combining with this GPL-3.0 work; attribution retained |

## Watch Party crash (0.1.11 regression)

**Symptom.** Tapping **Settings → Watch Party**, or opening a `nuvio://party/<code>` invite,
terminated the app.

**Root cause.** `PartyRoute` is declared in `navigation/Routes.kt`, is registered in
`navigationSavedStateConfiguration`, and is navigated to from two places (`SettingsDestinations.kt`
and the deep-link handler in `MainAppContent.kt`). The Navigation 3 `NavDisplay` entry
(`entry<PartyRoute> { … PartyScreen(…) }`) that exists in upstream 0.6.1 *and* 0.6.2 was missing
from StreamBridge's `MainAppContent.kt`: the block had been overwritten in place by the newly
inserted `entry<CloudStreamSettingsRoute>`. Navigation 3 has no destination for a key without an
entry, so the first composition after `navigate(PartyRoute(...))` threw and the process died.
A grep of the tree proved the invariant: 27 route classes, 26 entries, `PartyRoute` the only
missing one.

**Fix.** Restored the upstream block in its original position (before the CloudStream entry):

```kotlin
entry<PartyRoute> { route ->
    SettingsDestination(route, navController) { onBack ->
        PartyScreen(initialCode = route.code, onBack = onBack, onWatch = ::openPartyMedia)
    }
}
```

The default party display name was also changed from the upstream `"Nuvio"` to `"StreamBridge"`
(`PartyRepository.DEFAULT_NAME`), which is visible to other members.

**Regression tests.** `composeApp/src/androidHostTest/.../navigation/RouteEntryRegistrationTest.kt`:
every route declared in `Routes.kt` must have an `entry<...>`; every entry must reference a declared
route; the `PartyRoute` entry must compose `PartyScreen` with `initialCode = route.code` and
`onWatch = ::openPartyMedia`; and `PartyRoute`/`TabsRoute` must resolve in the saved-state
serializers module. This is the same static-source style as `StreamBridgeReleaseIdentityTest`, and
it fails `:composeApp:check` if the regression returns.

## Deliberate deviations / not ported

* **StreamBridge branding and release identity** — application id `com.streambridge.app`, app name,
  launcher icons, updater (`sascio/MyBridge`), signing and release workflows are StreamBridge-owned
  and were not replaced by upstream equivalents.
* **CloudStream runtime** — StreamBridge-only; upstream 0.6.2 files were merged around it, and the
  `entry<CloudStreamSettingsRoute>` that displaced the party entry is kept (now adjacent to it).
* **Upstream GitHub automation** — upstream issue templates, stale-bot and legacy workflows are not
  imported.
* **Upstream `0.6.2-beta` version strings** — StreamBridge stays on its own `0.1.x` line.

## Release verification (0.1.11)

| Check | Evidence |
|---|---|
| Release build compiles and tests pass | `Build Stream Bridge` runs `39363269001` (`baeae8c`) and `39364540708` (`cbe5c3e`): `:composeApp:check`, debug + release APKs, lint, Play Store variant, CloudStream runtime checks — all green |
| Main branch | PR #220 merged into `main` as `322c236` |
| Tag | `v0.1.11` → commit `322c236` (annotated tag, not moved afterwards) |
| Production signing | Release run `39366216569`, job "Production-signed full APK", steps "Require production signing secrets", "Decode upload keystore", "Reject debug certificates…" (pins the upload certificate SHA-256 `5da621d8…`) and "Require expected production APKs" all passed |
| Artifacts | `androidApp-full-arm64-v8a-release.apk`, `-armeabi-v7a-`, `-x86-`, `-x86_64-` plus `checksums.sha256`, published on the release |
| Version | Tag check `0.1.11` == `STREAMBRIDGE_VERSION_NAME`; version code `111` from `streambridge.version.properties` |
| iOS independence | The `draft` job ran with `needs: [android]` and the release was published with Android assets while the iOS job was still building; the release notes carry an explicit "iOS status: pending" section, and `store.json` was left at 0.1.10 by the sideload workflow instead of failing |

The APK bytes could not be downloaded for a second local inspection in this environment (release asset
hosts are outside the sandbox's allowed network); the signing and asset checks above ran inside the
release workflow itself and would have failed the job on a debug certificate or a missing/renamed
artifact.

### 0.1.12 follow-up

| Check | Evidence |
|---|---|
| Code + docs build | `Build Stream Bridge` runs `39378244773` (`cfbf46f`), `39380339784` (`a6cbcfe`), `39385299789` (`cf03f48`), `39387492901` (`b88bb5a`) — all green (unit tests, debug + release APKs, lint, CloudStream runtime checks) |
| Merge | PR #222 → `ea4787c`; workflow fix PR #223 → `1457a9b` |
| Release | `v0.1.12`, published `2026-10-20T08:31:41Z`, six assets: four ABI APKs, `checksums.sha256`, `StreamBridge-0.1.12-iOS-unsigned.ipa` |
| Signing | Release run `39382519342`: production signing steps green (keystore decode, debug-certificate rejection, expected-APK check) |
| Sideload source | `Update Sideload Source` run `39394045163` published `store.json` 0.1.12 (build 112) with the release IPA |
| iOS independence | Android APKs were published at 06:55 UTC while the iOS job was still building; the IPA was attached at 08:31 from an independent job |
| Known anomaly | Push run `39370436322` (`95bbc63`) hung in APK assembly on a runner for hours and was superseded by `39378244773` on the next commit; the integration token cannot cancel runs |

## Known gaps

* No Android emulator or device is available in this environment; the app was not launched here.
  Verification is the CI build (`:composeApp:check`, debug + release APKs, lint, Play Store variant,
  CloudStream runtime checks) plus static tests: run 39363269001 on `baeae8c` and run 39364540708 on
  `cbe5c3e`, both green. Device verification remains outstanding.
* Watch Party chat and cross-device sync require the upstream Supabase backend and two signed-in
  accounts; only the code path and unit tests are verified here.
