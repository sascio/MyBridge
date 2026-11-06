#!/usr/bin/env python3
"""Reconcile a known response from a trusted Actions job log; never send to Telegram."""
from __future__ import annotations

import argparse
import copy
import datetime as dt
import json
import os
import re
import subprocess
from typing import Any, Callable

import telegram_identity as identity
import telegram_ledger as ledger
import telegram_state as state


class ReconciliationError(RuntimeError):
    pass


ACKNOWLEDGEMENT = "VERIFIED_TRUSTED_RUN_AND_RESPONSE"
JOB_NAME = "Deliver with durable bot-only MTProto state"
WORKFLOW_PATH = ".github/workflows/telegram-release-delivery.yml"
OPERATION = re.compile(r"^(?:page|asset):[1-9][0-9]*$")


def validate_request(request: object) -> dict[str, Any]:
    fields = {"release_id", "tag", "operation", "message_id", "job_id", "acknowledge"}
    if not isinstance(request, dict) or set(request) != fields:
        raise ReconciliationError("An exact reconciliation request is required.")
    for field in ("release_id", "message_id", "job_id"):
        if type(request[field]) is not int or request[field] <= 0:
            raise ReconciliationError("Reconciliation identifiers must be positive integers.")
    try:
        identity.validate_tag(request["tag"])
    except identity.IdentityError as exc:
        raise ReconciliationError("Reconciliation requires the exact release tag.") from exc
    if (not isinstance(request["operation"], str) or not OPERATION.fullmatch(request["operation"])
            or request["acknowledge"] != ACKNOWLEDGEMENT):
        raise ReconciliationError("The exact operation and reviewed-response acknowledgement are required.")
    return request


def _get_log(repository: str, job_id: int, *, runner: Callable = subprocess.run) -> bytes:
    try:
        result = runner(["gh", "api", f"repos/{repository}/actions/jobs/{job_id}/logs"],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ReconciliationError("The trusted Actions job log is unavailable.") from exc
    if result.returncode or len(result.stdout) > 16 * 1024 * 1024:
        raise ReconciliationError("The trusted Actions job log is unavailable or too large.")
    return result.stdout


def confirmed_time(log: bytes, request: dict[str, Any], item: dict[str, Any]) -> str:
    """Match one marker emitted only after the transport checked the Telegram response."""
    pieces = (f"TELEGRAM_CONFIRMED_RESPONSE run_id={item['workflow_run_id']} run_attempt={item['run_attempt']} "
              f"release_id={request['release_id']} operation={request['operation']} "
              f"operation_id={item['random_id']} message_id={request['message_id']} confirmed_at=")
    pattern = re.compile(re.escape(pieces).encode() +
                         rb"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z) " +
                         re.escape(f"tooling_sha={item['tooling_sha']}".encode()) + rb"(?=\s|$)")
    matches = pattern.findall(log)
    if len(matches) != 1:
        raise ReconciliationError("A unique matching confirmed-response marker is unavailable.")
    stamp = matches[0].decode("ascii")
    confirmed = ledger.timestamp(stamp)
    if confirmed < ledger.timestamp(item["attempted_at"]) or confirmed > dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=5):
        raise ReconciliationError("The confirmed response timestamp is inconsistent.")
    return stamp


