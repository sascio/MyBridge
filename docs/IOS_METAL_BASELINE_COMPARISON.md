# MetalLayer simulator build blocker — baseline comparison

## Conclusion

**Confirmed baseline simulator/toolchain compatibility blocker, not a failure introduced by the dedicated-media navigation changes.**

The exact baseline and feature source commits both compile/link their Kotlin/Native simulator framework, then fail the same unsigned Swift simulator host build at the same unchanged line:

```text
MetalLayer.swift:256:18: error: value of type 'any CAMetalDrawable' has no member 'addPresentedHandler'
```

Both `xcodebuild` commands exit **65**. Neither iOS application build is successful. Framework linkage alone is not full Swift-host interoperability verification.

This comparison establishes the problem exists in baseline source under the tested current simulator SDK. It does **not** establish when it first appeared, whether an older Xcode accepted it, or whether an Xcode update originally exposed it. No older-toolchain bisect was run.

## Exact source commits and Actions evidence

Workflow: **PR Full Debug Build**, opt-in dedicated-media iOS verification mode. The normal Android APK job and Android verification job were skipped in the baseline comparison.

| Case | Application source commit | Run | Kotlin/Native framework | Unsigned Swift simulator host |
|---|---|---|---|---|
| Baseline | `b3290f655690f19ac6d35049ff9b7e415e4575a6` | [45529550173](https://github.com/sascio/MyBridge/actions/runs/45529550173) | Passed | Failed, exit 65, MetalLayer.swift:256:18 |
| Dedicated-media feature | `9a2345c463409dcc209ea1716733e69b7d3b1f64` | [45472519377](https://github.com/sascio/MyBridge/actions/runs/45472519377) | Passed | Failed, exit 65, MetalLayer.swift:256:18 |

Jobs:

- Baseline: https://github.com/sascio/MyBridge/actions/runs/45529550173/job/138921169403
- Feature: https://github.com/sascio/MyBridge/actions/runs/45472519377/job/138724004373

The baseline run's **workflow/harness commit** is `c7d5579e2b37ecf915ab81f13f75513a2c4b4786`, not its application source. The opt-in `media_baseline=true` input checks out the fixed baseline SHA and asserts `git rev-parse HEAD` matches it. The source/toolchain annotation explicitly records both SHAs. The newer workflow does not patch baseline source.

Recorded GitHub job timestamps (UTC):

- Baseline: `2026-11-25T01:58:09Z`–`2026-11-25T02:37:28Z`.
- Feature: `2026-11-24T19:44:34Z`–`2026-11-24T20:27:47Z`.

Only **one new baseline run** was dispatched for this comparison. The existing feature result was reused rather than rerunning it unnecessarily.

## Runner and toolchain verification

Both jobs use the existing `xcode-27` runner pool and:

```text
DEVELOPER_DIR=/Applications/Xcode_27.1.app/Contents/Developer
NUVIO_IOS_DISTRIBUTION=full
Java: Temurin 17
Architecture: arm64
Configuration: Debug
Destination: generic/platform=iOS Simulator
Signing: disabled
```

The baseline runner reported through the GitHub Checks API:

```text
Runner: GitHub Actions 1000002025 / ARM64
Xcode 27.1
Build version 27A9275
Simulator SDK: 27.1
Apple Swift version 6.4
swiftlang-6.4.0.34.1
clang-2100.3.34.1
```

The feature job used runner `GitHub Actions 1000002022` in the same pool. These are separate ephemeral runner instances, not a claim that the same VM was reused.

The feature and baseline compiler diagnostics both record:

- SDK path `/Applications/Xcode_27.1.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator27.1.sdk`.
- `-target-sdk-version 27.1` and `-target-sdk-name iphonesimulator27.1`.
- Application target `arm64-apple-ios16.1-simulator`.
- Swift language mode `5`.
- SDK-stat-cache identity containing `iphonesimulator27.1-24A94232`.

The baseline's actual `xcodebuild -version` was captured explicitly. The older feature workflow printed it in its logs but did not publish that version command as a Checks annotation; its exact Xcode build number is not independently quoted here. Its configured Xcode installation and recorded compiler SDK identity match the baseline comparison.

## Exact build configuration

Both source revisions used the same commands:

```sh
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 --console=plain --stacktrace

xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$RUNNER_TEMP/dedicated-media-derived-data" \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  build
```

The existing repository dependency-preparation script and iOS memory profile were used unchanged. Git comparison found no differences between baseline and the tested feature in:

- `iosApp/iosApp.xcodeproj/project.pbxproj`
- `composeApp/build.gradle.kts`
- `gradle/libs.versions.toml`
- `gradle/wrapper/gradle-wrapper.properties`
- `scripts/prepare-ios-dependencies.sh`
- `iosApp/iosApp/Player/MetalLayer.swift`

No archive/export task, IPA generation, production signing or release pipeline was run.

## Implementation and SDK/API findings

`MetalLayer.swift` contains two relevant paths:

1. `attachPresentationObserver` already uses `#if targetEnvironment(simulator)` to avoid its drawable presentation callback on the simulator.
2. The drawable-capture path in `nextDrawable()` calls `drawable.addPresentedHandler` unconditionally at line 256 after the deferred-capture early return. Whether that path would execute at runtime cannot prevent Swift from typechecking it.

The complete file is byte-identical in baseline, tested feature and the current workspace. SHA-256:

```text
df4066263f63bd28e94151108ce2d5448a4312049c235060df870f09d297ae29
```

The baseline runner inspected its installed simulator `Metal.framework/Headers/MTLDrawable.h` and typechecked this isolated, temporary source with the actual SDKs:

```swift
import Metal
import QuartzCore
func verifyDrawableAPI(_ drawable: any CAMetalDrawable) {
    drawable.addPresentedHandler { _ in }
}
```

Targets and actual results:

| Probe | Target | Result |
|---|---|---|
| Simulator SDK | `arm64-apple-ios16.1-simulator` | Exit **1**, the same missing `addPresentedHandler` diagnostic |
| Device SDK | `arm64-apple-ios16.1` | Exit **0**, API typecheck succeeded |

Thus the tested simulator SDK does not expose this member to Swift for that target, while its device SDK accepts the same source. No application code, Compose framework, MediaServiceCore or new navigation dependency is needed to reproduce the API failure.

The device probe is **not** a device application build, link, signing result or runtime playback test. No historical OS-version availability cutoff or SDK removal date is inferred from these probes.

## Changes made for this task

- Extended the existing verification workflow with an opt-in exact-baseline checkout and source assertion.
- Added runtime Xcode/Swift/SDK identity notices and isolated SDK API probes. Probe exit codes are explicitly reported; they do not stand in for the full application build result.
- Added this evidence report.
- **No changes to MetalLayer.swift, playback behavior/ownership, navigation source, dependencies, versions, application identifiers or secrets.**

Compiler diagnostics and toolchain notices are accessible through the linked job's Checks annotations. The workflow also uploads diagnostic logs as verification artifacts; external log-download access from this sandbox remains restricted.

## Recommended next action

Treat this as a separately scoped simulator drawable-capture compatibility issue. Review the simulator's intended capture/PiP behavior before selecting a fallback or compile-time target guard, preserving the device callback path and existing player ownership. Do not invent a successful presentation notification or change navigation to hide the build failure.

After any approved compatibility correction, rerun the same unsigned simulator host compilation and then the navigation runtime checks. If historical toolchain attribution is required, run an explicit older-Xcode/SDK control before claiming an Xcode-version regression.

**The iOS application build remains failed/unverified. The comparison does not justify changing the dedicated-media navigation implementation.**
