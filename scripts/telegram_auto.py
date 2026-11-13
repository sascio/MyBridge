#!/usr/bin/env python3
"""Automatically initialize and poll future releases without a test send.

Only the replacement workflow on the default branch can run this tool. It
bootstraps the pinned, pre-created protected state ref with a fast-forward,
verifies real state-only writes, and opens the ledger gate only after bot login,
logout and the final exclusion scans have been confirmed. The scheduled poll
uses the same serialized publisher and never replays the bootstrap inventory.
"""
from __future__ import annotations

import copy
import datetime as dt
import json
import os
import re
import secrets
from typing import Any, Callable

import telegram_cutover as cutover
import telegram_identity as identity
import telegram_ledger as ledger
import telegram_mtproto as mtproto
import telegram_publish as publish
import telegram_release_notes as notes
import telegram_state as state
import telegram_state_verify as verification


REPOSITORY = "sascio/MyBridge"
REPOSITORY_ID = 1349123452
BOOTSTRAP_BASE = "04ae2eb70c5cfc19ae7bbc4eef74d09a87e862d5"
WORKFLOW = ".github/workflows/telegram-release-delivery.yml"


class AutomaticDeliveryError(RuntimeError):
    pass


def require_context(default_branch: str) -> tuple[int, int]:
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    workflow = os.environ.get("GITHUB_WORKFLOW_REF", "").split("@", 1)[0]
    run_id, attempt = os.environ.get("GITHUB_RUN_ID", ""), os.environ.get("GITHUB_RUN_ATTEMPT", "")
    if (os.environ.get("GITHUB_ACTIONS") != "true" or event_name not in {"push", "schedule", "workflow_dispatch"}
            or repo != REPOSITORY or os.environ.get("GITHUB_REPOSITORY_ID") != str(REPOSITORY_ID)
            or workflow != f"{REPOSITORY}/{WORKFLOW}"
            or os.environ.get("GITHUB_REF") != f"refs/heads/{default_branch}"
            or not os.environ.get("GH_TOKEN")
            or not re.fullmatch(r"[1-9][0-9]*", run_id) or not re.fullmatch(r"[1-9][0-9]*", attempt)):
        raise AutomaticDeliveryError("Automatic state operations require the reviewed default-branch workflow.")
    enabled = os.environ.get("TELEGRAM_PUBLISHING_ENABLED") == "true"
    if event_name != "workflow_dispatch" and not enabled:
        raise AutomaticDeliveryError("Automatic delivery requires the enabled default-branch workflow.")
    if event_name == "workflow_dispatch" and enabled:
        raise AutomaticDeliveryError("No-send initialization requires the live publishing switch to remain off.")
    try:
        with open(os.environ.get("GITHUB_EVENT_PATH", ""), encoding="utf-8") as handle:
            event = json.load(handle, object_pairs_hook=ledger._no_duplicates)
        if not isinstance(event, dict):
            raise ValueError("event mismatch")
        repository = event.get("repository")
        # Scheduled events need not carry a repository object. The trusted
        # runner's repository ID and the live policy/API checks bind it instead.
        if repository is not None and (not isinstance(repository, dict) or repository.get("full_name") != REPOSITORY
                                       or repository.get("id") != REPOSITORY_ID
                                       or repository.get("default_branch") != default_branch):
            raise ValueError("repository mismatch")
        if event_name == "workflow_dispatch":
            inputs = event.get("inputs")
            requested = inputs.get("initialize_state") if isinstance(inputs, dict) else None
            preflight = inputs.get("preflight") if isinstance(inputs, dict) else None
            if (repository is None or not isinstance(inputs, dict)
                    or not (requested is True or requested == "true")
                    or not (preflight is None or preflight is False or preflight == "false")):
                raise ValueError("no-send initialization was not explicitly requested")
        if event_name == "push" and (repository is None or event.get("ref") != f"refs/heads/{default_branch}"
                                     or event.get("deleted") is not False):
            raise ValueError("push mismatch")
    except (OSError, ValueError, TypeError, KeyError) as exc:
        raise AutomaticDeliveryError("The automatic workflow event is inconsistent.") from exc
    return int(run_id), int(attempt)


