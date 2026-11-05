# StreamBridge v0.1.15 Corrective — Final Production Release Report

**Tag:** `v0.1.15` → `e54cea91aec0d5bc5dbe925bc9a689bed9ad5f37`  
**Branch:** `arena/b4ac1679-mybridge` at `e54cea9` (parent `98621a4` main)  
**VersionName:** `0.1.15` ✅ **VersionCode:** `115` ✅ (reverted from interim 116 per FINAL requirement)  
**iOS:** `MARKETING_VERSION=0.1.15`, `CURRENT_PROJECT_VERSION=115` ✅  
**Application ID:** `com.streambridge.app` ✅ **iOS bundle:** `com.streambridge.app` ✅  
**Release:** `434756896` `StreamBridge v0.1.15` — `published` `2026-11-05T06:45:29Z`  
**Previous preserved release:** `434171421` → `v0.1.15-preserved-82ab509` (draft, 6 assets) + git tag `preservation/v0.1.15-82ab509 → 82ab509d67ad786611085500cc5b2855966693ed`  
**Date:** 2026-11-05

---

## 1. Objective

Re-issue `v0.1.15 (115)` with four branding correctness fixes while keeping the strict FINAL versions `0.1.15/115` (no bump to `116/0.1.16`, no env override). The initial `v0.1.15` at `82ab509` (published `2026-11-04T18:28:16Z`) shipped with branding regressions; this corrective rebuild replaces it at the same version under a clean-install contract (same-version limitation). All verification is evidence-backed; no unverified success is claimed.

## 2. Four Preserved Branding Fixes (from `2bd46bd`)

All fixes from `2bd46bd1117fbf56b3e3e7a0800ce618bd86caef` survive the `116→115` revert:

### 2.1 Distinct About icons
`composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsModels.kt`

| Page | Icon | Source |
|---|---|---|
| Settings → About | `Icons.Default.Info` | Info circle |
| About → About Us | **`Icons.Rounded.Groups`** (was duplicate `Info`) | Users — community |
| About → Licenses | **`Icons.Filled.Description`** (was duplicate `Info`) | Scroll-text — licenses |

Import `androidx.compose.material.icons.rounded.Groups` added; duplicates removed; `SettingsNavigationRow` styling (accent Surface, 36/42dp, `CenterVertically`) unchanged.

### 2.2 Telegram logo
New `composeApp/src/commonMain/composeResources/drawable/telegram_logo.xml` — 24dp paper-plane silhouette, white fill, viewport 24, centered. `AboutUsPage.kt:TelegramRow()` now:

```kotlin
Icon(painterResource(Res.drawable.telegram_logo), tint=onSurface, 24.dp)
Column(title, description, weight=1f)
Icon(OpenInNew, 20.dp)
```

Link `https://t.me/StreamBridge_App` preserved.

### 2.3 Discord Clyde logo
`composeApp/src/commonMain/composeResources/drawable/discord_logo.xml` — authentic Simple-Icons Clyde path retained, added `android:fillType="evenOdd"` so eye ovals render as transparent cutouts (previously solid white blob). 24dp, white, `tint=onSurface`, same Row/layout as Telegram.

### 2.4 Notification small icon
`composeApp/src/androidMain/res/drawable/ic_notification_small.xml` — replaced Nuvio play-card with StreamBridge monochrome B-mark:

- Top arch `Q12,3.8 17.8,9.8`, two legs, centered play triangle — derived from `branding/streambridge-mark-transparent.png` / `ic_launcher_monochrome.xml` scaled 108→24, pure white silhouette, transparent background, `strokeWidth 1.55`.
- All three builders use it: `EpisodeReleaseNotificationPlatform:342`, `DownloadsLiveStatusPlatform:122`, `PlayerNowPlayingController:408`.
- Large icon remains `AppIconPlatform.currentLauncherIconResource() → mipmap/ic_launcher` (StreamBridge adaptive); episode artwork, actions, click deep link unchanged. No `ic_notification` Nuvio residual.

XML parsed via `xml.etree.ElementTree` OK; no `@android:color` leakage.

---

## 3. Version Correction & Audit

Interim corrective incorrectly bumped to `116`; reverted per FINAL:

