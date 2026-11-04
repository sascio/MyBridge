# StreamBridge 0.1.15 — Release record

Tag `v0.1.15` → commit `82ab509` (sync + About Us/Discord + layout-switch
relocation). Version 0.1.15, code 115. This file records how the release
objects were produced and corrected; nothing was force-pushed and no
existing tag was moved.

## Release cycle

1. Tag push → run 41865648639: Android signed APKs OK, draft created with
   Android assets; the iOS job was **never acquired by a hosted macOS ARM64
   runner** (GitHub capacity: "The job was not acquired by Runner of type
   hosted even after multiple attempts") and died in the queue; the attach
   job skipped.
2. `gh run rerun --failed` was refused by GitHub ("its workflow file may be
   broken" — the same API behaviour as the 0.1.14 cycle). Recovered the
   documented way: delete + re-push the same tag object (commit unchanged,
   `82ab509`, verified before every re-push).
3. Run 41868487081: the macOS queue killed the iOS job the same way; the
   draft job then stopped **by design** — the fail-closed digest guard found
   run 1's assets with different digests (every Android rebuild produces
   different bytes) and preserved them instead of overwriting. This is the
   guard working as built.
4. Run 41871404458: with the draft's stale run-1 assets cleared (recorded
   below), the whole Android path reproduced cleanly: fresh signed APKs,
   fresh checksums, draft body re-written from the approved notes file.
5. The iOS build was then run through the standalone `ios-release.yml`
   workflow (Release configuration) to avoid re-triggering the full
   pipeline, and the produced IPA is attached to the draft under the
   release asset name.

## Assets cleared during the cycle (deleted by asset ID, digests recorded)

Run 1 Android assets (superseded by run 3's): arm64 55,001,290 B
`6a1b0456d98b3ca50fc518328fb5248a7df50b625a76ff33e612a77f79a96edc`;
armv7 53,118,154 B `4b797f55793454f6cc7d20f1d66917b578f1a037d2b9949b577eb08ffb7be3eb`;
x86 55,445,968 B `341703327682f5f73628d2520d73cb8d15aaf480c90ef0971d439e1eb7ba3035`;
x86_64 56,500,704 B `0f3c1995540c0525b86d07d6802fb529facfc4b33a940c23e606d83b957fb92d`;
checksums.sha256 `0a7de766bc958169abbb127c6e177f489853e8b7bb24bf8c1b29ba9d0f33e7c4`.

## Final draft assets (run 3, to be re-verified before publication)

Recorded at publication time in the release body and below.

## Sideload source

`update-store-source.yml` is dispatched after publication. Unlike the
0.1.14 same-version rebuild, 0.1.15 is a new version/code, so the updater
appends a new entry normally.