def _recover_verification_markers(store: ledger.GitLedger, repository: str, *,
                                  policy_check: Callable[[], None], api: Callable) -> None:
    """Archive only non-delivery markers from runs GitHub confirms are finished."""
    document = store.read().document
    if document["session"] is not None:
        raise AutomaticDeliveryError("An unconfirmed bot session blocks automatic bootstrap.")
    runs = sorted({r["run_id"] for r in document["verification"] if r["status"] == "active"})
    for run_id in runs:
        run = api(f"repos/{repository}/actions/runs/{run_id}")
        if (not isinstance(run, dict) or run.get("id") != run_id or run.get("status") != "completed"
                or not isinstance(run.get("repository"), dict) or run["repository"].get("id") != REPOSITORY_ID
                or not isinstance(run.get("head_repository"), dict) or run["head_repository"].get("id") != REPOSITORY_ID):
            raise AutomaticDeliveryError("An unfinished state-verification run cannot be recovered automatically.")
        verification.archive_run(store, run_id, policy_check=policy_check)


def verify_bot_with_lock(store: ledger.GitLedger, *, token: str, chat: str, api_id: int, api_hash: str,
                         run_id: int, policy_check: Callable[[], None],
                         bot_lookup: Callable = publish.bot_details,
                         transport_factory: Callable = mtproto.BotTransport) -> None:
    """Prove bot login/logout under a durable lock; never send or upload."""
    owner = f"bootstrap-{run_id}-" + secrets.token_hex(12)
    policy_check()
    def lock(document: dict[str, Any]) -> None:
        if document["activation"].get("ready") is not False or document["session"] is not None or document["releases"]:
            raise AutomaticDeliveryError("A session or delivery plan prevents automatic readiness.")
        document["session"] = {"owner": owner}
    ledger.update(store, lock)
    transport = None
    error: Exception | None = None
    try:
        bot_id, channel_id, channel_ref = bot_lookup(token, chat)
        binding = publish.identity_binding(token, bot_id, channel_id)
        def bind(document: dict[str, Any]) -> None:
            if document["session"] != {"owner": owner} or document["identity"] not in (None, binding):
                raise AutomaticDeliveryError("The bot or channel identity changed during readiness.")
            document["identity"] = binding
        ledger.update(store, bind)
        transport = transport_factory(api_id, api_hash, token, channel_ref, bot_id, channel_id)
        transport.connect()
    except Exception as exc:
        error = exc
    finally:
        try:
            revoked = transport is None or transport.close() is True
        except Exception:
            revoked = False
        if revoked:
            try:
                def unlock(document: dict[str, Any]) -> None:
                    if document["session"] != {"owner": owner}:
                        raise AutomaticDeliveryError("The readiness session lock changed.")
                    document["session"] = None
                ledger.update(store, unlock)
            except Exception as exc:
                error = exc
        else:
            raise AutomaticDeliveryError("Bot logout is unconfirmed; the durable session lock remains.")
    if error is not None:
        raise AutomaticDeliveryError("No-send bot readiness did not complete; activation remains off.") from error


