# Upstream 1.0.0 review for StreamBridge 0.1.19

Reviewed tag snapshots (not branch tips): official `NuvioMedia/NuvioMobile`
`511ebd5f175dd39e31b9cc0e7b259259e887cf6f` and the specified Enhanced
`luqmanfadlli/NuvioMobile-Enhanced` `6c2ad7937171965608d05005080b96ca19345379`.
Both tag refs were verified against GitHub. Both are Kotlin Multiplatform trees.
The earlier review incorrectly used `yesnt10/NuvioMobile-Enhanced` tag
`3138f33fee8803d324a3ee213f588697995aafda`; that unrelated Expo/React Native
tag is not the specified Enhanced 1.0.0 and cannot establish its parity.

The existing StreamBridge tree is based on earlier documented core/Enhanced
pins and contains substantial StreamBridge-specific changes; a comparison of
`composeApp` feature code to official 1.0.0 shows hundreds of changed files.
No full 1.0.0 rebase or wholesale sync has been performed. A complete review,
selective integration and device regression testing remain outstanding. The
existing `docs/CLOUDSTREAM-RUNTIME-AUDIT.md` compares a later Enhanced branch,
not the exact 1.0.0 tag.

The exact Official 1.0.0 tag points to MPVKit `da4b7da256a5d67dee74f576762ceb9067fc7a71`
and the exact Enhanced 1.0.0 tag points to `359a885342d4eeda757f8e1d2c24fb01fe6d39c2`,
matching the existing Official pin and Enhanced overlay used by the iOS dependency
script. Their runtime behavior still needs device testing. `store.json` remains
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
`features/player/VodCacheSizing.kt`, and Exo buffer settings. The fork has
separate rating providers, rating UI, and disk-buffer controls; the absence of
these particular upstream files does not imply all those features are absent.
Their behavior differs and remains incompletely compared. The 1.0.0 cache sizing
policy is now adapted to the fork's Android disk cache (with its 10 GiB auto
limit retained), not a wholesale port of upstream's VOD cache lifecycle. Do not treat their absence as an intentional permanent
exclusion or as a full integration.

Compared examples where the StreamBridge behavior is deliberately retained:
`MetaDetailsParser.kt` retains TVDB/episode IDs; `ChapterSkipIntervals.kt`
retains longer credit rolls; `VersionUtils.kt` retains StreamBridge updater
naming and same-base handling; `BackendRateLimit.kt` retains its delay bound.
The official version catalogue uses AGP 9.2.0 while this tree uses 9.4.0 and
also contains additional runtime dependencies; no dependency or native/MPVKit
pins were changed without compatibility evidence.

The specified Enhanced 1.0.0 snapshot is a Kotlin tree closely related to this
fork. A path comparison found 833 identical, 92 changed, and two upstream-only
commonMain Kotlin files, plus seven changed Android and three changed iOS files;
these counts alone do not establish feature parity. StreamBridge-specific
identity, integrations, security and behavior must be preserved while resolving
differences. The separate CloudStream runtime audit references a later branch,
not this tag.

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
`894499115fb1a69cd4bb34d6969a74832f0e5636`; this is distinct from the specified
Kotlin 1.0.0 tag. The branch contains functionality not yet ported,
including the Telegram client and richer AI assistant implementations. The
upstream assistant persists API keys in ordinary SharedPreferences and
NSUserDefaults; StreamBridge instead provides an opt-in, session-only OpenRouter
chat without persistent credentials. It uses limited title metadata and is not
a complete port of Enhanced's series and episode context. The Telegram client
requires platform-specific native bridges and session
handling; copying its common UI alone would not deliver a usable integration.
Differences in other features still require integration and validation. The
1.1.0 beta releases and the current official branch likewise have not been
fully integrated. This is not a claim of upstream feature parity.

## Later selective ports

- Official `547972efd9fbfbde60e06cf83ae9e2d0c97fd8f4` (included in
  `1.1.0-beta.2`) adds genre-aware cast filtering for talk-show guests while
  retaining hosts. The TMDB response mapping and tests are adapted here.
- Enhanced Kotlin branch `894499115fb1a69cd4bb34d6969a74832f0e5636`
  supplies the DuckDuckGo GIF search flow. The StreamBridge implementation
  retains its configured proxy, uses a bounded fallback, and informs users
  where searches go. Live service behavior has not been verified.
