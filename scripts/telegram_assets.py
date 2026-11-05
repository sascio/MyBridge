#!/usr/bin/env python3
"""Download, verify and publish the StreamBridge release binaries to Telegram.

The production StreamBridge assets are larger than the 50 MB ceiling of the
public Bot API (the four APKs are 52-56 MiB and the unsigned IPA is ~70 MiB), so
``sendDocument`` against ``api.telegram.org`` can never deliver them. This module
uploads them over **MTProto** instead, using the *same bot token* that already
publishes the announcement. MTProto lifts the ceiling for bots to 2000 MB per
file without a second service, a user account, a phone number or a login code.

Flow per asset:

1. plan          -- decide which published assets are production binaries
2. download      -- stream to a temporary file, then verify size and SHA-256
3. upload        -- MTProto ``upload_file`` + ``send_file`` as a document
4. record        -- write the confirmed message id into the post ledger

Every step is resumable. An asset is only recorded after Telegram returns a
message id, so a rerun skips what succeeded and retries only what failed.

Nothing here prints, logs or persists the bot token, the API hash or a session.
"""

from __future__ import annotations

import hashlib
import html
import inspect
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Callable, Protocol, Sequence

import telegram_release_notes as notes

# Telegram's MTProto ceiling for a bot account is 2000 MB per file. Telegram
# documents this as the local-Bot-API limit and it is the same underlying
# MTProto limit; the value is a hard guard, never a target.
UPLOAD_MAX_BYTES = 2000 * 1000 * 1000
CAPTION_LIMIT = 1024
DOWNLOAD_CHUNK = 1024 * 1024
DOWNLOAD_TIMEOUT = 120
DOWNLOAD_ATTEMPTS = 3
UPLOAD_ATTEMPTS = 3
FLOOD_WAIT_CEILING = 600
BACKOFF_BASE = 4.0

EXIT_OK = 0
EXIT_ERROR = 1
EXIT_CONFIG = 3

_ANDROID_LABELS = {
    "arm64-v8a": "ARM64-v8a",
    "armeabi-v7a": "ARMv7",
    "x86": "x86",
    "x86_64": "x86_64",
}
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class AssetError(RuntimeError):
    """A release binary could not be verified, downloaded or uploaded."""


class ConfigError(AssetError):
    """A required secret is missing or malformed."""


def log(message: str) -> None:
    print(message, flush=True)


def notice(message: str) -> None:
    print(f"::notice::{message}", flush=True)


def warning(message: str) -> None:
    print(f"::warning::{message}", flush=True)


def error(message: str) -> None:
    print(f"::error::{message}", flush=True)


# --------------------------------------------------------------------------- #
# Planning
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class UploadPlan:
    """One production binary to deliver, with the caption it will carry."""

    asset: notes.Asset
    kind: str  # "android" | "ios"
    filename: str
    caption: str

    @property
    def key(self) -> str:
        return self.asset.name


def _caption(release: notes.Release, asset: notes.Asset, kind: str) -> str:
    """Caption with version, architecture, size and the GitHub download link.

    Telegram caps captions at 1024 characters after entity parsing; the text
    here is a handful of short lines, so the ceiling is only a guard against a
    pathological asset name.
    """
    if kind == "ios":
        heading = "🍎 <b>StreamBridge iOS — Unsigned IPA</b>"
        detail = "AltStore / SideStore sideload build"
    else:
        abi = asset.abi or "universal"
        label = _ANDROID_LABELS.get(abi, abi)
        heading = f"🤖 <b>StreamBridge Android — {html.escape(label, quote=False)}</b>"
        detail = f"ABI <code>{html.escape(abi, quote=False)}</code>"

    lines = [
        heading,
        f"Version {html.escape(release.version, quote=False)} · "
        f"{html.escape(notes.human_size(asset.size), quote=False)}",
        detail,
        f'⬇️ <a href="{html.escape(asset.url, quote=True).replace("&amp;", "&amp;")}">'
        "Download from GitHub</a>",
    ]
    caption = "\n".join(lines)
    if notes.telegram_length(caption) > CAPTION_LIMIT:
        # Only reachable with an absurd asset name; drop the detail line first.
        caption = "\n".join([heading, lines[1], lines[3]])
        if notes.telegram_length(caption) > CAPTION_LIMIT:
            raise AssetError(f"the caption for {asset.name} cannot fit in {CAPTION_LIMIT} characters")
    return caption


