# Repository identity and contributor audit — 2026-10-17

Status: investigation complete, safe public-identity corrections applied, and a
clean-history migration **prepared but not applied**. No shared history was
rewritten, no release was modified, and no branch other than
`arena/3975be3d-mybridge` was pushed.

This document is the forensic record for the StreamBridge GitHub contributor
change that followed the 0.1.10 upstream sync and PR #212.

---

## 1. Release and repository baseline (captured before any change)

| Item | Value |
|---|---|
| Repository | `sascio/MyBridge` (public, not a fork) |
| Default branch | `main` |
| `main` at audit start | `271ac498c79cc342d767973a6c13b951d882f025` |
| `main` subject | `chore(store): publish v0.1.10` |
| Release tag | `v0.1.10` (lightweight) → `913de2c73d17c36a1da0ed391c01480ae5992e08` |
| Release | `StreamBridge v0.1.10`, id `415704351`, published `2026-10-17T09:31:57Z`, not draft, not prerelease |
| Backup reference | `preservation/main-pre-contributor-audit-2026-10-17` (annotated tag object `15d27434653a1ab3c80684ccdec9d4d3b2c69e3e`) → `271ac498c79cc342d767973a6c13b951d882f025` |

### Published release assets (unchanged)

| Asset | Size (bytes) | SHA-256 |
|---|---:|---|
| `androidApp-full-arm64-v8a-release.apk` | 54,268,506 | `4a8ec27a1bdd7f7eff48a9c0db04cefcb5d679b6066e7fe4b94711229e9e1c16` |
| `androidApp-full-armeabi-v7a-release.apk` | 52,508,873 | `dd8f1d762dc61fcf9019a0940ca9f1a99dd065f92f745dad1198f823be2f2282` |
| `androidApp-full-x86-release.apk` | 54,639,742 | `e360a8579d91b74f913b830d7db7b09340219128e634a051a425ea33b4e8f261` |
| `androidApp-full-x86_64-release.apk` | 55,616,062 | `67efad6d40576cd798f4b648cda2d4a023a1b2d651937b4815eaede07510e98e` |
| `checksums.sha256` | 577 | `a27fd9d550f74687e7dcc25aaa603a915419e35e843f9d9818852701d1caed43` |
| `StreamBridge-0.1.10-iOS-unsigned.ipa` | 72,119,899 | `8ba75abea68832184bed69b192ac49ff95d5e67337b77188e8e59ea333732864` |

### Tags at audit start

| Tag | Peeled commit |
|---|---|
| `0.1.01` | `c18164d6c18751b90f15c5cc2b4045e155c5541a` |
| `0.1.02` | `d29d68942d32a270a650c9d46eccff8156591ce1` |
| `0.1.03` | `f08218d989f00bd89b7c7d1393660f6b3e30aef6` |
| `0.1.04` | `936e116acb940103bf7db2d13ec632d9ebaa3bab` |
| `v0.1.05` | `0da7e83d95874d480789b58e0e28cd3912978cbe` |
| `v0.1.06` | `35ab96c9f0f554b61fd39013c9d19087f8f0a41e` |
| `v0.1.07` | `a5fe7269aa63fac2b6c6775ca20b0eea954544a1` |
| `v0.1.08` | `9149c22c8c7bc3e4a426219a1212c6f4fd1c73ff` |
| `v0.1.09` | `a796cd025d67d1a6f2762fac152348f419df9c3e` (annotated tag `0ae2a88d`) |
| `v0.1.10` | `913de2c73d17c36a1da0ed391c01480ae5992e08` |
| `preservation/pre-enhanced-051-sync` | `0da7e83d95874d480789b58e0e28cd3912978cbe` |

---

## 2. Root cause

### 2.1 StreamBridge has no shared git history with upstream

StreamBridge was built as an overlay copy of NuvioMobile. Before the 0.1.10
sync, `main` at `8ef8be84` contained **266 commits** and **seven name/email
author identities** (six distinct email addresses), all StreamBridge/automation:

