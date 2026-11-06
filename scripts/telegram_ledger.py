#!/usr/bin/env python3
"""Fail-closed, exact-parent Git ledger for bot-only Telegram delivery.

The explicitly configured state repository/ref is authoritative for live delivery.
Each update is a single-parent commit containing only the ledger, pushed without
force, then verified against the remote. This module never creates or updates
releases or tags. Legacy schemas are deliberately not upgraded automatically:
see the runbook.
"""
from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import hmac
import json
import os
import re
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from urllib.parse import quote

LEGACY_PATH = ".github/telegram-releases.json"
LEDGER_PATH = "telegram-delivery-state.json"
PUBLISHING_CONFIG_PATH = ".github/telegram-publishing.json"
SCHEMA = 4
HEX = re.compile(r"^[0-9a-f]{64}$")
COMMIT_SHA = re.compile(r"^[0-9a-f]{40}$")
OWNER = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
STATES = {"pending", "uploading", "sending", "delivery_unknown", "sent", "failed"}
HISTORICAL_REASON = "pre-ledger historical release; replay prohibited"
# Public GitHub release identities independently rechecked on 2026-11-06.
# A cutoff inventory must include at least these; a later review may find more.
KNOWN_HISTORICAL_RELEASES = {
    434756896: "v0.1.15", 432616187: "v0.1.14", 420907375: "v0.1.13",
    418090879: "v0.1.12", 417984841: "v0.1.11", 415704351: "v0.1.10",
    404239275: "v0.1.09", 402741322: "v0.1.08", 397204180: "v0.1.07",
    396937291: "v0.1.06", 392242106: "v0.1.05", 388298232: "0.1.04",
    387780380: "0.1.03", 387726965: "0.1.02", 387689712: "0.1.01",
}
KNOWN_RELEASE_ID = 434756896
KNOWN_RELEASE_TAG = "v0.1.15"
KNOWN_MESSAGE_ID = 5
KNOWN_EVIDENCE = "https://t.me/StreamBridge_App/5"
MAX_UPDATE_ATTEMPTS = 3


class LedgerError(RuntimeError):
    """A ledger operation is unsafe or its durable outcome is unknown."""


class LedgerConflict(LedgerError):
    """The exact parent was overtaken and the attempted commit was not accepted."""


def canonical(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()


def timestamp(value: object) -> dt.datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise LedgerError("Ledger/release timestamp must be an explicit UTC timestamp.")
    try:
        parsed = dt.datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise LedgerError("Ledger/release timestamp is invalid.") from exc
    return parsed.astimezone(dt.timezone.utc)


def _no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise LedgerError("Ledger contains duplicate JSON keys.")
        result[key] = value
    return result


def _json_input(data: bytes) -> Any:
    return json.loads(data.decode("utf-8"), object_pairs_hook=_no_duplicates)


def parse(data: bytes) -> dict[str, Any]:
    try:
        doc = json.loads(data.decode("utf-8"), object_pairs_hook=_no_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise LedgerError("Remote ledger is unreadable or corrupt; refusing to reset it.") from exc
    validate(doc)
    return doc


def publishing_not_before(path: str = PUBLISHING_CONFIG_PATH) -> dt.datetime | None:
    """Return a reviewed activation fence, or None for disabled/invalid config."""
    try:
        doc = json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=_no_duplicates)
        if not isinstance(doc, dict) or set(doc) != {"enabled", "not_before"} or doc["enabled"] is not True:
            return None
        return timestamp(doc["not_before"])
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, LedgerError):
        return None


def publishing_enabled(path: str = PUBLISHING_CONFIG_PATH) -> bool:
    """Allow delivery only after an explicitly reviewed activation fence."""
    after = publishing_not_before(path)
    return after is not None and dt.datetime.now(dt.timezone.utc) > after


def strict_publishing_status(path: str = PUBLISHING_CONFIG_PATH) -> bool:
    """Distinguish an intentionally disabled gate from a missing or invalid one."""
    try:
        doc = _json_input(Path(path).read_bytes())
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, LedgerError) as exc:
        raise LedgerError("Publishing configuration is missing or malformed.") from exc
    if doc == {"enabled": False} and type(doc.get("enabled")) is bool:
        return False
    if not isinstance(doc, dict) or set(doc) != {"enabled", "not_before"} or doc.get("enabled") is not True:
        raise LedgerError("Publishing configuration is invalid.")
    return dt.datetime.now(dt.timezone.utc) > timestamp(doc["not_before"])


def validate_legacy(legacy: object) -> None:
    if (not isinstance(legacy, dict) or type(legacy.get("schema")) is not int
            or legacy.get("schema") not in (1, 2) or not isinstance(legacy.get("releases"), list)):
        raise LedgerError("Ledger legacy snapshot is invalid.")
    ids: set[int] = set()
    tags: set[str] = set()
    for entry in legacy["releases"]:
        if not isinstance(entry, dict):
            raise LedgerError("Legacy release entry is invalid.")
        rid, tag = entry.get("release_id"), entry.get("tag_name")
        if type(rid) is not int or rid <= 0 or rid in ids or not isinstance(tag, str) or not tag or tag in tags:
            raise LedgerError("Legacy release id/tag is missing, invalid or repeated.")
        ids.add(rid)
        tags.add(tag)
        if "announced" in entry and type(entry["announced"]) is not bool:
            raise LedgerError("Legacy announcement status is invalid.")
        if "message_ids" in entry and (not isinstance(entry["message_ids"], list)
                                        or any(type(x) is not int or x <= 0 for x in entry["message_ids"])):
            raise LedgerError("Legacy message ids are invalid.")
        if "assets" in entry:
            if not isinstance(entry["assets"], list):
                raise LedgerError("Legacy asset records are invalid.")
            names: set[str] = set()
            for asset in entry["assets"]:
                if (not isinstance(asset, dict) or not isinstance(asset.get("name"), str) or not asset["name"]
                        or asset["name"] in names or type(asset.get("message_id")) is not int or asset["message_id"] <= 0):
                    raise LedgerError("Legacy asset record is invalid or repeated.")
                names.add(asset["name"])