def plan_uploads(release: notes.Release) -> tuple[list[UploadPlan], list[str]]:
    """Return the upload plans for a release, plus reasons for anything skipped.

    Only assets the GitHub API reports as ``uploaded`` are planned: an asset
    whose upload is still in flight would be a truncated file. Debug-signed CI
    artifacts never appear as release assets, and anything that is not an APK
    or an IPA is reported instead of being uploaded.
    """
    summary = notes.classify_assets(release.assets)
    plans: list[UploadPlan] = []
    for asset in summary.android:
        plans.append(UploadPlan(asset, "android", asset.name, _caption(release, asset, "android")))
    if summary.ios is not None:
        ios = summary.ios
        plans.append(UploadPlan(ios, "ios", ios.name, _caption(release, ios, "ios")))

    skipped: list[str] = []
    for asset in release.assets:
        if any(plan.key == asset.name for plan in plans):
            continue
        if asset.name.lower().endswith((".apk", ".ipa")):
            skipped.append(f"{asset.name} was not published yet (state: {asset.state or 'unknown'})")
        else:
            skipped.append(f"{asset.name} is not an installable binary")
    for missing in summary.missing:
        skipped.append(f"missing: {missing}")
    return plans, skipped


# --------------------------------------------------------------------------- #
# Download and verification
# --------------------------------------------------------------------------- #


def parse_checksums(text: str) -> dict[str, str]:
    """Parse a ``sha256sum``-style file into ``{filename: hex digest}``.

    Both the ``<digest>  <name>`` and BSD ``SHA256 (<name>) = <digest>`` shapes
    are understood, and paths are reduced to their basename.
    """
    digests: dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.match(r"^SHA256 \((.+)\) = ([0-9a-fA-F]{64})$", line)
        if match:
            digests[os.path.basename(match.group(1).strip())] = match.group(2).lower()
            continue
        parts = line.split()
        if len(parts) >= 2 and _SHA256_RE.match(parts[0].lower()):
            digests[os.path.basename(parts[-1].lstrip("*"))] = parts[0].lower()
    return digests


