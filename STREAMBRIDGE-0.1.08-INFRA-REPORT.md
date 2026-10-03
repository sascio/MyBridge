# StreamBridge 0.1.08 — release-infrastructure alignment report

Branch `arena/01a0fc9f-mybridge` · HEAD `3d5cc5d` (pushed) · PR #180 · 2026-10-03
Reference: official NuvioMobile `5c6028f2` (0.5.5-beta), Enhanced `8b3bd867`
(0.5.5-beta). `main` untouched at `c52a2d72` (0.1.07).

**Status: implementation complete and pushed; the final "after" measurements are
not finished because GitHub authentication in this sandbox failed again (the
`GH_TOKEN` is no longer valid) while the post-change CI runs were still in
flight.** Everything below is either measured, explicitly derived, or explicitly
marked *not measured*.

---

## A. Comparison with the Nuvio 0.5.5-beta release infrastructure

| Area | Nuvio 0.5.5-beta (`5c6028f2`) | StreamBridge before | StreamBridge now |
| --- | --- | --- | --- |
| iOS build | `ios-test-build.yml`, `scripts/build-ios-ipa.sh`, always unsigned, `xcodebuild build` → `ditto` → `zip -qry` | already the same flow | unchanged; kept and documented |
| iOS release asset | unsigned IPA attached to the release | unsigned IPA **withheld** unless Apple secrets existed | unsigned IPA always attached as `StreamBridge-<version>-iOS-unsigned.ipa` |
| Sideload source | `store.json` + `update-store-source.yml` + `scripts/update-store-source.py`, regenerated from the published release, refuses signed IPAs | absent | ported and adapted (StreamBridge identity, extra bundle-id/version guards) |
| Asset pipeline | `generate_app_icon_assets.py`: compose icons at 256 px, iOS `app-icon-1024.png`, all `optimize=True` | assets were full-colour renders at 512/1024/1376 px | same *policy* reproduced by `scripts/optimize-app-assets.py`; the upstream script itself was not copied (it reads an `asset/` tree we do not have and its `wordmark_for()` inlines the icon into the wordmark) |
| Action majors | checkout v6, setup-java v5, setup-gradle v6, cache v5, upload-artifact v7, download-artifact v8 | checkout v5, setup-gradle v4, cache v4, upload-artifact v6, download-artifact v6 | aligned to upstream exactly |
| Caching | `~/.konan` keyed on `gradle/libs.versions.toml`; Gradle cache via setup-gradle | identical | identical **plus** the Xcode version in the key (deliberate deviation, see §B) |
| Kotlin/Native heaps | 8192M daemon / 12288M compiler applied from `build-ios-ipa.sh` | same, adopted in `ca71b609` | unchanged |
| Release concurrency | `concurrency: mobile-release-<ref>` | none on `release-draft.yml` | `streambridge-release-<tag>` added |