- `sascio <321979606+sascio@users.noreply.github.com>`
- `sascio <saifali978540@gmail.com>`
- `arena-agent <agent@arena.ai>`
- `arena-agent <arena-agent@users.noreply.github.com>`
- `Arena Agent <agent@arena.ai>`
- `arena-ai-coding-agent[bot] <298482267+arena-ai-coding-agent[bot]@users.noreply.github.com>`
- `github-actions[bot] <41898282+github-actions[bot]@users.noreply.github.com>`

The tree has two root commits after the 0.1.10 sync:

| Root | Date | Author | Subject |
|---|---|---|---|
| `e52e5915271bbbf1445df6972ca5f24516aecc33` | 2026-08-28 | `sascio` | `Create build.yml` |
| `4b99274756e992a531e9539b04b2bbc444fd017c` | 2026-03-04 | `tapframe` | `kmp init` (NuvioMobile history) |

A repository with two roots and no common ancestor between its lines of
development cannot perform a conventional merge. `docs/UPSTREAM-SYNC.md`
already recorded this:

```
git merge-base HEAD nuvio/cmp-rewrite   -> (empty)
```

and prescribed source-level integration with a synthetic base commit, not a
history merge.

### 2.2 The introducing commit: `51924933`

```
commit 51924933b65ff5f4c6352351648c9821c8ddce0d
parents 721fe49033460ae097377b74cb3eb3a50dd3fd4b  (StreamBridge pre-sync)
        504014d360bf1a1f844246f527c27c208738246e  (NuvioMobile-Enhanced 0.6.1-beta)
author  sascio <321979606+sascio@users.noreply.github.com>
date    2026-10-17T01:52:40+00:00
subject merge: sync NuvioMobile Enhanced 0.6.1-beta into StreamBridge
```

Evidence that this was an unrelated-history merge:

```
$ git merge-base 721fe490 504014d3
(no output, exit 1)

$ git rev-list --count 504014d3
3329
```

The commit message states:

> Merge base: Enhanced `8b3bd867` (the previous Enhanced pin).

That premise is false for this repository:

```
$ git merge-base --is-ancestor 8b3bd867 721fe490
exit 1 (8b3bd867 is NOT an ancestor of StreamBridge)
```

`8b3bd867` **is** an ancestor of the upstream Enhanced 0.6.1 tip
(`504014d3`), which is why the merge appeared to have a base on the upstream
side. But it is not in StreamBridge's history. Git therefore treated the two
parents as unrelated; a merge can only be produced in that situation with
`--allow-unrelated-histories` (or an equivalent low-level operation).

Effect of that single commit:

- **3,329 upstream commits** became ancestors of the StreamBridge branch,
  adding **95 name/email author identities** (87 distinct email addresses)
  that did not exist in StreamBridge before the merge.
- Source-level change from the StreamBridge side was 472 files,
  +53,186/−3,647 (`git diff --shortstat 51924933^1 51924933`). The history
  import is two orders of magnitude larger than the source change it records.

### 2.3 How it reached `main`: PR #212 / merge `913de2c7`

```
$ git log -1 --format='%H %P %s' 913de2c7
913de2c73d17c36a1da0ed391c01480ae5992e08
  8ef8be84a6088138920960f8cfccd602195ea2ae
  0988a1e9a00ab690dd04b9444748437edefa6796
Merge pull request #212 from sascio/arena/98ec96d3-mybridge
```

- PR #212 head `0988a1e9` is a descendant of `51924933`
  (`git merge-base --is-ancestor 51924933 0988a1e9` → success).
- `51924933` is **not** an ancestor of the pre-merge `main` (`8ef8be84`).
- PR #212 reported 3,356 commits and 490 changed files; its tree
  (`fec11e5e`) is identical to the merge commit's tree.
- `913de2c7` is the commit that made the imported history reachable from
  `main`. It is a normal, correctly formed merge commit; it is the delivery
  vehicle, not the origin of the extra authors. The origin is `51924933`.

`51924933` is the **only** unrelated-history merge in `main`. A scan of every
merge commit reachable from `main` for an empty merge-base between its parents
returned exactly one commit: `51924933`.

