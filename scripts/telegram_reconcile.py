#!/usr/bin/env python3
"""Reconcile a known response from a trusted Actions job log; never send to Telegram."""
from __future__ import annotations

import argparse
import copy
import datetime as dt
import json
import os
import re
import secrets
import subprocess
from typing import Any, Callable

import telegram_identity as identity
import telegram_ledger as ledger
import telegram_state as state


class ReconciliationError(RuntimeError):
    pass


ACKNOWLEDGEMENT = "VERIFIED_TRUSTED_RUN_AND_RESPONSE"
JOB_NAME = "Deliver with durable bot-only MTProto state"
POLL_JOB_NAME = "Automatically initialize state and detect new releases"
WORKFLOW_PATH = ".github/workflows/telegram-release-delivery.yml"
OPERATION = re.compile(r"^(?:page|asset):[1-9][0-9]*$")


def require_publishing_disabled(value: str | None) -> None:
    """Require an explicit off switch; missing or malformed variables are not proof of disablement."""
    if value != "false":
        raise ReconciliationError("Explicit owner reconciliation requires TELEGRAM_PUBLISHING_ENABLED=false.")


def validate_request(request: object) -> dict[str, Any]:
    fields = {"release_id", "tag", "operation", "message_id", "job_id", "acknowledge"}
    if not isinstance(request, dict) or set(request) not in (fields, fields | {"evidence"}):
        raise ReconciliationError("An exact reconciliation request is required.")
    request.setdefault("evidence", "github_actions_log")
    if (not isinstance(request["evidence"], str)
            or request["evidence"] not in {"github_actions_log", "telegram_mtproto_known_id"}):
        raise ReconciliationError("The reconciliation evidence source is unsupported.")
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
              policy_check: Callable[[], None],
              telegram_lookup: Callable[[dict[str, Any], dict[str, Any], dict[str, Any]], str] | None = None) -> ledger.Snapshot:
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
            or job.get("status") != "completed"
            or not isinstance(run, dict) or run.get("id") != run_id or run.get("status") != "completed"
            or (job.get("name"), run.get("event")) not in {
                (JOB_NAME, "release"), (JOB_NAME, "workflow_dispatch"), (POLL_JOB_NAME, "schedule")}
            or not isinstance(run.get("repository"), dict) or run["repository"].get("id") != 1349123452
            or not isinstance(run.get("head_repository"), dict) or run["head_repository"].get("id") != 1349123452
            or type(run.get("workflow_id")) is not int or run["workflow_id"] == 407947021):
        raise ReconciliationError("The job or repository identity is not the trusted delivery run.")
    workflow = api(f"repos/{repository}/actions/workflows/{run['workflow_id']}")
    if not isinstance(workflow, dict) or workflow.get("id") != run["workflow_id"] or workflow.get("path") != WORKFLOW_PATH:
        raise ReconciliationError("The job is not from the replacement delivery workflow.")
    evidence_source = request["evidence"]
    if evidence_source == "github_actions_log":
        stamp = confirmed_time(get_log(repository, job_id), request, baseline)
    else:
        if telegram_lookup is None:
            raise ReconciliationError("The direct Telegram known-ID verifier is unavailable.")
        stamp = telegram_lookup(request, baseline, snapshot.document)
        confirmed = ledger.timestamp(stamp)
        if (confirmed < ledger.timestamp(baseline["attempted_at"])
                or confirmed > dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=5)):
            raise ReconciliationError("The Telegram message timestamp is inconsistent with the recorded attempt.")
        stamp = confirmed.replace(microsecond=0).isoformat().replace("+00:00", "Z")
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
                          reconciliation={"status": "confirmed_response", "source": evidence_source, "job_id": job_id})
        entries[0]["status"] = ledger.release_status(entries[0]["items"])
    result, _ = ledger.update(store, apply)
    return result


