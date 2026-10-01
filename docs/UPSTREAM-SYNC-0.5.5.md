# StreamBridge 0.5.5-beta synchronization audit

Verification date: **2026-10-01 (UTC)**. This is the isolated stable-application
update track, not the CloudStream development track.

## Baseline and isolation

| Item | Verified value |
| --- | --- |
| Default branch | `main` |
| Default-branch snapshot at task start | `c52a2d7238e3e2874743851f968bb0e14dfe94f9` |
| Latest published StreamBridge release | `v0.1.07`, `a5fe7269aa63fac2b6c6775ca20b0eea954544a1` (2026-09-26) |
| Latest published release **without the CloudStream runtime** | `0.1.04`, `936e116acb940103bf7db2d13ec632d9ebaa3bab` (2026-09-14) |
| Last clean follow-up commit used as this track's starting point | `ee6f99b20ef06236029979d1636ebde5b151d677` (`fix(trakt): honour device-token poll status codes`) |
| Arena update branch | `arena/01a0f91c-mybridge` |

`main`, `v0.1.05`, `v0.1.06`, and `v0.1.07` already contain CloudStream
runtime work. They are **not** suitable clean baselines for this request.
History inspection identifies `789a391` as the first active CloudStream feature
commit; its parent is `ee6f99b20ef06236029979d1636ebde5b151d677`.
This session's previously unmodified, unpublished Arena branch was reset to
that parent, without switching branches or changing `main`. No development
branch was merged, rebased, or cherry-picked.

At the chosen baseline, a case-insensitive `git grep` for `cloudstream`,
`PathClassLoader`, and `com.lagradost` in `composeApp`, `androidApp`, `iosApp`,
`gradle`, `settings.gradle.kts`, and `.github` returns **no matches**. Historical
browse-only code under the unbuilt `legacy/` directory is not a runtime and is
not a source or test dependency of this application.

Explicitly excluded development commits:

- `6d4d5aa985039280bc9c42f48c2c386d4095c720`
- `c0538b16f9b2fad903c48e9d512e7e0184b58af0`
- `789a391` and subsequent CloudStream runtime/dependency/classloader work

## Direct upstream verification

