#!/usr/bin/env python3
"""Publish a StreamBridge release announcement to Telegram.

Run by ``.github/workflows/telegram-release-announcement.yml`` when a GitHub
release is published. The workflow is the thin part; every rule that matters is
here so it can be tested offline with a mock Telegram Bot API
(``scripts/tests/telegram-announcement-test.py``).

Delivery and safety:

  * Every announcement page is tagged with the release-id marker, for example
    ``#SB434756896``. A matching pinned message or a persisted post ledger entry
    skips a resend. The workflow does not pin by default and the bot cannot use
    the current user-only history lookup, so this is not exactly-once delivery.
  * The announcement is recorded as posted only after Telegram returns
    ``ok: true`` with a message id for every page. A partial send or failed
    ledger push can leave posts without a durable record; inspect before retrying.
  * Credentials are read from the environment and must not be printed or written
    to an artifact. The Bot API token is in its request URL, so diagnostics redact
    configured secret values and token-shaped strings.
  * Failures are bounded: per-request timeouts, retries with backoff for
    transient errors and for Telegram's own ``retry_after``, and a clear
    diagnostic for permanent ones (bad token, bot not an administrator, wrong
    chat).

Modes:

  * default    -- build the announcement, check it, and send it.
  * --dry-run  -- build and validate it, print the rendered messages, send
                  nothing. Used by the workflow's dry-run dispatch input and by
                  the tests.
  * --skip-html-check -- reserved for local experimentation; the workflow never
                  uses it.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import socket
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Callable

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import telegram_release_notes as notes  # noqa: E402

TELEGRAM_API = "https://api.telegram.org"
TOKEN_RE = re.compile(r"\b\d{6,}:[A-Za-z0-9_-]{20,}\b")
MAX_RETRIES = 4
REQUEST_TIMEOUT = 20
MAX_TELEGRAM_MESSAGES = 10
PAGE_INTERVAL = 1.25

EXIT_OK = 0
EXIT_ERROR = 1
EXIT_ALREADY_POSTED = 2
EXIT_CONFIG = 3
EXIT_INVALID = 4


class AnnouncementError(RuntimeError):
    """A failure that should be reported as a workflow error."""


class AlreadyAnnounced(AnnouncementError):
    """The release id marker already exists in the channel."""


@dataclass
class TelegramResult:
    ok: bool
    result: dict | None = None
    error_code: int | None = None
    description: str = ""
    retry_after: int | None = None
    attempts: int = 1


# --------------------------------------------------------------------------- #
# Logging
# --------------------------------------------------------------------------- #


def redact(text: str) -> str:
    """Remove configured Telegram secrets and token-shaped values from diagnostics."""
    result = text or ""
    # In addition to token-shaped values, exceptions from MTProto clients can
    # contain the API hash, API id or chat id. Mask the complete configured
    # values even when running outside GitHub Actions' automatic secret masking.
    configured = (
        os.environ.get(name, "")
        for name in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "TELEGRAM_API_ID", "TELEGRAM_API_HASH")
    )
    # The CLI strips surrounding whitespace when loading environment variables;
    # redact both representations if a secret was configured with whitespace.
    secrets = {value for raw in configured for value in (raw, raw.strip())}
    for value in sorted((value for value in secrets if value), key=len, reverse=True):
        result = result.replace(value, "<redacted>")
    return TOKEN_RE.sub("<redacted-bot-token>", result)


def log(message: str) -> None:
    print(redact(message), flush=True)


def notice(message: str) -> None:
    print(redact(f"::notice::{message}"), flush=True)


def warning(message: str) -> None:
    print(redact(f"::warning::{message}"), flush=True)


def error(message: str) -> None:
    print(redact(f"::error::{message}"), flush=True)


def mask_secret(value: str) -> None:
    """Ask GitHub Actions to mask a value in every later log line."""
    if value:
        print(f"::add-mask::{value}", flush=True)


# --------------------------------------------------------------------------- #
# Telegram Bot API client
# --------------------------------------------------------------------------- #


class TelegramClient:
    """Minimal Bot API client: sendMessage, getChat, pinChatMessage.

    Every call validates Telegram's response envelope (``ok`` and, for failures,
    ``error_code``/``description``) instead of trusting the HTTP status alone,
    because Telegram answers some application errors with a 200 or a JSON
    envelope that a naive caller would treat as success.
    """

    def __init__(
        self,
        token: str,
        chat_id: str,
        *,
        timeout: int = REQUEST_TIMEOUT,
        retries: int = MAX_RETRIES,
        sleeper: Callable[[float], None] = time.sleep,
        opener: Callable[..., object] | None = None,
    ) -> None:
        self._token = token
        self._chat_id = chat_id
        self._timeout = timeout
        self._retries = retries
        self._sleep = sleeper
        self._opener = opener or urllib.request.urlopen

    def __repr__(self) -> str:  # never leak a configured secret through a repr
        return "TelegramClient(chat_id=***)"

    @property
    def chat_id(self) -> str:
        return self._chat_id

    def call(self, method: str, payload: dict | None = None) -> TelegramResult:
        url = f"{TELEGRAM_API}/bot{self._token}/{method}"
        body = json.dumps(payload or {}).encode("utf-8")
        last = TelegramResult(ok=False, description="no attempt was made")
        for attempt in range(1, self._retries + 1):
            last = self._attempt(url, body, attempt)
            if last.ok:
                return last
            if not self._should_retry(last):
                return last
            delay = last.retry_after if last.retry_after else min(2 ** attempt, 30)
            delay += random.uniform(0, 0.5)
            log(
                f"{method}: attempt {attempt}/{self._retries} failed "
                f"({last.error_code or 'network'}: {redact(last.description)}); "
                f"retrying in {delay:.1f}s"
            )
            self._sleep(delay)
        return last

    def _attempt(self, url: str, body: bytes, attempt: int) -> TelegramResult:
        request = urllib.request.Request(
            url,
            data=body,
            method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        try:
            with self._opener(request, timeout=self._timeout) as response:  # type: ignore[union-attr]
                raw = response.read()
        except urllib.error.HTTPError as http_error:
            raw = http_error.read() if hasattr(http_error, "read") else b""
            parsed = self._parse(raw, attempts=attempt)
            if parsed is not None:
                return parsed
            return TelegramResult(
                ok=False,
                error_code=http_error.code,
                description=f"HTTP {http_error.code}",
                attempts=attempt,
            )
        except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as exc:
            return TelegramResult(
                ok=False,
                description=f"network error: {type(exc).__name__}: {exc}",
                attempts=attempt,
            )
        parsed = self._parse(raw, attempts=attempt)
        if parsed is None:
            return TelegramResult(
                ok=False,
                description="response was not a Telegram JSON envelope",
                attempts=attempt,
            )
        return parsed

    @staticmethod
    def _parse(raw: bytes, attempts: int) -> TelegramResult | None:
        try:
            data = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None
        if not isinstance(data, dict) or "ok" not in data:
            return None
        if data.get("ok") is True:
            result = data.get("result")
            return TelegramResult(
                ok=True,
                result=result if isinstance(result, dict) else {"value": result},
                attempts=attempts,
            )
        parameters = data.get("parameters") or {}
        retry_after = parameters.get("retry_after") if isinstance(parameters, dict) else None
        return TelegramResult(
            ok=False,
            error_code=int(data.get("error_code") or 0) or None,
            description=str(data.get("description") or "unknown Telegram error"),
            retry_after=int(retry_after) if retry_after else None,
            attempts=attempts,
        )

    @staticmethod
    def _should_retry(result: TelegramResult) -> bool:
        """Retry only what a retry can fix.

        * 429 -- Telegram asked us to wait; honour ``retry_after``.
        * 5xx -- Telegram side failure.
        * no error code -- transport failure (DNS, TLS, timeout, reset).
        A 400/401/403/404 is permanent: a retry would only repeat the same error.
        """
        if result.error_code is None:
            return True
        return result.error_code == 429 or result.error_code >= 500

    # -- API sugar ---------------------------------------------------------- #

    def send_message(self, text: str, buttons: list[tuple[str, str]] | None = None) -> TelegramResult:
        payload: dict = {
            "chat_id": self._chat_id,
            "text": text,
            "parse_mode": "HTML",
            "disable_notification": False,
            "link_preview_options": {"is_disabled": True},
        }
        if buttons:
            payload["reply_markup"] = {
                "inline_keyboard": [[{"text": label, "url": url}] for label, url in buttons]
            }
        return self.call("sendMessage", payload)

    def get_chat(self) -> TelegramResult:
        return self.call("getChat", {"chat_id": self._chat_id})

    def pinned_marker(self) -> str | None:
        """Marker of the pinned message, if it carries one.

        This is the read side of the idempotency check. It is best effort: the
        Bot API has no message-history method, so a channel where the
        announcement is not pinned cannot be inspected, and the caller then
        relies on the state file committed by earlier runs.
        """
        result = self.get_chat()
        if not result.ok:
            return None
        chat = result.result or {}
        pinned = chat.get("pinned_message")
        if not isinstance(pinned, dict):
            return None
        return marker_in_message(pinned)

    def pin_message(self, message_id: int) -> TelegramResult:
        return self.call(
            "pinChatMessage",
            {
                "chat_id": self._chat_id,
                "message_id": message_id,
                "disable_notification": True,
            },
        )


MARKER_RE = re.compile(r"#SB(\d{1,20})\b")


def marker_for(release_id: int) -> str:
    """The channel tag that identifies a release's announcement.

    ``#SB<release id>`` is a Telegram hashtag, so it is clickable and reads as the
    release's serial tag, while the digits make it a reliable idempotency key.
    """
    return f"#SB{int(release_id)}"


def marker_in_message(message: dict) -> str | None:
    """Recover the ``#SB-<release id>`` marker from a Telegram message object."""
    searchable: list[str] = []

    def visit(value: object) -> None:
        if isinstance(value, str):
            searchable.append(value)
        elif isinstance(value, dict):
            for nested in value.values():
                visit(nested)
        elif isinstance(value, list):
            for nested in value:
                visit(nested)

    visit(message.get("text"))
    visit(message.get("caption"))
    visit(message.get("reply_markup"))
    for value in searchable:
        match = MARKER_RE.search(value)
        if match:
            return f"#SB{match.group(1)}"
    return None


