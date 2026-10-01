# StreamBridge 0.5.5-beta validation status

## Meaning of this report

This file records **executed evidence**, not release-readiness inferred from a
version number, upstream assets or a green unrelated CI step. Device/runtime
checks and signed exports are separate from compile/archive checks.

## Local checks executed

- Canonical version/source-pin/Xcode consistency guard passed: shared/Android
  `0.5.5-beta`, build `108`, Apple `0.5.5`/`108`.
- 19 Python release-tool tests passed: invalid/missing/stale metadata/tag/pins,
  Apple profile/team/bundle/distribution validation and fail-closed missing-secret guard, complete coordinated asset
  sets, checksum tampering and rejection of debug/unsigned/upstream packages.
- Stable-track guard passed: clean baseline ancestry, exact source pins, active
  source/runtime isolation, StreamBridge identity, required legal texts, no tracked
  certificate/profile/keystore/private signing inputs.
- Resource XML parsing/named-resource uniqueness and app-brand checks passed.
- Shell and Python script syntax checks and Git whitespace checks passed.
- Android engine binary hash matches the verified official target; iOS icon
  matches the opaque RGB 1024×1024 StreamBridge launcher source.
- A visual spot-check confirmed preserved StreamBridge shared wordmark and the
  native Arctic Blue alternate icon, not upstream product branding. A full
  occurrence/source-asset classification is documented separately.

There is no local JDK, Android SDK, adb/device/emulator, Xcode or Apple toolchain
in this Linux workspace. Failed local toolchain downloads are not build results.
Hosted GitHub workers are used for actual application compilation.

## Hosted runs

| Run | Source | Executed result |
| --- | --- | --- |
| [36921503121](https://github.com/sascio/MyBridge/actions/runs/36921503121) | `82ecc2b592e31ea30f39e456a9e6f920b64c84fd` | Clean-baseline preparation workflow completed; not target-generation or iOS validation |
| [36923648498](https://github.com/sascio/MyBridge/actions/runs/36923648498) | `741f830f4fef8a26408014e828142ebb198d581e` | Audit passed; Android debug Kotlin and dependency analysis passed; R8 minification ran, but split APK+AAB resource layout failed before packages. Actual iOS Release link/archive failed with Java heap space. |
| [36926718901](https://github.com/sascio/MyBridge/actions/runs/36926718901) | `728545e72acbfabf034777eb975b9d48996ce931` | Rerun with separate APK/AAB builds, released UI policy fixes, strict Bash/pipefail and verified workflow lint. Final: debug/four-ABI R8 release APKs and separate AAB packaging/manifest/ABI/DEX/signature checks passed, with 508,498,147-byte nonproduction artifact ID 11195735385. Tests failed to compile because PlayerGestureOverlayTest missed two new parameters. iOS Release archive failed; no IPA. |

The first iOS job's API conclusion was incorrectly green: the default-shell
`tee` pipeline masked Xcode failure. Its artifact
`StreamBridge-iOS-unsigned-validation` (31,553 bytes, ID `11193373167`) contains
**only `ios-build.log`**, not an app archive/IPA. It is explicitly rejected as
success evidence. The log says `ARCHIVE FAILED`; Kotlin 2.4.10
`linkReleaseFrameworkIosArm64` failed with `OutOfMemoryError` during whole-program
DevirtualizationAnalysis. Native source compilation/package resolution progressed,
but **no full Release archive or IPA has been verified from that run**.

After reconnecting GitHub, the persisted edited files were recovered on the
same branch's previously pushed clean history (no original CloudStream-bearing
ancestry was merged). Pending commits were recreated: `71c5c93` fixes 8 GiB
explicit compiler budgets, strict app/report gates and unsigned IPA verification;
`c138ee5` adapts branding and missing gesture-test parameters; `c6e7a3d` fixes the
native NSString conversion. Final hosted validation for these changes is pending.

## Signed release access

An authorized `test-build`, target `both`, dispatch of the signed release workflow
was attempted. GitHub returned **HTTP 403: Resource not accessible by integration**;
no run was created. Secret metadata likewise returns 403. Subsequently both Git transport and the
GitHub API rejected the expired connection (authentication failure / HTTP 401). Production Android/
Apple signing availability is therefore **unknown**, not proven absent.
No new key, Apple team, profile or credential is fabricated. The configured
workflow must fail clearly at the signing stage when required values are missing.

The signing pipeline is implemented and metadata/profile/asset helper tests are
executed, but an actual production Android build, signed iOS archive/export and
coordinated GitHub release draft are **not established by this evidence**.

## Runtime and release checklist

The following remain device-level/manual checks, not claimed as passed:

- guest/account startup, Home, Search, metadata/details, tab navigation;
- user-installed lawful HTTP addons/JavaScript plugins, source picker and headers;
- Media3 and Android mpv fallback, iOS native mpv, multiple legal stream formats;
- subtitles/visual sync, gestures, PiP/background audio/external-player callbacks;
- library/collections/profiles/avatar access, calendar, ratings/Insight/Taste DNA;
- Trakt/Simkl/MDBList/TMDB/OMDb with authorized, actually configured accounts;
- user playlists/EPG/Live TV filtering, downloads/resume/widget/Live Activities;
- all appearance/icon choices, localization, privacy/optional telemetry;
- existing-install update certificate and beta/stable updater behavior on devices;
- actual APK sizes/checksums/ABI manifests and portable, signed iOS export installation.

No CloudStream-specific test, runtime, provider registry/discovery, Configure
lifecycle, classloader or dependency fix is included. Historical reports such as
`TEST-FAILURES.md` are not current target-generation validation evidence.

The two stale upstream client/library label assertions now expect the actual
StreamBridge labels while preserving legacy installation IDs. The native HLS
playlist writer now uses the same NSString factory pattern already present in
target title-facts/subtitle storage; its regression test is added but unexecuted.
These local follow-ups also require pushing and hosted validation after reconnect.
