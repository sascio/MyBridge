#!/usr/bin/env python3
"""Owner-triggered, state-only verification. Never calls Telegram or touches releases."""
from __future__ import annotations

import argparse
import concurrent.futures
import copy
import json
import os
import re
import threading
import uuid
from typing import Any, Callable

import telegram_ledger as ledger
import telegram_state as state


class VerificationError(RuntimeError):
    pass


def _marker(run_id: int, phase: str) -> dict[str, Any]:
    return {"id": uuid.uuid4().hex, "run_id": run_id, "phase": phase, "status": "active"}


def _append(document: dict[str, Any], marker: dict[str, Any]) -> None:
    if document["session"] is not None:
        raise VerificationError("A delivery session is locked; state-only verification is blocked.")
    if any(item["id"] == marker["id"] for item in document["verification"]):
        raise VerificationError("A verification marker already exists.")
    document["verification"].append(copy.deepcopy(marker))


def _contains(document: dict[str, Any], markers: list[dict[str, Any]]) -> bool:
    return all(marker in document["verification"] for marker in markers)


def _direct_parent(store: ledger.GitLedger, previous: str, current: str) -> bool:
    result = store._git("rev-list", "--parents", "-n", "1", current)
    return result.stdout.decode("ascii").strip().split() == [current, previous]


class _RacingStore(ledger.GitLedger):
    """Only the first attempt is synchronized; a rejected update must re-read."""
    def __init__(self, repo: str, branch: str, read_lock: threading.Lock,
                 read_barrier: threading.Barrier, push_barrier: threading.Barrier,
                 finish_barrier: threading.Barrier) -> None:
        super().__init__(repo, branch)
        self.read_lock, self.read_barrier = read_lock, read_barrier
        self.push_barrier, self.finish_barrier = push_barrier, finish_barrier
        self.initial_read, self.initial_push, self.initial_save = True, True, True
        self.conflicts = 0

    def read(self) -> ledger.Snapshot:
        # FETCH_HEAD is shared by these two independent publishers in this
        # checkout. Serialize the fetch, not the push, and then synchronize.
        with self.read_lock:
            snapshot = super().read()
        if self.initial_read:
            self.initial_read = False
            self.read_barrier.wait(timeout=60)
        return snapshot

    def _git(self, *args: str, **kwargs: Any):
        if args and args[0] == "push" and self.initial_push:
            self.initial_push = False
            self.push_barrier.wait(timeout=60)
        return super()._git(*args, **kwargs)

    def save(self, old: ledger.Snapshot, document: dict[str, Any]) -> ledger.Snapshot:
        initial = self.initial_save
        self.initial_save = False
        try:
            return super().save(old, document)
        except ledger.LedgerConflict:
            self.conflicts += 1
            raise
        finally:
            # Ensure both first attempts observe the same final race before a
            # loser retries and advances the ref again.
            if initial:
                self.finish_barrier.wait(timeout=60)


def verify(store: ledger.GitLedger, run_id: int, *, policy_check: Callable[[], None]) -> dict[str, Any]:
    """Exercise remote reads, exact writes, concurrent CAS and bounded reapplication."""
    if type(run_id) is not int or run_id <= 0:
        raise VerificationError("A positive workflow run ID is required.")
    policy_check()
    initial = store.read()
    initial_doc = copy.deepcopy(initial.document)
    if initial_doc["session"] is not None or any(r["status"] == "active" for r in initial_doc["verification"]):
        raise VerificationError("A session or an earlier unfinished verification blocks this check.")
    markers = [_marker(run_id, phase) for phase in ("first", "second", "race-a", "race-b")]
    policy_check()
    first, _ = ledger.update(store, lambda doc: _append(doc, markers[0]))
    observed = store.read()
    if (observed.parent != first.parent or observed.raw != first.raw or not _contains(observed.document, markers[:1])
            or not _direct_parent(store, initial.parent, first.parent)):
        raise VerificationError("First remote write, byte readback or fast-forward verification failed.")
    policy_check()
    second, _ = ledger.update(store, lambda doc: _append(doc, markers[1]))
    observed = store.read()
    if (observed.parent != second.parent or observed.raw != second.raw or not _contains(observed.document, markers[:2])
            or not _direct_parent(store, first.parent, second.parent)):
        raise VerificationError("Second remote write did not preserve the first record or fast-forward.")
    policy_check()
    lock = threading.Lock()
    read_barrier, push_barrier, finish_barrier = (threading.Barrier(2) for _ in range(3))
    racers = [_RacingStore(store.repo, store.branch, lock, read_barrier, push_barrier, finish_barrier) for _ in range(2)]
    def attempt(index: int) -> None:
        ledger.update(racers[index], lambda doc: _append(doc, markers[index + 2]))
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(attempt, index) for index in range(2)]
        for future in futures:
            future.result(timeout=180)
    raced = store.read()
    if (sum(worker.conflicts for worker in racers) != 1 or not _contains(raced.document, markers)
            or store._git("rev-list", "--count", f"{second.parent}..{raced.parent}").stdout.strip() != b"2"
            or store._git("merge-base", "--is-ancestor", second.parent, raced.parent, check=False).returncode != 0):
        raise VerificationError("Concurrent updates or conflict recovery could not be verified.")
    # Verification is non-delivery data. Archive, never delete or alter release
    # records; the branch history retains every test commit.
    ids = {marker["id"] for marker in markers}
    def archive(document: dict[str, Any]) -> None:
        if document["session"] is not None or not _contains(document, markers):
            raise VerificationError("Verification records changed before archival.")
        for record in document["verification"]:
            if record["id"] in ids:
                record["status"] = "archived"
    policy_check()
    archived, _ = ledger.update(store, archive)
    final = store.read()
    if (final.parent != archived.parent or final.raw != archived.raw or not _direct_parent(store, raced.parent, final.parent)
            or any(record["status"] != "archived" for record in final.document["verification"] if record["id"] in ids)
            or any(final.document[key] != initial_doc[key] for key in
                   ("activation", "legacy", "inventory", "identity", "session", "releases"))):
        raise VerificationError("Verification archival or preservation checks failed.")
    return {"head": final.parent, "records_archived": len(ids), "definite_conflicts_recovered": 1}


