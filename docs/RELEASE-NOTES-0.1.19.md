## StreamBridge 0.1.19

StreamBridge version 0.1.19 (version code 119). Android package and iOS bundle:
`com.streambridge.app`.

## 🌐 StreamBridge website and community

[🌐 Visit StreamBridge](https://streambridgeapp.pages.dev) ·
[Telegram](https://t.me/StreamBridge_App) · [Discord](https://discord.gg/T9apykrjW)

## Changes

- CloudStream repository authorization is explicit. Package installation and loading check archive structure and published SHA-256 hashes. Packages without a published hash are identified as unverified. Android Full can run authorized third-party extensions; Android Play and iOS do not execute their code. Repository authorization and a matching hash do not make third-party code safe.
- CloudStream repository management includes bulk operations, collision checks, offline metadata retention and safer staged package replacement.
- MDBList library sorting supports release-date ordering as well as added-date ordering.
- Trakt library refresh requests are coalesced, and TorBox alternate hash responses can be matched to requested hashes.
- App-owned iOS downloads are excluded from system backups.

## Release assets

- Android Full: `androidApp-full-arm64-v8a-release.apk`, `androidApp-full-armeabi-v7a-release.apk`, `androidApp-full-x86-release.apk`, `androidApp-full-x86_64-release.apk`.
- iOS: `StreamBridge-0.1.19-iOS-unsigned.ipa` for signing through AltStore or SideStore.
- SHA-256: `checksums.sha256` for Android and the GitHub-reported asset digest for the IPA.

StreamBridge is GPL-3.0 software. **Your Media, Your Way.** Made with ❤️ by Saif.

### iOS installation

The iOS build is unsigned. [AltStore](https://altstore.io) or
[SideStore](https://sidestore.io) signs it with the installer's Apple ID.
Add `https://raw.githubusercontent.com/sascio/MyBridge/main/store.json` as a
source for updates once the source metadata has been verified and updated.
