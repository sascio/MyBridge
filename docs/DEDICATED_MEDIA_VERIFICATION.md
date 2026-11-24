# Dedicated media foundation — local verification results

Date: 2026-11-24
Branch: `arena/b0aacb8e-mybridge`
Tracked baseline: `b3290f6` (the foundation is still in the working tree, including new untracked files).

**Result: compilation and runtime verification are BLOCKED, not passed.** No Kotlin, Compose, Kotlin/Native or Swift compiler reached these changes. This verification pass made no production-code changes and introduced no services/features.

## Local environment established

- Host: Linux x86_64, not macOS.
- `java`, `javac`, `kotlinc`, `gradle`, `swift`, `xcodebuild`, `xcrun`, `adb`, `emulator` and `sdkmanager` were not found on PATH.
- `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`, `KONAN_HOME` and `DEVELOPER_DIR` are unset.
- No SDK path is supplied by local.properties. No Android SDK, JDK or Gradle/Kotlin-Native cache was found at the inspected conventional locations (`/opt`, `/usr/lib/jvm`, `/usr/local/android*`, `~/.gradle`, `~/.konan`).
- No `/dev/kvm` or `/dev/bus/usb` is exposed. No emulator/device tooling is available; no device connection or simulator session was established.
- The Gradle wrapper script and wrapper JAR exist. The wrapper is configured for Gradle 9.8.2. This version was not downloaded/resolved.
- Version catalog: Kotlin 2.4.10, AGP 9.4.0, Compose Multiplatform 1.13.0-beta01; Android compile SDK API 37, minor 1, minimum API 24, target API 36. No dependency resolution was possible.
- Existing Android CI selects Temurin JDK 21. Existing iOS simulator CI selects JDK 17 and `/Applications/Xcode_27.1.app/Contents/Developer`. These workflow settings were inspected, not executed.
- The optional `../nuvio-engine/platform/apple/NuvioEngine.xcframework` is absent. It is a further requirement for an iOS **full** distribution, not the initial appstore compile attempt below. Android vendored AAR files are present, but their presence does not supply a compiler or Android SDK.

Installing only a JDK would not complete this environment: Android SDK/build dependencies and caches are also absent. The configured Gradle/plugin/Maven/Android download hosts are outside this session's permitted outbound host list. No dependency-download success or failure is claimed; the wrapper stopped before reaching downloads. Apple SDK/framework/Swift verification requires a macOS/Xcode host, not just a Linux JDK or Linux Swift installation.

## Commands actually attempted

All commands were short local attempts. No full release build, CI dispatch, release, tag or PR operation ran.

### Android compilation

```sh
./gradlew --no-daemon :composeApp:compileAndroidMain -Pnuvio.android.distribution=playstore
```

Exit code: **1**. Launcher stderr:

```text
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation.
```

**No project configuration, task discovery, source compilation or compiler diagnostics occurred.** Consequently the proposed task name was not validated by Gradle either. Android shared/actual code compilation status remains unknown.

### iOS Kotlin/Native compilation

```sh
./gradlew --no-daemon :composeApp:compileKotlinIosSimulatorArm64 -Pnuvio.ios.distribution=appstore
```

Exit code: **1**, with the same missing-Java launcher error. No Gradle task resolution or Kotlin/Native compilation occurred. Independently, this host lacks macOS, Apple SDKs, Xcode and Kotlin/Native tooling/caches. Device `iosArm64` compilation, simulator framework linkage and generated Swift/Objective-C API signatures were not verified.

### Swift/Xcode availability

```sh
xcodebuild -version
```

Exit code: **127**:

```text
/bin/bash: line 1: xcodebuild: command not found
```

No Swift build, framework import check, signing, simulator launch or iOS test run occurred.

### Focused navigation tests

```sh
./gradlew --no-daemon :composeApp:testAndroidHostTest \
  --tests com.nuvio.app.navigation.MediaEnvironmentStateTest \
  --tests com.nuvio.app.features.media.MediaSwipeDestinationTest \
  -Pnuvio.android.distribution=playstore
```

Exit code: **1**, with the same missing-Java error. Task and filter resolution did not occur. All **eight authored Kotlin tests remain unexecuted**. No test is marked passing on the basis of source inspection or a Python reimplementation.

## Runtime results

| Requirement | Android | iOS |
|---|---|---|
| Full-screen coverage of StreamBridge navigation | Not run | Not run |
| System Back / Exit | Not run | Exit not run; interactive dismissal not exercised |
| Origin route/tab/scroll restoration | Not run | Not run |
| Activity/controller recreation | Not run | Not run |
| Rapid repeated entry/Exit and duplicate presentation prevention | Not run | Not run |
| Background/foreground lifecycle | Not run | Not run |
| Narrow screen, tablet and orientation | Not run | Not run |
| System bars / safe areas | Not run | Not run |
| TalkBack / VoiceOver / large fonts | Not run | Not run |
| Player/carousel gesture arbitration | Not run | Not run |

