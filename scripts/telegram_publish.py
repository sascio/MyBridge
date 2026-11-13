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
import json
import logging
import os
import re
import secrets
import subprocess
import sys
import tempfile
import time
from typing import Any, Callable

import telegram_announce as announce
import telegram_assets as assets
import telegram_identity as identity
import telegram_ledger as ledger
import telegram_state as state
import telegram_mtproto as mtproto
import telegram_release_notes as notes

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


def require_workflow_context(branch: str) -> tuple[str, int, str]:
    """Require the designated workflow, activation and a bound release/recovery event."""
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    workflow_path = os.environ.get("GITHUB_WORKFLOW_REF", "").split("@", 1)[0]
    if (os.environ.get("GITHUB_ACTIONS") != "true" or not os.environ.get("GITHUB_RUN_ID")
            or not os.environ.get("GH_TOKEN") or event_name not in {"release", "workflow_dispatch"}
            or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo)
            or workflow_path != f"{repo}/.github/workflows/telegram-release-delivery.yml"):
        raise PublishError("Live publishing is restricted to the serialized GitHub Actions workflow.")
    if os.environ.get("TELEGRAM_PUBLISHING_ENABLED") != "true":
        raise PublishError("Live publishing has not been explicitly activated.")
    try:
        with open(os.environ.get("GITHUB_EVENT_PATH", ""), encoding="utf-8") as handle:
            event = json.load(handle)
        repository = event["repository"]
        if not isinstance(repository, dict) or repository.get("full_name") != repo or repository.get("default_branch") != branch:
            raise ValueError("mismatched repository")
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise PublishError("The Actions event does not identify the authoritative default branch.") from exc
    if event_name == "workflow_dispatch":
        if os.environ.get("GITHUB_REF") != f"refs/heads/{branch}":
            raise PublishError("Live manual delivery must use the reviewed default-branch workflow ref.")
        inputs = event.get("inputs")
        if not isinstance(inputs, dict):
            raise PublishError("Live manual dispatch requires explicit recovery and a release tag.")
        preflight, dry_run, resume = (inputs.get("preflight"), inputs.get("dry_run"), inputs.get("resume_unsent"))
        rid = inputs.get("release_id")
        if (not (preflight is None or preflight is False or preflight == "false")
                or not (dry_run is False or dry_run == "false")
                or not (resume is True or resume == "true")
                or not isinstance(rid, str) or not re.fullmatch(r"[1-9][0-9]*", rid)):
            raise PublishError("Live manual dispatch requires an exact release ID and explicit unsent-operation recovery.")
        try:
            tag = identity.validate_tag(inputs.get("tag"))
        except identity.IdentityError as exc:
            raise PublishError("Live manual dispatch requires an exact release tag.") from exc
        return repo, int(rid), tag
    release = event.get("release")
    if (event.get("action") != "published" or not isinstance(release, dict)
            or type(release.get("id")) is not int or release["id"] <= 0):
        raise PublishError("Initial delivery requires a published-release event with a valid release ID.")
    try:
        tag = identity.validate_tag(release.get("tag_name"))
    except identity.IdentityError as exc:
        raise PublishError("The published-release event has no exact release tag.") from exc
    return repo, release["id"], tag


def tooling_commit() -> str:
    """Record the exact source checkout that performed the delivery."""
    try:
        result = subprocess.run(["git", "rev-parse", "--verify", "HEAD^{commit}"], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=30, check=False)
        sha = result.stdout.decode("ascii").strip()
    except (OSError, UnicodeDecodeError, subprocess.TimeoutExpired) as exc:
        raise PublishError("The delivery tooling commit could not be verified.") from exc
    if result.returncode or not ledger.COMMIT_SHA.fullmatch(sha):
        raise PublishError("The delivery tooling commit could not be verified.")
    return sha


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
    if channel_ref.casefold() != "@streambridge_app":
        raise PublishError("The configured channel is not the reviewed @StreamBridge_App destination.")
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


def has_unresolved_items(items: list[dict[str, Any]]) -> bool:
    """Whether this release contains a write-ahead or ambiguous Telegram send."""
    return any(item["state"] in {"sending", "delivery_unknown"} for item in items)


def require_no_unresolved_items(items: list[dict[str, Any]]) -> None:
    """Never re-send an unresolved operation within the release being delivered."""
    if has_unresolved_items(items):
        raise PublishError("An earlier send in this release is unresolved; no resend is allowed until independently reconciled.")


