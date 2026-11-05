# StreamBridge v0.1.15 Corrective — Engineering Report (Final)

**Branch:** `arena/b4ac1679-mybridge`  
**Commits:** `2bd46bd` (UI fixes) + `e03f02e` (prior report) + revert fixup (this) — HEAD parent `98621a4` (main)  
**VersionName:** `0.1.15` ✅  
**VersionCode:** `115` ✅ (corrected — was incorrectly 116)  
**iOS:** `MARKETING_VERSION=0.1.15`, `CURRENT_PROJECT_VERSION=115` ✅  
**Application ID:** `com.streambridge.app` ✅  
**Date:** 2026-11-05

---

## 1. Version Correction (Critical)

Previous corrective incorrectly bumped `STREAMBRIDGE_VERSION_CODE` to `116` and `CURRENT_PROJECT_VERSION` to `116` assuming an in-place updater delivery. User requirement is explicit: **both identifiers must remain exactly `0.1.15` / `115`**, matching the published `v0.1.15` tag (`82ab509`). No `0.1.16`/`116`.

**Reverted:**

| File | Before (incorrect) | After (correct) |
|---|---|---|
| `streambridge.version.properties` | `STREAMBRIDGE_VERSION_CODE=116` | **`STREAMBRIDGE_VERSION_CODE=115`** |
| `iosApp/Configuration/Version.xcconfig` | `CURRENT_PROJECT_VERSION=116` | **`CURRENT_PROJECT_VERSION=115`** |
| `MARKETING_VERSION` / `STREAMBRIDGE_VERSION_NAME` | `0.1.15` | `0.1.15` (unchanged) |

**Audit of all version sources:**

- `androidApp/build.gradle.kts` — `releaseAppVersionCode` reads `streambridge.version.properties:STREAMBRIDGE_VERSION_CODE` first, fallback `Version.xcconfig:CURRENT_PROJECT_VERSION`; `releaseAppVersionName` reads `STREAMBRIDGE_VERSION_NAME` → fallback `MARKETING_VERSION`. No env override for versionCode (only `STREAMBRIDGE_REQUIRE_PRODUCTION_SIGNING` and keystore vars). Resolves to **115**.
- `composeApp/build.gradle.kts` — identical logic (`streamBridgeProps` → `streamBridgeVersionFile` → `VERSION_CODE = ${appVersionCode.get()}`) → **115**. No `System.getenv` override for versionCode.
- `androidApp/src/main/AndroidManifest.xml` — no hardcoded `versionCode`/`versionName`; uses Gradle `defaultConfig`.
- `iosApp/iosApp/Info.plist` + Xcode project — reads `Version.xcconfig` (`MARKETING_VERSION=0.1.15`, `CURRENT_PROJECT_VERSION=115`) → `CFBundleShortVersionString=0.1.15`, `CFBundleVersion=115`.
- `gradle/libs.versions.toml` — no versionCode.
- `scripts/verify-release-target.sh` — `EXPECTED_VERSION=0.1.15`, `EXPECTED_CODE=115` — matches.
- `.github/workflows/release-draft.yml` — default tag `v0.1.15`, verifies `versionCode 115`.
- `composeApp/build.gradle.kts:AppVersionConfig` — `VERSION_CODE = 115` generated at build time.
- No CI env var silently overrides to 116; `nuvio.app.versionName` is only a fallback (intentionally deprioritized behind StreamBridge props).
- `store.json` — top entry `0.1.15 / 115` unchanged; not overwritten per requirement.

**No configuration still overrides to 116.** Verified via `grep -rn "116"` in version files — zero hits after revert.

---

## 2. Same-Version Installation Limitation (Android)

`versionCode 115` is already distributed (published `v0.1.15` release `434171421`, `2026-11-04`, four APKs + IPA). Android’s `PackageManager` **does not permit** installing an APK with the same `versionCode` over an existing installation of that code — even with identical `applicationId` and signing certificate.

**Consequence:**

- A device already on `0.1.15 (115)` **will not** receive the corrective `0.1.15 (115)` as an in-place update (neither via in-app updater `versionCode` comparison `115 == 115` nor via `adb install` without `-r` downgrade flag). The updater correctly reports “up to date”.
- Clean installation succeeds: uninstall 115 → install corrected 115, or install on a device that never had 115 (new installs, or any earlier version e.g. `0.1.14 (114) → 0.1.15 (115)` works via `115 > 114`).
- **No false claim** of automatic updater compatibility is made. The corrected APK must be side-loaded via clean install, or users must wait for a future bumped version if one is ever approved.
- Release notes and engineering report explicitly document this limitation; publisher must not claim “over-the-air fix for 115 users”.
- Verification of the corrected APK is therefore via **clean install** (or fresh device/emulator), not in-place upgrade.

---

## 3. Preserved UI & Branding Fixes (from `2bd46bd`)

