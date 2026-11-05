# StreamBridge Telegram release announcements

The workflow renders an official GitHub Release for **@StreamBridge_App** as
StreamBridge messages with the **Your Media, Your Way** motto, **Made with ❤️ by
Saif** footer, `#SB<release id>` markers, and **⬇️ Download StreamBridge**
buttons linking to the actual release assets. Published APKs and the unsigned
IPA are sent as individual documents, after the text pages. Drafts and
pre-releases are not announced. The workflow does not pin, create/edit releases,
change tags or invoke Android/iOS builds.

> **Deployment is deliberately blocked.** The tracked
> `.github/telegram-releases.json` is the original schema-1 ledger, not proof of
> an empty channel. This code refuses to publish until a maintainer has completed
> the audited cutover below. Releases created on or before that cutover are
> blocked, not backfilled. Do not merge/deploy on the assumption that an empty
> file means StreamBridge has never posted to the channel.

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

The read-only audit on **2026-11-05** found `main` reported as
`protected: false` and the effective branch-rules endpoint returned an empty
list. The detailed classic branch-protection, Actions-permission, runner and
secret-metadata endpoints returned **403**, so this audit could not determine
whether secrets exist, which actors can bypass protections, or whether a future
protected-branch ledger push will be permitted. The API reported the current
integration had admin/maintain/push access; that is not proof of exclusive
writes or secure settings. A search of the current tracked workflows found the
Telegram secret names only in the preflight and live steps of this workflow;
that cannot audit old refs, other repositories, manual tools or who can access
the secrets. No settings or secrets were changed.

**Live publishing is blocked by these findings.** An administrator must arrange
active GitHub rulesets for the default branch that prohibit non-fast-forward
updates (`non_fast_forward`) and deletion (`deletion`), audit all bypass actors,
and ensure unreviewed humans, workflows and external integrations cannot
replace or modify the ledger or run another publisher. An ordinary
fast-forward commit can still rewrite the ledger: no-force alone is insufficient.
Protect the workflow, scripts, bot secrets and configuration from unreviewed
changes too. Verify that the live Actions token can write the exact-parent ledger
commit *under the resulting policy*; required PR reviews or other protections
may block it. Do not remove or weaken a control just to permit automation.

The publisher checks the active ruleset types through GitHub's metadata API
before preparing a delivery, each public attempt, and each file-part upload; an
unavailable API or missing rule fails closed. It also restricts live CLI use to
the named workflow and the repository's default-branch ledger; live manual
dispatch must select that branch. These checks are necessary but **not sufficient**:
metadata visibility does not prove that bypasses, other fast-forward writers,
old workflow definitions, secret access or ruleset changes are controlled, and
the check cannot eliminate a change between verification and a request. An
administrator must audit and continuously enforce those controls. Restoring a
rule after a rollback does not prove the ledger was never tampered with.

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
published release and renders by default. Live delivery requires the separate,
explicit `dry_run: false` choice. `upload_assets: false` skips documents for
that run only; a later authorized run can deliver them without reposting text.
`recover_unknown: true` has no publishing effect unless `dry_run` is also
explicitly false. The `release: published` trigger operates only for
non-draft/non-prerelease releases and uses the GitHub API release object.

## Durable delivery protocol

1. One channel-wide Actions concurrency group covers releases and delivery
   dispatches, with cancellation disabled and a queue of at most 100 pending
   runs. Queue order is not an audit trail; excess runs can be canceled. Read-only
   preflights use separate per-run groups and cannot delay or replace a release.
2. The publisher checks the visible non-fast-forward/deletion rules and fetches
   the **remote default-branch** ledger; missing rules, missing/malformed state,
   unknown versions, inconsistencies, duplicate keys or an unactivated ledger
   stop the run. It checks release creation/publish times against the activation
   fence and freezes the complete rendered pages/buttons and asset
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
   part upload. Each document is independent: albums/grouped media are
   intentionally unsupported, because atomic group recovery was not established.

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

The release-event workflow must be on the repository's **default branch** to run
for future events. Older release events are not backfilled. The read-only audit
found one visible `main` history entry for the tracked ledger, commit
`1924ddcd427a95327a1c02518272f65a09363cab`; the current blob
`e195b31c3371b777cb995f67e38e5b3936157e56` is schema 1 with zero records.
Visible historical workflow run `42004825171` skipped asset delivery and ledger
recording. None of this proves that channel posts or unrecorded legacy sends do
not exist. The local checkout may be shallow and is not a complete history audit.
Before enabling live publishing, maintainers must:

1. Inventory past channel posts, release IDs **and tags**, including deleted or
   unrecorded releases, through an authorized human review. No bot-only API
   provides a complete history proof. Ensure other bots, scripts and old
   workflows cannot publish with this identity, including manual dispatches of
   historical refs containing older workflow definitions. Restrict access to
   the bot secrets as needed; dispatch only the reviewed default-branch version.
   Verify no prior bot MTProto session is active, or stop; do not add a personal
   account to work around it.
2. Apply and verify the active no-force/no-delete rules, bypass and writer
   controls described above, and that the Actions token can make exact-parent
   ledger commits under the resulting policy. There must be no concurrent legacy
   publisher during migration or between migration and the cutover. Establish an operational rule that old or
   previously announced tags will **never** be reused for new releases; a new
   GitHub release ID/creation timestamp alone cannot prove a tag was never
   announced. If that rule or the exclusive-writer assumptions cannot be
   established, leave publishing disabled.
3. On an operator-reviewed change, prepare a new file from the complete current
   schema-1/legacy-schema-2 ledger. Choose an explicit UTC cutoff at least one
   hour in the future, with time to deploy before it. This command is **local
   preparation only**; it neither contacts Telegram nor pushes anything:

   ```sh
   python3 scripts/telegram_ledger.py \
     --legacy .github/telegram-releases.json \
     --output /tmp/streambridge-ledger-for-review.json \
     --created-after 2027-01-01T00:00:00Z \
     --acknowledge-legacy-is-unknown
   ```

   Replace the example with the chosen future cutoff. The output must not
   already exist. Review the preserved legacy snapshot, its checksum and any
   sensitive data before proposing it as `.github/telegram-releases.json` in the
   normal reviewed deployment process. Do not reset or discard legacy entries.
4. Deploy the new workflow and reviewed schema-3 ledger to the default branch
   **before** the cutoff, with all old publishers disabled. Only releases both
   created and published strictly after the cutoff are eligible; an older draft
   published later is still blocked. Same-tag or same-ID legacy entries block.
   If deployment misses the cutoff, prepare a new future cutoff before enabling.

This human attestation and tag-reuse rule are operational prerequisites, not
facts the bot can prove. Do not activate automatically based on a blank ledger.

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

For a current-head GitHub run without publishing files, manually dispatch
**Build Stream Bridge** on the exact PR branch with `upload_artifacts: false`.
This runs the Android unit tests, debug/release assemblies, lint, distribution
checks, tracking-config checks and script tests, while skipping CI artifact
uploads and CI-failure issue creation/closure. Gradle action caching is disabled
for this mode; dependency graph submission and build-scan publication are also
disabled. Normal push/PR runs and manual dispatches explicitly opting in retain
their normal artifact/issue behavior. GitHub Actions still records job logs and
summaries and downloads dependencies; never place credentials in those outputs.
This verification run is not the release or Telegram workflow.
