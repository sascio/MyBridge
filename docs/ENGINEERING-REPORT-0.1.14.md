# StreamBridge 0.1.14 — engineering report

Everything below is evidence from this repository's own GitHub Actions runs.
Nothing is claimed that a run or a file in this repository does not show.

## 1. Release target

| Item | Value | Where it is enforced |
|---|---|---|
| Tag | `v0.1.14` | `scripts/verify-release-target.sh` |
| versionName | `0.1.14` | `streambridge.version.properties`, `iosApp/Configuration/Version.xcconfig` |
| versionCode | `114` | `streambridge.version.properties` |
| Android application ID | `com.streambridge.app` | `androidApp/build.gradle.kts` |
| iOS bundle ID | `com.streambridge.app` | `iosApp/iosApp.xcodeproj/project.pbxproj` |
| Release notes | `docs/RELEASE-NOTES-0.1.14.md` | same script |

### 1a. Preflight baseline (verified through the API before touching anything)

| Fact | Value |
|---|---|
| Repository | `sascio/MyBridge`, branch `main` at `77858b5` when the sync started |
| Last published release | `v0.1.13` (2026-10-22): four per-ABI APKs 51.0–54.1 MB, `checksums.sha256`, `StreamBridge-0.1.13-iOS-unsigned.ipa` 73.0 MB |
| `v0.1.14` tag | **exists already**: annotated tag `d1665269…` → commit `6e36312` (an earlier, superseded attempt). No release has ever been published for it |
| GitHub Pages | not enabled (`GET /pages` → 404); the enable call is refused for this token (403) |
| Workflow secrets | not readable with this token (`GET /actions/secrets` → 403), so the report does not claim which signing secrets exist |
| Existing workflows | build, iOS release, release draft, store source, PR debug build, upstream watch |

Nothing was moved, deleted or re-tagged during the work.

## 2. Upstream integration

| | Repository | Reference | Commit |
|---|---|---|---|
| Official (primary) | `NuvioMedia/NuvioMobile` | `0.6.6-beta` | `2d52c8b71b598eb90e13fa9ec9ccff97ab601126` |
| Enhanced (secondary) | `luqmanfadlli/NuvioMobile-Enhanced` | `0.6.6-beta` | `2fc68109f9a4919c7fae373740ac8d5aae5253ca` |
| Previous pins | both | `0.6.3-beta` | `014fa2e2…` / `b9504e1a…` |

192 official and 288 Enhanced commits; 439 upstream paths changed, +47,845 /
−9,434 lines. Classification of every path, the ten hand-resolved conflicts and
the two excluded subsystems are in [upstream-parity-0.6.6.md](upstream-parity-0.6.6.md).
The import is source-level (StreamBridge has no shared history with upstream);
no upstream commit was cherry-picked and no directory was replaced wholesale.

The result touches 443 files against 0.1.13 (+50,794 / −12,109).

## 3. Branding

Every `Nuvio` match was classified as a leak, a legitimate internal reference or
a required attribution; only leaks changed. Details, including what was
deliberately kept and the remaining translation debt, are in
[BRANDING-AUDIT-0.1.14.md](BRANDING-AUDIT-0.1.14.md).

Highlights: the four new locales and every app self-reference now say
StreamBridge; the About footer renders the StreamBridge lockup; the privacy
policy is the in-app page instead of `nuvio.tv`; the two iOS permission prompts
no longer name Nuvio; the app no longer asks Android to App-Link-verify a domain
it does not own (`nuvio.tv`), while still parsing legacy invites.

## 4. Watch Party links

Unchanged in behaviour, re-verified after the sync:

- invites are generated as `streambridge://party/<code>` from the normalized code
  (`PartyRepository`), branded in every locale with `%1$s` code, `%2$s` link and
  `%3$s` install fallback;
- `nuvio://party/…` and legacy `https://nuvio.tv/party/…` invites are still
  parsed so links shared by older builds keep working; the Android filter for the
  legacy host no longer requests verification;
- `https://sascio.github.io/MyBridge/party/…` redirect is registered in the
  Android manifest and the iOS URL handler, unfurled as an https URL, and is not
  required for an invite to work;
