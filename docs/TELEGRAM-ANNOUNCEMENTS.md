# StreamBridge Telegram release announcements

The workflow announces official published GitHub releases to **@StreamBridge_App**
using StreamBridge, **Your Media, Your Way**, **Made with ❤️ by Saif**,
`#SB<release id>` markers, and **⬇️ Download StreamBridge** buttons. Release
pages come first, followed by selected APKs and the unsigned IPA as individual
documents. Drafts and pre-releases are not announced. No release, tag, asset,
app version, pin or build is created or changed by Telegram delivery.

## Activation and automatic bootstrap

The replacement `.github/workflows/telegram-release-delivery.yml` must exist on
the **default branch**. Leave legacy workflow ID `407947021` manually disabled:
historical refs may still contain its unsafe definition. The live publisher
checks the legacy workflow state, repository identity, `main` protection and
state-branch protection before state changes and Telegram requests.

The repository Actions variable `TELEGRAM_PUBLISHING_ENABLED` must be exactly
`true` for automatic bootstrap or delivery. Any other value is off. An explicit
no-send `initialize_state` dispatch can prepare the ledger while this switch
remains off; it cannot poll or deliver releases. The tracked
`.github/telegram-publishing.json` opts into **ledger-controlled** activation;
it does not enable delivery by itself. `.github/telegram-state.json` explicitly
selects the reviewed same-repository branch and acknowledges shared writers.
Missing, duplicate or malformed configuration fails closed. A valid, protected
schema-4 ledger, completed state verification, bound bot identity, no active
session/verification, and the automatic publication fence are also required. There
is no historical-cutoff approval, owner-created ledger file or special build
workflow needed to initialize.

Once the replacement is merged and the switch is enabled, the first eligible
main-branch push or scheduled run automatically:

1. Checks the pre-existing `telegram-delivery-state` ref is exactly the pinned
   pre-merge base `04ae2eb70c5cfc19ae7bbc4eef74d09a87e862d5` and an ancestor of
   the current protected default branch. An unexpected or advanced ref is never
   reset, overwritten, force-pushed or deleted.
2. Snapshots currently accessible releases, tags and recent release/tag activity
   as **exclusions**, including the already-announced `v0.1.15` message 5.
   Draft identities and non-public identifiers are HMAC-protected; neither
   credentials nor private draft metadata are written. Repeated snapshots must
   agree. The legacy `.github/telegram-releases.json` is preserved, and the
   state tip becomes a non-force child containing only
   `telegram-delivery-state.json`.
3. Checks real state-only reads, exact-parent writes, a concurrent update and
   conflict recovery, interruption markers, and the ambiguity guard. The
   verification records are archived without deleting any delivery history.
4. Checks Bot API identity/channel/Post Messages rights and logs the bot into
   MTProto **under a durable session lock**, without a message, upload or history
   read. The lock is cleared only after logout is confirmed. An interrupted or
   ambiguous logout leaves the lock in place and stops automatic retries.
5. Records a publication fence **before** the final two exclusion scans, which
   must agree and complete within 15 minutes. It only adds observed history;
   releases and tags observed during the scans are excluded even if newly
   created. Once all checks succeed, one protected ledger update records the
   fence and `ready`. No release published on or before the fence can be
   announced. Initialization itself never sends; eligible later publications
   can be delivered by their release event, scheduled fallback or explicit
   `poll_now` dispatch. Historical
   releases are **not backfilled**.

To finish production preparation before enabling live delivery:

```sh
gh workflow run telegram-release-delivery.yml --ref main -f preflight=true
gh workflow run telegram-release-delivery.yml --ref main -f initialize_state=true
```

Wait for each run to succeed and inspect the protected ledger's ready/idle state.
The second dispatch uses the same protected bootstrap and channel-wide queue,
requires the live switch to be off, and **never calls the polling or delivery
path**, regardless of release/recovery inputs. Its summary records the exact
state commit and future-only fence. Only after readiness succeeds, set the
repository Actions variable `TELEGRAM_PUBLISHING_ENABLED` to exactly `true`.
Reading or changing this variable requires the GitHub connection's Variables
permission; Actions dispatch permission alone is not enough. An HTTP 403 is a
connection-permission blocker, not a reason to remove the live gate or relax
branch protection. Reconnect GitHub in Arena with the required permission or
have the repository owner set the variable in Settings → Secrets and variables
→ Actions → Variables. Keep releases unpublished during initialization.

After readiness is verified and the owner has enabled the switch, an authorized
operator may run the exact same future-release poll immediately with
`gh workflow run telegram-release-delivery.yml --ref main -f poll_now=true`.
This explicit live dispatch is serialized with scheduled polling and only uses
the protected automatic poll path; it cannot target an arbitrary tag or bypass
the publication fence, release inventory, unresolved-operation isolation,
session lock or idempotency ledger. It is not a dry run. Initialization and
read-only preflight remain separate no-send modes.

Capturing the fence before the final scans avoids an artificial gap where a tag
could be created before a future cutoff but after the snapshot. Releases and
tags created during bootstrap can be conservatively excluded, so do not publish
while bootstrap is running. GitHub's scheduling is best effort: a five-minute
cron does not promise five-minute latency, and GitHub may pause scheduled
workflows after prolonged repository inactivity. Monitor failed or disabled
runs. A failure or incomplete verification leaves delivery off or locked,
rather than guessing that a write, login, logout, message or upload succeeded.

