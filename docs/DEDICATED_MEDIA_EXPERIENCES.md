# Dedicated Music and YouTube: foundation and reference audit

Date: 2026-11-24. **Navigation/UI foundation, not a service integration.**

## Current delivery

- `MediaEnvironmentState` is connected to `MainAppContent`. Dedicated experiences use platform modal hosts rather than replacing Navigation3 entries. Exit only changes environment state; the original composition, stack and tab remain in place.
- Music actions are added to shared floating, classic and tablet navigation. Native iOS has an additional Music action in the glass bar, and a native tab-view accessory in static mode. Neither creates or selects a new tab. These actions target the currently active, mounted root Compose controller. Legacy iOS modes use the shared bars.
- `MediaEnvironmentHost.kt` contains distinct music/video designs, fixed safe-area Exit, independent saveable page selection, and explicit unavailable states. No fabricated catalogue, search results, authentication, playback or download controls are supplied.
- The gesture boundary occupies reserved Scaffold top-bar space on root tab screens. Left opens Music, right opens YouTube after a 72dp horizontal drag. Vertical intent, multi-touch, pointer handoff and previously consumed changes cancel the explicit pointer sequence; release commits only after threshold and 1.5× horizontal dominance. It is not present on player/deep routes and never listens globally. Modal windows receive input separately from this boundary.
- Android dialog Back invokes the same Exit callback. Outside-click dismissal is disabled. Android uses an edge-to-edge dialog window with dark-system-bar icon appearance; content uses safeDrawingPadding. iOS uses a UIModalPresentationFullScreen ComposeUIViewController with modalInPresentation=true rather than the no-op shared BackHandler. Interactive sheet dismissal is deliberately disabled; Exit dismisses only that controller without popping a Swift navigation path. Presentation/dismissal are nonanimated to avoid transition overlap. Occupied/detached presenter failures are logged and clear the environment state. Safe areas, orientation, status-bar appearance and iPad sizing remain device-test requirements.
- No player, download service, account implementation, dependency or upstream asset was imported.

### Verification limits

`git diff --check` passed; source-level wiring and reserved layout space were inspected. Neither Java nor kotlinc is installed, so no compilation or Kotlin tests were run. Five pure transition tests and three swipe-policy tests are available in commonTest. No device or live-service tests ran.

Opening/closing the modal does not explicitly reset routes, tabs or list state. Active environment selection now uses rememberSaveable for activity recreation; the modal content remains independently saveable on Android. iOS keeps its presentation across ordinary background/foreground and rotation by controller ownership (source inspection, not exercised). This does not freeze asynchronous effects/deep links or guarantee process-death restoration. Profile/session changes can still rebuild the shell. Exact restoration under those conditions is unverified.

## Current application map

- `App.kt` → `AppGate.kt` → `MainAppContent.kt`. AppGate owns authentication/profile gating. Do not introduce a second gate or remount it to exit an environment.
- `MainAppContent.kt` owns the Navigation3 back stack, selected tab, stream launch flows and overlays. `NavDisplay` handles route Back. `MainTabsDestination.kt` supplies root-tab Back handling and classic/floating/tablet chrome.
- `RootTabHost.kt` preserves visited tab compositions with a saveable-state holder. Preserve the existing shell composition and route objects when presenting an environment, rather than replacing the shell with an `if/else` or navigating back to `TabsRoute` on Exit.
- `AppScreenTab.kt` and `NativeTabBridge.kt` map the existing five destinations. Music is an action opening another environment, not a replacement for a StreamBridge destination.
- iOS additionally has native controllers/chrome in `iosApp/iosApp/ContentView.swift`; publishing tab-bar visibility alone does not establish full-screen presentation and Back semantics for all host modes. `PlatformBackHandler.ios.kt` is currently a no-op.
- `PlayerDestination.kt`, `PlayerEngine.kt` and the shared player runtime dispatch to Android Media3/MPV and iOS implementations. Do not import an independent upstream ExoPlayer singleton over this ownership model.
- `YouTubeStreamResolver.kt` and the expect/actual `TrailerPlaybackResolver` resolve existing video/trailer links. These are **not** catalogue, search, account, playlist or music queue implementations. `AppFeaturePolicy.trailerPlaybackMode` can require external playback.

## Upstream audit

Reference: https://github.com/zoga2o3-ops/Nuvio-One

Inspected HEAD resolved to `3dfa4b0b6135a3c40f17729d8ab59fa5b489971a` during this audit. File paths below refer to that revision, under `app/src/main/java/` unless otherwise specified. This is static inspection, not proof of endpoint availability or successful playback.

