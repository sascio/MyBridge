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


def tag_ref_snapshot(refs: list[Any], token: str) -> str:
    """HMAC exact tag names, object kinds and SHAs, including non-public names."""
    if not isinstance(refs, list):
        raise CutoverError("The tag ref snapshot is unavailable.")
    records: list[dict[str, str]] = []
    seen: set[str] = set()
    for item in refs:
        if not isinstance(item, dict) or not isinstance(item.get("ref"), str) or not item["ref"].startswith("refs/tags/"):
            raise CutoverError("A tag ref is malformed.")
        tag, obj = item["ref"][len("refs/tags/"):], item.get("object")
        if (not tag or any(char in tag for char in "\x00\r\n") or tag in seen or not isinstance(obj, dict)
                or obj.get("type") not in {"commit", "tag"} or not isinstance(obj.get("sha"), str)
                or not ledger.COMMIT_SHA.fullmatch(obj["sha"])):
            raise CutoverError("A tag ref has an invalid or repeated object identity.")
        seen.add(tag)
        records.append({"tag": tag, "type": obj["type"], "sha": obj["sha"]})
    records.sort(key=lambda record: record["tag"])
    return ledger._inventory_mac(token, "tag-ref-snapshot", ledger.canonical(records).decode("utf-8"))


def build_snapshot(releases: list[Any], refs: list[Any], legacy: object, cutoff: str, token: str,
                   *, resolve: Callable[[str], str], now: dt.datetime,
                   observed_old_tags: set[str] | None = None,
                   observed_old_release_ids: set[int] | None = None) -> dict[str, Any]:
    """Build a protected exclusion snapshot, never a queue of releases to send."""
    fence = ledger.timestamp(cutoff)
    if fence < now + dt.timedelta(hours=1):
        raise CutoverError("The reviewed cutoff must be at least one hour in the future.")
    ledger.validate_legacy(legacy)
    if legacy["releases"]:
        raise CutoverError("Non-empty legacy records require a separately reviewed manual migration.")
    if not isinstance(releases, list) or not isinstance(refs, list):
        raise CutoverError("The release or tag inventory is unavailable.")
    refs_mac = tag_ref_snapshot(refs, token)
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
    # Activity may reveal recently deleted tags. Keep these as protected
    # exclusions; the API is bounded and is not a complete historical log.
    if observed_old_tags is not None:
        if not isinstance(observed_old_tags, set):
            raise CutoverError("Historical tag activity is invalid.")
        for tag in observed_old_tags:
            identity.validate_tag(tag)
        tags.update(observed_old_tags)
    if observed_old_release_ids is not None:
        if (not isinstance(observed_old_release_ids, set)
                or any(type(rid) is not int or rid <= 0 for rid in observed_old_release_ids)):
            raise CutoverError("Historical release activity is invalid.")
        ids = sorted(set(ids) | observed_old_release_ids)
    # Even if a draft's tag happens to be a visible Git ref, blind its name in
    # the state ledger unless it is also an independently published release tag.
    public_release_tags = {entry["tag"] for entry in exclusions}
    public_tags -= draft_tags - public_release_tags
    raw = {"release_ids": ids, "tags": sorted(tags), "message_ids": [ledger.KNOWN_MESSAGE_ID],
           "exclusions": sorted(exclusions, key=lambda entry: entry["release_id"])}
    protected = ledger.protect_inventory(raw, public_ids, public_tags, token)
    protected["protected"]["tag_ref_snapshot"] = refs_mac
    ledger.require_reviewed_history(protected)
    doc = ledger.migrate(legacy, cutoff, protected)
    if token.encode("utf-8") in ledger.canonical(doc):
        raise CutoverError("A credential appeared in the candidate ledger; initialization is blocked.")
    return doc