- `scripts/tests/run-tests.sh` fails the build if an invitation string mentions
  Nuvio or if the deep-link plumbing disappears.

**Blocker:** GitHub Pages is still not enabled for this repository (`GET
repos/sascio/MyBridge/pages` → 404, `POST` → 403 with this token), so the HTTPS
redirect page cannot be served yet. The invites themselves are unaffected.

## 5. Builds

| What | Workflow | Run | Commit | Result |
|---|---|---|---|---|
| Android: unit tests, assemble, lint, CloudStream runtime, Play-Store boundary, size report | Build Stream Bridge | 41234647872 | `1fea81d` | success |
| iOS unsigned IPA | StreamBridge iOS release | 41234647918 | `1fea81d` | success |
| Script tests (release target, store source, version wiring) | Build Stream Bridge | same run | `1fea81d` | success |
| Android at HEAD (`4e04001`, documentation only) | Build Stream Bridge | 41242979488 | `4e04001` | success |
| Android (earlier code-equivalent commit) | Build Stream Bridge | 41234647872 | `1fea81d` | success |

### 5a. iOS toolchain

The IPA builds now: run 41234647918 produced
`streambridge-0.1.14-full-release.ipa` (69.75 MiB) after the SDK fix below.

The first IPA run of this integration (41225110786) failed in `xcodebuild`, not in
the Kotlin build:

```
iosApp/iosApp/ContentView.swift:116: error: cannot find 'UIHingeInteraction' in scope
iosApp/iosApp/ContentView.swift:155: error: value of type 'UIView' has no member 'reservedRegions'
iosApp/iosApp/ContentView.swift:111: error: type 'UITraitCollection' has no member 'systemTraitsAffectingVerticalBarEdge'
```

Nuvio 0.6.6's iOS host uses APIs from the **iOS 27 SDK**. The `macos-26` runner
image ships at most Xcode 26.6 / iOS SDK 26.5 (checked against
`actions/runner-images`), and a runtime `#available(iOS 27.1, *)` guard cannot
help when the symbols are not declared at all. The fold/hinge path is now a
documented no-op that keeps the plain safe-area layout, so the player and all
navigation behaviour are unaffected on every iOS version the app supports; the
comments record exactly what comes back with a newer SDK. The iOS job timeout
was also raised from 120 to 180 minutes, because this build takes about 110
minutes on the runner and one run had already been cut by the limit.

Android artefacts produced by that run (compile validation only — the job does
not inject the production keystore and says so in its own artifact):

`Stream-Bridge-CI-full-release-APK-not-production` (196,043,648 B, four per-ABI
APKs) and `Stream-Bridge-CI-full-debug-APK`.

Production-signed APKs and the published IPA come from
`.github/workflows/release-draft.yml`, which fails if production signing material
is missing and rejects debug certificates.

## 6. Sizes

Measured on the built artefacts (annotation published with the check run, not a
copy of the log):

| APK | 0.1.13 (release asset) | 0.1.14 (CI build) | Δ |
|---|---:|---:|---:|
| arm64-v8a | 50.33 MiB | 52.05 MiB | +1.72 MiB (+3.4 %) |
| armeabi-v7a | 48.68 MiB | 50.26 MiB | +1.58 MiB (+3.2 %) |
| x86 | 50.76 MiB | 52.48 MiB | +1.72 MiB (+3.4 %) |
| x86_64 | 51.63 MiB | 53.48 MiB | +1.85 MiB (+3.6 %) |

The growth is the new native payload 0.6.6 requires (the Rust AIOStreams module:
thumbnails, container indexes, software decoder) plus the libass renderer; dex and
resources are unchanged in size because R8 and resource shrinking already run.

What already keeps the download small, and therefore was not reintroduced as a
"new" optimisation: per-ABI APK splits (no universal APK), `isMinifyEnabled` +
`isShrinkResources` for release, legacy JNI packaging so the native libraries
compress inside the APK, licence-file exclusion, and `proguard-rules.pro` keeps
for the reflection-heavy parts only. **No feature, provider, codec, player
integration or platform was removed to save space.**