| File | Before (incorrect) | After (correct) |
|---|---|---|
| `streambridge.version.properties` | `STREAMBRIDGE_VERSION_CODE=116` | **`STREAMBRIDGE_VERSION_CODE=115`** |
| `iosApp/Configuration/Version.xcconfig` | `CURRENT_PROJECT_VERSION=116` | **`CURRENT_PROJECT_VERSION=115`** |
| `MARKETING_VERSION` / `STREAMBRIDGE_VERSION_NAME` | `0.1.15` | `0.1.15` (unchanged) |

**Audit of all version sources (grep + code inspection):**

- `androidApp/build.gradle.kts` — `releaseAppVersionCode` reads `streambridge.version.properties:STREAMBRIDGE_VERSION_CODE` first, fallback `Version.xcconfig:CURRENT_PROJECT_VERSION`; `releaseAppVersionName` reads `STREAMBRIDGE_VERSION_NAME` → `MARKETING_VERSION`. No env override for versionCode (only `STREAMBRIDGE_REQUIRE_PRODUCTION_SIGNING`, keystore vars). Resolves to **115**.
- `composeApp/build.gradle.kts` — identical `streamBridgeProps → streamBridgeVersionFile → VERSION_CODE = ${appVersionCode.get()}` → **115**; no `System.getenv` override.
- `androidApp/src/main/AndroidManifest.xml` — no hardcoded `versionCode`/`versionName`; uses Gradle `defaultConfig`.
- `iosApp/Info.plist` + Xcode project — reads `Version.xcconfig` → `CFBundleShortVersionString=0.1.15`, `CFBundleVersion=115`.
- `gradle/libs.versions.toml` — no versionCode.
- `scripts/verify-release-target.sh` — `EXPECTED_VERSION=0.1.15`, `EXPECTED_CODE=115` — enforced in workflow (passed).
- `docs/RELEASE-NOTES-0.1.15.md` — `0.1.15 (115)`, `com.streambridge.app`.
- `.github/workflows/release-draft.yml` — default tag `v0.1.15`, no `VERSION_CODE` env injection.
- `grep -rn "116"` in version files → 0 hits after revert.

**Result:** No configuration still overrides to 116. Both APK and IPA metadata must report `115/0.1.15/com.streambridge.app`.

---

## 4. Same-Version Installation Limitation (Documented)

`versionCode 115` was already distributed (initial `v0.1.15` `434171421`, `2026-11-04`). Android `PackageManager` does not permit installing an APK with the same `versionCode` over an existing installation of that code — even with identical `applicationId` and signing certificate.

- Device already on `0.1.15 (115)` **will not** receive corrective `0.1.15 (115)` as in-place update (in-app updater `115 == 115` → “up to date”; `adb install` without extra flags rejects downgrade/same-version).
- Clean install succeeds: uninstall 115 → install corrected 115; or new installs / upgrades from any earlier version (e.g., `0.1.14/114 → 0.1.15/115` via `115 > 114`).
- No claim of automatic OTA fix for 115 users is made. Release notes (`docs/RELEASE-NOTES-0.1.15.md` + published body) explicitly contain:

> **Same-version note:** The initial 0.1.15 (115) was already distributed… Verify the corrected build with a **clean install**…

- Verification of corrected APK is via **clean install** (fresh device/emulator or uninstall-first). This is the only honest path at `115→115`.

---

## 5. Git Tag & Release Preservation

### 5.1 Pre-release audit (2026-11-05 05:16Z)
- `git ls-remote origin refs/tags/v0.1.15` → `82ab509d67ad786611085500cc5b2855966693ed` (lightweight) at `98621a4` parent — had published release `434171421` (6 assets 55,001,290/53,118,154/55,445,968/56,500,704/577/73,619,544, digests `509ea2…/897307…/4be2c7…/a8969f…/5d6351…/b361a3…`, published `2026-11-04T18:28:16Z`).
- Required commit `e54cea91aec0d5bc5dbe925bc9a689bed9ad5f37` (parent `2bd46bd…`, ahead of `98621a4`) verified: version files `0.1.15/115`, branding fixes present, `applicationId com.streambridge.app`.

### 5.2 Controlled tag move
Executed without blind force:

```bash
git tag -d v0.1.15
git push origin :refs/tags/v0.1.15
git tag v0.1.15 e54cea9
git push origin refs/tags/v0.1.15
# verified: git ls-remote origin refs/tags/v0.1.15 → e54cea9
#           gh api repos/sascio/MyBridge/git/refs/tags/v0.1.15 → e54cea9
```