| Area | Source evidence | Portability / decision |
|---|---|---|
| YouTube UI/browsing | `com/nuvio/tv/youtube/YouTubeScreen.kt`, `YouTubeViewModel.kt` | UI imports Android DateFormat and BackHandler. View model imports Context, Hilt, JVM utilities and MediaServiceCore. Rebuild responsive shared UI; adapt repository rather than copying TV screens. |
| Search/channels/playlists | `YouTubeViewModel.kt` imports `ContentService`, `SearchOptions`, MediaGroup and MediaItem | Service-backed architecture exists; its MediaItem-backed UI model cannot be a commonMain contract unchanged. Full behavioral coverage not audited. |
| Account/authentication | `YouTubeAccountViewModel.kt`, `com/nuvio/tv/music/MusicAccountRepository.kt` | Device-code flow, RxJava subscription, Android context/preferences. Music bridges signed-in home, liked songs and playlists via MediaServiceCore. Not a portable account adapter. Never reuse StreamBridge credentials for this. |
| Music catalogue | `com/music/bitchord/data/YtMusicRepository.kt`, `data/Http.kt`, `data/innertube/` | Real InnerTube repository/parser code, not just README claims. Repository uses java Locale/concurrent maps; HTTP is OkHttp/Okio with connection-sharing requirements. Adapt transport, concurrency and models for KMP; revalidate endpoint behavior. |
| Music screens | `com/nuvio/tv/music/MusicHomeScreen.kt`, `MusicDiscoverScreen.kt`, `MusicDetailScreen.kt`, `MusicLibraryScreen.kt`, `MusicNowPlayingScreen.kt` present in tree | Screen presence alone is not functional verification. Do not expose all screens before their services work. Build original StreamBridge UI, not copied branding/assets. |
| Music playback | `com/nuvio/tv/music/MusicPlayer.kt` | Android Context/Intent/AudioManager, Media3 ExoPlayer, MediaSession, service-owned audio queue. Requires explicit platform audio ownership and lifecycle integration. Not suitable for commonMain. |
| Offline/downloads | `com/nuvio/tv/music/MusicDownloads.kt`, `MusicDownloadService.kt` | Android filesystem, Media3 cache/database/offline download manager/service. Do not replace or silently share StreamBridge download storage. iOS implementation required separately. |
| History | `MusicListeningStore.kt`, `YouTubeProgress.kt`, home YouTube continue-watching integration present in tree | Persistence and account sync semantics not fully audited; unsupported in this patch. |
| MediaServiceCore | `mediaservicecore/youtubeapi/build.gradle.kts` | Android library; RxAndroid/RxJava, Retrofit/Gson, Cronet, Android WebKit and J2V8. Cannot add as a commonMain or Kotlin/Native dependency. |

`MUSIC-PORTING.md` recommends copying Android folders and adding Android foreground services. That advice is for another Android TV application and is not applicable unchanged to this KMP application.

### Licenses and attribution

Directly inspected through GitHub contents API:

- Repository `LICENSE`: GPL-3.0; upstream README credits BitChord by Kushagra Singh as GPL-3.0. This credit is evidence of upstream attribution, not a completed independent file-by-file provenance review of BitChord.
- `mediaservicecore/LICENSE-MediaServiceCore` and `LICENSE-SharedModules`: MIT, copyright 2020-present yuliskov. Preserve these notices if importing those components.
- `mediaservicecore/j2v8/LICENSE.txt`: EPL-1.0. Do not assume every vendored dependency is MIT because MediaServiceCore is MIT; review compatibility and distribution obligations before reuse.
- Tree contains Commons IO LICENSE/NOTICE, Schibsted Grotesk OFL and audio-sync notices. Those components/assets were not imported or fully audited.
- A root `NOTICE` request returned HTTP 404. This does not remove per-component obligations.

StreamBridge's existing GPL notices and identity are unchanged. No additional attribution is inserted into production NOTICE because no reference code/assets were copied. No new dependency has been approved for incorporation by this audit. Service-policy authorization is separate from copyright licensing; device-code authentication and private InnerTube endpoints need review before production integration. The sandbox only permits selected development hosts, so live YouTube/Music endpoint verification is unavailable here.

## Remaining integration and acceptance work

The shared foundation is connected, but not runtime-verified. The source audit above remains relevant to subsequent service adapters.

- Compile shared/Android/iOS targets and run common tests with available toolchains.
- Test modal full-screen coverage, Android Back and iOS native-controller/modal interaction (including iPad, interactive dismissal, safe areas and accessibility focus).
- Verify native Music actions on iOS 26 phone/tablet, both native tab accessories and custom glass modes. The native Music action is deliberately root-only; it is not offered on nested Swift routes. Expanded glass bars show the action; collapsed bars must first be expanded as with other destinations.
- Verify origin tab/route/scroll restoration and asynchronous callbacks, deep links, profile changes and background/foreground transitions while covered.
- Test reserved strip with every navigation style, small screens, tablets and accessibility font scaling; horizontal shelves, players and OS edge gestures must remain unaffected.
- Connect reviewed catalogue/account/playback interfaces before exposing real search, library mutations or playback controls. Current browsing pages explicitly state service availability and have no fake results.
- Decide environment page persistence across closing/reopening and process recreation. Current per-modal page selection starts at Home when reopened.
- English shared UI strings now use Compose resources; native Swift labels use String(localized:). Translations still need to be supplied and layout-tested.

