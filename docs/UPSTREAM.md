# StreamBridge source baselines — 0.5.5-beta generation

**Verified 2026-10-01.** The canonical file is
[`streambridge.version.properties`](../streambridge.version.properties).
No moving branch is substituted for either verified release.

| Source | Exact commit | Reference |
| --- | --- | --- |
| Clean StreamBridge development baseline | `ee6f99b20ef06236029979d1636ebde5b151d677` | Parent of the first CloudStream integration commit |
| Latest clean published StreamBridge release | `936e116acb940103bf7db2d13ec632d9ebaa3bab` | `0.1.04`, published 2026-09-14 |
| Official NuvioMobile | `5c6028f24d3017f27fc4aeb0b6234b18fcfd2ee8` | Actual [`0.5.5-beta`](https://github.com/NuvioMedia/NuvioMobile/releases/tag/0.5.5-beta) release, published 2026-09-30 19:22:54 UTC |
| NuvioMobile Enhanced | `8b3bd867e172757c9fd7373da301e6489914f3fc` | Actual [`0.5.5-beta`](https://github.com/luqmanfadlli/NuvioMobile-Enhanced/releases/tag/0.5.5-beta) release, published 2026-10-01 05:54:34 UTC |
| Previous Enhanced generation on the clean baseline | `ce4492ec057928b330105a2ad0ed54427f3a82e1` | Preservation/three-way comparison reference only |
| Old official merge-base | `cbc921d910c1c555fbbf683d438b1ba850df23ca` | Official-versus-Enhanced provenance comparison |
| Nuvio Engine | `f2217b8c6ac4563046be2a55a06981b6b4d1152b` | Annotated tag `v0.1.2` (tag object `9e400652b71077a67e20bef43f67de77c1445473`) |
| MPVKit | `bb1d0250ddcfa9d220761fcdad6011ac9fecf15e` | Pinned local submodule from NuvioMedia/MPVKit |

Both 0.5.5-beta release pages and APIs genuinely exist, with four Android ABI
assets and an IPA. Their API `isPrerelease` values are false despite beta names.
StreamBridge deliberately uses its own prerelease/beta policy. Upstream assets
prove those releases exist; they are not StreamBridge build validation or assets.

The official release commit is an ancestor of the Enhanced release. Enhanced's
additional official commit `d667f432` is `chore(store): publish 0.5.5`, store
metadata only, not a different application generation. The Enhanced moving head
`1b8d5c48783752b3aaf4a835011e9491d6f737c2` was not substituted for its tag.

The repository's initial `main` (`c52a2d7`) and published `v0.1.07`
(`a5fe7269aa63fac2b6c6775ca20b0eea954544a1`, 2026-09-26) already include the
experimental CloudStream runtime. They are not the clean starting tree. The
first integration `789a391` and its descendants are excluded from this update's
ancestry. `6d4d5aa`, `c0538b1`, and the separate unstable development branch are
not merged, cherry-picked, rebased or otherwise imported. Main is not changed.

The update stays on `arena/01a0f91c-mybridge`. For feature/conflict decisions,
created commits and validation evidence, see [UPSTREAM-SYNC-0.5.5.md](UPSTREAM-SYNC-0.5.5.md),
the [493-path provenance map](upstream-sync-0.5.5-files.tsv),
and [VALIDATION-0.5.5.md](VALIDATION-0.5.5.md).

GPL licensing applies to the runnable fork. Required original copyright,
source credits and dependency notices remain in source and the application.
The unbuilt MIT prototype does not relicense the application.