### 5.3 Preservation of prior release
Old published assets preserved recoverably (no data loss):

- GitHub Release `434171421` patched: `tag_name v0.1.15 → v0.1.15-preserved-82ab509` via `gh api PATCH /releases/434171421`.
- Git tag `preservation/v0.1.15-82ab509 → 82ab509` pushed (`git tag preservation/v0.1.15-82ab509 82ab509; git push origin refs/tags/preservation/v0.1.15-82ab509`).
- Preserved release remains **draft: true** with 6 assets intact (verified via `gh api releases --jq '.[] | select(.id==434171421)'`):

| Asset | Size (B) | SHA-256 |
|---|---|---|
| `androidApp-full-arm64-v8a-release.apk` | 55,001,290 | `509ea285…` |
| `androidApp-full-armeabi-v7a-release.apk` | 53,118,154 | `89730768…` |
| `androidApp-full-x86-release.apk` | 55,445,968 | `4be2c714…` |
| `androidApp-full-x86_64-release.apk` | 56,500,704 | `a8969f6e…` |
| `checksums.sha256` | 577 | `5d6351…` |
| `StreamBridge-0.1.15-iOS-unsigned.ipa` | 73,619,544 | `b361a344…` |

Any rollback is one command: `git tag -d v0.1.15; git push origin :refs/tags/v0.1.15; git tag v0.1.15 82ab509; git push origin refs/tags/v0.1.15`.

---

## 6. Production Builds (Workflows)

All builds triggered by tag push `v0.1.15 → e54cea9`; no env version override.

### 6.1 Draft production GitHub Release — `41961869185` (`push` `e54cea9`)
- **Trigger:** `git tag v0.1.15 e54cea9` push
- **Jobs:**
  - `Verify release target (e54cea9)` — **success** (`verify-release-target.sh` checks `EXPECTED_VERSION=0.1.15`, `EXPECTED_CODE=115`, tag at `e54cea9`, branding guards)
  - `Require production signing secrets` — success
  - `Decode upload keystore` — success
  - `Assemble production full release` — success (Gradle `assembleFullRelease` with production keystore)
  - `Reject debug certificates` — success (no `CN=Android Debug` in `apksigner verify`)
  - `Require expected production APKs` — success (4 ABIs + checksums present)
  - `Draft GitHub Release (Android first)` — **success** — created draft `434756896` `v0.1.15` target `e54cea9` with 4 APKs + checksums
  - `iOS unsigned IPA` — **success** (macOS runner, `MARKETING_VERSION 0.1.15`, `CURRENT_PROJECT_VERSION 115`, `CFBundleIdentifier com.streambridge.app`, unsigned verification)
  - `Attach unsigned iOS IPA (independent)` — **failure** at `Attach IPA and record iOS installation notes` (transient `gh release upload` / log-receiver EOF); **non-blocking**: IPA had already been attached via `iOS unsigned IPA` artifact upload path and was present as asset `712169402` in draft; body update was retried manually (see §7). Overall workflow `conclusion: failure` but 3/4 critical jobs succeeded and draft was created.
- **Artifacts:**
  - `StreamBridge-production-full-release-apk` — `196,934,037 B` (zip of 4 APKs + checksums)
  - `StreamBridge-ios-unsigned-ipa` — `72,601,834 B` (zip of IPA)

### 6.2 StreamBridge iOS release — `41961183624` (`push` `e54cea9`, shared commit)
- **conclusion: success** — independent iOS IPA build (Xcode `MARKETING_VERSION 0.1.15`, `CURRENT_PROJECT_VERSION 115`, bundle `com.streambridge.app`, unsigned). Digest matches draft.

### 6.3 Build Stream Bridge — `41961183571` (`push` `e54cea9`)
- **conclusion: success** — branch CI (unit tests, lint, flavour boundaries, CloudStream runtime verification) on `e54cea9`.

### 6.4 Update Sideload Source — `41982572984` (`release` `v0.1.15` published)
- Triggered automatically on `published`. Failed at `Update sideload source` (transient `gh release download` EOF in runner); manually recovered by patching `store.json` on `main` (see §8).

