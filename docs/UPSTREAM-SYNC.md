# Reviewing future StreamBridge upstream updates

StreamBridge is an independently branded, GPL-licensed adaptation of verified
NuvioMobile and Enhanced releases plus reviewed StreamBridge behavior. The
current exact sources are in [UPSTREAM.md](UPSTREAM.md) and canonical version
metadata. It is not a moving-head mirror and no upstream watcher auto-merges code.

## Safe synchronization process

1. Verify the official release **tag and release API**, dereference annotated
   tags, and record the full commit. Verify the Enhanced release independently;
   never assume a matching tag or silently substitute an unrelated release.
2. Establish a clean StreamBridge baseline. The experimental CloudStream branch,
   runtime, dependency/classloader fixes and associated commits remain excluded.
   Inspect history for comparison; do not import its source tree or ancestry.
3. Compare the previous verified official/Enhanced generation and the preserved
   StreamBridge overlay. Inventory custom, changed and overlapping paths.
4. Apply official behavior first, genuine Enhanced additions next, then the
   required StreamBridge adaptations. Review conflicts, cancellation/threading,
   tracked configuration, navigation/data migrations, identity and signing.
5. Audit both Android and iOS/common sources, native host/player, package assets,
   build tools, dependencies, licenses and the release infrastructure. Dependency
   changes need a verified source requirement, not a blanket latest-version bump.
6. Update canonical StreamBridge version/build and numeric Apple marketing
   version. Generate/check Xcode metadata; keep production signing continuity,
   accurate service/operator attribution and legacy callback/storage compatibility.
7. Compile/debug/test/minify/package Android and actually resolve/build/archive
   iOS. Signed export requires real credentials and a distinct signing stage.
   CI must propagate compiler/test failures, not accept log-only artifacts.
8. Finish real-device regressions and release notes before publishing. Attach
   only checksum-verified, correctly named, signed production assets to a draft.
   Publish only after maintainer review; do not rewrite already published releases.

The current synchronization map and reviewed conflict record are
[UPSTREAM-SYNC-0.5.5.md](UPSTREAM-SYNC-0.5.5.md). Historical parity/player audits
are not validation evidence for the current generation.