def make_release(release: notes.Release, operations: list[dict[str, Any]], used: set[int],
                 source_sha: str) -> dict[str, Any]:
    frozen = [{"key": op["key"], "kind": op["kind"], "payload": op["payload"]} for op in operations]
    return {"id": release.id, "tag": release.tag_name, "source_sha": source_sha,
            "created_at": release.created_at, "published_at": release.published_at, "status": "pending",
            "plan": ledger.digest(frozen),
            "items": [{**op, "random_id": random_id(used), "state": "pending", "message_id": None,
                       "sha256": None, "attempts": 0, "attempted_at": None, "delivered_at": None,
                       "workflow_run_id": None, "run_attempt": None, "tooling_sha": None,
                       "reconciliation": None} for op in frozen]}


def same_plan(stored: dict[str, Any], release: notes.Release, operations: list[dict[str, Any]],
              source_sha: str) -> bool:
    frozen = [{"key": op["key"], "kind": op["kind"], "payload": op["payload"]} for op in operations]
    return (stored["tag"] == release.tag_name and stored["source_sha"] == source_sha
            and stored["created_at"] == release.created_at and stored["published_at"] == release.published_at
            and stored["plan"] == ledger.digest(frozen)
            and [{key: item[key] for key in ("key", "kind", "payload")} for item in stored["items"]] == frozen)


def item_by_key(entry: dict[str, Any], key: str) -> dict[str, Any]:
    matches = [item for item in entry["items"] if item["key"] == key]
    if len(matches) != 1:
        raise PublishError("The durable operation is missing or duplicated.")
    return matches[0]


def transition(store: Any, owner: str, release_id: int, plan_hash: str, key: str,
               expected_states: set[str], updates: dict[str, Any], *, binding: str | None = None) -> dict[str, Any]:
    baseline: dict[str, Any] | None = None
    def apply(document: dict[str, Any]) -> dict[str, Any]:
        nonlocal baseline
        entry = stored_release(document, release_id)
        if document["session"] != {"owner": owner} or entry is None or entry["plan"] != plan_hash:
            raise PublishError("The durable session/plan changed; this worker is fenced off.")
        if binding is not None and document["identity"] != binding:
            raise PublishError("The durable bot/channel binding changed.")
        item = item_by_key(entry, key)
        if item["state"] not in expected_states:
            raise PublishError("The durable operation state changed concurrently.")
        if baseline is None:
            baseline = copy.deepcopy(item)
        elif item != baseline:
            raise PublishError("The durable operation changed during conflict recovery.")
        item.update(updates)
        entry["status"] = ledger.release_status(entry["items"])
        return copy.deepcopy(item)
    _, changed = ledger.update(store, apply)
    return changed


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def deliver(store: Any, owner: str, release_id: int, plan_hash: str, item: dict[str, Any],
            binding: str, transport: Any, request: object, *, workflow_run_id: int, run_attempt: int,
            tooling_sha: str, filename: str | None = None, size: int | None = None,
            policy_check: Callable[[], None]) -> int:
    key, identifier = item["key"], item["random_id"]
    if item["state"] not in {"pending", "uploading"} or item["attempts"] != 0:
        raise PublishError("A previously attempted or unresolved send cannot be retried.")
    policy_check()
    item = transition(store, owner, release_id, plan_hash, key, {item["state"]},
                      {"state": "sending", "attempts": 1, "attempted_at": utc_now(),
                       "workflow_run_id": workflow_run_id, "run_attempt": run_attempt,
                       "tooling_sha": tooling_sha}, binding=binding)
    if item["random_id"] != identifier or getattr(request, "random_id", None) != identifier:
        raise PublishError("The persisted operation identifier changed; no send is allowed.")
    # If policy changed after write-ahead, sending remains conservatively
    # unresolved even though this worker does not issue a public request.
    policy_check()
    try:
        message_id = transport.send(request, filename=filename, size=size)
        if type(message_id) is not int or message_id <= 0:
            raise mtproto.UnconfirmedResponse("Telegram returned no valid message identifier.")
    except Exception as exc:  # never print Telegram errors, payloads, or credentials
        transition(store, owner, release_id, plan_hash, key, {"sending"},
                   {"state": "delivery_unknown", "reconciliation": {
                       "status": "review_required", "reason": "unconfirmed_telegram_response"}}, binding=binding)
        raise PublishError("A Telegram outcome is unresolved; automatic and manual resend are blocked until reconciliation.") from exc
    # This marker is emitted only after the transport verifies the response.
    # If the final state write fails, it gives an operator a specific run and
    # message ID to investigate; it does not itself change the ledger to sent.
    confirmed_at = utc_now()
    announce.log(f"TELEGRAM_CONFIRMED_RESPONSE run_id={workflow_run_id} run_attempt={run_attempt} "
                 f"release_id={release_id} operation={key} operation_id={identifier} "
                 f"message_id={message_id} confirmed_at={confirmed_at} tooling_sha={tooling_sha}")
    try:
        transition(store, owner, release_id, plan_hash, key, {"sending"},
                   {"state": "sent", "message_id": message_id, "delivered_at": confirmed_at}, binding=binding)
    except Exception:
        announce.error("A confirmed response was not durably recorded; do not resend. Reconcile the run marker with the ledger.")
        raise
    return message_id