### 2.4 Measured before/after

| Metric | Before PR #212 (`8ef8be84`) | After (`271ac498`) |
|---|---:|---:|
| Commits reachable from `main` | 266 | 3,624 |
| Commits added | — | 3,358 |
| Upstream commits imported | 0 | 3,329 |
| Name/email author identities in history | 7 (6 distinct emails) | 102 (93 distinct emails) |
| GitHub contributor accounts | not versioned by GitHub; git shows only StreamBridge/automation identities | 78 |
| First-parent commits added | — | 2 (`913de2c7`, `271ac498`) |

The GitHub Contributors page is not a versioned API, so no historical HTML
snapshot exists to diff. The before-state is established from the commit graph
(`8ef8be84`) and the after-state from GitHub's live API plus the same graph.

### 2.5 Proof that GitHub is counting reachable commit authors

GitHub's counts match an author count over `main` exactly, including the
multiple emails linked to the same account:

| GitHub account | GitHub contributions | Commits in `git log main` by linked identity |
|---|---:|---|
| `tapframe` | 2,083 | `tapframe` 1,814 + `Nayif` 237 + `Muhammed Nayif Rahman` 32 = 2,083 |
| `luqmanfadlli` | 382 | `luqman.fadlli@gmail.com` 310 + `75630095+luqmanfadlli` 72 = 382 |
| `sascio` | 240 | `321979606+sascio` 203 + `saifali978540@gmail.com` 37 = 240 |

Current top of the live contributors API (78 accounts total):

```
2083 tapframe          148 chrisk325          66 paregi12          40 halibiram
 382 luqmanfadlli       87 skoruppa            57 github-actions[bot] 37 blueocean2308
 240 sascio
```

These are upstream authors, not new StreamBridge development. The upstream
project (`NuvioMedia/NuvioMobile`) shows the same leading accounts.

### 2.6 What is *not* the cause

- The v0.1.10 release build did not add contributors. Releases and tag pushes
  do not change commit authorship.
- `271ac498` (`chore(store): publish v0.1.10`) is authored by `sascio` and
  changed `store.json`; it is after the release and added no upstream authors.
- The 0.1.09 and earlier history contains only the seven StreamBridge/automation
  identities. A source copy does not import authorship unless upstream commits
  are merged in.
- No other merge, rebase, or history rewrite in `main` introduces upstream
  ancestry.

---

## 3. Why README edits cannot fix the contributor list

GitHub's Contributors page and contribution graph are derived from commit
authorship reachable from the **default branch**. They are not driven by README
content, `NOTICE`, `CODEOWNERS`, `.mailmap`, or repository description. GitHub
provides no supported setting or API to hide or remove a contributor whose
commits are in the default branch history.

Therefore:

- Rewriting README/description corrects the **public identity** of the project.
- It cannot remove `tapframe`, `luqmanfadlli`, or the other upstream authors
  from GitHub's contributor statistics.
- Only one of these can change the statistic:
  1. rewriting the default branch so those commits are no longer reachable
     (the prepared migration below); or
  2. changing the default branch to a branch that does not contain them
     (not recommended; see §6.3); or
  3. deleting the repository/account history (destructive and unacceptable).

Legally and ethically, upstream authorship must remain credited. The goal is
accurate attribution, not erasure. The prepared migration keeps upstream
credit in `NOTICE`, README, and the preserved `v0.1.10` tag/backup reference,
while preventing imported commits from dominating StreamBridge's own
contributor statistics.

---

## 4. Safe corrections applied

Only repository-facing text was changed. No source, build, signing,
application ID, release asset, or tag was modified.

| File | Change |
|---|---|
| `README.md` | Presents StreamBridge as an independent GPL-3.0 product; adds current upstream pins (0.6.1-beta); clarifies that `MyBridge` is a historical repository name; adds a project-identity/upstream-credit section and a contributor-list note. |
| `NOTICE` | Adds independence and imported-history statements; updates NuvioMobile and NuvioMobile-Enhanced pins from 0.5.5-beta to 0.6.1-beta; retains all copyright and GPL-3.0 notices. |
| `docs/UPSTREAM.md` | Updates the baseline table to 0.6.1-beta and records the unrelated-history import. |
| Repository description | Replacement text prepared; **not applied** because the GitHub integration token received HTTP 403 from `gh repo edit` (see §4.1). |
| `docs/REPOSITORY-IDENTITY-AUDIT.md` | This record. |

