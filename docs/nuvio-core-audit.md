# Milestone 0 — Nuvio core source audit

**Status: AUDIT COMPLETE (historical). Milestone 1 imported this mapping
into the tree — see [milestone-1.md](milestone-1.md). This document
records what was read before the import; it is not the live layout.**

This document is the source mapping required before any large-scale
Nuvio integration. Nothing below is guessed: it was read from the
official trees recorded in [UPSTREAM.md](UPSTREAM.md) and from this
repository.

The standing product rule:

> StreamBridge = Nuvio core + StreamBridge branding/UI + StreamBridge extras.

The Nuvio core is **not** present today. StreamBridge is an independent
MIT Android app that *speaks* the Stremio addon protocol and *imitates*
parts of Nuvio playback. That is the previous mistake. Do not continue
it as the foundation.

---

## 1. What StreamBridge is today

Single-module Android app (`:app`), namespace `com.streambridge.app`,
Jetpack Compose + Media3, MIT license.

| Area | Location | What it actually is |
|---|---|---|
| App shell | `MainActivity.kt`, `StreamBridgeApp.kt`, `di/AppContainer.kt` | Manual DI, original navigation |
| Addons | `addon/` (Stremio HTTP protocol) | Original protocol client. Starts **empty**. |
| Plugins | `addon/plugin/` + `compat/` | Original QuickJS sandbox *compatible with* Nuvio plugin manifests — **not** Nuvio’s `PluginRuntime` |
| Discovery | `data/discovery/` | Original home/search/merge |
| Library / progress | `data/library/`, `data/db/` | Original Room store |
| Settings | `data/settings/` | Original DataStore. TMDB/MDBList **OFF** by default |
| Player | `player/` | **Custom** Media3 holder + planning + backend selector + libmpv fallback |
| Player UI | `ui/player/PlayerScreen.kt` | Original Compose controls |
| Source selection | `ui/sources/` | Original progressive picker |
| LAN bridge | `server/` | StreamBridge-original (not Nuvio) |
| Branding | `ui/theme/`, `ui/components/` | StreamBridge-original |

Kotlin scale: **92** main `.kt` files.

The player stack the brief forbids is exactly what is in tree:

```
PlayerViewModel
  → PlaybackPlanning
  → PlaybackBackendSelector
  → PlayerHolder (Media3 ExoPlayer)
  → EngineEscalationPolicy → LibMpvEngine
```

That stack can compile. It is **not** Nuvio’s player. Device playback
parity with Nuvio is **REQUIRES DEVICE VALIDATION** and must not be
declared complete from CI.

---

## 2. What NuvioMobile actually is (the phone foundation)

Kotlin Multiplatform + Compose Multiplatform.

| Slice | Path | Scale |
|---|---|---|
| Shared app | `composeApp/src/commonMain` | **523** `.kt` files |
| Android actuals | `composeApp/src/androidMain` | **109** `.kt` files |
| Full-only (plugins, extra player factory) | `composeApp/src/fullCommonMain` + `androidFull` | plugins runtime, P2P policy |
| Play Store strip | `composeApp/src/androidPlaystore` | plugins **disabled** |
| Android host | `androidApp/` | flavors `full` / `playstore` |

Build that matches Nuvio’s own phone APK:

```bash
./gradlew :androidApp:assembleFullDebug
```

(`-Pnuvio.android.distribution=full` when the task name is ambiguous.)

### 2.1 Player (highest priority)

Nuvio does **not** have `PlayerHolder` / `PlaybackBackendSelector`.