def validate_inventory(inventory: object) -> None:
    """Require an explicit, duplicate-free historical release/tag inventory."""
    if (not isinstance(inventory, dict) or not {"release_ids", "tags"}.issubset(inventory)
            or set(inventory) - {"release_ids", "tags", "message_ids", "protected", "exclusions"}):
        raise LedgerError("Historical release/tag inventory is missing or invalid.")
    ids, tags = inventory["release_ids"], inventory["tags"]
    messages = inventory.get("message_ids", [])
    if (not isinstance(messages, list) or any(type(value) is not int or value <= 0 for value in messages)
            or len(set(messages)) != len(messages)):
        raise LedgerError("Historical message inventory contains invalid or repeated IDs.")
    if (not isinstance(ids, list) or any(type(value) is not int or value <= 0 for value in ids)
            or len(set(ids)) != len(ids)):
        raise LedgerError("Historical release inventory contains invalid or repeated IDs.")
    if (not isinstance(tags, list) or any(not isinstance(value, str) or not value
                                           or any(c in value for c in "\x00\r\n") for value in tags)
            or len(set(tags)) != len(tags)):
        raise LedgerError("Historical tag inventory contains invalid or repeated tags.")
    if "protected" in inventory:
        protected = inventory["protected"]
        required_protected = {"key_check", "release_id_digests", "tag_digests"}
        if (not isinstance(protected, dict) or not required_protected.issubset(protected)
                or set(protected) - (required_protected | {"tag_ref_snapshot"})
                or not isinstance(protected["key_check"], str) or not HEX.fullmatch(protected["key_check"])
                or ("tag_ref_snapshot" in protected and
                    (not isinstance(protected["tag_ref_snapshot"], str) or
                     not HEX.fullmatch(protected["tag_ref_snapshot"])))):
            raise LedgerError("Protected historical inventory is invalid.")
        for field in ("release_id_digests", "tag_digests"):
            values = protected[field]
            if (not isinstance(values, list) or any(not isinstance(v, str) or not HEX.fullmatch(v) for v in values)
                    or len(set(values)) != len(values)):
                raise LedgerError("Protected historical inventory contains invalid or repeated digests.")
    exclusions = inventory.get("exclusions", [])
    if not isinstance(exclusions, list):
        raise LedgerError("Historical exclusions must be a list.")
    excluded_ids: set[int] = set()
    excluded_tags: set[str] = set()
    excluded_messages: set[int] = set()
    for entry in exclusions:
        fields = {"release_id", "tag", "source_sha", "published_at", "status", "reason",
                  "message_id", "evidence", "delivered_at"}
        if not isinstance(entry, dict) or set(entry) != fields:
            raise LedgerError("A historical exclusion is malformed.")
        rid, tag, sha, mid = entry["release_id"], entry["tag"], entry["source_sha"], entry["message_id"]
        if (type(rid) is not int or rid <= 0 or rid in excluded_ids or rid not in ids
                or not isinstance(tag, str) or not tag or tag in excluded_tags or tag not in tags
                or entry["status"] != "historical_excluded" or entry["reason"] != HISTORICAL_REASON
                or (sha is not None and (not isinstance(sha, str) or not COMMIT_SHA.fullmatch(sha)))):
            raise LedgerError("A historical exclusion has an invalid or repeated identity.")
        timestamp(entry["published_at"])
        if mid is None:
            if entry["evidence"] is not None or entry["delivered_at"] is not None:
                raise LedgerError("Unconfirmed historical delivery cannot have evidence or a delivery time.")
        else:
            if (type(mid) is not int or mid <= 0 or mid in excluded_messages or mid not in messages
                    or entry["evidence"] != f"https://t.me/StreamBridge_App/{mid}"):
                raise LedgerError("Historical message evidence is invalid or repeated.")
            excluded_messages.add(mid)
            if entry["delivered_at"] is not None:
                timestamp(entry["delivered_at"])
        excluded_ids.add(rid)
        excluded_tags.add(tag)


def _inventory_mac(token: str, domain: str, value: str = "") -> str:
    if (not isinstance(token, str) or not token or token != token.strip()
            or any(char.isspace() for char in token)):
        raise LedgerError("A stable bot token is required to verify protected history.")
    return hmac.new(token.encode("utf-8"), ("streambridge-history-v1:" + domain + "\0" + value).encode("utf-8"),
                    hashlib.sha256).hexdigest()


