# Telegram historical inventory snapshot — 2026-11-06

This is **evidence for review, not a complete-history attestation or permission
to publish**. The snapshot was collected read-only at approximately
2026-11-06 03:02 UTC. No Telegram login, post, upload, release/tag change or
ledger deployment was performed.

## Known public announcement

- The anonymous [public channel view](https://t.me/s/StreamBridge_App) showed
  [message 5](https://t.me/StreamBridge_App/5), announcing **StreamBridge
  v0.1.15** and linking to its GitHub release. GitHub identifies that release as
  `434756896`. Its author and relationship, if any, to the legacy workflow are
  not established by this observation.
- The view also showed channel service messages 1 and 2. IDs 3 and 4 were not
  visible; do **not** infer whether they were deleted, unavailable or contained
  announcements. The `before=5` and `after=5` public views did not provide a
  complete authenticated export or prove that no other historical messages
  existed. Public previews and GitHub APIs cannot establish deleted/private
  channel history or rule out another publisher.
- The default-branch schema-1 ledger has zero records, which is demonstrably
  **not** a complete inventory of announcements. Its legacy blob at the audited
  main commit `04ae2eb70c5cfc19ae7bbc4eef74d09a87e862d5` is
  `e195b31c3371b777cb995f67e38e5b3936157e56`.

## GitHub snapshot and reproducibility

The authenticated read-only, paginated release API returned 16 releases,
including one visible draft. The Git refs API returned 21 current tag refs;
the union of release tag names and refs contained 22 names. Neither API proves
that deleted releases/tags or drafts invisible to the caller never existed.
Re-run these commands with appropriate read access and retain the results
privately; the release response may include unpublished draft metadata:

```sh
gh api --paginate 'repos/sascio/MyBridge/releases?per_page=100'
gh api 'repos/sascio/MyBridge/git/matching-refs/tags/'
gh api 'repos/sascio/MyBridge/actions/workflows/407947021/runs?per_page=100' --paginate
gh api 'repos/sascio/MyBridge/actions/runs/42004825171/jobs?per_page=100'
```

An earlier audit prepared a private local migration-input snapshot with 16
release IDs, 22 tag names and observed message ID `5`; those scratch files are
not retained in the current workspace. They were a snapshot, not a reviewed,
complete inventory or a freeze. The owner must independently retain and review
private evidence without publishing draft metadata. A digest of the raw inventory
was included in an earlier public PR revision; it may permit guesses of a
non-public tag, and removing it here does not erase Git history. Do not republish
raw private snapshots or their unkeyed digests.

The sole visible run of the old workflow was manual run
[42004825171](https://github.com/sascio/MyBridge/actions/runs/42004825171) at
`04ae2eb...`. Its publish step succeeded while the asset-delivery and ledger
recording steps were skipped. Step metadata alone does not prove which input
was used or whether any historic message was sent. Message 5 independently
proves at least one announcement existed, not that this run sent it.

## Rechecked public identities — unapproved candidate

At 2026-11-06 13:42 UTC the paginated GitHub API still reported 15 public
release identities, one non-public release identity, 21 existing tag refs and
22 distinct release/ref tag names. These counts match the earlier preliminary
preview; the additional union name belongs to the protected non-public release.
This is a read-only observation, **not** a release/tag freeze or approved cutoff.
The protected identity and its tag are deliberately omitted below.

All 15 public releases below are proposed `historical_excluded` with reason
`pre-ledger historical release; replay prohibited`, irrespective of proof of a
Telegram announcement. Only `v0.1.15` has the independently observed message 5
evidence above; no delivery timestamp is inferred. The source SHA is the
commit resolved through the exact Git ref, including dereferencing annotated
tags. The candidate also HMAC-binds every ref name, object kind and object SHA,
including non-public names, without publishing those names. The inventory
fingerprint and chosen candidate cutoff are reported by the current-head
owner-review preview; they do not establish completeness.

| Published tag | GitHub release ID | Published UTC | Resolved source commit |
| --- | ---: | --- | --- |
| 0.1.01 | 387689712 | 2026-09-12T21:14:35Z | c18164d6c18751b90f15c5cc2b4045e155c5541a |
| 0.1.02 | 387726965 | 2026-09-12T22:55:56Z | d29d68942d32a270a650c9d46eccff8156591ce1 |
| 0.1.03 | 387780380 | 2026-09-13T03:56:52Z | f08218d989f00bd89b7c7d1393660f6b3e30aef6 |
| 0.1.04 | 388298232 | 2026-09-14T10:21:18Z | 936e116acb940103bf7db2d13ec632d9ebaa3bab |
| v0.1.05 | 392242106 | 2026-09-19T22:45:52Z | 0da7e83d95874d480789b58e0e28cd3912978cbe |
| v0.1.06 | 396937291 | 2026-09-25T21:34:23Z | 35ab96c9f0f554b61fd39013c9d19087f8f0a41e |
| v0.1.07 | 397204180 | 2026-09-26T11:24:31Z | a5fe7269aa63fac2b6c6775ca20b0eea954544a1 |
| v0.1.08 | 402741322 | 2026-10-05T23:47:00Z | 9149c22c8c7bc3e4a426219a1212c6f4fd1c73ff |
| v0.1.09 | 404239275 | 2026-10-06T02:13:37Z | a796cd025d67d1a6f2762fac152348f419df9c3e |
| v0.1.10 | 415704351 | 2026-10-17T09:31:57Z | 913de2c73d17c36a1da0ed391c01480ae5992e08 |
| v0.1.11 | 417984841 | 2026-10-20T04:00:27Z | 322c236e51eacab379783ebcf26e1ba5d84407f4 |
| v0.1.12 | 418090879 | 2026-10-20T08:31:41Z | ea4787c96a1186141cd182c1579b1ac0d0ac65b5 |
| v0.1.13 | 420907375 | 2026-10-22T17:26:36Z | df1f1c9dd136cd5bf40a54e3809ed6336cbe8a0f |
| v0.1.14 | 432616187 | 2026-11-03T12:54:58Z | 415e557353dea5fcf0859f5ecd9036e5575c38a4 |
| v0.1.15 | 434756896 | 2026-11-05T06:45:29Z | e54cea91aec0d5bc5dbe925bc9a689bed9ad5f37 |

The following 21 tag refs were public at the recheck; the object SHA differs
from the resolved commit SHA for annotated tags. These refs and release IDs are
all blocked from later replay by the candidate inventory.

| Tag ref | Object type | Ref object SHA | Resolved commit SHA |
| --- | --- | --- | --- |
| 0.1.01 | commit | c18164d6c18751b90f15c5cc2b4045e155c5541a | c18164d6c18751b90f15c5cc2b4045e155c5541a |
| 0.1.02 | commit | d29d68942d32a270a650c9d46eccff8156591ce1 | d29d68942d32a270a650c9d46eccff8156591ce1 |
| 0.1.03 | commit | f08218d989f00bd89b7c7d1393660f6b3e30aef6 | f08218d989f00bd89b7c7d1393660f6b3e30aef6 |
| 0.1.04 | commit | 936e116acb940103bf7db2d13ec632d9ebaa3bab | 936e116acb940103bf7db2d13ec632d9ebaa3bab |
| preservation/main-pre-contributor-audit-2026-10-17 | tag | 15d27434653a1ab3c80684ccdec9d4d3b2c69e3e | 271ac498c79cc342d767973a6c13b951d882f025 |
| preservation/pre-clean-history-apply-2026-10-17 | tag | 956853f86712ab1b6c5e906cd7b41d73e1f8f04f | 271ac498c79cc342d767973a6c13b951d882f025 |
| preservation/pre-enhanced-051-sync | tag | ee55213293a84cfe7ff769dd0f06953e3ff98500 | 0da7e83d95874d480789b58e0e28cd3912978cbe |
| preservation/v0.1.15-82ab509 | commit | 82ab509d67ad786611085500cc5b2855966693ed | 82ab509d67ad786611085500cc5b2855966693ed |
| untagged-0d4266a3989c460094e3 | commit | 82ab509d67ad786611085500cc5b2855966693ed | 82ab509d67ad786611085500cc5b2855966693ed |
| v0.1.05 | commit | 0da7e83d95874d480789b58e0e28cd3912978cbe | 0da7e83d95874d480789b58e0e28cd3912978cbe |
| v0.1.06 | commit | 35ab96c9f0f554b61fd39013c9d19087f8f0a41e | 35ab96c9f0f554b61fd39013c9d19087f8f0a41e |
| v0.1.07 | commit | a5fe7269aa63fac2b6c6775ca20b0eea954544a1 | a5fe7269aa63fac2b6c6775ca20b0eea954544a1 |
| v0.1.08 | commit | 9149c22c8c7bc3e4a426219a1212c6f4fd1c73ff | 9149c22c8c7bc3e4a426219a1212c6f4fd1c73ff |
| v0.1.09 | tag | 0ae2a88d8cbf26e3d3cb2c1d37867f40a2ed913a | a796cd025d67d1a6f2762fac152348f419df9c3e |
| v0.1.10 | commit | 913de2c73d17c36a1da0ed391c01480ae5992e08 | 913de2c73d17c36a1da0ed391c01480ae5992e08 |
| v0.1.11 | tag | ded4759212a8debdee33882d391fe7c6e7ddd668 | 322c236e51eacab379783ebcf26e1ba5d84407f4 |
| v0.1.12 | tag | c78ebf431af024b650691785ebbf8c64289e0780 | ea4787c96a1186141cd182c1579b1ac0d0ac65b5 |
| v0.1.13 | commit | df1f1c9dd136cd5bf40a54e3809ed6336cbe8a0f | df1f1c9dd136cd5bf40a54e3809ed6336cbe8a0f |
| v0.1.14 | commit | 415e557353dea5fcf0859f5ecd9036e5575c38a4 | 415e557353dea5fcf0859f5ecd9036e5575c38a4 |
| v0.1.14-build-1 | commit | 25335785ad84a3fb85386d3a627cc32295fb767d | 25335785ad84a3fb85386d3a627cc32295fb767d |
| v0.1.15 | commit | e54cea91aec0d5bc5dbe925bc9a689bed9ad5f37 | e54cea91aec0d5bc5dbe925bc9a689bed9ad5f37 |

## Required cutover

1. Keep the legacy workflow ID `407947021` disabled and both publishing gates
   off. Stop all other Telegram writers; establish and document a continuous
   release/tag freeze before taking the authoritative snapshot.
2. An authorized channel administrator must audit public and non-public or
   archived channel evidence, deleted/recreated tags, old drafts, message IDs,
   bot accounts, and every other publisher. Record unknowns rather than treating
   absence from this snapshot as absence from history. The inventory must
   include every historical release ID and tag, including old drafts and
   deleted/reused refs, and every known announcement message ID.
3. Compare and independently review the inventory against the preserved legacy
   ledger and release/tag history. Choose a future cutoff and prepare a
   checksummed schema-4 migration only after the freeze is in place. Retain the
   unmodified schema-1/2 document and the independently reviewed inventory.
   The owner-triggered preview/initializer described in the runbook observes
   current API identities, not deleted or invisible ones or additional channel
   message IDs. If an independently known historical release, tag, or message
   ID is absent, do not initialize that candidate; prepare a separately reviewed
   protected migration that includes it. No preview has
   been frozen or approved and no migration has been deployed.
4. Verify the existing dedicated same-repository state branch and its
   no-force/no-delete controls as described in `TELEGRAM-ANNOUNCEMENTS.md`.
   Its current main-based tip is not an initialized ledger. Repository writers
   share access; real write/migration and crash recovery
   must be exercised without a Telegram send before activation. The public
   ledger must not reveal the draft identifier or tag.
5. Continue the freeze until the reviewed ledger, rules, gates and
   workflow are in place before the future fence. New plans are allowed only
   for genuine `release: published` events after the fence; every inventoried
   ID/tag is blocked. Manual dispatch is recovery-only for an already frozen
   plan. Never reuse a previously announced tag. If completeness, trusted writers,
   the freeze, or authorization cannot be established, leave publishing off.
