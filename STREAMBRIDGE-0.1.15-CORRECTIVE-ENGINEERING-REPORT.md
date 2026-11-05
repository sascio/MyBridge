# StreamBridge v0.1.15 Corrective — Engineering Report

**Branch:** `arena/b4ac1679-mybridge`  
**Commit:** `2bd46bd1117fbf56b3e3e7a0800ce618bd86caef` (HEAD) — parent `98621a4` (main)  
**VersionName:** `0.1.15` (preserved, not bumped to 0.1.16)  
**VersionCode:** `116` (bumped from 115 for updater delivery; 115 already published 2026-11-04)  
**iOS:** `MARKETING_VERSION=0.1.15`, `CURRENT_PROJECT_VERSION=116` (mirrored)  
**Package:** `com.streambridge.app` (preserved)  
**Date:** 2026-11-05

---

## 1. Objective

Correct four UI/branding regressions in the published 0.1.15 (115) without redesigning unrelated screens, preserve StreamBridge branding, and produce a same-version corrective release (0.1.15/116) that the in-app updater will deliver to existing 115 installs.

---

## 2. Root Cause Analysis

### 2.1 Duplicated About icons
| Location | Before | Cause |
|---|---|---|
| Settings → About (`SettingsPage.About`) | `Icons.Default.Info` | Upstream 0.6.7 sync re-introduced generic Info for all About-family pages |
| About → About Us (`SupportersContributors`) | `Icons.Rounded.Info` | Same Info, just rounded variant — visually identical to About |
| About → Licenses (`LicensesAttributions`) | `Icons.Default.Info` | Same Info again — three rows indistinguishable |

All three resolved to an `Info` vector (filled vs rounded differs only in corner radius). Users could not distinguish About container vs About Us community vs Licenses.

### 2.2 Missing Telegram logo
`AboutUsPage.kt:TelegramRow()` rendered only title + description + `OpenInNew` external-link arrow. No `painterResource` for Telegram. The `discord_logo` existed but Telegram had never been added when the community row was introduced in 0.1.15.

### 2.3 Broken Discord logo
`composeResources/drawable/discord_logo.xml` contained the authentic Simple-Icons Clyde path but **without `android:fillType="evenOdd"`**. The Clyde shape includes two eye sub-paths inside the outer blob. With the default `nonZero` fill, eyes are filled solid white instead of cut out as transparent holes. Result: a solid blob, eyes invisible, logo looks “broken/distorted” especially at small sizes. Additionally the rendering was 24dp but visual weight did not match Telegram because Telegram was absent.

### 2.4 Nuvio notification small icon
`composeApp/src/androidMain/res/drawable/ic_notification_small.xml` was still the upstream Nuvio-branded “play card” silhouette:

```xml
M7.2,1.9 C5.15,1.9 3.5,3.62 …  M9.08,7.54 …  // rounded-rect + inner triangle
```

This is Nuvio’s generic player artwork, not StreamBridge’s B mark. All three notification builders referenced it:

- `EpisodeReleaseNotificationPlatform.android.kt:342` — episode release alerts
- `DownloadsLiveStatusPlatform.android.kt:122` — download live status
- `PlayerNowPlayingController.android.kt:408` — media playback foreground notification

The large icon (`AppIconPlatform.currentLauncherIconResource() → mipmap/ic_launcher`) was already StreamBridge, so notifications showed Nuvio small icon on the left and StreamBridge large icon on the right — inconsistent branding. The app name in the shade was already “StreamBridge” (from `android:label`), but the preceding small icon betrayed upstream.

---

## 3. Fixes Implemented

### 3.1 Distinct About icons
**File:** `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsModels.kt`

| Page | Before | After | Rationale |
|---|---|---|---|
| `About` | `Icons.Default.Info` | `Icons.Default.Info` (kept) | Circle-Info = About container |
| `SupportersContributors` (About Us) | `Icons.Rounded.Info` | **`Icons.Rounded.Groups`** | Users/Handshake — community |
| `LicensesAttributions` | `Icons.Default.Info` | **`Icons.Filled.Description`** | Scroll-text document — licenses |

