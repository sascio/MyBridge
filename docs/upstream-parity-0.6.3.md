# Upstream parity — Nuvio 0.6.3 (Enhanced + official)

How the two upstream pins this repository tracks were reconciled into StreamBridge for the
**0.1.13** release, and what is still deliberately different.

| | |
|---|---|
| Enhanced reference | `luqmanfadlli/NuvioMobile-Enhanced`, tag `0.6.3-beta` |
| Enhanced commit | `b9504e1a3bfd212cb32a54138b149ea1995b65c7` |
| Official reference | `NuvioMedia/NuvioMobile`, tag `0.6.3-beta` |
| Official commit | `014fa2e2f8e2f4914e09d117ffe29d1eefcc6275` |
| Previous pins (0.1.12 baseline) | official `1cfae0fe7402f5e6f1fedad393d50d83775586fa`, enhanced `5d978096f9eb04a62a6bc3a5d6999bdccab4c9cb` (both `0.6.2-beta`) |
| Common ancestor used for 3-way merges | official 0.6.2 (`1cfae0fe`) |
| Commit range, official 0.6.2 → 0.6.3 | **35** commits, **52** files (18 added, 34 modified) |
| Commit range, enhanced 0.6.2 → 0.6.3 | **60** commits, **132** files (45 added, 86 modified, 1 deleted) |
| Relation between the two pins | `git merge-base --is-ancestor 014fa2e2 b9504e1a` → **true**: official 0.6.3 is an ancestor of enhanced 0.6.3, so syncing the Enhanced pin carries every official 0.6.3 change |
| StreamBridge delta applied | **132** changed paths: **45** added as-is, **67** taken from upstream unchanged (StreamBridge had never touched them), **17** three-way merged on top of StreamBridge customisations, **1** deletion (`GlassBarSurface.kt`, removed upstream), 2 StreamBridge-owned files skipped (`store.json`, `iosApp/Configuration/Version.xcconfig`) |

Method: both pins were fetched into this repository (`git fetch` of the upstream remotes, tags
recorded as `upstream-official-0.6.3-beta` / `upstream-enh-0.6.3-beta`), and every path in the
Enhanced delta was classified against the StreamBridge tree:

* **new upstream file → added verbatim** (no StreamBridge counterpart existed);
* **modified upstream file, StreamBridge tree byte-identical to the 0.6.2 base → taken verbatim**;
* **modified upstream file, StreamBridge had local edits → `git merge-file` three-way merge** using
  the official 0.6.2 blob as the base, so StreamBridge behaviour survives while upstream's change
  lands on top;
* **upstream-owner files (version, `store.json`, release workflows, branding) → not taken.**

The Enhanced tree was **not** copied wholesale: only the 132 paths that actually changed between
the two pins were integrated. One conflict occurred (`values/strings.xml`) and was resolved by
keeping StreamBridge's branded profile-PIN string and appending upstream's 22 new navigation-bar
glass keys. No conflict markers remain in the tree.

## Verification against the enhanced 0.6.3 tree

Measured on this tree at `0.1.13` (`composeApp/src` plus the module build script):

