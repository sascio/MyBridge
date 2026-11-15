# StreamBridge 0.1.18

## StreamBridge 0.1.18

StreamBridge version 0.1.18 (version code 118). Android package
`com.streambridge.app`; iOS bundle `com.streambridge.app`.

## 🌐 StreamBridge website and community

The official StreamBridge home is live. Explore the project, its features, and support resources:

**[🌐 Visit StreamBridge](https://streambridgeapp.pages.dev)**

Join the community on [Telegram](https://t.me/StreamBridge_App) and
[Discord](https://discord.gg/T9apykrjW).

## Playback, tracking, and stability

This release includes selected synchronized upstream improvements. The path-by-path source attribution
and StreamBridge-specific resolutions are recorded in the [integration manifest](SYNC-MANIFEST-0.6.12-beta.md).

- Subtitle preferences can be separated per device, exported/imported with settings, and cleared
  when a profile is removed.
- Chapter navigation and chapter-title controls now cover both playback engines and iOS. Android
  ExoPlayer adds Blu-ray ISO, BDMV and M2TS playback support.
- Live TV fixes address endless buffering and black screens, and restore the HLS quality chooser.
  HLS streams missing SPS/PPS initialization data and DASH streams with explicit MIME hints are
  routed more reliably.
- MPV position-zero glitches and playback error messages are improved. Saved tokenized stream links
  can be reused and refreshed after expiry.
- Folder views respect the catalog-type setting; IMDb actions in Discord Rich Presence are fixed.
- Simkl AUTH V2 supports PKCE browser and device authorization, protected refresh-token storage,
  and automatic refresh while retaining StreamBridge's profile-aware settings and sync flow.
- The Anime4K line sharpener has stronger settings. Stability fixes cover subtitle preferences,
  playback, and profile removal.

## Localization

Dutch, French, Vietnamese, and other supported translations were updated while keeping StreamBridge
wording and community identity localized consistently.

## Downloads

Download each production build directly from this release:

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
iOS identifiers, updater and distribution metadata, GPL-3.0 licensing, production Android signing
configuration, Telegram automation, and unsigned iOS IPA distribution remain unchanged. The
AltStore/SideStore source is updated only after the published release workflow verifies the actual
unsigned IPA and its digest.