| IPA | 0.1.13 | 0.1.14 | Δ |
|---|---:|---:|---:|
| unsigned IPA | 73,041,171 B (69.66 MiB) | 73,140,919 B (69.75 MiB) | +99,748 B (+0.14 %) |

The IPA is flat because the new native code lands in the already-bundled MPVKit
frameworks and the payload compresses well; `StreamBridge.app` inflates to
221.66 MiB before compression, of which 6.3 MB is Compose resources.

## 7. Performance

No benchmark is claimed: this repository has no benchmark harness and a CI runner
is not a phone. What the 0.6.6 integration does change, and what can be stated
without inventing numbers:

- `ChapterSkipIntervals` no longer drops credit rolls longer than four minutes, so
  the skip-credits action actually appears on films (behavioural fix, verified by
  unit tests);
- the chapter skip path uses `getOrPut` instead of repeated lookups (upstream
  commit `1755d483`), and chapter parsing runs on the existing background
  dispatchers;
- libass atlas rendering replaces the previous overlay path for styled subtitles,
  which is upstream's own performance-motivated change;
- the settings screen no longer composes one 3k-line playback page but one page
  per section, so the settings route composes less before first frame.

Not measured by CI, and therefore not claimed: cold/warm start time, memory,
frame timing, battery, playback start latency, subtitle render cost on device.

## 8. Testing

`Build Stream Bridge` runs `./gradlew test` plus Android lint, a CloudStream
runtime check, a Play-Store flavour boundary check and the script test suite. All
pass at `366974d`. The unit-test report is uploaded as an artifact.

Fixes made to get there, each a separate commit, are listed in the git log; none
of them suppresses a test, mocks production behaviour or weakens an assertion
without saying so in the commit message.

What CI cannot verify and no claim is made about: real playback on a device,
PiP/background behaviour, AirPlay/Cast, Live TV sources, provider responses,
Watch Party sync between two devices, updater installation, and store clients.

## 9. Publication

Draft/publish is done by `.github/workflows/release-draft.yml`, triggered by a
push of the tag `v0.1.14` or by manual dispatch.

**Blocker — needs a decision, not a guess:** the tag `v0.1.14` already exists in
this repository at commit `6e36312`, which predates this integration. Neither
trigger can run as things stand:

- pushing the tag again does nothing (it already exists, at the old commit);
- moving it is a tag rewrite, which this task forbids without explicit approval
  and a recovery plan;
- `workflow_dispatch` is refused for this token: `HTTP 403: Resource not
  accessible by integration` on `actions/workflows/…/dispatches` (the token has
  no `actions: write`).

Recovery plan if the tag is approved to move: the current tag object is recorded
(`v0.1.14` → `6e36312`, commit still reachable in `main`'s history and in this
branch), so re-pointing it is reversible by re-running
`git tag -f v0.1.14 6e36312 && git push --force origin refs/tags/v0.1.14`.
No release exists for `v0.1.14` today, so no published asset would be orphaned.

---

# Addendum — the 2026-11-01 production release

## Published

| Item | Value |
|---|---|
| Release | `StreamBridge v0.1.14` — https://github.com/sascio/MyBridge/releases/tag/v0.1.14 |
| Published at | 2026-11-01T04:51:21Z (not a draft, not a prerelease) |
| Tag | annotated `v0.1.14` → commit `25335785ad84a3fb85386d3a627cc32295fb767d` |
| Release target | `25335785ad84a3fb85386d3a627cc32295fb767d` |
| Producing run | `Draft production GitHub Release` 41269279636 (tag push) |
| Android job | success — `Production-signed full APK` |
| iOS job | success — `iOS unsigned IPA` |

`2533578` is `48feccb` plus one workflow-only commit (see "Release blocker"
below); the application tree is unchanged between the two.

## Assets (server-reported size and digest)