def protect_inventory(inventory: dict[str, Any], public_release_ids: set[int], public_tags: set[str],
                      token: str) -> dict[str, Any]:
    """Blind non-public identifiers for a public state branch; never mutate the input.

    Public classifications must be independently reviewed at the frozen cutover.
    The token remains secret; it is never written into the returned inventory.
    """
    validate_inventory(inventory)
    if "protected" in inventory:
        raise LedgerError("Historical inventory is already protected.")
    if (not isinstance(public_release_ids, set) or any(type(i) is not int or i <= 0 for i in public_release_ids)
            or not isinstance(public_tags, set) or any(not isinstance(tag, str) or not tag for tag in public_tags)):
        raise LedgerError("Public historical release/tag classification is invalid.")
    result: dict[str, Any] = {
        "release_ids": sorted(rid for rid in inventory["release_ids"] if rid in public_release_ids),
        "tags": sorted(tag for tag in inventory["tags"] if tag in public_tags),
        "protected": {
            "key_check": _inventory_mac(token, "key-check"),
            "release_id_digests": sorted(_inventory_mac(token, "release-id", str(rid))
                                         for rid in inventory["release_ids"] if rid not in public_release_ids),
            "tag_digests": sorted(_inventory_mac(token, "tag", tag)
                                  for tag in inventory["tags"] if tag not in public_tags),
        },
    }
    if "message_ids" in inventory:
        result["message_ids"] = list(inventory["message_ids"])
    if "exclusions" in inventory:
        if any(entry["release_id"] not in public_release_ids or entry["tag"] not in public_tags
               for entry in inventory["exclusions"]):
            raise LedgerError("Historical exclusion details may contain only independently reviewed public releases.")
        result["exclusions"] = copy.deepcopy(inventory["exclusions"])
    validate_inventory(result)
    return result


def require_inventory_key(inventory: dict[str, Any], token: str, *, required: bool = False) -> None:
    """Refuse delivery if protected history is absent or the bot token changed."""
    validate_inventory(inventory)
    protected = inventory.get("protected")
    if protected is None:
        if required:
            raise LedgerError("Same-repository state requires a protected historical inventory.")
        return
    if not hmac.compare_digest(protected["key_check"], _inventory_mac(token, "key-check")):
        raise LedgerError("The protected historical inventory key changed; audited migration is required.")


def require_reviewed_history(inventory: dict[str, Any]) -> None:
    """Require explicit public exclusions and the independently observed v0.1.15 evidence."""
    validate_inventory(inventory)
    exclusions = inventory.get("exclusions", [])
    if {entry["release_id"] for entry in exclusions} != set(inventory["release_ids"]):
        raise LedgerError("Every public historical release must have a reviewed exclusion record.")
    pairs = {entry["release_id"]: entry["tag"] for entry in exclusions}
    if any(pairs.get(rid) != tag for rid, tag in KNOWN_HISTORICAL_RELEASES.items()):
        raise LedgerError("The reviewed historical cutover omits a verified public release identity.")
    known = [entry for entry in exclusions if entry["release_id"] == KNOWN_RELEASE_ID]
    if (len(known) != 1 or known[0]["tag"] != KNOWN_RELEASE_TAG
            or known[0]["message_id"] != KNOWN_MESSAGE_ID or known[0]["evidence"] != KNOWN_EVIDENCE):
        raise LedgerError("The confirmed v0.1.15 announcement is missing from the historical cutover.")


def historically_seen(inventory: dict[str, Any], token: str, release_id: int, tag: str) -> bool:
    """Match the known announcement and public or blinded historical identities."""
    if release_id in KNOWN_HISTORICAL_RELEASES or tag in KNOWN_HISTORICAL_RELEASES.values():
        return True
    if release_id in inventory["release_ids"] or tag in inventory["tags"]:
        return True
    protected = inventory.get("protected")
    return bool(protected and (
        _inventory_mac(token, "release-id", str(release_id)) in protected["release_id_digests"]
        or _inventory_mac(token, "tag", tag) in protected["tag_digests"]))


def release_status(items: list[dict[str, Any]]) -> str:
    """The release is sent only when every operation has a confirmed response."""
    states = [item["state"] for item in items]
    if "delivery_unknown" in states:
        return "delivery_unknown"
    if "sending" in states:
        return "sending"
    if "failed" in states:
        return "failed"
    if states and all(value == "sent" for value in states):
        return "sent"
    return "pending"


