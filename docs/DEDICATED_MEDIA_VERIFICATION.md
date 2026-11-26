# Dedicated media navigation foundation — final verification review

Review date: 2026-11-24 (UTC session date). Branch: `arena/b0aacb8e-mybridge`.
Original baseline: `b3290f655690f19ac6d35049ff9b7e415e4575a6`.

## Final verdict

**Source-level foundation complete for the agreed limited navigation scope; Android compilation and eight focused tests passed; iOS arm64 simulator Kotlin/Native framework compilation/linking passed. The complete Swift simulator host remains blocked by a reproduced pre-existing MetalLayer compatibility error. Runtime verification has not been performed.**

Ready for the next scoped implementation/planning phase with these gates carried forward, **not** release-ready or proven cross-platform at runtime. No service integration was begun in this review. Before claiming working end-to-end navigation, exercise the platform hosts and navigation gestures on devices; an iOS host build is still required. Full-surface swipe entry is not implemented: swipes are intentionally limited to the reserved boundary.

This final report supersedes the previous local-only status. Local compilation attempts were blocked before Gradle by missing Java/Android/Apple tooling; subsequent authorized remote CI supplied the compilation evidence below. No new build or CI dispatch was needed for this review because source identity was established directly.

## Compilation and test evidence

| Scope | Verified result | Exact source / evidence |
|---|---|---|
| Android Playstore shared/actual compilation | PASS, `:composeApp:compileAndroidMain` | `83e01650f544553d663790043c4dce7c89f3e3d2`; [job 138701583851](https://github.com/sascio/MyBridge/actions/runs/45466179114/job/138701583851) |
| Focused Android host tests | PASS, exactly eight, zero failures/errors/skips | Same job; five `MediaEnvironmentStateTest` cases and three `MediaSwipeDestinationTest` cases; successful independent JUnit-count assertion step |
| iOS Debug arm64 simulator Kotlin/Native framework | PASS, `:composeApp:linkDebugFrameworkIosSimulatorArm64` | `9a2345c463409dcc209ea1716733e69b7d3b1f64`; [job 138724004373](https://github.com/sascio/MyBridge/actions/runs/45472519377/job/138724004373) |
| Unsigned Debug arm64 Swift simulator host | FAIL, exit 65 | Same iOS job; `MetalLayer.swift:256:18`, missing `addPresentedHandler` on `any CAMetalDrawable` |
| Baseline control under matching simulator configuration | Framework PASS; Swift host same FAIL | Actual baseline `b3290f655690f19ac6d35049ff9b7e415e4575a6`; [job 138921169403](https://github.com/sascio/MyBridge/actions/runs/45529550173/job/138921169403) |
| Device-target application build, all distributions, end-to-end Swift interop | NOT VERIFIED | Isolated device API typecheck is not an app build |
| Runtime, visual and accessibility checks | NOT RUN | No emulator, simulator or physical-device session |

The GitHub Jobs API was queried again during this final review: Android compilation, focused tests and count assertion are successful; feature and baseline Apple framework steps are successful; both Swift host steps failed. Checks annotations were re-read for the feature diagnostic. Overall Apple job/run failure is not described as success.

### Why earlier runs cover the current navigation source

Compared actual file bytes from Git objects with the current working tree, including new/untracked source paths:

- Against Android-tested `83e0165…`: all 1,918 files under `composeApp/src/commonMain`, `composeApp/src/androidMain`, `composeApp/src/commonTest`, and `iosApp` match, with no extra untracked files in those roots.
- Against iOS-tested `9a2345c…`: all 2,256 files under `composeApp/src` and `iosApp` match, with no extra untracked files in those roots.
- Whole-tree comparison with the iOS-tested commit finds only changes to the verification workflow and this report among files present at that commit; additional current files are verification reports. Build/dependency configuration and production source therefore have no intervening change. The iOS actual host differs from the Android-tested commit only as documented in the remote CI report; it is excluded from Android source sets.
- The current checkout HEAD is the baseline and the foundation is represented as working-tree changes/new files; the fetched remote branch retains the verification commits. Evidence is tied to exact source bytes, not an assertion that local HEAD itself was built. No reset, branch switch, commit or push was performed during this review.

## MetalLayer and blocker isolation

`iosApp/iosApp/Player/MetalLayer.swift` is unchanged in the working tree versus HEAD/baseline, and the fetched remote branch has no MetalLayer diff versus baseline. Baseline and current SHA-256 are both:

```text
df4066263f63bd28e94151108ce2d5448a4312049c235060df870f09d297ae29
```

No tentative fallback remains. Physical-device presentation handling, optional capture, deferred capture, PiP submission/observation and renderer suspension are preserved exactly. No existing playback or download file changed.

The baseline control actually compiled the baseline under the same configured Xcode 27.1 / simulator SDK 27.1 environment and reproduced the feature failure at line 256. The isolated API probe fails for simulator but passes for the device SDK. Thus the **observed remaining failure** is isolated to an existing simulator API incompatibility, not introduced by navigation. This does not prove that no additional host errors could emerge after that blocker, nor establish runtime compatibility or a historical Xcode regression. No unproven fallback is proposed or applied.

See `IOS_METAL_BASELINE_COMPARISON.md` for exact toolchain/source/control details and `DEDICATED_MEDIA_REMOTE_CI.md` for the compiler-fix/run ledger.

## Complete source/diff review

Reviewed every changed production file and each added host, policy, resource and test file against the baseline, plus the opt-in workflow and supporting reports. The complete inventory is below (including untracked files; plain `git diff --stat` omits those).

- **Ownership:** the existing route stack, tabs, player and downloads retain their owners. No navigator replacement, route rewrite, service adapter or dependency/version change. Dedicated environments are modal state, not added StreamBridge routes/tabs.
- **State:** one `MediaEnvironmentState` declaration and one state owner per composed shell/root; one `LocalOpenMedia` callback. Native Music requests are targeted events, not another selected-tab stack. Swift `isMediaEntryAtRoot` is derived from path emptiness and intentionally differs from the existing tab-bar visibility policy, which permits nested Settings routes. It is not a second navigation stack. Multiple retained native roots still require runtime lifecycle testing.
- **Entry/Exit:** shared classic/floating/tablet Music actions and native static/glass Music actions exist. Native actions are root-gated. The dedicated boundary alone handles swipes; no player/carousel-wide listener was added. Open is idempotent and cannot switch directly between dedicated environments; Exit clears the modal state without popping/pushing the origin route.
- **Platform hosts:** Android full-width dialog routes Back to Exit and disables outside dismissal. iOS full-screen UIKit presentation disables interactive dismissal, dismisses only its owned controller and logs/clears occupied or detached presenter requests. These are inspected implementation properties, not runtime assertions.
- **Branding:** new visible strings use StreamBridge; no added user-visible Nuvio branding. Existing `com.nuvio`, generated-resource names, logging/native filenames and design-token identifiers remain internal. YouTube/YouTube Music labels identify unavailable destinations, not an implemented integration.
- **Unsupported functionality:** pages explicitly state service unavailability; no fake catalogue, playback, sign-in, playlist or download success. Page tabs only select local unavailable-state content.
- **Unused-code finding:** `MediaEnvironmentState.handleBack()` has test callers only. Production Android Back directly calls the dialog Exit callback; iOS supports explicit Exit. It is a redundant policy helper, not evidence of a registered Back handler. Left unchanged to preserve tested source in this review; optional later cleanup. No other newly introduced orphan implementation or duplicate entry composition local was identified by source/call-site review.
- **Scope:** workflow additions are opt-in verification, baseline control and diagnostics; the original APK job is skipped in verification mode. No release/tag/merge/version/secrets change. Supporting docs are related audit/evidence, not production behavior changes. Older source-only statements in `DEDICATED_MEDIA_EXPERIENCES.md` are historical; this report controls the final verification status.

## Explicit runtime and restoration limitations

All of the following remain unverified on both platforms unless platform-specific:

- Full-screen coverage/chrome hiding, system bars/safe areas and platform modal/controller lifetime.
- Back/Exit, rapid repeated entry/Exit, duplicate presentation races, occupied/detached presenter recovery.
- Origin tab/route/scroll restoration, activity recreation and background/foreground lifecycle.
- Native static/glass root-only Music visibility; narrow six-item shared bars, tablets/iPad, rotation and large fonts.
- Player/carousel/nested-scroll gesture arbitration, multi-touch dispatch and OS-edge gestures.
- TalkBack/VoiceOver focus, touch targets and visual layouts.
- Playback/PiP/download regression smoke checks; unchanged implementation is not a runtime regression pass.

Retaining the origin shell does not freeze existing deep-link, stream-resolution, profile or session callbacks. They can mutate/rebuild the underlying shell while covered; exact restoration in those cases is not guaranteed. Saving an environment name is not end-to-end process-death or Swift stack/controller restoration. Per-modal browsing page state starts at Home on reopening. iOS has no claimed interactive swipe-back dismissal.

Next gates: Android device/emulator navigation/accessibility smoke tests; supported complete unsigned Apple host build without disguising the baseline blocker, followed by iOS simulator/device navigation/accessibility tests; device-target and other-distribution verification where support is required. No service functionality or release readiness should be inferred from the eight pure policy tests.

## Final changed-file inventory versus original baseline

- `.github/workflows/pr-full-debug-build.yml`
- `composeApp/src/androidMain/kotlin/com/nuvio/app/features/media/MediaEnvironmentHost.android.kt`
- `composeApp/src/commonMain/composeResources/values/media_strings.xml`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/AppShellComponents.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/MainTabsDestination.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/NativeTabBridge.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/media/MediaEnvironmentHost.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/media/MediaSwipeDestination.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/navigation/MediaEnvironment.kt`
- `composeApp/src/commonTest/kotlin/com/nuvio/app/features/media/MediaSwipeDestinationTest.kt`
- `composeApp/src/commonTest/kotlin/com/nuvio/app/navigation/MediaEnvironmentStateTest.kt`
- `composeApp/src/iosMain/kotlin/com/nuvio/app/features/media/MediaEnvironmentHost.ios.kt`
- `docs/DEDICATED_MEDIA_EXPERIENCES.md`
- `docs/DEDICATED_MEDIA_REMOTE_CI.md`
- `docs/DEDICATED_MEDIA_VERIFICATION.md`
- `docs/IOS_METAL_BASELINE_COMPARISON.md`
- `iosApp/iosApp/ContentView.swift`
- `iosApp/iosApp/NuvioGlassTabBar.swift`

## Final Navigation Foundation Review — 2026-11-25

This dated addendum preserves the preceding verification history and explicitly
supersedes its historical **local-only blocked/unexecuted status** with the
subsequent remote CI results below. Local tooling limitations remain historical
facts, not the final compilation or test verdict.

### Verified evidence by verification level

- **Baseline:** `b3290f655690f19ac6d35049ff9b7e415e4575a6`.
- **Source implementation:** the dedicated-media navigation foundation is complete
  for its agreed limited scope. Current relevant navigation sources match the
  remotely tested sources as established in the review above. No player, PiP or
  download implementation changes were made. No service integration is included.
- **Android compilation:** shared/platform compilation **passed** at commit
  `83e01650f544553d663790043c4dce7c89f3e3d2`, run
  [45466179114](https://github.com/sascio/MyBridge/actions/runs/45466179114).
  This verifies the selected Playstore compilation scope, not every distribution.
- **Focused tests:** exactly **8 passed**, zero failures or skips (five navigation
  transition tests and three swipe-policy tests), in the same Android run. These
  are focused host policy tests, not runtime UI/device tests.
- **iOS framework compilation:** at commit
  `9a2345c463409dcc209ea1716733e69b7d3b1f64`, run
  [45472519377](https://github.com/sascio/MyBridge/actions/runs/45472519377), the
  Debug arm64 simulator Kotlin/Native framework **compiled and linked**.
- **Swift host blocker:** baseline comparison run
  [45529550173](https://github.com/sascio/MyBridge/actions/runs/45529550173)
  and the feature run reproduce the same `MetalLayer.swift:256`
  `CAMetalDrawable.addPresentedHandler` failure under **Xcode 27.1**:
  `value of type 'any CAMetalDrawable' has no member 'addPresentedHandler'`.
  The observed remaining failure is pre-existing, not introduced by navigation.
  A complete Swift host pass or absence of any later masked errors is not claimed.
- **MetalLayer preservation:** `MetalLayer.swift` is unchanged; baseline and feature
  Git blob SHA is `a9ae482e132401b7ed4cdaed6b5f248a9bf25e31`.
  No tentative simulator workaround remains. This Git blob identifier is distinct
  from the SHA-256 content checksum recorded earlier.
- **Runtime verification:** **none** on devices or simulators. Modal lifecycle,
  Back/Exit, restoration, gestures, layouts and accessibility remain unverified.
  No physical-device application compilation or playback/PiP/download runtime
  regression pass is inferred from the framework build or unchanged source.

### Complete 17-file baseline comparison list

The following is the exact committed foundation/verification comparison from the
baseline through `c7d5579e2b37ecf915ab81f13f75513a2c4b4786`, prior to this
append-only documentation update. The earlier 19-file working-tree inventory also
included the two supplemental reports `docs/DEDICATED_MEDIA_REMOTE_CI.md` and
`docs/IOS_METAL_BASELINE_COMPARISON.md`; those are not part of this 17-file
comparison. No application paths were added or changed by this addendum.

1. `.github/workflows/pr-full-debug-build.yml`
2. `composeApp/src/androidMain/kotlin/com/nuvio/app/features/media/MediaEnvironmentHost.android.kt`
3. `composeApp/src/commonMain/composeResources/values/media_strings.xml`
4. `composeApp/src/commonMain/kotlin/com/nuvio/app/AppShellComponents.kt`
5. `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
6. `composeApp/src/commonMain/kotlin/com/nuvio/app/MainTabsDestination.kt`
7. `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/NativeTabBridge.kt`
8. `composeApp/src/commonMain/kotlin/com/nuvio/app/features/media/MediaEnvironmentHost.kt`
9. `composeApp/src/commonMain/kotlin/com/nuvio/app/features/media/MediaSwipeDestination.kt`
10. `composeApp/src/commonMain/kotlin/com/nuvio/app/navigation/MediaEnvironment.kt`
11. `composeApp/src/commonTest/kotlin/com/nuvio/app/features/media/MediaSwipeDestinationTest.kt`
12. `composeApp/src/commonTest/kotlin/com/nuvio/app/navigation/MediaEnvironmentStateTest.kt`
13. `composeApp/src/iosMain/kotlin/com/nuvio/app/features/media/MediaEnvironmentHost.ios.kt`
14. `docs/DEDICATED_MEDIA_EXPERIENCES.md`
15. `docs/DEDICATED_MEDIA_VERIFICATION.md`
16. `iosApp/iosApp/ContentView.swift`
17. `iosApp/iosApp/NuvioGlassTabBar.swift`

### Readiness and scope boundary

The navigation foundation is **ready for the next separately scoped development
phase, but is not release-ready**. Source implementation, compilation, focused
tests and runtime verification are separate conclusions: Android and framework
compilation plus eight focused tests passed; the complete Swift simulator host
is blocked, and runtime navigation remains unverified. Earlier restoration and
lifecycle limitations remain in force. This documentation-only update does not
modify application code, MetalLayer, playback, PiP or downloads, run CI, start
YouTube/YouTube Music integration, publish a release, create a tag or merge a PR.

## Subsequent service implementation increment — compilation not reverified

A later task authorized service implementation. See `MEDIA_SERVICE_IMPLEMENTATION.md`
for the source audit at upstream `c47267c1d2d2a2c87731de7fd2d131526bf9e2f5`,
licensing gates, actual source functionality and explicit non-parity status.
The shared media content, resources and Android initialization now differ from the
previously verified navigation source. **The historical Android eight-test and
iOS framework passes above do not verify this new increment.**

The new focused local test attempt stopped before Gradle because Java is absent;
30 new authored tests remain unexecuted. No new CI or runtime verification was
performed. MetalLayer and existing playback, PiP and download implementations
remain unchanged. The new work is not release-ready or accepted as full OneTube /
YouTube Music parity.

## Service increment 2 — narrow JVM tests executed, platform builds still blocked

See the increment-2 addendum in `MEDIA_SERVICE_IMPLEMENTATION.md` for actual
Google device-auth/library/comments and WebDAV/player implementation scope.
Java 21 runtime is now available. The affected Android compile attempt stopped
at Gradle distribution bootstrap with TLS/EOF, before project compilation.
Seventeen actual policy/parser test methods passed in a standalone Kotlin JVM
subset harness with compile-only annotation markers; this is not Gradle,
serialization, Ktor, Compose, Android or iOS verification. Thirty-six remaining
service tests, including all HTTP/OAuth tests, remain unexecuted. No live account,
server, player, device or simulator verification occurred. Neither old remote CI
passes nor syntax checks establish compatibility for these new sources.
MetalLayer and native playback/PiP/download implementations remain unchanged;
the existing shared player gained only a policy exclusion for the new dedicated
media type to prevent movie/series history pollution. Full parity remains unmet.
