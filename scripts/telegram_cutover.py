#!/usr/bin/env python3
"""Prepare or owner-initialize a frozen historical ledger without contacting Telegram."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import tempfile
from typing import Any, Callable

import telegram_identity as identity
import telegram_ledger as ledger
import telegram_state as state


class CutoverError(RuntimeError):
    pass


ACKNOWLEDGEMENT = "RELEASES_AND_TAGS_FROZEN_AND_REVIEWED"


def build_snapshot(releases: list[Any], refs: list[Any], legacy: object, cutoff: str, token: str,
                   *, resolve: Callable[[str], str], now: dt.datetime) -> dict[str, Any]:
    """Build a protected *candidate*; a human freeze/review is still mandatory."""
    fence = ledger.timestamp(cutoff)
    if fence < now + dt.timedelta(hours=1):
        raise CutoverError("The reviewed cutoff must be at least one hour in the future.")
    ledger.validate_legacy(legacy)
    if legacy["releases"]:
        raise CutoverError("Non-empty legacy records require a separately reviewed manual migration.")
    if not isinstance(releases, list) or not isinstance(refs, list):
        raise CutoverError("The release or tag inventory is unavailable.")
    ids: list[int] = []
    tags: set[str] = set()
    public_ids: set[int] = set()
    public_tags: set[str] = set()
    draft_tags: set[str] = set()
    exclusions: list[dict[str, Any]] = []
    for item in refs:
        if not isinstance(item, dict) or not isinstance(item.get("ref"), str) or not item["ref"].startswith("refs/tags/"):
            raise CutoverError("A tag ref is malformed.")
        tag = item["ref"][len("refs/tags/"):]
        if not tag or tag in tags:
            raise CutoverError("Tag refs are empty or repeated.")
        tags.add(tag)
        public_tags.add(tag)  # Git refs in this public repository are public.
    for release in releases:
        if not isinstance(release, dict):
            raise CutoverError("A release inventory item is malformed.")
        rid, tag, draft = release.get("id"), release.get("tag_name"), release.get("draft")
        if type(rid) is not int or rid <= 0 or rid in ids or not isinstance(tag, str) or not tag or type(draft) is not bool:
            raise CutoverError("A release identity is invalid or repeated.")
        ids.append(rid)
        tags.add(tag)
        if draft:
            draft_tags.add(tag)
            continue
        published_at = release.get("published_at")
        published = ledger.timestamp(published_at)
        if published > now + dt.timedelta(minutes=5) or published >= fence:
            raise CutoverError("A published release is outside the frozen historical cutoff.")
        identity.validate_tag(tag)
        sha = resolve(tag)
        if not isinstance(sha, str) or not ledger.COMMIT_SHA.fullmatch(sha):
            raise CutoverError("A public historical tag does not resolve to an immutable commit.")
        public_ids.add(rid)
        public_tags.add(tag)
        confirmed = rid == ledger.KNOWN_RELEASE_ID and tag == ledger.KNOWN_RELEASE_TAG
        exclusions.append({"release_id": rid, "tag": tag, "source_sha": sha, "published_at": published_at,
                           "status": "historical_excluded", "reason": ledger.HISTORICAL_REASON,
                           "message_id": ledger.KNOWN_MESSAGE_ID if confirmed else None,
                           "evidence": ledger.KNOWN_EVIDENCE if confirmed else None, "delivered_at": None})
    # Even if a draft's tag happens to be a visible Git ref, blind its name in
    # the state ledger unless it is also an independently published release tag.
    public_release_tags = {entry["tag"] for entry in exclusions}
    public_tags -= draft_tags - public_release_tags
    raw = {"release_ids": ids, "tags": sorted(tags), "message_ids": [ledger.KNOWN_MESSAGE_ID],
           "exclusions": sorted(exclusions, key=lambda entry: entry["release_id"])}
    protected = ledger.protect_inventory(raw, public_ids, public_tags, token)
    ledger.require_reviewed_history(protected)
    doc = ledger.migrate(legacy, cutoff, protected)
    if token.encode("utf-8") in ledger.canonical(doc):
        raise CutoverError("A credential appeared in the candidate ledger; initialization is blocked.")
    return doc


def collect(repository: str, legacy: object, cutoff: str, token: str) -> dict[str, Any]:
    """Capture API responses in memory only; never print private draft metadata."""
    releases = state._api(f"repos/{repository}/releases?per_page=100", paginate=True)
    refs = state._api(f"repos/{repository}/git/matching-refs/tags/?per_page=100", paginate=True)
    return build_snapshot(releases, refs, legacy, cutoff, token,
                          resolve=lambda tag: identity.resolve_tag_commit(repository, tag),
                          now=dt.datetime.now(dt.timezone.utc))


def initialize(store: ledger.GitLedger, doc: dict[str, Any]) -> str:
    """Create a single-file orphan ref, never overwriting an existing state branch."""
    ledger.validate(doc)
    ref = f"refs/heads/{store.branch}"
    probe = store._git("ls-remote", "--heads", "origin", ref, check=False)
    if probe.returncode != 0 or probe.stdout.strip():
        raise CutoverError("The state ref already exists or its absence cannot be verified.")
    body = ledger.canonical(doc)
    blob = store._git("hash-object", "-w", "--stdin", data=body).stdout.decode("ascii").strip()
    with tempfile.TemporaryDirectory(prefix="telegram-state-init-") as tmp:
        env = os.environ.copy()
        env.update({"GIT_INDEX_FILE": os.path.join(tmp, "index"),
                    "GIT_AUTHOR_NAME": "github-actions[bot]",
                    "GIT_AUTHOR_EMAIL": "41898282+github-actions[bot]@users.noreply.github.com",
                    "GIT_COMMITTER_NAME": "github-actions[bot]",
                    "GIT_COMMITTER_EMAIL": "41898282+github-actions[bot]@users.noreply.github.com"})
        store._git("read-tree", "--empty", env=env)
        store._git("update-index", "--add", "--cacheinfo", f"100644,{blob},{ledger.LEDGER_PATH}", env=env)
        tree = store._git("write-tree", env=env).stdout.decode("ascii").strip()
        commit = store._git("commit-tree", tree, env=env,
                            data=b"chore(telegram): initialize reviewed delivery ledger [skip ci]\n").stdout.decode("ascii").strip()
    # A failed push may have succeeded. Only the exact new root and exact bytes
    # count as success; never force, reset, replace, or delete a remote ref.
    try:
        store._git("push", "origin", f"{commit}:{ref}", check=False)
    except ledger.LedgerError:
        pass
    snapshot = store.read()
    parents = store._git("rev-list", "--parents", "-n", "1", snapshot.parent).stdout.decode("ascii").split()
    if snapshot.parent != commit or snapshot.raw != body or parents != [commit]:
        raise CutoverError("The new state root could not be confirmed; leave publishing disabled.")
    return commit


def _require_disabled_gates() -> None:
    if os.environ.get("TELEGRAM_PUBLISHING_ENABLED") == "true":
        raise CutoverError("Publishing must be disabled during historical cutover.")
    for path in (ledger.PUBLISHING_CONFIG_PATH, state.CONFIG_PATH):
        with open(path, encoding="utf-8") as handle:
            gate = json.load(handle, object_pairs_hook=ledger._no_duplicates)
            if not isinstance(gate, dict) or set(gate) != {"enabled"} or gate["enabled"] is not False:
                raise CutoverError("Both tracked activation gates must remain disabled.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("preview", "initialize"), required=True)
    parser.add_argument("--cutoff", required=True)
    parser.add_argument("--fingerprint", default="")
    parser.add_argument("--acknowledge", required=True)
    parser.add_argument("--source-dir", required=True)
    args = parser.parse_args()
    try:
        repo = os.environ.get("GITHUB_REPOSITORY", "")
        repo_id = os.environ.get("GITHUB_REPOSITORY_ID", "")
        if (os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
                or repo != "sascio/MyBridge" or repo_id != "1349123452" or not os.environ.get("GH_TOKEN")
                or args.acknowledge != ACKNOWLEDGEMENT):
            raise CutoverError("Explicit owner-reviewed Actions cutover and freeze acknowledgement are required.")
        _require_disabled_gates()
        token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
        with open(ledger.LEGACY_PATH, "rb") as handle:
            legacy = ledger._json_input(handle.read())
        legacy_workflow = state._api(f"repos/{repo}/actions/workflows/407947021")
        if (not isinstance(legacy_workflow, dict) or legacy_workflow.get("state") != "disabled_manually"
                or legacy_workflow.get("path") != ".github/workflows/telegram-release-announcement.yml"):
            raise CutoverError("The legacy workflow is not confirmed manually disabled.")
        doc = collect(repo, legacy, args.cutoff, token)
        fingerprint = ledger.digest(doc)  # digest of protected data, never raw private inventory
        protected = doc["inventory"].get("protected", {})
        summary = {"mode": args.mode, "candidate_cutoff": args.cutoff, "protected_fingerprint": fingerprint,
                   "public_exclusions": len(doc["inventory"].get("exclusions", [])),
                   "protected_release_ids": len(protected.get("release_id_digests", [])),
                   "total_tags": len(doc["inventory"]["tags"]) + len(protected.get("tag_digests", []))}
        if args.mode == "preview":
            if args.fingerprint:
                raise CutoverError("A preview does not accept an initialization fingerprint.")
            print(json.dumps(summary, sort_keys=True))
            print("Preview only: no state ref was created. Freeze and independent owner review remain mandatory.")
            return 0
        if not re.fullmatch(r"[0-9a-f]{64}", args.fingerprint) or args.fingerprint != fingerprint:
            raise CutoverError("The current protected inventory does not match the owner-reviewed preview.")
        config = state.SameRepositoryConfig(repo, int(repo_id))
        state.verify_bootstrap_rules(config, args.source_dir, repo)
        store = ledger.GitLedger(args.source_dir, state.STATE_BRANCH)
        head = initialize(store, doc)
        state.verify_state(config, args.source_dir, repo)
        if ledger.digest(collect(repo, legacy, args.cutoff, token)) != fingerprint:
            raise CutoverError("The frozen inventory changed during initialization; leave publishing disabled.")
        summary["state_head"] = head
        print(json.dumps(summary, sort_keys=True))
        print("The source-free state root was initialized; live write/race verification and owner bypass review remain mandatory.")
        return 0
    except Exception:  # do not print API payloads, credentials, private draft fields, or remote errors
        print("::error::Historical cutover failed closed; review the freeze, inventory, branch rules and runbook. Publishing remains disabled.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
