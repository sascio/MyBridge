#!/usr/bin/env python3
"""Fail-closed, exact-parent Git ledger for bot-only Telegram delivery.

The repository's default-branch ledger is authoritative. Each update is a
single-parent commit containing only the ledger, pushed without force, then
verified against the remote. This module never creates or updates releases or
tags. Legacy schemas are deliberately not upgraded automatically: see the runbook.
"""
from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from urllib.parse import quote

LEDGER_PATH = ".github/telegram-releases.json"
SCHEMA = 3
HEX = re.compile(r"^[0-9a-f]{64}$")
OWNER = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
STATES = {"planned", "uploading", "sending", "unknown", "confirmed"}


class LedgerError(RuntimeError):
    """A ledger operation is unsafe or its durable outcome is unknown."""


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


def parse(data: bytes) -> dict[str, Any]:
    try:
        doc = json.loads(data.decode("utf-8"), object_pairs_hook=_no_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise LedgerError("Remote ledger is unreadable or corrupt; refusing to reset it.") from exc
    validate(doc)
    return doc


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


def validate(doc: object) -> None:
    if not isinstance(doc, dict):
        raise LedgerError("Remote ledger is not a JSON object.")
    if type(doc.get("schema")) is not int or doc.get("schema") != SCHEMA:
        raise LedgerError("Remote ledger is not schema 3; an audited, manual legacy cutover is required before publishing.")
    if set(doc) != {"schema", "activation", "legacy", "identity", "session", "releases"}:
        raise LedgerError("Remote ledger has missing or unexpected top-level fields.")
    activation = doc["activation"]
    if not isinstance(activation, dict) or set(activation) != {"created_after", "legacy_sha256"}:
        raise LedgerError("Ledger activation is invalid.")
    timestamp(activation["created_after"])
    legacy = doc["legacy"]
    validate_legacy(legacy)
    if not isinstance(activation["legacy_sha256"], str) or digest(legacy) != activation["legacy_sha256"]:
        raise LedgerError("Ledger legacy snapshot checksum does not match.")
    identity = doc["identity"]
    if identity is not None and (not isinstance(identity, str) or not HEX.fullmatch(identity)):
        raise LedgerError("Ledger bot/channel identity binding is invalid.")
    session = doc["session"]
    if session is not None and (not isinstance(session, dict) or set(session) != {"owner"} or
                                not isinstance(session["owner"], str) or not OWNER.fullmatch(session["owner"])):
        raise LedgerError("Ledger session lock is invalid.")
    releases = doc["releases"]
    if not isinstance(releases, list):
        raise LedgerError("Ledger releases are invalid.")
    release_ids: set[int] = set()
    release_tags: set[str] = set()
    random_ids: set[int] = set()
    message_ids: set[int] = set()
    for release in releases:
        if not isinstance(release, dict) or set(release) != {"id", "tag", "created_at", "plan", "items"}:
            raise LedgerError("Ledger release entry is invalid.")
        rid = release["id"]
        if type(rid) is not int or rid <= 0 or rid in release_ids:
            raise LedgerError("Ledger contains an invalid or repeated release id.")
        release_ids.add(rid)
        if not isinstance(release["tag"], str) or not release["tag"] or release["tag"] in release_tags:
            raise LedgerError("Ledger release tag is invalid or repeated.")
        release_tags.add(release["tag"])
        if timestamp(release["created_at"]) <= timestamp(activation["created_after"]):
            raise LedgerError("Ledger release predates the audited activation cutoff.")
        if not isinstance(release["plan"], str) or not HEX.fullmatch(release["plan"]):
            raise LedgerError("Ledger release plan hash is invalid.")
        if not isinstance(release["items"], list) or not release["items"]:
            raise LedgerError("Ledger release has no operations.")
        keys: set[str] = set()
        page_count = 0
        saw_asset = False
        unfinished_page = False
        unfinished_asset = False
        for item in release["items"]:
            if not isinstance(item, dict) or set(item) != {"key", "kind", "payload", "random_id", "state", "message_id", "sha256", "attempts"}:
                raise LedgerError("Ledger operation entry is invalid.")
            key, kind = item["key"], item["kind"]
            if (not isinstance(key, str) or key in keys or not isinstance(kind, str) or kind not in {"page", "asset"}
                    or not re.fullmatch(rf"{kind}:[1-9][0-9]*", key)):
                raise LedgerError("Ledger operation key/kind is invalid or repeated.")
            keys.add(key)
            state = item["state"]
            if not isinstance(state, str) or state not in STATES:
                raise LedgerError("Ledger operation state is invalid.")
            if kind == "page":
                page_count += 1
                if saw_asset or key != f"page:{page_count}" or (unfinished_page and state != "planned"):
                    raise LedgerError("Ledger announcement pages are out of order.")
                unfinished_page = unfinished_page or state != "confirmed"
            else:
                saw_asset = True
                if not page_count or (unfinished_page and state != "planned"):
                    raise LedgerError("Ledger asset was attempted before all pages were confirmed.")
                if unfinished_asset and state != "planned":
                    raise LedgerError("Ledger assets are out of order.")
                unfinished_asset = unfinished_asset or state != "confirmed"
            if not isinstance(item["payload"], str) or not HEX.fullmatch(item["payload"]):
                raise LedgerError("Ledger operation payload hash is invalid.")
            random_id = item["random_id"]
            if type(random_id) is not int or random_id == 0 or not -(1 << 63) <= random_id < (1 << 63) or random_id in random_ids:
                raise LedgerError("Ledger operation random_id is invalid or repeated.")
            random_ids.add(random_id)
            if item["state"] not in STATES or type(item["attempts"]) is not int or item["attempts"] < 0:
                raise LedgerError("Ledger operation state/attempt count is invalid.")
            message_id = item["message_id"]
            if item["state"] == "confirmed":
                if type(message_id) is not int or message_id <= 0 or message_id in message_ids:
                    raise LedgerError("Confirmed ledger operation has an invalid or repeated message id.")
                message_ids.add(message_id)
            elif message_id is not None:
                raise LedgerError("Unconfirmed ledger operation has a message id.")
            if item["state"] in {"sending", "unknown", "confirmed"} and item["attempts"] < 1:
                raise LedgerError("A sent operation must have a recorded attempt.")
            if item["state"] in {"planned", "uploading"} and item["attempts"] != 0:
                raise LedgerError("An operation with send attempts cannot be reset to an unsent state.")
            if item["state"] == "uploading" and kind != "asset":
                raise LedgerError("Only an asset can be in the upload phase.")
            sha = item["sha256"]
            if sha is not None and (not isinstance(sha, str) or not HEX.fullmatch(sha)):
                raise LedgerError("Ledger asset SHA-256 is invalid.")
            if kind == "page" and sha is not None:
                raise LedgerError("A text operation cannot have an asset digest.")
            if item["state"] == "planned" and sha is not None:
                raise LedgerError("An unstarted operation cannot have a recorded upload digest.")
            if kind == "asset" and item["state"] in {"uploading", "sending", "unknown", "confirmed"} and sha is None:
                raise LedgerError("An attempted asset has no frozen SHA-256.")
        if page_count == 0:
            raise LedgerError("Ledger release has no announcement page.")
        frozen = [{key: item[key] for key in ("key", "kind", "payload")} for item in release["items"]]
        if digest(frozen) != release["plan"]:
            raise LedgerError("Ledger operation payloads do not match their frozen release plan.")
    if identity is None and any(item["state"] != "planned" for release in releases for item in release["items"]):
        raise LedgerError("Attempted operations require a bound bot/channel identity.")


def require_branch_rules(repository: str, branch: str, *, runner: Callable = subprocess.run) -> None:
    """Require visible active no-force/no-delete rules; this is necessary, not sufficient.

    Metadata-read GitHub rules do not expose every bypass actor. An administrator
    must separately audit bypasses and exclusive writers. All output is captured:
    neither GitHub's error text nor credentials are emitted in diagnostics.
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


def operation_outcome(item: dict[str, Any]) -> str:
    """Classify validated schema-3 state; unsent means *by this protocol* only."""
    if item["state"] == "confirmed":
        return "confirmed_sent"
    if item["state"] in {"planned", "uploading"}:
        return "confirmed_unsent_by_protocol"
    if item["state"] in {"sending", "unknown"}:
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
            "legacy_channel_history": "unknown"}


def migrate(legacy: object, created_after: str) -> dict[str, Any]:
    """Prepare, but do not deploy, a migration. No inference about history."""
    validate_legacy(legacy)
    timestamp(created_after)
    old = copy.deepcopy(legacy)
    doc = {"schema": SCHEMA, "activation": {"created_after": created_after, "legacy_sha256": digest(old)},
           "legacy": old, "identity": None, "session": None, "releases": []}
    validate(doc)
    return doc


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
        result = self._git("show", f"{parent}:{self.path}", check=False)
        if result.returncode:
            raise LedgerError("Remote ledger is missing or inaccessible; refusing to initialise it.")
        return Snapshot(parent, parse(result.stdout), result.stdout)

    def save(self, old: Snapshot, document: dict[str, Any]) -> Snapshot:
        validate(document)
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
        # Even a failed/timed-out push might have been accepted. Never send until
        # a fresh fetch proves this exact commit is an ancestor and bytes match.
        try:
            self._git("push", "origin", f"{commit}:refs/heads/{self.branch}", check=False, timeout=90)
        except LedgerError:
            pass
        current = self.read()
        ancestor = self._git("merge-base", "--is-ancestor", commit, current.parent, check=False)
        if ancestor.returncode or current.raw != body:
            raise LedgerError("Ledger update was rejected, raced, or could not be verified; stopped without assuming success.")
        return current


def main() -> int:
    parser = argparse.ArgumentParser(description="Inspect a ledger or prepare an audited legacy cutover; never deploys it or contacts Telegram.")
    parser.add_argument("--inspect", help="read-only summary of a local ledger snapshot")
    parser.add_argument("--legacy", help="existing legacy schema-1 or schema-2 ledger")
    parser.add_argument("--output", help="new file to inspect; must not already exist")
    parser.add_argument("--created-after", help="future UTC cutoff, e.g. 2027-01-01T00:00:00Z")
    parser.add_argument("--acknowledge-legacy-is-unknown", action="store_true")
    args = parser.parse_args()
    if args.inspect:
        if args.legacy or args.output or args.created_after or args.acknowledge_legacy_is_unknown:
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
    if not (args.legacy and args.output and args.created_after and args.acknowledge_legacy_is_unknown):
        parser.error("migration requires --legacy, --output, --created-after and --acknowledge-legacy-is-unknown")
    try:
        cutoff = timestamp(args.created_after)
        if cutoff <= dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=1):
            raise LedgerError("Choose a cutoff at least one hour in the future and deploy before it.")
        legacy = json.loads(Path(args.legacy).read_text(encoding="utf-8"), object_pairs_hook=_no_duplicates)
        document = migrate(legacy, args.created_after)
        with open(args.output, "xb") as handle:
            handle.write(canonical(document))
    except LedgerError as exc:
        print(f"Migration preparation failed: {exc}")
        return 1
    except (OSError, json.JSONDecodeError):
        print("Migration preparation failed: input/output could not be read or created.")
        return 1
    print("Prepared a migration for review. It is not deployed and proves nothing about older channel posts.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
