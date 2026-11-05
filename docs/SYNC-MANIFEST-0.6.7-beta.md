# StreamBridge 0.1.15 — Sync manifest (core baseline 0.6.7-beta)

Recorded before implementation, verified after. Baseline: StreamBridge `e2a7a2a`
(0.1.14, code 115-pre), core pin 0.6.6-beta (`2fc68109`). Target: Enhanced
`0.6.7-beta` (`376f8619`), which merges the official core `0.6.7-beta`
(`3e156936`) — now recorded in `streambridge.version.properties`.

## Upstream range

- 96 commits between `2fc68109..376f8619`.
- 114 files changed: 33 added, 81 modified (+8,671/−3,487 lines).
- Release notes were NOT used as the checklist; the classification below comes from
  the actual `git diff --name-status` over the range, compared byte-for-byte
  against the StreamBridge tree.

## Classification and disposition

| Class | Files | Disposition |
|---|---:|---|
| Verbatim take (ours byte-identical to the 0.6.6 base, or brand-new upstream files) | 85 | copied from `376f8619` unchanged |
| Three-way merge (both sides changed) | 27 | `git merge-file`; 19 auto-merged, 8 with conflicts resolved by hand |
| Upstream CI workflows we do not use (`ios-test-build.yml`, `mobile-release.yml`) | 2 | skipped — StreamBridge owns its release pipeline |

## Conflict resolutions (all 8)

| File | Resolution |
|---|---|
| `composeApp/build.gradle.kts` | kept the StreamBridge version-property read; added the upstream iOS phase-skip knob |
| `iosApp/Configuration/Version.xcconfig` | StreamBridge-owned: 0.1.15 / 115 (upstream's 0.6.7/150 rejected) |
| `store.json` | StreamBridge-owned: kept our source verbatim (upstream's store entry rejected) |
| `values-ar/strings.xml` (2), `values-es/strings.xml` (1), `values-fr/strings.xml` (9), `values-pt-rBR/strings.xml` (3), `values-vi/strings.xml` (23) | took upstream's translation hunks, then re-applied the branding scrub (restore-our-translation first, brand-substitute for new strings) |
| `ProfileInsightsSettingsPage.kt` (1) | took upstream's visibility tightening; the class has no external users |

## StreamBridge-owned files preserved (not overwritten)

`streambridge.version.properties`, `store.json`, `iosApp/Configuration/Version.xcconfig`,
`androidApp/build.gradle.kts` (signing/ABI splits/minify), all `.github/workflows/*`,
`scripts/verify-release-target.sh`, `scripts/tests/run-tests.sh`, README branding,
About Us page and footer, splash/brand lockups, app icons, updater configuration,
application IDs.

## Feature checklist from the actual diff

Glass UI presets + frosted pinned headers; app-wide background (gallery/URL) +
background settings section; dynamic-background/catalog-accent moved to Layout > Home;
party panel video resize; addon ASS subtitles via libass (Android); subtitle addon
requests with file extras; OpenSubtitles-compatible hashing for local/P2P (common +
Android/iOS actuals); stream-info reuse retention; auto-failover re-arm fix; anime
engine selection fix; mark-as-watched from continue watching; Live TV pinned header;
search/library header band; profile-insight frosted hero + photo-picker fix; iOS
long-form audio session + MPVKit bump + Now Playing publish-on-change; i18n completion
for ar/bg/el/es/fr/it/pl/pt-BR/tr/vi; Gradle 9.8.2 / AGP 9.4.0 / compile SDK 37.1;
new unit tests (player current stream, subtitle request extras, mark-watched).

## StreamBridge 0.1.15 additions on top

About Us row icon and subtitle restored verbatim from the original root-page row
(`Icons.Rounded.Info` + `about_us_settings_subtitle`); Discord invitation row
(`https://discord.gg/T9apykrjW`, system-browser open) added to About Us; version bump
to 0.1.15 / 115 across the five wired files.

## Regression guards

- Search GIFs (profile avatar GIF search, `ProfileGifSearch.kt`) and the custom avatar
  flow (`AvatarRepository.kt`, `ProfileEditScreen.kt`): zero files touched by the sync
  (verified via `git diff --name-only`).
- Hero stability model, About Us heading, footer layout, updater, signing and store
  pipeline: untouched by upstream changes; gate scripts updated to 0.1.15 and green.