The GitHub Events API is bounded, can be delayed and does **not** provide a
complete audit of deleted refs. In particular, tag-creation activity is not
reliably present for this repository. A tag absent from the accessible snapshot
is not mathematical proof that its name was never used; a scan and activation
are not atomic. Trusted release writers must never reuse a deleted or historical
tag name, and must not mutate releases or tags during bootstrap. If provenance
is uncertain, leave delivery disabled and investigate rather than replaying.
The automatic exclusions protect observed history, but cannot reconstruct a
deleted, never-observed tag or invisible Telegram channel history.

## Published-release detection

The `release: published` event is the normal path when GitHub emits a workflow
run. Events caused by the repository `GITHUB_TOKEN` do not normally trigger a
second workflow. The scheduled fallback instead lists **actual GitHub releases**,
selects currently published non-draft, non-prerelease releases after the ledger
fence, and fetches each exact ID again. It never substitutes `latest` and does
not rely on tag-creation events. It checks the same historical exclusions,
exact tag/commit identity, bot/channel binding, payload hashes and durable
write-ahead state as the event path. Both use the same channel-wide concurrency
queue and state ledger, so a duplicate event or poll cannot resend a confirmed
operation. GitHub's release `created_at` is the release commit date, not the
creation date of the draft; it is recorded but not used as a historical cutoff.
A manually targeted live delivery can only resume an existing, reviewed unsent
plan by exact release ID and tag; it cannot create a historical or arbitrary new
plan. The separate `poll_now` mode runs the exact protected automatic selection
logic and can create plans only for actual published releases after the fence.

`release-draft.yml` only creates drafts; a maintainer or other authorized
publisher must actually publish. The fallback does not mutate it. Future tags
must contain trusted workflow definitions: GitHub can select release workflow
definitions from the tag ref. Keep the old workflow disabled even after the
replacement has a distinct registered workflow ID.

## Credentials and trust boundary

Use the existing secrets `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`,
`TELEGRAM_API_ID`, and `TELEGRAM_API_HASH`. The bot needs channel administrator
**Post Messages** rights, not pin rights. API ID/hash authenticate the same
bot with MTProto; no personal account, phone, one-time code or persistent
session file is used. Telethon and its dependencies are pinned. A changed bot
token or channel identity fails the protected-inventory and identity checks.

No-send preflight jobs use only read-only Bot API calls and syntax-check the
MTProto credentials; they do not open a bot session. The branch readiness check
also reads GitHub state protections, the pinned ref and legacy workflow status;
it cannot prove write permission without a write. Actual state writes and bot
login/logout are verified only by the protected automatic bootstrap. Neither
preflight performs a public message, upload, channel-history read, release
mutation or test release.

Live jobs use same-repository `GITHUB_TOKEN` `contents: write` for state and
`actions: read` to verify the legacy workflow and interrupted-run metadata.
This token is not exclusive to the Telegram workflow: trusted repository writers
and other eligible workflows can also write. Keep the dedicated state ref
protected against force pushes and deletion without a bypass, pull-request
requirement or blocking status check; keep `main` PR protection intact. Rules
are checked at runtime, but limited API credentials may not reveal every bypass
actor. No policy check proves isolation from a malicious trusted writer.

## Durable delivery protocol

The ledger freezes the exact release id, tag, tag commit, publication timestamp,
rendered pages, asset names and checksums before delivery. A per-operation
MTProto `random_id` is persisted before sending; messages and assets are
individual operations. The publisher writes `sending` before each public call,
then records the confirmed Telegram message ID. A lost or ambiguous response is
`delivery_unknown`, not a reason to retry. An unresolved operation blocks any
further operation for that same release until independently reconciled; it does
not starve a separately identified release. The channel-wide concurrency queue,
durable session lock, active-verification gate and protected inventory checks
remain global. New release plans retain unique random IDs across the complete
ledger, and the scheduled poll skips (without retrying) a release whose plan is
unresolved or needs manual recovery while it continues with independent
eligible releases. A confirmed result cannot be changed or sent again. The same
protocol handles large files through pinned bot-only MTProto multipart upload.

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
the repository variable `TELEGRAM_PUBLISHING_ENABLED` is explicitly `false`.
Missing or malformed values do not satisfy this fail-closed gate. Independently review the completed original replacement
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
never use `latest` or an empty tag. The default evidence source checks GitHub's
job/run/repository/workflow metadata and matches exactly one confirmed-response
log marker against the stored attempt and tooling SHA before a non-force state
transition to `sent`. If trusted runner logs are unavailable, the owner may
explicitly request the read-only known-ID path by adding
`"evidence":"telegram_mtproto_known_id"` to the JSON. That path accepts only a
frozen text-page operation: it re-renders the immutable published release,
requires an exact match to the ledger payload hash, verifies the bot/channel
identity against the protected ledger, and asks MTProto for only the supplied
message ID. It compares the returned channel, text, formatting entities and
inline buttons with the frozen payload; it never scans history or sends. The
workflow holds the durable session lock during bot authentication and releases
it only after Telegram logout is confirmed. A failed lookup, mismatch, or
unconfirmed logout leaves the operation unresolved (and may retain the lock).
Both paths require the variable to be exactly `false` and an independently
reviewed request; the acknowledgement is not a substitute for reviewing the
code and evidence.
Missing, expired, ambiguous, mismatched or untrusted evidence is **not** proof;
the operation stays blocked. There is no unconditional way to prove an unknown
delivery absent or safely resend it.

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
