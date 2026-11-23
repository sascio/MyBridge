## StreamBridge 0.1.21

StreamBridge version 0.1.21 (version code 121). Android package and iOS bundle:
`com.streambridge.app`.

## 🌐 StreamBridge website and community

[🌐 Visit StreamBridge](https://streambridgeapp.pages.dev) ·
[Telegram](https://t.me/StreamBridge_App) · [Discord](https://discord.gg/T9apykrjW)

StreamBridge 0.1.21 removes the title-specific Ask AI feature and improves CloudStream extension compatibility.

## Changes

- Removed “Ask AI about this title”, its OpenRouter client, resources, and related privacy text.
- CloudStream extensions now expose the actual providers registered by an installed package as child sources under their parent extension.
- Nested provider names, runtime identities, and declared content categories are preserved.
- Playback resolution targets the selected nested provider instead of treating package content types as providers.
- Installed extensions restore their nested provider hierarchy after restart, with actionable registration errors.
- Repository authorization, published SHA-256 verification, archive validation, and private package storage remain mandatory.
- CloudStream DEX execution remains restricted to the Android Full distribution; Play Store and iOS builds do not execute downloaded DEX.

## Downloads

- `androidApp-full-arm64-v8a-release.apk`
- `androidApp-full-armeabi-v7a-release.apk`
- `androidApp-full-x86-release.apk`
- `androidApp-full-x86_64-release.apk`
- `StreamBridge-0.1.21-iOS-unsigned.ipa`
- `checksums.sha256`
- `checksums-ios.sha256`

**Your Media, Your Way.** Made with ❤️ by Saif.
