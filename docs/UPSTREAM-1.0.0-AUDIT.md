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
No full 1.0.0 rebase or wholesale sync has been performed. A complete review,
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

## Selective integration: MDBList release ordering

The official 1.0.0 release-order implementation has been adapted into the
existing StreamBridge MDBList sorter: it requests the unified release order,
uses available release dates to normalize direction, caches a single order for
both directions, and preserves the existing added-date and rank behaviors.
The snapshot's new release-date field is optional for older cached data.
A focused test exercises both upstream response directions, cache reuse and
cache restoration. This is one selected upstream feature, not a complete sync.

## Official 1.0.1 hotfix follow-up

The later stable tag `1.0.1` resolves to
`3cfc311b494f25213f5b95a3afdfa619b83ceb50`. Its source fixes since 1.0.0
are Trakt refresh coalescing (`103c5e07ac4e`), TorBox alternative-hash
matching (`f81edc6530f6`), iOS download backup exclusion (`4baf886eed74`),
and Android player bottom insets (`c31c30e1ec07`). The first three have been
adapted here; TorBox fallbacks are limited to eight concurrent lookups and the
iOS change affects only the app-owned folder, not a user-selected directory.
The player has a StreamBridge-specific cutout/navigation-bar implementation
already excluding gesture insets; it was retained instead of replacing it.
The 1.0.1 version bump was not adopted because StreamBridge has its own version.

Enhanced's Kotlin `enhanced` branch was inspected at
`894499115fb1a69cd4bb34d6969a74832f0e5636`; this is distinct from its older
React Native 1.0.0 tag. The branch contains functionality not yet ported,
including AI assistant and Telegram client implementations. The assistant's
upstream implementations persist API keys in ordinary SharedPreferences and
NSUserDefaults, so they require a secure-storage adaptation before adoption.
The Telegram client requires platform-specific native bridges and session
handling; copying its common UI alone would not deliver a usable integration.
Differences in other features still require integration and validation. The
1.1.0 beta releases and the current official branch likewise have not been
fully integrated. This is not a claim of upstream feature parity.