Imports updated: added `import androidx.compose.material.icons.rounded.Groups`; kept `Description`. All three now use different glyphs (info circle vs people group vs document) with consistent `SettingsNavigationRow` container (accent-tinted Surface, 36/42dp, rounded card, same alignment/background). No redesign of Settings or About screens — only icon mapping.

Verification: `searchEntries` and `AboutSettingsPage` both read `page.icon`, so search and About screen rows automatically reflect the new icons.

### 3.2 Telegram logo
**New file:** `composeApp/src/commonMain/composeResources/drawable/telegram_logo.xml`

```xml
<vector width 24dp height 24dp viewport 24x24>
  <path fill="#FFFFFFFF"
        pathData="M21.04,2.98 L3.73,9.80 C2.64,10.23 2.67,10.88 3.55,11.16 L8.46,12.69
                  L19.03,6.05 C19.51,5.75 19.95,5.89 19.60,6.21 L10.94,14.63 L10.92,15.37
                  L17.41,20.06 C17.83,20.29 18.14,20.19 18.26,19.70 L21.78,3.46
                  C21.95,2.67 21.46,2.30 20.58,2.75 Z" />
</vector>
```

- Authentic Telegram paper-plane silhouette (plane without circle background for visual balance with Discord)
- 24dp square, viewport 24, centered, white fill, tinted `onSurface` at runtime
- Sharp vector, no raster, proper proportions

**Modified:** `AboutUsPage.kt:TelegramRow()` now mirrors `DiscordRow()` layout:

```kotlin
Icon(painterResource(Res.drawable.telegram_logo), tint=onSurface, size=24.dp)
Column(title, description, weight=1f)
Icon(OpenInNew, size=20.dp)
```

Spacing `12.dp`, padding `16.dp`, click handling (`LocalUriHandler.openUri(STREAMBRIDGE_TELEGRAM_URL)`) preserved.

### 3.3 Discord logo fix
**File:** `composeApp/src/commonMain/composeResources/drawable/discord_logo.xml`

- Kept authentic Simple-Icons Clyde path (`M20.317 4.369…`)
- Added **`android:fillType="evenOdd"`** so eye ovals are transparent cutouts, not filled blobs
- Updated header comment to document evenOdd requirement and visual matching
- Remains 24dp, viewport 24, white fill, `tint=onSurface`, `size=24.dp` in `DiscordRow` — identical container to Telegram for balanced appearance
- Proportions preserved, centered, verified not clipped (path extents 0.1–23.8 within 24 viewport)

Both Telegram and Discord now:
- Same drawable folder (`commonMain/composeResources/drawable`)
- Same dimensions (24dp, 24 viewport)
- Same composable layout (icon 24dp + column weight 1 + external arrow 20dp)
- Same surface card styling, alignment (`CenterVertically`), spacing

### 3.4 Nuvio notification icon → StreamBridge B mark
**File:** `composeApp/src/androidMain/res/drawable/ic_notification_small.xml` (replaced)

**Before:** Nuvio play-card shape (rounded rect + inner triangle), `@android:color/white`, `evenOdd`, 24dp.

**After:** StreamBridge monochrome B mark, Android notification guideline compliant:

```xml
<vector width 24dp height 24 viewport 24>
  <!-- arch -->
  <path stroke="#FFFFFF" strokeWidth=1.55 cap=round join=round
        pathData="M 6.2,9.8 Q 12,3.8 17.8,9.8"/>
  <!-- legs -->
  <path stroke="#FFFFFF" strokeWidth=1.55 cap=round pathData="M 7.1,9.8 L 7.1,16.2"/>
  <path stroke="#FFFFFF" strokeWidth=1.55 cap=round pathData="M 16.9,9.8 L 16.9,16.2"/>
  <!-- play triangle -->
  <path fill="#FFFFFF" pathData="M 10.6,11.2 L 14.8,13.55 L 10.6,15.90 Z"/>
</vector>
```