def publish(release: notes.Release, store: Any, *, token: str, chat: str, api_id: int, api_hash: str,
            source_sha: str, workflow_run_id: int, run_attempt: int, tooling_sha: str,
            upload_assets: bool = True, resume_unsent: bool = False,
            transport_factory: Callable = mtproto.BotTransport, bot_lookup: Callable = bot_details,
            sleeper: Callable[[float], None] = time.sleep,
            policy_check: Callable[[], None], allow_new_release: bool,
            require_protected_inventory: bool = False) -> None:
    if release.draft or release.prerelease:
        announce.notice("Draft and pre-release announcements are disabled; nothing was published.")
        return
    policy_check()
    if (type(release.id) is not int or release.id <= 0 or not isinstance(source_sha, str)
            or not ledger.COMMIT_SHA.fullmatch(source_sha) or type(workflow_run_id) is not int
            or workflow_run_id <= 0 or type(run_attempt) is not int or run_attempt <= 0
            or not isinstance(tooling_sha, str) or not ledger.COMMIT_SHA.fullmatch(tooling_sha)):
        raise PublishError("The release, source commit or workflow attempt identity is invalid.")
    try:
        identity.validate_tag(release.tag_name)
    except identity.IdentityError as exc:
        raise PublishError("The release tag is not an exact tag.") from exc
    created = ledger.timestamp(release.created_at)
    published = ledger.timestamp(release.published_at)
    now = dt.datetime.now(dt.timezone.utc)
    if created > published or published > now + dt.timedelta(minutes=5):
        raise PublishError("The GitHub release timestamps are inconsistent.")
    announcement, pages = announce.build_pages(release)
    plans, _skipped = assets.plan_uploads(release)
    checksums = checksum_manifest(release, plans) if plans else {}
    operations = operation_plan(release, announcement, pages, plans, checksums)
    owner = f"run-{workflow_run_id}-" + secrets.token_hex(12)

    def acquire(document: dict[str, Any]) -> dict[str, Any] | None:
        inventory = document["inventory"]
        ledger.require_inventory_key(inventory, token, required=require_protected_inventory)
        if require_protected_inventory:
            ledger.require_reviewed_history(inventory)
            if document["activation"].get("ready") is not True:
                raise PublishError("Automatic state bootstrap is not ready; no delivery is allowed.")
        if any(ledger.historically_seen(inventory, token, entry["id"], entry["tag"])
               for entry in document["releases"]):
            raise PublishError("The delivery ledger overlaps protected historical release identities.")
        cutoff = ledger.timestamp(document["activation"]["published_after"])
        if published <= cutoff or now <= cutoff:
            raise PublishError("The release predates the audited activation cutoff or activation is not yet effective.")
        if document["session"] is not None:
            raise PublishError("A previous bot session is not confirmed revoked; audited operator recovery is required.")
        # Keep session and active-verification barriers global, but isolate
        # delivery ambiguity to its own release. The check below still refuses
        # every unresolved operation in this exact release; new plans allocate
        # random IDs unique across the complete ledger.
        if any(record["status"] == "active" for record in document["verification"]):
            raise PublishError("State-only verification is unfinished; no delivery is allowed.")
        entry = stored_release(document, release.id)
        if entry is None:
            if not allow_new_release:
                raise PublishError("Manual recovery requires a release already planned by a published-release event.")
            if (ledger.historically_seen(inventory, token, release.id, release.tag_name)
                    or any(e["tag"] == release.tag_name for e in document["releases"])):
                raise PublishError("The release or tag appears in the historical inventory or ledger; automatic reposting is blocked.")
            used = {i["random_id"] for e in document["releases"] for i in e["items"]}
            entry = make_release(release, operations, used, source_sha)
            document["releases"].append(entry)
        elif not same_plan(entry, release, operations, source_sha):
            raise PublishError("Release identity, source commit or asset plan changed after it was frozen; manual review is required.")
        # An uncertain operation anywhere in the release blocks the whole plan,
        # even when a manual dispatch requests only text or only some assets.
        require_no_unresolved_items(entry["items"])
        relevant = [i for i in entry["items"] if upload_assets or i["kind"] == "page"]
        if all(i["state"] == "sent" for i in relevant):
            return None
        if not resume_unsent and any(i["state"] == "failed" for i in relevant):
            raise PublishError("A pre-send failure requires explicit, exact-identity manual recovery.")
        document["session"] = {"owner": owner}
        return copy.deepcopy(entry)

    _, entry = ledger.update(store, acquire)  # frozen IDs and lock must be durable before login
    if entry is None:
        announce.notice("All requested release operations are already confirmed in the remote ledger.")
        return
    plan_hash = entry["plan"]
    transport = None
    failure: Exception | None = None
    try:
        bot_id, channel_id, channel_ref = bot_lookup(token, chat)
        binding = identity_binding(token, bot_id, channel_id)
        def bind(document: dict[str, Any]) -> None:
            if document["session"] != {"owner": owner} or document["identity"] not in (None, binding):
                raise PublishError("The bot/channel identity differs from the durable ledger or the session lock changed.")
            if document["identity"] is None:
                document["identity"] = binding
        ledger.update(store, bind)
        transport = transport_factory(api_id, api_hash, token, channel_ref, bot_id, channel_id)
        transport.connect()
        asset_failures = 0
        for op in operations:
            if op["kind"] == "asset" and not upload_assets:
                continue
            current = store.read()
            current_entry = stored_release(current.document, release.id)
            if (current.document["session"] != {"owner": owner} or current.document["identity"] != binding
                    or current_entry is None or not same_plan(current_entry, release, operations, source_sha)):
                raise PublishError("The authoritative release plan or session changed.")
            item = copy.deepcopy(item_by_key(current_entry, op["key"]))
            if item["state"] == "sent":
                continue
            require_no_unresolved_items([item])
            if item["state"] == "failed" and not resume_unsent:
                raise PublishError("A pre-send failure requires explicit manual recovery.")
            if op["kind"] == "page":
                request = transport.text_request(op["html"], [tuple(b) for b in op["buttons"]], item["random_id"])
                message_id = deliver(store, owner, release.id, plan_hash, item, binding, transport, request,
                                     workflow_run_id=workflow_run_id, run_attempt=run_attempt, tooling_sha=tooling_sha, policy_check=policy_check)
            else:
                plan = op["upload"]
                with tempfile.TemporaryDirectory(prefix="telegram-release-asset-") as tmp:
                    path = os.path.join(tmp, plan.filename)
                    try:
                        path, actual_sha = assets.download_asset(plan.asset, path, checksums=checksums, sleeper=sleeper)
                    except assets.AssetError as exc:
                        transition(store, owner, release.id, plan_hash, op["key"], {item["state"]},
                                   {"state": "failed", "reconciliation": {"status": "unsent", "reason": "pre_send_failure"}},
                                   binding=binding)
                        asset_failures += 1
                        announce.warning("A document failed verification before public send; checking other documents independently.")
                        continue
                    if actual_sha is None or (item["sha256"] is not None and item["sha256"] != actual_sha):
                        raise PublishError("Downloaded asset differs from its frozen SHA-256.")
                    item = transition(store, owner, release.id, plan_hash, op["key"], {item["state"]},
                                      {"state": "uploading", "sha256": actual_sha, "reconciliation": None}, binding=binding)
                    # Uploaded parts are not channel messages. If preparation
                    # fails before SendMedia, record an explicitly unsent failure.
                    policy_check()
                    try:
                        media = transport.prepare_document(path, plan.filename)
                    except (ConnectionError, TimeoutError, OSError):
                        transition(store, owner, release.id, plan_hash, op["key"], {"uploading"},
                                   {"state": "failed", "reconciliation": {"status": "unsent", "reason": "pre_send_failure"}},
                                   binding=binding)
                        asset_failures += 1
                        announce.warning("A document's temporary parts failed before public send; checking other documents independently.")
                        continue
                    request = transport.media_request(media, plan.caption, item["random_id"])
                    message_id = deliver(store, owner, release.id, plan_hash, item, binding, transport, request,
                                         workflow_run_id=workflow_run_id, run_attempt=run_attempt, tooling_sha=tooling_sha,
                                         filename=plan.filename, size=plan.asset.size, policy_check=policy_check)
            announce.log(f"Confirmed {op['key']} as Telegram message {message_id}; durable ledger updated.")
            if op["kind"] == "page" and op is not operations[-1]:
                sleeper(announce.PAGE_INTERVAL)
        if asset_failures:
            raise PublishError("At least one document failed before public send; confirmed documents remain recorded for recovery.")
    except Exception as exc:
        failure = exc
    finally:
        revoked = True if transport is None else transport.close()
        if revoked:
            try:
                def unlock(document: dict[str, Any]) -> None:
                    if document["session"] != {"owner": owner}:
                        raise PublishError("The bot session lock changed before cleanup.")
                    document["session"] = None
                ledger.update(store, unlock)
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
    parser.add_argument("--branch", required=True, help="reviewed source repository default branch")
    parser.add_argument("--state-dir", required=True, help="checkout of the reviewed same-repository state branch")
    parser.add_argument("--no-upload-assets", action="store_true")
    parser.add_argument("--resume-unsent", action="store_true")
    args = parser.parse_args(argv)
    try:
        repository, event_release_id, event_tag = require_workflow_context(args.branch)
        automatic = ledger.publishing_ledger_mode()
        not_before = ledger.publishing_not_before()
        if not automatic and (not_before is None or dt.datetime.now(dt.timezone.utc) <= not_before):
            raise PublishError("The reviewed publishing configuration is disabled, invalid or not yet effective.")
        is_manual = os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
        if is_manual and not args.resume_unsent:
            raise PublishError("Live manual dispatch is limited to explicitly reviewed unsent operations.")
        config = state.load_config(repository)
        token, chat = require_secret("TELEGRAM_BOT_TOKEN"), require_secret("TELEGRAM_CHAT_ID")
        api_id_text, api_hash = require_secret("TELEGRAM_API_ID"), require_secret("TELEGRAM_API_HASH")
        if not api_id_text.isdigit() or int(api_id_text) <= 0 or not re.fullmatch(r"[0-9a-fA-F]{32}", api_hash):
            raise PublishError("MTProto API credentials are malformed.")
        release = announce.load_release(args.release_json)
        if release.id != event_release_id or release.tag_name != event_tag:
            raise PublishError("The resolved release does not match the exact event or recovery identity.")
        if not automatic and not is_manual and (not_before is None or ledger.timestamp(release.published_at) <= not_before):
            raise PublishError("The release was published before the reviewed activation fence.")
        store = ledger.GitLedger(args.state_dir, state.STATE_BRANCH)
        if automatic:
            snapshot = store.read().document
            if snapshot["activation"].get("ready") is not True:
                raise PublishError("The protected ledger is not yet ready for delivery.")
            not_before = ledger.timestamp(snapshot["activation"]["published_after"])
            if dt.datetime.now(dt.timezone.utc) <= not_before:
                raise PublishError("The future-only activation fence is not yet effective.")
        if not is_manual and (not_before is None or ledger.timestamp(release.published_at) <= not_before):
            raise PublishError("The release was published before the reviewed activation fence.")
        source_sha = identity.resolve_tag_commit(repository, event_tag)
        if not is_manual and os.environ.get("GITHUB_SHA") != source_sha:
            raise PublishError("The published-release event commit does not match the exact current tag commit.")
        run_id, run_attempt = os.environ.get("GITHUB_RUN_ID", ""), os.environ.get("GITHUB_RUN_ATTEMPT", "")
        if not re.fullmatch(r"[1-9][0-9]*", run_id) or not re.fullmatch(r"[1-9][0-9]*", run_attempt):
            raise PublishError("The GitHub workflow run identity is invalid.")
        code_sha = tooling_commit()
        def policy() -> None:
            state.verify_state(config, args.state_dir, repository)
            state.require_disabled_legacy(repository)
        publish(release, store, token=token, chat=chat, api_id=int(api_id_text), api_hash=api_hash,
                source_sha=source_sha, workflow_run_id=int(run_id), run_attempt=int(run_attempt), tooling_sha=code_sha,
                upload_assets=not args.no_upload_assets, resume_unsent=args.resume_unsent,
                policy_check=policy,
                allow_new_release=not is_manual,
                require_protected_inventory=True)
    except Exception as exc:  # no raw exception strings, tracebacks or credential material
        if isinstance(exc, (PublishError, identity.IdentityError, ledger.LedgerError, announce.AnnouncementError)):
            announce.error(str(exc))
        else:
            announce.error("Telegram delivery failed safely; inspect the remote ledger and recovery runbook.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