| Nuvio component | Source | Role |
|---|---|---|
| `expect fun PlatformPlayerSurface(...)` | `composeApp/src/commonMain/.../player/PlayerEngine.kt` | Engine boundary |
| `actual` ExoPlayer + libmpv | `composeApp/src/androidMain/.../player/PlayerEngine.android.kt` (**2381 lines**) | Real Android player |
| HTTP session / OkHttp factory | `PlayerPlaybackNetworking.kt`, `PlatformPlaybackDataSourceFactory.android.kt` (full) | Source headers as default request properties; Range stripped |
| Player UI / lifecycle | `features/player/PlayerScreen*.kt` (~60 common files) | Tracks, subs, sources, next-episode, gestures |
| Streams / autoplay | `features/streams/` | Stream list, binge groups, autoplay policy |
| Skip intro | `features/player/skip/` | IntroDB (optional URL) |

Android engine behavior read from `PlayerEngine.android.kt`:

1. **Media3/ExoPlayer first** (`AndroidPlaybackEngine.Auto` / `ExoPlayer`).
2. On `UnrecognizedInputFormat` / `IO_UNSPECIFIED` / `BEHIND_LIVE_WINDOW`:
   probe MIME **once**, rebuild the same `MediaItem`, stay on ExoPlayer.
3. On decoder failure while extension mode is `ON`: rebuild once with
   `EXTENSION_RENDERER_MODE_PREFER` (software extension renderers).
4. Any remaining ExoPlayer error in Auto: switch the **same source** to
   **libmpv** (`ResolvedAndroidPlaybackEngine.Libmpv`).
5. Audio/subtitle UI reads the **active** engine (`extractAudioTracks` /
   `extractLibmpvTracks` from `track-list`).

Software decode is **not** “jump to libmpv first”. It is vendored
Media3 extension AARs:

`composeApp/libs/` (pulled via `fileTree { include "lib-*.aar" }`):

| AAR | Role |
|---|---|
| `lib-exoplayer-release.aar` | Forked ExoPlayer; **replaces** Maven `media3-exoplayer` / `media3-ui` (those modules are `exclude`d) |
| `lib-decoder-ffmpeg-release.aar` | FFmpeg extension renderer |
| `lib-decoder-av1-release.aar` | AV1 extension renderer |
| `lib-decoder-mpegh-release.aar` | MPEG-H extension renderer |
| `lib-ui-release.aar` | Forked Media3 UI |
| `lib-nuvio-engine-android-0.1.1.aar` | Torrent engine JNI |
| `quickjs-kt-android-1.0.5-nuvio.aar` | Full-flavor JS plugins |

Published Media3 line: **1.8.0** (hls/dash/ss/rtsp/datasource/decoder/common/container/extractor).

libmpv binding: `io.github.abdallahmehiz:mpv-android-lib:0.1.12`
(StreamBridge currently uses `io.github.wohal:mpv-android-lib:0.2.4` —
same family, **not** the pin Nuvio ships).

### 2.2 Addons / catalogs / metadata

| Nuvio component | Source | Notes |
|---|---|---|
| Addon install/list | `features/addons/AddonRepository.kt` | **No hardcoded default URLs.** Empty local storage → empty list |
| Manifest HTTP | `AddonHttpClient.kt`, `AddonManifestParser.kt`, `AddonTransportUrls.kt` | Stremio `manifest.json` + resources |
| Storage expect | `AddonPlatform.kt` | Android/iOS actuals |
| Catalog / home | `features/catalog/`, `features/home/` | Rails from installed addons |
| Details | `features/details/` | Meta, seasons, episodes |
| Search | `features/search/` | Addon search |

Nuvio **does** sync addons to Supabase when the user is signed in.
Core browsing must still work from local storage alone (the repository
already preserves local addons if the server is empty). StreamBridge
must not require Nuvio cloud accounts.

### 2.3 Plugins (JS providers)

Only in the **full** distribution (`AppFeaturePolicy.pluginsEnabled = true`
in `androidFull`; **false** in `androidPlaystore`).

| Nuvio component | Source |
|---|---|
| Plugin list / manifests | `features/plugins/` common + `fullCommonMain` |
| Execution | `fullCommonMain/.../plugins/runtime/PluginRuntime.kt` + `js/`, `network/`, `dom/`, `crypto/`, `wasm/` |

