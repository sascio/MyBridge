# StreamBridge 0.1.14

## StreamBridge 0.1.14

StreamBridge version 0.1.14 (version code 114). Android package
`com.streambridge.app`, iOS bundle `com.streambridge.app`.

## Hero Section — stability and richer information

The home hero has been rebuilt on the stable page model the reference
implementation uses, fixing the vertical wobble that appeared when the
displayed title changed:

- **Prefetch before the swipe** — while the carousel rests on a title, the
  neighbours' artwork, logo and details are resolved into the in-app caches.
  The incoming title therefore composes with its complete, final-height
  content on the first frame instead of growing after it becomes visible.
- **Draw-time transitions** — artwork and content layers read the live pager
  position at draw time. Content crossfades at the swipe midpoint with no
  partial-opacity blend, artwork layers never dip mid-transition, and layers
  are no longer reordered during a swipe.
- **Stable active item** — the trailer, artwork callback and action row now
  track the settled page directly, so a swipe can no longer restart them
  mid-transition.

The hero also renders the full metadata the app already had: title, content
type, genre, runtime, release date, IMDb rating, age rating / certification
and playing status, language, MDBList / TMDB external ratings when configured,
and the description. Values a catalog preview omits are filled from the app's
metadata cache — nothing is fabricated, no empty labels are shown, and long
titles and synopses ellipsize. Playback actions, the library toggle, trailer
playback and scrolling behaviour are unchanged.

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
- **Branding** — remaining third-party product strings in translated and
  English resources now say StreamBridge: library/progress source pickers
  (StreamBridge Library, StreamBridge Sync), Discord Rich Presence, the
  Picture-in-Picture notice, AniList/WeTrakr approval copy, settings-backup
  copy, P2P consent text, notification, updater and addon-store copy, and the
  TMDB/IMDb/Trakt/Simkl/MDBList/TorBox/Premiumize/IntroDB attribution bodies
  (which correctly name StreamBridge as the app using those services).
  Licence attributions, the privacy policy's backend disclosures and the
  media-server host keep their required references.
- **Size** — five resource files that no code referenced were removed
  (`app_logo_wordmark.png`, `compose-multiplatform.xml`, `sidebar_home.xml`,
  `sidebar_settings.xml`, `trailer_play_button.svg`). Release APKs are
  unaffected by this cleanup (resource shrinking already stripped them); it
  trims the source tree and debug builds. No dependency, codec, provider or
  platform support was touched: with shrinking, per-ABI splits and compressed
  native libraries already in place, every remaining byte is a working feature.

## What is new in 0.1.14

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
- **Native modules** — a Rust module builds `libaiostreams_native.so` for
  `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` (seek-bar thumbnails,
  container indexes).
- **Media stack** — playback engine 0.1.6 (the iOS engine download is pinned
  to the same version and checksum), a bundled ASS renderer for libass
  subtitles, and current Compose Multiplatform / Material3 / Navigation3.

## StreamBridge-specific behaviour

- Package `com.streambridge.app`, app name StreamBridge, launcher icon, splash,
  updater configuration, signing setup, store JSON and release workflows.
- Watch Party invites: `streambridge://party/<code>` generated from the
  normalized party code, StreamBridge-branded invitation text in every locale,
  an install fallback for recipients without the app, and a repository-hosted
  HTTPS redirect page. Legacy invite link formats are still parsed so links
  shared by earlier builds keep working.
- The privacy policy is an in-app page.
- CloudStream (`.cs3`) extension manager page, in-app updater, profiles,
  licenses and attributions pages.
- The legacy third-party party-invite intent filter no longer claims
  `autoVerify` for a domain StreamBridge does not own. Legacy invites are
  still parsed.

## Fixes in this release

- Credit rolls longer than four minutes can be skipped again: the chapter skip
  derivation only capped openings/recaps at four minutes, so a film's
  "Credits" chapter (routinely 5-8 minutes) never produced a skip.
- The iOS build no longer depends on an unreleased iOS SDK: this build keeps
  the plain safe-area layout (no behaviour change on any iOS version the app
  supports).
- The watch-party invite no longer asks Android to App-Link-verify a domain
  StreamBridge does not own. Legacy invite links are still parsed.
- Branding: the Face ID and local-network iOS permission prompts, the About
  footer and four newly added locales now say StreamBridge.
- Stable hero section: content changes no longer move the hero vertically
  (see the Hero Section section above).

## Branding

Every legacy third-party branding match in user-facing resources was audited,
replaced with StreamBridge branding, or kept where a licence, a backend
disclosure or a compatibility statement requires it. The classification is
recorded in [BRANDING-AUDIT-0.1.14.md](BRANDING-AUDIT-0.1.14.md).

## Download sizes

| Asset | previous release | 0.1.14 corrected build |
|---|---:|---:|
| `androidApp-full-arm64-v8a-release.apk` | 50.33 MiB | measured below |
| `androidApp-full-armeabi-v7a-release.apk` | 48.68 MiB | measured below |
| `androidApp-full-x86-release.apk` | 50.76 MiB | measured below |
| `androidApp-full-x86_64-release.apk` | 51.63 MiB | measured below |
| `StreamBridge-0.1.14-iOS-unsigned.ipa` | 69.66 MiB | measured below |

The Android growth over the previous release is the native module this
release adds (Rust thumbnails and container indexes, software decoder,
libass); no provider, codec, player integration or platform was dropped to
compensate.

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