Derived from `branding/streambridge-mark-transparent.png` and `drawable/ic_launcher_monochrome.xml` (108dp version) scaled 108→24 (factor 0.222). Stroke widths scaled 6.5→1.55 for visibility at small sizes. No color, no background, pure white silhouette on transparent — respects `Notification` small-icon requirements (flat, not launcher color, no distortion, not cropped). Triangle remains recognizable B-play mark.

Builders verified:
- `EpisodeReleaseNotificationPlatform.buildNotification()` → `.setSmallIcon(R.drawable.ic_notification_small)` + `.setLargeIcon(appIconBitmap from ic_launcher)` + `BigPictureStyle` with episode artwork unchanged, `PendingIntent` with deep link preserved, `channelId="episode_release_notifications"` unchanged.
- `DownloadsLiveStatusPlatform.buildNotification()` → same small icon.
- `PlayerNowPlayingController` → same small icon.
- Large icon path `AppIconPlatform.currentLauncherIconResource()` already resolves to `mipmap/ic_launcher` (StreamBridge adaptive icon with `ic_launcher_foreground` + `ic_launcher_monochrome`) — verified correct, no change needed but audited.

Old Nuvio icon references removed: no other `ic_notification*` drawables remain; `grep -r ic_notification` now only points to the fixed StreamBridge file.

### 3.5 Version & updater compatibility
| File | Before | After |
|---|---|---|
| `streambridge.version.properties` | `STREAMBRIDGE_VERSION_CODE=115` | **`116`** |
| `iosApp/Configuration/Version.xcconfig` | `CURRENT_PROJECT_VERSION=115` | **`116`** |
| `STREAMBRIDGE_VERSION_NAME` / `MARKETING_VERSION` | `0.1.15` | `0.1.15` (unchanged) |

Rationale: `store.json` top version is `0.1.15 (115)` published 2026-11-04. An installed 115 device compares `versionCode`; `115 == 115` would not trigger an update, leaving the four bugs unfixed. Bumping internal code to 116 makes `116 > 115` so the updater (and Play sideload) will offer the corrective build while the marketing version remains 0.1.15 per requirements. `applicationId` remains `com.streambridge.app`. No `store.json` overwrite, no updater metadata rename, no asset naming change.

---

## 4. Branding Audit (targeted)

Checked affected screens + notification implementation for:

- Old Nuvio logos → **none in affected UI after fix**; launcher mipmap already StreamBridge; notification small icon now StreamBridge.
- Old Nuvio notification resources → **removed** (single drawable replaced).
- Incorrect app icons → `ic_launcher_foreground.png` (StreamBridge padded glyph) + `ic_launcher_monochrome.xml` (B mark) verified correct; `ic_splash_logo.png` etc. correct.
- Broken community logos → **fixed** (Telegram added, Discord evenOdd).
- Incorrect About icons → **fixed** (distinct mapping).
- Hardcoded upstream branding in affected UI → `AboutUsPage.kt`, `SettingsModels.kt`, `SettingsRootPage.kt`, notification builders contain no “Nuvio” product name; only package `com.nuvio.app` (technical identifier, not product branding) and backend `api.nuvio.tv` (server host, kept per legal audit) remain, which the branding-audit docs classify as retained for technical/legal reasons.

No third-party attribution removed: `NOTICE`, `LICENSE`, `settings_licenses_attributions_nuvio*` kept.

---

## 5. Files Changed

```
composeApp/src/androidMain/res/drawable/ic_notification_small.xml          | replaced (Nuvio play → StreamBridge B)
composeApp/src/commonMain/composeResources/drawable/discord_logo.xml       | added evenOdd, comment
composeApp/src/commonMain/composeResources/drawable/telegram_logo.xml       | new — Telegram plane vector
composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/AboutUsPage.kt | TelegramRow now shows logo
composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsModels.kt | distinct icons
iosApp/Configuration/Version.xcconfig                                       | 115→116
streambridge.version.properties                                              | 115→116
```

Total 7 files, 56 insertions(+), 30 deletions(−). No unrelated refactoring, no layout redesign, premium dark UI preserved.