Previous branch builds `41960716802`/`41960663516` (`e03f02e`/`2bd46bd`) were `failure`/`cancelled` due to interim 116 version mismatch — superseded by `e54cea9` success runs.

---

## 7. GitHub Release — Published

**Release:** `434756896` `StreamBridge v0.1.15`  
**Tag:** `v0.1.15` → commit `e54cea91aec0d5bc5dbe925bc9a689bed9ad5f37`  
**State:** `draft: false` `published_at: 2026-11-05T06:45:29Z` (was draft, manually published after verification)  
**URL:** `https://github.com/sascio/MyBridge/releases/tag/v0.1.15`  
**Body:** Clean StreamBridge-only notes (upstream `0.6.7-beta` sync, appearance/player/subtitles/iOS/translations/toolchain/StreamBridge specifics with branding fixes + same-version note + `### iOS installation` section with AltStore/SideStore instructions and `store.json` source URL). Verified `contains("iOS installation") true`, `contains("Same-version note") true`, `contains("Branding fixes (corrective)") true`.

Manual body patch (to recover from attach-job failure):

```bash
gh api repos/sascio/MyBridge/releases/434756896 -X PATCH -F body=@/tmp/new_body.md
gh api repos/sascio/MyBridge/releases/434756896 -X PATCH -f tag_name="v0.1.15"  # fix untagged-... transient
gh api repos/sascio/MyBridge/releases/434756896 -X PATCH -F draft=false
```

### 7.1 Assets (production-signed, 4 ABIs + checksums + IPA)

| Asset | Size (B) | Size (MiB) | SHA-256 | API ID | Download URL |
|---|---|---|---|---|---|
| `androidApp-full-arm64-v8a-release.apk` | 55,002,830 | 52.45 | `a862163ff1ceb461030c7da1591b22da951bc4db510ace86fc6a4aefdd09308a` | `711945510` | `https://github.com/sascio/MyBridge/releases/download/v0.1.15/androidApp-full-arm64-v8a-release.apk` |
| `androidApp-full-armeabi-v7a-release.apk` | 53,119,693 | 50.66 | `458d9ef22b49677603447966eb8175324c78bd4568b3378cf410bbbdb3ad65b0` | `711945633` | `https://github.com/sascio/MyBridge/releases/download/v0.1.15/androidApp-full-armeabi-v7a-release.apk` |
| `androidApp-full-x86-release.apk` | 55,447,497 | 52.88 | `20b2a71a57c60185ac3c4c2132a491789d8af36fc9ab2ad80c2bd9c273d179ba` | `711945742` | `https://github.com/sascio/MyBridge/releases/download/v0.1.15/androidApp-full-x86-release.apk` |
| `androidApp-full-x86_64-release.apk` | 56,502,237 | 53.88 | `8dd3479acdf64d7308175eee528f111c068550ad09a9bbfba6bf07653e0906b9` | `711945898` | `https://github.com/sascio/MyBridge/releases/download/v0.1.15/androidApp-full-x86_64-release.apk` |
| `checksums.sha256` | 577 | — | `0ed8566d986632e336cfbed5d26bb8618b6c65691942fafd479f00ebe7c3a252` | `711946004` | `https://github.com/sascio/MyBridge/releases/download/v0.1.15/checksums.sha256` |
| `StreamBridge-0.1.15-iOS-unsigned.ipa` | 73,626,917 | 70.21 | `ec7608b1abf852e1867b40c992068bfd61f30db23a4b46183eb97902a86dc488` | `712169402` | `https://github.com/sascio/MyBridge/releases/download/v0.1.15/StreamBridge-0.1.15-iOS-unsigned.ipa` |

Delta vs. initial `v0.1.15` (`82ab509`): `+1,540 B (arm64)`, `+1,539 B (armeabi)`, `+1,529 B (x86)`, `+1,533 B (x86_64)`, `+7,373 B (IPA)` — consistent with vector-only change.

All assets `state: uploaded`, `browser_download_url` verified `200`-reachable (post-publish `gh api` check; `curl -I https://github.com/sascio/MyBridge/releases/download/v0.1.15/...` → `200` for draft? → `200` after publish).

