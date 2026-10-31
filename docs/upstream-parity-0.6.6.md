# Nuvio 0.6.6-beta integration matrix (StreamBridge 0.1.14)

## Pins

| | Repository | Reference | Resolved commit |
|---|---|---|---|
| Official (primary) | `NuvioMedia/NuvioMobile` | tag `0.6.6-beta`, branch `cmp-rewrite` | `2d52c8b71b598eb90e13fa9ec9ccff97ab601126` |
| Enhanced (secondary) | `luqmanfadlli/NuvioMobile-Enhanced` | tag `0.6.6-beta`, branch `enhanced` | `2fc68109f9a4919c7fae373740ac8d5aae5253ca` |
| Previous official pin | `NuvioMedia/NuvioMobile` | `0.6.3-beta` | `014fa2e2f8e2f4914e09d117ffe29d1eefcc6275` |
| Previous Enhanced pin | `luqmanfadlli/NuvioMobile-Enhanced` | `0.6.3-beta` | `b9504e1a3bfd212cb32a54138b149ea1995b65c7` |

Both references were verified against the GitHub API before any file was
touched: both tags exist, both resolve to the commits above, and the Enhanced
`0.6.6-beta` commit `d4c60c5f` merges the official tag into `enhanced`. The
Enhanced tag is therefore taken as the integration source, because its tree
contains the official changes plus the Enhanced-only work. Anything an official
commit changed and Enhanced did not carry forward was checked individually (see
"Official-only vs Enhanced-only" below).

Scope of the step: 192 official commits and 288 Enhanced commits between the
pins; 439 upstream paths changed, +47,845 / −9,434 lines.

## Method

StreamBridge has no git history in common with upstream (it was imported as a
squashed tree), so this is a source-level three-way integration, not a merge:

- base = the pristine Enhanced `0.6.3-beta` tree
- theirs = the Enhanced `0.6.6-beta` tree
- ours = StreamBridge `main` at `77858b57`

Per file:

| Classification | Files | Rule |
|---|---:|---|
| Already identical to upstream | 0 | nothing to do |
| Upstream changed, StreamBridge untouched (`TAKE-THEIRS`) | 167 | replaced verbatim |
| New in upstream (`ADD`) | 194 | added verbatim |
| Both changed (`MERGE`) | 54 | `git merge-file` against the pristine base |
| Removed upstream (`DELETE`) | 9 | removed |
| StreamBridge-owned (`SKIP`) | 15 | never replaced |

Nothing was cherry-picked from upstream git history and no directory was
overwritten wholesale; StreamBridge's own files were excluded by path.

Ten `MERGE` files conflicted and were resolved by hand:

| File | Resolution |
|---|---|
| `settings/SettingsScreen.kt`, `SettingsModels.kt`, `SettingsSearch.kt`, `SettingsRootPage.kt` | Adopt 0.6.6's settings tree, then re-apply StreamBridge's CloudStream page and in-app privacy policy |
| `settings/TrackingSettingsPage.kt` | Upstream's AniList / WeTrakr / MDBList sections restored (StreamBridge had dropped them in the 0.6.1 sync) |
| `settings/AboutSettingsPage.kt` (new) | Privacy row pointed at the in-app page instead of `https://nuvio.tv/privacy-policy`; StreamBridge lockup, credits and motto in the footer |
| `home/components/HomeHeroSection.kt` | StreamBridge's hero kept (library add/check action, trailer stop/position fixes); upstream's extracted `HomeHeroMetaRows` remains available but the hero does not call it |
| `commonTest/…/MdbListLibrarySorterTest.kt` | The removed released-order test stays removed: StreamBridge's sorter has no `RELEASED_ORDER_KEY` |
| `androidHostTest/…/RatingsVisibilityTest.kt` | Upstream's realistic episode durations |
| `androidHostTest/…/PlayerSurfaceGesturesTest.kt` | StreamBridge's swipe-to-seek state plus upstream's hold-to-speed fix |
| `values-hu/strings_party.xml` | Upstream's Hungarian orthography, StreamBridge's branded invite message with the install fallback |

## Integrated

- **Subtitles** — libass atlas renderer (`nuvio-libass`), `SubtitleAppearance`,
  `SubtitleAppearanceControls`, `SubtitleFontPicker`, `SubtitleCuePresenter`,
  `StyledSubtitle`, `MatroskaChapterParser` chapters, the settings appearance
  page with preview, custom font import, and the removal of the previous
  `PlayerLibassOverlay` / `SubtitleFontImporter` / `SubtitleShadowSpan` path.
- **Playback** — chapter panel and skip intervals (`ChapterSkipIntervals`,
  `PlayerChaptersPanel`), `PlayerNightMode`, `AutoPlaybackEngineResolver`,
  toolbar layout and legacy button layout, engine-switch track preference.
- **Native** — the `native/` Cargo workspace (thumbnails, Matroska/MP4
  container indexes, JNI bridge) wired into the APK with `cargo-ndk`, the
  FFmpeg public headers plus `sw_video.c` CMake target, `Thumbnails`,
  `SeekPreviewFrameSource.android.kt` and the software video decoder.
- **Settings** — the regrouped root (You / Look / Watch / Services / System),
  the split playback pages, About page, config backup/transfer, PublicMetaDB
  provider card, app-icon persistence fix.
- **Home and details** — `HomeHeroMetaRows`, single-decimal IMDb ratings,
  unreleased-episode labelling, slider component restyle.
- **Services** — MDBList library lists, Simkl sync copy, AutoSync strings,
  servers settings, DNS resolver plumbing.