- Official `97fbfa90565125859f973feff7a5740e8e647928` episode-rating alignment
  is not copied: it depends on the upstream Tapframe/SeriesGraph episode data
  pipeline and additional alignment heuristics; the 1.0.0 endpoint is now
  adapted below as a fallback, but that later alignment has not been fully
  integrated. The newer downloads redesign, custom downloads poster integration
  and server integrations also remain unported.
- Official `2dcfbb9f1a77fe690fdc788ef6d472fa91644517` handles system Back
  for an embedded show within downloads; this fork preserves navigation-owned
  Back handling and deletion modal behavior.

## Official 1.0.0 tracking and cache follow-up

The existing personal-rating providers were retained rather than replacing the
fork's different tracking interface. Simkl anime rating removal now uses the
Official show's removal bucket; MDBList ratings now follow both cursor and
offset pagination, reject repeated pages or an exhausted page limit rather
than cache an incomplete response, and retain the older has-more-only format.
The Official disk-space-based cache sizing is connected to StreamBridge's
Android Exo disk cache, which bypasses the cache when space is too low. This
is not a port of upstream's write sink, cache eviction lifecycle, or iOS cache.
Real service and device playback validation remain necessary.

## Enhanced 1.0.0 opt-in assistant

The unrelated React Native tag `3138f33fee8803d324a3ee213f588697995aafda`
includes an OpenRouter title assistant; it is not the requested Enhanced tag. StreamBridge now offers an opt-in chat
from title details, using an entered OpenRouter key and model only in memory for
the open dialog. Sending discloses the title metadata and conversation to the
service and selected provider; responses can contain spoilers or inaccuracies.
The fork intentionally does not persist the key in unprotected preferences, and
does not yet replicate the tag's episode/series context or settings screen.

## Specified Enhanced 1.0.0 MDBList library integration

The specified `6c2ad793` snapshot adds dynamic, external and liked list reads,
per-list visibility controls, and MDBList-saved ordering. These are integrated
with the existing account-scoped sync, list picker and read-only protections.
The fork's prior default ordering remains available; MDBList saved order is a
separate selectable sort. This is a targeted integration, not a statement that
all remaining differences have been reviewed or that device testing is complete.

## Enhanced 1.0.0 download recovery conflict

The Enhanced Android downloader resets the consecutive-retry budget when new
bytes arrive. StreamBridge now incorporates that behavior and reads the current
retry count atomically when a transfer fails, rather than the stale count from
the start of a run. A repeated or backward progress report does not reset the
budget. Tests cover the progress transition and existing transient/permanent
failure policy.

Enhanced also reschedules a missing user-initiated Android job, including after
`STOP_REASON_USER`. StreamBridge deliberately pauses such a transfer instead:
rescheduling after the system reports a user stop can override the user's
explicit cancellation, and a missing job at restore time does not reliably
identify whether it was cancelled by the user or lost by the system. Users may
resume the paused item explicitly. This is a conflict resolution, not a claim of
complete integration or on-device validation.

Enhanced's empty-server addon migration/preservation is not blindly applied:
StreamBridge treats an empty server list as authoritative, so migrating cached
local entries on an empty response could resurrect addons intentionally removed
on another device. A safe migration needs an explicit first-sync marker and
cross-device deletion semantics; no such migration is claimed here.

## Official 1.0.0 episode-ratings fallback

The official Tapframe/SeriesGraph season-ratings endpoint is adapted as a
fallback in the existing metadata enrichment pipeline, using the already-wired
optional API base URLs. It preserves OMDb ratings and the fork's IMDb/TMDB
separation, fills only missing IMDb ratings, validates season/episode/rating
values, and avoids applying a show-level map to a different explicit IMDb ID or
to unmapped anime IDs. It caches successful results briefly, bounds response
size and preserves coroutine cancellation. The SeriesGraph fallback uses an explicit TMDB ID when available, and otherwise
resolves the IMDb show ID through the existing TMDB service only if the primary
ratings source is empty. Real service results, device UI, and unusual anime
numbering remain unverified.

## Official 1.0.0 MDBList rating decoding

The existing StreamBridge rating provider now accepts Official's flat and
nested media rows, `rating_precise`, ID aliases and alternative season/episode
number fields. It rejects invalid coordinates and out-of-range scores. The
fork's shared personal-rating model stores integers, so a precise MDBList score
is rounded to the nearest supported 1–10 value rather than represented with
fractional precision; writes retain the existing fork API and tracking UI.
