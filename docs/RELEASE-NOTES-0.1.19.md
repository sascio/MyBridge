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
- Trakt library refresh requests are coalesced when Trakt is configured, and TorBox alternate hash responses can be matched to requested hashes. This release does not include Trakt credentials, so Trakt sign-in and sync are unavailable in this build.
- MDBList personal ratings follow cursor and offset pages, and Simkl anime rating removal uses the show's removal endpoint.
- Android's optional playback disk cache sizes itself against available space and bypasses caching when space is insufficient.
- App-owned iOS downloads are excluded from system backups. System Back returns from an embedded show's downloads to the downloads list.
- Profile GIF search can use the configured search proxy or, when none is configured, DuckDuckGo. The selected GIF is saved through the existing avatar editor.
- Actor filmographies omit talk-show guest appearances while retaining hosts and ordinary acting credits.
- An optional title assistant can use your OpenRouter key and chosen model. The key and conversation are kept only while the dialog is open; sending shares title metadata and messages with the external service and model provider.

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
