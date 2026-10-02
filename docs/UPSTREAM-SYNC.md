# Upstream Nuvio sync status

## Current pin

| Field | Value |
|---|---|
| Upstream repo | https://github.com/NuvioMedia/NuvioMobile |
| Branch | `cmp-rewrite` |
| Commit | `5c6028f24d3017f27fc4aeb0b6234b18fcfd2ee8` |
| Release | 0.5.5-beta (`bump version`, 2026-09-30) |
| Enhanced overlay | `8b3bd867` (tag `0.5.5-beta`, 2026-10-01) |
| Previous pin | 0.5.2-beta (`bb3c1e4c`) + Enhanced `3da8d06f` |

## Important: upstream rewrote history (historical note)

The previously recorded pin `d177eb57dfc8989e56de577bda8f4c2714b2bd64`
(0.4.17) **no longer exists** in the upstream repository. It is not an
ancestor of `cmp-rewrite` and `git cat-file` cannot resolve it.

StreamBridge was also imported as a squashed commit, so there is **no merge
base** with upstream:

```
git merge-base HEAD nuvio/cmp-rewrite   -> (empty)
```

A conventional `git merge` or `git rebase` against upstream is therefore
impossible. Sync is done by source-level three-way integration: a synthetic
base commit (tree = StreamBridge HEAD, parent = the previous Enhanced pin)
is merged with the new upstream tip so `git merge` sees the correct base,
then conflicts are resolved by hand. The result is committed as regular
commits so the StreamBridge branch history stays linear.

## 0.5.5-beta / Enhanced 0.5.5-beta integration (this sync)

Base: Enhanced `3da8d06f` (0.5.2-beta) / Official `bb3c1e4c` (0.5.2-beta).
Theirs: Official NuvioMobile `5c6028f24d3017f27fc4aeb0b6234b18fcfd2ee8`
(tag `0.5.5-beta`) + Enhanced `8b3bd867e172757c9fd7373da301e6489914f3fc`
(tag `0.5.5-beta`, which contains the official tag).

Scope: 236 files, +16628/−5013. 49 files are new, 2 upstream-removed files are
kept removed (`features/player/RandomEpisodePlaybackTracker.kt`, whose episode
shuffle role moved to `features/shuffle/*`, and the superseded
`commonTest/.../ReleaseDateDisplayTest.kt`).

**Official NuvioMobile 0.5.2-beta → 0.5.5-beta**
- Ratings: `features/ratings/*` storage + user rating sheet, ratings providers
  for Trakt/Simkl/MDBList, star row on details.
- Episode shuffle: `features/shuffle/*` (repository, picker, sheet, badge) with
  Android/iOS storage and host tests.
- Shared UI restyle: `core/ui/Chip.kt`, `core/ui/Dialog.kt`, `core/ui/Menu.kt`,
  shimmer loading states, `core/format/ReleaseDateDisplay.*`,
  `core/i18n/MediaStatusLabel.kt`, `TmdbAgeRatings.kt`.
- Plugin runtime: async timers / `setTimeout` (`HostFunctions.kt`, `JsBindings.kt`).
- Downloads moved into the library (`LibraryDownloadsButton.kt`,
  `DownloadsDestinations.kt`), debrid episode file selection, MDBList ordering,
  buffer-duration tuning, player resume/auto-play fixes.
- New locales: Bengali (`values-bn`) and Urdu (`values-ur`), plus Greek, Slovak,
  Vietnamese and other translation updates.
- iOS: QuickJS 1.0.15, `MPVPlayerBridge.swift` drops `.mixWithOthers` so Now
  Playing controls work again, `Version.xcconfig` 137/0.5.5.
- Root `store.json` (AltStore source) updated — kept out of StreamBridge.

