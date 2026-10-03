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
./scripts/build-ios-ipa.sh              # the unsigned IPA that is published
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
| `NUVIO_GRADLE_JVMARGS` | Gradle daemon heap for the iOS build (default `-Xmx12288M -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=2048m`) |
| `NUVIO_KOTLIN_DAEMON_JVMARGS` | Kotlin compile daemon heap (default `-Xmx8192M`) |
| `NUVIO_KOTLIN_NATIVE_JVMARGS` | Kotlin/Native compiler heap (default `-Xmx12288M`) |

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

## Memory

Kotlin/Native compiles and links inside the Gradle daemon, so the daemon heap is
what decides whether `linkReleaseFrameworkIosArm64` fits. StreamBridge's
`gradle.properties` keeps the Android profile (`-Xmx6144M`, `-Xmx4096M` for the
Kotlin daemon); with it the release link died with
`java.lang.OutOfMemoryError: Java heap space`.

`scripts/build-ios-ipa.sh` therefore writes a small, marker-tagged profile into
`GRADLE_USER_HOME/gradle.properties` (a file Gradle gives precedence over the
project one) before it starts `xcodebuild`, using the heaps upstream NuvioMobile
ships in its own `gradle.properties`: `org.gradle.jvmargs=-Xmx12288M`,
`kotlin.daemon.jvmargs=-Xmx8192M`, `kotlin.native.jvmArgs=-Xmx12288M`. The values
are also passed to that build as Gradle properties, and can be overridden with
the three variables in the table above. Android builds never read this profile:
they use the project's `gradle.properties` unchanged.

## GitHub Actions

| Workflow | Purpose |
| --- | --- |
| [`.github/workflows/ios-release.yml`](../.github/workflows/ios-release.yml) | Build the IPA on demand (`workflow_dispatch`) and on pushes that touch iOS paths. Always unsigned unless Apple secrets exist; reports the IPA composition. |
| [`.github/workflows/release-draft.yml`](../.github/workflows/release-draft.yml) | Draft production release. Attaches the per-ABI Android APKs and the unsigned iOS IPA. |
| [`.github/workflows/update-store-source.yml`](../.github/workflows/update-store-source.yml) | Runs when a release is published. Verifies the published IPA and commits the regenerated `store.json`. |

Artifacts and release assets:

- `StreamBridge-iOS-unsigned-IPA` (or `-signed-`, when signing is configured) —
  validation artifact from `ios-release.yml`.
- `StreamBridge-iOS-build-log` — the captured `xcodebuild` log, uploaded when the
  build fails. The workflow also republishes the failing lines as run
  annotations (`.github/report-ios-failure.sh`) and comments them on the pull
  request, because the raw Actions log is not always downloadable.
- `StreamBridge-ios-unsigned-ipa` — the IPA the draft release attaches; it is
  re-uploaded as `StreamBridge-<version>-iOS-unsigned.ipa`.

## Sideload source (`store.json`)

`store.json` in the repository root is an [AltStore](https://altstore.io) /
[SideStore](https://sidestore.io) source. Add

```
https://raw.githubusercontent.com/sascio/MyBridge/main/store.json
```

as a source in either app and StreamBridge appears as an installable,
updatable application.

The file is generated, never hand-edited for a release:
`.github/workflows/update-store-source.yml` fires on `release: published`, and
`scripts/update-store-source.py` then

- requires the release to be public and to carry **exactly one** `.ipa` asset,
- downloads that IPA and checks its size (and SHA-256 digest, when the API
  exposes one) against the release metadata,
- refuses to publish a signed IPA (any `_CodeSignature/` entry), a bundle that
  is not `com.streambridge.app`, or a version that does not match the release,
- reads `CFBundleShortVersionString`, `CFBundleVersion` and `MinimumOSVersion`
  from the app, collects its `NS*UsageDescription` strings, and prepends a
  version entry with the real download URL, size and SHA-256,
- commits the result to the default branch as `github-actions[bot]`.

The iOS app is **not** uploaded to the App Store, and no Apple credentials are
needed anywhere in this flow.

## Signing

StreamBridge's iOS distribution is **unsigned by design**. The published IPA is
re-signed on the user's own device by AltStore/SideStore with the user's own
Apple ID, so this repository needs no Apple Developer account, certificate,
provisioning profile, notarisation, or App Store Connect access. Nothing in this
repository contains a certificate, provisioning profile, password, or team
identifier.

`release-draft.yml` always builds unsigned and fails if the application comes out
signed. `build-ios-ipa.sh` additionally supports producing a **signed** IPA for
local use or for a future distribution route; that path is off unless the
following secrets are configured, and it is required only by
`ios-release.yml`'s optional signed mode.

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

## Installing and re-signing the unsigned IPA

The unsigned IPA is the artifact users install. AltStore/SideStore does the
re-signing on the device; the manual equivalent is:

```bash
unzip -q build/ios-ipa/streambridge-0.1.08-full-release.ipa -d resigned
codesign --force --deep --sign "Apple Development" \
  --entitlements StreamBridge.entitlements \
  --keychain "$KEYCHAIN" "resigned/Payload/StreamBridge.app"
# then repackage the Payload directory as an .ipa
```

Re-signing with a free Apple ID works but lasts 7 days; AltStore/SideStore
refresh it before expiry. Alternatively run the signed path above:

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
