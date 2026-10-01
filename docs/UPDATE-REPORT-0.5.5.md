# StreamBridge 0.5.5-beta update report

**Status: implementation and local audits prepared; final hosted validation is
being resumed after GitHub reconnection. This is not a release-ready or
runtime-tested completion claim.**

## Exact baseline and source selection

- Dedicated branch: `arena/01a0f91c-mybridge`; `main` is unchanged.
- Clean StreamBridge baseline: `ee6f99b20ef06236029979d1636ebde5b151d677`.
- Latest clean published release: `0.1.04`, source
  `936e116acb940103bf7db2d13ec632d9ebaa3bab`.
- Official genuine `0.5.5-beta`:
  `5c6028f24d3017f27fc4aeb0b6234b18fcfd2ee8`.
- Enhanced genuine `0.5.5-beta`:
  `8b3bd867e172757c9fd7373da301e6489914f3fc`.
- Previous Enhanced preservation reference:
  `ce4492ec057928b330105a2ad0ed54427f3a82e1`.

Both tag/release APIs were verified directly. Enhanced's tag exists and contains
the official generation; its additional official `d667f432` commit is store
metadata only. No moving Enhanced head or unrelated release is substituted.
See [UPSTREAM.md](UPSTREAM.md) for exact release timestamps, source/dependency
pins and the excluded main/release history.

## Integrated and preserved behavior

493 reviewed file records: **455 direct**, **34 automatic three-way**, **four
manual conflict resolutions**, with a [path/hash provenance map](upstream-sync-0.5.5-files.tsv).
Official/shared/Android changes are separated from Enhanced and platform/release
adaptations in logical commits. Native iOS/shared host sources are included.

The generation integrates updated async/binary plugin fetch and timers, playback
startup/resume/buffer/gestures/next episode/shuffle, poster/landscape metadata,
source/debrid/download handling, ratings and tracking, MDBList account/library,
Simkl recommendations, Insight/Taste DNA/facts, calendar/cache/throttling,
navigation/dialogs/tablets, Live TV playlist/EPG search and localization.

StreamBridge's custom branding/navigation/privacy/empty addon defaults,
source-picker/header handling, suspend/cancellation semantics, player/mpv and
updater/signing continuity, optional Trakt/Simkl/TMDB/MDBList configuration,
avatar access and library/profile/settings behavior are reviewed overlays rather
than blind upstream file replacement. Released non-runtime episode-rating
precedence and avatar accessibility policies are explicitly adapted. Legacy
storage/notifications/OAuth callbacks remain compatible while newly generated
links use `streambridge://`. A native HLS NSString cast warning is repaired using
the target's existing safe factory pattern; that new regression test is unexecuted.

Catalog/tooling follow exact target requirements: AGP 9.2.0, Gradle 9.4.1, Kotlin
2.4.10, Compose 1.12.0, SDK 37/minor 0, min Android 24/target 36, Media3 1.8.0,
mpvAndroid 0.1.12, Ktor 3.4.1, Coil 3.6.3, serialization 1.10.0, coroutines 1.10.2,
QuickJS 1.0.15 and DocumentFile 1.0.1. Engine 0.1.2 matches official binary SHA.
No blanket dependency or toolchain upgrade is used to hide a build error.

## Android and iOS release configuration

Canonical shared/Android `0.5.5-beta`, build **108**; Apple numeric `0.5.5`, build
**108**, with separate full beta metadata. Both platforms are visibly StreamBridge.
Android retains `com.streambridge.app` and the established production certificate.
Four ABI APKs and a separate full AAB are supported; split-APK/AAB shrinking cannot
share one invocation. Debug/nonproduction output is explicitly marked and never
promoted into an update or release draft.

The native StreamBridge Xcode target/scheme, bundle IDs, app/widget display names,
primary and alternate assets, version configuration, pinned MPVKit/Apple engine
bootstrap and full Release archive/IPA validation are implemented. The upstream
Apple team is removed. Signed export has secure temporary-keychain/profile setup,
exact team/bundle/distribution checks, real credential requirements, cleanup and
codesign/profile verification. No Apple credentials are invented or committed.

The coordinated release workflow supports signed platform `test-build` and
checksum-validated draft assets for the exact source commit, with manual
publication. It rejects incomplete, debug, upstream, unsigned and wrong-version
sets and does not overwrite already published releases.

## Validation: what actually ran

- **19 local Python release/profile/asset-tool tests passed**.
- Canonical metadata/Xcode consistency, clean ancestry/source isolation, named
  resource XML/app identity, source/asset hashes, script syntax and whitespace
  checks passed.
- Hosted baseline preparation run **36921503121** completed; not target-generation
  or iOS success evidence.
- Target run **36923648498**, commit `741f830`: Android debug Kotlin and dependency
  analysis passed; R8 minification progressed/completed, but a combined split
  APK/AAB resource-layout error prevented release packaging. iOS actual archive
  failed from Kotlin Native Java heap space. A masked `tee` status falsely made
  that job green; its 31,553-byte artifact is only a log, explicitly rejected.
