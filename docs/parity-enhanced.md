# Feature parity: NuvioMobile-Enhanced vs StreamBridge

**Audit date:** 2026-09-12  
**Written before overlay.** Overlay of Enhanced `d4a3b23` + official 0.4.17 re-apply + StreamBridge identity/updater landed in the same change. Device playback remains **UNVERIFIED**.

## Pins

| Tree | Repo | Branch | Commit | User-facing version |
|---|---|---|---|---|
| StreamBridge HEAD (pre-overlay) | sascio/MyBridge `arena/01a08c34-mybridge` | `889ea2a` | official Nuvio **0.4.17 / `d177eb57`** | **0.1.01** (101) |
| Official Nuvio | NuvioMedia/NuvioMobile | `cmp-rewrite` | `d177eb57` | 0.4.17 |
| Reference fork | [luqmanfadlli/NuvioMobile-Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced) | `enhanced` | `d4a3b23` (`chore(store): publish 0.4.16`) | 0.4.16 |

Enhanced vs official `d177eb57`: **179 commits ahead, 9 behind** (the 0.4.17 plugin/sync/player fixes). File diff: **234 paths**. License: **GPL-3.0** (same as upstream).

## Intentional StreamBridge differences (do not “fix”)

| Topic | Fork | StreamBridge | Why |
|---|---|---|---|
| applicationId | `com.nuvio.media` / debug `com.nuviodebug.com` | `com.streambridge.app` | StreamBridge identity |
| Visible name | Nuvio / Nuvio Enhanced | StreamBridge | Branding |
| Launcher / splash mark | Nuvio | StreamBridge symbol; splash = logo + name + tagline | Branding |
| About credits | Nuvio wordmark + Tapframe | Tiny: StreamBridge / Based on NuvioMobile / NuvioMedia | User requirement |
| App version | Nuvio 0.4.16 | **0.1.01** | Independent versioning |
| Updater GitHub | `luqmanfadlli/NuvioMobile-iOS` | `sascio/MyBridge` | StreamBridge releases only |
| Default addons | Empty (user-added) | Empty | Do not seed |
| TMDB / MDBList / Trakt / Simkl / OMDB keys | Empty unless configured | Empty | No fake keys |
| Gradle heap | 12g | 6g (CI) | Keep StreamBridge CI |
| `local.properties` always `@InputFile` | Yes (breaks Gradle 9) | Optional if missing | Keep CI fix |
| Official 0.4.17 fixes | Missing (9 behind) | Present | Preserve |

## Matrix (Enhanced README + traced implementation)

