# Milestone 1 — Nuvio core as the runnable foundation

**Status: SOURCES IMPORTED AND BRANDED. CI APK PENDING. PLAYBACK = REAL DEVICE VALIDATION REQUIRED.**

Compilation of an APK is not success. This report does **not** claim
device playback, production-ready torrents, or feature parity extras.

## Changed

- Relicensed the repository to **GPL-3.0** (`LICENSE`, `COPYING`, `NOTICE`).
- Gradle root is now NuvioMobile’s graph (`:composeApp` + `:androidApp`),
  wrapper **9.4.1**, AGP **9.2.0**.
- `applicationId` is `com.streambridge.app` (debug and release).
- Visible name is StreamBridge / StreamBridge Debug.
- Default launcher adaptive icon uses the StreamBridge mark.
- CI builds `:androidApp:assembleFullDebug` and
  `:androidApp:assembleFullRelease` (debug-signed when no Nuvio
  keystore is present). Artifacts come from
  `androidApp/build/outputs/apk/full/…`, **not** `app/build/outputs/…`.
- Gradle heap sized for GitHub Actions (6G). Nuvio’s 12–16G iOS native
  heap is not used.
- `nuvio.android.distribution=full` is set in `gradle.properties`.
- Old `:app` moved to `legacy/streambridge-app/` and is not included
  in `settings.gradle.kts`.

## Imported (NuvioMobile `e377942`)

- `composeApp/` including `PlayerEngine.android.kt` (2381 lines) and
  `composeApp/libs/*.aar` (ExoPlayer fork, FFmpeg/AV1/MPEG-H decoders,
  Media3 UI, nuvio-engine 0.1.1, quickjs-kt 1.0.5-nuvio).
- `androidApp/` host, flavors `full` / `playstore`.
- `iosApp/Configuration/Version.xcconfig` (required at Gradle root;
  MARKETING_VERSION=0.4.15, CURRENT_PROJECT_VERSION=120).
- Nuvio `gradle/` version catalog and wrapper.

Kotlin packages remain `com.nuvio.app` / `com.nuvio.android`.

## Adapted (identity / CI only)

- Gradle `rootProject.name` stays `Nuvio` so Compose Multiplatform
  generates `nuvio.composeapp.generated.resources` (185 imports). Visible
  identity is still StreamBridge via applicationId / `app_name`.
- `applicationId` / debug applicationId (was `com.nuvio.app` /
  `com.nuviodebug.com`)
- `app_name` strings
- Default adaptive launcher overlay
- Release signing falls back to the debug keystore when
  `NUVIO_RELEASE_*` is unset (CI would otherwise fail
  `assembleFullRelease`)
- ABI splits only when a real release keystore is present
- Gradle memory / `nuvio.android.distribution=full`

No player rewrite. No extras. No UI redesign.

## Removed / disconnected from runtime

- Custom StreamBridge player stack
  (`PlayerViewModel` → `PlaybackPlanning` → `PlaybackBackendSelector` →
  custom Media3 → custom libmpv) — quarantined under
  `legacy/streambridge-app/`, not compiled.
- CI path `app/build/outputs/apk/…`.

## Build

Local command:

```bash
./gradlew :androidApp:assembleFullDebug
```

GitHub Actions: `.github/workflows/build.yml` runs
`:androidApp:assembleFullDebug` and `:androidApp:assembleFullRelease`.

**CI:** run `34533186538` failed at `:composeApp:generateRuntimeConfigs`
because Gradle 9 requires a specified `@InputFile` to exist and Nuvio
always points at `local.properties`. Adapted: only wire that input when
the file is present. A later run must still produce the Nuvio APK.

## APK

Expected outputs after a green CI run:

- `androidApp/build/outputs/apk/full/debug/*.apk`
- `androidApp/build/outputs/apk/full/release/*.apk` (debug-signed in CI)

## Runtime

**NOT VERIFIED.** No emulator/device was run in this milestone.

## Playback

**REAL DEVICE VALIDATION REQUIRED.** Do not treat a green assemble as
Nuvio playback working.

## Tests

Nuvio common/host tests were **not** made the CI gate in Milestone 1.
The quarantined `:app` unit tests are **not** run.

## License

GPL-3.0. NuvioMobile `e377942` attribution in `NOTICE`. Historical MIT
text for the quarantined client is in `legacy/LICENSE-MIT.txt`.

## Limitations

- `composeApp/src/full/jniLibs` is empty in the upstream clone; engine
  JNI is expected from `lib-nuvio-engine-android-0.1.1.aar`. Do not
  invent `.so` files.
- `MPVKit/` is empty (iOS). Android libmpv is Maven
  `mpv-android-lib:0.1.12`.
- Optional TMDB / MDBList / Trakt / Simkl / Supabase keys generate
  empty unless `local.properties` or env supplies them.
- Addons/plugins start from empty local storage (no seeded URLs).
- P2P defaults **off**; nuvio-engine itself is not production-ready
  upstream.
- iOS is imported for version config / future sync, not a Milestone 1
  target.
- Device install, first launch, addon install, and playback are all
  **UNVERIFIED**.

## Next

1. Confirm CI produced `androidApp/…/full/debug` APK (not `app/…`).
2. Install on a real Android phone.
3. Add a user addon/plugin, play a real source through Nuvio’s
   `PlayerEngine.android.kt`.
4. Only then Milestone 2+ extras / UI.