# --------------------------------------------------------------------------- #
# Release payload
# --------------------------------------------------------------------------- #


def load_release(path: str) -> notes.Release:
    """Read the release API JSON the workflow fetched, or the event payload.

    The workflow always downloads the release through the API. When that file is
    missing (local runs, or a direct dispatch), the GitHub event payload's
    ``release`` object is used instead so the script still has real data and
    never fabricates one.
    """
    data = None
    if path and os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    else:
        event_path = os.environ.get("GITHUB_EVENT_PATH", "")
        if event_path and os.path.exists(event_path):
            with open(event_path, encoding="utf-8") as handle:
                event = json.load(handle)
            data = event.get("release") if isinstance(event, dict) else None
    if not isinstance(data, dict):
        raise AnnouncementError(
            "no release payload was found; pass --release-json with the GitHub API release object"
        )
    try:
        return notes.Release.from_api(data)
    except ValueError as exc:
        raise AnnouncementError(f"release payload is unusable: {exc}") from exc


def channel_tag(release_id: int) -> str:
    """The branded footer tag that carries the release id."""
    return f"#StreamBridge {marker_for(release_id)}"


def page_text_with_marker(page: notes.Page, release_id: int) -> str:
    """The final page text, guaranteed to carry the release id marker."""
    text = page.text.rstrip()
    marker = marker_for(release_id)
    if marker in text:
        return text
    return f"{text}\n{marker}"