def merge_exclusions(old: dict[str, Any], fresh: dict[str, Any]) -> dict[str, Any]:
    """Union two protected inventories without dropping or rewriting history."""
    ledger.validate_inventory(old)
    ledger.validate_inventory(fresh)
    if (set(old) != set(fresh) or "protected" not in old
            or old["protected"]["key_check"] != fresh["protected"]["key_check"]):
        raise AutomaticDeliveryError("The historical exclusion inventories are incompatible.")
    merged = copy.deepcopy(old)
    for field in ("release_ids", "tags", "message_ids"):
        if field in old:
            merged[field] = sorted(set(old[field]) | set(fresh[field]))
    by_id = {entry["release_id"]: copy.deepcopy(entry) for entry in old.get("exclusions", [])}
    for entry in fresh.get("exclusions", []):
        existing = by_id.get(entry["release_id"])
        if existing is not None and existing != entry:
            raise AutomaticDeliveryError("A historical release changed during bootstrap.")
        by_id[entry["release_id"]] = copy.deepcopy(entry)
    if "exclusions" in old:
        merged["exclusions"] = sorted(by_id.values(), key=lambda item: item["release_id"])
    for field in ("release_id_digests", "tag_digests"):
        merged["protected"][field] = sorted(set(old["protected"][field]) | set(fresh["protected"][field]))
    if "tag_ref_snapshot" in old["protected"]:
        merged["protected"]["tag_ref_snapshot"] = fresh["protected"]["tag_ref_snapshot"]
    ledger.validate_inventory(merged)
    ledger.require_reviewed_history(merged)
    return merged