def validate(doc: object) -> None:
    if not isinstance(doc, dict):
        raise LedgerError("Remote ledger is not a JSON object.")
    if type(doc.get("schema")) is not int or doc.get("schema") != SCHEMA:
        raise LedgerError("Remote ledger is not schema 4; an audited, manual legacy cutover is required before publishing.")
    if set(doc) != {"schema", "activation", "legacy", "inventory", "identity", "session", "releases", "verification"}:
        raise LedgerError("Remote ledger has missing or unexpected top-level fields.")
    activation = doc["activation"]
    if not isinstance(activation, dict) or set(activation) != {"published_after", "legacy_sha256", "inventory_sha256"}:
        raise LedgerError("Ledger activation is invalid.")
    cutoff = timestamp(activation["published_after"])
    legacy = doc["legacy"]
    validate_legacy(legacy)
    if not isinstance(activation["legacy_sha256"], str) or digest(legacy) != activation["legacy_sha256"]:
        raise LedgerError("Ledger legacy snapshot checksum does not match.")
    inventory = doc["inventory"]
    validate_inventory(inventory)
    if not isinstance(activation["inventory_sha256"], str) or digest(inventory) != activation["inventory_sha256"]:
        raise LedgerError("Ledger historical inventory checksum does not match.")
    if (not {entry["release_id"] for entry in legacy["releases"]}.issubset(inventory["release_ids"])
            or not {entry["tag_name"] for entry in legacy["releases"]}.issubset(inventory["tags"])):
        raise LedgerError("Historical inventory omits a preserved legacy release or tag.")
    if any(timestamp(entry["published_at"]) > cutoff for entry in inventory.get("exclusions", [])):
        raise LedgerError("A historical exclusion is newer than the audited cutoff.")
    identity = doc["identity"]
    if identity is not None and (not isinstance(identity, str) or not HEX.fullmatch(identity)):
        raise LedgerError("Ledger bot/channel identity binding is invalid.")
    session = doc["session"]
    if session is not None and (not isinstance(session, dict) or set(session) != {"owner"} or
                                not isinstance(session["owner"], str) or not OWNER.fullmatch(session["owner"])):
        raise LedgerError("Ledger session lock is invalid.")
    verification = doc["verification"]
    if not isinstance(verification, list):
        raise LedgerError("Ledger verification records are invalid.")
    verification_ids: set[str] = set()
    for record in verification:
        if (not isinstance(record, dict) or set(record) != {"id", "run_id", "phase", "status"}
                or not isinstance(record["id"], str) or not re.fullmatch(r"[0-9a-f]{32}", record["id"])
                or record["id"] in verification_ids or type(record["run_id"]) is not int or record["run_id"] <= 0
                or not isinstance(record["phase"], str) or record["phase"] not in
                    {"interrupted", "unknown-simulation", "first", "second", "race-a", "race-b"}
                or not isinstance(record["status"], str) or record["status"] not in {"active", "archived"}):
            raise LedgerError("Ledger verification record is invalid or repeated.")
        verification_ids.add(record["id"])
    releases = doc["releases"]
    if not isinstance(releases, list):
        raise LedgerError("Ledger releases are invalid.")
    release_ids: set[int] = set()
    release_tags: set[str] = set()
    legacy_ids = set(inventory["release_ids"])
    legacy_tags = set(inventory["tags"])
    random_ids: set[int] = set()
    # Preserve known historical messages without pretending the channel history is complete.
    message_ids = set(inventory.get("message_ids", []))
    message_ids.update(message_id for entry in legacy["releases"] for message_id in entry.get("message_ids", []))
    message_ids.update(asset["message_id"] for entry in legacy["releases"] for asset in entry.get("assets", []))
    for release in releases:
        fields = {"id", "tag", "source_sha", "created_at", "published_at", "status", "plan", "items"}
        if not isinstance(release, dict) or set(release) != fields:
            raise LedgerError("Ledger release entry is invalid.")
        rid, tag, sha = release["id"], release["tag"], release["source_sha"]
        if type(rid) is not int or rid <= 0 or rid in release_ids:
            raise LedgerError("Ledger contains an invalid or repeated release id.")
        release_ids.add(rid)
        if not isinstance(tag, str) or not tag or tag in release_tags:
            raise LedgerError("Ledger release tag is invalid or repeated.")
        release_tags.add(tag)
        if (rid in KNOWN_HISTORICAL_RELEASES or tag in KNOWN_HISTORICAL_RELEASES.values()
                or rid in legacy_ids or tag in legacy_tags):
            raise LedgerError("A schema-4 release overlaps the historical release/tag inventory.")
        if not isinstance(sha, str) or not COMMIT_SHA.fullmatch(sha):
            raise LedgerError("Ledger source commit SHA is missing or invalid.")
        created, published = timestamp(release["created_at"]), timestamp(release["published_at"])
        if published <= cutoff or created > published:
            raise LedgerError("Ledger publication predates the audited cutoff or has inconsistent timestamps.")
        if not isinstance(release["plan"], str) or not HEX.fullmatch(release["plan"]):
            raise LedgerError("Ledger release plan hash is invalid.")
        if not isinstance(release["items"], list) or not release["items"]:
            raise LedgerError("Ledger release has no operations.")
        keys: set[str] = set()
        page_count = 0
        saw_asset = False
        unfinished_page = False
        uncertain_count = 0
        for item in release["items"]:
            fields = {"key", "kind", "payload", "random_id", "state", "message_id", "sha256", "attempts",
                      "attempted_at", "delivered_at", "workflow_run_id", "run_attempt", "tooling_sha", "reconciliation"}
            if not isinstance(item, dict) or set(item) != fields:
                raise LedgerError("Ledger operation entry is invalid.")
            key, kind, status = item["key"], item["kind"], item["state"]
            if (not isinstance(key, str) or key in keys or not isinstance(kind, str) or kind not in {"page", "asset"}
                    or not re.fullmatch(rf"{kind}:[1-9][0-9]*", key)):
                raise LedgerError("Ledger operation key/kind is invalid or repeated.")
            keys.add(key)
            if not isinstance(status, str) or status not in STATES:
                raise LedgerError("Ledger operation state is invalid.")
            if kind == "page":
                page_count += 1
                if saw_asset or key != f"page:{page_count}" or (unfinished_page and status != "pending"):
                    raise LedgerError("Ledger announcement pages are out of order.")
                unfinished_page = unfinished_page or status != "sent"
            else:
                saw_asset = True
                if not page_count or (unfinished_page and status != "pending"):
                    raise LedgerError("Ledger asset was attempted before all pages were confirmed.")
            if not isinstance(item["payload"], str) or not HEX.fullmatch(item["payload"]):
                raise LedgerError("Ledger operation payload hash is invalid.")
            random_id = item["random_id"]
            if type(random_id) is not int or random_id == 0 or not -(1 << 63) <= random_id < (1 << 63) or random_id in random_ids:
                raise LedgerError("Ledger operation random_id is invalid or repeated.")
            random_ids.add(random_id)
            if type(item["attempts"]) is not int or item["attempts"] not in (0, 1):
                raise LedgerError("Ledger operation attempt count is invalid; ambiguous sends must not be retried.")
            attempted = status in {"sending", "delivery_unknown", "sent"}
            if attempted:
                if (item["attempts"] != 1 or type(item["workflow_run_id"]) is not int or item["workflow_run_id"] <= 0
                        or type(item["run_attempt"]) is not int or item["run_attempt"] <= 0
                        or not isinstance(item["tooling_sha"], str) or not COMMIT_SHA.fullmatch(item["tooling_sha"])):
                    raise LedgerError("An attempted send lacks its workflow and attempt identity.")
                attempted_at = timestamp(item["attempted_at"])
            else:
                if (item["attempts"] != 0 or item["attempted_at"] is not None or item["tooling_sha"] is not None
                        or item["workflow_run_id"] is not None or item["run_attempt"] is not None):
                    raise LedgerError("An operation with send attempts cannot be reset to an unsent state.")
                attempted_at = None
            message_id = item["message_id"]
            if status == "sent":
                if type(message_id) is not int or message_id <= 0 or message_id in message_ids:
                    raise LedgerError("Sent ledger operation has an invalid or repeated message id.")
                if timestamp(item["delivered_at"]) < attempted_at:
                    raise LedgerError("A delivery timestamp predates its attempt.")
                message_ids.add(message_id)
            elif message_id is not None or item["delivered_at"] is not None:
                raise LedgerError("Unconfirmed ledger operation has a message id or delivery time.")
            if status == "delivery_unknown":
                uncertain_count += 1
                if item["reconciliation"] != {"status": "review_required", "reason": "unconfirmed_telegram_response"}:
                    raise LedgerError("Ambiguous delivery lacks required reconciliation information.")
            elif status == "sending":
                uncertain_count += 1
                if item["reconciliation"] is not None:
                    raise LedgerError("An in-flight operation has unexpected reconciliation data.")
            elif status == "failed":
                if item["reconciliation"] != {"status": "unsent", "reason": "pre_send_failure"}:
                    raise LedgerError("A failed operation lacks its pre-send failure classification.")
            elif status == "sent" and item["reconciliation"] is not None:
                evidence = item["reconciliation"]
                if (not isinstance(evidence, dict) or set(evidence) != {"status", "source", "job_id"}
                        or evidence["status"] != "confirmed_response" or evidence["source"] != "github_actions_log"
                        or type(evidence["job_id"]) is not int or evidence["job_id"] <= 0):
                    raise LedgerError("Confirmed reconciliation evidence is invalid.")
            elif item["reconciliation"] is not None:
                raise LedgerError("Ledger operation has unexpected reconciliation data.")
            if status == "uploading" and kind != "asset":
                raise LedgerError("Only an asset can be in the upload phase.")
            asset_sha = item["sha256"]
            if asset_sha is not None and (not isinstance(asset_sha, str) or not HEX.fullmatch(asset_sha)):
                raise LedgerError("Ledger asset SHA-256 is invalid.")
            if kind == "page" and asset_sha is not None:
                raise LedgerError("A text operation cannot have an asset digest.")
            if status == "pending" and asset_sha is not None:
                raise LedgerError("An unstarted operation cannot have a recorded upload digest.")
            if kind == "asset" and status in {"uploading", "sending", "delivery_unknown", "sent"} and asset_sha is None:
                raise LedgerError("An attempted asset has no frozen SHA-256.")
        if page_count == 0:
            raise LedgerError("Ledger release has no announcement page.")
        if uncertain_count > 1:
            raise LedgerError("Multiple unresolved sends in one release are inconsistent.")
        if release["status"] != release_status(release["items"]):
            raise LedgerError("Ledger release status does not match its operations.")
        frozen = [{key: item[key] for key in ("key", "kind", "payload")} for item in release["items"]]
        if digest(frozen) != release["plan"]:
            raise LedgerError("Ledger operation payloads do not match their frozen release plan.")
    if identity is None and any(item["state"] != "pending" for release in releases for item in release["items"]):
        raise LedgerError("Attempted operations require a bound bot/channel identity.")