# --------------------------------------------------------------------------- #
# Announcement pipeline
# --------------------------------------------------------------------------- #


@dataclass
class AnnouncementRun:
    release: notes.Release
    announcement: notes.Announcement
    pages: list[str] = field(default_factory=list)
    sent_message_ids: list[int] = field(default_factory=list)
    pinned: bool = False
    already_announced: bool = False
    dry_run: bool = False
    warnings: list[str] = field(default_factory=list)


def build_pages(release: notes.Release) -> tuple[notes.Announcement, list[str]]:
    """Build the announcement and re-validate it with the marker included."""
    announcement = notes.build_announcement(release, tag=channel_tag(release.id))
    if announcement.html_problems:
        problems = "; ".join(announcement.html_problems[:10])
        raise AnnouncementError(f"generated announcement is not valid Telegram HTML: {problems}")

    pages: list[str] = []
    for number, page in enumerate(announcement.pages, start=1):
        text = page_text_with_marker(page, release.id)
        problems = notes.validate_html(text)
        if notes.telegram_length(text) > notes.TELEGRAM_TEXT_LIMIT:
            problems.append(
                f"page {number} is {notes.telegram_length(text)} characters, over the "
                f"{notes.TELEGRAM_TEXT_LIMIT}-character Telegram limit"
            )
        if problems:
            raise AnnouncementError(
                f"page {number} would be rejected by Telegram: {'; '.join(problems[:10])}"
            )
        pages.append(text)

    if len(pages) > MAX_TELEGRAM_MESSAGES:
        raise AnnouncementError(
            f"the announcement needs {len(pages)} messages, more than the "
            f"{MAX_TELEGRAM_MESSAGES}-message ceiling; shorten the release notes instead of "
            "truncating them silently"
        )
    return announcement, pages


