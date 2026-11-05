# Telegram release announcements

`.github/workflows/telegram-release-announcement.yml` publishes a StreamBridge
release announcement to the Telegram channel **[@StreamBridge_App](https://t.me/StreamBridge_App)**
whenever a GitHub Release is published. It is an announcement channel only: it
never creates, edits, publishes or deletes a release, and it does not touch the
Android or iOS build jobs.

## What triggers it

| Event | Behaviour |
|---|---|
| `release`, type `published` | The announcement is rendered from that release and posted. |
| Draft creation, tag push, branch push | Never triggers the workflow. |
| `release` with `prerelease: true` | The job is skipped; nothing is posted. |
| Manual dispatch, default | Dry run: the exact messages are rendered and validated in the log, nothing is sent. |
| Manual dispatch, `dry_run` unchecked | Sends for the resolved release (`tag` input, or the latest published release). |

Every future release is covered automatically. No version number is hardcoded:
the release name, tag, version code, notes, assets, sizes and URLs all come from
the GitHub API object of the release that fired the event.

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
| `🤖 Android APK` | The `browser_download_url` of the primary APK (arm64 first) |
| `🍏 iOS IPA` | The `browser_download_url` of the published IPA |
| `🔐 Checksums` | The `browser_download_url` of `checksums.sha256` |

Asset buttons appear only for assets that exist and are `uploaded`. No URL is
ever assembled from a pattern, and the HTML validator rejects any link that is
not `https://`.

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

Idempotency is keyed on the **release id**, not on the workflow event:

* every page carries `#SB<release id>` in its footer;
* before sending, the workflow reads the channel's pinned message (`getChat`)
  and stops if it carries the same tag — a rerun, a retried job or a duplicated
  `release` event is reported as "already announced" and nothing is sent;
* after Telegram confirms **every** page (`ok: true` plus a `message_id`), the
  first message is pinned and `.github/telegram-releases.json` records the
  release id and message ids. The ledger is committed, so the guard still works
  if the pin later moves to another message.

`concurrency` (grouped by release id, never cancelling) only stops two runs from
racing; it is not the protection. Nothing is marked as posted before Telegram
confirms delivery, and a partial failure reports the message ids that arrived.

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
* The bot token is read from the environment, sent only in the HTTPS request
  body, masked with `::add-mask::`, and redacted from every log line the script
  prints. It is never passed as an argument, echoed, or written to an artifact.
  The workflow uploads no artifacts at all.

## Setup

1. `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` are repository secrets.
   `TELEGRAM_CHAT_ID` is `@StreamBridge_App`.
2. **Merge this workflow into the default branch (`main`).** GitHub only creates
   a `release: published` trigger for workflows that exist on the default branch.
   On any other branch the file is inert: no run starts, and the workflow does
   not appear in the Actions list until the merge lands. `workflow_dispatch` also
   only offers workflows from the default branch in the UI.
3. The bot must be an **administrator** of the channel with *Post Messages*.
   *Pin Messages* enables the pinned duplicate guard (and the pin itself); without
   it the announcement still posts and the step reports a warning.
4. That is all. The first release published after the merge is announced
   automatically. Releases published before the merge are not backfilled; use a
   manual dispatch with `dry_run` unchecked if one should be announced.

## Testing

```bash
scripts/tests/telegram-announcement-test.py     # offline; no credentials needed
scripts/tests/run-tests.sh                      # the repository's whole suite
```

The tests replace the Bot API with a scripted mock, so no test can reach the
channel. They cover event filtering (draft, prerelease, duplicate), note parsing,
HTML escaping, section routing, long-note splitting, asset detection and missing
assets, button URLs, missing secrets, token redaction, 429/5xx/400/network
failures, partial delivery, unconfirmed delivery, the pinned-message and ledger
duplicate guards, idempotent reruns, and the workflow's own guardrails.

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
   This one posts the announcement, pins it and records it in the ledger.
   `update-store-source.yml` refreshes `store.json` independently.

If the announcement fails, the release stays published and can be re-run safely
at any time: it will not post twice.