This is the runtime StreamBridge extras (Peachify / AnimePahe / …) must
eventually share. StreamBridge’s current `NuvioPluginRuntime` +
`CompatPrelude` is a **parallel** sandbox, not this code.

### 2.4 P2P / torrents

| Nuvio component | Source | Notes |
|---|---|---|
| Settings (default **off**) | `features/p2p/P2pStreaming.kt` (`p2pEnabled: Boolean = false`) | User must enable |
| Android engine | `features/p2p/P2pStreamingEngine.android.kt` (848 lines) | Talks to vendored `lib-nuvio-engine` AAR |
| Native core | NuvioMedia/nuvio-engine | C++20 + libtorrent 2.0.12. README: **not production-ready** |

Do **not** invent a new torrent stack. Use NuvioMobile’s Android wiring
+ the AAR they already ship. Treat nuvio-engine’s CMake tree as
upstream for rebuilds, not as something to rewrite.

### 2.5 Watch progress / episodes / settings

| Nuvio component | Source |
|---|---|
| Progress model + rules | `features/watchprogress/` |
| Next-episode autoplay | `features/player/PlayerNextEpisodeAutoPlay.kt` |
| Player settings | `PlayerSettingsRepository.kt` + Android storage |
| Optional Trakt/Simkl/TMDB/MDBList | `features/trakt`, `simkl`, `tmdb`, `mdblist` | Config generated empty unless `local.properties` supplies keys |

Optional integrations stay **off** unless the user enables them and
supplies keys. Do not bake Nuvio’s cloud keys into StreamBridge.

### 2.6 Distribution flavors (do not mix)

| Flavor | Plugins | P2P policy flag | Software decoder AARs |
|---|---|---|---|
| `full` | yes | yes | yes (`lib-*.aar`) |
| `playstore` | no | P2P flag true, plugins false | same `lib-*.aar` fileTree |

StreamBridge’s phone APK must follow **`full`**: that is the Nuvio
build that actually plays the sources users care about.

---

## 3. NuvioTV (Android-specific reference, not the phone shell)

NuvioTV (`com.nuvio.tv`) is a separate GPL-3.0 Android TV app.

Useful **after** NuvioMobile player is in place, not as the phone UI:

- `app/src/main/java/com/nuvio/tv/core/player/` — Dolby Vision, AFR,
  HEVC RPU strip, bitrate-aware load control
- `core/torrent/` — TorrServer (different from nuvio-engine)
- In-tree `libmpv-android/`, `ffmpeg-decoder-downmix/`

Do not replace NuvioMobile’s `PlayerEngine.android.kt` with the TV
player. Phone target = NuvioMobile.

---

## 4. nuvio-engine

Cross-platform C++ torrent engine (GPL-3.0-or-later). libtorrent is BSD
3-Clause (fetched at build, not committed). Android/Apple/Linux/Windows
packaging is in `platform/`.

NuvioMobile already consumes a prebuilt Android AAR
(`lib-nuvio-engine-android-0.1.1.aar`). Milestone 1 should **use that
same AAR** rather than adding an NDK build of nuvio-engine to CI.

**BLOCKED for “production torrent” claims:** upstream itself says the
engine is not production-ready. Wire it the way NuvioMobile does; do
not declare P2P complete from a compile.

---

## 5. License (blocking for any copy)

| Tree | License | Implication |
|---|---|---|
| StreamBridge today | MIT | Cannot absorb GPL source and stay MIT |
| NuvioMobile / NuvioTV | GPL-3.0 | Copying player/addon/runtime **requires StreamBridge to become GPL-3.0** |
| nuvio-engine | GPL-3.0-or-later | Same |
| Nuvio `lib-decoder-ffmpeg` etc. | Shipped by a GPL app; treat as GPL-incompatible with MIT | Only reusable **after** StreamBridge is GPL-3.0 |
| `mpv-android-lib` | MIT (mpv-android lineage) | OK either license |
| Media3 Maven artifacts | Apache-2.0 | OK |
| StreamBridge QuickJS (`quickjs-kt`) | Apache-2.0 | OK; Nuvio’s forked AAR is a different artifact |