def verify_known_telegram_message(store: Any, request: dict[str, Any], item: dict[str, Any],
                                 snapshot: dict[str, Any], *, repository: str,
                                 policy_check: Callable[[], None]) -> str:
    """Use bot MTProto to check one known ID against the frozen rendered page; never send or scan history."""
    if item.get("kind") != "page" or not request["operation"].startswith("page:"):
        raise ReconciliationError("Direct Telegram verification is limited to a frozen text page.")
    try:
        page_index = int(request["operation"].split(":", 1)[1]) - 1
        release_record = next(r for r in snapshot["releases"]
                              if r["id"] == request["release_id"] and r["tag"] == request["tag"])
        raw_release = state._api(f"repos/{repository}/releases/{request['release_id']}")
        import telegram_announce as announce
        import telegram_publish as publish
        import telegram_release_notes as notes
        import telegram_mtproto as mtproto
        release = notes.Release.from_api(raw_release)
        if (release.id != release_record["id"] or release.tag_name != release_record["tag"]
                or release.created_at != release_record["created_at"] or release.published_at != release_record["published_at"]
                or release.draft or release.prerelease):
            raise ReconciliationError("The immutable published release identity changed or is not public.")
        announcement, pages = announce.build_pages(release)
        if page_index < 0 or page_index >= len(pages):
            raise ReconciliationError("The frozen announcement page does not exist in the published release.")
        operation = publish.operation_plan(release, announcement, pages, [], {})[page_index]
        if operation["key"] != request["operation"] or operation["payload"] != item["payload"]:
            raise ReconciliationError("The published release no longer renders to the frozen ledger payload.")
        token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
        chat = os.environ.get("TELEGRAM_CHAT_ID", "")
        api_id_text = os.environ.get("TELEGRAM_API_ID", "")
        api_hash = os.environ.get("TELEGRAM_API_HASH", "")
        if (not token or not chat or not api_id_text.isdigit() or int(api_id_text) <= 0
                or not re.fullmatch(r"[0-9a-fA-F]{32}", api_hash)):
            raise ReconciliationError("Read-only Telegram verification credentials are invalid.")
        bot_id, channel_id, channel_ref = publish.bot_details(token, chat)
        if snapshot.get("identity") != publish.identity_binding(token, bot_id, channel_id):
            raise ReconciliationError("The Telegram bot/channel does not match the protected ledger identity.")
        owner = f"reconcile-{os.environ.get('GITHUB_RUN_ID', 'manual')}-{secrets.token_hex(8)}"

        def acquire(document: dict[str, Any]) -> None:
            policy_check()
            if document["session"] is not None:
                raise ReconciliationError("A durable Telegram session lock already exists.")
            records = [r for r in document["releases"] if r["id"] == request["release_id"] and r["tag"] == request["tag"]]
            matches = [i for r in records for i in r["items"] if i["key"] == request["operation"]]
            if len(matches) != 1 or matches[0] != item:
                raise ReconciliationError("The frozen operation changed before Telegram verification.")
            if document["identity"] != snapshot.get("identity"):
                raise ReconciliationError("The protected Telegram identity changed before verification.")
            document["session"] = {"owner": owner}

        transport = mtproto.BotTransport(int(api_id_text), api_hash, token, channel_ref, bot_id, channel_id)
        ledger.update(store, acquire)
        verified_at: str | None = None
        try:
            policy_check()
            transport.connect()
            markup = transport.text_request(operation["html"], [tuple(b) for b in operation["buttons"]], item["random_id"])
            message = transport.read_known_message(request["message_id"], markup)
            message_time = getattr(message, "date", None)
            if not isinstance(message_time, dt.datetime):
                raise ReconciliationError("Telegram did not return a server timestamp for the known message.")
            if message_time.tzinfo is None:
                message_time = message_time.replace(tzinfo=dt.timezone.utc)
            verified_at = message_time.astimezone(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
        finally:
            revoked = transport.close()
            if revoked:
                def unlock(document: dict[str, Any]) -> None:
                    policy_check()
                    if document["session"] != {"owner": owner}:
                        raise ReconciliationError("The Telegram session lock changed before confirmed logout.")
                    document["session"] = None
                ledger.update(store, unlock)
            else:
                raise ReconciliationError("Telegram session logout was not confirmed; the durable lock remains for recovery.")
        if verified_at is None:
            raise ReconciliationError("The known Telegram message was not verified.")
        print(f"TELEGRAM_KNOWN_ID_VERIFIED release_id={request['release_id']} "
              f"operation={request['operation']} message_id={request['message_id']} "
              f"confirmed_at={verified_at} payload_sha256={item['payload']}")
        return verified_at
    except ReconciliationError:
        raise
    except Exception as exc:
        # Never surface raw Telegram/GitHub exceptions, text or credential material.
        raise ReconciliationError("Read-only known-message verification failed closed.") from exc


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
                or repo_id != "1349123452" or not os.environ.get("GH_TOKEN")):
            raise ReconciliationError("Explicit owner reconciliation requires the trusted same-repository workflow.")
        require_publishing_disabled(os.environ.get("TELEGRAM_PUBLISHING_ENABLED"))
        request = validate_request(json.loads(args.request, object_pairs_hook=ledger._no_duplicates))
        config = state.SameRepositoryConfig(repo, int(repo_id))
        store = ledger.GitLedger(args.state_dir, state.STATE_BRANCH)
        policy = lambda: state.verify_state(config, args.state_dir, repo)
        result = reconcile(store, request, repository=repo, api=lambda endpoint: state._api(endpoint),
                           get_log=_get_log, policy_check=policy,
                           telegram_lookup=lambda req, item, document: verify_known_telegram_message(
                               store, req, item, document, repository=repo, policy_check=policy))
        print(f"Confirmed response reconciled in state commit {result.parent}; no Telegram send or upload was made.")
    except Exception:  # never print API logs, request data, remote errors, or credentials
        print("::error::Reconciliation failed closed; the operation remains blocked. Review the trusted run and ledger.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
