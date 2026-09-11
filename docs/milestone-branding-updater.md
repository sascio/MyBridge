# Nuvio 0.4.17 sync + StreamBridge branding + silent updater

**Status: Nuvio 0.4.17 overlaid, StreamBridge version 0.1.01, splash/credits/updater
tightened. CI assemble = pending this commit. PLAYBACK / INSTALL / LAUNCH =
REAL DEVICE VALIDATION REQUIRED.**

Compilation is not playback. Do not treat a green assemble as movies,
series, subtitles, or tracks working on a phone.

## Previous Nuvio pin

`465266d7059b3f226b378988393497e8c64a768d` — NuvioMobile 0.4.16

## Integrated Nuvio pin

`d177eb57dfc8989e56de577bda8f4c2714b2bd64` — NuvioMobile **0.4.17**
(`cmp-rewrite`, `bump version`). Official GitHub release tag is still 0.4.16;
xcconfig marketing version is 0.4.17.

## Important upstream changes brought in

From `465266d..d177eb57` (8 commits, 36 files):

- `fix(sync): prevent automatic pulls from restoring deleted data`
- plugin QuickJS pool / bytecode cache / fetch cancel (TV match)
- pause local plugin search during playback
- `fix(streams): avoid main-thread stringResource during list compose`
- binge-group reuse **off by default**
- TV plugin runtime merge
- version bump 0.4.17

Player engine / decoder AARs unchanged. Updater sources still StreamBridge-only.

## Merge conflicts

None. Overlay copy of the upstream diff, then StreamBridge deltas re-applied.

## StreamBridge-specific functionality preserved

- Empty addons/plugins (no seeded URLs)
- Integrations unconfigured by default
- `applicationId` `com.streambridge.app`
- CI `:androidApp:assembleFullDebug` / `assembleFullRelease`
- Quarantined `legacy/streambridge-app/` not on the runtime path
- Nuvio player / addon / plugin / P2P architecture untouched

## Branding

- User-facing version **exactly `0.1.01`** (versionCode 101)
- Splash overlay: StreamBridge logo (not huge) → “StreamBridge” →
  “Your Media, Your Way”. No credits/legal/based-on-Nuvio on splash.
- Android 12 splash + launcher: symbol only
- About footer credits (tiny): StreamBridge / Based on NuvioMobile / NuvioMedia
- LICENSE / NOTICE / GPL preserved in files and Licenses page

## Updater

Nuvio `AppUpdater` + `AppUpdaterBanner` (full flavor). Source:

`https://api.github.com/repos/sascio/MyBridge/releases`

Drafts and prereleases ignored. ABI-aware APK pick.

Silent unless a **valid newer StreamBridge APK** exists:

- Auto-check on startup: no toast / no banner if no release, no network, API fail, or already current
- Settings → Check for updates: same; only shows Nuvio-style banner when a newer APK is present
- Download/install errors still surface on that banner after a real update is shown

Does **not** use NuvioMedia releases or Actions artifacts.

Startup network toasts (`Cannot reach servers` / `No internet connection`)
are suppressed until Home is ready so they are not launch popups.

Signing: [SIGNING.md](SIGNING.md) — **PARTIAL** until a dedicated
upload keystore is configured.

## StreamBridge version

0.1.01 (versionCode 101) in `streambridge.version.properties`.
Nuvio 0.4.17 remains recorded separately.

## Upstream sync mechanism

[`.github/workflows/nuvio-upstream.yml`](../.github/workflows/nuvio-upstream.yml)
opens an issue when `cmp-rewrite` moves. No automatic production merge.
See [UPSTREAM-SYNC.md](UPSTREAM-SYNC.md).

## Build / device

GitHub Actions `Build Stream Bridge` run **34648085532**
(`:androidApp:assembleFullDebug` + `assembleFullRelease`) **SUCCESS**
in about 8 minutes.

Device install, launch, playback, addons, and updater download/install
are **UNVERIFIED** here.
