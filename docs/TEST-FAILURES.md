# Known unit-test failures

Status after the NuvioMobile 0.5.1 / Enhanced 0.5.1-beta sync
(commit `8603b603`, CI run `36083305028`).

`./gradlew :composeApp:check` runs the `allTests` host suite:
**10 failing** (down from 12 at the previous audit). The unit-test CI step
is `continue-on-error: true` so the suite is visible without blocking APK
validation. Each failing test is annotated by name in the run
(`.github/report-failing-tests.py`). **The debug and release APKs build
and assemble successfully, and both CloudStream APK verifications pass.**

## Fixed by the 0.5.1 sync or follow-up (previously failing)

| Test | Status |
|---|---|
| `HomeHeroSectionTest.mobile hero height leaves room for continue watching card section` | Passes again (upstream 0.5.1 hero layout changes). |
| `HomeHeroSectionTest.mobile hero can shrink below default minimum to fit short viewport` | Passes again. |
| `AvatarPickerTest.rowsFillTheWidthAcrossDensitiesAndLayoutDirections` | Passes again (upstream `ProfileEditScreen` grid changes merged with StreamBridge's avatar-status states). |
| `CloudStreamRepositoryLoaderTest` (whole class, `initializationError`) | Fixed in `8603b603`: two methods ended with `assertNotNull(...)`, which returns non-Unit, so JUnit4 rejected the class and **none of its tests were executing**. Now `runBlocking<Unit>`; all nine tests run and pass. Pre-existing since the CloudStream suite landed; surfaced by the new per-test CI annotations. |

## Category A - pre-existing on `main`, not caused by the port (2)

These test files are byte-identical to upstream and assert values the
production code has never returned.

| Test | Expected | Actual | Why |
|---|---|---|---|
| `HomeHeroSectionTest.mobile hero height stays compact without continue watching` | `452.4` | `585.0` | `452.4 = 390 x 1.16`, but `MOBILE_PORTRAIT_HERO_WIDTH_RATIO` is `1.5` (`390 x 1.5 = 585`). The test still encodes the superseded `1.16` ratio. |
| `BackendRateLimitTest.retry delay uses bounded fallback and adds jitter` | `60_250` | `30_250` | `retryAfterDelayMillis` applies `.coerceAtMost(MaximumFallbackDelayMs = 30_000)`, so a `Retry-After: 60` header clamps to `30_000` before jitter. The test predates that clamp. |

Fix direction: these are stale expectations, not product bugs — update the
expected values (or intentionally restore the old ratio/clamp). Deliberately
left alone to keep the sync free of unrelated behavioural edits.

## Category B - Enhanced tests asserting upstream behaviour (7)

| Test | Notes |
|---|---|
| `RootTabHostTest.switchingAllTabsRetainsVisitedContentWithoutMountingUnvisitedTabs` | Expects `{Home, Search, Library, LiveTv, Settings}`; StreamBridge's host-test composition mounts `{Home, Search, Library, Settings}` (Live TV tab requires configured sources). |
| `RootTabHostTest.activityEffectsFollowSelectionWithoutRecomposingTheirParentContent` | Same. |
| `StreamOrientationTest` (4 tests) | Fail in setup: `AndroidAppUpdaterPlatform.initialize must be called before use` — StreamBridge's updater platform requires initialization that the Enhanced test harness does not perform. StreamBridge's own playback/orientation behaviour must not be regressed to satisfy them. |
| `DownloadSubtitlesTest.backgroundDownloadSavesAddonAndStreamSubtitlesBeforeVideo` | Enhanced's subtitle-before-video ordering vs StreamBridge's sink-based download pipeline. |

Fix direction: decide per test whether to adopt Enhanced's behaviour or
keep StreamBridge's. Because the instruction was explicitly *not* to
regress StreamBridge's playback/provider fixes, these were **not**
silently "fixed" by changing product code to satisfy upstream tests.

## Category C - upstream test that fails upstream (1)

| Test | Notes |
|---|---|
| `RatingsVisibilityTest.allEpisodeLayoutsRespectVisibilityForFetchedAndAddonRatings` | New with the 0.5.1 sync, byte-identical to Enhanced 0.5.1-beta. **Every production file it exercises (`DetailSeriesContent.kt`, `DetailMetaInfo.kt`, `MetaDetailsModels.kt`, `EpisodeRatingsVisibility.kt`, theme) is byte-identical to Enhanced 0.5.1-beta**, so it fails the same way on the unmodified upstream tree. Neither NuvioMobile nor NuvioMobile-Enhanced runs unit tests in CI (their workflows only assemble APKs), which is why it was never caught upstream. Fails at `onNodeWithText("8.4").assertIsDisplayed()` (the badge is not displayed under the Robolectric compose host). Not caused by, and not fixable within, the StreamBridge integration; upstreaming a fix is the correct channel. |

## Already adapted (not in the failure list)

Three merged Enhanced test files failed to **compile** against StreamBridge
APIs and were adapted (tests changed, production code untouched):

- `AndroidDownloadTransferTest` - rewritten onto the `DownloadSink` API
  (StreamBridge supports SAF document targets; Enhanced wrote straight to a
  `File`).
- `NextEpisodeCardTest`, `PlayerSurfaceGesturesTest` - pass the additional
  `playerController` and `swipeToSeekEnabledState` parameters that
  StreamBridge's `playerSurfaceDragGestures` requires.
