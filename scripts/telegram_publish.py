#!/usr/bin/env python3
"""Publish a GitHub release using one bot and a durable MTProto write-ahead log.

Only GitHub Actions runs of the dedicated, channel-wide serialized workflow may
invoke the live CLI. Legacy releases, unknown state, altered payloads and an
unrevoked prior session fail closed. See docs/TELEGRAM-ANNOUNCEMENTS.md.
"""
from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import hmac
import logging
import os
import re
import secrets
import sys
import tempfile
import time
from typing import Any, Callable

import telegram_announce as announce
import telegram_assets as assets
import telegram_ledger as ledger
import telegram_mtproto as mtproto
import telegram_release_notes as notes

MAX_SEND_ATTEMPTS_PER_RUN = 3
MAX_SEND_ATTEMPTS_TOTAL = 10
MAX_FLOOD_SECONDS = 60

# Third-party exception/debug logs may contain request or authorization material.
# Controlled user-facing diagnostics below never print those objects.
logging.disable(logging.CRITICAL)


class PublishError(RuntimeError):
    pass


def require_secret(name: str) -> str:
    value = os.environ.get(name, "")
    if not value or value != value.strip() or any(c.isspace() for c in value):
        raise PublishError(f"{name} is missing or malformed.")
    return value


def bot_details(token: str, chat: str) -> tuple[int, int, str]:
    """Read-only Bot API checks; do not log responses or credentials."""
    client = announce.TelegramClient(token, chat)
    me, target = client.call("getMe"), client.get_chat()
    bot, channel = me.result or {}, target.result or {}
    bot_id, channel_id = bot.get("id"), channel.get("id")
    if not me.ok or bot.get("is_bot") is not True or type(bot_id) is not int or bot_id <= 0:
        raise PublishError("Bot API authentication could not be verified.")
    if not target.ok or channel.get("type") != "channel" or type(channel_id) is not int or channel_id >= 0:
        raise PublishError("Bot API channel access could not be verified.")
    member_result = client.call("getChatMember", {"chat_id": chat, "user_id": bot_id})
    member = member_result.result or {}
    if not member_result.ok or member.get("status") != "administrator" or member.get("can_post_messages") is not True:
        raise PublishError("Bot channel administrator/Post Messages rights could not be verified.")
    username = channel.get("username")
    if isinstance(username, str) and re.fullmatch(r"[A-Za-z0-9_]{5,}", username):
        channel_ref = "@" + username
    elif re.fullmatch(r"@[A-Za-z0-9_]{5,}", chat):
        channel_ref = chat
    else:
        # A fresh in-memory session cannot reliably resolve a bare numeric ID
        # without an access hash. Never guess a different destination.
        raise PublishError("A resolvable public channel username is required for a fresh bot session.")
    return bot_id, channel_id, channel_ref


def identity_binding(token: str, bot_id: int, channel_id: int) -> str:
    return hmac.new(token.encode(), f"telegram-bot-channel-v1:{bot_id}:{channel_id}".encode(), hashlib.sha256).hexdigest()


def checksum_manifest(release: notes.Release, plans: list[assets.UploadPlan]) -> dict[str, str]:
    summary = notes.classify_assets(release.assets)
    if summary.checksums is None:
        return {}
    if summary.checksums.size <= 0 or summary.checksums.size > assets.MAX_CHECKSUM_BYTES:
        raise PublishError("The published checksum manifest has an invalid or unsupported size.")
    try:
        checksums = assets.parse_checksums(assets._fetch_text(summary.checksums.url))
    except Exception as exc:
        raise PublishError("A published checksum manifest could not be read; no size-only fallback is allowed.") from exc
    for plan in plans:
        manifest = checksums.get(plan.asset.name)
        api_digest = assets.expected_digest(plan.asset, {})
        if not manifest and not api_digest:
            raise PublishError("A selected release asset has no checksum in the published manifest.")
        if manifest and api_digest and manifest != api_digest:
            raise PublishError("The release API digest and checksum manifest disagree.")
    return checksums