| Asset | Bytes | SHA-256 |
|---|---:|---|
| `androidApp-full-arm64-v8a-release.apk` | 54,720,370 | `9d950823bea2abc0d47a0085dd37c7d3b82b1045a1ccdb8e4eab68c469f6f056` |
| `androidApp-full-armeabi-v7a-release.apk` | 52,837,269 | `309ce9a2e06699776a7f4fc14f0f4e8c73f500f17b0e4c1054e101d95004bf64` |
| `androidApp-full-x86-release.apk` | 55,165,068 | `f8d7c2cbdbe3008b80cb282189dbba08dfb4b4d0ccf2873ff5997c41b4ac68c5` |
| `androidApp-full-x86_64-release.apk` | 56,219,773 | `fc85d3346c963fdbd87816521f44e8c241f44754b6cbc769aef3dde30e97a418` |
| `checksums.sha256` | 577 | `52f41752a7f7bca842bd6fcfb4f030f3a53f39f67958b675337c285ba381fbb1` |
| `StreamBridge-0.1.14-iOS-unsigned.ipa` | 73,141,429 | `07ce445aac8bd3711bd1d9311d3926889e9958fbc5f972932890ba88000f811a` |

Built / signed / uploaded / published, in that order: the APKs are built and
signed inside the run (the job's `Reject debug certificates` step pins the
StreamBridge upload certificate SHA-256 `5da621d8e6f5c4ffb7396de3fbf686f3bf61bcec71cccffba9bbbbdef56285fc`
and fails on anything else), uploaded as release assets, and the release was
published after all six were present. The IPA is **unsigned by design** — that
is the distribution form for AltStore/SideStore, which re-sign it on device.

## Release blocker found and fixed

The first production run (41256469053, 2026-11-01T00:22Z) failed in
`Assemble production full release` after 2 m 51 s. Root cause: the release
workflow's Android job never installed the Rust toolchain, while
`androidApp/build.gradle.kts` registers `buildRust` (`cargo ndk … build -p
aiostreams-native-android`) as a generated jniLibs source directory for every
variant. `build.yml` installs the four Android targets and `cargo-ndk`; the
release workflow had the step only in its iOS job. Fixed by adding the same
step, verbatim, to `release-draft.yml`'s Android job (commit `2533578`).

Because a tag push always runs the workflow as of the tagged commit, the fix
required the tag to point at a commit newer than `48feccb`. The user approved
that re-point, the application tree being byte-identical between the two.

## Other observations from the release runs

- **iOS is flaky on this runner.** Run 41258406918's iOS job failed at 19 min
  with `xcodebuild` exit 65 on sources identical to a run that had passed 2.5 h
  earlier; the immediate retry passed (95 min). No code change was involved.
  Raw logs are not readable from this environment (the log blob host is outside
  its network allowlist), so the failure text could not be captured; the
  inference is from step timings and identical sources.
- **APK builds are not bit-reproducible.** Two builds of the same commit
  produced identical sizes but different SHA-256 digests, so the workflow's
  `ensure-asset` guard ("preserve, never replace") correctly refused to
  overwrite the assets of an earlier partial run. Clearing those unpublished
  draft assets so a single run produced the whole release was the resolution.
- **Deleting and re-creating a tag detaches a draft release from it.** After the
  re-point, the draft's `tag_name` became an `untagged-…` placeholder; it was
  re-attached with `PATCH /releases/{id} {tag_name: "v0.1.14"}` before
  publishing. Publishing without that would have created a junk tag.
- **`attach-ios` requires an `### iOS status` placeholder** in the release notes
  and otherwise refuses to touch the release. `docs/RELEASE-NOTES-0.1.14.md`
  had none, so the job failed *after* it had already uploaded the IPA (assets
  first, notes second). The iOS installation section it would have written was
  applied to the release body and to the notes file, so the published body and
  the repository now agree. The notes template should carry that placeholder in
  future releases.
- **The sideload source (`store.json`) was not updated.** The
  `Update Sideload Source` run 41274675738 generated the new source and failed
  in its own reachability check, which sends a one-byte range request and then
  required exactly HTTP 200 — a ranged request answers 206, so the check failed
  for every entry, including all previously published versions. The check now
  accepts 200 and 206. Publishing the source needs that workflow to run again
  from the default branch (it is triggered by `release: published` or by
  dispatch, and dispatch is refused for this session's token), and the update
  script verifies the real IPA file, which this environment cannot download.
  Until then AltStore/SideStore users do not see 0.1.14 in the source, although
  the release and its IPA are live.
