## StreamBridge 0.1.18

StreamBridge version 0.1.18 (version code 118). Android package and iOS bundle:
`com.streambridge.app`.

## 🌐 StreamBridge website and community

Visit [🌐 Visit StreamBridge](https://streambridgeapp.pages.dev) for project information and
support resources. Join the community on [Telegram](https://t.me/StreamBridge_App) or
[Discord](https://discord.gg/T9apykrjW).

## Playback and source selection

- When an automatically selected source fails during startup, playback can try the next matching
  source. The fallback setting and attempt limit are configurable; manual source choices remain
  under the user's control.
- The loading screen shows clearer playback startup stages. Keyboard controls include basic player
  navigation and volume adjustment.
- Repeated searches for the same title, episode, profile, and source setup share in-progress and
  recently completed results across the stream list and player source panels. Refreshes and changed
  source configurations remain separate.
- Continue Watching can be configured to open an item in the preferred destination. Dismissing a
  next-up entry is keyed to that content rather than only its series.
- Stream badge updates are synchronized before the next sync pull, and playback recommendations use
  the configured Simkl source where available.

## Home, collections, and discovery

- Home waits for source catalogs before showing the hero, and its page state and artwork transitions
  have been refined to avoid unstable content while catalog metadata loads.
- Landscape poster grids show more items per row, with a tablet-specific layout.
- Collection headers and covers have improved behavior across orientation changes. The streams
  artwork backdrop is retained beneath the glass treatment.
- Trailer playback and its mute control are more consistent on mobile and tablet layouts.

## iOS video and interface

- Picture in Picture has improved HDR and Dolby Vision frame handling, including the associated
  metadata and subtitle-rendering paths.
- The player drawable uses the source's native resolution. iPad settings sub-pages retain a visible
  back control, and collection headers respect the native navigation area in landscape.

## Languages and compatibility

French, Slovak, Vietnamese, Indonesian, and Polish translations were updated. StreamBridge wording,
community links, and localized product identity are retained.

## Downloads and installation

| Platform / architecture | Download |
|---|---|
| Android · arm64-v8a | [Download APK](https://github.com/sascio/MyBridge/releases/download/v0.1.18/androidApp-full-arm64-v8a-release.apk) |
| Android · armeabi-v7a | [Download APK](https://github.com/sascio/MyBridge/releases/download/v0.1.18/androidApp-full-armeabi-v7a-release.apk) |
| Android · x86 | [Download APK](https://github.com/sascio/MyBridge/releases/download/v0.1.18/androidApp-full-x86-release.apk) |
| Android · x86_64 | [Download APK](https://github.com/sascio/MyBridge/releases/download/v0.1.18/androidApp-full-x86_64-release.apk) |
| iOS · unsigned IPA | [Download IPA](https://github.com/sascio/MyBridge/releases/download/v0.1.18/StreamBridge-0.1.18-iOS-unsigned.ipa) |
| Android APK SHA-256 checksums | [Download checksums](https://github.com/sascio/MyBridge/releases/download/v0.1.18/checksums.sha256) |

The checksum file covers the four Android APKs. GitHub also exposes the SHA-256 digest for each
release asset, including the iOS IPA. The iOS IPA is intentionally unsigned for AltStore and
SideStore installation. Production Android APKs are signed with the existing production certificate.

## StreamBridge identity and distribution

The app name, motto **Your Media, Your Way**, footer credit **Made with ❤️ by Saif**, Android and
iOS identifiers, updater compatibility, GPL-3.0 licensing, production Android signing, Telegram
automation, release URLs, and AltStore/SideStore metadata remain StreamBridge-owned. The sideload
source is updated only after the published release workflow verifies the actual unsigned IPA and its
digest.