def wire_text(markup: str, buttons: list[list[str]]) -> dict[str, Any]:
    """Freeze the actual parsed MTProto text/entities, not just source HTML."""
    text, entities = mtproto.BotTransport.parse_html(markup)
    keyboard = mtproto.BotTransport.keyboard([tuple(button) for button in buttons])
    return {"text": text, "entities": [entity.to_dict() for entity in entities],
            "reply_markup": keyboard.to_dict() if keyboard else None}


def operation_plan(release: notes.Release, announcement: notes.Announcement, pages: list[str],
                   plans: list[assets.UploadPlan], checksums: dict[str, str]) -> list[dict[str, Any]]:
    operations: list[dict[str, Any]] = []
    for index, page in enumerate(pages):
        buttons = [list(button) for button in announcement.pages[index].buttons]
        payload = {"kind": "page", "html": page, "buttons": buttons, "no_webpage": True,
                   "wire": wire_text(page, buttons)}
        operations.append({"key": f"page:{index + 1}", "kind": "page", "payload": ledger.digest(payload),
                           "html": page, "buttons": buttons})
    ids: set[int] = set()
    names: set[str] = set()
    for plan in plans:
        asset = plan.asset
        if type(asset.id) is not int or asset.id <= 0 or asset.id in ids or asset.name in names:
            raise PublishError("Every selected asset must have a unique GitHub asset id and name.")
        if os.path.basename(asset.name) != asset.name or asset.name in {".", ".."} or any(c in asset.name for c in "\r\n"):
            raise PublishError("A release asset has an unsafe filename.")
        if asset.size <= 0 or asset.size > assets.UPLOAD_MAX_BYTES:
            raise PublishError("A selected release asset has an unsupported size.")
        ids.add(asset.id)
        names.add(asset.name)
        if asset.digest and assets.expected_digest(asset, {}) is None:
            raise PublishError("A selected asset has an unsupported or malformed API digest.")
        expected = assets.expected_digest(asset, checksums)
        payload = {"kind": "asset", "asset_id": asset.id, "name": asset.name, "size": asset.size,
                   "url": asset.url, "expected_sha256": expected, "caption": plan.caption,
                   "wire": wire_text(plan.caption, []),
                   "mime_type": mtproto.BotTransport.mime_type(asset.name), "force_file": True}
        operations.append({"key": f"asset:{asset.id}", "kind": "asset", "payload": ledger.digest(payload),
                           "upload": plan, "expected_sha256": expected})
    return operations


def random_id(used: set[int]) -> int:
    while True:
        value = int.from_bytes(secrets.token_bytes(8), "big", signed=True)
        if value and value not in used:
            used.add(value)
            return value


def stored_release(document: dict[str, Any], release_id: int) -> dict[str, Any] | None:
    return next((entry for entry in document["releases"] if entry["id"] == release_id), None)


def make_release(release: notes.Release, operations: list[dict[str, Any]], used: set[int]) -> dict[str, Any]:
    frozen = [{"key": op["key"], "kind": op["kind"], "payload": op["payload"]} for op in operations]
    return {"id": release.id, "tag": release.tag_name, "created_at": release.created_at,
            "plan": ledger.digest(frozen),
            "items": [{**op, "random_id": random_id(used), "state": "planned", "message_id": None,
                       "sha256": None, "attempts": 0} for op in frozen]}


def same_plan(stored: dict[str, Any], release: notes.Release, operations: list[dict[str, Any]]) -> bool:
    frozen = [{"key": op["key"], "kind": op["kind"], "payload": op["payload"]} for op in operations]
    return (stored["tag"] == release.tag_name and stored["created_at"] == release.created_at and
            stored["plan"] == ledger.digest(frozen) and
            [{key: item[key] for key in ("key", "kind", "payload")} for item in stored["items"]] == frozen)