| Source | Release/tag | Exact source commit | Release publication |
| --- | --- | --- | --- |
| [Official Nuvio Mobile](https://github.com/NuvioMedia/NuvioMobile/releases/tag/0.5.5-beta) | `0.5.5-beta` | `5c6028f24d3017f27fc4aeb0b6234b18fcfd2ee8` | 2026-09-30 19:22:54 UTC |
| [NuvioMobile Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced/releases/tag/0.5.5-beta) | `0.5.5-beta` | `8b3bd867e172757c9fd7373da301e6489914f3fc` | 2026-10-01 05:54:34 UTC |

**Enhanced really has this tag and release.** No nearest-version or branch-tip
substitution is needed. Both release APIs currently report `isPrerelease=false`
despite the `-beta` tag; StreamBridge will use its own beta-release policy.

The Enhanced tag contains the official target commit in its ancestry. Its
additional official commit `d667f4324b5f8fbcb5954dae6ee6b82885f9a9c4` only publishes
upstream store metadata; it does not change application source. Later moving
branch tips are not the synchronization inputs. Neither selected application
source tree contains CloudStream/DEX runtime code.

Baseline Enhanced source:
`ce4492ec057928b330105a2ad0ed54427f3a82e1` (`0.4.23-beta` store publication).
Its official ancestor/merge base with the target is
`cbc921d910c1c555fbbf683d438b1ba850df23ca`. The baseline version-properties
file still claimed official `0.4.17`; that claim was stale after its Enhanced
integration and is not used as the three-way source base.

Verification commands used (GitHub authentication is supplied by the environment):

```sh
gh release view 0.5.5-beta -R NuvioMedia/NuvioMobile --json tagName,publishedAt,body,assets
gh api repos/NuvioMedia/NuvioMobile/git/matching-refs/tags/0.5.5
gh release view 0.5.5-beta -R luqmanfadlli/NuvioMobile-Enhanced --json tagName,publishedAt,body,assets
gh api repos/luqmanfadlli/NuvioMobile-Enhanced/tags --paginate
# In the separately downloaded, detached Enhanced source checkout:
git merge-base --is-ancestor 5c6028f24d3017f27fc4aeb0b6234b18fcfd2ee8 8b3bd867e172757c9fd7373da301e6489914f3fc
```

## Stable StreamBridge inventory before synchronization

- Android `com.streambridge.app`, version `0.1.04`, code `104`; full sideload
  and playstore build flavors, four release ABI splits, R8/resource shrinking.
- Production signing reads the established `NUVIO_RELEASE_*` secrets/properties,
  fails closed when `STREAMBRIDGE_REQUIRE_PRODUCTION_SIGNING=true`, and checks
  the established public upload-certificate SHA-256 in the release workflow.
- StreamBridge launcher/splash/colorway assets, wordmark, onboarding/auth/profile
  branding and separate StreamBridge version/updater metadata.
- Empty default addon/plugin/catalog configuration; Stremio-compatible addons
  and the upstream **JavaScript** plugin runtime (not CloudStream).
- Stable source picker/pinning, HTTP handling, Media3/ExoPlayer and native mpv
  player infrastructure; SAF download targets and Wi-Fi-only settings.
- Optional TMDB, MDBList, OMDB, Trakt and Simkl configuration. Trakt device-token
  HTTP status handling, normalized optional credentials, and presence-only
  build diagnostics are local behaviors to retain.
- Avatar catalog loading/error/empty/retry and access restrictions; upstream
  public account/avatar backend configuration retained as a technical service,
  not relabeled as a StreamBridge-owned backend.
- Library, calendar, Live TV sources/EPG/settings, profiles and per-profile
  settings/sync, navigation, metadata enrichment and episode ratings.
- Privacy & Policy, GPL licenses, legal attribution, signing documentation and
  release-certificate continuity.
- Updater endpoints restricted to `sascio/MyBridge`, ABI-aware APK selection and
  stable/beta channel infrastructure. Existing legacy stable-only helpers are
  retained until their tests/references are audited.
- iOS sources already exist (Swift host, Compose/Kotlin framework, mpv bridge,
  Live Activities/download widget), but display/bundle identity and signing
  still reference upstream. There is no working StreamBridge iOS release
  workflow, and the required MPVKit submodule was not registered in this fork.
- README, NOTICE and previous synchronization notes are stale and need a genuine
  StreamBridge rewrite, not just a name replacement.

The file-level comparison to the exact old Enhanced source records 181 baseline
local differences (including artwork, source, omitted upstream helper scripts,
and missing iOS dependency bootstrap), 494 upstream-generation file changes,
and 39 overlapping modifications. Those overlaps require three-way integration
or explicit review; whole-project replacement is not appropriate.

## Synchronization map

“Existing” means present in the chosen clean baseline, not in the unstable
branch. Integration uses the official implementation as carried by the pinned
Enhanced source, then its audited feature overlay, while retaining StreamBridge
local differences. The companion file inventory records individual paths.

### Official source

| Area | Existing baseline | Target changes to integrate |
| --- | --- | --- |
| Android | Media3 1.8.0, mpv 0.1.12, full/playstore distributions | Player lifecycle/restored-position fixes, gesture/seek preview, download/library presentation and source loading |
| iOS | Swift host, mpv bridge, Compose framework | Lifecycle/rendering/PiP improvements, complete dependency bootstrap, QuickJS 1.0.15 on iOS |
| Shared/common | Addons, metadata, tracking, profiles, library | Updated UI sheets/dialogs/chips, poster customization/sync, playback keys and shuffle handling |
| Dependencies | AGP 9.2.0, Kotlin 2.4.10, Compose 1.12.0, Gradle 9.4.1 | Coil 3.6.3, iOS QuickJS 1.0.15, engine 0.1.2; retain verified unchanged versions |
| Player | ExoPlayer/mpv, subtitle and seek controls | Short-clip tracking guard, smaller buffers, restored resume, swipe-to-seek preview, post-credits/next-episode timing |
| Networking | OkHttp/Darwin Ktor clients, addon HTTP helpers | Engine binary fetch, plugin asynchronous timers and fetch bridge fixes |
| Addons/plugins | Empty defaults, user-added repositories | JS timer support, addon landscape posters, debrid requested-episode file selection |
| Metadata | Optional TMDB/MDBList/OMDB | TMDB source priority and folder-ID resolution, Simkl type hints, custom-poster retention |
| Library | Saved/cloud lists and calendar | Newest MDBList additions first, downloads in Library with activity indicator |
| Profiles | Multiple profiles, PIN/avatar support | PIN alignment, poster settings schema updates and profile-sync compatibility |
| Settings | Per-profile settings and platform policy | Shared detail chips, poster pattern controls, landscape clearlogo toggle |
| Live TV | Enhanced implementation already present | No official CloudStream hierarchy; retain playlist/EPG implementation |
| Localization | Many locales | Bengali/Urdu additions, Greek/Vietnamese/Slovak updates and episode-label consistency |
| Updater | StreamBridge endpoint and channels | Preserve StreamBridge repository, transport/cancellation safeguards and ABI behavior |
| Build/release | Android-only fork workflows | Audit upstream Android/iOS mechanics; do not import upstream store publishing or keys |

### Enhanced source

| Area | Existing baseline | Target changes to integrate |
| --- | --- | --- |
| Android | Live TV, source pinning, downloads and appearance extras | Updated platform storage/settings, rating integration, player overlays and subtitle-sync UI |
| iOS | Enhanced host/player portions | iPad navigation/Live TV visibility, native Now Playing controls, improved MPV PiP/cache/fonts and download handling |
| Shared/common | Enhanced feature overlay | New user-ratings and visual subtitle-sync features, metadata-cache clearing |
| UI/navigation | Floating/classic bars and theme settings | Tablet inline labels, top-positioned bars, settings subpage retention, gradient accents and dialogs |
| Playback | Volume boost, HLS quality chooser, controls | Recommendation timing/dismissal/watched filtering, retained quality/PiP/info controls, visual subtitle synchronization |
| Live TV | Playlist sources, groups, EPG | Sticky search/filter header, playlist file picker and platform layout fixes |
| Settings | Device/profile settings | Keep Enhanced-only appearance/navigation choices device-local; expose clear metadata cache |
| Ratings | OMDB episode ratings and enriched metadata | In-app ratings synchronized with Trakt/Simkl/MDBList, stars and animated rating sheets |
| Profiles | Avatar access, Profile Insight | Taste DNA chart, distinct-title statistics, consistent cross-device insights and persistent title facts |
| Library/calendar | Existing calendar and lists | Denser adaptive grids, manual refresh/error states, avoid caching failed release lookups |
| Localization | Existing Enhanced strings | New-feature English strings plus official locale updates; reapply StreamBridge-visible name in every locale |
| Performance | Existing background loading | Off-main-thread insight statistics, API-limited background lookups, persistent metadata facts |
| Bug fixes | Baseline tests/custom fixes | Autofill text fields, iOS audio-session controls, settings/nav previews and collection layout |
| Build/release | Upstream Android + unsigned iOS release | StreamBridge-specific identity, signed iOS archive/export support and one coordinated draft release |

## Constraints and validation policy

- Keep GPL-3.0, upstream copyright/attribution and Noto font OFL notices.
- Keep technical `com.nuvio.*` Kotlin packages and compatible serialized/storage
  keys where changing them would break data, native bindings or plugins.
- Do not publish an upstream-branded IPA or use upstream Apple team identifiers.
- Do not commit keystores, Apple certificates/profiles/private keys or API secrets.
- Android production retains its established certificate; validation APKs are
  clearly marked non-production.
- iOS validation must resolve dependencies and build Release without signing on
  macOS; unsigned validation does **not** prove a signed IPA release.
- Actual runtime checks require a device/emulator and configured test sources.
  Source review and compilation must not be reported as runtime success.
- This local environment is Linux, initially without JDK/Android SDK/Xcode.
  Maven/Google/Gradle downloads failed with TLS/network errors. GitHub refuses
  signing-secret metadata access (403), so secret availability is **unknown**,
  not assumed absent. CI/signing results will be recorded separately.

## Reviewed local conflicts and released non-runtime policies

- Greek/Norwegian resources: use updated translations, retain StreamBridge's
  name. English resources retain privacy/credit additions while adopting the
  expanded MDBList tracking description.
- Fetch bridge: add upstream binary request/response support without restoring
  blocking `runBlocking` inside the async bridge; continue propagating coroutine
  cancellation instead of converting it into a network response.
- MDBList config generation: adapt the new upstream client ID into the fork's
  tracked Gradle inputs, optional local-property configuration and escaped Kotlin
  literals. Do not refer to upstream's incompatible task-local `props` variable.
- Version and signing: keep the established Android application ID and production
  certificate. Use StreamBridge metadata for both platforms, never an upstream
  version fallback or Apple team ID.
- A read-only review of the released non-CloudStream UI policies in `0.1.06`
  identified fetched episode-rating precedence and avatar accessibility labels.
  These small policies are explicitly adapted to the target implementation,
  including all three episode layouts and new pure policy tests. No later source
  tree/CloudStream commit is copied, merged or cherry-picked.
- AGP 9.2 does not support shrinking ABI-split APKs and an unsplit AAB in the same
  invocation. CI and production build them separately, stage APKs first and clean
  only generated application-module output before the bundle build. R8 stays on.

## Initial hosted validation findings (not final success evidence)

Run `36923648498` at `741f830` compiled Android Kotlin and completed its R8
minification task, but the combined split-APK/AAB invocation failed before release
packaging. The next source commit separates those layouts.

The same run's iOS job was incorrectly green because a default-shell pipeline
returned `tee`'s status. The actual Xcode log says **ARCHIVE FAILED**:
`linkReleaseFrameworkIosArm64` exhausted Java heap during Kotlin 2.4.10
DevirtualizationAnalysis. Its 31,553-byte artifact contains **only the build log**,
not an archive or IPA. This run is NOT iOS build/IPA proof. Native compile and
package resolution progressed, but no full Release link/archive completed.

The validation workflow now runs Bash with `pipefail`, separates build reports
from app artifacts, requires actual archive/IPA outputs on successful builds,
and uses explicit 8 GiB Gradle/Native compiler arguments without disabling
Release optimization. The unsigned IPA itself is now validated after packaging.

An authorized attempt to dispatch the signed test-build workflow returned
GitHub HTTP 403 (`Resource not accessible by integration`). No signed test run
was created, and Apple secret availability remains unknown. Signing credentials
were not requested, printed or fabricated; dispatch requires reconnecting the
Arena GitHub integration with the necessary Actions access.

The actual native compiler also warned that `playlistText as NSString` can never
succeed in the temporary HLS quality-playlist writer. That conversion now uses
`NSString.create(string = playlistText)`, the same already-compiled target-source
pattern used in title-facts and subtitle storage. Filename/storage conventions
and UTF-8 output stay compatible. A native regression test is added; it has not
been executed in this Linux workspace and must run after toolchain access resumes.