def require_branch_rules(repository: str, branch: str, *, runner: Callable = subprocess.run) -> None:
    """Require visible active no-force/no-delete rules; this is necessary, not sufficient.

    PR-only default branches are incompatible with this direct-push store, so
    stop rather than request a bypass. Metadata-read rules do not prove exclusive
    writers or expose every bypass actor. All output is captured: neither
    GitHub's error text nor credentials are emitted in diagnostics.
    """
    if (not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)
            or not re.fullmatch(r"[A-Za-z0-9._/-]+", branch) or branch.startswith("-")):
        raise LedgerError("GitHub repository or ledger branch is invalid.")
    endpoint = f"repos/{repository}/rules/branches/{quote(branch, safe='')}?per_page=100"
    try:
        result = runner(["gh", "api", "--paginate", "--jq", ".[] | .type", endpoint],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60, check=False)
        types = set(result.stdout.decode("utf-8").splitlines())
    except (OSError, subprocess.TimeoutExpired, UnicodeDecodeError) as exc:
        raise LedgerError("GitHub branch rules could not be verified; publishing is blocked.") from exc
    if result.returncode or not {"non_fast_forward", "deletion"}.issubset(types):
        raise LedgerError("Active no-force and no-delete rulesets could not be verified; publishing is blocked.")
    if "pull_request" in types:
        raise LedgerError("The default-branch PR requirement conflicts with direct ledger writes; publishing is blocked. Do not bypass it.")