def collect(repository: str, legacy: object, cutoff: str, token: str, *,
            include_activity: bool = False) -> dict[str, Any]:
    """Capture API responses in memory only; never print private draft metadata."""
    releases = state._api(f"repos/{repository}/releases?per_page=100", paginate=True)
    refs = state._api(f"repos/{repository}/git/matching-refs/tags/?per_page=100", paginate=True)
    old_tags: set[str] = set()
    old_ids: set[int] = set()
    if include_activity:
        events = state._api(f"repos/{repository}/events?per_page=100", paginate=True)
        if not isinstance(events, list):
            raise CutoverError("Historical tag activity is unavailable.")
        for event in events:
            if not isinstance(event, dict) or event.get("type") not in {"CreateEvent", "DeleteEvent", "ReleaseEvent"}:
                continue
            payload, repo = event.get("payload"), event.get("repo")
            if not isinstance(payload, dict):
                raise CutoverError("Historical release/tag activity is malformed.")
            if event["type"] == "ReleaseEvent" or payload.get("ref_type") == "tag":
                if not isinstance(repo, dict) or repo.get("name") != repository:
                    raise CutoverError("Historical activity has the wrong repository.")
                if event["type"] == "ReleaseEvent":
                    release = payload.get("release")
                    if not isinstance(release, dict) or type(release.get("id")) is not int or release["id"] <= 0:
                        raise CutoverError("Historical release activity is malformed.")
                    old_ids.add(release["id"])
                    old_tags.add(identity.validate_tag(release.get("tag_name")))
                else:
                    old_tags.add(identity.validate_tag(payload.get("ref")))
    doc = build_snapshot(releases, refs, legacy, cutoff, token,
                         resolve=lambda tag: identity.resolve_tag_commit(repository, tag),
                         now=dt.datetime.now(dt.timezone.utc), observed_old_tags=old_tags,
                         observed_old_release_ids=old_ids)
    refs_after = state._api(f"repos/{repository}/git/matching-refs/tags/?per_page=100", paginate=True)
    if tag_ref_snapshot(refs_after, token) != doc["inventory"]["protected"]["tag_ref_snapshot"]:
        raise CutoverError("Tag ref objects changed during the inventory snapshot.")
    return doc


def preview_fingerprint(doc: dict[str, Any], state_parent: str | None, default_parent: str,
                        tooling_commit: str) -> str:
    """Bind protected candidate data to the exact reviewed refs and tooling."""
    ledger.validate(doc)
    if ((state_parent is not None and (not isinstance(state_parent, str) or not ledger.COMMIT_SHA.fullmatch(state_parent)))
            or not isinstance(default_parent, str) or not ledger.COMMIT_SHA.fullmatch(default_parent)
            or not isinstance(tooling_commit, str) or not ledger.COMMIT_SHA.fullmatch(tooling_commit)):
        raise CutoverError("The preview branch or tooling identities are invalid.")
    return ledger.digest({"candidate": doc, "state_parent": state_parent,
                          "default_parent": default_parent, "tooling_commit": tooling_commit})


def remote_head(store: ledger.GitLedger, branch: str) -> str | None:
    """Read exactly one remote branch ref without modifying it."""
    ref = f"refs/heads/{branch}"
    probe = store._git("ls-remote", "--heads", "origin", ref, check=False)
    if probe.returncode:
        raise CutoverError("The remote branch could not be verified.")
    lines = probe.stdout.decode("ascii").splitlines()
    if not lines:
        return None
    fields = lines[0].split()
    if len(lines) != 1 or len(fields) != 2 or fields[1] != ref or not ledger.COMMIT_SHA.fullmatch(fields[0]):
        raise CutoverError("The remote branch identity is inconsistent.")
    return fields[0]