def bootstrap(store: ledger.GitLedger, *, default_branch: str, main_head: str, token: str, chat: str,
              api_id: int, api_hash: str, run_id: int, policy_check: Callable[[], None],
              api: Callable = state._api, collect: Callable = cutover.collect,
              verify_state: Callable = verification.verify, probe: Callable = verify_bot_with_lock,
              now: Callable[[], dt.datetime] = lambda: dt.datetime.now(dt.timezone.utc)) -> ledger.Snapshot:
    """Initialize the pinned base once, then verify and arm without manual inputs."""
    policy_check()
    parent = cutover.remote_head(store, state.STATE_BRANCH)
    if parent == BOOTSTRAP_BASE:
        legacy_bytes = store._git("show", f"{parent}:{ledger.LEGACY_PATH}").stdout
        legacy = ledger._json_input(legacy_bytes)
        # Two matching snapshots avoid initializing during observed inventory
        # drift. This is an exclusion snapshot, not a list to replay.
        first = collect(REPOSITORY, legacy, ledger.BOOTSTRAP_PENDING_FENCE, token, include_activity=True)
        second = collect(REPOSITORY, legacy, ledger.BOOTSTRAP_PENDING_FENCE, token, include_activity=True)
        if ledger.canonical(first) != ledger.canonical(second):
            raise AutomaticDeliveryError("The baseline changed during bootstrap; retry later.")
        document = copy.deepcopy(second)
        document["activation"]["ready"] = False
        ledger.validate(document)
        cutover.initialize(store, document, expected_parent=parent, default_branch=default_branch,
                           expected_main=main_head, bootstrap_base=BOOTSTRAP_BASE)
        policy_check()
    snapshot = store.read()  # a moved, malformed or unknown ref is never reset
    document = snapshot.document
    store._git("fetch", "--no-tags", "origin", f"refs/heads/{default_branch}")
    if (store._git("merge-base", "--is-ancestor", BOOTSTRAP_BASE, snapshot.parent, check=False).returncode != 0
            or store._git("merge-base", "--is-ancestor", BOOTSTRAP_BASE, main_head, check=False).returncode != 0):
        raise AutomaticDeliveryError("The state or default branch is not descended from the pinned bootstrap base.")
    for commit in (BOOTSTRAP_BASE, main_head):
        saved = store._git("show", f"{commit}:{ledger.LEGACY_PATH}", check=False)
        if saved.returncode or ledger._json_input(saved.stdout) != document["legacy"]:
            raise AutomaticDeliveryError("The pinned or current legacy ledger differs from preserved state.")
    ledger.require_inventory_key(document["inventory"], token, required=True)
    ledger.require_reviewed_history(document["inventory"])
    if document["activation"].get("ready") is True:
        return snapshot
    if document["activation"].get("ready") is not False or document["releases"]:
        raise AutomaticDeliveryError("The state branch is not a supported automatic bootstrap ledger.")
    _recover_verification_markers(store, REPOSITORY, policy_check=policy_check, api=api)
    verify_state(store, run_id, policy_check=policy_check)
    probe(store, token=token, chat=chat, api_id=api_id, api_hash=api_hash, run_id=run_id,
          policy_check=policy_check)
    # Anchor the publication fence BEFORE the last scans, not in the future
    # after them. Otherwise a tag created between the scan and that future
    # fence could later be treated as new despite existing before the fence.
    # Observed releases/tags during the scans are conservatively excluded too.
    scan_started = now()
    if not isinstance(scan_started, dt.datetime) or scan_started.utcoffset() is None:
        raise AutomaticDeliveryError("The baseline clock is unavailable or not UTC-aware.")
    scan_started = scan_started.astimezone(dt.timezone.utc)
    fence = scan_started.replace(microsecond=0).isoformat().replace("+00:00", "Z")
    first = collect(REPOSITORY, document["legacy"], ledger.BOOTSTRAP_PENDING_FENCE, token, include_activity=True)
    fresh = collect(REPOSITORY, document["legacy"], ledger.BOOTSTRAP_PENDING_FENCE, token, include_activity=True)
    scan_finished = now()
    if (not isinstance(scan_finished, dt.datetime) or scan_finished.utcoffset() is None
            or scan_finished.astimezone(dt.timezone.utc) < scan_started
            or scan_finished.astimezone(dt.timezone.utc) - scan_started > ledger.BOOTSTRAP_SNAPSHOT_MAX_AGE):
        raise AutomaticDeliveryError("The baseline scans exceeded the trusted clock window; retry later.")
    if ledger.canonical(first) != ledger.canonical(fresh):
        raise AutomaticDeliveryError("The baseline changed before activation; retry later.")
    # Only add exclusions: neither an earlier observation nor a protected
    # draft ID can disappear. Mutations observed by scans postpone activation.
    policy_check()
    def arm(document: dict[str, Any]) -> None:
        if (document["activation"].get("ready") is not False or document["session"] is not None
                or document["identity"] is None or document["releases"]
                or any(r["status"] == "active" for r in document["verification"])):
            raise AutomaticDeliveryError("State or no-send readiness is incomplete; activation stays off.")
        document["inventory"] = merge_exclusions(document["inventory"], fresh["inventory"])
        document["activation"]["inventory_sha256"] = ledger.digest(document["inventory"])
        document["activation"]["published_after"] = fence
        document["activation"]["ready"] = True
    result, _ = ledger.update(store, arm)
    policy_check()
    activation = result.document["activation"]
    if (activation.get("ready") is not True or activation.get("published_after") != fence
            or activation.get("legacy_sha256") != snapshot.document["activation"]["legacy_sha256"]
            or activation.get("inventory_sha256") != ledger.digest(result.document["inventory"])):
        raise AutomaticDeliveryError("The future-only activation could not be verified.")
    print(f"Automatic state bootstrap verified; only publications after the baseline fence {fence} can be eligible.")
    return result


