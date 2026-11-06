# StreamBridge Telegram release announcements

The workflow renders an official GitHub Release for **@StreamBridge_App** as
StreamBridge messages with the **Your Media, Your Way** motto, **Made with ❤️ by
Saif** footer, `#SB<release id>` markers, and **⬇️ Download StreamBridge**
buttons linking to the actual release assets. Published APKs and the unsigned
IPA are sent as individual documents, after the text pages. Drafts and
pre-releases are not announced. The workflow does not pin, create/edit releases,
change tags or invoke Android/iOS builds.

> **Deployment is blocked, not merely awaiting a switch.** The tracked ledger
> is still the original schema-1 file and is not proof of an empty channel. The
> new workflow is default-off through both a reviewed configuration file and a
> repository variable. The existing `main` branch rules require a PR with no
> bypass, so this implementation's direct ledger pushes are **incompatible**
> and its guard refuses to publish. Do not weaken those rules. A compliant durable
> state implementation must be designed and verified before activation.
>
> The legacy workflow on `main` was manually disabled during the 2026-11-06
> audit. It retains its original path and must **never** be re-enabled: old refs
> can contain the unsafe definition. The replacement uses a different workflow
> path/identity and its tracked configuration is false. Merging alone does not
> publish or replay releases. Cutover requires a complete historical release/tag
> inventory and release freeze: GitHub's `created_at` field alone does not prove
> when a release draft was created.

## Credentials and permission boundaries