def item_by_key(entry: dict[str, Any], key: str) -> dict[str, Any]:
    matches = [item for item in entry["items"] if item["key"] == key]
    if len(matches) != 1:
        raise PublishError("The durable operation is missing or duplicated.")
    return matches[0]


def transition(store: Any, owner: str, release_id: int, plan_hash: str, key: str,
               expected_states: set[str], updates: dict[str, Any], *, binding: str | None = None) -> dict[str, Any]:
    snapshot = store.read()
    document = copy.deepcopy(snapshot.document)
    entry = stored_release(document, release_id)
    if document["session"] != {"owner": owner} or entry is None or entry["plan"] != plan_hash:
        raise PublishError("The durable session/plan changed; this worker is fenced off.")
    if binding is not None and document["identity"] != binding:
        raise PublishError("The durable bot/channel binding changed.")
    item = item_by_key(entry, key)
    if item["state"] not in expected_states:
        raise PublishError("The durable operation state changed concurrently.")
    item.update(updates)
    store.save(snapshot, document)
    return copy.deepcopy(item)


def retry_delay(exc: Exception, attempt: int) -> float | None:
    """Only explicit flood/transport ambiguity and duplicate-in-flight qualify."""
    from telethon import errors
    if isinstance(exc, (errors.FloodWaitError, errors.SlowModeWaitError)):
        seconds = max(1, int(exc.seconds))
        return float(seconds) if seconds <= MAX_FLOOD_SECONDS else None
    if isinstance(exc, (errors.RandomIdDuplicateError, errors.ServerError, errors.TimedOutError,
                        TimeoutError, ConnectionError, OSError, mtproto.UnconfirmedResponse)):
        return float(min(2 ** attempt, 10))
    return None


def deliver(store: Any, owner: str, release_id: int, plan_hash: str, item: dict[str, Any],
            binding: str, transport: Any, request: object, *, filename: str | None = None,
            size: int | None = None, sleeper: Callable[[float], None] = time.sleep) -> int:
    key, identifier = item["key"], item["random_id"]
    for attempt in range(1, MAX_SEND_ATTEMPTS_PER_RUN + 1):
        if item["attempts"] >= MAX_SEND_ATTEMPTS_TOTAL:
            raise PublishError("The lifetime send-attempt limit was reached; operator review is required.")
        item = transition(store, owner, release_id, plan_hash, key, {item["state"]},
                          {"state": "sending", "attempts": item["attempts"] + 1}, binding=binding)
        if item["random_id"] != identifier or getattr(request, "random_id", None) != identifier:
            raise PublishError("The persisted random_id changed; no send is allowed.")
        try:
            message_id = transport.send(request, filename=filename, size=size)
        except Exception as exc:  # deliberately do not print exception or request contents
            item = transition(store, owner, release_id, plan_hash, key, {"sending"},
                              {"state": "unknown"}, binding=binding)
            delay = retry_delay(exc, attempt)
            if delay is None or attempt == MAX_SEND_ATTEMPTS_PER_RUN:
                raise PublishError("A send outcome is unresolved; its original ID is retained for audited recovery.") from exc
            announce.warning(f"Send outcome uncertain; rechecking durable state before same-ID replay ({attempt}/{MAX_SEND_ATTEMPTS_PER_RUN}).")
            sleeper(delay)
            continue
        if type(message_id) is not int or message_id <= 0:
            raise PublishError("Telegram returned an invalid message id; the operation remains unconfirmed.")
        transition(store, owner, release_id, plan_hash, key, {"sending"},
                   {"state": "confirmed", "message_id": message_id}, binding=binding)
        return message_id
    raise PublishError("The send retry budget was exhausted.")