def check_already_announced(
    client: TelegramClient | None,
    release: notes.Release,
    ledger_path: str,
) -> str | None:
    """Return a human reason when the release was already announced, else None.

    Two independent sources are consulted: the pinned channel message (the
    public record) and the post ledger this workflow commits after a successful
    send (the private record, which survives an unpinned channel).
    """
    marker = marker_for(release.id)
    if client is not None:
        pinned = client.pinned_marker()
        if pinned == marker:
            return f"the channel's pinned message already carries {marker}"
        if pinned and pinned != marker:
            log(f"Pinned message carries {pinned}, not {marker}; continuing.")
    if ledger_records(ledger_path, release.id):
        return f"the post ledger already records release id {release.id} ({marker})"
    return None


def send_announcement(
    client: TelegramClient,
    release: notes.Release,
    announcement: notes.Announcement,
    pages: list[str],
    *,
    pin: bool = True,
    throttle: Callable[[float], None] = time.sleep,
) -> AnnouncementRun:
    """Send every page in order, then pin the first one.

    A page that fails stops the run: a partially delivered announcement is
    reported as a failure carrying the message ids that did arrive, and the
    first page (which holds the download buttons) is only pinned once the whole
    thread is complete, so a half-sent announcement is never the channel's
    pinned post.
    """
    run = AnnouncementRun(release=release, announcement=announcement, pages=pages)
    for number, text in enumerate(pages, start=1):
        buttons = announcement.pages[number - 1].buttons
        result = client.send_message(text, buttons)
        if not result.ok:
            message_id = self_ids(run.sent_message_ids)
            raise AnnouncementError(
                f"Telegram rejected page {number}/{len(pages)} "
                f"({result.error_code or 'network'}: {redact(result.description)}). "
                f"Delivered so far: {message_id or 'none'}. "
                "The GitHub release is unaffected. Inspect the channel and ledger before "
                "retrying: the delivered pages might otherwise be posted again."
            )
        message_id = (result.result or {}).get("message_id")
        if not isinstance(message_id, int):
            raise AnnouncementError(
                f"Telegram accepted page {number}/{len(pages)} without returning a message id; "
                "treating the delivery as unconfirmed."
            )
        run.sent_message_ids.append(message_id)
        log(f"Sent page {number}/{len(pages)} as message {message_id} ({notes.telegram_length(text)} chars)")
        if number < len(pages):
            # Telegram asks bots to stay under roughly one message per second per
            # chat, so a multi-page announcement is paced instead of burst.
            throttle(PAGE_INTERVAL)

    if pin:
        result = client.pin_message(run.sent_message_ids[0])
        if result.ok:
            run.pinned = True
            log(f"Pinned message {run.sent_message_ids[0]} as the channel announcement.")
        else:
            run.warnings.append(
                "the announcement was delivered but could not be pinned "
                f"({result.error_code or 'network'}: {redact(result.description)}); "
                "the bot needs the 'Pin Messages' administrator right for that"
            )
            warning(run.warnings[-1])
    return run