Preserved untouched: `applicationId = com.streambridge.app`, iOS bundle
identifier `com.streambridge.app`, signing configuration, `store.json`,
`.github/workflows/**`, Gradle configuration, and every release asset.

### 4.1 Repository description

Current description is still the stale `MyBridge Android Stremio Bridge`.
The attempt to update it failed:

```
$ gh repo edit sascio/MyBridge --description "..."
HTTP 403: Resource not accessible by integration
```

The integration token can push git refs and read releases but cannot edit
repository settings. Apply this replacement manually in **Settings → General →
Description**, or with a token that has repository administration permission:

> StreamBridge — an independent GPL-3.0 media client for Android and iOS, built
> on NuvioMobile.

The repository **name** (`MyBridge`) is deliberately not renamed: renaming
would break clone URLs, the in-app updater's `sascio/MyBridge` target, the
AltStore source URL, and every published release link.

---

## 5. Prepared clean-history migration (NOT applied)

### 5.1 Design

Keep all 266 StreamBridge-native commits up to the pre-sync state
`8ef8be84`, replace the imported upstream history with **three** commits whose
trees reproduce the released state plus this identity correction:

```
... a796cd02 (v0.1.09)
    8ef8be84  chore(store): publish v0.1.09          (existing)
    7f4ef415  merge: sync NuvioMobile Enhanced 0.6.1-beta into StreamBridge
              (squashed source import; tree = PR #212 tree fec11e5e)
    1d719c0d  chore(store): publish v0.1.10          (tree = 387acbb5, released tree)
    <tip>     docs: correct StreamBridge identity and contributor attribution
              (tree = the identity-correction branch head)
```

- Sync commit: `7f4ef41597d229801f4477615c681db3c60165ed`
  - parent `8ef8be84a6088138920960f8cfccd602195ea2ae`
  - tree `fec11e5e8c9d98248740a7675e422d6a921a18f7`
    (identical to PR head `0988a1e9` and merge `913de2c7`)
  - author/committer `sascio`, date `2026-10-17T01:52:40+00:00`
- Release commit: `1d719c0df97c8037a0f4bbb1d79bb4d9905be35c`
  - parent `7f4ef41597d229801f4477615c681db3c60165ed`
  - tree `387acbb57aa95a992380bc25c915b456023071f0`
    (identical to `271ac498`, the released `main`)
  - author/committer `sascio`, date `2026-10-17T09:32:18+00:00`
- Documentation commit: parent `1d719c0d`, tree identical to the identity
  correction head of `arena/3975be3d-mybridge` (the commit that adds this
  document, the README/NOTICE updates, and the corrected upstream pins).
  Its SHA is the tip of the migration ref; it cannot be embedded in this file
  without changing the tree it describes. Obtain it with
  `git rev-parse refs/migration/clean-history-0.1.10`; it is also recorded in
  the handoff report that accompanies this branch.
- History length: **269 commits** (266 + 3)
- Author identities: the same **seven** StreamBridge/automation identities as
  before the sync; no upstream accounts.
- New commits are unsigned. Re-sign them if signed commits are required.

Location and packaging:

| Item | Path / ref |
|---|---|
| Isolated clone (no checkout, shared object store) | `/home/user/MyBridge-clean-history` |
| Proposal ref | `refs/migration/clean-history-0.1.10` |
| Portable bundle | `/home/user/MyBridge-clean-history-0.1.10.bundle` (~17 MB; requires `8ef8be84`) |

The migration ref exists only in the isolated clone. It has **not** been
pushed to GitHub.

### 5.2 Verification procedure (run against the rebuilt tip)