Milestone 1 **must** start by:

1. Replacing `LICENSE` with GPL-3.0 (or GPL-3.0-or-later if engine is included).
2. Adding Nuvio copyright / `COPYING` / third-party notices.
3. Recording that StreamBridge is a **GPL derivative** of NuvioMobile
   (branding overlay), not an MIT reimplementation.

Do not copy Nuvio sources into this repo while `LICENSE` is still MIT.

Do not relicense StreamBridge-original files away from their authors
without the GPL overlay on the combined work.

---

## 6. Mapping table (Nuvio → StreamBridge)

| Nuvio component | Nuvio source | StreamBridge destination | Adaptation | Dependencies | License |
|---|---|---|---|---|---|
| App / navigation | `composeApp` `App.kt`, `MainAppContent.kt`, `navigation/` | Replace `ui/navigation/StreamBridgeRoot.kt` after core plays | Branding, applicationId `com.streambridge.app` | Compose Multiplatform or Android extract | GPL-3.0 |
| Player engine | `PlayerEngine.android.kt` + networking + data source factory | **Replace** entire `player/PlayerHolder.kt` stack | Keep StreamBridge package **or** keep `com.nuvio.app.features.player` and wrap | Media3 1.8.0 + `lib-*.aar` + mpv-android-lib **0.1.12** | GPL-3.0 |
| Player UI | `features/player/PlayerScreen*.kt` | Replace `ui/player/PlayerScreen.kt` | StreamBridge theme later (Milestone 4) | PlayerEngineController | GPL-3.0 |
| Streams | `features/streams/` | Replace `addon/StreamResolver.kt` playback handoff | Map to Nuvio stream models | Addon HTTP | GPL-3.0 |
| Addons | `features/addons/` | Replace `addon/ExtensionManager.kt` protocol core | **Keep empty defaults** | Ktor/OkHttp actuals | GPL-3.0 |
| Plugins | `features/plugins` + `runtime/` | Replace `addon/plugin/*` | Full flavor only; still empty until user adds a repo | Nuvio quickjs AAR | GPL-3.0 |
| Catalog / home | `features/home`, `catalog` | Replace `data/discovery/` | StreamBridge home chrome later | Addons | GPL-3.0 |
| Details / episodes | `features/details` | Replace `ui/details/` | — | Meta resources | GPL-3.0 |
| Watch progress | `features/watchprogress` | Replace `data/library` progress | Local-only must work without Trakt/Simkl | Storage expect/actual | GPL-3.0 |
| Settings | `features/settings` | Merge into `data/settings` + `ui/settings` | Integrations remain OFF | — | GPL-3.0 |
| P2P | `features/p2p` + engine AAR | New (StreamBridge has **none**) | Default disabled | `lib-nuvio-engine-android` | GPL-3.0 |
| Subtitles | player + `SubtitleRepository.kt` | Replace side-load path in `PlayerViewModel` | OpenSubtitles stays optional | Player engine | GPL-3.0 |
| TMDB / MDBList / Trakt / Simkl | `features/tmdb` etc. | Keep optional, OFF | Empty config; no bundled keys | User keys | GPL-3.0 |
| Debrid / membership / Supabase | `features/debrid`, `membership`, `core/network` | **Do not require** for core | Stub/disable cloud until extras | — | GPL-3.0 |
| LAN bridge | *(none in NuvioMobile)* | Keep `server/` as StreamBridge-original | After core | — | MIT island inside GPL app |
| SourceSelectionScreen | Nuvio `PlayerSourcesPanel` / stream screen | Keep StreamBridge UI only in Milestone 4+ | Must call Nuvio stream+player APIs | — | mixed |
| CloudStream `.cs3` | Nuvio does not execute CS3 either | Stay **NOT IMPLEMENTED** | Honest browse-only if kept | — | — |
| MPVKit | empty in shallow clone (iOS) | Not for Android | — | — | — |
| NuvioTV player extras | `NuvioTV/.../core/player` | Later, codec edge cases only | Do not swap phone engine | TV-only deps | GPL-3.0 |

