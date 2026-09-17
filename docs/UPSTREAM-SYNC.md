# Upstream Nuvio sync status

## Current pin

| Field | Value |
|---|---|
| Upstream repo | https://github.com/NuvioMedia/NuvioMobile |
| Branch | `cmp-rewrite` |
| Commit | `cbc921d910c1c555fbbf683d438b1ba850df23ca` |
| Release | 0.4.23 (`chore(store): publish 0.4.23`, 2026-09-17) |

## Important: upstream rewrote history

The previously recorded pin `d177eb57dfc8989e56de577bda8f4c2714b2bd64`
(0.4.17) **no longer exists** in the upstream repository. It is not an
ancestor of `cmp-rewrite` and `git cat-file` cannot resolve it.

StreamBridge was also imported as a squashed commit, so there is **no merge
base** with upstream:

```
git merge-base HEAD nuvio/cmp-rewrite   -> (empty)
```

A conventional `git merge` or `git rebase` against upstream is therefore
impossible. Sync must be done by tree comparison
(`git diff HEAD nuvio/cmp-rewrite`), not by merging.

## State of the core

Tree comparison against upstream `cbc921d9`, restricted to `composeApp`:

| Category | Count |
|---|---|
| Files only upstream has | 3 |
| Files only StreamBridge has | 143 |
| Files modified on both sides | 124 |

StreamBridge already carries the NuvioMobile-Enhanced overlay pinned at
`ce4492ec` / 0.4.23-beta, which tracks upstream 0.4.23. The core is therefore
**already current**; it is not an outdated fork. The 143 StreamBridge-only
files are the Enhanced overlay plus intentional StreamBridge work (Live TV,
downloads engine, OMDB ratings, player settings, logging, branding).

A wholesale file-level sync would delete those 143 files and is not
appropriate.

## The 3 upstream-only files

| File | Decision |
|---|---|
| `features/details/ImdbEpisodeRatingsRepository.kt` | **Not ported** — duplicate |
| `features/details/SeriesGraphApi.kt` | **Not ported** — duplicate |
| `features/home/components/CollectionCardRemoteImage.ios.kt` | iOS-only; not applicable to the Android target |

### Why the ratings files were not ported

Upstream fetches episode ratings from the IMDb ratings API via
`ImdbEpisodeRatingsRepository` + `SeriesGraphApi`.

StreamBridge already implements the same capability through
`OmdbEpisodeRatingsService`, with persistent storage
(`OmdbEpisodeRatingsStorage`), user-facing settings (`OmdbSettingsRepository`,
`OmdbSettingsStorage`) and live wiring in `TmdbMetadataService`
(lines ~700–729).

Porting upstream's implementation would create a **second, competing episode
ratings engine** — explicitly against the "no duplicate implementations"
requirement — while replacing a working, settings-backed feature. The existing
OMDB implementation is retained.

## Verification

Re-run the comparison with:

```sh
git remote add nuvio https://github.com/NuvioMedia/NuvioMobile.git
git fetch nuvio
git diff --numstat HEAD nuvio/cmp-rewrite -- composeApp | awk -F'\t' '$2==0'  # upstream-only
git diff --numstat HEAD nuvio/cmp-rewrite -- composeApp | awk -F'\t' '$1==0'  # ours-only
```
