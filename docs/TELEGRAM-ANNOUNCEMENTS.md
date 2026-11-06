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
> The selected state path is the dedicated `telegram-delivery-state` branch in
> this repository. That branch and its required no-force/no-delete rules are not
> yet provisioned; production state writes and recovery have not been verified.
> The tracked publishing and storage configurations remain off.
>
> The old workflow ID `407947021` is manually disabled and must **never** be
> re-enabled; historical refs can contain unsafe workflow definitions. Verify
> the replacement gets a distinct ID after any future merge. Do not weaken the
> existing `main` protection, add a bypass, deploy a migration or enable
> publishing until all controls and the cutover have been independently reviewed.

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

`preflight` and rendering jobs have `contents: read`. The live job uses only
`contents: write` for the same-repository `GITHUB_TOKEN`; no OIDC broker or
additional service is required. Other eligible repository workflows and writers
can also write that branch, so the token is **not** exclusive to this workflow.
Only trusted writers may touch it. Delivery writes only
`.github/telegram-releases.json` on that ref. Git output is captured rather than
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
effective rules did not include required checks. `telegram-delivery-state` did
not exist and had no effective rules. Classic branch-protection and Actions
permission/secret/variable endpoints returned 403 to the coding-agent token.
Recheck these facts before activation; an API result is not a complete audit of
all repository writers or settings.

### Dedicated same-repository state branch

Create and review a **separate** active ruleset for `telegram-delivery-state`:
block force pushes and deletion, do not require pull requests or status checks
for its automated commits, and grant no bypass. Do not alter or bypass `main`
protection. No-force/no-delete rules are necessary to prevent a previously
valid ledger tip from being silently rolled back; a normal non-force push alone
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
history scan, ledger write, release or tag change is made. After activation that
MTProto login step is skipped rather than creating an unfenced diagnostic session.
If logout is unconfirmed or the runner is lost during login, investigate and
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
2. The publisher checks the same-repository state protections and fetches
   the **remote state-branch** ledger; missing rules, missing/malformed state,
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
concurrency group. The Git ledger assumes its separate state branch is not rewound, deleted,
replaced or modified by an untrusted writer. State access and no-bypass branch
rules must be independently verified by a maintainer. If writes are rejected,
the job fails before the next public send; do not weaken branch protection.
The state ref is not a build trigger in the checked workflows; `[skip ci]` is only a precaution.

## Audited legacy cutover (not yet performed)

GitHub selects a release-event workflow definition from the tagged commit, not
just from the default branch. The replacement is
`.github/workflows/telegram-release-delivery.yml`; the original ID `407947021`
must stay disabled even for historical refs. Verify the replacement registers
with a **distinct** ID after merge. Future tags must contain the reviewed
replacement workflow and point to trusted commits. Older events are not backfilled.

The tracked schema-1 ledger is empty, but public [message 5](https://t.me/StreamBridge_App/5)
already announces `v0.1.15` (release ID `434756896`). The legacy workflow's
visible run skipped ledger recording. Missing/deleted channel history remains
unknown; see [the historical audit](TELEGRAM-HISTORICAL-AUDIT.md).

Before activation:

1. Keep both gates false and the old workflow disabled; confirm no legacy run or
   other publisher is active. Freeze release creation/publication/deletion and
   tag changes continuously through cutover. Review all accessible release IDs
   and tags, drafts, deleted/reused refs and independently known channel message
   IDs. GitHub and a bot-only Telegram session cannot prove a complete history.
2. The source repository is public. Do **not** put private draft names, draft
   IDs, credentials, tokens, Telegram sessions or the raw private audit snapshot
   on the state branch. Independently classify which inventory IDs and tags are
   already public. Blind the remaining values with the existing bot token using
   the preparer below. The token is read from the environment and never written;
   protected values are domain-separated HMAC-SHA-256 digests. A key check makes
   a changed token fail closed. Even hashes reveal the number of protected items;
   preserve the full private inventory securely outside Git for future audit.
   Review any other legacy fields for confidentiality before deployment. If a
   legacy schema-1/2 record itself contains non-public identifiers, the
   protected migration stops rather than discarding or publishing that record.

   `INVENTORY` is a private JSON object with `release_ids`, `tags` and optionally
   `message_ids`; `PUBLIC_INVENTORY` is a reviewed JSON object with only IDs and
   tags known to be public. A release-API response with drafts is **not** a
   public classification. Set `TELEGRAM_BOT_TOKEN` securely in the operator's
   environment, never on a command line or in logs, then prepare new files:

   ```sh
   python3 scripts/telegram_ledger.py --protect-inventory \
     --inventory "$INVENTORY" --public-inventory "$PUBLIC_INVENTORY" \
     --output "$PROTECTED_INVENTORY"
   python3 scripts/telegram_ledger.py --legacy .github/telegram-releases.json \
     --inventory "$PROTECTED_INVENTORY" --output "$LEDGER_FOR_REVIEW" \
     --created-after "$FUTURE_UTC_CUTOFF" \
     --acknowledge-legacy-is-unknown --acknowledge-release-freeze
   ```

   Choose a cutoff at least one hour ahead. The preparers never send or deploy;
   inspect the complete output and protect private inputs. The migration retains
   schema-1/2 legacy content and the historical checksum. A human acknowledgement
   is not proof of inventory completeness or a freeze.
3. Provision the state branch and its no-force/no-delete, no-bypass ruleset.
   Verify the GitHub Actions token can read rules and make exact-parent state-only
   updates, reject concurrent stale writes and recover correctly without sending
   to Telegram. Preserve the reviewed schema-3 snapshot and recheck the frozen
   inventory before the cutoff. Failure, a missing ref, or any ambiguity means
   leave publishing disabled. No artifacts or caches are authoritative state.
4. Obtain a current-head passing PR CI run and review/resolve all conversations.
   Merge using the required PR controls only when all applicable production
   safety checks pass; do not weaken `main`. Verify the replacement workflow's
   distinct ID and the old ID remains disabled. Verify actual bot authentication,
   MTProto channel binding, posting rights and confirmed logout without a test
   message or upload. A preflight alone does not verify sending or live recovery.
5. While the freeze continues and all checks pass, deliberately enable the
   reviewed storage config and set the publishing config `enabled: true` with
   a future `not_before` fence, plus the Actions variable
   `TELEGRAM_PUBLISHING_ENABLED=true`. Complete the reviewed cutover before the
   fence; if missed, choose and review a new future fence while disabled. Once
   the fence passes, only a genuinely new eligible published-release event may
   create a plan. Never replay old releases or reuse old tags. Future releases
   need no per-release approval or policy update.

## Recovery and monitoring

Fetch the authoritative remote ledger and inspect a local copy; this summary is
read-only, validates the full file and does not print tags, operation IDs,
identity fingerprints or secret values:

```sh
# Run from the source checkout, with a separately checked-out and independently
# verified same-repository state branch at STATE_CHECKOUT.
git -C "$STATE_CHECKOUT" fetch --no-tags origin telegram-delivery-state
git -C "$STATE_CHECKOUT" show FETCH_HEAD:.github/telegram-releases.json > /tmp/streambridge-ledger-snapshot.json
python3 scripts/telegram_ledger.py --inspect /tmp/streambridge-ledger-snapshot.json
```

For schema 1/2 the summary only reports the legacy-record count and unknown history.
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
