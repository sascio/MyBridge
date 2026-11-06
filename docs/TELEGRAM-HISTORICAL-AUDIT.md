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

The audit prepared a **private local** migration-input snapshot, including the
visible draft's ID and tag, at `telegram-audit-private/inventory-2026-11-06.json`
and its provenance at `telegram-audit-private/evidence-2026-11-06.json`. The
folder is ignored by Git. Do not publish
these files without reviewing draft confidentiality. The inventory contains
16 release IDs, 22 tag names and the observed message ID `5`. It is a snapshot,
not a reviewed, complete inventory or a freeze. A digest of the raw inventory
was included in an earlier public PR revision; it may permit guesses of a
non-public tag, and removing it here does not erase Git history. Do not republish
raw private snapshots or their unkeyed digests.

The sole visible run of the old workflow was manual run
[42004825171](https://github.com/sascio/MyBridge/actions/runs/42004825171) at
`04ae2eb...`. Its publish step succeeded while the asset-delivery and ledger
recording steps were skipped. Step metadata alone does not prove which input
was used or whether any historic message was sent. Message 5 independently
proves at least one announcement existed, not that this run sent it.

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
   checksummed schema-3 migration only after the freeze is in place. Retain the
   unmodified schema-1/2 document and the independently reviewed inventory.
   The local preview created during this audit is a simulation, **not** an
   approved migration.
4. Provision and verify the dedicated same-repository state branch and its
   no-force/no-delete controls as described in `TELEGRAM-ANNOUNCEMENTS.md`.
   Repository writers share access; real write/migration and crash recovery
   must be exercised without a Telegram send before activation. The public
   ledger must not reveal the draft identifier or tag.
5. Continue the freeze until the reviewed ledger, rules, gates and
   workflow are in place before the future fence. New plans are allowed only
   for genuine `release: published` events after the fence; every inventoried
   ID/tag is blocked. Manual dispatch is recovery-only for an already frozen
   plan. Never reuse a previously announced tag. If completeness, trusted writers,
   the freeze, or authorization cannot be established, leave publishing off.