def self_ids(ids: list[int]) -> str:
    return ", ".join(str(int(value)) for value in ids)


# --------------------------------------------------------------------------- #
# Post ledger
# --------------------------------------------------------------------------- #


def load_ledger(path: str) -> dict:
    if not path or not os.path.exists(path):
        return {"releases": []}
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        raise AnnouncementError(f"post ledger {path} is unreadable: {exc}") from exc
    if not isinstance(data, dict) or not isinstance(data.get("releases"), list):
        raise AnnouncementError(f"post ledger {path} does not have the expected shape")
    return data


def ledger_records(path: str, release_id: int) -> bool:
    """True when the ledger already lists this release id.

    The ledger is the private record; the channel pin is the public one. Both are
    consulted so a rerun is caught even when the pin is missing or carries an
    older release.
    """
    data = load_ledger(path)
    for entry in data["releases"]:
        if isinstance(entry, dict) and int(entry.get("release_id", 0) or 0) == int(release_id):
            return True
    return False


def record_in_ledger(path: str, release: notes.Release, message_ids: list[int], chat_id: str) -> bool:
    """Append the posted release to the ledger. Returns True when it changed.

    ``chat_id`` is retained as a caller argument but not persisted: it is
    supplied via a repository secret and is not needed for release-id checks.
    """
    data = load_ledger(path)
    releases = [entry for entry in data["releases"] if isinstance(entry, dict)]
    releases = [entry for entry in releases if int(entry.get("release_id", 0)) != release.id]
    releases.append(
        {
            "release_id": release.id,
            "tag_name": release.tag_name,
            "version": release.version,
            "message_ids": [int(value) for value in message_ids],
            "announced_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
    )
    releases.sort(key=lambda entry: int(entry.get("release_id", 0)), reverse=True)
    payload = {
        "schema": 1,
        "description": (
            "StreamBridge release announcements that Telegram confirmed. Written by "
            ".github/workflows/telegram-release-announcement.yml after a successful send; "
            "the release id is the idempotency key."
        ),
        "releases": releases,
    }
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    temporary = f"{path}.tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    os.replace(temporary, path)
    return True


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #


def write_summary(path: str, run: AnnouncementRun) -> None:
    if not path:
        return
    summary = dict(run.announcement.summary)
    summary.update(
        {
            "already_announced": run.already_announced,
            "dry_run": run.dry_run,
            "sent_message_ids": run.sent_message_ids,
            "pinned": run.pinned,
            "warnings": run.warnings,
        }
    )
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, ensure_ascii=False)
        handle.write("\n")


def print_dry_run(run: AnnouncementRun) -> None:
    print("\n" + "=" * 78)
    print(f"DRY RUN — nothing was sent to Telegram")
    print(f"release id : {run.release.id}")
    print(f"tag        : {run.release.tag_name}")
    print(f"version    : {run.release.version}")
    print(f"pages      : {len(run.pages)}")
    print("=" * 78)
    for number, text in enumerate(run.pages, start=1):
        print(f"\n--- page {number}/{len(run.pages)} ({notes.telegram_length(text)} chars) ---\n")
        print(text)
        for label, url in run.announcement.pages[number - 1].buttons:
            print(f"  [{label}] -> {url}")
    print("\n" + "=" * 78)
    print(json.dumps(run.announcement.summary, indent=2, ensure_ascii=False))
    print("=" * 78 + "\n")