No acceptance claim is made for unexecuted device tests or unavailable services.

## Focused implementation review (2026-11-24)

### Confirmed issues addressed in source

1. Generic dialog presentation did not explicitly enforce full-screen UIKit behavior. Added Android/iOS actual modal hosts; the iOS presenter cannot adapt to a page sheet or dismiss by pulling it down. Full-screen presentation covers, rather than removes, the underlying shell/navigation. Native iOS does not depend on the shared no-op BackHandler.
2. Music was missing from native iOS navigation. Added standard SwiftUI action buttons: glass-bar companion and static native TabView bottom accessory. No new NativeNavigationTab/AppScreenTab destination or Swift navigation coordinator was added. A targeted SharedFlow sends the action to the active root; stale tab targets are ignored. Requests without a ready receiver are logged. A separately published root-availability flag distinguishes truly empty Swift paths from nested Settings paths that intentionally keep the tab bar visible.
3. Environment selection was plain remember. It is now saved by name through rememberSaveable. This is not a tested process-death restoration guarantee for the full app, Swift navigation stack, or iOS controller-local UI state.
4. Duplicate composition locals for Music/general entry were unnecessary. There is now one LocalOpenMedia callback. The immutable environment model rejects repeated entry and cross-environment switching; Exit is idempotent and the shell's Exit callback checks the presented environment before clearing it.
5. Hardcoded shared UI text and glyph-only page icons were replaced with resource strings and Material icons. Exit and tablet Music have at least 48dp requested touch height; native actions request 44pt. Decorative icons do not repeat their adjacent labels. The headline has heading semantics.
6. The swipe recognizer could hand off across pointers. It now rejects multi-touch/handoff and consumed changes; pure direction/threshold policy is isolated for testing. The direction hint now explicitly says left for Music/right for YouTube instead of ambiguous arrows.

### Why the swipe strip remains

No root-wide detector was added. Home/Library/Search roots contain child-owned horizontal shelves; root-level embedded trailers, overlays and list contents vary with data and form factor. Observing unconsumed movement on the final pointer pass can avoid stealing some child gestures but cannot distinguish a user's intended horizontal navigation from a drag on a non-scrollable card, and accepting motion before children consumes can steal their gestures. System-edge gesture widths also vary by platform. There is no stable, content-wide unused hit region to safely use. The reserved inset strip is therefore the only swipe-entry hit region. It reserves Scaffold layout space and stays off deep/player routes; it does not implement full-surface swiping. Edge and multi-touch arbitration still require device tests.

### Known remaining behavior limits

- Shell retention preserves existing remembered origin state only while that state is not changed by other work. Existing download/deep-link collectors and pending stream-resolution callbacks can still navigate or select a tab while a modal is open. No blanket freeze, callback dropping, or changes to playback/download ownership were introduced. Exact restoration under those concurrent commands is **not guaranteed**.
- Background/foreground does not itself alter the new environment state, but existing authentication/profile lifecycle can rebuild the shell. That is not overridden. Native restoration across process death is not implemented/tested.
- iOS occupied/detached presenter failures close the requested environment and log a warning; no retry queue or stacked modal is created. Native actions are root-only to avoid presenting from a root hidden behind a nested Swift controller.
- Full-screen/system-bar appearance, orientation changes, keyboard/insets, large fonts, VoiceOver/TalkBack focus and narrow six-item shared bars need runtime verification. Existing native tabs are not expanded to six: the Music control is an action accessory/companion, avoiding automatic More-tab routing and profile-index changes.
- Page selection starts at Home on reopening. No service functionality is implied by the unavailable pages.

### Checks actually performed

- Read the complete changed source and native host/navigation paths, including NativeNavComposeView, root/native tab selection, glass/segmented bar behavior and existing UIKit bottom-sheet patterns.
- Ran `git diff --check` and separate whitespace checks for new untracked source/resources.
- Parsed the new XML resource file and checked all 18 new shared media resource references against its definitions. A later whole-tree check initially included seven unrelated pre-existing media-prefixed keys; expanding its lookup to all default XML files resolved that checker-scope error and passed.
- Added three pure swipe-policy tests and one repeated-entry/Exit test; **none were executed**.
- Checked toolchain availability: java, kotlinc and swift are absent. No Gradle compilation, Kotlin test runner, Swift build, device/emulator test or CI workflow ran.