The 266-commit base and the two source/release commits were verified before the
documentation commit was added. After the documentation commit is rebuilt, run:

```bash
TIP=$(git rev-parse refs/migration/clean-history-0.1.10)
BRANCH_HEAD=<identity-correction head, recorded in the handoff>

git diff --quiet "$TIP" "$BRANCH_HEAD" ; echo $?
# 0                     # migration tip tree == reviewed branch head

git diff --quiet "$TIP~1" 271ac498 ; echo $?
# 0                     # post-release store commit tree == main @ 271ac498

git diff --quiet "$TIP~2" 913de2c7 ; echo $?
# 0                     # sync commit tree == v0.1.10 release tree

git diff --stat 271ac498 "$TIP"
# only README.md, NOTICE, and docs/*.md differ from post-release main

git rev-list --count "$TIP"
# 269

git log "$TIP" --format='%an <%ae>' | sort -u
# seven StreamBridge/automation identities only
```

The portable bundle is verified by fetching it into a throwaway clone and
comparing the fetched tip tree with `BRANCH_HEAD`; the diff must be empty.
`git bundle verify` must report the bundle as valid.

### 5.3 Exact proposed change (requires explicit approval)

Nothing below has been executed.

```bash
# 0. Preconditions: clean working tree; no open PRs; owner approval recorded.
cd /home/user/MyBridge-clean-history
git fetch origin main --tags

test "$(git rev-parse origin/main)" = \
  "271ac498c79cc342d767973a6c13b951d882f025" || { echo "main moved"; exit 1; }

# 1. Capture the migration tip and re-confirm content identity.
TIP=$(git rev-parse refs/migration/clean-history-0.1.10)
BRANCH_HEAD=<identity-correction head, recorded in the handoff>
git diff --quiet "$TIP" "$BRANCH_HEAD" || { echo "tree mismatch"; exit 1; }

# 2. Rewrite main only, guarded by the exact observed value.
git push --force-with-lease=main:271ac498c79cc342d767973a6c13b951d882f025 \
  origin "$TIP":refs/heads/main
```

`--force-with-lease` fails if `main` moved after the audit, preventing an
accidental overwrite of new work. The command rewrites only `main`; tags,
releases, and assets are not touched.

### 5.4 Expected effect

- `main` becomes the migration tip: `1d719c0d` (release) plus one documentation
  commit whose tree equals the reviewed identity-correction branch head.
- Compared with post-release `main` (`271ac498`), the only content differences
  are `README.md`, `NOTICE`, and `docs/*.md`. Compared with tag `v0.1.10`, the
  differences are those docs plus `store.json`, which was already updated by
  `271ac498` after the release. Every build setting, application ID, workflow,
  source file, and release artifact is byte-identical. Only commit ancestry
  (and the documentation correction) changes.
- GitHub's contributor graph, after its cache refreshes (typically minutes to
  24–48 hours, occasionally longer), should show only StreamBridge/automation
  accounts for `main`, instead of 78 accounts dominated by upstream authors.
- Upstream commits remain reachable through the untouched `v0.1.10` tag
  (`913de2c7`) and the backup tag, so the complete pre-rewrite history is
  preserved for audit and rollback.
- The v0.1.10 GitHub release remains published and downloadable; it points at
  tag `v0.1.10`, which is not modified.

### 5.5 Rollback

The backup tag makes rollback a single guarded force-push:

```bash
# If main is at the clean migration tip and rollback is required:
TIP=$(git rev-parse origin/main)
git push --force-with-lease=main:"$TIP" \
  origin 271ac498c79cc342d767973a6c13b951d882f025:refs/heads/main
```

Equivalent, using the backup tag from the main clone:

```bash
TIP=$(git rev-parse origin/main)
git push --force-with-lease=main:"$TIP" \
  origin refs/tags/preservation/main-pre-contributor-audit-2026-10-17^{commit}:refs/heads/main
```

Verify afterwards:

```bash
git fetch origin main
git rev-parse origin/main          # 271ac498c79cc342d767973a6c13b951d882f025
git diff --quiet origin/main refs/tags/preservation/main-pre-contributor-audit-2026-10-17^{commit}
```