def initialize(store: ledger.GitLedger, doc: dict[str, Any], *, expected_parent: str | None,
               default_branch: str, expected_main: str, bootstrap_base: str | None = None) -> str:
    """Create a verified single-file child; never overwrite an advanced state ref."""
    ledger.validate(doc)
    ref = f"refs/heads/{store.branch}"
    parent = remote_head(store, store.branch)
    main = remote_head(store, default_branch)
    if (parent != expected_parent or main is None or main != expected_main or default_branch == store.branch):
        raise CutoverError("The state or default branch changed since the reviewed preview.")
    if parent is not None:
        # Ordinarily require the unchanged default tip. Automatic bootstrap may
        # instead use the explicitly pinned, pre-created state base after the
        # PR has merged, but only if that exact base is an ancestor of main.
        # No force or history rewrite is used, and a moved state ref is refused.
        if parent != main:
            if (not bootstrap_base or parent != bootstrap_base
                    or not ledger.COMMIT_SHA.fullmatch(bootstrap_base)):
                raise CutoverError("The existing state ref is not the unchanged default-branch tip.")
            store._git("fetch", "--no-tags", "origin", f"refs/heads/{default_branch}")
            if store._git("merge-base", "--is-ancestor", parent, main, check=False).returncode != 0:
                raise CutoverError("The pinned bootstrap state base is not an ancestor of main.")
            main_legacy = store._git("show", f"{main}:{ledger.LEGACY_PATH}", check=False)
            if main_legacy.returncode or ledger._json_input(main_legacy.stdout) != doc["legacy"]:
                raise CutoverError("The default branch legacy ledger changed since the pinned state base.")
        store._git("fetch", "--no-tags", "origin", ref)
        fetched = store._git("rev-parse", "FETCH_HEAD").stdout.decode("ascii").strip()
        old = store._git("show", f"{parent}:{ledger.LEGACY_PATH}", check=False)
        if fetched != parent or old.returncode or ledger._json_input(old.stdout) != doc["legacy"]:
            raise CutoverError("The existing state ref or preserved legacy ledger is inconsistent.")
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
        parent_args = ["-p", parent] if parent else []
        commit = store._git("commit-tree", tree, *parent_args, env=env,
                            data=b"chore(telegram): initialize reviewed delivery ledger [skip ci]\n").stdout.decode("ascii").strip()
    # Recheck immediately before the push. Git also guards the state ref with a
    # non-force fast-forward check. The two refs cannot be checked atomically,
    # so the caller rechecks the default tip after the push as well.
    if remote_head(store, default_branch) != main or remote_head(store, store.branch) != parent:
        raise CutoverError("A reviewed branch changed before initialization; no state push was attempted.")
    # A failed push may have succeeded. Only the exact commit, parent and bytes
    # count as success; never force, reset, replace, or delete a remote ref.
    try:
        store._git("push", "origin", f"{commit}:{ref}", check=False)
    except ledger.LedgerError:
        pass
    snapshot = store.read()
    parents = store._git("rev-list", "--parents", "-n", "1", snapshot.parent).stdout.decode("ascii").split()
    expected_parents = [commit, parent] if parent else [commit]
    if snapshot.parent != commit or snapshot.raw != body or parents != expected_parents:
        raise CutoverError("The initialized state commit could not be confirmed; leave publishing disabled.")
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
                or repo != "sascio/MyBridge" or repo_id != "1349123452" or not os.environ.get("GH_TOKEN")):
            raise CutoverError("The explicit same-repository Actions dispatch is required.")
        if args.mode == "initialize" and args.acknowledge != ACKNOWLEDGEMENT:
            raise CutoverError("Initialization requires the independent freeze and history acknowledgement.")
        _require_disabled_gates()
        token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
        with open(ledger.LEGACY_PATH, "rb") as handle:
            legacy = ledger._json_input(handle.read())
        legacy_workflow = state._api(f"repos/{repo}/actions/workflows/407947021")
        if (not isinstance(legacy_workflow, dict) or legacy_workflow.get("state") != "disabled_manually"
                or legacy_workflow.get("path") != ".github/workflows/telegram-release-announcement.yml"):
            raise CutoverError("The legacy workflow is not confirmed manually disabled.")
        metadata = state._api(f"repos/{repo}")
        if (not isinstance(metadata, dict) or metadata.get("id") != int(repo_id)
                or metadata.get("full_name") != repo or not isinstance(metadata.get("default_branch"), str)
                or not state.BRANCH.fullmatch(metadata["default_branch"])):
            raise CutoverError("The source repository or default branch identity is inconsistent.")
        default_branch = metadata["default_branch"]
        store = ledger.GitLedger(args.source_dir, state.STATE_BRANCH)
        tooling_commit = store._git("rev-parse", "--verify", "HEAD^{commit}").stdout.decode("ascii").strip()
        if not ledger.COMMIT_SHA.fullmatch(tooling_commit) or tooling_commit != os.environ.get("GITHUB_SHA"):
            raise CutoverError("The reviewed workflow and checked-out tooling commit do not match.")
        state_parent = remote_head(store, state.STATE_BRANCH)
        main_parent = remote_head(store, default_branch)
        if main_parent is None:
            raise CutoverError("The source default branch could not be verified.")
        doc = collect(repo, legacy, args.cutoff, token)
        # Bind the protected candidate, both ref tips and exact workflow code to
        # the reviewed preview. Never digest or print raw private inventory.
        fingerprint = preview_fingerprint(doc, state_parent, main_parent, tooling_commit)
        protected = doc["inventory"].get("protected", {})
        summary = {"mode": args.mode, "candidate_cutoff": args.cutoff, "protected_fingerprint": fingerprint,
                   "state_parent": state_parent, "default_parent": main_parent, "tooling_commit": tooling_commit,
                   "public_exclusions": len(doc["inventory"].get("exclusions", [])),
                   "protected_release_ids": len(protected.get("release_id_digests", [])),
                   "total_tags": len(doc["inventory"]["tags"]) + len(protected.get("tag_digests", []))}
        if args.mode == "preview":
            if args.fingerprint:
                raise CutoverError("A preview does not accept an initialization fingerprint.")
            preview = json.dumps(summary, sort_keys=True)
            print(preview)
            print("::notice title=Unapproved Telegram history preview::" + preview)
            summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
            if summary_path:
                with open(summary_path, "a", encoding="utf-8") as handle:
                    handle.write("### Unapproved Telegram history preview\n\n```json\n" + preview + "\n```\n")
            print("Unapproved preview only: no state ref was created or modified. Freeze and independent owner review remain mandatory.")
            return 0
        if not re.fullmatch(r"[0-9a-f]{64}", args.fingerprint) or args.fingerprint != fingerprint:
            raise CutoverError("The current protected inventory does not match the owner-reviewed preview.")
        config = state.SameRepositoryConfig(repo, int(repo_id))
        state.verify_bootstrap_rules(config, args.source_dir, repo)
        head = initialize(store, doc, expected_parent=state_parent, default_branch=default_branch,
                          expected_main=main_parent)
        state.verify_state(config, args.source_dir, repo)
        final_state = store.read()
        if (ledger.digest(collect(repo, legacy, args.cutoff, token)) != ledger.digest(doc)
                or remote_head(store, default_branch) != main_parent or final_state.parent != head
                or final_state.raw != ledger.canonical(doc)):
            raise CutoverError("The inventory or branch tips changed during initialization; leave publishing disabled.")
        summary["state_head"] = head
        report = json.dumps(summary, sort_keys=True)
        print(report)
        print("::notice title=Telegram historical state initialized::" + report)
        summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary_path:
            with open(summary_path, "a", encoding="utf-8") as handle:
                handle.write("### Telegram historical state initialized\n\n```json\n" + report + "\n```\n")
        print("The single-file state tip was initialized; live write/race verification and owner bypass review remain mandatory.")
        return 0
    except Exception:  # do not print API payloads, credentials, private draft fields, or remote errors
        print("::error::Historical cutover failed closed; review the freeze, inventory, branch rules and runbook. Publishing remains disabled.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
