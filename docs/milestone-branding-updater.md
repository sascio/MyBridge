# Nuvio 0.4.16 sync + StreamBridge branding + updater

**Status: SOURCES SYNCED AND OVERLAID. CI APK PENDING AT TIME OF WRITE.
PLAYBACK / INSTALL / LAUNCH = REAL DEVICE VALIDATION REQUIRED.**

Compilation is not playback. Do not treat a green assemble as movies,
series, subtitles, or tracks working on a phone.

## Previous Nuvio pin

`e377942` — NuvioMobile 0.4.15 (`chore(store): publish 0.4.15`)

## Integrated Nuvio pin

`465266d7059b3f226b378988393497e8c64a768d` — NuvioMobile **0.4.16**
(`cmp-rewrite`, `chore(store): publish 0.4.16`)

## Important upstream changes brought in

From `e377942..465266d` (56 files): home hero settle, poster/navigation
transitions, native loading indicator (compottie removed), settings
search deferral, Trakt continue-watching window, i18n (el/nl/vi),
Android baseline profile, `androidResources.noCompress += cvr`,
optional `releaseMinifyEnabled`. **Updater sources unchanged** in
upstream; player engine / AARs unchanged.

## Merge conflicts

None. Overlay copy of the upstream diff, then StreamBridge deltas
re-applied (applicationId, signing fallback, local.properties,
version properties, updater GitHub target, branding).

## StreamBridge-specific functionality preserved

- Empty addons/plugins (no seeded URLs)
- Integrations unconfigured by default
- `applicationId` `com.streambridge.app`
- CI `:androidApp:assembleFullDebug` / `assembleFullRelease`
- Quarantined `legacy/streambridge-app/` not on the runtime path
- Nuvio player / addon / plugin / P2P architecture untouched

## Branding

- User-facing `app_brand_name` / `app_name` → StreamBridge
- Tagline “Your Media, Your Way” on About
- About credits: based on NuvioMobile by NuvioMedia, GPL-3.0; Tapframe
  line kept
- Launcher: B + play mark from the StreamBridge logo (no wordmark on
  the small icon)
- Splash: same mark (`ic_splash_logo`)
- In-app original wordmark asset replaced

## Updater

Nuvio `AppUpdater` + `AppUpdaterBanner` (full flavor). Source retargeted:

`https://api.github.com/repos/sascio/MyBridge/releases`

Drafts and prereleases ignored. ABI-aware APK pick. Settings → Check
for updates. Does **not** use NuvioMedia releases or Actions artifacts.

Signing: [SIGNING.md](SIGNING.md) — **PARTIAL** until a dedicated
upload keystore is configured.

## StreamBridge version

0.5.0 (versionCode 500) in `streambridge.version.properties`.
Nuvio 0.4.16 remains recorded separately.

## Upstream sync mechanism

[`.github/workflows/nuvio-upstream.yml`](../.github/workflows/nuvio-upstream.yml)
opens an issue when `cmp-rewrite` moves. No automatic production merge.
See [UPSTREAM-SYNC.md](UPSTREAM-SYNC.md).

## Build / device

Recorded after CI in a follow-up. Device install, launch, playback,
addons, and updater download/install are **UNVERIFIED** here.