def run_preflight(release: notes.Release, chat_id: str, token: str) -> int:
    """Read-only readiness check. Never posts, uploads, pins or edits.

    Everything here is a GET-equivalent Telegram call; the only side effect is a
    Telegram login for the MTProto check, which is what any announcement would do
    anyway. Nothing is printed that could carry a credential.
    """
    problems = 0
    client = TelegramClient(token, chat_id)
    mask_secret(token)

    result = client.get_chat()
    if not result.ok:
        error(
            f"The channel {chat_id} is not reachable with this bot token "
            f"({result.error_code or 'network'}: {redact(result.description)}). "
            "Check TELEGRAM_CHAT_ID and that the bot is a member of the channel."
        )
        problems += 1
    else:
        chat = result.result or {}
        title = chat.get("title") or chat.get("username") or chat_id
        log(f"Channel reachable: {title!r} ({chat.get('type', 'channel')}).")
        # The only right the delivery path needs: posting messages and files.
        status = chat.get("status")
        log(f"Bot membership status in the channel: {status or 'member'}.")
        if chat.get("type") not in (None, "channel"):
            warning(f"{chat_id} is a {chat.get('type')}, not a channel.")
        if status in (None, "member", "restricted"):
            warning(
                "The bot does not report administrator status. Posting may still work if it "
                "holds a direct post grant; if it does not, add the bot as an administrator "
                "with 'Post Messages' only."
            )

    # The existing MTProto lookup uses messages.getHistory, which Telegram
    # documents as user-only. A bot login can succeed but the lookup can still
    # fail; do not infer anything about upload readiness from that failure.
    api_id_text = os.environ.get("TELEGRAM_API_ID", "").strip()
    api_hash = os.environ.get("TELEGRAM_API_HASH", "").strip()
    marker = marker_for(release.id)
    if not api_id_text or not api_hash:
        warning(
            "TELEGRAM_API_ID / TELEGRAM_API_HASH are not set, so the APK/IPA upload was not "
            "verified and the channel-history duplicate check was skipped."
        )
    else:
        mask_secret(api_hash)
        try:
            import telegram_assets  # noqa: PLC0415 - optional dependency boundary

            uploader = telegram_assets.MtprotoUploader(int(api_id_text), api_hash, token, chat_id)
        except (ValueError, telegram_assets.ConfigError) as exc:
            error(f"MTProto credentials are unusable: {exc}")
            return EXIT_CONFIG
        try:
            found = uploader.find_marker(marker)
            if found:
                notice(
                    f"Release {release.id} already appears in the recent channel history "
                    f"({marker}); a rerun would report it as already announced."
                )
            else:
                log(
                    f"No inspected recent channel message carries {marker}; "
                    "this does not rule out an earlier announcement."
                )
            log("MTProto login succeeded with the bot token, API id and API hash.")
        except Exception as exc:  # noqa: BLE001 - report the failure without a traceback
            if type(exc).__name__ == "BotMethodInvalidError":
                error(
                    "The MTProto history check failed: Telegram does not allow bot accounts "
                    "to use messages.getHistory. Channel-history duplicate protection cannot "
                    "be verified with this bot; this does not establish whether uploads work."
                )
            else:
                error(
                    f"The MTProto check failed: {exc}. Channel-history access was not verified; "
                    "this does not establish whether large-file uploads work."
                )
            problems += 1
        finally:
            uploader.close()

    if problems:
        error(f"Preflight found {problems} problem(s); see above.")
        return EXIT_ERROR
    notice("Preflight passed: the Bot API token, the channel and the MTProto credentials are usable.")
    return EXIT_OK


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--release-json", default="", help="file holding the GitHub API release object")
    parser.add_argument("--dry-run", action="store_true", help="render and validate, send nothing")
    parser.add_argument("--summary", default="", help="write a JSON run summary to this path")
    parser.add_argument("--ledger", default="", help="post ledger JSON (idempotency record)")
    parser.add_argument(
        "--pin",
        action="store_true",
        help=(
            "pin the announcement after it is delivered (needs the bot's 'Pin Messages' "
            "administrator right; off by default, which keeps the required right to "
            "'Post Messages' and keeps the ledger marker as the duplicate guard)"
        ),
    )
    parser.add_argument(
        "--no-pin",
        action="store_true",
        help=argparse.SUPPRESS,
    )
    parser.add_argument(
        "--preflight",
        action="store_true",
        help=(
            "read-only check: test the bot token and channel, then attempt MTProto history. "
            "Bot accounts cannot use the current history method. The workflow separately "
            "checks administrator rights. Sends no message, uploads no file, pins nothing."
        ),
    )
    parser.add_argument(
        "--skip-html-check",
        action="store_true",
        help="development only: do not fail on HTML validation problems",
    )
    return parser.parse_args(argv)


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise AnnouncementError(
            f"the {name} secret is not configured for this workflow. Add it in "
            "Settings -> Secrets and variables -> Actions; the announcement is skipped "
            "until it exists (the GitHub release itself is unaffected)."
        )
    return value


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])

    try:
        release = load_release(args.release_json)
    except (AnnouncementError, json.JSONDecodeError, OSError) as exc:
        error(f"Cannot read the release: {exc}")
        return EXIT_INVALID

    if release.draft:
        notice(f"{release.tag_name} is a draft release; nothing is announced.")
        return EXIT_OK
    if release.prerelease:
        notice(f"{release.tag_name} is a pre-release; nothing is announced.")
        return EXIT_OK

    try:
        announcement, pages = build_pages(release)
    except AnnouncementError as exc:
        if not (args.skip_html_check and "valid Telegram HTML" in str(exc)):
            error(f"Announcement build failed: {exc}")
            return EXIT_INVALID
        announcement, pages = notes.build_announcement(release), []
        for page in announcement.pages:
            pages.append(page_text_with_marker(page, release.id))
        warning(f"HTML validation problems were ignored: {'; '.join(announcement.html_problems[:5])}")

    log(
        f"Release {release.id} ({release.tag_name}, version {release.version}) rendered as "
        f"{len(pages)} page(s), {sum(notes.telegram_length(page) for page in pages)} characters."
    )

    run = AnnouncementRun(release=release, announcement=announcement, pages=pages)
    run.dry_run = bool(args.dry_run)

    if args.dry_run:
        print_dry_run(run)
        write_summary(args.summary, run)
        return EXIT_OK

    if args.preflight:
        try:
            token = require_env("TELEGRAM_BOT_TOKEN")
            chat_id = require_env("TELEGRAM_CHAT_ID")
        except AnnouncementError as exc:
            error(str(exc))
            return EXIT_CONFIG
        return run_preflight(release, chat_id, token)

    try:
        token = require_env("TELEGRAM_BOT_TOKEN")
        chat_id = require_env("TELEGRAM_CHAT_ID")
    except AnnouncementError as exc:
        error(str(exc))
        return EXIT_CONFIG
    # Register the secret with the runner before any request that could echo it.
    mask_secret(token)

    client = TelegramClient(token, chat_id)
    try:
        reason = check_already_announced(client, release, args.ledger)
        if not reason:
            # The optional history lookup is best-effort and never turns into a
            # hard failure. Its current messages.getHistory RPC is user-only,
            # however, so bot credentials alone cannot make it a reliable guard.
            api_id_text = os.environ.get("TELEGRAM_API_ID", "").strip()
            api_hash = os.environ.get("TELEGRAM_API_HASH", "").strip()
            if api_id_text and api_hash:
                try:
                    import telegram_assets  # noqa: PLC0415 - optional dependency boundary

                    uploader = telegram_assets.MtprotoUploader(int(api_id_text), api_hash, token, chat_id)
                    try:
                        if uploader.find_marker(marker_for(release.id)):
                            reason = (
                                f"the channel history already carries {marker_for(release.id)}"
                            )
                    finally:
                        uploader.close()
                except Exception as exc:  # noqa: BLE001 - a guard must not break the post
                    warning(
                        f"The channel-history duplicate check could not run ({redact(str(exc))}); "
                        "relying on the pinned message and the ledger."
                    )
        if reason:
            run.already_announced = True
            notice(
                f"StreamBridge {release.version} was already announced: {reason}. "
                "No announcement pages were sent in this run."
            )
            write_summary(args.summary, run)
            return EXIT_ALREADY_POSTED

        run = send_announcement(
            client,
            release,
            announcement,
            pages,
            pin=bool(args.pin) and not args.no_pin,
        )
        if args.ledger:
            record_in_ledger(args.ledger, release, run.sent_message_ids, chat_id)
            log(f"Recorded release {release.id} in the post ledger.")
    except AnnouncementError as exc:
        error(str(exc))
        run.sent_message_ids = run.sent_message_ids or []
        write_summary(args.summary, run)
        return EXIT_ERROR

    notice(
        f"StreamBridge {release.version} announced in {chat_id}: message ids "
        f"{self_ids(run.sent_message_ids)}."
    )
    write_summary(args.summary, run)
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
