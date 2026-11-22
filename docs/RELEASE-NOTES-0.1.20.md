## StreamBridge 0.1.20

StreamBridge version 0.1.20 (version code 120). Android package and iOS bundle:
`com.streambridge.app`.

## 🌐 StreamBridge website and community

[🌐 Visit StreamBridge](https://streambridgeapp.pages.dev) ·
[Telegram](https://t.me/StreamBridge_App) · [Discord](https://discord.gg/T9apykrjW)

## Changes

- CloudStream repository authorization is now a visible action. The extension details screen and every repository card show the authorization state, the **Authorize repository** button opens a confirmation naming the repository, and authorizing it covers every extension that repository provides (CNC Verse and other CloudStream providers included). A refused install or enable now shows a banner that carries the authorization action instead of an error with no way forward. Authorization remains explicit and per repository: adding a repository never authorizes it, and no build can auto-trust every repository. Authorization and a matching hash do not make third-party code safe.
- Fixed the Android build failing during configuration on a runner whose NDK directory was incomplete (`[CXX1101] ... did not have a source.properties file`) and on a corrupt SDK download cache. `scripts/setup-android-sdk.sh` now validates and repairs the platform, build-tools, CMake and NDK before Gradle runs, the validated NDK is pinned for the build, and a broken preinstalled NDK is replaced instead of being selected.
- Unit tests, release builds and preflight verification now run against the validated SDK, so a provisioning fault fails with an actionable message rather than an unrelated "unit tests failed" step.

## Release assets

- Android Full: `androidApp-full-arm64-v8a-release.apk`, `androidApp-full-armeabi-v7a-release.apk`, `androidApp-full-x86-release.apk`, `androidApp-full-x86_64-release.apk`.
- iOS: `StreamBridge-0.1.20-iOS-unsigned.ipa` (for AltStore or SideStore).
- SHA-256: `checksums.sha256` for Android and `checksums-ios.sha256` for iOS.

StreamBridge is GPL-3.0 software. **Your Media, Your Way.** Made with ❤️ by Saif.

### iOS installation

This IPA is **unsigned**. Install with [AltStore](https://altstore.io) or
[SideStore](https://sidestore.io), which signs it with your Apple ID. Add
`https://raw.githubusercontent.com/sascio/MyBridge/main/store.json` as a source
for updates. See `docs/RELEASE-IOS.md` for installation details.