def poll(store: ledger.GitLedger, *, repository: str, repository_id: int, token: str, chat: str,
         api_id: int, api_hash: str, run_id: int, run_attempt: int, tooling_sha: str,
         policy_check: Callable[[], None], api: Callable = state._api,
         send_release: Callable = publish.publish,
         resolve: Callable = identity.resolve_tag_commit,
         now: Callable[[], dt.datetime] = lambda: dt.datetime.now(dt.timezone.utc)) -> int:
    """Select only current, genuine post-activation publications; never backfill."""
    if repository != REPOSITORY or repository_id != REPOSITORY_ID:
        raise AutomaticDeliveryError("The polling repository identity is invalid.")
    policy_check()
    document = store.read().document
    if document["activation"].get("ready") is not True:
        raise AutomaticDeliveryError("The state bootstrap is not ready.")
    cutoff_text = document["activation"]["published_after"]
    cutoff = ledger.timestamp(cutoff_text)
    if now() <= cutoff:
        print("The automatic activation fence has not passed; no releases were processed.")
        return 0
    ledger.require_inventory_key(document["inventory"], token, required=True)
    ledger.require_reviewed_history(document["inventory"])
    if document["session"] is not None or any(r["status"] == "active" for r in document["verification"]):
        raise AutomaticDeliveryError("A previous operation or verification is unresolved.")
    releases = api(f"repos/{repository}/releases?per_page=100", paginate=True)
    if not isinstance(releases, list):
        raise AutomaticDeliveryError("The release list is unavailable.")
    candidates: list[tuple[dt.datetime, int, str]] = []
    seen: set[int] = set()
    for item in releases:
        if not isinstance(item, dict) or type(item.get("id")) is not int or item["id"] <= 0 or item["id"] in seen:
            raise AutomaticDeliveryError("The release list contains invalid or duplicate identities.")
        seen.add(item["id"])
        if type(item.get("draft")) is not bool or type(item.get("prerelease")) is not bool:
            raise AutomaticDeliveryError("A release has an invalid publication status.")
        if item["draft"] or item["prerelease"]:
            continue
        published = ledger.timestamp(item.get("published_at"))
        tag = identity.validate_tag(item.get("tag_name"))
        if (published <= cutoff or ledger.historically_seen(document["inventory"], token, item["id"], tag)):
            continue
        existing = publish.stored_release(document, item["id"])
        if existing is not None and existing["status"] == "sent":
            continue
        if existing is not None and (publish.has_unresolved_items(existing["items"])
                                     or existing["status"] == "failed"):
            # Never retry this release automatically, but do not let its
            # unresolved/failed item starve independent published releases.
            print(f"::warning::Skipping release_id={item['id']} tag={tag}: its ledger plan needs recovery; other releases remain eligible.")
            continue
        candidates.append((published, item["id"], tag))
    for published, release_id, tag in sorted(candidates):
        current = store.read().document
        if current["activation"]["published_after"] != cutoff_text or current["activation"].get("ready") is not True:
            raise AutomaticDeliveryError("The activation fence changed during polling.")
        if ledger.historically_seen(current["inventory"], token, release_id, tag):
            continue
        current_entry = publish.stored_release(current, release_id)
        if current_entry is not None and current_entry["status"] == "sent":
            continue
        if current_entry is not None and (publish.has_unresolved_items(current_entry["items"])
                                          or current_entry["status"] == "failed"):
            print(f"::warning::Skipping release_id={release_id} tag={tag}: its ledger plan needs recovery; other releases remain eligible.")
            continue
        payload = api(f"repos/{repository}/releases/{release_id}")
        if (not isinstance(payload, dict) or type(payload.get("id")) is not int or payload["id"] != release_id
                or payload.get("tag_name") != tag or payload.get("draft") is not False
                or payload.get("prerelease") is not False or ledger.timestamp(payload.get("published_at")) != published):
            raise AutomaticDeliveryError("The current release no longer matches the selected publication.")
        release = notes.Release.from_api(payload)
        source_sha = resolve(repository, tag)
        send_release(release, store, token=token, chat=chat, api_id=api_id, api_hash=api_hash,
                     source_sha=source_sha, workflow_run_id=run_id, run_attempt=run_attempt,
                     tooling_sha=tooling_sha, upload_assets=True, resume_unsent=False,
                     policy_check=policy_check, allow_new_release=True, require_protected_inventory=True)
    print(f"Automatic future-release poll examined {len(candidates)} eligible release(s).")
    return len(candidates)


