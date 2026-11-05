# Telegram release announcements

`.github/workflows/telegram-release-announcement.yml` publishes a StreamBridge
release announcement to the Telegram channel **[@StreamBridge_App](https://t.me/StreamBridge_App)**
whenever a GitHub Release is published, then delivers the production APKs and the
unsigned IPA to the same channel as documents. It never creates, edits, publishes
or deletes a release, and it does not touch the Android or iOS build jobs.

## What triggers it

| Event | Behaviour |
|---|---|
| `release`, type `published` | The announcement is rendered from that release and posted. |
| Draft creation, tag push, branch push | Never triggers the workflow. |
| `release` with `prerelease: true` | The job is skipped; nothing is posted. |
| Manual dispatch, `preflight` checked | Read-only Telegram readiness check; ignores `dry_run`, `tag`, and `upload_assets`. |
| Manual dispatch, default (`preflight` unchecked) | Dry run: the exact messages are rendered and validated in the log, nothing is sent. |
| Manual dispatch, `preflight` and `dry_run` unchecked | Sends for the resolved release (`tag` input, or the latest published release). |

Every future release is covered automatically. No version number is hardcoded:
the release name, tag, version code, notes, assets, sizes and URLs all come from
the GitHub API object of the release that fired the event.

## Read-only Telegram preflight

**Review blocker:** the existing MTProto history check calls
[`messages.getHistory`](https://core.telegram.org/method/messages.getHistory),
which Telegram allows only for user accounts, not bots. The uploader authenticates
as a bot, so a real preflight cannot currently verify history and should fail at
that check even with valid credentials. Successful offline mocks are not evidence
that Telegram permits the call. This restriction does not by itself establish
whether MTProto file uploads work. A bot-compatible duplicate check must be
implemented and validated before this preflight is ready for use.

Once that blocker is resolved and the workflow is merged into the default branch,
open **Actions → Telegram release announcement → Run workflow** and select
**Preflight**. The input defaults to false and takes precedence over `dry_run`,
`tag` and `upload_assets`. It uses an independent job, so the announcement,
upload and ledger steps are skipped even if the other inputs request a real
announcement. The equivalent GitHub CLI command is:

```bash
gh workflow run telegram-release-announcement.yml --ref main -f preflight=true
```

All four repository secrets must be configured: `TELEGRAM_BOT_TOKEN`,
`TELEGRAM_CHAT_ID`, `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`. A missing value,
a bot authentication or channel access failure, missing administrator or **Post
Messages** rights, or an MTProto history failure makes the job fail and names the
problem in the log. The final result also appears in the job summary.

The job checks out the default branch with a read-only GitHub token and without
persisting checkout credentials. It creates a synthetic release object in runner
temporary storage solely because the existing CLI requires release metadata,
and then runs this command (the fixture is not an actual release):

```bash
python3 scripts/telegram_announce.py --release-json "${RUNNER_TEMP}/telegram-preflight-release.json" --preflight
```

The workflow additionally uses the existing Bot API client for `getMe` and
`getChatMember`: `getChat` alone does not report the bot's administrator status
or Post Messages right. The existing CLI may therefore print a membership warning
based on `getChat`; the preceding `getChatMember` check and the **final job
result** are authoritative. The CLI attempts the existing bot login and history
lookup with the fixture marker `#SB0`; Telegram's bot restriction above prevents
that lookup from succeeding. The existing CLI gives `--dry-run` priority over
`--preflight`, so the workflow deliberately passes only `--preflight`, regardless
of the dispatch's dry-run input.

No real release is fetched or changed; no tag or post ledger is modified; no
message is sent, file uploaded, or message pinned or unpinned. The normal release
job retains its existing behavior and ledger write permission. Note that the
current announcement step receives only the bot token and chat ID. Giving it the
MTProto API credentials alone would not enable a working history guard: the
underlying method is unavailable to bots. The independent ledger remains in use.

## How the message is built

`scripts/telegram_release_notes.py` converts the release notes into Telegram HTML
(`parse_mode: HTML`). Nothing is invented, and nothing is silently truncated.

1. **Header.** `🚀 STREAMBRIDGE <version> IS HERE!`, the motto *Your Media, Your
   Way*, the publication date and (for the current release) `🏷 Latest release`.
2. **Sections**, in a fixed order, each rendered only when the release notes
   support it: What's New, Appearance & Glass UI, Player & Playback, Subtitles &
   Audio, Continue Watching, Live TV, iOS Improvements, Translations, Core
   Updates, StreamBridge Improvements, Downloads, Installation Notes.
   Headings are routed by keyword (for example `## iOS installation` goes to
   Installation Notes, not iOS Improvements; `## Android` and `## APK` headings
   are installation notes, not features).
3. **Downloads**, generated from the release's own assets: every APK with its ABI
   and real size, the IPA, and the checksum file. If an asset is missing — or its
   upload has not finished (`state` not `uploaded`) — the section says so instead
   of linking something that does not exist.
4. **Footer.** `Made with ❤️ by Saif` and the release tag `#StreamBridge #SB<release id>`.

A hand-written "Download sizes" table is dropped from the notes (its numbers are
replaced by the real asset sizes), release-title headings and the
`StreamBridge version X (code Y)` metadata line are skipped, and Markdown is
converted rather than dumped: `**bold**`, `*italic*`, `` `code` ``, `[label](url)`
and hard-wrapped bullets all render correctly. All HTML is escaped, so any HTML
in the notes becomes inert text.

**Branding.** Upstream product names are scrubbed from the notes text
(`Nuvio`/`NuvioMedia`/`NuvioMobile`/`Nuvio-Enhanced` → StreamBridge wording,
`Upstream` → `Core`) and repository-internal paths (`docs/…`, `scripts/…`,
`composeApp/…`) are replaced with readable phrases or labelled links. The
workflow summary reports how many tokens were scrubbed instead of hiding it.

## Download buttons

An inline keyboard is attached to the first page and repeated (release page only)
on every later page:

| Button | URL |
|---|---|
| `⬇️ Download StreamBridge` | The release's `html_url` — the exact published release page |
| `🤖 Android ARM64` | The `browser_download_url` of `…arm64-v8a…apk` |
| `🤖 Android ARMv7` | The `browser_download_url` of `…armeabi-v7a…apk` |
| `🤖 Android x86` | The `browser_download_url` of `…x86-release.apk` |
| `🤖 Android x86_64` | The `browser_download_url` of `…x86_64…apk` |
| `🍎 iOS IPA` | The `browser_download_url` of the published IPA |
| `🔐 Checksums` | The `browser_download_url` of `checksums.sha256` |

Asset buttons appear only for assets that exist and are `uploaded`. No URL is
ever assembled from a pattern, and the HTML validator rejects any link that is
not `https://`.

## Delivering the APK and IPA files

The production binaries are larger than the Bot API's 50 MB ceiling (four APKs at
52-56 MiB, the unsigned IPA at ~70 MiB), so `sendDocument` against
`api.telegram.org` cannot deliver them. They are uploaded over **MTProto** by the
same bot that posts the announcement.

| Route | Per-file ceiling | What it needs |
|---|---|---|
| Public Bot API (`sendDocument`) | 50 MB — too small for every StreamBridge binary | bot token |
| **MTProto, bot identity (used here)** | **2000 MB** | bot token + `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` |
| Self-hosted local Bot API server | 2000 MB | a second long-running service |

MTProto was chosen because it needs no extra service, no user account, no phone
number and no login code: Telethon authenticates with `auth.importBotAuthorization`
using the existing bot token, and the session is an in-memory `StringSession` that
is never written to disk.

### What the workflow does per asset

1. **Plan** — only assets the release reports as `state: uploaded` are queued. The
   four Android ABIs are matched by their published names, and the IPA is
   detected by extension and filename pattern, never by a hardcoded version.
2. **Download** — streamed to `$RUNNER_TEMP/telegram-assets` (never the checkout),
   written to a `.part` file, and renamed only after it verifies.
3. **Verify** — the byte count must equal the size GitHub reports, and if the
   release publishes `checksums.sha256` (or the asset carries a `digest`), the
   SHA-256 must match too. A short or corrupt download is discarded and retried;
   it is never uploaded.
4. **Upload** — `upload_file` in 512 KiB parts, then `send_file` as a document with
   the original filename and an HTML caption carrying the version, the ABI or
   platform, the size and the verified GitHub link.
5. **Record** — the confirmed message id is written to the ledger for that asset.

A failure on one asset never stops the others. Every failure is reported with the
asset's verified GitHub link and `continue-on-error` keeps the job green. If the
ledger was successfully pushed, a re-run skips recorded files and retries the
others. A cancellation or failed ledger push can lose successful upload records,
so a re-run can upload the same files again; inspect the channel before retrying.

### Credentials for large-file delivery

`TELEGRAM_API_ID` and `TELEGRAM_API_HASH` are required, from
[my.telegram.org](https://my.telegram.org) → *API development tools*. They
identify the application, not an account; no phone number or login code is ever
requested, and no user session is created. Without them the announcement is still
posted, the files are reported as not delivered, and the run stays green.

## Long release notes

Telegram accepts 4096 characters per message, counted in UTF-16 code units
*after* tags are parsed. Pages target 3800 visible characters, are packed from
complete, tag-balanced lines, and break at word boundaries. Every page is
re-validated on its own (balanced tags, allowed tags only, escaped `&`, https
links) and the run fails if a page could not be sent. Ordering is preserved with
`(part N/M)` headers, and content is never dropped: a section that does not fit
continues on the next page under a repeated heading. More than ten pages is
refused rather than truncated, with an instruction to shorten the notes.

## Duplicate protection

The independent post ledger (`.github/telegram-releases.json`) is keyed on the
**release id**:

* Every page carries `#SB<release id>`. Before sending, the script checks the
  pinned message and the persistent ledger for that release id.
* The workflow does **not** pass `--pin`; it does not request Pin Messages rights
  or create a pin. A matching pin can help if one already exists, but is not a
  dependable protection for this workflow.
* The ledger records an announcement only after **every** page is confirmed. Each
  confirmed binary is added to the same ledger individually. The workflow pushes
  the ledger after attempting asset delivery; push failures only warn. A retry
  skips a recorded release or asset only if that record was actually persisted.
* The optional MTProto history lookup currently uses a user-only method and cannot
  protect bot retries. The normal publish step therefore does not receive the API
  id/hash just to invoke this unsupported lookup.

**Retry risk:** a partially sent announcement, runner interruption, or failed
ledger push can leave channel posts without a durable record. Retrying can then
post duplicate pages or re-upload files. A response lost after Telegram accepted a
send can also trigger a duplicate during the same run's automatic retries. If a
future history guard finds one page of a multi-page announcement, it must not be
assumed to have completed or resumed the remaining pages. Inspect the channel and
workflow logs before retrying an interrupted or partially failed run. Concurrency
reduces races only within the same group; it is not an exactly-once guarantee.

## Failure handling

* HTTP timeouts on every request; retries with backoff for transport failures,
  HTTP 5xx and Telegram 429 (honouring `retry_after`). `400/401/403/404` are
  treated as permanent and are not retried.
* Telegram's JSON envelope is validated, not just the HTTP status; a `200` with
  `ok: false`, or a missing `message_id`, is a failure.
* Missing secrets fail the step with a message naming the secret and pointing at
  *Settings → Secrets and variables → Actions*, plus a note that the GitHub
  release is unaffected.
* A pin failure only warns: the announcement is already delivered.
* Credentials are read from step-scoped environment variables and masked in
  GitHub Actions logs. Diagnostics and upload-error summaries also redact the
  configured secret values and token-shaped strings. New ledger records do not
  store the chat ID; older ledger entries are preserved. No credential is passed
  as an argument or written to an artifact. The workflow uploads no artifacts.

## Setup

1. `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` are repository secrets.
   `TELEGRAM_CHAT_ID` is `@StreamBridge_App`.
2. `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` are repository secrets, from
   [my.telegram.org](https://my.telegram.org) → *API development tools*. The
   upload and preflight steps need them; without them announcements can still post.
3. Resolve the preflight history blocker above before merging. GitHub only creates
   a `release: published` trigger for workflows on the default branch, and the UI
   offers `workflow_dispatch` workflows from that branch.
4. The bot must be an **administrator** of the channel with *Post Messages*.
   Pinning is off in the workflow; merely granting *Pin Messages* does not enable it.
5. Once merged, future releases are announced automatically. Releases published
   earlier are not backfilled; a real manual dispatch is a publication operation.

## Testing

```bash
scripts/tests/telegram-announcement-test.py     # offline; no credentials needed
scripts/tests/run-tests.sh                      # the repository's whole suite
```

The tests replace Telegram with scripted doubles, so no test can reach the
channel. They cover event filtering (draft, prerelease, duplicate), note parsing,
HTML escaping, section routing, long-note splitting, asset detection and missing
assets, button URLs, missing secrets, token redaction, 429/5xx/400/network
failures, partial delivery, unconfirmed delivery, the pinned-message and ledger
duplicate guards, idempotent reruns, and the workflow's own guardrails. The
preflight tests execute the workflow's actual shell with mocked Bot API and
MTProto clients, check the input and permission matrix, and reject any ledger,
send, upload or pin access. A separate test drives Telethon's real bot login and
history RPC construction through an offline allowlisted RPC server that rejects
the user-only history call as Telegram does for bots.

Binary delivery is covered in two layers. The download side runs against a
loopback HTTP server and proves that a truncated body, a wrong digest, a missing
URL, a zero size and an over-ceiling file are all refused before anything is
uploaded. The upload side keeps **Telethon's own** `upload_file` and `send_file`
-- the 512 KiB chunk loop, the `upload.saveBigFilePart` requests, the filename
attributes, the response parsing -- and stubs only the network call, so a 12 MiB
file is reassembled byte-for-byte, a mid-upload drop is retried, FloodWait is
honoured, a permanent rejection is not retried, and an unconfirmed send is
reported as a failure. A separate test proves the login uses
`auth.importBotAuthorization` and never asks for a phone number or a code.

To see exactly what a release would post without sending anything, dispatch the
workflow with `dry_run` checked, or locally:

```bash
gh api repos/sascio/MyBridge/releases/latest > /tmp/release.json
python3 scripts/telegram_announce.py --release-json /tmp/release.json --dry-run
```

## Publishing a release, end to end

1. `.github/workflows/release-draft.yml` builds the Android APKs and the iOS IPA
   and creates a **draft** release. Nothing is announced.
2. A maintainer publishes the draft (for example with `gh release edit <tag> --draft=false`).
3. `release: published` fires both this workflow and the sideload-source workflow.
   This one posts the announcement without pinning and attempts to persist the
   ledger. `update-store-source.yml` refreshes `store.json` independently.

If the announcement fails, the release stays published. Check the channel and
ledger before a retry: partial delivery or a lost ledger update can cause duplicates.
