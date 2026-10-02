# StreamBridge iOS releases

StreamBridge ships an iOS application from the same Kotlin Multiplatform core
as the Android app. iOS releases were added in **StreamBridge 0.1.08**,
synchronised from official NuvioMobile **0.5.5-beta** and NuvioMobile-Enhanced
**0.5.5-beta**.

| Item | Value |
| --- | --- |
| App name | StreamBridge |
| App bundle identifier | `com.streambridge.app` |
| Widget bundle identifier | `com.streambridge.app.DownloadsWidgetExtension` |
| Marketing version | `0.1.08` (`iosApp/Configuration/Version.xcconfig`) |
| Build number | `108` |
| Xcode project | `iosApp/iosApp.xcodeproj`, scheme `iosApp` |
| Product | `StreamBridge.app` |
| Distribution | `full` (`NUVIO_IOS_DISTRIBUTION=full`, plugins/CloudStream-capable core) |

The StreamBridge version comes from
[`streambridge.version.properties`](../streambridge.version.properties)
(`STREAMBRIDGE_VERSION_NAME`). `scripts/build-ios-ipa.sh` fails the build if the
packaged application does not report that version, is not named `StreamBridge`,
or does not carry a `com.streambridge.app*` bundle identifier.

## Building locally

```bash
./scripts/prepare-ios-dependencies.sh   # MPVKit local package + Nuvio Engine XCFramework
./scripts/build-ios-ipa.sh              # unsigned IPA for validation
```

Output: `build/ios-ipa/streambridge-0.1.08-full-release.ipa`.

Environment:

| Variable | Meaning |
| --- | --- |
| `IOS_CONFIGURATION` | `Release` (default) or `Debug` |
| `IOS_IPA_OUTPUT_DIR` | Output directory (default `build/ios-ipa`) |
| `IOS_DERIVED_DATA_PATH` | DerivedData directory |
| `NUVIO_ENGINE_ROOT` | Where `NuvioEngine.xcframework` is expected (default `../nuvio-engine`) |
| `NUVIO_MPVKIT_REPO` | MPVKit source (default `NuvioMedia/MPVKit`) |
| `NUVIO_MPVKIT_COMMIT` | MPVKit revision (default `d5cf091c`, the revision NuvioMobile-Enhanced 0.5.5-beta records for its submodule) |
| `NUVIO_MPVKIT_REF` | Fetch a branch instead of the pinned revision |

`scripts/prepare-ios-dependencies.sh` downloads the pinned **Nuvio Engine
0.1.2** Apple XCFramework (checksum verified) and, when the working tree does not
provide it, fetches the MPVKit local Swift package that
`iosApp/iosApp.xcodeproj` references as `../MPVKit`. MPVKit is checked out at
`d5cf091c` — the same revision NuvioMobile-Enhanced 0.5.5-beta pins for its own
submodule — so the iOS build always uses the player revision the release was
synced from rather than a moving branch tip. StreamBridge does not vendor the
MPVKit submodule; fetches are ignored by git.

`NuvioEngine` is kept only in the path and file names of the upstream engine
artifact. The iOS application itself is named, signed and versioned as
StreamBridge (`StreamBridge.app`, `com.streambridge.app`, 0.1.08), and
`scripts/build-ios-ipa.sh` fails if the packaged app reports anything else.

## GitHub Actions

| Workflow | Purpose |
| --- | --- |
| [`.github/workflows/ios-release.yml`](../.github/workflows/ios-release.yml) | Build the IPA on demand (`workflow_dispatch`) and on `main` pushes that touch iOS paths. Always runs; signs only when Apple secrets exist. |
| [`.github/workflows/release-draft.yml`](../.github/workflows/release-draft.yml) | Draft production release. Attaches the per-ABI Android APKs and, when signing is configured, the signed iOS IPA. |

Artifacts:

- `StreamBridge-iOS-signed-IPA` / `StreamBridge-iOS-unsigned-IPA` — validation
  artifacts from `ios-release.yml`.
