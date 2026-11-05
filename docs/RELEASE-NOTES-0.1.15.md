# StreamBridge 0.1.15

## StreamBridge 0.1.15

StreamBridge version 0.1.15 (version code 115). Android package
`com.streambridge.app`, iOS bundle `com.streambridge.app`.

## Complete core baseline sync

This release integrates the complete applicable 0.6.7-beta core tree at source level —
96 upstream commits across the whole changed file set — while keeping every
StreamBridge-specific implementation, identifier and integration on top of it. The sync
manifest classified every changed file; conflict-prone files were three-way merged so
neither side's functionality was dropped.

## Appearance

- **Glass UI (experimental)** — a new appearance preset family with dark, balanced and
  vivid presets. Headers render edge-to-edge with a frosted finish on phones and pin at
  the very top of the screen; the full extended header band is dimmed so headers no
  longer look boxed; secondary and muted text stay readable over the app background.
- **App-wide background** — choose an image from your gallery or a URL and it is drawn
  once behind every screen. The dynamic-background and catalog-accent settings moved to
  Layout > Home.
- **Search and library** — both draw one continuous header band with the cloud toolbar
  pinned, and Search keeps Discover in its fixed header band.

## Player and playback

- The video can be resized beside the watch-party panel.
- The automatic source failover only re-arms on a real source change, so it no longer
  loops.
- Anime engine selection is fixed together with the auto-failover loop.
- The skip button keeps its timer continuity through segment-category changes.
- Reusing the last link keeps the chosen stream info and the current-stream marker.

## Subtitles and audio

- Addon-delivered ASS subtitles render through libass on Android again.
- Subtitle addon requests carry file extras and build correct request URLs.
- Local and P2P streams compute OpenSubtitles-compatible hashes for subtitle extras, in
  parity with the TV client.

## Continue watching

- Mark an item as watched directly from its long-press menu.

## Live TV

- The Live TV screen pins its title, search and filter header, and the last-watched card
  is gone.

## iOS

- Playback declares the long-form-video audio session, so the system no longer treats
  it as music playback.
- MPVKit was updated for the host audio session.
- Now Playing publishes only on real playback changes.
- The profile-insight photo picker opens after the native sheet closes, and the insight
  hero and cards render frosted over the app background.

## Translations

Arabic, Bulgarian, Greek, Spanish, French, Italian, Polish, Brazilian Portuguese,
Turkish and Vietnamese locales were completed and harmonized.

## Toolchain

Gradle 9.8.2, the Android Gradle Plugin 9.4.0 line and compile SDK 37.1.

## StreamBridge specifics

- The About Us entry regained its original icon and subtitle, restored verbatim from
  the original root-page row.
- About Us gained a "Join our Discord" invitation row next to the Telegram community
  channel, opening the invitation in the system browser.

## Download sizes

| Asset | previous release | 0.1.15 |
|---|---:|---:|
| `androidApp-full-arm64-v8a-release.apk` | 52.15 MiB | 52.45 MiB |
| `androidApp-full-armeabi-v7a-release.apk` | 50.35 MiB | 50.66 MiB |
| `androidApp-full-x86-release.apk` | 52.57 MiB | 52.88 MiB |
| `androidApp-full-x86_64-release.apk` | 53.58 MiB | 53.88 MiB |
| `StreamBridge-0.1.15-iOS-unsigned.ipa` | 69.72 MiB | 70.21 MiB |

Sizes are filled in from the production build jobs before publication.

## Installation

APKs built by branch CI are debug-signed compile artifacts, not production builds. The
published release carries one production APK per ABI (production-certificate signed,
SHA-256 checksums attached) and an **unsigned** iOS IPA for AltStore/SideStore
re-signing. Install only assets downloaded from this repository's published releases.

The in-app updater compares version codes, so 0.1.15 (code 115) is offered as an update
over any earlier version.