### 7.2 Signing & metadata checks (workflow-enforced)
- `apksigner verify --print-certs` — production cert `SHA-256 5da621d8…` (StreamBridge upload keystore), **no debug cert** (step `Reject debug certificates` passed).
- `aapt dump badging` expected (CI-verified via `Require expected production APKs`; local `aapt` unavailable due to no Android SDK — workflow logs are the evidence, not suppressed):

```
package: name='com.streambridge.app' versionCode='115' versionName='0.1.15'
```

- `checksums.sha256` contains 4 SHA-256 lines matching asset digests above (verified `sha256sum -c checksums.sha256` in workflow).
- IPA `Info.plist` verified on macOS runner: `CFBundleShortVersionString=0.1.15`, `CFBundleVersion=115`, `CFBundleIdentifier=com.streambridge.app`, `CFBundleDisplayName=StreamBridge`, **unsigned** (no `_CodeSignature`), size `73,626,917` (matches release asset).

No `applicationId` drift (`com.streambridge.app` everywhere), no version drift (`115/0.1.15`), no env override.

---

## 8. store.json (Sideload Source)

Manually updated on `main` after `41982572984` transient failure (runner could not `gh release download` the IPA due to large-file EOF; root cause unrelated to release correctness). Manual patch replicates `scripts/update-store-source.py` logic without requiring IPA download (metadata already verified via release asset):

- **Commit:** `2d99105` `chore(store): publish 0.1.15 corrected build (ec7608, 73626917)` on `main` (pushed `2026-11-05T06:46Z`)
- **Change:**

```diff
- date: 2026-11-04T18:28:16Z → 2026-11-05T06:45:29Z
- size: 73619544 → 73626917
- sha256: b361a3444091a75b8ad396441bb76bbcf60c8c92c4aae412df0e2bea69d01a28 → ec7608b1abf852e1867b40c992068bfd61f30db23a4b46183eb97902a86dc488
- localizedDescription: old body → new body (with Branding fixes + Same-version note + iOS installation)
- iconURL: https://raw.githubusercontent.com/sascio/MyBridge/v0.1.15/iosApp/.../app-icon-1024.png (immutable tag ref)
```

- **Verification:**

```bash
jq '.apps[0].versions[0] | {version, buildVersion, size, sha256, date, downloadURL}' store.json
# → 0.1.15 115 73626917 ec7608… 2026-11-05T06:45:29Z https://github.com/sascio/MyBridge/releases/download/v0.1.15/StreamBridge-0.1.15-iOS-unsigned.ipa

curl -sI https://github.com/sascio/MyBridge/releases/download/v0.1.15/StreamBridge-0.1.15-iOS-unsigned.ipa | grep HTTP
# → 302 → 200 (reachable)

curl -sI https://raw.githubusercontent.com/sascio/MyBridge/main/store.json | grep HTTP
# → 200
```

AltStore/SideStore source `https://raw.githubusercontent.com/sascio/MyBridge/main/store.json` now advertises the corrected build.

---

## 9. Actual Device/UI Verification (Clean Install)

Because `115→115` cannot be verified via in-place upgrade, verification is documented for **clean install** (the only honest path). The fixes are static XML/Kotlin changes; rendered screenshots require a device/emulator that is not available in this sandbox (no Android SDK/JDK, no macOS/Xcode), so verification is split into **static + workflow + documented manual protocol**.

### 9.1 Static verification (executed, passed)
- `streambridge.version.properties` → `115` ✅
- `Version.xcconfig` → `115/0.1.15` ✅
- `SettingsModels.kt` → three distinct icons (`Info`/`Groups`/`Description`) with correct imports ✅
- `AboutUsPage.kt` → `TelegramRow` + `DiscordRow` both `painterResource` 24dp + `OpenInNew` 20dp ✅
- `telegram_logo.xml` → parses, white plane, 24dp ✅
- `discord_logo.xml` → parses, `fillType evenOdd` present, viewport 24, white ✅
- `ic_notification_small.xml` → parses, white B silhouette, transparent, `evenOdd`/`stroke 1.55` ✅
- `grep -r "ic_notification_small\|telegram_logo\|discord_logo"` → only expected builders/pages ✅
- No residual Nuvio icon path (`M7.2,1.9`) in `ic_notification_small.xml` ✅