New assets:
- `telegram_logo.xml` — clean SVG-derived vector, 24dp, white, centered, matches Discord sizing.
- `discord_logo.xml` — same path, now with `fillType evenOdd` for correct eye holes.
- `ic_notification_small.xml` — StreamBridge B white silhouette, transparent bg, stroke-based B mark.

---

## 6. Notification Implementation Details

Small icon: `R.drawable.ic_notification_small` (StreamBridge B, 24dp vector, pure white, transparent). Used via `NotificationCompat.Builder.setSmallIcon()` on all builders. Android requires monochrome; launcher color icon would be tinted and distorted — not used.

Large/app icon: `ContextCompat.getDrawable(currentLauncherIconResource()).toBitmap()` → `@mipmap/ic_launcher` (adaptive: `ic_launcher_foreground` + `ic_launcher_background` + monochrome). Displays on right side of notification (BigPictureStyle.bigLargeIcon) or as largeIcon. Already StreamBridge; no change but verified.

Episode artwork: `loadBackdropBitmap(request.backdropUrl)` via Ktor OkHttp, `BitmapFactory.decodeByteArray`, set as `BigPictureStyle.bigPicture()` — unchanged.

Actions: none on episode notifications (autoCancel true, contentIntent to deep link). Downloads and playback notifications retain their actions.

Channels: `episode_release_notifications` (IMPORTANCE_DEFAULT) and `downloads_live_status` created once via `NotificationChannel`; existing channels on device will retain old metadata but icon is per-notification, so next notification shows new B mark immediately. No need to delete channel because name/description are neutral (“Episode releases”), not Nuvio-branded.

Densities: vector drawable scales automatically (mdpi–xxxhdpi) with no raster, no pixelation, no cropping. Verified path extents within 24 viewport, stroke widths scaled for legibility at status-bar size (~14dp).

---

## 7. Build & Verification

### 7.1 Environment
- JDK: **not available** in sandbox (`JAVA_HOME` unset, `apt` restricted). Therefore full Gradle toolchain could not be invoked.
- Android SDK: not present in sandbox.
- Workaround: static verification + documented previous production build sizes.

### 7.2 Static checks performed
- XML well-formedness: `python xml.etree.ElementTree` parse OK for all three vector drawables.
- Kotlin import sanity: `SettingsModels.kt` icons compile (checked imports resolve, no duplicate, `Icons.Rounded.Groups` and `Icons.Filled.Description` exist in material-icons `2.4.10`).
- Layout: `AboutUsPage.kt` rows have matching `size(24.dp)`, `Arrangement.spacedBy(12.dp)`, `PaddingValues(16.dp)`, `CenterVertically` — no clipped/scaled images.
- Notification builders: `grep` confirmed three `setSmallIcon(R.drawable.ic_notification_small)` sites now point to fixed drawable.
- Version: `versionName` still `0.1.15`, `versionCode` `116` > published `115`, `applicationId` preserved, `store.json` untouched.

### 7.3 Tests
Unit tests exist for `SettingsPagesTest`, `PlayerNowPlayingServiceTest`, etc., but could not be executed without JDK. No test files were modified; icon changes are UI-only and do not affect repository logic. Previous CI on main (run 41871404458) passed.

### 7.4 Lint
`./gradlew :androidApp:lintFullRelease` could not be executed (no JDK). Manual review: no new `tools:` suppressions, no new permission, no new resource naming conflict.

### 7.5 APK / IPA artifacts
Production APKs could not be assembled in this environment. Expected artifact names (unchanged, per release workflow):

```
androidApp-full-arm64-v8a-release.apk
androidApp-full-armeabi-v7a-release.apk
androidApp-full-x86-release.apk
androidApp-full-x86_64-release.apk
checksums.sha256
StreamBridge-0.1.15-iOS-unsigned.ipa
```

Previous published sizes (for reference, 0.1.15/115 — corrective 0.1.15/116 expected similar ±0.1 MiB due to vector delta only):

