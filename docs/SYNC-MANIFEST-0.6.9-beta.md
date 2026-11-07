# StreamBridge 0.1.16 — Sync manifest (core baseline 0.6.9-beta)

Recorded from the actual diff, verified after integration. Baseline: StreamBridge
`647d86a7` (`main`, 0.1.15 / code 115), core pin Enhanced 0.6.7-beta (`376f8619`).
Target: NuvioMobile-Enhanced tag `0.6.9-beta` = `01879330a3e8db844d49f31bda14ad35b458d287`
(2026-11-07, "fix(details): restore glass floating header in cinematic mode after
upstream merge"), which contains the official NuvioMobile tag `0.6.9-beta` =
`42e37ace0820a86c8b924b41b4bba4e570f12a6d` (2026-11-06, "bump version") as an ancestor
(`git merge-base` of the two tags is `42e37ace`). Both pins are recorded in
`streambridge.version.properties`.

The two `0.6.9-beta` tags are different objects in different repositories; the Enhanced
tag was fetched and verified by commit hash, never substituted by the official one.

## Upstream range

- 150 commits between `376f8619..01879330` (Enhanced 0.6.7-beta → Enhanced 0.6.9-beta,
  through Enhanced 0.6.8-beta `33a78671` and official 0.6.8-beta `39417082`).
- 265 files changed: 101 added, 161 modified, 3 deleted (+19,837 / −4,091 lines).
- Release notes were NOT used as the checklist; the classification below comes from
  `git diff --name-status 376f8619 01879330`, with each path compared blob-for-blob
  against the StreamBridge tree (`HEAD:path`), the base (`376f8619:path`) and the target.

## Classification and disposition

| Class | Files | Disposition |
|---|---:|---|
| Added upstream, absent in StreamBridge | 101 | copied from `01879330` |
| Modified upstream, StreamBridge byte-identical to the 0.6.7 base | 130 | copied from `01879330` |
| Deleted upstream, StreamBridge byte-identical to the base (`LiveTvBackToTopPadding.{kt,android.kt,ios.kt}`) | 3 | deleted |
| Modified on both sides | 31 | 29 three-way merged with `git merge-file` (19 clean, 10 with conflicts resolved by hand); 2 StreamBridge-owned files not imported |

No file was found already equal to the target or missing on our side, i.e. the
StreamBridge tree was exactly at the recorded 0.6.7 base for every upstream-changed path.

## StreamBridge-owned files not imported

| File | Upstream change | Reason |
|---|---|---|
| `store.json` | upstream's own sideload source (0.6.8/0.6.9 entries) | StreamBridge sideload source; written only by `update-store-source.yml` from StreamBridge releases |
| `iosApp/Configuration/Version.xcconfig` | 0.6.9 / 152 | StreamBridge version 0.1.16 / 116 |

## Conflict resolutions (all 10)

| File | Resolution |
|---|---|
| `androidApp/build.gradle.kts` | Took upstream's `assets.directories.add("../testFixtures")` (AGP 9 API) and kept the StreamBridge comment; the baseline-profile plugin, `profileinstaller` dependency and `baselineProfile {}` block auto-merged. Application ID, signing, ABI splits, minify and CloudStream dependencies untouched. |
| `composeApp/build.gradle.kts` | Kept StreamBridge's macOS-only guard (`isMacHost` / `onlyIf`) on the simulator boot task while taking upstream's explicit `tasks.register<Exec>("bootIosTestSimulator")` form. Upstream's `composeCompiler {}` stability config, `-Xexpect-actual-classes`, Coil/cinterop opt-ins, `@InputFiles` local-properties input and explicit `getByName` source sets auto-merged. StreamBridge version-property reads untouched. |
| `features/home/components/HomeHeroSection.kt` | StreamBridge hero kept (library add/check action, trailer fixes, keyed content layers, draw-time crossfade, neighbour prefetch); applied upstream's two changes on top: `rememberBackdropFocusState` + `focusedAlignment` + `onSuccess` on the artwork `AsyncImage`, and the auto-mirrored volume icons. |
| `androidHostTest/…/PlayerSurfaceGesturesTest.kt` | Took upstream's test file (adds `backGestureEdgePx`, the locked-controls tests and three edge-gesture tests) and re-applied the two arguments StreamBridge's `playerSurfaceDragGestures` requires (`playerController = null`, `swipeToSeekEnabledState`), which the upstream file still omits. |
| `commonTest/…/ChapterSkipIntervalsTest.kt` | Kept StreamBridge's two tests for the >4-minute credits skip (our `MAX_CREDITS_DURATION_SEC = 600` fix) and added upstream's two "post-credits scene is a skip target, not a segment" tests; our implementation satisfies all four. |
| `values-bn/strings.xml` (12), `values-bn/strings_pip.xml` (1), `values-id/strings.xml` (4), `values-in/strings.xml` (4) | Took upstream's translation hunks, then re-applied the branding scrub (see below). |
| `values-vi/strings.xml` | Upstream's file is CRLF, StreamBridge's is LF, so the first merge conflicted on every line. All three versions were normalised to LF and re-merged: clean merge, then branding scrub. |

The 19 clean merges (`App.kt`, `AppShellComponents.kt`, `MainAppContent.kt`,
`AppUrlBridge.kt`, `MetaDetailsRepository.kt`, `DetailSeriesContent.kt`,
`LibraryScreen.kt`, `PlayerScreenRuntimeEffects.kt`, `PlayerScreenRuntimeUi.kt`,
`SettingsSearch.kt`, `TrackingSettingsPage.kt`, `StreamsScreen.kt`, `ContentView.swift`,
`Info.plist`, `settings.gradle.kts`, `values/strings.xml`, `values-fr/strings.xml`,
`values-pt/strings.xml`, `values-pt-rBR/strings.xml`) were verified line-by-line: every
StreamBridge-added line from `376f8619..HEAD` is still present, and every upstream-added
line from `376f8619..01879330` is present.

## Branding scrub

Rule (same as 0.1.15): in every imported locale file, `Nuvio` → `StreamBridge` in every
`<string>` whose key does not start with `privacy_policy_`,
`settings_licenses_attributions_nuvio`, `streambridge_`, `server_error_official` or
`action_support_nuvio` (backend disclosures and licence attribution keep the upstream
name). 63 lines were rewritten across the Tamil, Bengali, Indonesian (`values-id`,
`values-in`) and Vietnamese files, plus the two bullet lines of the multi-line Tamil
`p2p_consent_body`. `app_brand_name` is `StreamBridge` in the new Tamil and Indonesian
files.

The new `strings_party.xml` for `ta`, `id`, `in` and `bn` arrived with a two-argument
invitation; the StreamBridge third argument (install fallback, "New to StreamBridge?
%3$s") was added so all 28 locales share the same invite shape.

Code-side `Nuvio` occurrences introduced by the sync are internal identifiers, log tags
and comments only (`NuvioTokens`, `NuvioScreenHeader`, `NuvioPosterShape`,
`"NuvioLibmpv"` thread name, `"NuvioAudioOutput"` log tag); none is user-facing.

## StreamBridge-owned files preserved (not overwritten)

`streambridge.version.properties`, `store.json`, `iosApp/Configuration/Version.xcconfig`,
all `.github/workflows/*`, `scripts/*` (release, store source, Telegram automation and
its ledger), README branding, About Us page and footer, splash/brand lockups, app icons,
updater configuration, application IDs, CloudStream runtime, `legacy/`, `native/`,
`third_party/`.

## Feature checklist from the actual diff

Player audio delay / amplification / downmix + centre mix (Android: `AudioDelayMediaSource`,
`GainAudioProcessor`, `AudioOutputKey`, runtime audio-delay / amplification files, settings
and strings); playback stall resume (`PlaybackStallResume` common + Android, tests); engine
failover pinned to the source; libmpv load-failure surfacing; call/audio-focus handling
(`PlayerAudioFocus`); `MpvSurfaceView` playback-thread detach (+ instrumented
`MpvSurfaceTest`); tablet landscape lock; system back-gesture edge exclusion; VC-1 fix in
`SoftwareVideoDecoder`; player title artwork (`PlayerTitleArtwork` + test, TMDB logo
fallback); network speed on the loading screen (`PlayerNetworkSpeed`,
`NetworkByteCounting`); control-glass and skip-button toggles; subtitle outline blur
(`SubtitleBlurPlatform` android/ios, `StyledSubtitle`, appearance controls, storage tests);
AutoSync for libass ASS/SSA add-on subtitles (`AutoSyncLibassSubtitles`,
`AutoSyncAssDialogueFilter` + tests, `RetimedAssSubtitleParserTest`, nuvio-libass AAR
update); Anime-Skip / AniSkip selection fixes (`SkipIntroRepository`, `SkipModels`,
`AnimeSkipIntervalsTest`); ratings (star ratings in details, rating source setting,
Simkl + MDBList rating sync); details cinematic backdrop (`ArtworkBackdrop` common /
android / ios + test, floating header, see-through buttons/chips, tablet hero fade);
hero smart framing (`BackdropFocus` common / android / ios + test); Home/shelf/grid
performance (`SharedCardStyles`, `PosterImagePrefetch`, `compose-stability.conf`, shelf
cache window, `baselineprofile` module); library and Discover search actions; screen
header title constraint (+ `NuvioScreenHeaderTest`); Live TV revamp (`LiveTvGuide`,
`LiveTvSports`, `LiveTvOnDemand`, models/repository/storage/screen, category search,
back-to-top removal); Private Viewing moved to Tracking; WeTrakr watched badge / no
rewatch push; Simkl related-title signing; plugin settings fix; iOS PiP subtitles
(`PictureInPictureSubtitleComposer.swift`, PiP controller/parking, `MPVPlayerBridge`),
video-output presets on open, Control-Centre skip fix, background kept in PiP, folder
under native nav bar, HLS playlist write fix, cache-speed INT64; Tamil locale (26 split
string files + Android `locale_config.xml`, `AppLanguage`, iOS `Info.plist`); Bengali,
Vietnamese, Indonesian, French and pt-BR translation updates; deprecation clean-up
(`kotlinx.io.IOException`, Ktor `segments`).

## Intentionally excluded or adapted

| Item | Decision |
|---|---|
| Upstream `store.json` entries | excluded — StreamBridge sideload source |
| Upstream `Version.xcconfig` (0.6.9 / 152) | excluded — StreamBridge 0.1.16 / 116 |
| Upstream's omission of `playerController` / `swipeToSeekEnabledState` in `PlayerSurfaceGesturesTest` | adapted — the test did not compile against the shared `PlayerSurfaceGestures.kt`; arguments re-added |
| Upstream's revert of the >4-minute credits tests | adapted — StreamBridge keeps its credits-skip fix and its tests, and adds upstream's |
| Upstream CI workflows | not in this range; StreamBridge owns its pipeline |

Nothing in the range executes downloaded native/DEX code; the plugin runtime change
(`PluginRuntime.kt`) only registers the existing host-API modules (fetch, URL, crypto,
host functions) for the QuickJS runtime before evaluating scraper code, as the other
plugin entry points already did.

## StreamBridge 0.1.16 additions on top

Version bump to 0.1.16 / 116 across `streambridge.version.properties`,
`iosApp/Configuration/Version.xcconfig`, `README.md`, `.github/workflows/release-draft.yml`,
`scripts/verify-release-target.sh`, `scripts/tests/run-tests.sh` and the new
`docs/RELEASE-NOTES-0.1.16.md`; upstream pins updated to `42e37ace` / `01879330`.

## Regression guards

- Search GIFs (`ProfileGifSearch.kt`, `ProfileGifSearchDialog.kt`), the custom avatar flow,
  About Us, Privacy Policy, CloudStream settings page, updater, brand lockup and the
  release/Telegram scripts: zero files touched by the sync.
- `scripts/tests/run-tests.sh` (255 unit tests + release-gate groups) passes locally on the
  integrated tree with the 0.1.16 gates.
- Android unit tests, lint, APK, Kotlin/Native and iOS IPA builds run in the repository's
  CI; results are recorded on the release PR.
