# StreamBridge Telegram release announcements

The workflow renders an official GitHub Release for **@StreamBridge_App** as
StreamBridge messages with the **Your Media, Your Way** motto, **Made with ❤️ by
Saif** footer, `#SB<release id>` markers, and **⬇️ Download StreamBridge**
buttons linking to the actual release assets. Published APKs and the unsigned
IPA are sent as individual documents, after the text pages. Drafts and
pre-releases are not announced. The workflow does not pin, create/edit releases,
change tags or invoke Android/iOS builds.

> **Deployment remains blocked.** The original schema-1 ledger is not a
> complete history: public channel [message 5](https://t.me/StreamBridge_App/5)
> already announces `v0.1.15`. See [the historical audit](TELEGRAM-HISTORICAL-AUDIT.md).
> The dedicated `telegram-delivery-state` branch and its no-force/no-delete
> rules now exist, but the branch is still at the old `main` commit, contains
> application source and has no schema-4 ledger. Production state writes and
> recovery remain unverified. The tracked publishing and storage gates remain off.
>
> The old workflow ID `407947021` is manually disabled and must **never** be
> re-enabled; historical refs can contain unsafe workflow definitions. Verify
> the replacement gets a distinct ID after any future merge. Do not weaken the
> existing `main` protection, add a bypass, deploy a migration or enable
> publishing until all controls and the cutover have been independently reviewed.

## Credentials and permission boundaries

The existing bot is the **only Telegram identity**: the repository secrets
`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `TELEGRAM_API_ID`, and
`TELEGRAM_API_HASH` are scoped to the preflight or live delivery step. The bot
token is also scoped to historical preview/verification to protect inventory
identities without a Telegram call. The
bot needs channel administrator **Post Messages** rights; pin rights are not
needed. The API ID/hash are MTProto application credentials for that same bot,
not a personal-account session. This design never asks for a phone number,
one-time code, password or user session. A fresh in-memory `StringSession` is
used and neither its authorization key nor credentials are written to the
ledger, checkout, logs or artifacts.

`preflight` and rendering jobs have `contents: read`. The live job uses only
`contents: write` for the same-repository `GITHUB_TOKEN`; no OIDC broker or
additional service is required. Other eligible repository workflows and writers
can also write that branch, so the token is **not** exclusive to this workflow.
Only trusted writers may touch it. Delivery writes only
`telegram-delivery-state.json` on that ref. Git output is captured rather than
printed. Telethon and its transitive dependencies are pinned.

The fresh bot session needs a public channel username that resolves to the same
numeric channel returned by Bot API `getChat`. A private channel that can only
be identified by a numeric ID/access hash is not supported; it fails rather
than guessing a peer. Changing the bot token or channel fails the HMAC-based
identity binding and requires an audited migration. Do not paste secret values
into issues, PRs, logs or the ledger.

### Repository security audit and required controls

On 2026-11-06 the active `main-protection` ruleset `26674724` targeted the
default branch, requiring a PR and resolved conversations with zero approvals;
force pushes and deletion were prohibited and the bypass list was empty. Its
effective rules did not include required checks. Subsequently the owner created
`telegram-delivery-state` at commit `04ae2eb70c5cfc19ae7bbc4eef74d09a87e862d5`
and active ruleset `telegram-delivery-state-protection` (`26692231`) targeting
only that exact branch. GitHub's rules API reported deletion and non-fast-forward
rules, an empty bypass list, and no PR or update restriction. The ref still has
the `main` source tree and legacy schema-1 ledger, not an initialized state tip.
Classic branch-protection and Actions permission/secret/variable endpoints
returned 403 to the coding-agent token. Recheck these facts before activation;
an API result is not a complete audit of all repository writers or settings.

### Dedicated same-repository state branch

Review the **separate** active ruleset for `telegram-delivery-state`: block
force pushes and deletion, do not require pull requests or status checks for its
automated commits, and grant no bypass. Do not alter or bypass `main` protection.
No-force/no-delete rules are necessary to prevent a previously valid ledger
tip from being silently rolled back; a normal non-force push alone
does not prevent another writer from force-pushing an unprotected branch.

The runtime verifies the source repository ID, exact state checkout and remote,
state branch, separation from the default-branch PR ruleset, active state
no-force/no-delete rules and the token's lack of bypass. Missing, inaccessible,
inconsistent or incompatible rules stop delivery. GitHub may hide the bypass
actor list from the limited token, so the owner must inspect that list separately;
absence from the API response is not proof it is empty. These checks are necessary,
not proof of writer isolation: a different repository writer can make a
fast-forward change. Trust and monitor all repository writers and workflows with
`contents: write`. Only the intended workflow may use the bot for automated
publishing.

The reviewed storage gate remains false at `.github/telegram-state.json` until
all production checks and the historical cutover pass. Its eventual value is:

```json
{
  "enabled": true,
  "mode": "repository-branch",
  "repository_id": 1349123452,
  "shared_writers_acknowledged": true
}
```

The repository ID must match the GitHub workflow context and API. This setting
is an acknowledgement of shared writers, not a claim of isolation. The publisher
never initializes a missing ledger. Updates use exact-parent, non-force commits,
verify the remote result and stop on conflicts or ambiguous outcomes.

## Default-off activation gates

Both of these independently must be true before live delivery can run; the separate reviewed storage configuration must also be enabled:

- `.github/telegram-publishing.json` must be a valid, duplicate-free reviewed
  object with exactly `enabled: true` and a UTC `not_before` timestamp, e.g.
  `{"enabled":true,"not_before":"2027-01-01T00:00:00Z"}`. Live delivery stays
  off until that timestamp passes; a release event also requires its GitHub
  `published_at` to be later. GitHub documents
  [`created_at`](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)
  as the release commit date, **not** the draft-creation date. An otherwise
  new release may legitimately point to a commit older than the fence, so its
  commit date is recorded but not treated as a draft-creation date. Timestamps
  alone cannot prove a release or tag is new; the inventory and release freeze
  below are mandatory. The file is tracked as `{"enabled": false}` in this
  change; missing or malformed config fails closed. A future activation PR must
  deliberately update the regression test that currently asserts false.
- The repository Actions variable `TELEGRAM_PUBLISHING_ENABLED` must be exactly
  the lowercase string `true`. Missing or any other value is off. The audit's
  variable read returned 403, so its current value is **unknown**; the tracked
  false gate still prevents publishing in the new workflow.

The workflow checks both before the live job; the CLI checks both again. They
are independent of the release-age fence and schema-4 migration and do not
establish state-repository readiness. Dry rendering makes no Telegram login or
send; the no-send preflight can temporarily create a bot authorization. Merging
this code or changing a gate is not a release trigger and never initiates a
backfill. No old workflow may be re-enabled during this cutover.

## No-send preflight and manual dispatch

`workflow_dispatch` defaults to `preflight: false` and `dry_run: true`. An
explicit `preflight: true` takes precedence over all other inputs. Bot API
`getMe`, `getChat` and `getChatMember` check bot identity, channel access,
administrator status and Post Messages rights. While the publishing variable is
not `true`, an additional pinned Telethon step actually logs in with the bot,
checks that the resolved peer matches **@StreamBridge_App** and the Bot API
channel, and confirms logout of the in-memory session. No message, upload,
history scan, ledger write, release or tag change is made. After activation a
full preflight fails explicitly rather than creating an unfenced diagnostic
session or treating the Bot API-only check as complete. If logout is unconfirmed
or the runner is lost during login, investigate and
revoke the authorization before activation; do not treat the probe as passed.

The workflow is not registered until its file exists on the default branch. To
verify a reviewed PR branch beforehand, the existing **Build Stream Bridge**
workflow has an explicit `telegram_readiness: true` dispatch. It skips all build
and test jobs, requires the tracked publishing gate and variable to be off, and
runs only the no-send bot/MTProto probe. A successful probe does not demonstrate
send, upload, deduplication, Git state, recovery, or complete channel history.
A bot cannot use `messages.getHistory`; a known message ID can be checked with
bot-supported `channels.getMessages`, which is not a history search. No preflight
is run merely by merging.

With preflight off, **every release-selecting manual dispatch**, even a dry
render, requires a nonempty exact `tag`; `latest`, an empty value and ref
expressions are rejected. Dry rendering does not authenticate or send. A live
manual dispatch must use the default branch, supply the exact release ID and
tag, set `dry_run: false` and `resume_unsent: true`, and refer to an existing
frozen plan. It may resume only provably unsent `pending`, `uploading` or
pre-send `failed` operations: it cannot create a plan, resend `sending` or
`delivery_unknown`, or replay historical releases. Initial plans are permitted
only for an actual `release: published` event, bound to its release ID, exact
tag and tag commit, with non-draft and non-prerelease API data. Both activation
gates and the ledger age fence apply. `upload_assets: false` skips documents
for that run; a reviewed unsent-only recovery can later deliver them.

## Durable delivery protocol and schema

After initialization, the `telegram-delivery-state` tip must contain only
`telegram-delivery-state.json`. The old `.github/telegram-releases.json` is
preserved inside the schema-4 `legacy` object. Schema **4** validates duplicate
JSON keys, immutable repository/release identity, release ID, exact tag and resolved
source commit, frozen payload hashes, operation/random IDs, attempts, workflow
run/attempt and tooling commit. It records only confirmed message IDs and UTC
times, a session lock, historical exclusions/reasons/evidence, and state-only
verification records. No bot token, authorization key, raw payload or private
draft identity is recorded. Schema-1/2 legacy records are retained; schema-3
state is not silently migrated and requires a separately reviewed migration.

Public exclusion records include the source SHA where resolvable. Private IDs
and tags are domain-separated HMACs keyed by the existing bot token; changing
the key fails closed. The public `v0.1.15` exclusion preserves verified message
ID 5 and its evidence link, but no invented send time. The historical inventory
must itself be frozen and complete; an HMAC does not establish completeness.

1. A channel-wide Actions concurrency group uses `cancel-in-progress: false`
   and `queue: max`, shared by delivery and state-changing maintenance jobs. Up
   to 100 pending jobs/runs can wait; further arrivals may be canceled. Monitor
   the queue and handle canceled events through reviewed unsent-only recovery;
   do not assume an unlimited durable queue or event ordering. Preflights have
   independent groups.
2. The publisher validates remote state, protection, activation fences,
   historical exclusions, identity and the exact release/tag/commit. It freezes
   page and asset hashes and allocates distinct nonzero MTProto IDs. A
   non-expiring durable session lock serializes the bot authorization.
3. Updates use a new exact-parent commit and a **non-force** push, re-fetch and
   verify the graph and exact bytes. On a definite non-fast-forward conflict,
   the code re-reads, revalidates and reapplies the intended transition, with a
   bounded retry count. Ambiguous pushes and corrupt/missing state stop; existing
   valid records cannot be removed or rewritten.
4. Each operation is `pending` initially. Temporary document parts can be
   `uploading`; a known pre-send failure becomes `failed` with `unsent`
   evidence. Immediately before each public request, `sending`, attempt/run ID
   and original operation ID are durably committed. Text uses raw
   `messages.sendMessage`; documents use raw `messages.sendMedia` after the
   published bytes are downloaded and verified. APKs and IPA are individual
   operations, not grouped albums.
5. A valid matched Telegram response with a positive message ID is required for
   `sent`; the confirmation and time are then committed separately per item. A
   response marker containing the exact run/attempt/release/operation/ID,
   confirmation time and tooling SHA is logged **before** that final state
   commit to support reviewed recovery if it fails. It contains no credentials.
   Timeouts, lost or unmatchable responses become `delivery_unknown`; a crash
   may leave `sending`. Neither is automatically or manually resent, even with
   the same random ID. Telethon's high-level request retries **and automatic
   reconnect/requeue** are disabled. `RANDOM_ID_DUPLICATE` is not confirmation.
6. Logout must be confirmed before clearing the session lock. A lost logout or
   killed runner leaves the lock; a worker must not be assumed dead or revoked.
   A pre-send document failure may allow other independent documents to proceed,
   but the run still fails. A page failure, ambiguous send, policy/ledger error
   or changed frozen payload stops subsequent public sends.

Published checksum manifests/API digests and size are verified when available;
the first local SHA-256 only pins future attempts if no independent digest was
published. A changed plan blocks recovery. This is a fail-closed protocol, **not
an exactly-once guarantee**: Telegram behavior, the trustworthiness of every
repository writer, old workers, message edits/deletions and external publishers
remain outside a complete proof. The ordinary CI branch trigger does not target
the state ref; `[skip ci]` in state commits is only an extra precaution.

## Owner cutover runbook (not yet performed)

GitHub selects a release-event workflow definition from the tagged commit.
Keep old workflow ID `407947021` permanently disabled; future tags must contain
the reviewed replacement. It is not registered on the default branch until a
safe merge, and old releases are not backfilled. Public
[message 5](https://t.me/StreamBridge_App/5) already announces `v0.1.15`
(release ID `434756896`); the legacy schema-1 ledger is incomplete. See the
[historical audit](TELEGRAM-HISTORICAL-AUDIT.md).

**Do not proceed unless the complete history and freeze can be independently
verified.** These steps are for the repository owner, not an automatic deploy:

1. Keep `.github/telegram-publishing.json` and `.github/telegram-state.json`
   exactly `{"enabled": false}`, keep `TELEGRAM_PUBLISHING_ENABLED` absent or
   not `true`, and stop every other publisher. Continuously freeze release and
   tag creation, publication, mutation and deletion until cutover. Review the
   complete accessible GitHub inventory, old/deleted/reused refs, drafts,
   legacy records and channel evidence; preserve unknowns. Never alter the
   preserved draft or publish private draft metadata. A bot-only public view or
   API snapshot alone does not establish complete history.
2. At <https://github.com/sascio/MyBridge/settings/rules>, independently verify
   active branch ruleset `telegram-delivery-state-protection` targets only
   `telegram-delivery-state`, enables **Restrict deletions** and **Block force
   pushes**, and has no bypass. Do not require pull requests, checks,
   creation/update restrictions, or other rules that stop non-force Actions
   writes. Leave `main-protection` unchanged. Limited-token APIs may omit the
   bypass list; inspect it independently.
3. Choose a future UTC cutoff at least one hour away. A preliminary read-only
   preview can be taken without claiming a freeze; it is not an approved cutoff.
   Dispatch registered **Build Stream Bridge** on the reviewed PR branch with
   `upload_artifacts=false`, `telegram_cutover_mode=preview`, and
   `telegram_cutover_cutoff=<UTC cutoff>`. Other Telegram flags must be false.
   This reads GitHub only and prints the two branch tips, counts, and a digest
   of **protected**, not raw private, inventory; it does not modify a ref. Its
   job nevertheless needs `contents: write`: GitHub only lists draft releases
   to a token with push access. That token can write to the repository and is
   subject to the same shared-writer trust boundary. For an actual cutover,
   establish the continuous freeze first, repeat the preview, and review the
   privately held complete inventory against that snapshot. The built-in
   preparer includes current API releases/tags plus known v0.1.15 evidence;
   it cannot discover deleted/invisible identities or additional channel message
   IDs. If any independently known historical release, tag, or message ID is
   missing, **do not initialize this candidate**: arrange a separately reviewed
   protected migration that includes them while
   preserving all records and privacy. Do not pass private inventory as a
   workflow input or publish unkeyed hashes of it.
4. Only after that review and while the freeze continues, dispatch the same
   workflow/branch with `telegram_cutover_mode=initialize`, the **same** cutoff,
   `telegram_cutover_fingerprint=<exact protected preview fingerprint>`, and
   `telegram_cutover_ack=RELEASES_AND_TAGS_FROZEN_AND_REVIEWED`. It re-reads
   the inventory and both ref tips, verifies the legacy workflow is disabled,
   checks the state rules and validates the pre-existing legacy ledger. A
   pre-created state branch is accepted only if its tip still exactly equals the
   reviewed default-branch tip. It creates a **single-parent non-force child**
   whose tree contains only `telegram-delivery-state.json`; it verifies the
   parent and exact bytes. The inherited earlier Git history still contains the
   original source tree: removing that history would require a prohibited
   destructive rewrite. If the branch is absent, the initializer can instead
   create a one-file orphan root. It rechecks the snapshot afterward and never
   contacts Telegram. If a write is ambiguous or the inventory changes after a
   commit, leave gates off; never delete, force-push or reset the branch. A
   separately reviewed non-force migration is required for amendments. The
   initializer never overwrites a changed or already-initialized state branch.
5. Dispatch **Build Stream Bridge** on that same reviewed branch with
   `telegram_state_verification=true` and `upload_artifacts=false` (all other
   Telegram modes off). Inspect its result: it reads the remote, adds unique
   non-delivery markers, reads exact bytes, adds a second while preserving the
   first, races two updates, refetches/reapplies a definite conflict, checks the
   fast-forward graph, and archives its markers by a non-force commit. No send,
   upload, release/tag change or destructive protection test is performed.
   A failed run is not a pass; after review the optional
   `telegram_verification_cleanup_run=<exact failed run ID>` archives only that
   run's active markers, and a fresh full verification is still required.
   Independently inspect bypass actors, shared writers and both branch policies.
6. Obtain passing current-head no-upload CI, no-send authentication/peer/rights/
   logout, complete frozen-history review and live state-only verification.
   Merge only after all applicable gates pass; never weaken `main`. Confirm the
   replacement's new distinct workflow ID and old ID remains disabled. Then,
   under a separately reviewed activation change, set the storage config to the
   documented enabled object and publishing config to a future `not_before`,
   and set the Actions variable `TELEGRAM_PUBLISHING_ENABLED=true`. Complete
   cutover before that fence or choose a new one while disabled. Only a genuine
   future `release: published` event with publication later than both fences
   and an exact new tag containing the reviewed workflow may start a new plan;
   its commit may predate the fences. No per-release approval or policy update
   is required. No test posts or uploads are permitted.

State commits require GitHub's `contents: write` token; the no-write preview
also needs it for GitHub's draft visibility. Preview, cutover and reconciliation
additionally read Actions metadata. Initialization and state verification have
not been run against production state. Failure of rules/API visibility is not permission
to relax rules. The owner must use a trusted reviewed PR ref when dispatching;
GitHub cannot make an untrusted workflow ref safe merely by checking its inputs.

## Recovery and monitoring

Read and validate the remote state without altering it:

```sh
# STATE_CHECKOUT is a separately checked-out and verified same-repository state ref.
git -C "$STATE_CHECKOUT" fetch --no-tags origin telegram-delivery-state
git -C "$STATE_CHECKOUT" show FETCH_HEAD:telegram-delivery-state.json > /tmp/streambridge-ledger-snapshot.json
python3 scripts/telegram_ledger.py --inspect /tmp/streambridge-ledger-snapshot.json
```

The schema-4 summary counts `confirmed_sent`, `confirmed_unsent_by_protocol`
and `unknown`; it does not print tags, IDs or fingerprints. `pending`,
`uploading` and pre-send `failed` are unsent *by this protocol*; external/manual
publishers are not covered. `sending` and `delivery_unknown` may have produced a
message: **never resend them**, including through `resume_unsent`. Confirmed
entries are never resent. Preserve all operation IDs, hashes and history.

If the original runner died, first independently prove it cannot resume **and**
its bot authorization was revoked. Only an authorized maintainer may then clear
that exact stale session lock by a reviewed non-force state commit. Bot support
for listing/revoking all sessions is limited: if revocation cannot be proved,
leave the lock in place and stop. If state is inaccessible, corrupt, rolled back,
or release payloads/credentials changed, stop and investigate without relaxing
protection or modifying the frozen plan.

When a final ledger write failed **after a confirmed response**, a maintainer
may reconcile without sending only if the durable lock is already clear and
publishing is disabled. Independently review the completed original replacement
workflow job, its trusted tooling SHA and its single exact
`TELEGRAM_CONFIRMED_RESPONSE` line. Verify release/tag, job/run/attempt,
operation and random ID, positive Telegram message ID and the actual confirmation
time. Then dispatch **Build Stream Bridge** on trusted reviewed code with
`telegram_cutover_mode=reconcile`, `upload_artifacts=false`, and
`telegram_reconciliation` set to the exact non-secret JSON:

```json
{"release_id":123,"tag":"vX.Y.Z","operation":"page:1","message_id":456,"job_id":789,"acknowledge":"VERIFIED_TRUSTED_RUN_AND_RESPONSE"}
```

Replace those example IDs/tag with the independently verified exact values;
never use `latest` or an empty tag. The job checks GitHub's job/run/repository/
workflow metadata and matches exactly one confirmed-response log marker against
the stored attempt and tooling SHA before a non-force state transition to
`sent`. It makes no Telegram request. The acknowledgement is not a substitute
for reviewing the code and evidence. Missing, expired, ambiguous, mismatched or
untrusted logs are **not** proof; the operation stays blocked. There is no
unconditional way to prove an unknown delivery absent or safely resend it.

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
uploads, changed payloads, blocked lost-response replay, exact confirmed-log reconciliation, secret
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
