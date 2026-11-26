# Dedicated media foundation — remote CI evidence

Date: 2026-11-24. Branch: `arena/b0aacb8e-mybridge`.

This report supersedes the local-only compilation/test status in `DEDICATED_MEDIA_VERIFICATION.md`. Runtime/device checks are still outstanding.

## Workflow selection and scope

Inspected the existing workflows before making CI changes:

- `build.yml`: JDK 21, Android SDK API 37 minor 1, build tools 36.0.0, CMake 3.22.1 and NDK 28.2.13676358; broad test/build workflow, not dispatched.
- `ios-simulator-tests.yml`: JDK 17, runner label `xcode-27`, Xcode 27.1, full-distribution pinned native dependencies. It runs the broader Kotlin simulator suite but does not build the changed Swift host.
- `pr-full-debug-build.yml`: existing manually dispatchable workflow. Added an opt-in `dedicated_media_verification` mode with Android/iOS platform selection, leaving its normal behavior intact. In verification mode the original APK job is skipped.

Workflow name remains **PR Full Debug Build**; the verification jobs build no APK/IPA, perform no archive/export/signing, and publish only diagnostic logs and test reports. The Apple Swift step uses a Debug arm64 simulator build with `CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO`. No release pipeline was dispatched.

Runner inventory listing returned HTTP 403 for this GitHub integration. Actual dispatched jobs nevertheless acquired `ubuntu-latest` and `xcode-27` runners, so runner availability was established by execution, not guessed from labels.

## Android — compilation and all eight tests passed

Run: https://github.com/sascio/MyBridge/actions/runs/45466179114

Job: https://github.com/sascio/MyBridge/actions/runs/45466179114/job/138701583851

Exact tested commit: `83e01650f544553d663790043c4dce7c89f3e3d2`.

- `:composeApp:compileAndroidMain -Pnuvio.android.distribution=playstore`: **success**.
- Focused host test task was discovered from the real Gradle task list rather than assumed.
- Only `MediaEnvironmentStateTest` and `MediaSwipeDestinationTest` were selected.
- A separate successful CI step parsed JUnit XML and required **five transition tests plus three swipe-policy tests**, with no failure, error or skipped testcase. **Eight tests passed.**
- Android job conclusion: **success**, duration 5m59s. The overall combined run was failure because its Apple job failed; this does not negate the Android job result.
- Later production changes are limited to the iOS actual host. Shared source, Android source and the test sources are unchanged from the successful Android SHA, checked with Git diff. Android was not rerun unnecessarily after iOS-only fixes.

## Apple — framework passed; unsigned Swift simulator build failed

Current run: https://github.com/sascio/MyBridge/actions/runs/45472519377

Job: https://github.com/sascio/MyBridge/actions/runs/45472519377/job/138724004373

Exact tested commit: `9a2345c463409dcc209ea1716733e69b7d3b1f64`.

The Debug arm64 simulator framework compile/link step completed successfully (19:45:24–20:13:05 UTC). The unsigned Swift host step then failed with exit code **65** (20:13:05–20:27:32 UTC). The overall Apple job/run conclusion is **failure**.

Actual Swift diagnostic:

```text
iosApp/iosApp/Player/MetalLayer.swift:256:18: error:
value of type 'any CAMetalDrawable' has no member 'addPresentedHandler'
```

The failing file is identical to baseline `b3290f6` (verified by Git diff and byte comparison). This is an unchanged player-code simulator-build blocker, not a newly reported navigation-source error. The file already guards a different addPresentedHandler call for targetEnvironment(simulator), but the call at line 256 is unguarded. No player code or playback ownership was modified as part of this navigation verification task.

**Swift host / end-to-end Kotlin-to-Swift interoperability is not verified.** The framework successfully links, but the complete host does not build. No unsigned app, IPA, signed device build or iOS runtime success is claimed.

Commands configured:

```sh
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 --console=plain --stacktrace

xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$RUNNER_TEMP/dedicated-media-derived-data" \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

The existing `prepare-ios-dependencies.sh` prepares pinned Apple dependencies. This job uses the same `NUVIO_IOS_DISTRIBUTION=full`, Xcode and Java settings as the repository's supported simulator workflow. It does not build all variants.

## Actual native compiler errors and fixes

Compiler diagnostics were obtained from GitHub Checks annotations:

1. `MediaEnvironmentHost.ios.kt:12:23` and `:24:49`: `Unresolved reference 'UIUserInterfaceStyleDark'`.
   - Fixed by importing `UIUserInterfaceStyle` and using `UIUserInterfaceStyle.UIUserInterfaceStyleDark`.
2. `MediaEnvironmentHost.ios.kt:26:20`: `Unresolved reference 'modalInPresentation'`.
   - An intermediate boolean-name spelling (`isModalInPresentation`) also failed compilation.
   - Corrected by explicitly importing the UIKit category extension `platform.UIKit.modalInPresentation` and using `controller.modalInPresentation = true`.

The final Kotlin/Native framework pass confirms both new-host reference fixes compile. The remaining actual compiler failure is the Swift simulator/player error above; it is not suppressed or reported as passing.

No common, Android, playback or download implementation was changed to accommodate the CI environment. No versions, secrets, signing configuration or production identifiers were changed.

### Retry/evidence ledger

| Run | Exact commit | Purpose / observed result |
|---|---|---|
| [45466179114](https://github.com/sascio/MyBridge/actions/runs/45466179114) | `83e01650f544553d663790043c4dce7c89f3e3d2` | Both platforms: Android compile and eight tests passed; Apple framework step failed; Swift skipped. |
| [45467445872](https://github.com/sascio/MyBridge/actions/runs/45467445872) | `f38aaffe998c31228e06b9e2abce964b895846cc` | iOS-only diagnostic retry. Framework failed. Actions default bash errexit prevented the initial diagnostic handler from running. |
| [45468834837](https://github.com/sascio/MyBridge/actions/runs/45468834837) | `4e9559c9fa8056ed5c53d7946f76a70a5754f61c` | iOS-only retry fixing diagnostic capture; exposed the enum-reference compiler errors. |
| [45470081594](https://github.com/sascio/MyBridge/actions/runs/45470081594) | `f7c3278b84a3fef76de6e2fe4007cb4fd3ea98f6` | iOS-only enum fix; compiler then reported the missing modal property reference. |
| [45471310731](https://github.com/sascio/MyBridge/actions/runs/45471310731) | `385236ca385e24d4d40ea54851e1155aa0d904af` | iOS-only boolean-name attempt; compiler rejected it. |
| [45472519377](https://github.com/sascio/MyBridge/actions/runs/45472519377) | `9a2345c463409dcc209ea1716733e69b7d3b1f64` | iOS-only category-extension import fix; framework linked successfully; unsigned Swift host failed in unchanged MetalLayer.swift:256 (exit 65). |

All these runs used the same existing workflow; no duplicate workflow files were created. Android ran once. Later retries skipped Android and the original APK job. Automatic push CI was skipped in the branch commits; only the explicitly requested verification mode was dispatched.

GitHub's external log-storage endpoint returned EOF from this sandbox, so diagnostic annotations were added to the workflow. They preserve compiler exit status and expose bounded error excerpts through the accessible Checks API. Logs and test reports were also uploaded as seven-day verification artifacts. They are not APK/IPA release artifacts.

## Current compilation blocker

Before claiming a complete iOS host build, the repository's simulator-specific Metal drawable presentation/capture behavior needs a separately scoped correction and another unsigned simulator build, or a supported unsigned device-target verification must be performed on the current source. Neither was performed here. This report does not assert a baseline device build is broken merely because its simulator build fails.

## Remaining runtime/device-only verification

The eight executed tests exercise pure transition and swipe-direction policy, not Compose/UIKit interaction. Regardless of build outcome, these remain unverified:

- Android Back/Exit and iOS Exit/controller lifetime under actual event dispatch.
- Full-screen coverage, native chrome hiding, system bars and safe areas.
- Narrow phones, tablets/iPad, orientation and accessibility font sizes.
- Activity recreation, background/foreground, rapid entry/Exit and duplicate presentation races.
- Native root-only Music visibility and presenter failure paths.
- Player seek gestures, carousels, nested scrolling, multi-touch and native OS-edge arbitration.
- TalkBack/VoiceOver focus behavior.
- Exact origin-state restoration during pending deep links, stream-resolution callbacks or profile/session changes. The existing asynchronous origin-mutation limitation has not been fixed or runtime-tested.
- iOS device `iosArm64` build and physical-device behavior; the focused Apple job targets the arm64 simulator only.

No YouTube/Music services were integrated and no runtime success is inferred from compilation or pure tests.

### Baseline control now completed

A later baseline-only comparison reproduced the same Swift error at the same line under the matching Xcode 27.1 / simulator SDK 27.1 configuration. Baseline run: https://github.com/sascio/MyBridge/actions/runs/45529550173 . Both baseline and feature link their Kotlin/Native framework and fail the unsigned Swift simulator host. This confirms a baseline simulator/toolchain compatibility blocker rather than a failure introduced by dedicated-media navigation. See `IOS_METAL_BASELINE_COMPARISON.md` for exact source/workflow SHAs, actual Xcode/Swift versions, SDK probes and the limits of this conclusion.