def publish(release: notes.Release, store: Any, *, token: str, chat: str, api_id: int, api_hash: str,
            upload_assets: bool = True, recover_unknown: bool = False,
            transport_factory: Callable = mtproto.BotTransport, bot_lookup: Callable = bot_details,
            sleeper: Callable[[float], None] = time.sleep) -> None:
    if release.draft or release.prerelease:
        announce.notice("Draft and pre-release announcements are disabled; nothing was published.")
        return
    if type(release.id) is not int or release.id <= 0 or not release.tag_name:
        raise PublishError("The GitHub release identity is invalid.")
    created = ledger.timestamp(release.created_at)
    published = ledger.timestamp(release.published_at)
    now = dt.datetime.now(dt.timezone.utc)
    if created > published or published > now + dt.timedelta(minutes=5):
        raise PublishError("The GitHub release timestamps are inconsistent.")
    announcement, pages = announce.build_pages(release)
    plans, _skipped = assets.plan_uploads(release)
    checksums = checksum_manifest(release, plans) if plans else {}
    operations = operation_plan(release, announcement, pages, plans, checksums)

    snapshot = store.read()
    document = copy.deepcopy(snapshot.document)
    cutoff = ledger.timestamp(document["activation"]["created_after"])
    if created <= cutoff or published <= cutoff or now <= cutoff:
        raise PublishError("The release predates the audited activation cutoff or activation is not yet effective.")
    if document["session"] is not None:
        raise PublishError("A previous bot session is not confirmed revoked; audited operator recovery is required.")
    entry = stored_release(document, release.id)
    if entry is None:
        legacy_entries = [e for e in document["legacy"]["releases"] if isinstance(e, dict)]
        if (any(e.get("tag_name") == release.tag_name or e.get("release_id") == release.id for e in legacy_entries)
                or any(e["tag"] == release.tag_name for e in document["releases"])):
            raise PublishError("The release or tag already appears in the ledger; automatic reposting is blocked.")
        used = {i["random_id"] for e in document["releases"] for i in e["items"]}
        entry = make_release(release, operations, used)
        document["releases"].append(entry)
    elif not same_plan(entry, release, operations):
        raise PublishError("Release content or asset plan changed after it was frozen; manual review is required.")
    relevant = [i for i in entry["items"] if upload_assets or i["kind"] == "page"]
    if all(i["state"] == "confirmed" for i in relevant):
        announce.notice("All requested release operations are already confirmed in the remote ledger.")
        return
    if not recover_unknown and any(i["state"] in {"sending", "unknown"} for i in relevant):
        raise PublishError("An earlier send is unresolved; explicit same-ID recovery is required.")
    owner = "run-" + re.sub(r"[^0-9]", "", os.environ.get("GITHUB_RUN_ID", "0"))[:20] + "-" + secrets.token_hex(12)
    document["session"] = {"owner": owner}
    snapshot = store.save(snapshot, document)  # IDs and lock must be durable before login
    plan_hash = entry["plan"]
    transport = None
    failure: Exception | None = None
    try:
        bot_id, channel_id, channel_ref = bot_lookup(token, chat)
        binding = identity_binding(token, bot_id, channel_id)
        # Bind the account/peer before any public send; changed credentials fail closed.
        current = store.read()
        bound = copy.deepcopy(current.document)
        if bound["session"] != {"owner": owner} or bound["identity"] not in (None, binding):
            raise PublishError("The bot/channel identity differs from the durable ledger or the session lock changed.")
        if bound["identity"] is None:
            bound["identity"] = binding
            store.save(current, bound)
        transport = transport_factory(api_id, api_hash, token, channel_ref, bot_id, channel_id)
        transport.connect()
        for op in operations:
            if op["kind"] == "asset" and not upload_assets:
                continue
            current = store.read()
            current_entry = stored_release(current.document, release.id)
            if current.document["session"] != {"owner": owner} or current.document["identity"] != binding or current_entry is None or not same_plan(current_entry, release, operations):
                raise PublishError("The authoritative release plan or session changed.")
            item = copy.deepcopy(item_by_key(current_entry, op["key"]))
            if item["state"] == "confirmed":
                continue
            if item["state"] in {"sending", "unknown"} and not recover_unknown:
                raise PublishError("An earlier send remains unresolved; explicit recovery is required.")
            if op["kind"] == "page":
                request = transport.text_request(op["html"], [tuple(b) for b in op["buttons"]], item["random_id"])
                message_id = deliver(store, owner, release.id, plan_hash, item, binding, transport, request, sleeper=sleeper)
            else:
                plan = op["upload"]
                with tempfile.TemporaryDirectory(prefix="telegram-release-asset-") as tmp:
                    path = os.path.join(tmp, plan.filename)
                    # The downloader verifies byte count and published digests when present.
                    path, actual_sha = assets.download_asset(plan.asset, path, checksums=checksums, sleeper=sleeper)
                    if actual_sha is None or (item["sha256"] is not None and item["sha256"] != actual_sha):
                        raise PublishError("Downloaded asset differs from its frozen SHA-256.")
                    if item["state"] in {"planned", "uploading"}:
                        item = transition(store, owner, release.id, plan_hash, op["key"], {item["state"]},
                                          {"state": "uploading", "sha256": actual_sha}, binding=binding)
                    # Parts are temporary and not public messages; a failed upload
                    # can be restarted, but never changes the final send ID.
                    media = transport.prepare_document(path, plan.filename)
                    request = transport.media_request(media, plan.caption, item["random_id"])
                    message_id = deliver(store, owner, release.id, plan_hash, item, binding, transport, request,
                                         filename=plan.filename, size=plan.asset.size, sleeper=sleeper)
            announce.log(f"Confirmed {op['key']} as Telegram message {message_id}; durable ledger updated.")
            if op["kind"] == "page" and op is not operations[-1]:
                sleeper(announce.PAGE_INTERVAL)
    except Exception as exc:
        failure = exc
    finally:
        revoked = True if transport is None else transport.close()
        if revoked:
            try:
                current = store.read()
                released = copy.deepcopy(current.document)
                if released["session"] != {"owner": owner}:
                    raise PublishError("The bot session lock changed before cleanup.")
                released["session"] = None
                store.save(current, released)
            except Exception as exc:
                failure = PublishError("The bot session was closed, but its durable lock could not be released; operator review is required.")
                failure.__cause__ = exc
        else:
            failure = PublishError("Bot session revocation was not confirmed; the durable lock remains and recovery is required.")
    if failure is not None:
        raise failure


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--release-json", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--no-upload-assets", action="store_true")
    parser.add_argument("--recover-unknown", action="store_true")
    args = parser.parse_args(argv)
    try:
        if os.environ.get("GITHUB_ACTIONS") != "true" or not os.environ.get("GITHUB_RUN_ID"):
            raise PublishError("Live publishing is restricted to the serialized GitHub Actions workflow.")
        token, chat = require_secret("TELEGRAM_BOT_TOKEN"), require_secret("TELEGRAM_CHAT_ID")
        api_id_text, api_hash = require_secret("TELEGRAM_API_ID"), require_secret("TELEGRAM_API_HASH")
        if not api_id_text.isdigit() or int(api_id_text) <= 0 or not re.fullmatch(r"[0-9a-fA-F]{32}", api_hash):
            raise PublishError("MTProto API credentials are malformed.")
        release = announce.load_release(args.release_json)
        store = ledger.GitLedger(os.getcwd(), args.branch)
        publish(release, store, token=token, chat=chat, api_id=int(api_id_text), api_hash=api_hash,
                upload_assets=not args.no_upload_assets, recover_unknown=args.recover_unknown)
    except Exception as exc:  # no raw exception strings, tracebacks or credential material
        if isinstance(exc, (PublishError, ledger.LedgerError, announce.AnnouncementError)):
            announce.error(str(exc))
        else:
            announce.error("Telegram delivery failed safely; inspect the remote ledger and recovery runbook.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