| Check | Result |
|---|---|
| Kotlin files in upstream 0.6.3 | **1465** |
| Kotlin files byte-identical to upstream 0.6.3 | **1358** |
| Kotlin files that differ | **99** — all pre-existing fork decisions, none of them a path in the 0.6.3 delta except the 9 intentionally merged files below |
| Upstream Kotlin files absent here | **8** (4 instrumentation tests, `BackgroundDownloadTestActivity.kt`, `launcher/AppIconActivities.kt`, `MdbListLibraryListsPicker.kt`, `SupportersContributorsPage.kt`) — see [Deliberate deviations](#deliberate-deviations) |
| StreamBridge-only Kotlin files | **86** (CloudStream runtime, Trakt device flow, MDBList HTTP engine, updater, branding, tests) |
| Delta files still differing from upstream after the sync | **9**, all `MERGE` targets, each carrying StreamBridge code upstream does not have: `MainActivity.kt`, `CatalogRepository.kt`, `PartyRepository.kt`, `PlayerScreenRuntimeEffects.kt`, `PlayerScreenRuntimeUi.kt`, `SettingsModels.kt`, `SettingsScreen.kt`, `StreamBadgeChip.kt`, `StreamsScreen.kt` |
| `values/strings.xml` | merged: upstream's 22 new glass/navigation keys **plus** StreamBridge's `profile_pin_clear_cleanup_failed` |
| Upstream deletions | 1 applied (`GlassBarSurface.kt`); no references to it remain |

## What 0.6.3 added, and where it landed

Status legend: **Integrated** (upstream code present and wired), **Merged** (present on top of a
StreamBridge customisation).

### Official NuvioMobile 0.6.2 → 0.6.3

| # | Change | Upstream commit | Where it landed | Status |
|---|---|---|---|---|
| 1 | Watch party **chat** (send/list RPC, realtime `chat` event) | `4fe5d96f` | `features/party/PartyChat.kt`, `PartyChatContent.kt`, `PartyApi.kt`, `PartyChannel.kt`, `PartyModels.kt`, `PartyPanel.kt`, `PartyRepository.kt`, `PartyScreen.kt`, `commonTest/.../PartyChatTest.kt` | Integrated |
| 2 | **Party companion** — drive the party from your phone with the same profile | `ebb17678` | `features/party/PartyCompanion.kt`, `PartyCompanionScreen.kt`, `PartyScreen.kt` (`PartyScreen.kt:106` swaps to `PartyCompanionScreen` when a companion link is active) | Integrated |
| 3 | Chat sends with the **return key** | `b8249380` | `PartyChatContent.kt` | Integrated |
| 4 | Player **badges** — what is playing, matched against stream-badge rules | `344117e7` | `features/player/PlayerBadges.kt` (new), `PlaybackMatchText.kt` (new), `PlayerTimeline.kt:98`, `PlayerScreenRuntimeUi.kt:432`, `StreamBadgeSettingsRepository.showInPlayer`, settings row in `StreamsSettingsPage.kt` | Integrated |
| 5 | Centre controls stay put on pause; engine switch moved to the top row **with a confirmation dialog** | `37a6656a`, `18268427` | `PlayerControlActions.kt`, `PlayerControls.kt`, `PlayerScreenRuntimeUi.kt`, `PlayerScreenRuntimePlaybackActions.kt`, `PlayerScreenRuntimeState.kt`, 2 new strings | Integrated |
| 6 | Party source shown as **plain text** | `d45d4d36` | `PartyScreen.kt`, `PartyRepository.kt` | Merged |
| 7 | `bingeGroup` always stores **infoHash + filename** | `1b85c645` | `PlayerScreenRuntimeState.kt:141-142` | Integrated |
| 8 | **Library custom posters** in the catalog/list view | `8c5e09c0` | `CatalogRepository.kt:116,195` | Merged |
| 9 | Localisation: pt-BR, Hungarian, Vietnamese, autosync/party strings | `903dc99c`, `b8e58f1f`, `a5183958`, `0d9fbc43`, `99146a4f`, `9b0863e2`, `6c2d93d3`, `b999d7e3`, `0edc4daf`, `6b25c403`, `eddb1139`, `eedc3343`, `aa74808a` | `composeResources/values{,-hu,-vi,-pt-rBR}/strings.xml` + the l10n XML sets they touch | Integrated (resource presence checked; no linguistic review) |
| 10 | Workflow/CI cleanups and MPVKit submodule fetch for the debug APK build | `95aa9786`, `f054426b`, `4aba2efc`, `e4f56ef5` | **not taken** — StreamBridge owns `.github/workflows/` | Upstream-only |
| 11 | `store.json` publication + version bump | `324afcff`, `014fa2e2` | **not taken** — StreamBridge owns `store.json` and its version file | Upstream-only |

### NuvioMobile-Enhanced 0.6.2 → 0.6.3 (beyond official)

| # | Change | Upstream commit | Where it landed | Status |
|---|---|---|---|---|
| 12 | **Discord Rich Presence** (opt-in) | `0e023d5e` | `features/discord/*` (8 files, Android + iOS actuals), `settings/DiscordRpcSettingsPage.kt`, `SettingsPage.DiscordRpc`, `PlayerScreenRuntimeDiscord.kt`, `DiscordRpcStorage` init in `MainActivity` | Integrated — **`enabled = false` by default** (`DiscordRpcRepository.kt:81`), Settings → Integrations → Discord RPC is the only switch |
| 13 | **Unified Liquid Glass navigation bar** + settings sheet, preview, per-surface effect settings | `69e907c4`, `0009587a`, `edfc412c`, `cc407cbc`, `39738467`, `451f2517`, `ac67a775`, `abe14de8` | `core/ui/glass/LiquidGlassEffectSettings.kt`, `LiquidGlassNavigationSurfaces.kt`, `core/ui/FloatingNavigationBar.kt`, `MainTabsDestination.kt`, `settings/{NavigationBarSettingsSheet,NavigationBarPreview,NavBarStyle}.kt`, `AppearanceSettingsPage.kt:278`, `androidHostTest/.../{LiquidGlass*,NavigationBarGlassSettingsTest,NavigationPreviewScrollBounceGateTest}.kt` | Integrated |
| 14 | **Custom subtitle fonts** (import a TTF, pick it per profile) | `b47732e3` | `features/player/{SubtitleFonts,SubtitleFontRepository,SubtitleFontImporter,SubtitleFontFiles}.kt` + Android/iOS actuals, `settings/SubtitleFontDialog.kt`, `PlaybackSettingsPage.kt:348,738,1761`, applied on the mpv path (`PlayerEngine.android.kt:1733,2301`) and the libass/ExoPlayer path (`SubtitleFontFiles.android.kt:39`, `aiostreams/AiostreamsAssEngine.kt:409`) | Integrated |
| 15 | **Subtitle text shadow** (enable + strength) | `63ce8680`, `111471f5` | `PlayerSettingsRepository.kt:354-356,683-684`, `PlayerSettingsStorage.kt:66-68` + platform actuals, `androidMain/player/SubtitleShadowSpan.kt` (`ExoSubtitleShadow`), `PlayerEngine.android.kt:2589,3009,3458`, `PlayerSidecarSubtitles.kt:193` | Integrated |
| 16 | Liquid Glass **search bar** and stream **filter field** in cinematic mode | `d875e3f8`, `13c87a32` | `features/streams/StreamsScreen.kt`, `SearchScreen.kt` surfaces, `core/ui/glass/*` | Integrated |
| 17 | Stream card **glass legibility** (lighter glass + text shadow) | `ab5f157d`, `53ed7540` | `StreamCard.kt`, `StreamBadgeChip.kt`, `StreamsScreen.kt` | Merged |
| 18 | Player **glass/libass regression** fix | `a1f1e952` | `PlayerEngine.android.kt`, `PlayerSidecarSubtitles.kt` | Integrated |
| 19 | Cinematic details **hero artwork** reuse | `1b670f7a` | `features/details/components/*` detail artwork surfaces | Integrated |
| 20 | **iOS hero trailer zoom** kept after Fill became stretch | `a4796e75` | `iosApp/iosApp/**` trailer surfaces (Swift) | Integrated (source), iOS workflow compiles independently |
| 21 | pt-BR translations for the new strings | `69ae467c` | `values-pt-rBR/strings.xml` | Integrated |

Nothing in the 0.6.3 delta was skipped for complexity. The two things deliberately not taken
(upstream CI workflows, upstream `store.json`/version) are StreamBridge-owned files, listed under
[Deliberate deviations](#deliberate-deviations).

## StreamBridge-only work in 0.1.13

### Background → return no longer loses the session

**Symptom (reported on `androidApp-full-arm64-v8a-release.apk`).** Play a video, pause, switch to
another app, come back: the app was on profile selection, or on Home with the player and the
navigation history gone.

**Root cause.** Backgrounding the app while a video is paused is enough for Android to reclaim the
process (the full build is large — `largeHeap` does not prevent that). On return, Compose restored
`AppGate`'s `gateScreen` as `Main` *together with the Navigation 3 back stack*, but
`AuthRepository` had restarted and reported `AuthState.Loading` while it validated the stored
session. `AppGate`'s routing effect treated that transient `Loading` as a cold start and called
`enterProfileGate(...)`, which re-derived profile selection (`AppGate.kt`, `is AuthState.Loading ->`
branch). That replaced the restored `Main` with `ProfileSelection`/`Loading`, unmounting
`MainAppContent` and discarding its restored back stack; when the session then resolved, `Main` was
mounted fresh at `initialRoute` = `TabsRoute`, i.e. Home. With `rememberLastProfileEnabled` off (or
several profiles) the user landed on profile selection instead.

**Fix (smallest lifecycle-safe change, no keep-alives or foreground services).**

* `composeApp/src/commonMain/.../AppGateSessionRestore.kt` (new) — `restoredSessionGateAction(...)`,
  a pure policy that keeps a restored `Main` gate only when *all* of: the composition was restored
  from saved instance state, the saved gate is `Main`, auth is still pending, and the profile the
  gate was recorded for is still the active profile.
* `AppGate.kt` records `mainGateProfileIndex` (saved with the gate, set on every profile entry) and
  returns early from the auth/profile routing effect while the policy says "keep". Sign-out still
  reaches the auth/profile gate, because once auth resolves to `Unauthenticated` the normal branch
  runs unchanged.
* `App.kt` / `MainActivity.kt` — `App(restoredFromSavedState = savedInstanceState != null)`; a
  restored activity also stops replaying its original launch intent
  (`if (!restoredFromSavedState) handleIncomingAppIntent(intent)`), which used to re-navigate a
  restored session to a deep link it had already handled.

**What the user sees now.** The restored back stack is kept. `PlayerDestination` and
`StreamDestination` pop cleanly when their in-memory launch data is gone (they already did), so a
process death while the player was open lands on the title's details screen instead of profile
selection or Home. The player itself cannot survive process death — the ExoPlayer instance and the
launch payload live in memory — so playback is not resumed; watch progress still resumes from the
normal resume prompt.

**Deliberately unchanged.** The `ON_STOP` pause in `PlayerEngine.android.kt:950-960` and
`:1442-1450` is intentional (backgrounding pauses playback; PiP is exempt). No wake lock, no
foreground-service change, no battery-optimisation prompt was added.

**Tests.** `commonTest/.../AppGateSessionRestoreTest.kt` covers the policy (restored Main kept;
cold launch, other profile, unknown profile, resolved auth and a non-Main gate all fall through),
and `androidHostTest/.../AppGateRestoreWiringTest.kt` guards the one-line wiring (activity reports
restore-ness, passes it to `App`, does not replay the launch intent, gate consults the policy).

### Trakt (investigation only — no behaviour change in 0.1.13)

Both upstream pins and StreamBridge use the **classic OAuth 2.0 authorization-code flow with a
client secret**. There is **no PKCE for Trakt** anywhere in either pin.

| Fact | Official 0.6.3 (`014fa2e2`) | Enhanced 0.6.3 (`b9504e1a`) | StreamBridge 0.1.13 |
|---|---|---|---|
| Credentials gate | `features/trakt/TraktAuthRepository.kt:116-117` — `CLIENT_ID.isNotBlank() && CLIENT_SECRET.isNotBlank()` | same logic in its own copy | `TraktAuthRepository.kt:140-141` `hasRequiredCredentials()` → `RuntimeCredentials.allConfigured(CLIENT_ID, CLIENT_SECRET)` |
| Early return when unconfigured | `:121` | same | `:169`, surfaced as the missing-credentials error; UI gate in `TrackingProviderCards.kt:280` |
| Authorize endpoint | `:38` `https://trakt.tv/oauth/authorize` | same | `:45` same |
| Authorize URL params | `:524` — `response_type`, `client_id`, `redirect_uri`, `state` (no `code_challenge`) | same | `:856` — identical set |
| API base / token / revoke host | `:37` `https://api.trakt.tv`, token `:304` | same | `:44` base; token `:565`, refresh `:664`, revoke `:624` |
| `client_secret` sent on the wire | yes — DTOs at `:560`, `:569`, `:578` | yes | yes — `:401` (device token), `:558` (refresh), `:619` (revoke), `:656` (code exchange), DTOs `:966`, `:973`, `:982`, `:991` |
| `code_challenge` / `code_challenge_method` | **absent** (`git grep code_challenge` matches only `features/simkl/*`) | **absent** (0 matches under any `*trakt*` path) | **absent** |
| Device authorization flow | **not present** | **not present** | **StreamBridge-only**: `:318` `/oauth/device/code`, `:396` `pollDeviceToken`, `:414` `/oauth/device/token`, `:919-930` `traktDevicePollAction` (200/400/404/409/410/418/429/5xx mapping, `SLOW_DOWN` handled at `:428`) |
| Callback | `nuvio://auth/trakt` via `LinkActivity` | same | same — manifest `scheme=nuvio, host=auth, path=/trakt`, `AppUrlBridge.handleAppUrl` → `isTraktAuthCallback` (`:256`) |

**Answer to "ID only, or ID + secret?"** With the code as pinned, StreamBridge needs **both**
`TRAKT_CLIENT_ID` and `TRAKT_CLIENT_SECRET`; a build with only the id reports "Trakt is not
configured". Trakt's own documentation now says the opposite is possible — a client created today
gets a **Client ID only**, PKCE is the recommended sign-in, the secret is optional and deprecated for
user sign-in, and **all** OAuth requests (exchange, refresh, revoke, device) should go to
`auth.trakt.tv` (`developer.trakt.tv/docs/pkce`, `/authentication-oauth`, `/create-an-app`, and
`trakt/trakt-api`'s `clientSecretSchema = optional + deprecated`). PKCE there is **S256 only**, and
the authorize/redirect rules require an `https` app/universal link rather than a custom scheme.

So: **StreamBridge's Trakt support is currently secret-only, PKCE-free, and posts to
`api.trakt.tv`.** Moving to id-only PKCE (plus `auth.trakt.tv`, S256 challenge, universal-link
redirect, single-use refresh-token handling, and the device flow's optional-secret contract) is a
credential-flow rewrite and is scheduled for **0.1.14**; it is explicitly *not* done here. Simkl is
unaffected: it already uses PKCE with an S256 challenge (`features/simkl/SimklPkce.kt:19-30`).

One documentation error is corrected with this release: the 0.6.2 parity notes claimed a
"Trakt/Simkl PKCE flow". Only Simkl uses PKCE.

## Deliberate deviations

* **StreamBridge identity and release plumbing** — application id `com.streambridge.app`, visible
  name, launcher art, updater (`sascio/MyBridge`), `store.json`, `streambridge.version.properties`,
  `Version.xcconfig`, signing config and `.github/workflows/` stay StreamBridge-owned.
* **CloudStream runtime** — StreamBridge-only; upstream files were merged around it.
* **Trakt device flow + poll hardening** — StreamBridge-only (see above); upstream has the browser
  redirect flow only.
* **Upstream instrumentation tests and launcher activity helper** —
  `androidApp/src/androidTest/*` (`BackgroundDownloadTest`, `LibraryTrackingUiTest`,
  `MdbListLiveTest`, `MdbListSettingsUiTest`), `BackgroundDownloadTestActivity.kt` and
  `launcher/AppIconActivities.kt` are not imported: they target upstream's launcher/icon pipeline
  and require a device; StreamBridge ships its own branding and test surface.
* **`MdbListLibraryListsPicker.kt`, `SupportersContributorsPage.kt`** — superseded by StreamBridge's
  own picker and About/Supporters presentation.
* **`ProfileAvatarBorderColorTest.kt`** — **dropped**. The file ships in Enhanced 0.6.3 but the
  function it calls (`resolveProfileAvatarBorderColor`) is declared **nowhere in either upstream
  tree** (`git grep` over `b9504e1a` and `014fa2e2` matches only the test itself), so it cannot
  compile against upstream either. Porting it would mean inventing a profile-avatar-ring colour
  feature that upstream does not have; the test is removed instead of a production symbol being
  made up for it. If the feature lands upstream later it can be ported with its implementation.
* **Two upstream host tests adapted to the current API** (upstream's own `androidHostTest` does not
  compile at these pins):
  `FloatingNavigationBarGestureTest.kt` now passes the two-argument
  `FloatingNavigationItem.content` lambda that production code (upstream included) declares, and
  `NavigationBarGlassSettingsTest.kt` swipes `centerLeft → centerRight` instead of reading a node
  `size` that `InjectionScope` no longer exposes. Behaviour under test is unchanged.
* **Upstream issue templates, stale bot and legacy workflows** — not imported.

## Known gaps

* No Android device or emulator is reachable from this environment, so the lifecycle fix and every
  visual change are verified by source analysis and unit tests only. The on-device reproduction
  steps ship with the release notes; see also `docs/UPSTREAM-SYNC.md`.
* Watch party chat and the phone companion need the upstream Supabase backend and two signed-in
  accounts.
* Discord RPC needs a Discord account and network access to Discord's gateway; only the code path
  and the disabled-by-default state are verified here.
* Localisation coverage is machine-checked (resource presence and key parity), not linguistically
  reviewed.

Historical notes for 0.1.11/0.1.12 (the 0.6.2 sync, the Watch Party crash fix, release runs) live in
[docs/UPSTREAM-SYNC.md](UPSTREAM-SYNC.md).