def operation_outcome(item: dict[str, Any]) -> str:
    """Classify validated schema-4 state; unsent means *by this protocol* only."""
    if item["state"] == "sent":
        return "confirmed_sent"
    if item["state"] in {"pending", "uploading", "failed"}:
        return "confirmed_unsent_by_protocol"
    if item["state"] in {"sending", "delivery_unknown"}:
        return "unknown"
    raise LedgerError("Ledger operation state is invalid.")


def summarize(doc: dict[str, Any]) -> dict[str, Any]:
    """Redacted read-only counts; never infer a clean channel from legacy data."""
    validate(doc)
    counts = {"confirmed_sent": 0, "confirmed_unsent_by_protocol": 0, "unknown": 0}
    for release in doc["releases"]:
        for item in release["items"]:
            counts[operation_outcome(item)] += 1
    return {"schema": SCHEMA, "session_locked": doc["session"] is not None,
            "operations": counts, "legacy_release_records": len(doc["legacy"]["releases"]),
            "inventoried_release_ids": len(doc["inventory"]["release_ids"]) +
                                       len(doc["inventory"].get("protected", {}).get("release_id_digests", [])),
            "inventoried_tags": len(doc["inventory"]["tags"]) +
                                len(doc["inventory"].get("protected", {}).get("tag_digests", [])),
            "inventoried_message_ids": len(doc["inventory"].get("message_ids", [])),
            "historical_exclusions": len(doc["inventory"].get("exclusions", [])),
            "active_verifications": sum(record["status"] == "active" for record in doc["verification"]),
            "legacy_channel_history": "unknown"}


def migrate(legacy: object, published_after: str, inventory: object) -> dict[str, Any]:
    """Prepare, but do not deploy, a migration. Inventory completeness needs human review."""
    validate_legacy(legacy)
    validate_inventory(inventory)
    timestamp(published_after)
    old = copy.deepcopy(legacy)
    if "protected" in inventory and (
        not {entry["release_id"] for entry in old["releases"]}.issubset(inventory["release_ids"])
        or not {entry["tag_name"] for entry in old["releases"]}.issubset(inventory["tags"])
    ):
        raise LedgerError("Protected migration cannot publish a non-public legacy entry; review it privately.")
    historical = {
        "release_ids": sorted(set(inventory["release_ids"]) | {entry["release_id"] for entry in old["releases"]}),
        "tags": sorted(set(inventory["tags"]) | {entry["tag_name"] for entry in old["releases"]}),
    }
    if "message_ids" in inventory:
        historical["message_ids"] = sorted(inventory["message_ids"])
    if "protected" in inventory:
        historical["protected"] = copy.deepcopy(inventory["protected"])
    if "exclusions" in inventory:
        historical["exclusions"] = copy.deepcopy(inventory["exclusions"])
    doc = {"schema": SCHEMA, "activation": {"published_after": published_after, "legacy_sha256": digest(old),
                                             "inventory_sha256": digest(historical)},
           "legacy": old, "inventory": historical, "identity": None, "session": None, "releases": [],
           "verification": []}
    validate(doc)
    return doc


def validate_append_only(old: dict[str, Any], new: dict[str, Any]) -> None:
    """Do not erase or rewrite historical identities, plans or successful deliveries."""
    for key in ("legacy", "inventory", "activation"):
        if old[key] != new[key]:
            raise LedgerError("Historical snapshots and the audited activation fence are immutable during delivery.")
    if old["identity"] is not None and new["identity"] != old["identity"]:
        raise LedgerError("The bound bot/channel identity cannot be rewritten.")
    before, after = old["releases"], new["releases"]
    if len(after) < len(before):
        raise LedgerError("Existing release records cannot be removed.")
    allowed = {
        "pending": {"pending", "uploading", "sending", "failed"},
        "uploading": {"uploading", "sending", "failed"},
        "failed": {"failed", "uploading"},
        "sending": {"sending", "delivery_unknown", "sent"},
        "delivery_unknown": {"delivery_unknown", "sent"},
        "sent": {"sent"},
    }
    immutable_release = ("id", "tag", "source_sha", "created_at", "published_at", "plan")
    immutable_item = ("key", "kind", "payload", "random_id")
    for previous, current in zip(before, after):
        if (any(previous[key] != current[key] for key in immutable_release)
                or len(previous["items"]) != len(current["items"])):
            raise LedgerError("An existing release identity or frozen plan was changed.")
        for old_item, new_item in zip(previous["items"], current["items"]):
            if any(old_item[key] != new_item[key] for key in immutable_item):
                raise LedgerError("An existing operation identity or frozen payload was changed.")
            if new_item["state"] not in allowed[old_item["state"]]:
                raise LedgerError("The requested operation transition is unsafe.")
            if (old_item["state"] == "delivery_unknown" and new_item["state"] == "sent"
                    and (not isinstance(new_item["reconciliation"], dict)
                         or new_item["reconciliation"].get("status") != "confirmed_response")):
                raise LedgerError("An ambiguous delivery requires confirmed-response reconciliation evidence.")
            if old_item["state"] == "sent" and old_item != new_item:
                raise LedgerError("A confirmed delivery cannot be rewritten.")
            if old_item["attempts"] and any(old_item[key] != new_item[key] for key in
                                             ("attempts", "attempted_at", "workflow_run_id", "run_attempt", "tooling_sha")):
                raise LedgerError("An attempted operation's audit identity cannot be rewritten.")
            if old_item["sha256"] is not None and old_item["sha256"] != new_item["sha256"]:
                raise LedgerError("An asset's frozen digest cannot be rewritten.")
    old_verification, new_verification = old["verification"], new["verification"]
    if len(new_verification) < len(old_verification):
        raise LedgerError("Verification records cannot be removed.")
    for previous, current in zip(old_verification, new_verification):
        if (any(previous[key] != current[key] for key in ("id", "run_id", "phase"))
                or (previous["status"] == "archived" and current["status"] != "archived")):
            raise LedgerError("Existing verification records cannot be rewritten.")


