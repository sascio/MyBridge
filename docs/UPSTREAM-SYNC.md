# Upstream Nuvio sync status

## Current pin

| Field | Value |
|---|---|
| Upstream repo | https://github.com/NuvioMedia/NuvioMobile |
| Branch | `cmp-rewrite` |
| Commit | `b88fef2e655609443a9949e578c5267110be5fac` |
| Release | 0.5.1 (`chore(store): publish 0.5.1`, 2026-09-23) |
| Enhanced overlay | `9d311c18` (tag `0.5.1-beta`, 2026-09-23) |

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

## 0.5.1 / Enhanced 0.5.1-beta integration (this sync)

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