Differences kept deliberately: StreamBridge keeps its own versioning
(`streambridge.version.properties`, 0.1.08/108), its repo identity, its artifact
names (`androidApp-full-<abi>-release.apk`), its production Android signing
(`NUVIO_RELEASE_*`, untouched), `android-actions/setup-android@v4` (upstream does
not use it) and `actions/github-script@v8` (newer than upstream's v7).

## B. Build-time analysis

Measured on `macos-26` / Xcode 26.6 / temurin 17, same runner class as upstream.

**iOS, before (run `37117867341` on `ca71b609`, success):**

| Step | Duration |
| --- | --- |
| **Build StreamBridge IPA** | **2916 s (97.3 % of the job)** |
| Cache Kotlin/Native toolchain | 20 s |
| Set up Gradle | 14 s |
| Check out / Java / toolchain probe / runtime properties | 5 + 1 + 5 + 0 s |
| Prepare iOS dependencies | 3 s |
| Summarize + upload IPA | 4 s |
| Post steps | 23 s |
| **Job total** | **2996 s** |

Nuvio's own 0.5.5-beta release run `36757343598`: iOS job 3958 s, build
3867 s (97.7 %). **StreamBridge's iOS build is already 951 s (≈25 %) faster than
Nuvio's on the same runner class.** No Nuvio-derived speed win is available:
workflow overhead is ~80 s and everything else is Kotlin/Native compilation and
linking plus Xcode. Reducing it would mean disabling release optimizations or
dropping the framework, which is out of scope by instruction.

Android baseline: run `37117867332`, **142 s** total (assemble 30 s, unit tests
26 s, distribution check 13 s).

**What I changed, and its measured cost:**
1. Kotlin/Native cache key now includes the Xcode version
   (`konan-<os>-<arch>-<xcode_version>-<libs.versions.toml hash>`, restore prefix
   `konan-<os>-<arch>-`). A miss only re-links; it can never reuse platform
   libraries built against a different SDK. This is a deliberate deviation from
   upstream, made because the platform klibs are generated from the installed SDK.
2. Action-major bump. **Measured cost:** the first Android run afterwards
   (`37141023293`, 69ad0a9) took **1410 s** instead of 142 s (assemble 534 s,
   unit tests 400 s) because `setup-gradle@v6` does not share the cache scope of
   `v4`, so that run started from a cold Gradle cache. That is a one-time cost of
   changing the cache implementation, not a slower build; the following run's
   timings are the ones that show the steady state — *not measured (auth)*.
   The bump was not cosmetic: the run's own annotation said
   `Node.js 20 is deprecated … actions/cache@v4, gradle/actions/setup-gradle@v4`.
3. The second Android run on the new commit (`37142277079`, 3d5cc5d) completed
   **success** and the new brand-asset guard step took 3 s.

**Honest conclusion for §B:** I am not claiming a faster build. The iOS build
time is dominated by compilation (97 %), is already faster than upstream's, and
the only build-time change I made costs one cold-cache run and removes two
deprecation warnings plus one stale-SDK cache risk.

## C. IPA size analysis

**Before (measured):** our unsigned IPA artifact `95983592 B` (run
`37117867341`). Nuvio's published `nuvio-0.5.5-full-release.ipa`
`66647562 B`. Difference **+29 336 030 B (+44 %)**.

Component split — *derived* from the pinned trees, because the GitHub artifact
and release CDNs are unreachable from this sandbox (`EOF` /
`Bad credentials`), with the real per-component breakdown to come from the new
CI step:

| Component | StreamBridge | Nuvio 0.5.5-beta | Delta |
| --- | --- | --- | --- |
| `composeResources/drawable/` | 12 840 754 B | 815 715 B | **+12 025 039** |
| iOS `AppIcon*.appiconset` (6 icons) | 5 281 285 B | 779 669 B | **+4 501 616** |
| `SubtitleFonts/` vs `Resources/Fonts/` | 17 011 043 B | 16 441 665 B | **+569 378** |
| `NotoSansCJKsc-Regular.otf` itself | 16 437 364 B | 16 437 364 B | 0 (identical) |
| Extra StreamBridge Swift (`MPVPlayerBridge` + PiP + subtitle resolver) | — | — | source-level, compiled |
| **Attributed to assets** | | | **≈ 17.1 MB of the 29.3 MB** |

The remaining ≈ 12.2 MB is compiled code (StreamBridge ships ~1.1 MB more Kotlin
and ~168 KB more Swift than upstream) and is **not yet measured** — the new
`Report IPA composition` step prints the largest files and the size by directory,
so the next green iOS run will attribute it exactly. That step also emits the
top directories as an annotation, which stays readable through the GitHub API
even when logs cannot be downloaded.

**Fix applied (`scripts/optimize-app-assets.py`, idempotent, `--check` guarded):**

| Asset group | Before | After | Rule |
| --- | --- | --- | --- |
| `app_icon_*` (6 files) | 1 483 643 + 6 × ~293 000 | 7 677–8 170 | 256 px square — the size the picker renders at and the size upstream ships |
| `app_mark_transparent.png` | 691 369 | 33 420 | 512 px, 2× headroom over its 52 dp maximum use |
| 8 wordmark PNGs | 8 923 458 | 342 050 | **dimensions unchanged** (1376×768) |
| iOS `app-icon-1024.png` × 6 | 5 281 285 | 490 235 | exactly 1024×1024, **opaque** (no alpha channel, no `tRNS`) |
| **Total** | **17 886 492 B** | **907 460 B** | **−16 979 032 B (−94.9 %)** |

Nothing was removed: every colourway, both wordmark variants and all six app icons
still exist, and the untouched full-resolution masters remain in `branding/`
(`streambridge-launcher-1024.png`, `streambridge-wordmark-1600.png`,
`streambridge-mark-transparent.png` are byte-identical to the assets they feed).
Quality was checked visually and numerically: PSNR 34.6–38.6 dB at the sizes
actually rendered, with matched mean RGB (e.g. graphite icon 55,60,74 → 55,60,73);
side-by-side renders at 78 dp and 400 px are indistinguishable.

**Expected but NOT YET MEASURED:** an IPA of roughly 79 MB, i.e. the 16.98 MB of
saved assets, because the same PNGs are also inside the Android APKs — our
v0.1.07 APKs are 58.1–61.2 MB against Nuvio's 41.1–44.2 MB, the same ≈17 MB gap.
The exact numbers will come from the next CI run.

## D. Android

- Production signing untouched: the four `NUVIO_RELEASE_*` secrets are still
  required, the workflow still fails closed without them, and a debug-signed APK
  is still rejected (`apksigner` certificate pin `5da621d8…`).
- Variants, artifact names, per-ABI APKs and `checksums.sha256` unchanged.
- Added: one step, `Check the shipped brand assets are optimised` (pip-installs
  Pillow, runs `optimize-app-assets.py --check`, fails CI if a bloated asset is
  committed). Measured at 3 s in run `37141023293`, and green.
- Changed: action majors (checkout v6, setup-gradle v6, upload-artifact v7) to
  clear the measured Node 20 deprecation annotation.
- CI status: run `37141023293` (69ad0a9) success, run `37142277079` (3d5cc5d)
  success. The cold-cache slowness of the first run is explained in §B.

## E. iOS

- The release path now builds the IPA **unsigned, always**: no
  `STREAMBRIDGE_IOS_*` secret is consulted by `release-draft.yml`, and the job
  fails if the application comes out signed.
- Unsignedness is enforced in three independent places: `build-ios-ipa.sh` fails
  if `_CodeSignature` exists; `release-draft.yml` greps the packaged IPA before
  attaching it; `update-store-source.py` refuses to publish a signed IPA.
- Added `Report IPA composition`, keyed the Kotlin/Native cache on the Xcode
  version, extended the trigger paths to `composeResources` and the asset script,
  and replaced the stale "must be re-signed before it can be installed" wording
  with the actual distribution model.
- **Bug found by this CI run and fixed in `3d5cc5d`:** the new toolchain probe
  parsed `xcodebuild -version | head -n 1` under `set -o pipefail`, which fails
  intermittently with SIGPIPE (the PR run of `69ad0a9` failed, the push run of the
  same commit passed). It now parses the first line in bash, and the composition
  report uses `awk` instead of `head -N` for the same reason.
- Run history: `69ad0a9` PR run failed (the bug above), `69ad0a9` push run was
  cancelled by the workflow's own `cancel-in-progress` when `3d5cc5d` was pushed,
  and the `3d5cc5d` push/PR runs were still building when GitHub auth failed.
  **The post-change IPA size and duration are therefore not measured yet.**

## F. AltStore / SideStore source

- `store.json` (repo root): StreamBridge name, `com.streambridge.app`,
  `featuredApps`, tint, description that says the IPA is unsigned, real website,
  and an empty `versions[]` that CI fills in.
- `.github/workflows/update-store-source.yml`: triggers on `release: published`
  (plus `workflow_dispatch`/`workflow_call`), `contents: write`, concurrency
  `sideload-source-<tag>`, checks out the default branch, requires the release to
  be public, requires **exactly one** `.ipa` asset, downloads it and compares its
  size (and `sha256:` digest when the API exposes one) with the release metadata,
  then commits the regenerated `store.json` as `github-actions[bot]`. The release
  channel is `vars.STREAMBRIDGE_SOURCE_BRANCH || default_branch`, overridable.
- `scripts/update-store-source.py`: refuses a signed IPA, requires exactly one
  application `Info.plist` and `MinimumOSVersion`, refuses a bundle id other than
  `com.streambridge.app`, refuses a version that does not match the release,
  harvests `NS*UsageDescription` from the app and its extension, computes size and
  SHA-256, validates an HTTPS `.ipa` URL and a timezoned date, and writes
  atomically. Verified locally against synthetic IPAs: 1 positive case and
  **11 negative cases all correctly rejected** (signed, wrong bundle, wrong
  version, two applications, missing `MinimumOSVersion`, non-HTTPS URL, non-`.ipa`
  URL, date without timezone, empty notes, duplicate version, invalid JSON).
- The `jq` release queries and the `unzip -l` composition pipelines were exercised
  against fixtures; the end-to-end run needs a published release.
- README and `docs/RELEASE-IOS.md` document the source URL
  (`https://raw.githubusercontent.com/sascio/MyBridge/main/store.json`), the
  free-Apple-ID limits (7 days, 3 apps, 10 App IDs per week), and state that there
  is no App Store or TestFlight distribution. StreamBridge branding only; the
  Nuvio README was not copied.

## G. Root causes

1. **IPA size.** StreamBridge's brand PNGs were stored as full-colour renders at
   several times their display size, while upstream's generator writes exactly the
   sizes the UI uses. Cost: +12.0 MB of compose drawables, +4.5 MB of iOS icons.
2. **No iOS artifact in releases.** The release policy treated the unsigned IPA as
   a validation-only artifact, which contradicted the AltStore/SideStore
   distribution model and would have left every release without an iOS asset — and
   `store.json` would have had nothing to point at.
3. **Deprecation warnings.** `actions/cache@v4` and
   `gradle/actions/setup-gradle@v4` still targeted Node 20.
4. **Release race.** `release-draft.yml` had no concurrency group, so two runs for
   one tag could both try to create the tag and the draft.
5. **Intermittent iOS failure.** Introduced by my own `head`-under-`pipefail`
   probe; found by CI and fixed in `3d5cc5d`.
6. **Unattributed ≈12 MB.** Still to be attributed by the composition report.

## H. Remaining work

1. **Needs GitHub auth (blocked):** finish the post-change measurements — iOS IPA
   size/duration/composition annotation (runs `37142277126` / `37142280128` on
   `3d5cc5d`), and the Android artifact-size comparison against the baseline
   (`350 638 196` / `229 471 368` / `678 178 B` for `37116930929`).
2. Then the release itself: tag the verified commit → `release-draft.yml` builds
   the production APKs and the unsigned IPA into a draft → inspect → `gh release
   edit v0.1.08 --draft=false` → `update-store-source.yml` commits `store.json`.
3. Runtime testing is impossible in this environment (no device/emulator) and was
   not performed; nothing here should be reported as runtime-verified.
4. Pre-existing brand debt: 170 strings across 21 locales still say "Nuvio" where
   English says StreamBridge (present since 0.1.07, left untouched).
5. The optional **signed** iOS path is retained in `build-ios-ipa.sh` and
   `ios-release.yml` (unused unless the `STREAMBRIDGE_IOS_*` secrets exist) for
   local use; `release-draft.yml` never signs.

## I. Safety and scope confirmation

- No application code, UI, branding artwork, feature, CloudStream integration or
  versioning was changed. `main` was not touched and nothing was merged into it.
- No Apple signing was added: no `.p12`, no provisioning profile, no App Store
  Connect, no notarization, no enterprise or distribution certificate. No Apple
  credential is required anywhere in the release flow.
- `NUVIO_RELEASE_*` production signing is intact and required; the debug-APK
  rejection is intact; a debug APK can still never be published as a release.
- No framework, architecture, resource or release optimization was removed to
  save space. Assets were re-encoded, not deleted, and masters are preserved.
- No fake or stale caching: the Gradle cache is only read, the Kotlin/Native cache
  is keyed by Kotlin **and** Xcode version, and no release artifact is ever cached.
- Upstream attribution and licenses are untouched; the asset script reproduces
  upstream's *policy* without copying its code or its `wordmark_for()` bug.

## J. Status table

| Item | Status | Evidence |
| --- | --- | --- |
| Sideload source (`store.json`, workflow, script) | ✅ implemented | 1 positive + 11 negative local tests |
| iOS unsigned release artifact | ✅ implemented, build not re-measured | 3 enforcement layers; CI runs pending auth |
| Unattended release flow without Apple credentials | ✅ implemented | `release-draft.yml` unsigned-only |
| Release race conditions | ✅ fixed | concurrency group added |
| Action majors / Node 20 warnings | ✅ fixed | annotation named `cache@v4`, `setup-gradle@v4` |
| Brand assets re-encoded | ✅ measured locally | 17 886 492 → 907 460 B (−94.9 %) |
| IPA size reduction | ⏳ predicted ≈ −17 MB, **not measured** | needs the post-change iOS run |
| iOS build time | ⏳ not measured after change; baseline 2996 s (build 2916 s), 25 % faster than Nuvio | needs the post-change iOS run |
| Android CI | ✅ success | runs `37141023293`, `37142277079` |
| Android build time | ⚠️ first run after the change was cold-cache (1410 s vs 142 s baseline) | one-time cache-scope change |
| iOS CI after the fix | ⏳ unknown | runs were in flight when auth failed |
| Release created / published | ❌ not yet | manual step, documented |
| Runtime validation | ❌ not performed (impossible here) | — |