@dataclass(frozen=True)
class Snapshot:
    parent: str
    document: dict[str, Any]
    raw: bytes


class GitLedger:
    """Remote Git ref, not the runner's checkout, is the only authority."""

    def __init__(self, repo: str, branch: str, path: str = LEDGER_PATH) -> None:
        self.repo = repo
        self.branch = branch
        self.path = path
        if path != LEDGER_PATH or not re.fullmatch(r"[A-Za-z0-9._/-]+", branch) or branch.startswith("-"):
            raise LedgerError("Invalid ledger path or branch.")
        self._git("check-ref-format", f"refs/heads/{branch}")

    def _git(self, *args: str, data: bytes | None = None, env: dict[str, str] | None = None,
             check: bool = True, timeout: int = 90) -> subprocess.CompletedProcess[bytes]:
        try:
            result = subprocess.run(["git", *args], cwd=self.repo, input=data, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, env=env, timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise LedgerError("A Git ledger operation failed or timed out; no output or credentials were logged.") from exc
        if check and result.returncode:
            raise LedgerError("A Git ledger operation failed; remote permissions, branch policy, or connectivity need review.")
        return result

    def read(self) -> Snapshot:
        self._git("fetch", "--no-tags", "origin", f"refs/heads/{self.branch}")
        parent = self._git("rev-parse", "FETCH_HEAD").stdout.decode("ascii").strip()
        if not re.fullmatch(r"[0-9a-f]{40,64}", parent):
            raise LedgerError("Could not determine the authoritative ledger revision.")
        names = self._git("ls-tree", "-r", "--name-only", parent).stdout.decode("utf-8").splitlines()
        if names != [self.path]:
            raise LedgerError("The ledger is missing or the state branch contains additional files or application source.")
        entry = self._git("ls-tree", parent, "--", self.path).stdout.decode("utf-8").split()
        if len(entry) != 4 or entry[0] != "100644" or entry[1] != "blob" or entry[3] != self.path:
            raise LedgerError("The state branch ledger must be an ordinary file.")
        result = self._git("show", f"{parent}:{self.path}", check=False)
        if result.returncode:
            raise LedgerError("Remote ledger is missing or inaccessible; refusing to initialise it.")
        return Snapshot(parent, parse(result.stdout), result.stdout)

    def save(self, old: Snapshot, document: dict[str, Any]) -> Snapshot:
        validate(document)
        if parse(old.raw) != old.document:
            raise LedgerError("The fetched ledger snapshot was modified or is inconsistent.")
        validate_append_only(old.document, document)
        body = canonical(document)
        blob = self._git("hash-object", "-w", "--stdin", data=body).stdout.decode("ascii").strip()
        # A private temporary index starts from precisely the fetched parent; the
        # normal index, checkout and unrelated files are never modified.
        with tempfile.TemporaryDirectory(prefix="telegram-ledger-") as tmp:
            env = os.environ.copy()
            env.update({"GIT_INDEX_FILE": os.path.join(tmp, "index"),
                        "GIT_AUTHOR_NAME": "github-actions[bot]",
                        "GIT_AUTHOR_EMAIL": "41898282+github-actions[bot]@users.noreply.github.com",
                        "GIT_COMMITTER_NAME": "github-actions[bot]",
                        "GIT_COMMITTER_EMAIL": "41898282+github-actions[bot]@users.noreply.github.com"})
            self._git("read-tree", old.parent, env=env)
            self._git("update-index", "--add", "--cacheinfo", f"100644,{blob},{self.path}", env=env)
            tree = self._git("write-tree", env=env).stdout.decode("ascii").strip()
            commit = self._git("commit-tree", tree, "-p", old.parent, env=env,
                               data=b"chore(telegram): persist delivery state [skip ci]\n").stdout.decode("ascii").strip()
        # A failed/timed-out push might have been accepted. Never assume success
        # until a fresh fetch proves this exact commit and the intended bytes.
        push_result = None
        try:
            push_result = self._git("push", "origin", f"{commit}:refs/heads/{self.branch}", check=False, timeout=90)
        except LedgerError:
            pass  # The remote ref below, not a lost push response, decides success.
        current = self.read()
        ancestor = self._git("merge-base", "--is-ancestor", commit, current.parent, check=False)
        if not ancestor.returncode and current.raw == body:
            return current
        # Retry only a definite non-fast-forward rejection. A timeout or any
        # other uncertain outcome is not silently treated as a conflict.
        old_ancestor = self._git("merge-base", "--is-ancestor", old.parent, current.parent, check=False)
        rejected = (push_result is not None and push_result.returncode != 0
                    and any(reason in push_result.stderr for reason in
                            (b"non-fast-forward", b"fetch first", b"cannot lock ref", b"failed to update ref")))
        if (rejected and current.parent != old.parent and old_ancestor.returncode == 0 and ancestor.returncode == 1):
            validate_append_only(old.document, current.document)
            raise LedgerConflict("The ledger parent advanced; the intended transition must be re-evaluated.")
        raise LedgerError("Ledger update was rejected, raced, or could not be verified; stopped without assuming success.")


def update(store: Any, change: Callable[[dict[str, Any]], Any], *, attempts: int = MAX_UPDATE_ATTEMPTS) -> tuple[Snapshot, Any]:
    """Re-read/reapply only definite CAS conflicts; never retry ambiguous writes."""
    if type(attempts) is not int or not 1 <= attempts <= MAX_UPDATE_ATTEMPTS:
        raise LedgerError("The bounded ledger update retry count is invalid.")
    for index in range(attempts):
        old = store.read()
        document = copy.deepcopy(old.document)
        result = change(document)
        validate(document)
        validate_append_only(old.document, document)
        if document == old.document:
            return old, result
        try:
            return store.save(old, document), result
        except LedgerConflict:
            if index + 1 == attempts:
                raise LedgerError("The bounded ledger conflict retry limit was reached.")
    raise LedgerError("The ledger transition could not be completed.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Inspect a ledger or prepare an audited legacy cutover; never deploys it or contacts Telegram.")
    parser.add_argument("--inspect", help="read-only summary of a local ledger snapshot")
    parser.add_argument("--protect-inventory", action="store_true", help="blind non-public historical identifiers")
    parser.add_argument("--public-inventory", help="reviewed public release-ID/tag classification JSON")
    parser.add_argument("--legacy", help="existing legacy schema-1 or schema-2 ledger")
    parser.add_argument("--inventory", help="reviewed historical release-ID/tag inventory JSON")
    parser.add_argument("--output", help="new file to inspect; must not already exist")
    parser.add_argument("--published-after", help="future UTC cutoff, e.g. 2027-01-01T00:00:00Z")
    parser.add_argument("--acknowledge-legacy-is-unknown", action="store_true")
    parser.add_argument("--acknowledge-release-freeze", action="store_true")
    args = parser.parse_args()
    if args.inspect:
        if (args.protect_inventory or args.public_inventory or args.legacy or args.inventory or args.output
                or args.published_after or args.acknowledge_legacy_is_unknown or args.acknowledge_release_freeze):
            parser.error("--inspect cannot be combined with migration arguments")
        try:
            raw = Path(args.inspect).read_bytes()
            doc = json.loads(raw.decode("utf-8"), object_pairs_hook=_no_duplicates)
            if isinstance(doc, dict) and type(doc.get("schema")) is int and doc["schema"] in (1, 2):
                validate_legacy(doc)
                report = {"schema": doc["schema"], "legacy_release_records": len(doc["releases"]),
                          "legacy_channel_history": "unknown"}
            else:
                report = summarize(doc)
            print(json.dumps(report, sort_keys=True))
            return 0
        except (LedgerError, OSError, UnicodeDecodeError, json.JSONDecodeError):
            print("Ledger inspection failed: state is missing, malformed or unsupported.")
            return 1
    if args.protect_inventory:
        if (not args.inventory or not args.public_inventory or not args.output or args.legacy or args.published_after
                or args.acknowledge_legacy_is_unknown or args.acknowledge_release_freeze):
            parser.error("--protect-inventory requires --inventory, --public-inventory and --output only")
        try:
            inventory = _json_input(Path(args.inventory).read_bytes())
            public = _json_input(Path(args.public_inventory).read_bytes())
            validate_inventory(public)
            if "protected" in public:
                raise LedgerError("Public classification must contain plain public identifiers only.")
            protected = protect_inventory(inventory, set(public["release_ids"]), set(public["tags"]),
                                          os.environ.get("TELEGRAM_BOT_TOKEN", ""))
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as handle:
                handle.write(canonical(protected))
        except LedgerError as exc:
            print(f"Inventory protection failed: {exc}")
            return 1
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            print("Inventory protection failed: input/output could not be read or created.")
            return 1
        print("Prepared a protected inventory for review; it is not deployed or proof of completeness.")
        return 0
    if args.public_inventory:
        parser.error("--public-inventory is only used with --protect-inventory")
    if not (args.legacy and args.inventory and args.output and args.published_after
            and args.acknowledge_legacy_is_unknown and args.acknowledge_release_freeze):
        parser.error("migration requires --legacy, --inventory, --output, --published-after and both acknowledgements")
    try:
        cutoff = timestamp(args.published_after)
        if cutoff <= dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=1):
            raise LedgerError("Choose a cutoff at least one hour in the future and deploy before it.")
        legacy = json.loads(Path(args.legacy).read_text(encoding="utf-8"), object_pairs_hook=_no_duplicates)
        inventory = json.loads(Path(args.inventory).read_text(encoding="utf-8"), object_pairs_hook=_no_duplicates)
        document = migrate(legacy, args.published_after, inventory)
        with open(args.output, "xb") as handle:
            handle.write(canonical(document))
    except LedgerError as exc:
        print(f"Migration preparation failed: {exc}")
        return 1
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        print("Migration preparation failed: input/output could not be read or created.")
        return 1
    print("Prepared a migration for review. It is not deployed and proves nothing about older channel posts.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