def expected_digest(asset: notes.Asset, checksums: dict[str, str]) -> str | None:
    """The SHA-256 this asset must have, from the API digest or a checksum file."""
    digest = (asset.digest or "").strip().lower()
    if digest.startswith("sha256:"):
        value = digest.split(":", 1)[1]
        if _SHA256_RE.match(value):
            return value
    return checksums.get(asset.name)


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(DOWNLOAD_CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _fetch_text(url: str, opener: Callable[..., object] = urllib.request.urlopen) -> str:
    with opener(url, timeout=DOWNLOAD_TIMEOUT) as response:  # type: ignore[attr-defined]
        return response.read().decode("utf-8", "replace")


def load_checksums(release: notes.Release, opener: Callable[..., object] = urllib.request.urlopen) -> dict[str, str]:
    """Fetch and parse the release's checksum asset, if there is one.

    Verification is defence in depth: the release notes already tell users to
    check the digest, and a corrupt download must never reach the channel.
    """
    summary = notes.classify_assets(release.assets)
    if summary.checksums is None:
        return {}
    try:
        return parse_checksums(_fetch_text(summary.checksums.url, opener))
    except (OSError, urllib.error.URLError) as exc:
        warning(f"Could not read {summary.checksums.name} ({exc}); relying on the size check.")
        return {}


def download_asset(
    asset: notes.Asset,
    destination: str,
    *,
    checksums: dict[str, str] | None = None,
    opener: Callable[..., object] = urllib.request.urlopen,
    sleeper: Callable[[float], None] = time.sleep,
    attempts: int = DOWNLOAD_ATTEMPTS,
) -> tuple[str, str | None]:
    """Download ``asset`` to ``destination`` and prove it is complete.

    Returns ``(path, sha256)``. The file is written to ``<destination>.part``
    and renamed only after the byte count and (when known) the digest match, so
    a truncated download can never be handed to the uploader.
    """
    if not asset.url:
        raise AssetError(f"{asset.name} has no download URL in the release")
    if asset.size <= 0:
        raise AssetError(f"{asset.name} reports no size; refusing to upload an unverifiable file")
    if asset.size > UPLOAD_MAX_BYTES:
        raise AssetError(
            f"{asset.name} is {notes.human_size(asset.size)}, over the {UPLOAD_MAX_BYTES // 1_000_000} MB "
            "MTProto ceiling for bots"
        )

    want = expected_digest(asset, checksums or {})
    partial = f"{destination}.part"
    last_error = "unknown error"
    for attempt in range(1, attempts + 1):
        try:
            digest = hashlib.sha256()
            written = 0
            with opener(asset.url, timeout=DOWNLOAD_TIMEOUT) as response:  # type: ignore[attr-defined]
                status = getattr(response, "status", 200)
                if status != 200:
                    raise AssetError(f"HTTP {status} from {asset.url}")
                with open(partial, "wb") as handle:
                    while True:
                        chunk = response.read(DOWNLOAD_CHUNK)
                        if not chunk:
                            break
                        written += len(chunk)
                        if written > asset.size:
                            raise AssetError(
                                f"{asset.name} served more than the {asset.size} bytes the release reports"
                            )
                        digest.update(chunk)
                        handle.write(chunk)
                    handle.flush()
                    os.fsync(handle.fileno())

            if written != asset.size:
                raise AssetError(
                    f"incomplete download: {written} of {asset.size} bytes for {asset.name}"
                )
            actual = digest.hexdigest()
            if want is not None and actual != want:
                raise AssetError(
                    f"digest mismatch for {asset.name}: expected {want}, downloaded {actual}"
                )
            os.replace(partial, destination)
            log(
                f"Verified {asset.name}: {notes.human_size(written)}"
                + (" (SHA-256 matches)" if want else " (size only; no published digest)")
            )
            return destination, (actual if want else actual)
        except (AssetError, OSError, urllib.error.URLError) as exc:
            last_error = str(exc)
            _discard(partial)
            if attempt < attempts:
                delay = BACKOFF_BASE * attempt
                warning(f"Download attempt {attempt}/{attempts} for {asset.name} failed ({exc}); retrying in {delay:.0f}s.")
                sleeper(delay)
    raise AssetError(f"could not download {asset.name} after {attempts} attempts: {last_error}")


def _discard(path: str) -> None:
    try:
        os.unlink(path)
    except OSError:
        pass


# --------------------------------------------------------------------------- #
# MTProto transport
# --------------------------------------------------------------------------- #


class UploadTransport(Protocol):
    """What the orchestrator needs from a Telegram uploader."""

    def upload_document(self, path: str, filename: str, caption: str) -> int: ...


@dataclass
class UploadRecord:
    name: str
    kind: str
    size: int
    state: str  # "uploaded" | "failed" | "skipped" | "already"
    message_id: int | None = None
    url: str = ""
    detail: str = ""


class MtprotoUploader:
    """Upload documents to the StreamBridge channel as the existing bot.

    Deliberately narrow: it only ever sends documents, and it authenticates as
    the bot, so no user account, phone number or login code is involved. The
    Telethon session lives in memory (``StringSession``) and is never written to
    disk, so no reusable credential can leak through a workspace or an artifact.
    """

    def __init__(
        self,
        api_id: int,
        api_hash: str,
        bot_token: str,
        chat_id: str,
        *,
        client_factory: Callable[..., object] | None = None,
        sleeper: Callable[[float], None] = time.sleep,
        attempts: int = UPLOAD_ATTEMPTS,
        flood_wait_ceiling: int = FLOOD_WAIT_CEILING,
    ) -> None:
        if api_id <= 0:
            raise ConfigError("TELEGRAM_API_ID must be a positive integer")
        if not api_hash or len(api_hash) < 16:
            raise ConfigError("TELEGRAM_API_HASH does not look like a valid API hash")
        if not bot_token or ":" not in bot_token:
            raise ConfigError("TELEGRAM_BOT_TOKEN does not look like a bot token")
        self._api_id = api_id
        self._api_hash = api_hash
        self._bot_token = bot_token
        self._chat_id = chat_id
        self._client_factory = client_factory
        self._sleeper = sleeper
        self._attempts = attempts
        self._flood_wait_ceiling = flood_wait_ceiling
        self._client: object | None = None
        self._closed = False

    def __repr__(self) -> str:  # never leak a secret through a repr
        return f"MtprotoUploader(chat_id={self._chat_id!r}, api_id=***, api_hash=***, token=***)"

    # -- connection -------------------------------------------------------- #

    def _connect(self) -> object:
        if self._client is not None:
            return self._client

        factory = self._client_factory
        if factory is None:
            try:
                from telethon import TelegramClient  # noqa: PLC0415 - optional dependency
                from telethon.sessions import StringSession  # noqa: PLC0415
            except ImportError as exc:  # pragma: no cover - exercised by a test
                raise ConfigError(
                    "Telethon is not installed, so the APK/IPA files cannot be uploaded over "
                    "MTProto. Install it with `pip install 'telethon>=1.36'` (the workflow does "
                    "this) and re-run; the announcement itself does not need it."
                ) from exc
            factory = TelegramClient

        # StringSession keeps the authorisation in memory only: nothing is
        # written to disk, so no session file can be committed or uploaded.
        session = None
        try:
            from telethon.sessions import StringSession  # noqa: PLC0415

            session = StringSession()
        except ImportError:  # pragma: no cover - only when a test injects a factory
            session = None

        kwargs = {"connection_retries": 5, "request_retries": 5, "timeout": 300}
        client = factory(session, self._api_id, self._api_hash, **kwargs) if session is not None else factory(
            self._api_id, self._api_hash, **kwargs
        )
        start = getattr(client, "start", None)
        if callable(start):
            # ``start`` is a coroutine: it opens the connection and signs in with
            # ``sign_in(bot_token=...)``, which is the only credential involved.
            self._await(client, start(bot_token=self._bot_token))
        self._client = client
        return client

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        client = self._client
        self._client = None
        if client is None:
            return
        try:
            disconnect = getattr(client, "disconnect", None)
            if callable(disconnect):
                self._await(client, disconnect())
        except Exception as exc:  # noqa: BLE001 - teardown must never mask a result
            warning(f"Closing the MTProto client reported: {exc}")

    def __enter__(self) -> "MtprotoUploader":
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()

    # -- upload ------------------------------------------------------------ #

    def upload_document(self, path: str, filename: str, caption: str) -> int:
        """Upload one file as a document and return the confirmed message id."""
        size = os.path.getsize(path)
        if size > UPLOAD_MAX_BYTES:
            raise AssetError(
                f"{filename} is {notes.human_size(size)}, over the {UPLOAD_MAX_BYTES // 1_000_000} MB ceiling"
            )
        last_error = "unknown error"
        for attempt in range(1, self._attempts + 1):
            try:
                client = self._connect()
                message = self._send(client, path, filename, caption)
                message_id = getattr(message, "id", None)
                document = getattr(message, "document", None) or getattr(message, "media", None)
                if not isinstance(message_id, int) or document is None:
                    raise AssetError(
                        f"Telegram accepted {filename} without returning a document message; "
                        "treating the upload as unconfirmed"
                    )
                log(f"Uploaded {filename} ({notes.human_size(size)}) as message {message_id}.")
                return message_id
            except Exception as exc:  # noqa: BLE001 - every backend error is retried once classified
                last_error = str(exc)
                delay = self._retry_delay(exc, attempt)
                if delay is None:
                    raise AssetError(f"Telegram rejected the upload of {filename}: {last_error}") from exc
                if attempt < self._attempts:
                    warning(
                        f"Upload attempt {attempt}/{self._attempts} for {filename} failed "
                        f"({last_error}); retrying in {delay:.0f}s."
                    )
                    self._sleeper(delay)
        raise AssetError(f"could not upload {filename} after {self._attempts} attempts: {last_error}")

    def _send(self, client: object, path: str, filename: str, caption: str) -> object:
        """Upload the bytes, then send the uploaded handle as a document.

        The two steps are kept apart on purpose. ``upload_file`` is where the
        chunk size lives (512 KiB is Telegram's recommended maximum), and it
        returns a handle Telegram will later accept by reference, so the send
        call carries no file body and cannot be re-uploaded after a transient
        error. ``send_file`` on the handle sends a real document whatever the
        extension is, because ``force_document`` and an explicit filename
        attribute are supplied.
        """
        from telethon.tl.types import DocumentAttributeFilename  # noqa: PLC0415

        document = self._await(
            client,
            client.upload_file(  # type: ignore[attr-defined]
                path,
                part_size_kb=512,
                file_size=os.path.getsize(path),
                file_name=filename,
            ),
        )
        return self._await(
            client,
            client.send_file(  # type: ignore[attr-defined]
                self._chat_id,
                document,
                caption=caption,
                parse_mode="html",
                force_document=True,
                attributes=[DocumentAttributeFilename(file_name=filename)],
                supports_streaming=False,
                allow_cache=True,
            ),
        )

    @staticmethod
    def _await(client: object, value: object) -> object:
        """Run a coroutine on the client's own event loop.

        Telethon's upload and send methods are coroutines while this script is
        synchronous, so the client's loop has to drive them. Reusing the client's
        loop (rather than creating a second one) keeps the authorisation state
        that ``start()`` established.
        """
        if not inspect.isawaitable(value):
            return value
        loop = getattr(client, "loop", None)
        if loop is None:
            raise AssetError("the MTProto client has no event loop to run the upload on")
        return loop.run_until_complete(value)

    def _retry_delay(self, exc: Exception, attempt: int) -> float | None:
        """Seconds to wait before retrying, or None when the error is permanent.

        Telegram's own error taxonomy is used rather than string matching where
        possible: FloodWait carries the wait, server errors and timeouts are
        retried, and an authorisation or chat error is permanent because
        retrying it would only burn the rate limit.
        """
        name = type(exc).__name__
        if name == "FloodWaitError":
            seconds = getattr(exc, "seconds", None)
            if isinstance(seconds, int) and 0 <= seconds <= self._flood_wait_ceiling:
                return float(max(seconds, 1))
            return None
        permanent = {
            "AuthKeyError",
            "BadRequestError",
            "ChatAdminRequiredError",
            "ChannelPrivateError",
            "ForbiddenError",
            "InviteHashExpiredError",
            "UsernameInvalidError",
            "UsernameNotOccupiedError",
            "PeerIdInvalidError",
            "UnauthorizedError",
            "FilePartsInvalidError",
            "FileTooBigError",
        }
        if name in permanent:
            return None
        if name in {"ServerError", "TimedOutError", "TimeoutError", "RPCError"}:
            # A generic RPCError is only retried once; anything repeated is
            # treated as a permanent rejection further up the stack.
            return None if attempt >= 2 and name == "RPCError" else BACKOFF_BASE * attempt
        if isinstance(exc, (TimeoutError, ConnectionError, OSError)):
            return BACKOFF_BASE * attempt
        return None


# --------------------------------------------------------------------------- #
# Per-asset state, stored inside the announcement's post ledger
# --------------------------------------------------------------------------- #


def load_ledger(path: str) -> dict:
    if not path or not os.path.exists(path):
        return {"schema": 2, "releases": []}
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        raise AssetError(f"post ledger {path} is unreadable: {exc}") from exc
    if not isinstance(data, dict) or not isinstance(data.get("releases"), list):
        raise AssetError(f"post ledger {path} does not have the expected shape")
    return data


def _release_entry(data: dict, release_id: int) -> dict | None:
    for entry in data.get("releases") or []:
        if isinstance(entry, dict) and int(entry.get("release_id", 0) or 0) == int(release_id):
            return entry
    return None


def recorded_uploads(path: str, release_id: int) -> dict[str, dict]:
    """Assets already confirmed by Telegram for this release, keyed by name."""
    try:
        data = load_ledger(path)
    except AssetError:
        return {}
    entry = _release_entry(data, release_id)
    if not entry:
        return {}
    assets = entry.get("assets")
    if not isinstance(assets, list):
        return {}
    recorded: dict[str, dict] = {}
    for item in assets:
        if isinstance(item, dict) and item.get("name") and item.get("message_id"):
            recorded[str(item["name"])] = item
    return recorded


def record_upload(
    path: str,
    release: notes.Release,
    plan: UploadPlan,
    message_id: int,
    *,
    sha256: str = "",
    chat_id: str = "",
) -> None:
    """Record one confirmed upload, preserving everything else in the ledger."""
    data = load_ledger(path)
    releases = [entry for entry in data["releases"] if isinstance(entry, dict)]
    entry = _release_entry({"releases": releases}, release.id) or {
        "release_id": release.id,
        "tag_name": release.tag_name,
        "version": release.version,
        "announced": False,
        "message_ids": [],
        "assets": [],
    }
    assets = [item for item in entry.get("assets") or [] if isinstance(item, dict)]
    assets = [item for item in assets if item.get("name") != plan.asset.name]
    assets.append(
        {
            "name": plan.asset.name,
            "kind": plan.kind,
            "size": plan.asset.size,
            "sha256": sha256 or None,
            "message_id": int(message_id),
            "chat_id": chat_id or None,
            "uploaded_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
    )
    entry["assets"] = assets
    releases = [item for item in releases if int(item.get("release_id", 0) or 0) != release.id]
    releases.append(entry)
    releases.sort(key=lambda item: int(item.get("release_id", 0) or 0), reverse=True)
    payload = {
        "schema": 2,
        "description": (
            "StreamBridge releases that Telegram confirmed. Written by "
            ".github/workflows/telegram-release-announcement.yml only after a successful "
            "send; the release id is the idempotency key, and each asset's message id proves "
            "that particular upload landed."
        ),
        "releases": releases,
    }
    os.makedirs(os.path.dirname(os.path.abspath(path)) or ".", exist_ok=True)
    temporary = f"{path}.tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    os.replace(temporary, path)


# --------------------------------------------------------------------------- #
# Orchestration
# --------------------------------------------------------------------------- #


@dataclass
class UploadRun:
    release: notes.Release
    plans: list[UploadPlan] = field(default_factory=list)
    records: list[UploadRecord] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)
    dry_run: bool = False
    warnings: list[str] = field(default_factory=list)

    @property
    def uploaded(self) -> list[UploadRecord]:
        return [record for record in self.records if record.state in {"uploaded", "already"}]

    @property
    def failed(self) -> list[UploadRecord]:
        return [record for record in self.records if record.state == "failed"]

    @property
    def ok(self) -> bool:
        return not self.failed


def upload_release_assets(
    release: notes.Release,
    *,
    transport: UploadTransport | None,
    workspace: str,
    ledger_path: str = "",
    chat_id: str = "",
    opener: Callable[..., object] = urllib.request.urlopen,
    sleeper: Callable[[float], None] = time.sleep,
    download: Callable[..., tuple[str, str | None]] = download_asset,
    dry_run: bool = False,
) -> UploadRun:
    """Download and publish every production binary for ``release``.

    An asset that is already recorded is skipped, so a rerun after a partial
    failure retries only the missing files. A failure on one asset never stops
    the others: the caller reports it, and its verified GitHub link is already in
    the announcement's buttons, so users are never left without a route.
    """
    run = UploadRun(release=release, dry_run=dry_run)
    run.plans, run.skipped = plan_uploads(release)
    if not run.plans:
        run.warnings.append("this release publishes no APK or IPA file to upload")
        return run

    recorded = recorded_uploads(ledger_path, release.id) if ledger_path else {}
    checksums: dict[str, str] = {}

    os.makedirs(workspace, exist_ok=True)
    for plan in run.plans:
        if plan.asset.name in recorded:
            message_id = int(recorded[plan.asset.name]["message_id"])
            run.records.append(
                UploadRecord(
                    plan.asset.name,
                    plan.kind,
                    plan.asset.size,
                    "already",
                    message_id,
                    plan.asset.url,
                    "already uploaded by an earlier run",
                )
            )
            log(f"{plan.asset.name} is already in the channel as message {message_id}; skipping.")
            continue

        if dry_run:
            run.records.append(
                UploadRecord(plan.asset.name, plan.kind, plan.asset.size, "skipped", None, plan.asset.url, "dry run")
            )
            log(f"[dry run] would download and upload {plan.asset.name} ({notes.human_size(plan.asset.size)}).")
            continue

        if transport is None:
            run.records.append(
                UploadRecord(
                    plan.asset.name,
                    plan.kind,
                    plan.asset.size,
                    "failed",
                    None,
                    plan.asset.url,
                    "no upload transport is configured",
                )
            )
            continue

        if not checksums:
            checksums = load_checksums(release, opener) if plan.kind == "android" else checksums

        destination = os.path.join(workspace, plan.asset.name)
        try:
            path, digest = download(
                plan.asset, destination, checksums=checksums, opener=opener, sleeper=sleeper
            )
            message_id = transport.upload_document(path, plan.filename, plan.caption)
            if ledger_path:
                record_upload(ledger_path, release, plan, message_id, sha256=digest or "", chat_id=chat_id)
            run.records.append(
                UploadRecord(plan.asset.name, plan.kind, plan.asset.size, "uploaded", message_id, plan.asset.url)
            )
        except AssetError as exc:
            message = str(exc)
            run.records.append(
                UploadRecord(plan.asset.name, plan.kind, plan.asset.size, "failed", None, plan.asset.url, message)
            )
            # The GitHub release is the source of truth and keeps working; the
            # verified direct link is reported so users are not stranded.
            error(
                f"{plan.asset.name} was not delivered to Telegram: {message}. "
                f"It is still available at {plan.asset.url}"
            )
        except Exception as exc:  # noqa: BLE001 - one bad asset must not stop the rest
            run.records.append(
                UploadRecord(plan.asset.name, plan.kind, plan.asset.size, "failed", None, plan.asset.url, str(exc))
            )
            error(f"{plan.asset.name} was not delivered to Telegram: {exc}. It is still available at {plan.asset.url}")
        finally:
            # Temporary downloads never survive the job, whatever happened.
            _discard(f"{destination}.part")
            _discard(destination)
    return run


def write_summary(path: str, run: UploadRun) -> None:
    if not path:
        return
    payload = {
        "release_id": run.release.id,
        "tag_name": run.release.tag_name,
        "version": run.release.version,
        "dry_run": run.dry_run,
        "planned": [plan.asset.name for plan in run.plans],
        "uploaded": [
            {"name": record.name, "message_id": record.message_id, "url": record.url}
            for record in run.uploaded
        ],
        "failed": [
            {"name": record.name, "error": record.detail, "url": record.url} for record in run.failed
        ],
        "skipped": run.skipped,
        "warnings": run.warnings,
    }
    os.makedirs(os.path.dirname(os.path.abspath(path)) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, ensure_ascii=False)
        handle.write("\n")


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ConfigError(
            f"the {name} secret is not configured for this workflow. Add it in "
            "Settings -> Secrets and variables -> Actions; the announcement is still "
            "posted, but the APK/IPA files cannot be uploaded until it exists."
        )
    return value


def main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--release-json", required=True)
    parser.add_argument("--workspace", default="", help="directory for temporary downloads")
    parser.add_argument("--ledger", default="", help="post ledger holding per-asset state")
    parser.add_argument("--summary", default="", help="write a JSON run summary here")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--no-verify-only", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    try:
        import telegram_announce as announce  # noqa: PLC0415 - reuse load_release

        release = announce.load_release(args.release_json)
    except Exception as exc:  # noqa: BLE001
        error(f"Cannot read the release: {exc}")
        return EXIT_ERROR

    if release.draft or release.prerelease:
        notice(f"{release.tag_name} is a draft or pre-release; no binaries are uploaded.")
        return EXIT_OK

    workspace = args.workspace or os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), "streambridge-assets")

    if args.dry_run:
        run = upload_release_assets(release, transport=None, workspace=workspace, ledger_path=args.ledger, dry_run=True)
        for plan in run.plans:
            log(
                f"[dry run] {plan.kind}: {plan.asset.name} "
                f"({notes.human_size(plan.asset.size)}) -> {plan.asset.url}"
            )
        for reason in run.skipped:
            log(f"[dry run] not uploaded: {reason}")
        write_summary(args.summary, run)
        return EXIT_OK

    try:
        # The MTProto credentials are checked first: they are the ones that are
        # specific to large-file delivery, so a missing value is reported as
        # exactly that instead of as a generic Telegram configuration problem.
        api_id_text = require_env("TELEGRAM_API_ID")
        api_hash = require_env("TELEGRAM_API_HASH")
        token = announce.require_env("TELEGRAM_BOT_TOKEN")
        chat_id = announce.require_env("TELEGRAM_CHAT_ID")
    except ConfigError as exc:
        error(str(exc))
        return EXIT_CONFIG
    announce.mask_secret(token)
    announce.mask_secret(api_hash)

    try:
        api_id = int(api_id_text)
    except ValueError:
        error("TELEGRAM_API_ID must be the numeric API id from my.telegram.org -> API development tools.")
        return EXIT_CONFIG

    try:
        uploader = MtprotoUploader(api_id, api_hash, token, chat_id)
    except ConfigError as exc:
        error(str(exc))
        return EXIT_CONFIG

    run = UploadRun(release=release)
    try:
        run = upload_release_assets(
            release,
            transport=uploader,
            workspace=workspace,
            ledger_path=args.ledger,
            chat_id=chat_id,
        )
    finally:
        uploader.close()

    write_summary(args.summary, run)
    if run.failed:
        error(
            f"{len(run.failed)} of {len(run.plans)} binaries were not delivered. The GitHub release "
            "is unaffected; re-run this workflow to retry only the missing files."
        )
        return EXIT_ERROR
    notice(
        f"Delivered {len(run.uploaded)} of {len(run.plans)} StreamBridge binaries for "
        f"{release.version} to {chat_id}."
    )
    return EXIT_OK


__all__ = [
    "AssetError",
    "CAPTION_LIMIT",
    "ConfigError",
    "MtprotoUploader",
    "UPLOAD_MAX_BYTES",
    "UploadPlan",
    "UploadRecord",
    "UploadRun",
    "download_asset",
    "expected_digest",
    "load_checksums",
    "main",
    "parse_checksums",
    "plan_uploads",
    "record_upload",
    "recorded_uploads",
    "sha256_file",
    "upload_release_assets",
    "write_summary",
]

if __name__ == "__main__":
    sys.exit(main())