**NuvioMobile-Enhanced 0.5.2-beta → 0.5.5-beta**
- Ratings UI completion (star row, animations, episode badge), Profile Insight
  caching/background computation and manual refresh, Clear Metadata Cache in
  Advanced settings, subtitle "sync by ear" card and modal wiring, landscape
  clearlogo option, iOS library/calendar metadata-lookup limits.

**StreamBridge changes in this sync**
- All StreamBridge customizations carried through the merge unchanged:
  CloudStream runtime and settings pages, in-app updater for
  `sascio/MyBridge`, privacy & policy page, licenses & attributions, avatar
  catalog, profiles, Trakt/Simkl plumbing, full/playstore flavor split, signing
  and release workflows, StreamBridge branding.
- Locale rebrand: `app_brand_name` is StreamBridge in every locale (the new
  Bengali/Urdu files arrived as `Nuvio` and were reset).
- New: iOS release support for StreamBridge 0.1.08 —
  `scripts/build-ios-ipa.sh` (unsigned by default, signed when Apple material
  is provided), `.github/workflows/ios-release.yml`, iOS identity
  (`com.streambridge.app`, `StreamBridge.app`, StreamBridge app icons),
  `docs/RELEASE-IOS.md`.
- Version metadata: `streambridge.version.properties` = 0.1.08/108;
  `iosApp/Configuration/Version.xcconfig` = 0.1.08/108 (upstream's 0.5.5/137 is
  not shipped as StreamBridge's version).

**Merge conflicts and how they were resolved**
- `PlayerGestureOverlay.kt` — took upstream Enhanced 0.5.5 verbatim (a custom
  gesture reading is a strict superset of the 0.1.07 behavior).
- `MainActivity.kt` — kept StreamBridge's CloudStream initialization and added
  upstream's `UserRatingsStorage.initialize`.
- `StreamOrientationTest.kt` — took upstream's initialization block.
- `values-el/strings.xml` — took upstream's Greek wording, kept
  `app_brand_name` = StreamBridge.
- `SettingsScreen.kt` — kept StreamBridge's `onCloudStreamClick`/`onDownloadsClick`
  parameters alongside upstream's `onAccountClick` and friends.
- `store.json` — stays deleted (StreamBridge is not published through the
  AltStore source).

**Deliberate divergence**
- Upstream uses Compose format strings that treat a trailing `%` as a conversion
  and expect `S%1$dE%2$d`. StreamBridge keeps its 0.1.07 composition rule: the
  transparency value stays a literal percent (`%1$d%%`) and episode codes keep
  the space after the season number. The countdown string upstream changed to
  `Playing via %1$s in %2$ds…` is also kept in the StreamBridge form so the
  argument stays an `Int` (see `player_next_episode_playing_via_countdown`).

**Verification**
- `composeApp/build.gradle.kts` and `androidApp/build.gradle.kts` are byte-identical
  to the pre-sync StreamBridge tree, so Android credentials, signing, flavors and
  release behavior did not change.
- `gradle/libs.versions.toml` differs only in `quickjsKt` 1.0.5 → 1.0.15 (the
  Android `full` flavor keeps its pinned `quickjs-kt-android-1.0.5-nuvio.aar`
  for plugin runtime compatibility; iOS uses the version-catalog QuickJS 1.0.15
  that upstream 0.5.5 requires).
- No CloudStream-path file was touched; the no-DEX boundary and the pinned AARs
  are intact.

## 0.5.2-beta / Enhanced 0.5.2-beta integration (previous sync)

Base: Enhanced `9d311c18` (0.5.1-beta) / Official `b88fef2e` (0.5.1).
Theirs: Official NuvioMobile `bb3c1e4c43f65b0c12c897ae9eda6f36806452b0` (tag `0.5.2-beta`) +
Enhanced `3da8d06f2355dd1c26191b7d5bf61be223405788` (tag `0.5.2-beta`).

**Official NuvioMobile 0.5.1 → 0.5.2-beta Core Upgrades**
- Upgraded `lib-nuvio-engine-android` to `0.1.2.aar` with optimized binary fetch.
- Added Simkl "More Like This" recommendations provider (`SimklRelatedRepository.kt`) and toggle in Tracking settings.
- Added per-screen custom poster URL controls (`custom_poster_enabled_screens`) in Profile Settings Sync (v4 schema) and Poster Customization settings page.
- Added dedicated `BadgeImageLoader.kt` for provider badges with caching.
- Player engine surface lifecycle keyed on `PlaybackKey` (item ID, season, episode, stream URL) ensuring clean player resets between episode transitions.
- Player gesture overlay displaying exact percentage for volume/brightness adjustments.
- Player next episode auto-play post-credits timing delay support (`postCreditsDurationSeconds`).
- iOS Swift UI lifecycle updates (`prepare-ios-dependencies.sh`, `SystemUI.swift` replacing obsolete `NuvioImmersiveSystemUI.swift`, `MPVPlayerBridge.swift` rendering callbacks).

**Enhanced 0.5.2-beta Extra Features Integrated**
- Taste DNA genre derivation and interactive donut chart in Profile Insights settings.
- Password manager autofill integration in `AuthScreen.kt` using `BasicSecureTextField`.
- Device-local appearance and bottom navigation item customization sheet and preview.
- Simkl catalog "View all" repository resolution for enhanced item browsing.
- Adaptive grid column count calculation for tablet and landscape orientations in Library.

**StreamBridge & CloudStream Preservation**
- Preserved StreamBridge brand lockup, logo, tagline, and custom assets.
- Preserved CloudStream runtime, extensions manager, multi-engine aggregators, and no-DEX boundary.
- Preserved signing, release architecture, and updater configurations.

## 0.5.1 / Enhanced 0.5.1-beta integration (previous sync)

Base: Enhanced `ce4492ec` (0.4.23-beta). Theirs: Enhanced tag `0.5.1-beta`
(`9d311c18`), which itself contains official NuvioMobile `b88fef2e`
(0.5.1). The merge brought in:

**Official NuvioMobile 0.4.25-beta → 0.5.1**

- MDBList mobile integration: shared-device authentication, watchlist and
  static list operations, watched-history synchronization, scrobbling,
  list management UI, account status cards, ratings with connected-account
  override, batched rating requests, corrected library sorting (~45 new
  `features/mdblist` files plus tests, androidTest UI tests)
- Custom poster URL pattern support: `core/poster` resolver, overlay,
  fallback interceptor, storage, settings page, tests
- Landscape posters in the library, poster fallback for Continue Watching,
  poster pattern reload on profile switch
- Library list management controls (controller, dialog, rows, provider
  orders, sort effect)
- Player: external-player results that report completion without a
  position are recorded as watched (MX Player); subtitles restored per
  episode; iOS audio preserved during calls; clean player framework archive
- TMDB: custom posters in TMDB/Trakt collections, collections race-condition
  fix, blank collection release dates sorted last, new aiom season poster
  method; TMDB release-dates enrichment removed
- Settings: tracker cards collapsed by default
- Streams: addon filtering shared across pickers (`ProviderFilterRow.kt`)
- Translations: complete Norwegian Bokmål, Spanish, Vietnamese updates
- Coil 3.5.0-beta01 → 3.6.3, iOS test entitlements, coroutines-test

**NuvioMobile-Enhanced 0.4.23-beta → 0.5.1-beta**

- Jelly floating navigation bar on iPad (jelly drawing/motion/spring/tabs
  moved androidMain → commonMain), floating pill inline labels, pill slide
  skip after drag, floating nav bar position setting (top/bottom), tablet
  classic nav bar preview fixes, tab bar kept on Settings sub-pages
- Profile page redesign: hero, circular edit/switch/save header buttons,
  iOS-style floating header on Android, finished titles split into
  Watched/Completed/Episodes, next-7-day episodes under Upcoming
- Player: More Like This suggestions at the end of a movie (snoozable,
  timed to the last two minutes, skips watched titles), seek/volume/
  brightness gesture readouts in the new layout, PiP/quality-chooser/info
  buttons restored in the new layout
- Details: actions in an icon row under Play (optional), library icon on
  saved items, hero blend layer kept below content, hero trailer
  start-with-sound
- Streams: optional search bar, source pinning kept in the shared provider
  filter row
- Downloads: optional download button on the details screen (on by default),
  open-downloads-folder icon tint; iOS: Files app folder, Live Activity
  background task on the main actor
- Live TV: sticky search/filter header; iOS Live TV visibility and legacy
  tab bar on iOS 26+
- Theme: accent gradient applied to icons, accents and buttons
- iOS: Latin subtitle font (Noto Sans) bundled under `iosApp/iosApp/SubtitleFonts/`
  with license, stabilized subtitle font resolver, mpv demuxer cache cap,
  per-plugin large-stack threads, morphed tab bar handoff
- Primary button gradient fades when disabled

**Reconciliations**

- `composeApp/build.gradle.kts`: upstream's `MdbListConfig` generation read
  `props` from the removed plain-IO `Properties` loading; rewired to a
  `mdblistClientId` Gradle `Provider` wired through `runtimeConfigValue`,
  matching StreamBridge's configuration-cache-safe credential plumbing.
- `values/strings.xml`: MDBList attribution body keeps StreamBridge
  branding with upstream's fuller scope (ratings, watched history and
  playback tracking). `values-nb`: upstream's complete Norwegian
  translation taken with `app_brand_name` reset to StreamBridge.
