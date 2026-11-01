# StreamBridge 0.1.14

## StreamBridge 0.1.14

StreamBridge version 0.1.14 (version code 114), built from the official
NuvioMobile `0.6.6-beta` tree with the applicable NuvioMobile-Enhanced `0.6.6-beta`
improvements. Android package `com.streambridge.app`, iOS bundle
`com.streambridge.app` — unchanged.

## Upstream integration

- Official NuvioMobile `0.6.6-beta`, tag object
  `2d52c8b71b598eb90e13fa9ec9ccff97ab601126` (branch `cmp-rewrite`).
- NuvioMobile-Enhanced `0.6.6-beta`,
  `2fc68109f9a4919c7fae373740ac8d5aae5253ca` (branch `enhanced`, which merges the
  official tag).
- Integration was source-level and file-by-file over the previous `0.6.3-beta`
  pin, not a merge of upstream history. Files StreamBridge never touched were
  taken verbatim; files StreamBridge had customised were three-way merged. The
  full matrix is in [upstream-parity-0.6.6.md](upstream-parity-0.6.6.md).

## What is new

- **Subtitles** — libass atlas rendering for styled ASS/SSA tracks, a subtitle
  appearance page (font, size in 1 sp steps, weight, outline, shadow,
  background, margins) with preview, custom font import, and subtitle styling
  that survives track changes and pause/resume.
- **Chapters and skipping** — chapter panel, Matroska chapter parsing, merged
  and overlapping skip intervals, a setting for when Skip Intro appears, and
  auto-skip on resolved HLS and P2P streams.
- **Night mode** — libmpv audio night mode (dialogue lift + peak limiting) on
  both playback engines, with a player button and a settings entry.
- **Settings** — regrouped root (You / Look / Watch / Services / System) and
  dedicated playback sub-pages; configuration backup and restore.
- **Player and library polish** — single-decimal IMDb ratings, unreleased
  episode labelling, app-icon persistence, player badge codec detection from
  filenames, offline improvements, TV parity for the engine switch.
- **Native modules** — upstream's Cargo workspace builds
  `libaiostreams_native.so` for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`
  (seek-bar thumbnails, container indexes). CI installs the Rust Android targets
  and `cargo-ndk`; the NDK comes from the Android SDK.
- **Media stack** — `lib-nuvio-engine-android` 0.1.6 (the iOS engine download is
  pinned to the same version and checksum), `nuvio-libass` replaces the
  peerless2012 ASS artifacts, and Compose Multiplatform / Material3 /
  Navigation3 move to the versions 0.6.6 requires.

## StreamBridge-specific changes kept

- Package `com.streambridge.app`, app name StreamBridge, launcher icon, splash,
  updater configuration, signing setup, store JSON and release workflows.
- Watch Party invites: `streambridge://party/<code>` generated from the
  normalized party code, StreamBridge-branded invitation text in every locale,
  an install fallback for recipients without the app, and a repository-hosted
  HTTPS redirect page. `nuvio://party/…` and legacy `https://nuvio.tv/party/…`
  invites are still parsed so links shared by earlier builds keep working.
- The privacy policy is an in-app page. Upstream linked the About section to
  `https://nuvio.tv/privacy-policy`.
- CloudStream (`.cs3`) extension manager page, in-app updater, profiles,
  licenses and attributions pages.
- New in this release: the legacy `https://nuvio.tv/party/…` intent filter no
  longer claims `autoVerify`. That domain belongs to NuvioMedia; StreamBridge
  keeps parsing legacy invites but does not ask Android to verify a domain it
  does not own.

## Not integrated

- Upstream's GitHub workflows, `store.json`, `README.md` and Xcode version file:
  StreamBridge owns those. The version stays `0.1.14` / `114`.
- Upstream's CloudStream-free content-discovery layout: StreamBridge keeps its
  CloudStream row.

## Fixes in this release

- Credit rolls longer than four minutes can be skipped again: the chapter skip
  derivation only capped openings/recaps at four minutes, so a film's
  "Credits" chapter (routinely 5-8 minutes) never produced a skip.
- The iOS build no longer depends on the unreleased iOS 27 SDK. Nuvio 0.6.6's
  iOS host uses fold/vertical-bar APIs that only exist there, so this build
  keeps the plain safe-area layout (no behaviour change on any iOS version the
  app supports).
- The watch-party invite no longer asks Android to App-Link-verify `nuvio.tv`, a
  domain StreamBridge does not own. Legacy `https://nuvio.tv/party/<code>`
  invites are still parsed.
- Branding: the Face ID and local-network iOS permission prompts, the About
  footer and four newly added locales now say StreamBridge instead of Nuvio.

## Branding

Every user-visible Nuvio match was audited; the classification (leak / internal
reference / required attribution) and the remaining translation gap are in
[BRANDING-AUDIT-0.1.14.md](BRANDING-AUDIT-0.1.14.md).

## Download sizes

| Asset | previous release | 0.1.14 |
|---|---:|---:|
| `androidApp-full-arm64-v8a-release.apk` | 50.33 MiB | 52.05 MiB |
| `androidApp-full-armeabi-v7a-release.apk` | 48.68 MiB | 50.26 MiB |
| `androidApp-full-x86-release.apk` | 50.76 MiB | 52.48 MiB |
| `androidApp-full-x86_64-release.apk` | 51.63 MiB | 53.48 MiB |
| `StreamBridge-0.1.14-iOS-unsigned.ipa` | 69.66 MiB | 69.75 MiB |

The Android growth is the native module 0.6.6 adds (Rust thumbnails and
container indexes, software decoder, libass); no provider, codec, player
integration or platform was dropped to compensate.

## Verification

Static checks and builds run in the repository's own CI (unit tests, Android
lint, full/playstore flavour boundary checks, CloudStream runtime verification,
production signing verification, iOS IPA). See the release notes on the GitHub
release for the artefacts that were actually produced.

### iOS installation

The iOS build in this release is **unsigned**. Install it with
[AltStore](https://altstore.io) or [SideStore](https://sidestore.io): either signs the IPA with
your own Apple ID on your device, so no Apple Developer account and no App Store listing is
involved.

Add this repository as a source in AltStore or SideStore to get updates automatically:

`https://raw.githubusercontent.com/sascio/MyBridge/main/store.json`

See `docs/RELEASE-IOS.md` for the details.

