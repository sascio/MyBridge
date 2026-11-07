#!/usr/bin/env python3
"""Plan and verify StreamBridge APK/IPA downloads.

Live unledgered uploads and bot history searches are retired. The production
path is scripts/telegram_publish.py, which uses telegram_mtproto.py and the
remote write-ahead ledger. This module remains responsible for asset selection,
checksums, bounded downloads and read-only previews.
"""

from __future__ import annotations

import hashlib
import html
import json
import os
import re
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
MAX_CHECKSUM_BYTES = 2 * 1024 * 1024
DOWNLOAD_TIMEOUT = 120
DOWNLOAD_ATTEMPTS = 3
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


def redact(message: str) -> str:
    # Share the announcement client's secret handling without creating a
    # module-import cycle: telegram_announce loads this module lazily too.
    from telegram_announce import redact as redact_telegram  # noqa: PLC0415

    return redact_telegram(message)


def log(message: str) -> None:
    print(redact(message), flush=True)


def notice(message: str) -> None:
    print(redact(f"::notice::{message}"), flush=True)


def warning(message: str) -> None:
    print(redact(f"::warning::{message}"), flush=True)


def error(message: str) -> None:
    print(redact(f"::error::{message}"), flush=True)


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

    def record(name: str, digest: str) -> None:
        name, digest = os.path.basename(name), digest.lower()
        if name in digests and digests[name] != digest:
            raise AssetError("The checksum manifest contains conflicting entries for an asset.")
        digests[name] = digest

    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.match(r"^SHA256 \((.+)\) = ([0-9a-fA-F]{64})$", line)
        if match:
            record(match.group(1).strip(), match.group(2))
            continue
        parts = line.split()
        if len(parts) >= 2 and _SHA256_RE.match(parts[0].lower()):
            record(parts[-1].lstrip("*"), parts[0])
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
        raw = response.read(MAX_CHECKSUM_BYTES + 1)
    if len(raw) > MAX_CHECKSUM_BYTES:
        raise AssetError("The published checksum manifest exceeds the size limit.")
    return raw.decode("utf-8", "replace")


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
        raise AssetError("The published checksum manifest could not be read; refusing a size-only fallback.") from exc


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
            return destination, actual
        except (AssetError, OSError, urllib.error.URLError) as exc:
            # Network exceptions can carry signed URLs or authorization details.
            last_error = str(exc) if isinstance(exc, AssetError) else f"network or I/O error ({type(exc).__name__})"
            _discard(partial)
            if attempt < attempts:
                delay = BACKOFF_BASE * attempt
                warning(f"Download attempt {attempt}/{attempts} for {asset.name} failed ({last_error}); retrying in {delay:.0f}s.")
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
    """Retired compatibility shim: high-level sends cannot preserve random_id."""

    def __init__(self, api_id: int, api_hash: str, bot_token: str, chat_id: str, **_kwargs: object) -> None:
        if api_id <= 0 or len(api_hash) < 16 or not bot_token or ":" not in bot_token:
            raise ConfigError("MTProto credentials are invalid.")

    def __repr__(self) -> str:
        return "MtprotoUploader(credentials=<redacted>, disabled=True)"

    def upload_document(self, path: str, filename: str, caption: str) -> int:
        raise ConfigError("Legacy direct uploads are disabled; use the durable MTProto workflow.")

    def find_marker(self, marker: str, limit: int = 100) -> bool:
        raise ConfigError("Channel history is not a bot-supported duplicate guard.")

    def close(self) -> None:
        pass

    def __enter__(self) -> "MtprotoUploader":
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()


# --------------------------------------------------------------------------- #
# Per-asset state, stored inside the announcement's post ledger
# --------------------------------------------------------------------------- #


def load_ledger(path: str) -> dict:
    if not path or not os.path.exists(path):
        raise AssetError("The legacy ledger is missing; refusing to assume an empty upload history.")
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        raise AssetError(f"post ledger {path} is unreadable: {exc}") from exc
    if (not isinstance(data, dict) or type(data.get("schema")) is not int
            or data.get("schema") not in (1, 2) or not isinstance(data.get("releases"), list)):
        raise AssetError("The ledger is invalid or is not a legacy schema; durable state must use the ledger implementation.")
    return data


def _release_entry(data: dict, release_id: int) -> dict | None:
    for entry in data.get("releases") or []:
        if isinstance(entry, dict) and int(entry.get("release_id", 0) or 0) == int(release_id):
            return entry
    return None


def recorded_uploads(path: str, release_id: int) -> dict[str, dict]:
    """Assets already confirmed by Telegram for this release, keyed by name."""
    data = load_ledger(path)
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
    """Preview legacy asset selection only; direct unledgered sends are disabled."""
    if not dry_run:
        raise ConfigError("The legacy uploader has no durable write-ahead ledger; use the MTProto workflow.")
    run = UploadRun(release=release, dry_run=True)
    run.plans, run.skipped = plan_uploads(release)
    for plan in run.plans:
        run.records.append(UploadRecord(plan.asset.name, plan.kind, plan.asset.size, "skipped", None,
                                        plan.asset.url, "dry run"))
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
            "Settings -> Secrets and variables -> Actions; no files can be uploaded until it exists."
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

    error("Direct uploads are disabled because they cannot preserve a durable idempotency key. "
          "Use the serialized GitHub Actions MTProto delivery workflow.")
    return EXIT_CONFIG


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
    "recorded_uploads",
    "sha256_file",
    "upload_release_assets",
    "write_summary",
]

if __name__ == "__main__":
    sys.exit(main())