def reconcile(store: Any, request: dict[str, Any], *, repository: str,
              api: Callable[[str], Any], get_log: Callable[[str, int], bytes],
              policy_check: Callable[[], None]) -> ledger.Snapshot:
    """Confirm a specific already-returned response; ambiguous sends without a marker stay blocked."""
    request = validate_request(request)
    policy_check()
    snapshot = store.read()
    if snapshot.document["session"] is not None:
        raise ReconciliationError("The durable session lock must remain until its revocation is independently confirmed.")
    entries = [entry for entry in snapshot.document["releases"]
               if entry["id"] == request["release_id"] and entry["tag"] == request["tag"]]
    if len(entries) != 1:
        raise ReconciliationError("The exact release identity is not present in the ledger.")
    items = [item for item in entries[0]["items"] if item["key"] == request["operation"]]
    if len(items) != 1 or items[0]["state"] not in {"sending", "delivery_unknown"}:
        raise ReconciliationError("The exact operation is not unresolved.")
    baseline = copy.deepcopy(items[0])
    run_id, job_id = baseline["workflow_run_id"], request["job_id"]
    job = api(f"repos/{repository}/actions/jobs/{job_id}")
    run = api(f"repos/{repository}/actions/runs/{run_id}")
    if (not isinstance(job, dict) or job.get("id") != job_id or job.get("run_id") != run_id
            or job.get("run_attempt") != baseline["run_attempt"]
            or job.get("name") != JOB_NAME or job.get("status") != "completed"
            or not isinstance(run, dict) or run.get("id") != run_id or run.get("status") != "completed"
            or run.get("event") not in {"release", "workflow_dispatch"}
            or not isinstance(run.get("repository"), dict) or run["repository"].get("id") != 1349123452
            or not isinstance(run.get("head_repository"), dict) or run["head_repository"].get("id") != 1349123452
            or type(run.get("workflow_id")) is not int or run["workflow_id"] == 407947021):
        raise ReconciliationError("The job or repository identity is not the trusted delivery run.")
    workflow = api(f"repos/{repository}/actions/workflows/{run['workflow_id']}")
    if not isinstance(workflow, dict) or workflow.get("id") != run["workflow_id"] or workflow.get("path") != WORKFLOW_PATH:
        raise ReconciliationError("The job is not from the replacement delivery workflow.")
    stamp = confirmed_time(get_log(repository, job_id), request, baseline)
    def apply(document: dict[str, Any]) -> None:
        policy_check()
        if document["session"] is not None:
            raise ReconciliationError("The durable session lock changed during reconciliation.")
        entries = [entry for entry in document["releases"]
                   if entry["id"] == request["release_id"] and entry["tag"] == request["tag"]]
        if len(entries) != 1:
            raise ReconciliationError("The release identity changed during reconciliation.")
        matches = [item for item in entries[0]["items"] if item["key"] == request["operation"]]
        if len(matches) != 1 or matches[0] != baseline:
            raise ReconciliationError("The unresolved operation changed during reconciliation.")
        matches[0].update(state="sent", message_id=request["message_id"], delivered_at=stamp,
                          reconciliation={"status": "confirmed_response", "source": "github_actions_log", "job_id": job_id})
        entries[0]["status"] = ledger.release_status(entries[0]["items"])
    result, _ = ledger.update(store, apply)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", required=True)
    parser.add_argument("--request", required=True, help="exact JSON identity and owner acknowledgement")
    args = parser.parse_args()
    try:
        repo = os.environ.get("GITHUB_REPOSITORY", "")
        repo_id = os.environ.get("GITHUB_REPOSITORY_ID", "")
        if (os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
                or os.environ.get("RECONCILIATION_REQUESTED") != "true" or repo != "sascio/MyBridge"
                or repo_id != "1349123452" or not os.environ.get("GH_TOKEN")
                or os.environ.get("TELEGRAM_PUBLISHING_ENABLED") == "true"):
            raise ReconciliationError("Explicit owner reconciliation requires publishing to be disabled.")
        request = validate_request(json.loads(args.request, object_pairs_hook=ledger._no_duplicates))
        config = state.SameRepositoryConfig(repo, int(repo_id))
        store = ledger.GitLedger(args.state_dir, state.STATE_BRANCH)
        result = reconcile(store, request, repository=repo, api=lambda endpoint: state._api(endpoint),
                           get_log=_get_log, policy_check=lambda: state.verify_state(config, args.state_dir, repo))
        print(f"Confirmed response reconciled in state commit {result.parent}; no Telegram request was made.")
    except Exception:  # never print API logs, request data, remote errors, or credentials
        print("::error::Reconciliation failed closed; the operation remains blocked. Review the trusted run and ledger.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