def report_initialization(snapshot: ledger.Snapshot) -> None:
    """Expose redacted evidence of readiness without claiming a delivery test."""
    document = snapshot.document
    summary = ledger.summarize(document)
    if (document["activation"].get("ready") is not True or document["identity"] is None
            or summary["session_locked"] or summary["active_verifications"] or document["releases"]):
        raise AutomaticDeliveryError("No-send initialization did not finish in a ready, idle state.")
    fence = document["activation"]["published_after"]
    print(f"::notice title=Telegram ledger initialized::Protected schema-4 state {snapshot.parent}; "
          f"future-only fence {fence}. Live publishing remains off. No message or file was sent.")
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write("### Telegram no-send ledger initialization\n\n"
                         f"- Protected state commit: `{snapshot.parent}`\n"
                         f"- Future-only publication fence: `{fence}`\n"
                         "- Exact-parent writes, concurrent conflict recovery and interruption/ambiguity guards verified.\n"
                         "- Bot/channel rights and locked MTProto bot login/logout verified.\n"
                         "- No active session, verification or delivery plan remains.\n"
                         "- Live publishing switch remains **off**.\n"
                         "- **No Telegram message or file was sent; actual delivery was not tested.**\n")


def main() -> int:
    try:
        branch = os.environ.get("SOURCE_BRANCH", "")
        run_id, run_attempt = require_context(branch)
        if not ledger.publishing_ledger_mode() or not ledger.strict_publishing_status():
            raise AutomaticDeliveryError("The explicit ledger-controlled publishing gate is not enabled.")
        config = state.load_config(REPOSITORY)
        token, chat = publish.require_secret("TELEGRAM_BOT_TOKEN"), publish.require_secret("TELEGRAM_CHAT_ID")
        api_id_text, api_hash = publish.require_secret("TELEGRAM_API_ID"), publish.require_secret("TELEGRAM_API_HASH")
        if not api_id_text.isdigit() or int(api_id_text) <= 0 or not re.fullmatch(r"[0-9a-fA-F]{32}", api_hash):
            raise AutomaticDeliveryError("MTProto credentials are malformed.")
        store = ledger.GitLedger(os.environ.get("STATE_DIR", ""), state.STATE_BRANCH)
        state.require_disabled_legacy(REPOSITORY)
        main_head = cutover.remote_head(store, branch)
        tooling_sha = publish.tooling_commit()
        if main_head != tooling_sha or main_head != os.environ.get("GITHUB_SHA"):
            raise AutomaticDeliveryError("The default branch changed; a fresh automatic run is required.")
        def policy() -> None:
            state.verify_state(config, store.repo, REPOSITORY)
            state.require_disabled_legacy(REPOSITORY)
            if cutover.remote_head(store, branch) != main_head:
                raise AutomaticDeliveryError("The default branch changed; this run is no longer trusted.")
        snapshot = bootstrap(store, default_branch=branch, main_head=main_head, token=token, chat=chat,
                             api_id=int(api_id_text), api_hash=api_hash, run_id=run_id, policy_check=policy)
        if os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch":
            report_initialization(snapshot)
        # A push or initialization dispatch never sends. Scheduled polls recover real
        # new publications, including those whose workflow event was suppressed.
        if os.environ.get("GITHUB_EVENT_NAME") == "schedule":
            poll(store, repository=REPOSITORY, repository_id=REPOSITORY_ID, token=token, chat=chat,
                 api_id=int(api_id_text), api_hash=api_hash, run_id=run_id, run_attempt=run_attempt,
                 tooling_sha=tooling_sha, policy_check=policy)
    except (AutomaticDeliveryError, ledger.LedgerError, publish.PublishError, cutover.CutoverError,
            verification.VerificationError, identity.IdentityError) as exc:
        # These modules only raise fixed, redacted diagnostic strings.
        print(f"::error::{exc}")
        return 1
    except Exception:  # never print credentials, payloads or raw remote errors
        print("::error::Automatic Telegram readiness or polling failed closed; inspect state and workflow logs.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