- `StreamBridge-iOS-build-log` — the captured `xcodebuild` log, uploaded when the
  build fails. The workflow also republishes the failing lines as run
  annotations (`.github/report-ios-failure.sh`) and comments them on the pull
  request, because the raw Actions log is not always downloadable.
- `StreamBridge-production-ios-release-ipa` — the signed IPA the draft release
  attaches.
- `StreamBridge-ios-UNSIGNED-ipa-not-a-release` — the unsigned IPA produced
  when signing is unavailable; deliberately not a release asset.

## Signing

Signing is **manual**, with your own Apple Developer team. Nothing in this
repository contains a certificate, provisioning profile, password, or team
identifier. Without signing material the IPA is built unsigned and reported as
unsigned; the draft release then carries **no** IPA and the workflow states the
remaining requirement instead of claiming an iOS release was produced.

Repository secrets (all optional, all required together for a signed IPA):

| Secret | Value |
| --- | --- |
| `STREAMBRIDGE_IOS_TEAM_ID` | Apple Developer team identifier (10 characters) |
| `STREAMBRIDGE_IOS_SIGN_IDENTITY` | e.g. `Apple Distribution` |
| `STREAMBRIDGE_IOS_CERTIFICATE_BASE64` | Base64 of the distribution `.p12` |
| `STREAMBRIDGE_IOS_CERTIFICATE_PASSWORD` | Password of that `.p12` |
| `STREAMBRIDGE_IOS_KEYCHAIN_PASSWORD` | Password for the temporary CI keychain |
| `STREAMBRIDGE_IOS_PROFILE_BASE64` | Base64 of the App Store `.mobileprovision` |

Optional repository variable: `STREAMBRIDGE_IOS_EXPORT_METHOD` (`app-store`,
`ad-hoc`, or `enterprise`; default `app-store`).

Create the base64 values without committing them:

```bash
base64 -w 0 StreamBridgeDistribution.p12
base64 -w 0 StreamBridge_AppStore.mobileprovision
```

Local signed build:

```bash
export STREAMBRIDGE_IOS_SIGNING=1
export STREAMBRIDGE_IOS_TEAM_ID=XXXXXXXXXX
export STREAMBRIDGE_IOS_SIGN_IDENTITY="Apple Distribution"
export STREAMBRIDGE_IOS_PROFILE_PATH=/path/to/StreamBridge_AppStore.mobileprovision
export STREAMBRIDGE_IOS_CERTIFICATE_BASE64="$(base64 -w 0 StreamBridgeDistribution.p12)"
export STREAMBRIDGE_IOS_CERTIFICATE_PASSWORD='…'
export STREAMBRIDGE_IOS_KEYCHAIN_PASSWORD='…'
./scripts/build-ios-ipa.sh
```

The script creates a temporary keychain, imports the certificate, archives
`StreamBridge.app`, exports an IPA, and fails if archiving or export fails. It
never prints a credential.

## Re-signing an unsigned IPA

The unsigned IPA from CI is a validation artifact. Either run the signed path
above, or re-sign the packaged app:

```bash
unzip -q build/ios-ipa/streambridge-0.1.08-full-release.ipa -d resigned
codesign --force --deep --sign "Apple Distribution" \
  --entitlements StreamBridge.entitlements \
  --keychain "$KEYCHAIN" "resigned/Payload/StreamBridge.app"
# then repackage the Payload directory as an .ipa
```

## Version bumps

1. Update `STREAMBRIDGE_VERSION_NAME` / `STREAMBRIDGE_VERSION_CODE` in
   `streambridge.version.properties`.
2. Mirror `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` in
   `iosApp/Configuration/Version.xcconfig`.
3. Run `ios-release.yml`; it fails if the packaged app's version is not the
   StreamBridge version.

Do not set the iOS version to NuvioMobile's `MARKETING_VERSION` (`0.5.5` at this
pin). StreamBridge never ships Nuvio's version number as its own.