The existing bot is the **only Telegram identity**: the repository secrets
`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `TELEGRAM_API_ID`, and
`TELEGRAM_API_HASH` are scoped only to the preflight or live delivery step. The
bot needs channel administrator **Post Messages** rights; pin rights are not
needed. The API ID/hash are MTProto application credentials for that same bot,
not a personal-account session. This design never asks for a phone number,
one-time code, password or user session. A fresh in-memory `StringSession` is
used and neither its authorization key nor credentials are written to the
ledger, checkout, logs or artifacts.

`preflight` and rendering jobs have `contents: read` and do not persist checkout
credentials. The live publisher alone has `contents: write`, needed to push the
ledger to the default branch; that permission technically permits more than the
ledger, so restrict workflow and branch access accordingly. The code writes only
`.github/telegram-releases.json`; it does not print request/exception objects or
credentials. Git command output is captured and not printed. GitHub's secret
masking is not treated as a security boundary. The workflow installs pinned
Telethon and transitive dependency versions.

The fresh bot session needs a public channel username that resolves to the same
numeric channel returned by Bot API `getChat`. A private channel that can only
be identified by a numeric ID/access hash is not supported; it fails rather
than guessing a peer. Changing the bot token or channel fails the HMAC-based
identity binding and requires an audited migration. Do not paste secret values
into issues, PRs, logs or the ledger.

### Repository security audit and required controls

The **2026-11-06** read-only audit found active `main` ruleset `26662733`:
non-fast-forward pushes and deletion are prohibited; a PR, one approval, review
thread resolution and approval for unattributed changes are required. Stale-review
dismissal, code-owner review and last-push approval are not required. The
ruleset response explicitly returned `bypass_actors: []`, and its effective
rules reported no required status checks. PR #266 had no reviews and was blocked
on approval. A separate classic branch-protection endpoint, repository Actions
permissions, runner and secret metadata, and the publishing-variable endpoint
returned **403**. An administrator must verify those inaccessible settings; an
empty visible ruleset bypass list is not evidence about separate controls or all
writers. Recheck all settings before using this runbook.

**The current ledger architecture cannot operate under that policy.** The
publisher commits directly to the default branch, but the visible PR rule has
no bypass. Its guard now deliberately rejects any active PR-only rule and the
live guard was verified to stop against the actual metadata before Telegram
access. No production push was attempted. Do not grant a bypass, remove review,
allow force pushes or weaken protection merely to make it run. Implement and
review a durable state mechanism whose allowed writes comply with the policy,
while preserving atomic compare-and-swap, no rollback/deletion, exclusive writer,
session fencing and durable ID invariants. Demonstrate that design under the
actual permissions and rerun its concurrency/crash/recovery tests before
activation. This PR does not yet provide that mechanism.

The publisher also requires visible non-fast-forward/deletion rules and restricts
live CLI use to the designated workflow, default-branch ledger and a matching
published-release event or explicitly tagged recovery dispatch. Missing or
unavailable metadata fails closed. These checks are necessary but **not
sufficient**: they do not prove exclusive writers, safe fast-forward changes,
secret access, historical refs, external publishers or resistance to ruleset
changes between a check and a request. An administrator must continuously audit
and enforce those controls. Restoring a rule after a rollback does not prove the
ledger was never tampered with. The visible required-check list is empty; a
maintainer must select and enforce appropriate eligible PR checks, and verify
classic protection separately rather than treating a manual CI run as a gate.

## Default-off activation gates

Both of these independently must be true before live delivery can run:

- `.github/telegram-publishing.json` must be a valid, duplicate-free reviewed
  object with exactly `enabled: true` and a UTC `not_before` timestamp, e.g.
  `{"enabled":true,"not_before":"2027-01-01T00:00:00Z"}`. Live delivery stays
  off until that timestamp passes; a release event also requires its GitHub
  `created_at` and `published_at` fields to be later. GitHub documents
  [`created_at`](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)
  as the release commit date, **not** the draft-creation date. The timestamps
  alone cannot prove a release or tag is new; the inventory and release freeze
  below are mandatory. The file is tracked as `{"enabled": false}` in this
  change; missing or malformed config fails closed. A future activation PR must
  deliberately update the regression test that currently asserts false.
- The repository Actions variable `TELEGRAM_PUBLISHING_ENABLED` must be exactly
  the lowercase string `true`. Missing or any other value is off. The audit's
  variable read returned 403, so its current value is **unknown**; the tracked
  false gate still prevents publishing in the new workflow.

The workflow checks both before the live job; the CLI checks both again. They
are independent of the release-age fence and schema-3 migration and do not
remove the branch-policy blocker. The preflight and dry render stay read-only
when activation is off. Merging this code or changing a gate is not a release
trigger and never initiates a backfill. No old workflow may be re-enabled during
this cutover.

## Read-only preflight and manual dispatch

`workflow_dispatch` defaults to `preflight: false` and `dry_run: true`. An
explicit `preflight: true` takes precedence over **all** other inputs and does
only Bot API `getMe`, `getChat`, `getChatMember` and syntax validation of the
API ID/hash. It checks channel type, bot administration and Post Messages
rights. It does **not** log in to MTProto, send, upload, pin, read/write the
ledger, or read/modify releases or tags; the Actions step summary reports the
scope of the check. A successful preflight does **not** establish MTProto
credential validity, large-file support, history access or publish readiness.

Preflight deliberately creates no MTProto session: it cannot both be
ledger-write-free and reserve a crash-safe session lock. A bot also cannot use
[`messages.getHistory`](https://core.telegram.org/method/messages.getHistory),
so a history scan must not be simulated as a successful bot check. Bot-supported
[`channels.getMessages`](https://core.telegram.org/method/channels.getMessages)
can check a **known** returned message ID; it is not a channel history search.
No preflight is run merely by merging this code.

With preflight off, manual dispatch resolves the selected `tag` or latest
published release and renders by default. A **live** manual dispatch must select
the default branch, specify a nonempty exact tag, set `dry_run: false` and
`recover_unknown: true`, and refer to an existing frozen release plan. It cannot
create a new plan or replay an old release by choosing `latest`. The CLI binds
that tag to the fetched release. Initial plans are permitted only for an actual
`release: published` event, bound to its release ID, and non-draft,
non-prerelease API release data. Both activation gates and the ledger age fence
also apply. `upload_assets: false` skips documents for that run only; a later
properly authorized recovery may deliver them without reposting confirmed text.

## Durable delivery protocol

1. One channel-wide Actions concurrency group covers releases and delivery
   dispatches, with cancellation disabled and a queue of at most 100 pending
   runs. Queue order is not an audit trail; excess runs can be canceled. Read-only
   preflights use separate per-run groups and cannot delay or replace a release.
2. The publisher checks the visible non-fast-forward/deletion rules and fetches
   the **remote default-branch** ledger; missing rules, missing/malformed state,
   unknown versions, inconsistencies, duplicate keys or an unactivated ledger
   stop the run. It checks the GitHub release `created_at` (commit date) and
   `published_at` fields against the activation fences and rejects historical
   inventory IDs/tags; it then freezes the rendered pages/buttons and asset
   metadata/checksums into hashes. It allocates a distinct nonzero signed
   64-bit `random_id` for every page and document, all before any public send.
3. A single-parent Git commit based on that exact fetched tip writes the ledger
   and session lock. A non-force push must succeed or be verified by re-fetching
   the commit and exact ledger bytes. A concurrent change is a conflict, not an
   excuse to overwrite. A failed/unverifiable write means **no next send**.
4. Bot API read checks and bot-only MTProto login bind the Telegram bot and
   channel identity to a keyed fingerprint. Exactly one MTProto client is used
   for all pages and files in that run. After use it attempts `auth.logOut`;
   the lock is released only after logout is confirmed and the remote update is
   verified. A crash or uncertain logout leaves the lock in place.
5. Each text uses raw `messages.sendMessage`; each binary uses file-part upload
   followed by raw `messages.sendMedia`. The operation's immutable payload hash,
   original ID, state and attempt count are durably recorded **before** the
   public request. Confirmed Telegram message IDs are committed individually,
   not batched at the end. For an asset, the local SHA-256 is frozen before
   uploading parts; a later download must match it. Published API/manifest
   checksums and sizes are verified when available; checksum manifests are limited
   to 2 MiB. If neither source publishes a digest, the first local hash only
   pins subsequent retries; it does not independently authenticate the first
   download.
6. Lost responses, timeouts, temporary server errors and short flood waits are
   recorded as `unknown` before bounded same-ID replay. Telegram documents that
   a reused `random_id` for the same account and peer returns the previous
   messages, or may return `RANDOM_ID_DUPLICATE` while the first send is in
   flight; that error is **not** confirmation. The transport checks the response
   mapping, peer, text, formatting, buttons and document name/size before
   recording the message ID. A known ID may be fetched via bot-supported
   `channels.getMessages`; an unmatched/absent response remains unknown.
7. Part uploads are temporary and are not public messages. An interrupted
   `uploading` operation can download/verify and upload the parts again; after
   `sending` begins, recovery must reuse the original `random_id`. Telegram
   retains parts only minutes to hours, so an expired handle may require another
   part upload. APK and IPA documents have separate IDs, states and confirmations;
   albums/grouped media are unsupported because atomic group recovery was not
   established. Processing is sequential, but an asset-specific download or
   temporary-part I/O failure before a public send does not prevent other assets
   from being attempted; the run still fails and preserves each state. A failed
   page, ambiguous public send, policy/ledger failure, changed frozen bytes or
   unresolved recovery stops later sends. Confirmed documents are not resent
   during reviewed recovery; this is not unrestricted failure independence.

The code freezes selected asset IDs, names, sizes, URLs, captions, MIME type and
available published hashes, as well as parsed text, formatting and buttons. A
change that alters those planned payloads **blocks** the rerun; edits that do not
change any rendered or selected payload need not. A changed plan is not treated
automatically as a new post. The original direct Bot API sender and high-level
`send_file` uploader are disabled: they cannot expose and durably reuse
Telegram's request ID across retries.

### What this does and does not guarantee

Telegram's [update documentation](https://core.telegram.org/api/updates#updatemessageid-updates)
describes non-expiring random-ID deduplication across sessions of the same
account and peer. It is an essential provider assumption, not a live test of
this repository. Collision with an ID used outside the ledger is extremely
unlikely but cannot be ruled out; response validation cannot verify a document's
actual server-side SHA-256. Message deletion, edits by others, Telegram API
changes, replay response shapes, state rollback/force-push, manual ledger edits,
external publishers, token rotation and malicious or partitioned old workers
are outside an absolute exactly-once proof. **Do not claim exactly-once.**

A write-ahead Git commit and workflow concurrency do not fence a worker that an
operator wrongly assumes is dead. The session lock must not be cleared while an
old worker could resume. Only this workflow may use the bot for automated
publishing; jobs in other repositories or manual tools are not covered by its
concurrency group. The Git ledger assumes the default branch is not rewound,
force-pushed, replaced or bypassed. Branch protection and action-token push
permissions must be verified by a maintainer. If writes are rejected, the job
fails before the next public send; do not weaken branch protection just to make
it work. A GitHub Actions `GITHUB_TOKEN` push generally does not trigger new
push workflows; the ledger commit also contains `[skip ci]` as a precaution.

## Audited legacy cutover (maintainer action; not performed by this change)

GitHub resolves a [`release` event's workflow definition from the tagged
commit](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflows),
not merely from the current default branch. The replacement lives at
`.github/workflows/telegram-release-delivery.yml`; the original
`.github/workflows/telegram-release-announcement.yml` is a retired stub and its
existing workflow ID `407947021` must stay `disabled_manually`, including for
historical refs. After a merge, verify that GitHub registers the replacement as
**a distinct workflow ID**; do not enable the old ID to activate it. A new
release tag must point to a trusted commit containing the reviewed replacement
workflow. Checking out default-branch scripts inside that workflow does not
protect an unsafe workflow definition selected from another ref. Control who
can create tags, releases and workflow refs, and audit historical definitions.
The 2026-11-06 read-only spot-check returned 404 for the old workflow path on
all 21 then-visible tags; it did not cover deleted tags, branches or other refs,
and is not proof that no historical unsafe definition can run.

Older release events are not backfilled. The read-only audit found one visible
`main` history entry for the tracked ledger, commit
`1924ddcd427a95327a1c02518272f65a09363cab`; the current blob
`e195b31c3371b777cb995f67e38e5b3936157e56` is schema 1 with zero records.
Visible historical workflow run `42004825171` skipped asset delivery and ledger
recording. None of this proves that channel posts or unrecorded legacy sends do
not exist. The local checkout may be shallow and is not a complete history audit.
Before enabling live publishing, maintainers must:

1. Keep the legacy workflow ID **disabled**, verify there are no queued/running
   legacy executions, and keep both new activation gates off. Review and merge
   the replacement only through the normal PR controls. Merging is not a release
   trigger; afterwards verify the original ID is still disabled and the new path
   has a distinct ID. Do not assume either workflow's state after a merge; the
   new path may be registered as active but its two default-off gates still apply.
   Obtain required approval and an eligible passing PR check; an administrator
   must inspect inaccessible settings and the empty visible required-check list.
   This PR is safe to review in a disabled state, not ready to activate.
2. Freeze release creation, publication, deletion and tag changes from inventory
   through activation; otherwise the snapshot can miss an old draft/tag. Under
   that freeze, inventory **all** accessible release IDs, including drafts, and
   all tags, plus any deleted/unrecorded releases or tags known from a human
   channel/history audit. No bot-only API provides a complete history proof.
   Ensure other bots, scripts and old workflows cannot publish with this identity,
   including dispatches of historical refs. Restrict bot secret access, dispatch
   only the reviewed default-branch version and verify no prior bot MTProto
   session is active, or stop. Never reuse old or previously announced tags; a
   new release ID or commit date does not prove a tag was never posted.
3. Resolve the **blocking** PR-rule/direct-push architecture conflict described
   above without weakening protection, and verify no-force/no-delete, all bypass
   actors, exclusive writers, workflow permissions and state-write capability
   under the resulting design. No concurrent legacy publisher may run during or
   after migration. If any condition cannot be established, stop with both gates
   off and the workflow disabled.
4. Under that freeze, create and review `/tmp/streambridge-history.json` with
   exactly `release_ids` (an array of positive integer IDs) and `tags` (an array
   of tag names). This read-only example collects visible releases and tags;
   the authenticated account must have access to drafts. Add independently
   discovered deleted/unrecorded IDs and tags and verify completeness before
   using it. GitHub cannot recover every deleted item automatically:

   ```sh
   export GITHUB_REPOSITORY=sascio/MyBridge  # replace if appropriate
   python3 - <<'PY'
   import json, os, subprocess
   repo = os.environ['GITHUB_REPOSITORY']
   def lines(resource, field):
       return subprocess.run(
           ['gh', 'api', '--paginate', '--jq', f'.[].{field}',
            f'repos/{repo}/{resource}?per_page=100'],
           check=True, capture_output=True, text=True).stdout.splitlines()
   ids = [int(value) for value in lines('releases', 'id')]
   release_tags = lines('releases', 'tag_name')  # includes draft tag names
   if any(value in ('', 'null') for value in release_tags):
       raise RuntimeError('A release has no tag; inventory requires manual review')
   inventory = {'release_ids': ids,
                'tags': sorted(set(lines('tags', 'name')) | set(release_tags))}
   with open('/tmp/streambridge-history.json', 'x', encoding='utf-8') as out:
       json.dump(inventory, out, indent=2)
       out.write('\n')
   PY
   ```

   Then prepare, but do not deploy, a schema-3 file from the complete current
   schema-1/legacy-schema-2 ledger and reviewed inventory. Choose a cutoff at
   least one hour in the future. This command does not contact Telegram or push:

   ```sh
   python3 scripts/telegram_ledger.py \
     --legacy .github/telegram-releases.json \
     --inventory /tmp/streambridge-history.json \
     --output /tmp/streambridge-ledger-for-review.json \
     --created-after 2027-01-01T00:00:00Z \
     --acknowledge-legacy-is-unknown --acknowledge-release-freeze
   ```

   Replace the example cutoff and use a new output path. The preparer includes
   IDs/tags already present in the legacy ledger, hashes both snapshots and
   rejects malformed/duplicate inventory data. Review the complete output and
   any sensitive data through the normal deployment process; never discard
   legacy entries. The acknowledgement is an operator attestation, **not** a
   technical proof that the freeze or inventory is complete.
5. Deploy the reviewed schema-3 ledger and policy-compatible publisher to the
   default branch **before** the cutoff, with the workflow disabled and both
   activation gates still off. The original ledger and complete inventory are
   preserved. An inventoried release ID/tag or a recorded legacy message ID
   blocks a new operation, including during later validation. Earlier schema-3
   preview files that omit the inventory fail closed and must be regenerated from
   the preserved legacy source. If deployment misses the cutoff, prepare and
   review a new future cutoff rather than enabling.
6. Only after all blockers are resolved and the replacement is verified on the
   default branch, choose a reviewed `not_before` UTC time far enough in the
   future for the remaining steps. While **both** workflow IDs are disabled and
   the tracked config is false, have an authorized maintainer deliberately set
   the variable to `true`. Submit a separate reviewed PR setting `enabled: true`
   and that future `not_before`, and update its default-off regression test.
   Verify both gates and the actual replacement code, then explicitly enable
   only the **distinct new** workflow ID. The legacy ID must remain disabled.
   Complete all these steps **before** `not_before`. If the time is missed,
   leave the new workflow disabled and review a new future fence before enabling.
   This ordering is required because the CLI cannot independently prove when a
   repository variable was changed. Do not dispatch a live manual initialization;
   it is forbidden by the code.
7. Wait until both the migration cutoff and `not_before` have passed, then end
   the freeze by deliberately creating and publishing a new, never-announced
   tag/release from a trusted commit containing the reviewed replacement
   workflow. GitHub's `created_at` (the commit date) and `published_at` must
   both be strictly after both fences, but this alone does not prove a draft or
   tag is new. The frozen inventory rejects known older draft IDs and tag names;
   an incomplete inventory or broken freeze invalidates the safety claim. That
   future `release: published` event is the only way to create an initial plan.
   A queued pre-fence publication cannot create a plan, and inventoried
   historical releases and tags are not replayed automatically.

This human attestation and tag-reuse rule are operational prerequisites, not
facts the bot can prove. Do not activate automatically based on a blank ledger;
if the old workflow definition is still on `main`, do not enable it.

## Recovery and monitoring

Fetch the authoritative remote ledger and inspect a local copy; this summary is
read-only, validates the full file and does not print tags, operation IDs,
identity fingerprints or secret values:

```sh
git fetch --no-tags origin main
git show FETCH_HEAD:.github/telegram-releases.json > /tmp/streambridge-ledger-snapshot.json
python3 scripts/telegram_ledger.py --inspect /tmp/streambridge-ledger-snapshot.json
```

Replace `main` with the repository's actual default branch if it changes. For
schema 1/2 the summary only reports the legacy-record count and unknown history.
For schema 3 it also reports `session_locked` and these operation categories:

- `confirmed_sent`: the protocol recorded a response matched to the original
  request and message ID; it does not prove the message still exists unchanged.
- `confirmed_unsent_by_protocol`: `planned` has no public attempt, and
  `uploading` has no final send attempt, though temporary parts may exist.
  This says nothing about a manual or legacy publisher outside the protocol.
- `unknown`: `sending` or `unknown` could have produced a public message.
  `legacy_channel_history` is always `unknown`, even for a zero-record ledger.

Inspect the workflow result and the validated ledger in a controlled environment
for the affected operation; never delete, reset or regenerate an ID. A
`confirmed` entry is skipped. A safe-to-retry `uploading` entry re-downloads and
verifies the same bytes before reuploading parts. The regular rerun blocks on
unknown sends; after review, an explicit manual `recover_unknown: true` plus
`dry_run: false` can replay the **same** ID, provided the session lock is clear,
branch rules are in place, and the frozen plan, credentials and assets still
match. Choose the reviewed default branch and release tag in the dispatch.
More than three attempts per run or ten per operation, an excessive flood wait,
an unrecognized error, or insufficient response evidence stops for review.
Confirmed pages/assets remain recorded; `RANDOM_ID_DUPLICATE` is not proof.

If `session` is non-null, first prove the original runner cannot resume **and**
that its bot authorization is revoked. Only then can an authorized maintainer
review and clear that exact stale lock through the protected ledger, preserving
all operation IDs, states and hashes; a subsequent recovery can use the same
IDs. A lost logout response or killed runner may make revocation impossible to
prove: bot support for listing/resetting all account sessions is limited. If
there is no trustworthy bot-only way to establish revocation, **leave the lock
in place and stop**; this implementation does not invent a user-session
workaround. Never clear a lock merely because a workflow looks old. A secret or
identity change likewise requires operator review; do not silently replace the
identity binding or IDs.

If remote state is missing, corrupt, rolled back or inaccessible, stop and
restore the exact audited authoritative Git history before recovery. If the
release or checksum changes, do not edit the frozen plan to force a send; review
whether a separate, explicitly authorized announcement is appropriate. Neither
preflight nor offline mocks validate these live recovery procedures.

## Research and trade-offs

* Telegram's [sendMessage](https://core.telegram.org/method/messages.sendMessage)
  and [sendMedia](https://core.telegram.org/method/messages.sendMedia) raw calls
  accept `random_id`; Telethon's references list both as usable by bots. Its
  high-level `send_file` chooses IDs internally, so retrying that call cannot
  preserve this ledger's IDs. Telegram's [file protocol](https://core.telegram.org/api/files)
  documents 512 KiB part limits, the big-file method above 10 MB and temporary
  part lifetimes. [sendMultiMedia](https://core.telegram.org/method/messages.sendMultiMedia)
  uses per-item IDs and separate media preparation, but group atomicity and
  partial recovery were not established; it is not used.
* [xz-dev/TelegramFileUploader](https://github.com/xz-dev/TelegramFileUploader)
  is MIT and was active on 2026-10-27. It uses a bot and Telethon multipart
  uploads but its grouped high-level `send_file` does not provide our durable
  per-operation ID/ledger guarantees.
* [mnicolas94/telegram-file-uploader](https://github.com/mnicolas94/telegram-file-uploader)
  is MIT, last pushed 2025-05-01, and uses bot-authenticated Pyrogram
  `send_document`; session handling and ambiguous reruns need separate care.
  [nikhilbadyal/ghaction-telegram-uploader](https://github.com/nikhilbadyal/ghaction-telegram-uploader)
  was active on 2026-11-01 and also uses Pyrogram, but GitHub reports GPL-3.0
  while its README says MIT; no code was copied from it. Pyrogram's upstream
  repository is archived. [ircfspace/githubMirror](https://github.com/ircfspace/githubMirror)
  was active on 2026-11-05 but reports no license; it uses high-level sends and
  its JSON state resets on read errors, so it is not a safe ledger replacement.
* [Telethon](https://github.com/LonamiWebs/Telethon) is MIT and already used by
  the project. Its GitHub repository was archived on 2026-02-21 because the
  project moved to [Codeberg](https://codeberg.org/Lonami/Telethon), not because
  an archive alone proves development stopped. [PyPI](https://pypi.org/project/Telethon/)
  listed 1.45.0 as the latest release on 2026-11-05; that version is pinned and
  exercised by the offline tests. Monitor its maintenance and security updates.
  An upgrade can change parsed entities or button types and thereby block a
  pending frozen plan; review and test upgrades without changing IDs or identity.

## Offline verification

Run `scripts/tests/run-tests.sh` after installing the pinned Telethon and PyYAML
versions listed in `.github/workflows/build.yml`. The test runner blocks external
DNS/TCP and permits only local Git remotes; loopback HTTP fixtures remain
available. Tests cover formatting, official release filters, preflight input
combinations and no-write behavior, strict ledger parsing, Git non-fast-forward
races, ambiguous push results, crash/session locks, duplicate events, partial
uploads, changed payloads, same-ID lost-response replay, flood waits, secret
redaction, individual raw requests and real Telethon 512 KiB big-file chunking
against fake RPCs. They include two simultaneous publisher workers using
separate local Git clones and a simulated accepted-send process killed with
`SIGKILL`; no remote network or Telegram send is used. Fake server semantics
and local Git tests do **not** prove live Telegram behavior, server compatibility
or GitHub branch permissions.

For a current-head GitHub run without publishing files, dispatch **Build Stream
Bridge** on the exact PR branch with `upload_artifacts: false`, or opt a
`pull_request` run into the same mode by including the literal HTML comment
`<!-- ci:no-upload -->` in the PR body. A push can opt in by including
`[ci no-upload]` in its commit message. These are verification-only controls,
not `[skip ci]`: all tests still run. Without the markers, normal push/PR runs
retain their prior artifact/issue behavior; a manual dispatch can explicitly opt
in to uploads. This mode runs the Android unit tests, debug and **debug-signed,
non-production** release assemblies, lint, distribution checks, tracking-config
checks and script tests, while skipping CI artifact uploads, Gradle cache use and
CI-failure issue creation/closure. Dependency graph submission and build-scan
publication are disabled. GitHub still records logs and summaries and downloads
dependencies; the Android job retains `issues: write` even when its issue steps
are skipped. Never put credentials in outputs.

GitHub does **not** evaluate `workflow_dispatch` job checks as required checks
for a PR, even when the exact head passes. Use a PR-triggered run for an eligible
PR check and have an administrator configure the desired required checks; an
empty rollup/list is not a CI gate. These verification runs are not production
release workflows or the Telegram workflow.