---

## 7. Recommended integration strategy (Milestone 1)

**Prefer Nuvio implementation → minimal adaptation → StreamBridge branding.**

Do **not**:

- keep evolving `PlayerHolder` / `PlaybackPlanning` as the long-term player
- port Nuvio “behavior” into new StreamBridge types
- copy `PlayerEngine.android.kt` into `PlayerHolder` piecemeal
- vendor Nuvio GPL AARs while this repo is MIT

Do:

1. **Relicense this repository to GPL-3.0** and add Nuvio attribution.
2. Vendor NuvioMobile as a git subtree (or `:composeApp` + `:androidApp`
   modules) pinned to `e377942`, **full** Android distribution.
3. Change applicationId / app name / icons to StreamBridge; leave
   `com.nuvio.app` packages intact so upstream merges stay possible.
4. Force addons/plugins to start empty (already true in Nuvio local
   storage if we do not seed URLs).
5. Leave Trakt/Simkl/TMDB/MDBList/debrid/Supabase unconfigured.
6. Produce an APK with GitHub Actions via
   `:androidApp:assembleFullDebug` (and release equivalent).
7. Only then delete or quarantine the custom `player/` stack.

Tracking layout (so upstream sync stays possible):

```
third_party/nuvio-mobile/     ← git subtree, GPL-3.0, commit pinned
app/                          ← StreamBridge branding, extras, CI glue
docs/UPSTREAM.md              ← pin
```

Exact module layout can be “run Nuvio’s Gradle graph and overlay
branding” rather than flattening KMP into one `:app`. Flattening would
make future Nuvio merges impossible.

---

## 8. What StreamBridge already got right (keep)

These are **not** Nuvio core, but they match the brief and must survive:

- Extensions/plugins start **empty** (no CNCVerse, no bundled providers).
- TMDB / MDBList **OFF** by default; no bundled API keys.
- No fake streams / demo playback URLs in production code.
- CI already builds `testDebugUnitTest` + `assembleDebug` + `assembleRelease`.
- Honest CloudStream limitation (no `.cs3` execution).

---

## 9. Explicitly NOT IMPLEMENTED (relative to Nuvio core)

| Item | State |
|---|---|
| Nuvio `PlatformPlayerSurface` / `PlayerEngine.android.kt` | NOT IMPLEMENTED |
| Nuvio software-decoder AARs on the classpath | NOT IMPLEMENTED (license-blocked while MIT) |
| Nuvio libmpv pin `0.1.12` + `track-list` UI | PARTIAL imitation only |
| Nuvio addon repository / catalog pipeline | NOT IMPLEMENTED (parallel protocol client) |
| Nuvio plugin runtime | NOT IMPLEMENTED (parallel QuickJS sandbox) |
| P2P / nuvio-engine | NOT IMPLEMENTED |
| Nuvio watch-progress rules / next-episode autoplay | PARTIAL (original Room progress) |
| Nuvio settings / player settings | NOT IMPLEMENTED |
| GPL attribution / relicensing | NOT IMPLEMENTED |
| Device playback parity | REQUIRES DEVICE VALIDATION |

---

## 10. Milestone gate

Milestone 0 is **done** when this mapping exists in the repo.

Milestone 0 is **not** “the app is Nuvio.” CI green on the custom
player does **not** complete Milestone 1.

Next: **Milestone 1 — Nuvio core** (relicense + vendor NuvioMobile full
Android as the runnable foundation). No StreamBridge extras, no UI
redesign, no default extensions, no enabled integrations.
