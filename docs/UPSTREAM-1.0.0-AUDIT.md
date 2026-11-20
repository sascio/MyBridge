# Upstream 1.0.0 review for StreamBridge 0.1.19

Reviewed tag snapshots (not branch tips): official `NuvioMedia/NuvioMobile`
`511ebd5f175dd39e31b9cc0e7b259259e887cf6f`, Enhanced
`yesnt10/NuvioMobile-Enhanced` `3138f33fee8803d324a3ee213f588697995aafda`.
Both tag refs were verified against GitHub. The official tag is Kotlin
Multiplatform; the exact Enhanced tag is an older Expo/React Native tree, not
the later Kotlin Enhanced branch used in the existing CloudStream runtime audit.
It contains no CloudStream implementation under `src`. It is not a drop-in
upstream overlay for the current application.

The existing StreamBridge tree is based on earlier documented core/Enhanced
pins and contains substantial StreamBridge-specific changes; a comparison of
`composeApp` feature code to official 1.0.0 shows hundreds of changed files.
No full 1.0.0 rebase or wholesale sync has been performed. In particular, no
upstream feature is claimed as newly merged in this change. A complete review,
selective integration and device regression testing remain outstanding. The
existing `docs/CLOUDSTREAM-RUNTIME-AUDIT.md` compares a later Enhanced branch,
not the exact 1.0.0 tag.

MPVKit/native pins were not changed: the tag comparison alone does not establish
binary compatibility with StreamBridge's native overlays. `store.json` remains
at the last published version; 0.1.19 metadata requires real production
artifacts and verified checksums. Android Play and iOS remain non-executing
CloudStream distributions. Android Full loads third-party code in-process;
authorized repositories and hashes do not make code malware-free. A missing
published hash is unverified. These checks are not a sandbox.

## Follow-up comparison

A path-and-byte comparison of commonMain Kotlin under `com/nuvio/app` against
exact official 1.0.0 found 509 identical common paths, 259 different common
paths, 14 official-only paths and 188 StreamBridge-only paths. These are file
counts, not an assessment that each difference should be merged. Examples of
upstream-only work include `features/mdblist/MdbListUserRatingDecoder.kt`,
`MdbListUserRatings.kt`, `features/simkl/SimklRatings.kt`, details rating UI,
`features/player/VodCacheSizing.kt`, and Exo buffer settings. These are **not**
ported here: ratings change tracking persistence, remote APIs and UI together,
and the player additions need review against StreamBridge's existing playback
and download changes. Do not treat their absence as an intentional permanent
exclusion or as a full integration.

Compared examples where the StreamBridge behavior is deliberately retained:
`MetaDetailsParser.kt` retains TVDB/episode IDs; `ChapterSkipIntervals.kt`
retains longer credit rolls; `VersionUtils.kt` retains StreamBridge updater
naming and same-base handling; `BackendRateLimit.kt` retains its delay bound.
The official version catalogue uses AGP 9.2.0 while this tree uses 9.4.0 and
also contains additional runtime dependencies; no dependency or native/MPVKit
pins were changed without compatibility evidence.

The exact Enhanced tag's README and `src/` identify React Native/Expo home,
search, library, player, Trakt and addon features. The current application
already has Kotlin implementations of these broad areas, but full functional
parity has not been established. Directly copying React components, Expo
configuration, app IDs, remote endpoints or native modules would be incompatible
and would risk StreamBridge identity. The later Kotlin Enhanced CloudStream
reference is documented separately in `CLOUDSTREAM-RUNTIME-AUDIT.md` and is
not represented as the requested 1.0.0 tag.

This follow-up adds CloudStream bulk management, fail-closed handling for
ambiguous installed filenames and malformed hashes, and retains installed
metadata when a repository refresh fails. It does not constitute an upstream
1.0.0 sync. Real-device provider registration, playback and platform regression
testing are still required before production release.