Feature | Nuvio fork | StreamBridge (pre-overlay) | Missing/Different | Action
---|---|---|---|---
Home catalogs / pagination / empty / errors | Official + extras | Official 0.4.17 | Same core | Keep official; overlay extras
Hero trailer autoplay + delay + style | Settings → Layout | Partial (detail hero trailer exists; home extras missing) | Home hero style/delay/dynamic bg | Overlay Enhanced home/settings
Dynamic background from artwork | Off by default | Absent | Missing | Overlay `DynamicArtworkBackground.kt`
Catalog accent underline | Off by default | Absent | Missing | Overlay appearance settings
Search (debounce, cancel, history, providers) | Official | Official | Same | Keep
Metadata movie/series/seasons/episodes/cast | Official | Official | Same | Keep
Budget / revenue on details | Present | Absent | Missing | Overlay details components
Episode ratings (TMDB + OMDB) | OMDB module; IMDb episode repo removed | IMDb episode ratings repo | Fork replaced IMDb episode path with OMDB | Overlay OMDB; do **not** seed OMDB key
More Like This → View All paged grid | Present | Official rail only | Missing View All | Overlay
Random episode + include-watched toggle | Present | Absent | Missing | Overlay
Addons install/remove/enable/manifest | Official | Official; empty default | Same | Keep empty default
Plugins / QuickJS | Official 0.4.17 (pool, bytecode, pause in playback) | Present | Fork behind 0.4.17 | Overlay fork then **re-apply 0.4.17** plugin/sync/streams patches
Player Media3 + libmpv | Official + volume boost, HLS quality, info modal, tap-to-seek, swipe-to-seek toggle, keyboard shortcuts | Official 0.4.17 player | Missing Enhanced player extras | Overlay player files; keep 0.4.17 binge-reuse-off / stringResource / runtime patches
Audio / subtitles / resume / next episode | Official | Official | Same core | Overlay extras only
Live TV (M3U / Xtream / Stalker) | Full feature, tab when configured | **Absent** | Missing | Overlay `features/livetv/**` (unconfigured = no tab)
Library add/remove / CW / watched | Official | Official | Same | Keep 0.4.17 sync-delete fix
Library calendar | Present | Absent | Missing | Overlay
Downloads + Wi-Fi-only + custom folder | Present | Official downloads; no Wi-Fi/folder extras | Missing extras | Overlay downloads settings (documentfile)
Profile insights | Present | Absent | Missing | Overlay
Custom profile background URL | Present | Partial (member backgrounds) | Overlay custom URL
Trakt/Simkl device-code login | Present | Browser OAuth only | Missing | Overlay device-code sheets; still no default client IDs
Settings search | Official | Official | Same | Overlay new rows (Live TV, OMDB, insights, downloads)
Content & Discovery: Addon **and** Plugin separate | Present | Present | Keep split | Overlay must not merge them
In-app debug logs | Present | Absent | Overlay (optional advanced)
App intro | Present | Absent | Overlay
iOS PiP / Skia / CJK font / glass tab | iOS-only | iOS sources exist (official) | Overlay iOS files; Android primary
Updater UX | Nuvio banner; toasts on no-channel | StreamBridge GitHub; **too silent** on manual check | Manual check shows nothing | Restore Nuvio “up to date” on **manual** current/no-release; keep startup silent; never map empty-200 to “cannot reach servers”
Startup network toasts | Nuvio | Suppressed until Home ready | Keep StreamBridge silent-startup
Deep links / notifications / permissions | Official | Official | Same | Overlay only if fork changed
Animations / poster transitions | Official + small UI fix | Official | Overlay UI fix (`duplicate poster clickable`)
Localization | Fork extra strings | Official locales | Overlay strings + keep StreamBridge name
Signing | Nuvio keystore required for release | CI debug-sign fallback | Keep StreamBridge fallback |

## 0.4.17 files to re-apply after overlay

**Safe copy (fork did not touch):** plugin Android/iOS runtime, PluginRepository, sync deleted-data, library reconciler, StreamDestination, StreamModels, PlayerStreamList, PlayerScreenRuntimeEffects/SourceActions, ProfileRepository, tests, xcconfig.

**Both touched — apply official `465266d..d177eb57` patch on Enhanced copies:** AddonPlatform, AddonRepository, PlayerSettingsStorage, PlayerSettingsRepository, PlayerStreamsRepository, PlayerScreenRuntimeUi, StreamBadgeChip, StreamsRepository, StreamsScreen, PluginRuntime, FetchBridge.

## Updater (pre-overlay bug)

`AppUpdater.kt` currently:

- Auto-check: silent (correct)
- Manual check: also silent when no newer APK / no releases / API fail (incorrect vs this task)

Required:

- Newer valid APK → Nuvio banner (auto + manual)
- Manual + already current **or** HTTP 200 with no newer valid release → “You're using the latest version”
- Genuine network/HTTP failure on **manual** → updater check-failed message (not Home “Cannot reach servers”)
- Auto: never toast no-update / no-release / network
- Ignore drafts + prereleases; ABI pick unchanged

`sascio/MyBridge` has **zero GitHub Releases** at audit time. Manual check after the fix should say up-to-date, not hang. A real 0.1.02 APK test needs a controlled non-production channel or unit tests; this environment **cannot install on a phone**.

## Legal

Enhanced is GPL-3.0. Overlay is license-compatible. Do not copy AltStore `store.json` / Enhanced branding as StreamBridge assets. NOTICE will record the Enhanced commit.
