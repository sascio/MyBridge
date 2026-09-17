# Known unit-test failures

Status at the time the NuvioMobile-Enhanced port landed.

`./gradlew :composeApp:check` runs the `allTests` host suite:
**1113 tests, 12 failing.**

The unit-test CI step is currently `continue-on-error: true` so the suite is
visible without blocking APK validation. It should become a hard gate once this
list is empty. **The debug and release APKs build and assemble successfully.**

## Category A - pre-existing on `main`, not caused by the port (3)

These test files are byte-identical to `936e116` and the production code they
exercise was **not touched** by the merge. They assert values the production
code has never returned, so they were already red; they only became visible
now because the workflow never ran unit tests before.

| Test | Expected | Actual | Why |
|---|---|---|---|
| `HomeHeroSectionTest.mobile hero height stays compact without continue watching` | `452.4` | `585.0` | `452.4 = 390 x 1.16`, but `MOBILE_PORTRAIT_HERO_WIDTH_RATIO` is `1.5` (`390 x 1.5 = 585`). The test still encodes the superseded `1.16` ratio. |
| `HomeHeroSectionTest.mobile hero height leaves room for continue watching card section` | reserve of `24` | mismatch | Same portrait-branch ratio change; the viewport-driven path is no longer reached for portrait. |
| `HomeHeroSectionTest.mobile hero can shrink below default minimum to fit short viewport` | `268` | mismatch | Same cause. |
| `BackendRateLimitTest.retry delay uses bounded fallback and adds jitter` | `60_250` | `30_250` | `retryAfterDelayMillis` applies `.coerceAtMost(MaximumFallbackDelayMs = 30_000)`, so a `Retry-After: 60` header clamps to `30_000` before jitter. The test predates that clamp. |

`HomeHeroSection.kt` was edited by the merge, but only its carousel
auto-scroll effect (`ScreenActivityEffect`); `homeHeroLayout` /
`mobileHeroHeight` and all five layout constants are **identical** to base.
`BackendRateLimit.kt` was not modified at all.

Fix direction: these are stale expectations, not product bugs - update the
expected values (or intentionally restore the old ratio/clamp). Deliberately
left alone here to keep the port free of unrelated behavioural edits.

## Category B - Enhanced tests asserting upstream behaviour (8)

Added by the port. They test Enhanced components that merged cleanly but whose
supporting wiring differs in StreamBridge.

| Test | Notes |
|---|---|
| `RootTabHostTest.switchingAllTabsRetainsVisitedContentWithoutMountingUnvisitedTabs` | New `RootTabHost.kt` + `ScreenActivity.kt`; StreamBridge's `AppShellComponents` mounts tabs differently. |
| `RootTabHostTest.activityEffectsFollowSelectionWithoutRecomposingTheirParentContent` | Same. |
| `StreamOrientationTest` (4 tests) | Expect Enhanced's orientation policy for manual stream lists; StreamBridge keeps its own playback/orientation fixes, which must not be regressed. |
| `DownloadSubtitlesTest.backgroundDownloadSavesAddonAndStreamSubtitlesBeforeVideo` | Enhanced's subtitle-before-video ordering vs StreamBridge's sink-based download pipeline. |
| `AvatarPickerTest.rowsFillTheWidthAcrossDensitiesAndLayoutDirections` | Grid edge-alignment assertions against StreamBridge's `ProfileEditScreen` layout. |

Fix direction: decide per test whether to adopt Enhanced's behaviour or keep
StreamBridge's. Because the instruction was explicitly *not* to regress
StreamBridge's playback/provider fixes, these were **not** silently
"fixed" by changing product code to satisfy upstream tests.

## Already adapted (not in the failure list)

Three merged Enhanced test files failed to **compile** against StreamBridge
APIs and were adapted (tests changed, production code untouched):

- `AndroidDownloadTransferTest` - rewritten onto the `DownloadSink` API
  (StreamBridge supports SAF document targets; Enhanced wrote straight to a
  `File`).
- `NextEpisodeCardTest`, `PlayerSurfaceGesturesTest` - pass the additional
  `playerController` and `swipeToSeekEnabledState` parameters that
  StreamBridge's `playerSurfaceDragGestures` requires.