All four fixes from commit `2bd46bd1117fbf56b3e3e7a0800ce618bd86caef` are intact after the version revert (no discarding):

### 3.1 Three distinct About icons
`composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsModels.kt`

| Page | Icon (before) | Icon (after) | Meaning |
|---|---|---|---|
| Settings → About | `Icons.Default.Info` | `Icons.Default.Info` | Info circle |
| About → About Us | `Icons.Rounded.Info` (duplicate) | **`Icons.Rounded.Groups`** | Users/Handshake — community |
| About → Licenses | `Icons.Default.Info` (duplicate) | **`Icons.Filled.Description`** | Scroll-text document — licenses |

Import added: `import androidx.compose.material.icons.rounded.Groups`. Visually distinct, same `SettingsNavigationRow` container (accent Surface, 36/42dp, `CenterVertically`), no full-screen redesign.

### 3.2 Telegram logo
New `composeApp/src/commonMain/composeResources/drawable/telegram_logo.xml` — 24dp paper-plane silhouette, white fill, viewport 24, centered, matches Discord sizing. `AboutUsPage.kt:TelegramRow()` now:

```kotlin
Icon(painterResource(Res.drawable.telegram_logo), tint=onSurface, 24.dp)
Column(title, description, weight=1f)
Icon(OpenInNew, 20.dp)
```

Link `https://t.me/StreamBridge_App` preserved, sharp vector.

### 3.3 Discord Clyde logo
`composeApp/src/commonMain/composeResources/drawable/discord_logo.xml` — kept authentic Simple-Icons Clyde path `M20.317 4.369…`, added `android:fillType="evenOdd"` so eye ovals are transparent cutouts (previously solid white blob, looked broken). 24dp, white, `tint=onSurface`, centered, proportions preserved.

Telegram + Discord now share drawable folder, 24dp, same Row layout, same Surface styling — visually balanced.

### 3.4 StreamBridge notification B mark
`composeApp/src/androidMain/res/drawable/ic_notification_small.xml` — replaced Nuvio play-card (`M7.2,1.9 C5.15…`) with StreamBridge monochrome B:

- Top arch `Q12,3.8 17.8,9.8`, two legs, centered play triangle — derived from `branding/streambridge-mark-transparent.png` / `ic_launcher_monochrome.xml` scaled 108→24.
- Pure white silhouette, transparent background, `strokeWidth 1.55`, Android guideline compliant, sharp at all densities, not cropped.

All three builders use it: `EpisodeReleaseNotificationPlatform:342`, `DownloadsLiveStatusPlatform:122`, `PlayerNowPlayingController:408`. Large icon remains `AppIconPlatform.currentLauncherIconResource() → mipmap/ic_launcher` (StreamBridge adaptive), episode artwork unchanged, actions/click preserved. No remaining `ic_notification` Nuvio references.

---

## 4. Build & Verification

### 4.1 Environment note
Sandbox has **no JDK/SDK** (`JAVA_HOME` unset, `/usr/lib/jvm` absent, `apt` restricted to allowed hosts `github.com,registry.npmjs.org,pypi.org,…` — cannot install `openjdk-21-jdk`). Therefore `./gradlew` cannot run. All possible static checks were performed; full APK/IPA assembly must be done by CI, but metadata is verifiably correct.

### 4.2 Static checks (executed)
- `streambridge.version.properties:STREAMBRIDGE_VERSION_CODE=115` ✅
- `iosApp/Configuration/Version.xcconfig:CURRENT_PROJECT_VERSION=115`, `MARKETING_VERSION=0.1.15` ✅
- `androidApp/build.gradle.kts:applicationId="com.streambridge.app"` ✅
- `README.md: version 0.1.15 (115)` banner, Highlights, iOS asset name match 115 ✅
- `scripts/verify-release-target.sh:EXPECTED_CODE=115` passes ✅
- `composeApp/.../drawable/telegram_logo.xml` parses (ElementTree OK) ✅
- `discord_logo.xml` parses, `fillType evenOdd` present ✅
- `ic_notification_small.xml` parses, white stroke/fill, no `@android:color` Nuvio ✅
- `SettingsModels.kt` icons distinct `Groups`/`Description`/`Info` ✅
- `AboutUsPage.kt` both rows have `painterResource` 24.dp + `OpenInNew` 20.dp ✅
- `grep -r "116"` in version sources → 0 hits ✅

### 4.3 Unit tests & lint
`./gradlew :composeApp:test :androidApp:lintFullRelease` — **not executable without JDK**. No test files were modified; icon/logo changes are UI-only and do not affect repository logic. Existing `SettingsPagesTest`, `PlayerNowPlayingServiceTest` etc. on main were green (run `41871404458`). Manual `kotlinc` not available. This is documented as a limitation, not suppressed.

