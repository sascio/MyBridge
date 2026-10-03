# StreamBridge 0.1.07 → 0.1.08 — update report

Branch: `arena/01a0fc9f-mybridge` (PR #180 → `main`; **not merged automatically**)
Final head: `d25ed73` · Report date: 2026-10-03 · **Android CI green, iOS CI green**

---

## 1. Bases

| Item | Value |
| --- | --- |
| StreamBridge 0.1.07 starting commit | `c52a2d7238e3e2874743851f968bb0e14dfe94f9` (`main`) |
| Official NuvioMobile `0.5.5-beta` tag | `5c6028f24d3017f27fc4aeb0b6234b18fcfd2ee8` |
| NuvioMobile-Enhanced `0.5.5-beta` tag | `8b3bd867e172757c9fd7373da301e6489914f3fc` |
| MPVKit revision used for iOS | `d5cf091c80368bbbc1bbf2d195fbc55d926df888` (the revision Enhanced 0.5.5-beta pins for its submodule) |
| CloudStream development branch | **not used** — no file from that branch is in this update |

## 2. Commits on the update branch

| Commit | Subject |
| --- | --- |
| `9929b93` | sync(core): integrate NuvioMobile + Enhanced 0.5.5-beta |
| `901b0f3` | release: StreamBridge 0.1.08 (Nuvio 0.5.5-beta core + iOS release) |
| `393c2bc` | ci(ios): also run the iOS workflow on arena/** pushes and pull requests |
| `5418879` | ci: use gradle/actions/setup-gradle (the actions/ org has no setup-gradle) |
| `813c331` | test: fix the two androidHostTest compile errors from the 0.5.5 sync |
| `0ee8830` | test: align the 0.5.5 androidHostTest suite with upstream behaviour |
| `875a3b4` | ci(ios): capture the iOS build log in run annotations and pin MPVKit |
| `faee91d` | docs: record the 0.1.08 test reconciliations and the MPVKit pin |
| `b974401` | ci(ios): make the failure reporter actually run |
| `6a14f02` | ci(ios): publish iOS build diagnostics without relying on bash 4 |
| `e05fc1e` | fix(ios): give Kotlin/Native the heap its link step needs |
| `d25ed73` | fix(ios): give the iOS build upstream's memory profile — **head, pushed, CI green** |

`d25ed73` consolidates three follow-up fixes (daemon heap via `GRADLE_USER_HOME`,
removal of the conservative background `NUVIO_GRADLE_JVMARGS` pass-through, stale
daemon reset, plus the `docs/RELEASE-IOS.md` memory section) into one commit,
because the sandbox was re-cloned mid-session and the local objects for those
commits were lost while their content survived in the working tree. The tree is
identical to what those commits produced; the delta against `e05fc1e` is exactly
those three files.

## 3. Changes integrated (Δ `c52a2d72..d25ed73`)

264 files, +18,390 / −5,118 lines, concentrated in:

- `composeApp/src/commonMain/kotlin` (155 files) — features: settings, player, core/ui, details, shuffle, ratings, mdblist, streams, library, home, simkl, profiles
- `composeApp/src/commonTest` (23) and `androidHostTest` (11) — upstream test sources plus the 0.1.08 reconciliations
- iOS: `composeApp/src/ios{Main,Full,AppStore}` (the iOS implementation from 0.5.5-beta), `iosApp/` (project, scheme, `Config.xcconfig`, `Version.xcconfig`, `Info.plist`, app icons), `scripts/build-ios-ipa.sh`, `scripts/prepare-ios-dependencies.sh`
- Release metadata: `streambridge.version.properties`, `README.md`, `NOTICE`, `docs/{UPSTREAM,UPSTREAM-SYNC,SIGNING,RELEASE-IOS,TEST-FAILURES}.md`
- CI: `.github/workflows/{build,ios-release,release-draft}.yml`, `.github/report-ios-failure.sh`
- `gradle/libs.versions.toml` (1 line, iOS `quickjs-kt` 1.0.5 → 1.0.15, matching upstream)

Not changed: `androidApp/build.gradle.kts` (applicationId `com.streambridge.app` untouched), `legacy/`, the Android CloudStream runtime packaging, the production signing path.

## 4. Identity / version verification

- App name **StreamBridge** everywhere user-facing: Android `app_name` (`values`, `values-bn`, `values-es`) used by the manifest label; iOS `PRODUCT_NAME=StreamBridge` via `Config.xcconfig` → `StreamBridge.app`.
- Application identity unchanged: `applicationId com.streambridge.app`, iOS `com.streambridge.app` / `com.streambridge.app.DownloadsWidgetExtension`.
- Version **0.1.08 (108)** in `streambridge.version.properties`, Android `versionName`/`versionCode`, `Version.xcconfig` (`MARKETING_VERSION=0.1.08`, `CURRENT_PROJECT_VERSION=108`), README and release drafts.
- Upstream licence/attribution kept (`NOTICE`, upstream copyright, `NuvioEngine` engine artifact names, internal Swift/Kotlin type names). No Nuvio product name or version is presented as StreamBridge's.
- Static string audit: 2,941 `stringResource(Res.string.*)` call sites, all keys resolve, no duplicate keys, no placeholder/argument mismatches (the one template without inline args, `plugins_message_installed`, is substituted in code on purpose).

## 5. Build / test / release results

### Android — PASS

| Run | Head | Result |
| --- | --- | --- |
| `37064295272` (push) / `37064298928` (PR) | `875a3b4` | success — all steps |
| `37065058705` / `37065067443` | `faee91d` | success — all steps |
| `37112724658` (push) / `37112726818` (PR) | `d25ed73` | success — all steps |

`Run unit tests` (`:composeApp:allTests`, the release gate), the failing-test annotation step, `assembleFullRelease` (R8/minify on), both distribution-verification steps, the size report and the artifact uploads all passed.

Artifacts (latest run): `Stream-Bridge-CI-full-debug-APK` 350,636,870 B ·
`Stream-Bridge-CI-full-release-APK-not-production` 229,472,152 B (debug-signed by
design) · `Stream-Bridge-unit-test-report` 678,023 B.
Release outputs are per-ABI (arm64-v8a, armeabi-v7a, x86, x86_64).

### iOS — PASS

| Run | Head | Result |
| --- | --- | --- |
| `37112724661` (push, 1 h 3 m) / `37112726814` (PR) | `d25ed73` | **success** |

Artifact: **`StreamBridge-iOS-unsigned-IPA`** — `streambridge-0.1.08-full-release.ipa`,
**95,986,293 B (95.99 MB)**, unsigned by design.

`scripts/build-ios-ipa.sh` validates the packaged app *before* it writes the IPA —
name `StreamBridge.app`, bundle identifier `com.streambridge.app`, version 0.1.08
— and fails otherwise, so the passing build is itself the identity and version
check.

What the iOS work involved:

- New `.github/workflows/ios-release.yml` (macOS runner, Xcode 26.6, JDK 17, Kotlin/Native toolchain cache, optional signing, unsigned IPA when Apple secrets are absent), an `ios` job in `release-draft.yml`, and `scripts/build-ios-ipa.sh` / `prepare-ios-dependencies.sh`.
- MPVKit pinned to `d5cf091c` (Enhanced's submodule revision) instead of a moving branch tip.
- The build initially failed with `error: java.lang.OutOfMemoryError: Java heap space` → `> Task :composeApp:linkReleaseFrameworkIosArm64 FAILED` (runs `37067486267` @`6a14f02`, `37068441616` @`e05fc1e`; root cause read from check-run annotations because raw Actions logs are not retrievable).
- Root cause: StreamBridge's `gradle.properties` keeps the Android memory profile, and Kotlin/Native compiles and links inside the Gradle daemon.
- Fix (`e05fc1e`, `d25ed73`): `build-ios-ipa.sh` writes a marker-tagged `GRADLE_USER_HOME/gradle.properties` (Gradle gives it precedence over the project file) with upstream's heaps — `org.gradle.jvmargs=-Xmx12288M`, `kotlin.daemon.jvmargs=-Xmx8192M`, `kotlin.native.jvmArgs=-Xmx12288M` — passes them as Gradle properties too, stops any stale daemon first, and echoes the effective profile; failing runs also print the profile and heap-related environment. Android keeps `-Xmx6144M/-Xmx4096M` unchanged.
- The build now runs 63 minutes to completion instead of dying at 8–12 minutes.

**Signing:** no Apple credentials exist in this repository. The workflow reports
the missing `STREAMBRIDGE_IOS_*` secrets (team id, signing identity, certificate,
certificate password, keychain password, provisioning profile) and attaches the
**unsigned** IPA as a build-validation artifact only. Supplying those secrets
produces a distributable signed IPA — no other change is needed. The re-signing
procedure is documented in `docs/RELEASE-IOS.md`. No certificate, provisioning
profile, or password is committed.

### GitHub Actions

`build.yml` (Android) green · `ios-release.yml` green · `release-draft.yml`
(Android production + iOS + draft release, draft-only, fails closed without the
production keystore secrets) and `nuvio-upstream.yml` configured and parsing.
PR **#180** open against `main`, `MERGEABLE`, +18,390/−5,118 across 264 files, with
the validation results posted as a comment. `main` is untouched at `c52a2d72`.

## 6. Session continuity note

GitHub authentication was invalidated mid-session and the sandbox was re-cloned
afterwards: the local branch was recreated from `main` and the objects of the
follow-up commits were lost, while the working tree kept their content. The
content was verified file by file (`git ls-tree -r` of `e05fc1e` vs the working
tree — 1791 of 1792 files present; the one missing test file was restored from
`e05fc1e`), re-committed as `d25ed73` on top of `e05fc1e`, and pushed as a
fast-forward. Nothing was lost.

## 7. Remaining items

1. Provide the `STREAMBRIDGE_IOS_*` secrets for a distributable, signed IPA.
2. Merge PR #180 when ready — deliberately not done automatically.
3. Optional cosmetics, deliberately left alone: the Android CI job label `Nuvio-core full Android APK` (a check-run name — renaming could break branch-protection required checks) and the internal `NuvioTab*.imageset` asset names (not user-facing).

## 8. Rules followed

- No CloudStream development-branch content; only the three upstream-identical `fullCommonMain` files that legitimately arrived with the 0.5.5-beta sync.
- StreamBridge identity, package/application identity, existing functionality, versioning, and the working Android release behaviour preserved; upstream licence/attribution intact.
- No Apple signing material committed; the unsigned IPA is reported as unsigned rather than presented as a release.
- Test reconciliations are documented in `docs/TEST-FAILURES.md`; no build/test claim is made without CI evidence.