- Rerun **36926718901**, commit `728545e`: separate APK/AAB layout, UI policy fixes,
  strict shell/pipefail and workflow lint. Audit/lint passed at the last successful
  query. Final: debug/four-ABI minified APKs and separate AAB packaging passed; tests did not compile (two missing gesture parameters). iOS archive failed; no IPA.
- Local `71c5c93` fixes iOS 8 GiB explicit compiler budgets, strict app/report gates
  and unsigned IPA verification. `c138ee5` adapts two own-brand test expectations;
  `c6e7a3d` repairs native NSString playlist conversion/adds regression coverage.
  These changes are being pushed for a fresh hosted validation run. New native/fixture changes are not yet validated.
- Signed test-build dispatch returned **403**; no run was created. Secret metadata
  is restricted. Then Git push failed and API reads returned **401**. Production
  Android/iOS credential availability remains **unknown**, not proven absent.

There is no local Java/SDK/adb/Xcode toolchain or attached device. No device-level
startup/playback/subtitle/PiP/download/tracking/updater regression is claimed.
The prior target four-ABI/R8 packages are real but the suite is not yet green. No proper iOS archive/IPA,
production signed export, APK-size result or published release is fabricated.
See [VALIDATION-0.5.5.md](VALIDATION-0.5.5.md) for the full checklist and actual run links.

## Branding, documentation and legal audit

The new README is genuinely written for StreamBridge, covering Android/iOS,
installation, actual release status, source builds/tools, architecture, privacy,
optional runtime settings and credits. Release notes are plain Markdown. Old
parity/player/test documents are labeled historical, not current success evidence.

The comprehensive reference inventory classifies all Nuvio-like tracked path/text
references by namespace/data/callback/dependency/external-service/test/source/legal
role. Own app names, IDs, icon/wordmark, UA and release assets are StreamBridge;
compatibility identifiers and original copyright are not erased. All shared
wordmark/icon and alternate native artwork remains the clean StreamBridge artwork;
only the stale native primary icon is replaced with the canonical own launcher.
See [BRANDING-AUDIT-0.5.5.md](BRANDING-AUDIT-0.5.5.md).

`LICENSE`/`COPYING` are unchanged, `NOTICE` is accurate, engine embedded licenses/
third-party notices and Noto OFL are retained. MPVKit/native/player license payload
limits are explicitly recorded, not replaced with invented license claims.
See [THIRD-PARTY-0.5.5.md](THIRD-PARTY-0.5.5.md).

## Created commits and next action

<!-- commit-list-start -->
| Full commit | Logical change |
| --- | --- |
| `82ecc2b592e31ea30f39e456a9e6f920b64c84fd` | chore: prepare isolated StreamBridge 0.5.5-beta upstream sync |
| `45abeeee441c011d05fab80727e8da0c4654edf2` | sync: integrate official 0.5.5-beta shared and Android updates |
| `f587089ad184976d17cac281048dcfdd872d589a` | sync: integrate verified Enhanced 0.5.5-beta improvements |
| `0fe93ea2c7a089448ce538595bd17f94c855b057` | build: align StreamBridge versions, Android dependencies and identity |
| `cdd29d05fd397b2c6e19a4224011a502683fc32a` | feat(ios): establish native StreamBridge archive and release target |
| `741f830f4fef8a26408014e828142ebb198d581e` | ci: validate isolated StreamBridge Android and iOS builds |
| `79382eb244d4b1f8151cbbaffdad98765123cb08` | fix: preserve released UI policies and separate R8 APK and AAB builds |
| `728545e72acbfabf034777eb975b9d48996ce931` | feat(release): coordinate verified Android and securely signed iOS assets |
| `71c5c93c3eaa1274c6cd9113ad76283d4b955abb` | fix(ios): budget Release linking and reject log-only build success |
| `c138ee5d415e11d79224cc253ddb4b2d28fe50da` | test: adapt own labels and gesture fixtures to verified target contracts |
| `c6e7a3d783ba5c5bc010f2d7dcc5c7dbdec9172f` | fix(ios): use verified NSString bridge for temporary HLS playlists |
| `58d7719b891271246b916cd538e3f49274a18c48` | ci: review published official and Enhanced tags without moving-head substitution |
<!-- commit-list-end -->

At reconnection, the latest remote commit was
`728545e72acbfabf034777eb975b9d48996ce931`. Persisted edits were restored onto that
clean history after Arena restarted the checkout, then recreated in the logical
commits above. The updated branch is being pushed for final validation. The report/documentation commit itself is the current local
HEAD shown by `git log -1`; source/CI commits above are not abbreviated ambiguously.

**GitHub has been reconnected in Arena; Actions signing-dispatch permission must
still be verified.** Do not paste tokens, passwords or signing files in chat.
Push only this dedicated branch, run final hosted validation for the follow-ups,
resolve actual app/test failures and finish lawful-source/device regressions.
Only after authorized production signing succeeds should a release draft or
installable iOS release be reported. No main/unstable branch is used as a fallback.

**No CloudStream runtime, provider registry/discovery, Configure lifecycle,
PathClassLoader, plugin-resource/AppCompat dependency fixes or associated commits
are merged, cherry-picked, rebased or otherwise incorporated into this update.**