Recovery does not depend on GitHub reflogs or garbage-collection timing: the
backup tag is an independent ref at the exact old `main` commit.

---

## 6. Alternatives considered

### 6.1 Keep history, fix only public identity (lowest risk) — **recommended default**

- Apply the README/NOTICE/description corrections (README/NOTICE/docs applied;
  description needs a token with administration permission, see §4.1).
- Keep `main` at `271ac498`.
- Accept that GitHub's contributor list includes upstream authors, and explain
  why in README/this document.
- No force-push, no clone/fork disruption, no release risk.

This is sufficient if the requirement is an accurate product identity with
correct upstream credit. It does **not** satisfy a requirement that the
contributor page list only StreamBridge maintainers.

### 6.2 Clean-history rewrite of `main` (prepared, approval-gated)

- Removes upstream authors from default-branch statistics.
- Keeps every StreamBridge-native commit and the exact released tree.
- Preserves upstream history in `v0.1.10` and the backup tag.
- Requires a force-push of the default branch and disrupts existing clones and
  forks (see §7).
- This is the only supported way to change the contributor statistic while
  keeping the repository name, release URLs, and default branch.

### 6.3 Switch the default branch to a new clean branch (not recommended)

- Push the migration commit as a new branch and change the default branch,
  leaving `main` untouched. Avoids force-push.
- But the repository's canonical branch would no longer be `main`; clone
  instructions, `raw.githubusercontent.com/.../main/store.json`, badges, and
  the in-app/sideload source URLs all assume `main`. `main` would remain a
  misleading stale branch containing the imported history.
- The contributor page follows the default branch, so it would change, but the
  operational breakage is worse than a guarded force-push.

### 6.4 `git filter-repo` pruning (not chosen)

- Technically possible, but it would need path/commit filtering plus manual
  conflict resolution, and the result is less predictable than replaying three
  verified tree-identical commits.
- The repository has a single, well-defined boundary (`8ef8be84`), so a clean
  replay is simpler and auditable.

---

## 7. Risks and impact if the rewrite is approved

| Area | Impact | Mitigation |
|---|---|---|
| Forks | 1 fork: `saif-ali-04/MyBridgeEnhanced` (last push 2026-09-26). It is far behind and will not share history after the rewrite. | Fork owner resets/rebases onto the new `main`; no open PRs are affected. |
| Existing clones | `git pull` sees divergent history and may create a merge that reintroduces imported upstream ancestry. | Users run `git fetch origin && git reset --hard origin/main` (after saving local work) or `git rebase --onto origin/main <old-base> <branch>`. |
| Open pull requests | None at audit time (`gh pr list --state open` → empty). | If one opens before approval, rebase it before rewriting. |
| Stale remote branches | Seven `arena/*` branches exist; they point into the old history. | Leave them; they are historical. Do not merge them after the rewrite without rebasing. |
| Tags and releases | All tags remain; `v0.1.10` remains at `913de2c7`. Release assets and checksums are unchanged. | No action. Do not move or delete published tags. |
| `git describe` | `v0.1.10` will no longer be an ancestor of `main`, so `git describe` on `main` reports `v0.1.09-4-g<tip>`. | Expected; tag `v0.1.11` on the next release restores normal behavior. Optionally add an informational tag on the clean commit, but do not replace `v0.1.10`. |
| CI | Force-push to `main` triggers `Build Stream Bridge` (`build.yml` on `push` to `main`). Release workflows are `workflow_dispatch`/`release`-event driven and are not triggered by the rewrite. | Watch the run after applying; expect a normal debug build. |
| Branch protection | `GET /repos/sascio/MyBridge/branches/main` reports `"protected": false`; force-push is permitted by branch settings. The protection endpoint itself returned 403 because the token lacks administration scope. | Confirm in repository settings before applying; no protection change is expected to be needed. |
| Commit signatures | Replacement commits are not GitHub-signed, unlike merge `913de2c7`. | Re-sign with the maintainer key before pushing if signed history is required. |
| Contributor cache | GitHub may keep old contributor data cached after the rewrite. | Wait 24–48 hours; verify with the contributors API. |
| Auditability | Default-branch history no longer shows granular upstream commits. | Full upstream history remains in tag `v0.1.10`, backup tag, and this document. |

