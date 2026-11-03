# StreamBridge 0.1.14

## StreamBridge 0.1.14

StreamBridge version 0.1.14 (version code 114), built from the official
NuvioMobile `0.6.6-beta` tree with the applicable NuvioMobile-Enhanced `0.6.6-beta`
improvements. Android package `com.streambridge.app`, iOS bundle
`com.streambridge.app` — unchanged.

## Corrections in this rebuild

This release was rebuilt in place (same version, same version code) to correct
regressions found in the first 0.1.14 build. The corrected assets replace the
original ones under the same names; see the asset table below.

- **About Us heading** — the About page heading briefly read
  "Supporters & Contributors". It is `About Us` again everywhere the page is
  reached (the Settings About row, settings search, and the page header). The
  page's content — description cards, credits, community links — is unchanged.
- **Footer** — the tagline that had been added to the settings footer is
  removed, and the logo + app name are centred with the text lines they were
  offset from. The footer is the StreamBridge lockup, `Made with ❤️ by Saif`,
  and the version line at the bottom, using the build's real version
  configuration.
- **Hero section** — the home hero now renders the full metadata rows it
  already had data for: content type, genre, runtime, release date, IMDb
  rating, age rating / certification and playing status, language, MDBList /
  TMDB external ratings when configured, and the title's description. Values a
  catalog preview omits are filled from the app's existing metadata cache, so
  nothing is fabricated and no extra network traffic is added beyond a details
  fetch that was already cached from the hero loader. Long titles and synopses
  ellipsize; missing values simply don't render.
- **Branding** — remaining user-facing "Nuvio" strings in translated and
  English resources now say StreamBridge: library/progress source pickers
  (StreamBridge Library, StreamBridge Sync), Discord Rich Presence, the
  Picture-in-Picture notice, AniList/WeTrakr approval copy, settings-backup
  copy, P2P consent text, notification, updater and addon-store copy, and the
  TMDB/IMDb/Trakt/Simkl/MDBList/TorBox/Premiumize/IntroDB attribution bodies
  (which correctly name StreamBridge as the app using those services).
  Licence attributions, the privacy policy's backend disclosures and the
  `api.nuvio.tv` host keep their required references.
- **Size** — five resource files that no code referenced were removed
  (`app_logo_wordmark.png`, `compose-multiplatform.xml`, `sidebar_home.xml`,
  `sidebar_settings.xml`, `trailer_play_button.svg`). Release APKs are
  unaffected by this cleanup (resource shrinking already stripped them); it
  trims the source tree and debug builds. No dependency, codec, provider or
  platform support was touched: with shrinking, per-ABI splits and compressed
  native libraries already in place, every remaining byte is a working feature.

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

| Asset | previous release | 0.1.14 first build | 0.1.14 corrected build |
|---|---:|---:|---:|
| `androidApp-full-arm64-v8a-release.apk` | 50.33 MiB | 52.05 MiB | 52.02 MiB |
| `androidApp-full-armeabi-v7a-release.apk` | 48.68 MiB | 50.26 MiB | 50.22 MiB |
| `androidApp-full-x86-release.apk` | 50.76 MiB | 52.48 MiB | 52.44 MiB |
| `androidApp-full-x86_64-release.apk` | 51.63 MiB | 53.48 MiB | 53.45 MiB |
| `StreamBridge-0.1.14-iOS-unsigned.ipa` | 69.66 MiB | 69.75 MiB | built with the release |

The Android growth over the previous release is the native module 0.6.6 adds
(Rust thumbnails and container indexes, software decoder, libass); no provider,
codec, player integration or platform was dropped to compensate. The corrected
build measures within 0.1% of the first build — the only source-level removals
are five unreferenced resource files, which release APKs had already stripped
through resource shrinking.

## Verification

Static checks and builds run in the repository's own CI (unit tests, Android
lint, full/playstore flavour boundary checks, CloudStream runtime verification,
production signing verification, iOS IPA). See the release notes on the GitHub
release for the artefacts that were actually produced.

### Same-version updater note

This corrected build keeps version code 114, exactly like the first 0.1.14
build. An installed 0.1.14 therefore does not see this rebuild as an update:
version-code comparison only moves upward. Install the corrected APK for your
device ABI directly from this release to pick the fixes up. New installs and
installs of any earlier version update normally. The version was deliberately
not bumped to work around this, per the release decision for 0.1.14.

### iOS installation

The iOS build in this release is **unsigned**. Install it with
[AltStore](https://altstore.io) or [SideStore](https://sidestore.io): either signs the IPA with
your own Apple ID on your device, so no Apple Developer account and no App Store listing is
involved.

Add this repository as a source in AltStore or SideStore to get updates automatically:

`https://raw.githubusercontent.com/sascio/MyBridge/main/store.json`

See `docs/RELEASE-IOS.md` for the details.

