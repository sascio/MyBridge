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

## Published assets (SHA-256, release 434171421, published 2026-11-04)

| Asset | Size (B) | SHA-256 |
|---|---:|---|
| `androidApp-full-arm64-v8a-release.apk` | 55001290 | `509ea28568d7c43fe1d29af8fa3b749940406b458ae1098a130750182608ceda` |
| `androidApp-full-armeabi-v7a-release.apk` | 53118154 | `8973076861da46f58e29aa2df39877a7018a2db03d212c85caab6133933784ed` |
| `androidApp-full-x86-release.apk` | 55445968 | `4be2c714766f1620a4db6bcaf6f7103a5d9cfc80684eae52339ae20f88125499` |
| `androidApp-full-x86_64-release.apk` | 56500704 | `a8969f6ebbe32080144eae6d209137114e769e7a0222621daaea20b7e3a64744` |
| `checksums.sha256` | 577 | `5d6351ac7d39e1d28919b27aaff49dd81ce6efc82b5520a9a85c7b96475404a5` |
| `StreamBridge-0.1.15-iOS-unsigned.ipa` | 73619544 | `b361a3444091a75b8ad396441bb76bbcf60c8c92c4aae412df0e2bea69d01a28` |

The checksums asset was cross-checked: its SHA-256 equals the digest of the
file constructed from the four published APK digests. The IPA was verified
by the attach workflow (bundle id, version, build, unsigned) and then by
`update-store-source.py` itself, which downloads the release IPA and
re-verifies it before writing the sideload source entry.

## Draft tag detachment during the cycle

Twice during the cycle the draft release's `tag_name` was reset by GitHub to
`untagged-…` (once during the draft's lifecycle, once at the
draft→published transition). Both times it was re-associated with
`v0.1.15` by a single `PATCH tag_name` — the documented safe method from
the 0.1.14 cycle. The auto-triggered sideload-source run that fired in the
detached window failed with "release not found"; after the re-association
the updater was dispatched again and succeeded (run 41887660605).

## Sideload source

The updater appended the 0.1.15 entry normally (new version/code, so the
0.1.14 same-version limitation did not apply): version 0.1.15, build 115,
size 73619544, SHA-256 `b361a344…`, download URL pointing at the published
IPA. The served `store.json` on `main` was synced back onto the release
branch so both stay byte-identical.