---

## 8. Validation performed / required

### 8.1 Performed for this correction

- `main` and all tags recorded before changes; backup tag pushed and verified.
- Release metadata and asset SHA-256 digests captured before changes.
- Working branch `arena/3975be3d-mybridge` only; `main` not pushed or reset.
- Diff of this change contains documentation only (see the branch diff).
- Build-affecting files are byte-identical to tag `v0.1.10`; e.g.
  `androidApp/build.gradle.kts` still resolves
  `applicationId = "com.streambridge.app"`, and `Version.xcconfig` still has
  `MARKETING_VERSION=0.1.10`, `CURRENT_PROJECT_VERSION=110`.
- `store.json` remains the v0.1.10 sideload record with matching IPA SHA-256.
- Migration base/source/release trees verified equal to `main` and `v0.1.10`;
  bundle verified after rebuild.

### 8.2 Required after any approved rewrite

```bash
git fetch origin main --tags
TIP=$(git rev-parse origin/main)
git diff --stat v0.1.10 "$TIP"                 # store.json (post-release) + docs only
git diff --quiet "$TIP" <reviewed branch head> && echo "tree identical"
gh release view v0.1.10 -R sascio/MyBridge --json assets   # digests unchanged
git log "$TIP" --format='%ae' | sort -u        # no upstream emails
gh api "repos/sascio/MyBridge/contributors?per_page=100"   # after cache refresh
```

Then confirm a normal build:

```bash
./gradlew :androidApp:assembleFullDebug
```

Release artifacts must not be rebuilt or re-uploaded as part of this correction.

---

## 9. Reproduce the evidence

```bash
# The only unrelated-history merge in main
for c in $(git rev-list --merges main); do
  git merge-base "$c^1" "$c^2" >/dev/null 2>&1 || echo "$c"
done
# -> 51924933b65ff5f4c6352351648c9821c8ddce0d

git log -1 --format='%H%nparents %P%n%s' 51924933
git merge-base 51924933^1 51924933^2 || echo "no merge base"
git rev-list --count 51924933^2                  # 3329
git merge-base --is-ancestor 8b3bd867 51924933^1 || echo "claimed base is not in StreamBridge"
git merge-base --is-ancestor 51924933 0988a1e9 && echo "PR #212 head contains the merge"
git merge-base --is-ancestor 51924933 8ef8be84 || echo "pre-merge main did not"

# Author explosion
git rev-list --count 8ef8be84                    # 266
git rev-list --count main                        # 3624
git log 8ef8be84..main --format='%ae' | sort -u | wc -l

# Contributor-count match
git log main --format='%ae' | sort | uniq -c | sort -rn | head
```

---

## 10. Bottom line

- **Introduced by:** `51924933b65ff5f4c6352351648c9821c8ddce0d`, an
  unrelated-history merge of NuvioMobile-Enhanced 0.6.1-beta
  (`504014d3`) into StreamBridge.
- **Made public on `main` by:** PR #212 merge commit
  `913de2c73d17c36a1da0ed391c01480ae5992e08`; the post-release store commit
  is `271ac498c79cc342d767973a6c13b951d882f025`.
- **Release status:** v0.1.10 and every earlier release remain published and
  unchanged; the release tree is preserved byte-for-byte.
- **Safest correction:** README/NOTICE/description fixes (README/NOTICE/docs
  applied; description needs a permissioned token) and, only if the
  contributor page must exclude upstream authors, the guarded clean-history
  migration in §5, with explicit approval.
- **Backup:** `preservation/main-pre-contributor-audit-2026-10-17` →
  `271ac498c79cc342d767973a6c13b951d882f025`.
- **History rewriting is not required** for release integrity or for accurate
  StreamBridge branding and upstream credit. It is required only for the
  contributor statistic itself.