- **Build** — Compose Multiplatform `1.13.0-beta01`, Material3 `1.13.0-beta01`,
  Navigation3 `1.2.0`, compileSdk minor `1`, `lib-nuvio-engine-android` `0.1.6`,
  MPVKit pin moved to the Enhanced fork revision `7f40522a` that 0.6.6's iOS
  player requires.

## Official-only vs Enhanced-only

The Enhanced tree is the union, so a separate pass over
`diff official-0.6.6 enhanced-0.6.6` was used to check that no official change
was lost in Enhanced's merge resolution:

- Differences are Enhanced's own work: the AIOStreams/aiostreams player stack
  (libass engine, atlas surface, thumbnails, software decoder, container
  indexes), the Enhanced MPVKit fork, `NuvioEnhanced.json`/`NuvioFull.json`,
  night mode, and the extra natives. All integrated.
- Official-only differences that mattered were build metadata
  (`mobile-release.yml`, `pr-full-debug-build.yml`, `.gitmodules`, `store.json`,
  `README.md`, `Version.xcconfig`). StreamBridge owns repositories, workflows,
  store JSON and versioning, so these were skipped by design; the equivalent
  build changes (Rust toolchain steps, compileSdk minor) were applied to
  StreamBridge's own workflows.

## Skipped, with reasons

| Path | Reason |
|---|---|
| `.github/workflows/*` (3 changed, incl. `android-release.yml` → `mobile-release.yml`) | StreamBridge has its own workflows, signing and release gates. The Rust build steps were added to StreamBridge's workflows instead |
| `store.json` | StreamBridge publishes its own AltStore/SideStore source |
| `README.md` | StreamBridge's own README |
| `iosApp/Configuration/Version.xcconfig` | Version is StreamBridge's (`0.1.14` / `114`), not Nuvio's `0.6.6` |
| `.gitmodules`, `MPVKit` | StreamBridge vendors no submodule; `scripts/prepare-ios-dependencies.sh` pins the fork revision instead |
| `scripts/prepare-ios-dependencies.sh` | StreamBridge's own dependency fetcher; engine version/checksum and MPVKit pin updated in place |
| `androidApp/build.gradle.kts`, `composeApp/build.gradle.kts`, `gradle/libs.versions.toml`, `composeApp/proguard-rules.pro`, `iosApp/iosApp.xcodeproj/project.pbxproj` | StreamBridge-owned application identity, flavours, signing, CloudStream runtime. Upstream deltas applied by hand (Rust task, libass AAR, dependency bumps, keep rule, Xcode test target) |
| `documentation only` | — |

## Not applicable

- Upstream's Nuvio-branded locales as a whole: the four newly added locale
  files were rebranded (see [BRANDING-AUDIT-0.1.14.md](BRANDING-AUDIT-0.1.14.md)).

## Enhanced-only subsystems deliberately not integrated

These existed in Nuvio Enhanced before 0.6.6 and still were not brought over;
each is a self-contained subsystem, not a 0.6.6 change:

| Subsystem | Why not integrated |
|---|---|
| MDBList library lists (`MdbListLibraryListsPicker`, `MdbListLibraryService.listOptions/setListVisible/setListVisibleAsync`, dynamic/external/liked list models in `MdbListLibraryModels`) | StreamBridge's MDBList layer is the official Nuvio one (library lists sync is Enhanced-only). 0.6.6's tracking page calls the picker, so that one section is omitted; MDBList account, ratings and sync settings are all kept |

The rest of the tracking page 0.6.6 ships (AniList, WeTrakr, PublicMetaDB,
rating-source preferences, Simkl copy) is integrated.

## Files replaced after the automated pass

The first mechanical pass left a few files inconsistent with the 0.6.6 tree
because the three-way base for them was already several releases behind:

| File | Resolution |
|---|---|
| `features/settings/{PlaybackSettingsPage, AppIconPlatform, SettingsComponents, AppearanceSettingsPage, AppearanceThemePicker, AdvancedSettingsPage, MdbListSettingsPage, PosterCustomizationSettingsPage, RatingsSettings, StreamsSettingsPage, ThemeColorPicker, TmdbSettingsPage}.kt` | Replaced with the 0.6.6 versions. `PlaybackSettingsPage` is now the 0.6.6 shell (the playback settings live in the `Playback*Settings.kt` files 0.6.6 split them into) and no longer declares a second `SettingsSliderRow`; `AppIconPlatform` declares the icon-key accessors the Android/iOS actuals implement |
| `features/settings/TrackingProviderCards.kt` | 0.6.6 version plus StreamBridge's single delta: the Simkl PIN/device flow stays hidden because it still speaks AUTH V1 and V2 clients reject it |
| `features/settings/SubtitleFontDialog.kt` | Deleted. 0.6.6 removed it along with `SubtitleFontRepository`; `SubtitleFontPicker` replaced it and is already present |
| `features/home/components/HomeHeroSection.kt` | StreamBridge's hero kept (library add/check action, trailer stop/position fixes, dynamic artwork scrim). The hero therefore does not call 0.6.6's `HomeHeroMetaRows`; the details screen and its tests do |

## Known divergence after this sync

- The home hero keeps StreamBridge's implementation, so 0.6.6's hero meta rows
  (unreleased labels and rating toggles on the hero itself) are only used on the
  details screen. The hero still shows ratings through StreamBridge's own row.
- `lib-nuvio-engine-android-0.1.6.aar` is the upstream pin for the torrent
  engine JNI; the AAR is tracked and loaded through the `lib-*.aar` file tree,
  exactly as `0.1.5` was.
- The HTTPS invite redirect (`https://sascio.github.io/MyBridge/party/`) still
  needs GitHub Pages enabled by a repository administrator; the invite never
  depends on it.