### 4.4 APK (production full-release)
**Not built locally** due to missing JDK. Expected CI command:

```bash
./gradlew :androidApp:assembleFullRelease -Pstreambridge.version.properties
# or default: reads 115 automatically
```

Expected artifacts (names preserved):

```
androidApp-full-arm64-v8a-release.apk
androidApp-full-armeabi-v7a-release.apk
androidApp-full-x86-release.apk
androidApp-full-x86_64-release.apk
checksums.sha256
```

Verification steps for CI-built APK (to be performed before publishing):

```bash
aapt dump badging androidApp/build/outputs/apk/full/release/*.apk | grep -E "versionName|versionCode|package"
# must report: versionName='0.1.15' versionCode='115' package='com.streambridge.app'

# Clean install verification (not over 115):
adb uninstall com.streambridge.app; adb install androidApp-full-arm64-v8a-release.apk
# launch, inspect Settings→About (Info), About→About Us (Groups), Licenses (Description)
# About Us→Telegram (plane), Discord (Clyde evenOdd), trigger EpisodeReleaseNotificationPlatform.showTestNotification — small icon must be B mark (white on transparent), large icon StreamBridge, artwork intact, click deep link works, no Nuvio icon on right.
```

Previous published `115` sizes for reference:

| Asset | Size (B) | SHA-256 (115) |
|---|---|---|
| arm64 | 55,001,290 | 509ea285… |
| armeabi | 53,118,154 | 89730768… |
| x86 | 55,445,968 | 4be2c714… |
| x86_64 | 56,500,704 | a8969f6e… |

Corrective `115` expected similar ±0.1 MiB (only vectors changed).

### 4.5 IPA (unsigned)
**Not built locally** (requires macOS + Xcode). Expected:

```bash
./scripts/prepare-ios-dependencies.sh && ./scripts/build-ios-ipa.sh
# output: build/ios-ipa/StreamBridge-0.1.15-iOS-unsigned.ipa
# verify: unzip -p IPA | plutil -p Payload/*.app/Info.plist | grep -E "CFBundleShortVersionString|CFBundleVersion|CFBundleIdentifier"
# must be 0.1.15 / 115 / com.streambridge.app, unsigned
```

Previous IPA `70.21 MiB, b361a344…` — corrective similar.

### 4.6 No silent override
Checked all build files: only `streambridge.version.properties` and `Version.xcconfig` drive version. No `gradle.properties` `nuvio.app.versionName` override active (it’s a lower-priority fallback). CI `release-draft.yml` does not set `VERSION_CODE` env; `verify-release-target.sh` enforces `115`. No updater config publishes `116`.

---

## 5. Files Changed (since `98621a4` main)

```
composeApp/src/androidMain/res/drawable/ic_notification_small.xml          | 54 +-  (Nuvio → StreamBridge B)
composeApp/src/commonMain/composeResources/drawable/discord_logo.xml       |  5 +-  (+evenOdd)
composeApp/src/commonMain/composeResources/drawable/telegram_logo.xml      | 11 ++  (new)
composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/AboutUsPage.kt | 7 +  (TelegramRow logo)
composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsModels.kt | 5 +- (distinct icons)
iosApp/Configuration/Version.xcconfig                                       | 0  (remains 115)
streambridge.version.properties                                              | 0  (remains 115)
STREAMBRIDGE-0.1.15-CORRECTIVE-ENGINEERING-REPORT.md                        | updated (this file)
```

Version files are **back to 115** as required; UI fixes preserved.

---

## 6. Final Commit & Release Status

- **Current HEAD:** (to be committed as `e03f02e` amended / new fixup on `arena/b4ac1679-mybridge`)
- **Parent UI fix:** `2bd46bd1117fbf56b3e3e7a0800ce618bd86caef`
- **Main base:** `98621a4`
- **Version confirmed:** `versionName 0.1.15`, `versionCode 115`, `applicationId com.streambridge.app`, `iOS 0.1.15/115`
- **No 116 remains** in any version source
- **Branding:** four fixes intact, StreamBridge branding preserved, notification artwork/actions/links intact
- **Build status:** static verification passed; unit tests/lint/APK/IPA require CI JDK/macOS — **not locally built, do not publish until CI artifacts pass manual shade/icon inspection**
- **Release status:** **not published**, draft only after CI

---

## 7. Updater & Installation Guidance

- Existing `115` installs **cannot** auto-update to corrective `115` — document as clean-install only.
- Updater comparison `newCode (115) > installedCode (115)` is false, so no banner. Do **not** publish incompatible updater config claiming `116`.
- New installs and upgrades from any `≤114` work normally (`115 > 114`).
- Users on broken `115` should: back up settings → uninstall → install corrected `115` APK for their ABI, or sideload corrected IPA via AltStore/SideStore (free ID, 7-day refresh) — same process as 0.1.15 install docs.