There is no local APK, linked Apple framework or simulator/device session from this verification pass. Screenshots, accessibility trees and visual/runtime assertions cannot be supplied honestly.

## Source-only regression checks performed

These checks succeeded, but are **not compilation or runtime verification**:

- `git diff --check` passed.
- Parsed `media_strings.xml` and resolved all 18 new shared media resource references.
- Checked source wiring for rememberSaveable environment state, the root-route condition supplied to MainTabsDestination, the reserved boundary condition, the single boundary pointer listener, and explicit multi-pointer rejection.
- Compared tracked paths against HEAD: `Routes.kt`, `NuvioNavigator.kt`, `AppScreenTab.kt`, `RootTabHost.kt`, tracked player/download files in shared/Android/iOS layers, `iosApp/iosApp/Player/` and `androidApp/` have no diff. This establishes absence of direct edits there, not absence of indirect shell regressions.
- Inspected Android Dialog properties: Back is routed to Exit, outside dismissal is disabled, platform default width is disabled, decorFitsSystemWindows is false, and modal system-bar icon appearance is configured. Shared content fills available space and pads safe drawing insets. Actual OS sizing/appearance is unverified.
- Inspected the immutable transition model: StreamBridge is default, open is accepted only from StreamBridge, dedicated environments cannot be nested/switched directly, and Exit is idempotent. The shell Exit callback does not push/pop a StreamBridge route. These properties have authored tests but no executed Kotlin coverage here.
- Inspected iOS native Music buttons: static-mode TabView accessory and expanded-glass-bar companion, both root-gated without changing selectedTab. The dedicated published root flag distinguishes genuinely empty paths from Settings paths that retain the tab bar. Flow requests target the active root; the generated Swift symbol bridge remains uncompiled.
- Inspected iOS actual host: a full-screen ComposeUIViewController with modalInPresentation enabled and dark appearance. Occupied/detached presenter requests are logged and cleared; disposal dismisses only the owned controller. The host does not call Swift coordinator pop/push methods. UIKit attachment, callback timing, view-controller retention, system appearance and root-only availability remain untested.
- The boundary is reserved Scaffold top-bar content only when the tabs route is active. It is not attached globally or to player/carousel surfaces. Its recognizer rejects multi-touch, pointer handoff, consumed changes and vertical intent. Real pointer dispatch, child cancellation and OS-edge arbitration cannot be confirmed statically.

## Known restoration and lifecycle limits

- Keeping the originating shell composed does not stop pending deep links, stream-resolution callbacks or profile/session lifecycle from changing/rebuilding it. Exact restoration under those concurrent events is not guaranteed.
- Saving the selected environment by name is not evidence of end-to-end process-death restoration, Swift stack restoration or UIKit controller-local UI restoration. No process-death test ran.
- Source-level rejection of repeated requests does not prove that platform modal/window lifecycle cannot race. Rapid entry/Exit must be tested with actual Android/iOS dispatch and lifecycle scheduling.
- iOS explicitly disables interactive sheet dismissal; Exit is the supported dismissal path. No native swipe-back or hardware-keyboard dismissal result is claimed.
- Root-only native Music entry, presenter failure handling and narrow navigation-bar layouts need simulator/device validation before compatibility is claimed.

## Required next verification environment

1. **Android:** provision a compatible JDK (repository Android CI uses 21), the configured Android SDK/platform/build tools, dependency access/caches and an emulator/device. Resolve the actual task graph first; then run shared/Android compilation and the two focused test classes. Exercise both environments on a narrow phone and tablet, with gesture and button navigation, rotation/recreation, rapid taps, background/foreground and TalkBack. Include a Full-distribution compile/device check if that distribution is to be supported.
2. **iOS:** use macOS with the repository-selected compatible Xcode/Apple SDKs, JDK, Gradle/Kotlin-Native dependencies, and simulator runtimes. Compile both relevant Kotlin/Native targets, link/import ComposeApp into Swift and run a Debug simulator build. Check native and shared navigation modes on phone/iPad, root/non-root availability, Exit, occupied presenters, orientation, safe areas and VoiceOver. Supply the engine XCFramework for Full-distribution verification.
3. Run the existing player/download/background-playback regression tests and device smoke tests after compilation succeeds; unchanged source alone is not a regression pass.

**Release readiness: not established. No service integration should be started on the assumption that this foundation compiles or is platform-verified.**