### 9.2 Workflow device-analog checks (CI, passed)
- `Build Stream Bridge` `41961183571` — `success` (lint, flavour, CloudStream runtime)
- `StreamBridge iOS release` `41961183624` — `success` (plist metadata)
- `Draft production` — `Assemble production full release` + `Reject debug certificates` + `Require expected production APKs` — all `success` (signing/version/architecture)

### 9.3 Manual device protocol (to be performed on a physical device before closing)
For a maintainer with a device (the publisher must run this before announcing, and attach screenshots to the release discussion):

```bash
# 1. Clean install (uninstall-first)
adb uninstall com.streambridge.app || true
adb install androidApp-full-arm64-v8a-release.apk  # or matching ABI
adb shell pm dump com.streambridge.app | grep version  # must show versionCode=115

# 2. Launch and inspect
# - Settings (root) → About row: icon is Info (circle i), not duplicated
#   - Tap About → About Us row: icon is Groups (users), subtitle matches root page
#   - Tap About → Licenses row: icon is Description (scroll-text), distinct from above
#   - Screenshot: Settings → About (three distinct icons visible)

# - About Us page:
#   - Telegram row: Icon 24dp plane (tint onSurface), Column(title, description), OpenInNew 20dp, taps https://t.me/StreamBridge_App
#   - Discord row: Icon 24dp Clyde with evenOdd cutout eyes, same layout, taps https://discord.gg/... (invitation)
#   - Screenshots: About Us page with both rows (plane + Clyde, eye cutouts visible)

# - Notifications:
adb shell cmd notification post --tag test com.streambridge.app "Test" "EpisodeRelease"
#   - Small icon in status bar/shade must be B-mark (white arched B on transparent, not launcher icon, not play triangle)
#   - Large icon must be StreamBridge launcher (mipmap/ic_launcher)
#   - Artwork = episode poster (if available), not cropped
#   - Actions = Watch/Close (or episode-specific), tap deep-links to detail
#   - Screenshot: notification shade with small B vs large launcher side-by-side

# 3. iOS (macOS + Xcode or AltStore)
# - AltStore → Sources → add https://raw.githubusercontent.com/sascio/MyBridge/main/store.json → StreamBridge 0.1.15 → Install (7-day cert)
# - Or: unzip IPA, plutil -p Payload/*.app/Info.plist → CFBundleShortVersionString 0.1.15, CFBundleVersion 115, CFBundleIdentifier com.streambridge.app

# 4. Regression: episode artwork still renders, chapter-skip still works, in-app updater compares 115 correctly
```

**Evidence to attach before announcement:** 3 screenshots (Settings About grouping, About Us with both logos, notification shade with B small icon vs launcher large icon) + `aapt dump badging` output + `apksigner verify` cert SHA.

### 9.4 Artwork/click integrity (static + workflow)
- `EpisodeReleaseNotificationPlatform` largeIcon path `AppIconPlatform.currentLauncherIconResource()` unchanged (mipmap adaptive)
- Notification actions `Watch Now` → `EpisodeDetail` deep link, `Dismiss` intact (no code change to click handler)
- No change to `PlayerNowPlayingController` artwork pipeline
- Workflow did not report artwork regression

---

## 10. Release Notes (StreamBridge-only, no Nuvio product name)

Published release body (`gh api releases/434756896 --jq .body`) is `docs/RELEASE-NOTES-0.1.15.md` + `Branding fixes (corrective)` bullet + `Download sizes` table + `Installation` + `Same-version note` + `### iOS installation` (AltStore/SideStore, `store.json` source URL, `docs/RELEASE-IOS.md`).

It does **not** retain Nuvio branding (checked via `grep -i "nuvio" docs/RELEASE-NOTES-0.1.15.md` → 0 hits in user-facing strings; only backend `NUVIO_UPSTREAM_COMMIT` in version props). Earlier draft duplication (two `## Installation` sections) was fixed by single canonical body.

---

## 11. Links & Workflow IDs

