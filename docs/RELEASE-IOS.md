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
| Marketing version | `0.1.20` (`iosApp/Configuration/Version.xcconfig`) |
| Build number | `120` |
| Xcode project | `iosApp/iosApp.xcodeproj`, scheme `iosApp` |
| Product | `StreamBridge.app` |
| Distribution | `full` (`NUVIO_IOS_DISTRIBUTION=full`; CloudStream DEX execution is not supported on iOS) |

The StreamBridge version comes from
[`streambridge.version.properties`](../streambridge.version.properties)
(`STREAMBRIDGE_VERSION_NAME`). `scripts/build-ios-ipa.sh` fails the build if the
packaged application does not report that version, is not named `StreamBridge`,
or does not carry a `com.streambridge.app*` bundle identifier.

0.1.20 is a preparation target, not a published release; artifact paths below are expected outputs, not verified downloads.

## Building locally

```bash
./scripts/prepare-ios-dependencies.sh   # MPVKit local package + Nuvio Engine XCFramework
./scripts/build-ios-ipa.sh              # unsigned sideload build
```

Output: `build/ios-ipa/streambridge-0.1.20-full-release.ipa`.

Environment:

| Variable | Meaning |
| --- | --- |
| `IOS_CONFIGURATION` | `Release` (default) or `Debug` |
| `IOS_IPA_OUTPUT_DIR` | Output directory (default `build/ios-ipa`) |
| `IOS_DERIVED_DATA_PATH` | DerivedData directory |
| `NUVIO_ENGINE_ROOT` | Where `NuvioEngine.xcframework` is expected (default `../nuvio-engine`) |
| Official MPVKit source | `https://github.com/NuvioMedia/MPVKit` at `da4b7da256a5d67dee74f576762ceb9067fc7a71` (Official Nuvio 0.6.12-beta gitlink) |
| Enhanced MPVKit overlay | `https://github.com/luqmanfadlli/MPVKit` revision `359a885342d4eeda757f8e1d2c24fb01fe6d39c2`; the reviewed three-file delta is applied from [`scripts/patches/mpvkit-enhanced-0.6.13.patch`](../scripts/patches/mpvkit-enhanced-0.6.13.patch), SHA-256 `cc058085ecfaf65459b71c1d0b3e197cf4f3424a43680a12ebb33f178e0c1c36` |
| `NUVIO_GRADLE_JVMARGS` | Gradle daemon heap for the iOS build (default `-Xmx12288M -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=2048m`) |
| `NUVIO_KOTLIN_DAEMON_JVMARGS` | Kotlin compile daemon heap (default `-Xmx8192M`) |
| `NUVIO_KOTLIN_NATIVE_JVMARGS` | Kotlin/Native compiler heap (default `-Xmx12288M`) |

`scripts/prepare-ios-dependencies.sh` downloads the pinned **Nuvio Engine
0.1.6** Apple XCFramework (checksum verified) and prepares the MPVKit local Swift
package referenced by `iosApp/iosApp.xcodeproj` as `../MPVKit`. MPVKit starts at
the exact Official Nuvio 0.6.12-beta gitlink, `da4b7da256a5d67dee74f576762ceb9067fc7a71`
(repository `https://github.com/NuvioMedia/MPVKit`). It then applies the reviewed
delta from Enhanced MPVKit `359a885342d4eeda757f8e1d2c24fb01fe6d39c2`
(repository `https://github.com/luqmanfadlli/MPVKit`): changed binary URLs/checksums
for the `nuvio.7` Libmpv/FFmpeg artifacts, two audio filters, and the extended PiP
patch that serializes Dolby Vision RPU metadata. The Official revision already
contains the PiP bridge and the subsequent VideoToolbox-recovery and host-audio
session patches; those are retained unchanged. A setup marker records both pins,
and the script refuses an unrelated or stale checkout rather than silently using
it. `MPVKit/` remains a local ignored build dependency, not a vendored application
submodule. The Swift package's checksums make SwiftPM validate every downloaded
binary; successful CI dependency resolution is still required before declaring
the archive downloaded and build-verified.