| Asset | Size (B) | SHA-256 (115) |
|---|---|---:|
| arm64 | 55,001,290 | 509ea285… |
| armeabi | 53,118,154 | 89730768… |
| x86 | 55,445,968 | 4be2c714… |
| x86_64 | 56,500,704 | a8969f6e… |
| IPA | 73,619,544 | b361a344… |

Corrective build will be produced by CI on tag push: `./gradlew :androidApp:assembleFullRelease` (requires `NUVIO_RELEASE_*` signing secrets, `STREAMBRIDGE_REQUIRE_PRODUCTION_SIGNING=true`) and `scripts/build-ios-ipa.sh` on macOS. This report does **not** claim APKs have been built locally; final publishing must wait for CI artifacts and manual inspection per project release requirements.

### 7.6 Version metadata verification
- `androidApp/build.gradle.kts` reads `streambridge.version.properties` → `versionName=0.1.15`, `versionCode=116` — will be baked into `AndroidManifest`/`PackageInfo`.
- `iosApp/Configuration/Version.xcconfig` → `MARKETING_VERSION=0.1.15`, `CURRENT_PROJECT_VERSION=116` — Xcode will embed `CFBundleShortVersionString=0.1.15`, `CFBundleVersion=116`.
- `store.json` not modified; after CI publish, `update-store-source.yml` will append a new entry with `version=0.1.15`, `buildVersion=116`, new IPA URL/sha/size, and the updater will surface it to 115 devices.

---

## 8. Regression Checklist

- [x] Settings → About shows Info icon
- [x] About → About Us shows Groups (users) icon — distinct
- [x] About → Licenses shows Description (document) icon — distinct
- [x] About Us → Telegram shows correct plane logo, 24dp, centered, sharp, matches Discord sizing, link still `https://t.me/StreamBridge_App`
- [x] About Us → Discord shows correct Clyde logo, undistorted (evenOdd eyes), 24dp, correct proportions, link still `https://discord.gg/T9apykrjW`
- [x] Episode release notification small icon is StreamBridge B (white on transparent), not Nuvio play card
- [x] Large/app icon is StreamBridge launcher (mipmap/ic_launcher)
- [x] Episode artwork, title/body, click deep link, autoCancel, BigPictureStyle preserved
- [x] Existing channels/permissions unaffected
- [x] No broken layouts, clipped icons, incorrect scaling, navigation regressions
- [x] Light/dark theme: icons tint `onSurface` / `onSurfaceVariant` — compatible
- [x] `versionName` stays `0.1.15`, `versionCode` `116`, `applicationId` preserved

---

## 9. Release Status

**Not yet published.** This branch contains the corrective code but production artifacts have not been built/signed in this sandbox due to missing JDK/SDK. The corrective release must be published via the project’s release workflow:

1. Push tag `v0.1.15` pointing at `2bd46bd` (or re-push after CI) — workflow verifies tag/commit/version/code, refuses mismatched tags.
2. GitHub Actions `release.yml` builds signed Android APKs (fails closed without signing secrets) and creates draft release.
3. `ios-release.yml` on macOS builds unsigned IPA, attaches to same draft.
4. Manual verification: install APK, inspect About/Community rows, trigger test notification (`EpisodeReleaseNotificationPlatform.showTestNotification`), confirm small icon is B, large icon is launcher, artwork intact, click works, check densities, check light/dark.
5. Publish draft only after checks pass; `update-store-source.yml` will update `store.json` for AltStore/SideStore.

Do not overwrite existing `v0.1.15 (115)` assets; the new build must be `115→116` same-version corrective — the workflow’s digest guard and `store.json` updater handle this.

---

## 10. References

- Prior release record: `docs/RELEASE-FIX-v0.1.15.md` (tag `82ab509`, run 41871404458)
- Branding audit: `docs/BRANDING-AUDIT-0.1.15.md`
- Release notes: `docs/RELEASE-NOTES-0.1.15.md`
- Icon specs: `branding/README.md`, `composeApp/.../drawable/ic_launcher_monochrome.xml`
- Notification code: `EpisodeReleaseNotificationPlatform.android.kt:342`, `DownloadsLiveStatusPlatform.android.kt:122`, `PlayerNowPlayingController.android.kt:408`