- `StreamsScreen.kt`: StreamBridge's inline `ProviderFilterRow` dropped in
  favour of upstream's extracted `ProviderFilterRow.kt` (which Enhanced
  extends with source pinning).
- `iosApp/iosApp/Resources/Fonts/` (StreamBridge location) moved back to
  upstream-Enhanced `iosApp/iosApp/SubtitleFonts/` to match
  `MPVSubtitleFontResolver.bundledFontsDirectory`; the Latin
  `NotoSans-Regular.ttf` is added alongside the CJK font.
- `store.json`, `MPVKit` submodule: kept out (StreamBridge is not published
  via the AltStore sources and does not vendor the submodule).
- Version metadata: `iosApp/Configuration/Version.xcconfig` set to
  0.5.1 / build 133; `streambridge.version.properties` pins updated.

**Preserved StreamBridge-specific functionality**

CloudStream compatibility runtime and settings, updater (`sascio/MyBridge`),
privacy & policy page, licenses & attributions, avatar catalog states,
Trakt/Simkl integration, Stremio addons, plugins, source picker, branding
strings (`app_brand_name` = StreamBridge in every locale that carried it),
full/playstore flavor split and signing configuration — all unchanged by
the merge (verified: no CloudStream-path files touched; MainActivity still
initializes `CloudStreamStorage`/`CloudStreamPlatformRuntime`).

## State of the core (0.4.25 audit, superseded counts)

Tree comparison against upstream `cbc921d9` at the last full audit:
3 upstream-only files (not ported: duplicate IMDb ratings repositories —
StreamBridge uses its OMDB implementation — and one iOS-only component),
143 StreamBridge-only files, 124 modified on both sides. The 0.5.1 sync
resolves the official-side portion of that gap; StreamBridge-only files
(Enhanced overlay + Live TV + downloads engine + OMDB ratings + CloudStream
+ branding) are retained.

## Verification

Re-run the comparison with:

```sh
git remote add nuvio https://github.com/NuvioMedia/NuvioMobile.git
git fetch nuvio
git diff --numstat HEAD nuvio/cmp-rewrite -- composeApp | awk -F'\t' '$2==0'  # upstream-only
git diff --numstat HEAD nuvio/cmp-rewrite -- composeApp | awk -F'\t' '$1==0'  # ours-only
```