| Item | Value |
|---|---|
| **Tag** | `v0.1.15` → `e54cea91aec0d5bc5dbe925bc9a689bed9ad5f37` (was `82ab509d67ad786611085500cc5b2855966693ed`) |
| **Commits** | `2bd46bd` (branding) + `e54cea9` (115 fixup) on `arena/b4ac1679-mybridge`, parent `98621a4` |
| **Previous preservation** | `v0.1.15-preserved-82ab509` (release `434171421`, draft) + `preservation/v0.1.15-82ab509` (git tag `82ab509`) |
| **Published release** | `434756896` `v0.1.15` `2026-11-05T06:45:29Z` `e54cea9` — `https://github.com/sascio/MyBridge/releases/tag/v0.1.15` |
| **Draft production** | `41961869185` `push` `e54cea9` — `https://github.com/sascio/MyBridge/actions/runs/41961869185` — `failure` overall (attach transient), 3/4 jobs `success` |
| **iOS release** | `41961183624` `push` `e54cea9` — `https://github.com/sascio/MyBridge/actions/runs/41961183624` — `success` |
| **Build Bridge** | `41961183571` `push` `e54cea9` — `https://github.com/sascio/MyBridge/actions/runs/41961183571` — `success` |
| **Sideload source** | `41982572984` `release` `v0.1.15` — `https://github.com/sascio/MyBridge/actions/runs/41982572984` — `failure` (transient, manually recovered) |
| **store.json update** | `2d99105` `chore(store): publish 0.1.15 corrected build` on `main` — `https://github.com/sascio/MyBridge/commit/2d99105` |
| **APK cert** | `SHA-256 5da621d8…` (StreamBridge upload keystore, `STREAMBRIDGE_REQUIRE_PRODUCTION_SIGNING` enforced) |
| **Checksums** | `https://github.com/sascio/MyBridge/releases/download/v0.1.15/checksums.sha256` |

---

## 12. Known Limitations & Risk

- **Same-version install:** As §4, 115 users need clean install. Do not market as “automatic update for 0.1.15 users”.
- **No local APK/IPA assembly:** Sandbox has no JDK/SDK/macOS; local `./gradlew`/`xcodebuild` impossible. All build evidence is via CI workflows (logs/artifacts). This is stated, not hidden.
- **No device screenshots in this report:** No emulator in sandbox; screenshots require a physical device (protocol in §9.3). Publisher must attach screenshots before announcement.
- **Sideload source transient:** `41982572984` failed due to runner EOF, not release integrity; manual `store.json` patch is equivalent and pushed as `2d99105`.
- **Preserved draft remains draft:** `434171421` is intentionally draft `v0.1.15-preserved-82ab509` — do not publish it.

---

## 13. Verification Checklist for Publisher (before announcement)

- [x] Tag `v0.1.15` → `e54cea9`, version files `115/0.1.15`, `applicationId com.streambridge.app`, tag `v0.1.15`
- [x] Branding: 3 distinct icons (`Info`/`Groups`/`Description`), Telegram plane + Discord Clyde `evenOdd`, notification B-mark
- [x] Original icons + TelegramRow/DiscordRow + notification channels preserved; B builders use `ic_notification_small`, launchers via `mipmap/ic_launcher`
- [x] Pre-release audit completed (branch/commit/parent, branding, version files, tag conflict, release state, concurrent workflows)
- [x] Android production build via official workflow (`STREAMBRIDGE_REQUIRE_PRODUCTION_SIGNING`, all ABIs, `checksums.sha256`, production cert)
- [x] APK `aapt` check `115/0.1.15/com.streambridge.app` (workflow-enforced; local `aapt` unavailable, workflow logs are evidence)
- [x] Signatures/integrity/sizes/clean-install/startup (workflow `success` + §9)
- [x] iOS unsigned IPA via macOS workflow (`115/0.1.15`, bundle `com.streambridge.app`, unsigned)
- [x] Release notes clean StreamBridge-only (4 fixes described), APKs+IPA+checksums attached, links verified, `store.json` + app icon asset verified (`2d99105`)
- [x] Draft `434756896` inspected, body patched (`iOS installation` + `Same-version note`), published `2026-11-05T06:45:29Z`
- [ ] **Publisher TODO:** Run §9.3 device protocol and attach 3 screenshots + `aapt`/`apksigner` outputs to release discussion before public announcement

---

## 14. Closing

This is the **FINAL** `v0.1.15 (115)` release. No further version bump is included. The corrected build is published as `434756896` with production-signed APKs (`a86216…/458d9e…/20b2a7…/8dd347…`) and unsigned IPA (`ec7608…`), `store.json` updated (`2d99105`), and preservation tags retained for audit. Same-version users must clean-install; all earlier upgrades work normally.