def archive_run(store: ledger.GitLedger, run_id: int, *, policy_check: Callable[[], None]) -> int:
    """Archive only this run's active verification records after a failed test."""
    if type(run_id) is not int or run_id <= 0:
        raise VerificationError("A positive verification run ID is required.")
    policy_check()
    def archive(document: dict[str, Any]) -> int:
        if document["session"] is not None:
            raise VerificationError("A delivery session is locked; cleanup is blocked.")
        records = [r for r in document["verification"] if r["run_id"] == run_id and r["status"] == "active"]
        if not records:
            raise VerificationError("That run has no active verification records to archive.")
        for record in records:
            record["status"] = "archived"
        return len(records)
    _, count = ledger.update(store, archive)
    return count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", required=True)
    parser.add_argument("--archive-run", default="")
    args = parser.parse_args()
    try:
        repo, repository_id = os.environ.get("GITHUB_REPOSITORY"), os.environ.get("GITHUB_REPOSITORY_ID")
        run_id, mode = os.environ.get("GITHUB_RUN_ID", ""), os.environ.get("STATE_VERIFICATION_REQUESTED", "")
        if (os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
                or mode != "true" or repo != "sascio/MyBridge" or repository_id != "1349123452"
                or not re.fullmatch(r"[1-9][0-9]*", run_id) or not os.environ.get("GH_TOKEN")
                or os.environ.get("TELEGRAM_PUBLISHING_ENABLED") == "true"):
            raise VerificationError("State verification requires the explicit, publishing-disabled Actions dispatch.")
        for path in (ledger.PUBLISHING_CONFIG_PATH, state.CONFIG_PATH):
            with open(path, encoding="utf-8") as handle:
                gate = json.load(handle, object_pairs_hook=ledger._no_duplicates)
                if not isinstance(gate, dict) or set(gate) != {"enabled"} or gate["enabled"] is not False:
                    raise VerificationError("Tracked publishing and state gates must both remain disabled.")
        token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
        config = state.SameRepositoryConfig(repo, int(repository_id))
        policy = lambda: state.verify_state(config, args.state_dir, repo)
        store = ledger.GitLedger(args.state_dir, state.STATE_BRANCH)
        policy()
        snapshot = store.read()
        ledger.require_inventory_key(snapshot.document["inventory"], token, required=True)
        ledger.require_reviewed_history(snapshot.document["inventory"])
        if args.archive_run:
            if not re.fullmatch(r"[1-9][0-9]*", args.archive_run):
                raise VerificationError("Cleanup requires an exact positive verification run ID.")
            count = archive_run(store, int(args.archive_run), policy_check=policy)
            print(f"Archived {count} non-delivery records. This cleanup is not a verification pass.")
        else:
            result = verify(store, int(run_id), policy_check=policy)
            print(json.dumps({"status": "state_only_verified", **result}, sort_keys=True))
            print("No Telegram API calls, messages, uploads, releases or tags were made.")
            print("Owner must separately inspect every ruleset bypass actor; no destructive rule test was performed.")
    except Exception:  # never print remote errors, private inventory, or secrets
        print("::error::State-only verification failed; inspect branch rules, ledger and owner runbook. No success is assumed.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
