#!/usr/bin/env python3
"""Render and validate StreamBridge Telegram release announcements.

The Bot API client is read-only. Direct Bot API sends and pins are disabled
because they lack a caller-controlled idempotency key. Production delivery is
performed only by scripts/telegram_publish.py after durable MTProto planning.
Dry-run rendering and read-only preflight never publish or alter the ledger.
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
EXIT_CONFIG = 3
EXIT_INVALID = 4


class AnnouncementError(RuntimeError):
    """A failure that should be reported as a workflow error."""


@dataclass(repr=False)
class TelegramResult:
    ok: bool
    result: dict | None = None
    error_code: int | None = None
    description: str = ""
    retry_after: int | None = None
    attempts: int = 1

    def __repr__(self) -> str:
        return f"TelegramResult(ok={self.ok}, error_code={self.error_code}, attempts={self.attempts})"


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


# --------------------------------------------------------------------------- #
# Telegram Bot API client
# --------------------------------------------------------------------------- #


class TelegramClient:
    """Read-only Bot API client: getMe, getChat, getChatMember.

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
        # The Bot API has no client-provided idempotency key. It is restricted
        # to these read-only methods; all public sends use the durable MTProto path.
        if method not in {"getMe", "getChat", "getChatMember"}:
            return TelegramResult(ok=False, description="Bot API mutation is disabled", attempts=0)
        url = f"{TELEGRAM_API}/bot{self._token}/{method}"
        body = json.dumps(payload or {}).encode("utf-8")
        last = TelegramResult(ok=False, description="no attempt was made")
        for attempt in range(1, self._retries + 1):
            last = self._attempt(url, body, attempt)
            if last.ok:
                return last
            if not self._should_retry(last):
                return last
            if last.retry_after is not None and last.retry_after > 60:
                return last  # respect long server waits without blocking the runner indefinitely
            delay = last.retry_after if last.retry_after else min(2 ** attempt, 30)
            delay += random.uniform(0, 0.5)
            log(
                f"{method}: attempt {attempt}/{self._retries} failed "
                f"({last.error_code or 'network'}); retrying in {delay:.1f}s"
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
                description=f"network error: {type(exc).__name__}",
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
        error_code = data.get("error_code")
        return TelegramResult(
            ok=False,
            error_code=error_code if type(error_code) is int and 100 <= error_code <= 599 else None,
            description="Telegram API request failed",
            retry_after=retry_after if type(retry_after) is int and retry_after > 0 else None,
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

    def get_chat(self) -> TelegramResult:
        return self.call("getChat", {"chat_id": self._chat_id})


MARKER_RE = re.compile(r"#SB(\d{1,20})\b")


def marker_for(release_id: int) -> str:
    """The channel tag that identifies a release's announcement.

    ``#SB<release id>`` is a clickable release label, not proof of delivery or
    an idempotency key. Public sends require the durable MTProto ledger.
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


# Legacy read-only ledger inspection remains for migration diagnostics only.
# No pin or local-ledger observation is used as evidence that a bot can send.


def load_ledger(path: str) -> dict:
    if not path or not os.path.exists(path):
        raise AnnouncementError("legacy post ledger is missing; refusing to infer an empty history")
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        raise AnnouncementError(f"post ledger {path} is unreadable: {exc}") from exc
    if not isinstance(data, dict) or type(data.get("schema")) is not int or data.get("schema") not in (1, 2) or not isinstance(data.get("releases"), list):
        raise AnnouncementError("legacy post ledger is invalid or is not a legacy schema")
    return data


def ledger_records(path: str, release_id: int) -> bool:
    """True when the ledger already lists this release id.

    This is legacy inspection only, not proof about unknown channel history.
    Asset-only entries must not be mistaken for a confirmed announcement.
    """
    data = load_ledger(path)
    for entry in data["releases"]:
        if (isinstance(entry, dict) and int(entry.get("release_id", 0) or 0) == int(release_id)
                and entry.get("announced", bool(entry.get("message_ids")))):
            return True
    return False


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
    print(redact(f"release id : {run.release.id}"))
    print(redact(f"tag        : {run.release.tag_name}"))
    print(redact(f"version    : {run.release.version}"))
    print(f"pages      : {len(run.pages)}")
    print("=" * 78)
    for number, text in enumerate(run.pages, start=1):
        print(f"\n--- page {number}/{len(run.pages)} ({notes.telegram_length(text)} chars) ---\n")
        print(redact(text))
        for label, url in run.announcement.pages[number - 1].buttons:
            print(redact(f"  [{label}] -> {url}"))
    print("\n" + "=" * 78)
    print(redact(json.dumps(run.announcement.summary, indent=2, ensure_ascii=False)))
    print("=" * 78 + "\n")


def run_preflight(release: notes.Release | None, chat_id: str, token: str) -> int:
    """Read-only Bot API and configuration checks; no MTProto session is opened.

    A crash-safe MTProto session needs a durable session lock, which preflight
    must not modify. This check cannot establish MTProto login, history access,
    multipart uploads or delivery correctness.
    """
    del release
    client = TelegramClient(token, chat_id)
    result = client.get_chat()
    if not result.ok or (result.result or {}).get("type") != "channel":
        error("The Bot API could not verify access to the configured channel.")
        return EXIT_ERROR
    api_id = os.environ.get("TELEGRAM_API_ID", "")
    api_hash = os.environ.get("TELEGRAM_API_HASH", "")
    if not api_id.isdigit() or int(api_id) <= 0 or not re.fullmatch(r"[0-9a-fA-F]{32}", api_hash):
        error("MTProto credential syntax is invalid; MTProto authentication was not attempted.")
        return EXIT_CONFIG
    notice("Preflight passed Bot API channel access and MTProto credential syntax only. "
           "MTProto authentication, history and uploads were not tested.")
    return EXIT_OK


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--release-json", default="", help="file holding the GitHub API release object")
    parser.add_argument("--dry-run", action="store_true", help="render and validate, send nothing")
    parser.add_argument("--summary", default="", help="write a JSON run summary to this path")
    parser.add_argument("--ledger", default="", help="legacy option; the renderer never writes the ledger")
    parser.add_argument(
        "--pin",
        action="store_true",
        help="legacy option; direct publishing and pinning are disabled",
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
            "read-only Bot API channel check plus MTProto credential syntax only. The workflow "
            "also checks administrator rights. No MTProto login, message, upload or pin."
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

    # Match the workflow's isolation even when invoked directly: preflight
    # ignores release, dry-run, summary, ledger and pin arguments entirely.
    if args.preflight:
        try:
            token = require_env("TELEGRAM_BOT_TOKEN")
            chat_id = require_env("TELEGRAM_CHAT_ID")
        except AnnouncementError as exc:
            error(str(exc))
            return EXIT_CONFIG
        return run_preflight(None, chat_id, token)

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

    error("Direct Bot API publishing is disabled because it cannot preserve an idempotency key. "
          "Use the serialized GitHub Actions MTProto delivery workflow with its durable ledger.")
    return EXIT_CONFIG


if __name__ == "__main__":
    sys.exit(main())
