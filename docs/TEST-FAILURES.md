# Unit-test status

Status for the StreamBridge 0.1.08 release, based on NuvioMobile 0.5.5-beta and
NuvioMobile-Enhanced 0.5.5-beta.

The host test suite is a release gate (`./gradlew :composeApp:check`, run as
`:composeApp:allTests` in CI). The `continue-on-error` exception from the 0.1.05
era is long gone: a failing test fails the build.

## 0.1.08 test reconciliations

Upstream never runs `:composeApp:allTests`, so the test sources that arrived with
the 0.5.5-beta sync were compiled and executed for the first time here. Four
groups needed adapting to the code they are supposed to cover. None of them
changed application behaviour; all fixes are in test code.

### Compile errors

- `StreamOrientationTest` carried a duplicated `AndroidAppUpdaterPlatform`
  import from the conflict resolution (upstream's new test file plus
  StreamBridge's updater initialization). Kotlin rejects the now-ambiguous name.
- `PlayerGestureOverlayTest` (new upstream test) called
  `Modifier.playerSurfaceDragGestures(...)` without the `playerController` and
  `swipeToSeekEnabledState` arguments that StreamBridge's same-version
  `PlayerSurfaceGestures.kt` requires. The other three call sites already passed
  them; the test file simply never compiled upstream.

### Behaviour drift in fixtures

- `WatchProgressIdentityTest` and `RatingsVisibilityTest` encoded one-second
  clips. Since 0.5.5 `isShortPlaceholderDuration()` treats anything under 121 s
  as an error/placeholder clip, so a 95 % position on a 1 000 ms entry no longer
  derives "completed". The fixtures now use real-length episodes, which is what
  the assertions were always about.
- `PlayerAutoPlayTest` relied on `WatchProgressRepository.clearLocalState()`
  leaving no stored progress. `clearLocalState()` only clears memory:
  `WatchProgressStorage` keeps one static `SharedPreferences` instance for the
  whole test JVM, so entries persisted by an earlier test were reloaded by
  `ensureLoaded()` and leaked into the error-clip assertions. The class now
  clears the `nuvio_watch_progress` preferences and re-initializes the storage
  per test, the pattern `PlayerSubtitleRestoreTest` already used. Both error-clip
  assertions also report the offending entries in the failure message.

## 0.1.06 test reconciliations

The 0.5.1 integration exposed ten stale or harness-specific assertions. They were
reconciled without reducing StreamBridge functionality:

- Home hero height now expects the production `1.5` mobile ratio.
- Backend retry delay now expects the production 30-second cap plus jitter.
- Root tab retention tests only expect tabs that the harness actually visits;
  Live TV remains conditionally mounted when configured.
- Stream orientation tests initialize StreamBridge's updater platform just as
  `MainActivity` does before composing the app.
- Download subtitle verification no longer imposes a serial request order on
  StreamBridge's intentionally concurrent video/subtitle pipeline.
- Episode rating visibility checks verify semantic presence rather than pixel
  visibility in Robolectric, avoiding a false negative caused by host viewport
  clipping while retaining every hide/show assertion.

CloudStream repository-loader tests continue to execute as proper JUnit tests
and remain part of the gated suite.

## Robolectric note

`assertIsDisplayed()` compares the node's bounds against the *host viewport*.
Robolectric composes at the surface size the test asks for (480 × 270 dp in the
player gesture tests), so widgets laid out at the top of that surface are
displayed and assertions hold. `PlayerGestureOverlayTest` additionally advances
the compose clock before asserting, because Robolectric does not drive it, and
reports the gesture feedback state on failure so a failure names its own cause.