`NuvioEngine` is kept only in the path and file names of the upstream engine
artifact. The iOS application itself is named and versioned as
StreamBridge (`StreamBridge.app`, `com.streambridge.app`, 0.1.20), and
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

The iOS IPA, simulator-test, and production-draft workflows use the `xcode-27` GitHub runner with
Xcode 27.1. The version-keyed Kotlin/Native cache is isolated from builds made with older Xcode
SDKs. Xcode 27.1 is required to compile the fold and vertical-bar integrations in
`ContentView.swift`; those calls are guarded by iOS 27.1 availability, and the deployment target
remains iOS 16.1. IPA and simulator jobs run independently; the simulator job restores the native
cache read-only so concurrent cache writers cannot race. The previous Xcode 26.6 IPA step measured
118.9 minutes; the new pipeline's duration has not yet been measured.

| Workflow | Purpose |
| --- | --- |
| [`.github/workflows/ios-release.yml`](../.github/workflows/ios-release.yml) | Build the IPA on demand (`workflow_dispatch`) and on pushes that touch iOS paths. Always unsigned unless Apple secrets exist; reports the IPA composition. |
| [`.github/workflows/release-draft.yml`](../.github/workflows/release-draft.yml) | Draft production release. Requires verified per-ABI Android APKs, an independently inspected unsigned iOS IPA and simulator tests. |
| [`.github/workflows/update-store-source.yml`](../.github/workflows/update-store-source.yml) | Runs when a release is published. Verifies the published IPA and commits the regenerated `store.json`. |

Artifacts and release assets:

- `StreamBridge-iOS-unsigned-IPA` (or `-signed-`, when signing is configured) —
  validation artifact from `ios-release.yml`.
- `StreamBridge-iOS-build-log` — the captured `xcodebuild` log, uploaded when the
  build fails. The workflow also republishes the failing lines as run
  annotations (`.github/report-ios-failure.sh`) and comments them on the pull
  request, because the raw Actions log is not always downloadable.
- `StreamBridge-ios-unsigned-ipa` — the independently verified production artifact;
  the draft attaches `StreamBridge-<version>-iOS-unsigned.ipa` and `checksums-ios.sha256`.

## Sideload source (`store.json`)

`store.json` in the repository root is an [AltStore](https://altstore.io) /
[SideStore](https://sidestore.io) source. Add

```
https://raw.githubusercontent.com/sascio/MyBridge/main/store.json
```

as a source in either app and StreamBridge appears as an installable,
updatable application.

The file is generated from an actual public release, never guessed in advance.
The updater downloads the sole IPA, checks its size and GitHub SHA-256, and
rejects signed or misidentified artifacts before creating an entry.

## Production distribution

The release workflow builds an unsigned IPA for AltStore and SideStore. These
clients re-sign it at installation using the user's Apple ID. No Apple
certificate or provisioning profile is required in the release workflow.
The separately downloaded IPA is checked for its checksum, archive integrity,
app and widget identity, version, executables and unsigned state.

## Version bumps

1. Update `STREAMBRIDGE_VERSION_NAME` / `STREAMBRIDGE_VERSION_CODE` in
   `streambridge.version.properties`.
2. Mirror `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` in
   `iosApp/Configuration/Version.xcconfig`.
3. Run `ios-release.yml`; it fails if the packaged app's version is not the
   StreamBridge version.

Do not set the iOS version to NuvioMobile's `MARKETING_VERSION` (`0.5.5` at this
pin). StreamBridge never ships Nuvio's version number as its own.

### Non-publishing production preflight

The production release workflow can be dispatched with `preflight=true` on the
candidate branch. It checks the v0.1.20 source identity without requiring a tag,
builds signed Android APKs and an unsigned IPA, then downloads each platform’s
artifacts into independent jobs to verify checksums, identity and signing state. Preflight never
creates a tag, draft or public release; the normal release path still requires
the exact verified tag. These checks do not replace device or service testing.
