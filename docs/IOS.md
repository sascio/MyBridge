# StreamBridge iOS builds, installation and signing

## What is implemented

The `StreamBridge` Xcode target combines the verified 0.5.5-beta Swift host and
player implementation with the same Kotlin/Compose application used on Android.
This is not an Android APK translated to iOS. The native host supplies UIKit/
SwiftUI navigation, audio sessions/Now Playing, mpv rendering/PiP, orientation,
and the download Live Activity/widget. The shared layer supplies catalogs,
addons, metadata, profiles, library, tracking and settings.

| Component | StreamBridge identity |
| --- | --- |
| Scheme / target / product | `StreamBridge` / `StreamBridge.app` |
| Main bundle ID (Debug and Release) | `com.streambridge.app` |
| Widget bundle ID | `com.streambridge.app.DownloadsWidgetExtension` |
| Display names | `StreamBridge`, `StreamBridge Downloads` |
| Shared framework bundle ID | `com.streambridge.app.shared` |
| Main URL scheme | `streambridge` |
| Compatibility URL schemes | `nuvio` (existing configured OAuth/addon callbacks), `stremio` |
| Primary icon | The opaque 1024×1024 StreamBridge launcher artwork |
| Version | Marketing `0.5.5`; build `108`; shared/release label `0.5.5-beta` |

Upstream Kotlin packages, native protocol names, notification names and stored
keys are retained where needed for binding/data compatibility. They do not set
the app’s product name, bundle IDs or Apple signing team. `iosApp/` remains a
technical source-directory/project name, not the displayed application name.

## Environment

- macOS with **Xcode 26.6**, selected via `xcode-select` or `DEVELOPER_DIR`.
- JDK 17; the checked-in Gradle 9.4.1 wrapper.
- Kotlin 2.4.10 / Compose 1.12.0, as pinned in the version catalog.
- Python 3.12 recommended, Bash, curl, unzip and Apple command-line tools.
- Network access to Maven/Google repositories and the upstream Swift-package
  binary archives. No CocoaPods installation is required: MPVKit is a local
  pinned Swift package/submodule and Kotlin is built by the Xcode shell phase.

Use `git clone --recurse-submodules`, or let the bootstrap initialize MPVKit at
`bb1d0250ddcfa9d220761fcdad6011ac9fecf15e`. Do not update its moving branch.
`prepare-ios-dependencies.sh` fetches engine 0.1.2 with the upstream verified
SHA-256 `ed35576d962930d3207b2725fa737f71ef080e35dbfa2f0bf6f21ab1719b7029`.
Binaries go in ignored `.cache/ios/nuvio-engine/`, not Git. `NUVIO_ENGINE_ROOT`
can override that root; Gradle and the bootstrap use the same location.

## Unsigned validation

```sh
python3 .github/streambridge_release.py
./scripts/build-ios-archive.sh unsigned
```

This resolves Swift packages, invokes the Gradle/Kotlin framework integration,
archives a device **Release** build without code signing, validates arm64,
app/widget identity and version metadata, and generates:

- `build/ios/StreamBridge-0.5.5-beta.xcarchive`
- `build/artifacts/ios/StreamBridge-0.5.5-beta-unsigned.xcarchive.zip`
- `build/artifacts/ios/StreamBridge-0.5.5-beta-unsigned.ipa`
- checksums

`.github/workflows/build.yml` runs this on `macos-26`. It never uses Apple
credentials and never publishes the unsigned IPA as a release. A Linux static
check does not substitute for this Xcode build. See the validation report for
actual completed build results.

## Signed release model

The verified upstream/Enhanced mobile workflows build **unsigned sideload IPAs**.
StreamBridge keeps that validation mechanism but adds explicit, secure archive/
export signing. No upstream Apple team ID is reused and no credentials are
invented. `.github/workflows/release-draft.yml` has a dedicated signing stage
that fails clearly if credentials are missing or inconsistent.

Required GitHub Secrets:

| Secret | Required value |
| --- | --- |
| `IOS_DISTRIBUTION_P12_BASE64` | Base64 Apple Distribution certificate **and private key** exported as P12 |
| `IOS_DISTRIBUTION_P12_PASSWORD` | Password protecting that P12 |
| `IOS_APP_PROVISIONING_PROFILE_BASE64` | Distribution profile explicitly for `com.streambridge.app` |
| `IOS_WIDGET_PROVISIONING_PROFILE_BASE64` | Distribution profile explicitly for `com.streambridge.app.DownloadsWidgetExtension` |
| `IOS_TEAM_ID` | Your own ten-character Apple Developer team ID |
| `NUVIO_LOCAL_PROPERTIES_BASE64` | Optional shared runtime API/OAuth settings; not a signing requirement |

Do not put any of these values, P12s, private keys or provisioning profiles in
source control, issue comments, logs or chat. The current session cannot inspect
secret metadata (GitHub returns 403); configured credential availability must be
established by an authorized signing workflow, not assumed.

The signing helper checks expiration, exact app/widget IDs, matching team,
distribution (not development) entitlements, profile export model and a shared
usable Apple Distribution identity from the P12. It imports into a temporary
keychain, sets the two target-specific provisioning UUIDs, archives and exports
with Xcode, then verifies the exported app/widget signatures and versions. It
removes private files/profiles and restores the original keychain list even on
failure. No keychain passwords or decoded secret values are printed.

### Distribution choices and installation

- **`release-testing` (default):** Ad Hoc distribution. Register the recipient
  device UDIDs and create both distribution profiles for those devices. Install
  the signed `StreamBridge-0.5.5-beta.ipa` using Apple Configurator or an
  appropriate managed-device installation tool. Unregistered devices cannot
  install this IPA. Embedded Ad Hoc profiles contain the registered UDIDs;
  account for that when distributing a public release.
- **`app-store-connect`:** Export using App Store profiles. This workflow does not
  upload to App Store Connect/TestFlight; a maintainer must do that separately.
  The exported IPA is not a general-purpose sideload download.
- **`enterprise`:** Requires a legitimate Enterprise team/profile and authorized
  internal distribution. It is not a workaround for missing signing credentials.
- **Unsigned developer IPA:** Requires re-signing with your own Apple account
  and both bundle/extension entitlements using your chosen developer tooling.
  It is not a signed StreamBridge release and is not directly installable.

A signed workflow produces `StreamBridge-0.5.5-beta.ipa` and a matching archive
ZIP. Only the signed production artifact set can enter a coordinated GitHub
draft. Signing success must not be inferred from unsigned archive success.

## Local Xcode development

Open `iosApp/iosApp.xcodeproj` and select scheme **StreamBridge**. Run the bootstrap
before opening/building. Select your own team and the matching app/extension
profiles. An ignored `iosApp/Configuration/Signing.local.xcconfig` can set the
`STREAMBRIDGE_TEAM_ID`, `STREAMBRIDGE_CODE_SIGN_STYLE`,
`STREAMBRIDGE_CODE_SIGN_IDENTITY`, `STREAMBRIDGE_APP_PROFILE_UUID` and
`STREAMBRIDGE_WIDGET_PROFILE_UUID` values. Do not check that file in.

The root `Version.xcconfig` is generated from canonical StreamBridge metadata;
update it with `python3 .github/streambridge_release.py --write-xcconfig` rather
than copying upstream build numbers. The numeric iOS marketing version is
required by Apple; the beta label is retained separately in Info.plist, About,
archive and asset names. Both targets inherit the same build number.
