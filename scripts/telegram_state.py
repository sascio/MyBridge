#!/usr/bin/env python3
"""Fail-closed configuration and policy checks for Git-backed delivery state.

The selected production path uses a dedicated branch in the source repository.
No branch, credential or Telegram session is created here. GitHub's repository
GITHUB_TOKEN is shared with other eligible workflows: it is not a workflow-level
exclusive credential. The legacy private-repository policy helpers remain for
migration compatibility, but the delivery workflow does not use a broker.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from urllib.parse import quote

import telegram_ledger as ledger

CONFIG_PATH = ".github/telegram-state.json"
STATE_BRANCH = "telegram-delivery-state"
ENVIRONMENT = "telegram-delivery"
POLICY_PATH = ".github/chainguard/telegram-delivery.sts.yaml"
WORKFLOW_PATH = ".github/workflows/telegram-release-delivery.yml"
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
BRANCH = re.compile(r"^[A-Za-z0-9._/-]+$")
DOMAIN = re.compile(r"^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z][A-Za-z0-9-]{1,62}$")
SHA = re.compile(r"^[0-9a-f]{40}$")


@dataclass(frozen=True)
class StateConfig:
    repository: str
    repository_id: int
    policy_branch: str
    broker_domain: str
    trusted_admin_ids: tuple[int, ...]


@dataclass(frozen=True)
class SameRepositoryConfig:
    repository: str
    repository_id: int


def _json(raw: bytes) -> Any:
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=ledger._no_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ledger.LedgerError("State configuration or policy is not valid JSON.") from exc


def load_config(source_repository: str, path: str = CONFIG_PATH) -> StateConfig | SameRepositoryConfig:
    """Load explicitly reviewed storage; missing/disabled configuration fails closed."""
    try:
        doc = _json(Path(path).read_bytes())
    except OSError as exc:
        raise ledger.LedgerError("Reviewed state storage configuration is missing.") from exc
    if isinstance(doc, dict) and doc.get("mode") == "repository-branch":
        required = {"enabled", "mode", "repository_id", "shared_writers_acknowledged"}
        rid = doc.get("repository_id")
        if (set(doc) != required or doc.get("enabled") is not True
                or doc.get("shared_writers_acknowledged") is not True
                or not isinstance(source_repository, str) or not REPOSITORY.fullmatch(source_repository)
                or any(part in {".", ".."} for part in source_repository.split("/"))
                or type(rid) is not int or rid <= 0):
            raise ledger.LedgerError("Same-repository state configuration is unsafe or incomplete.")
        return SameRepositoryConfig(source_repository, rid)
    required = {"enabled", "repository", "repository_id", "policy_branch", "broker_domain",
                "trusted_admin_ids", "access_controls_reviewed"}
    if not isinstance(doc, dict) or set(doc) != required or doc.get("enabled") is not True:
        raise ledger.LedgerError("Isolated state storage is disabled or its reviewed configuration is invalid.")
    repo, rid, policy_branch, domain, admins = (doc["repository"], doc["repository_id"],
                                                 doc["policy_branch"], doc["broker_domain"], doc["trusted_admin_ids"])
    if (doc["access_controls_reviewed"] is not True or not isinstance(source_repository, str)
            or not REPOSITORY.fullmatch(source_repository)
            or any(part in {".", ".."} for part in source_repository.split("/")) or not isinstance(repo, str)
            or not REPOSITORY.fullmatch(repo) or repo.casefold() == source_repository.casefold()
            or repo.split("/", 1)[0].casefold() != source_repository.split("/", 1)[0].casefold()
            or any(part in {".", ".."} for part in repo.split("/"))
            or type(rid) is not int or rid <= 0 or not isinstance(policy_branch, str)
            or not BRANCH.fullmatch(policy_branch) or policy_branch.startswith("-")
            or policy_branch == STATE_BRANCH or not isinstance(domain, str) or not DOMAIN.fullmatch(domain)
            or domain.lower().endswith((".local", ".localhost", ".invalid", ".example", ".test"))
            or not isinstance(admins, list) or not admins
            or any(type(value) is not int or value <= 0 for value in admins) or len(set(admins)) != len(admins)):
        raise ledger.LedgerError("Isolated state storage configuration is unsafe or incomplete.")
    return StateConfig(repo, rid, policy_branch, domain, tuple(sorted(admins)))


def _go_quote_meta(value: str) -> str:
    # regexp.QuoteMeta-compatible literal for Octo STS (Go's RE2 engine).
    special = r"\.+*?()|[]{}^$"
    return "".join("\\" + c if c in special else c for c in value)


def _exact_claim(value: str) -> str:
    # Anchor independently of the broker version. Otherwise a regex-based
    # matcher could accept a reviewed workflow ref as a substring of another.
    return "^" + _go_quote_meta(value) + "$"


def expected_policy(source: str, source_id: int, owner_id: int, workflow_ref: str,
                    workflow_sha: str, event_name: str) -> dict[str, Any]:
    """One immutable workflow/ref/SHA per policy; no wildcard federation."""
    if (not isinstance(source, str) or not REPOSITORY.fullmatch(source)
            or any(part in {".", ".."} for part in source.split("/"))
            or type(source_id) is not int or source_id <= 0 or type(owner_id) is not int or owner_id <= 0
            or not isinstance(workflow_ref, str) or not workflow_ref.startswith(f"{source}/{WORKFLOW_PATH}@refs/")
            or "\n" in workflow_ref or "\r" in workflow_ref
            or not isinstance(workflow_sha, str) or not SHA.fullmatch(workflow_sha)
            or event_name not in {"release", "workflow_dispatch"}):
        raise ledger.LedgerError("The immutable workflow identity is invalid.")
    owner, name = source.split("/", 1)
    return {
        "issuer": "https://token.actions.githubusercontent.com",
        "subject": f"repo:{owner}@{owner_id}/{name}@{source_id}:environment:{ENVIRONMENT}",
        "claim_pattern": {
            "repository_id": _exact_claim(str(source_id)),
            "repository_owner_id": _exact_claim(str(owner_id)),
            "workflow_ref": _exact_claim(workflow_ref),
            "workflow_sha": _exact_claim(workflow_sha),
            "event_name": _exact_claim(event_name),
        },
        "permissions": {"contents": "write", "administration": "read"},
    }


def _call(command: list[str], *, runner: Callable = subprocess.run, cwd: str | None = None,
          timeout: int = 60) -> bytes:
    try:
        result = runner(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                        timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ledger.LedgerError("State storage or policy verification is unavailable.") from exc
    if result.returncode:
        raise ledger.LedgerError("State storage or policy verification was denied or failed.")
    return result.stdout


def _api(endpoint: str, *, runner: Callable = subprocess.run, paginate: bool = False) -> Any:
    command = ["gh", "api"]
    if paginate:
        # gh versions predating --slurp still support pagination and jq. Each
        # array element becomes one JSON line, independent of the page count.
        command += ["--paginate", "--jq", ".[] | @json"]
    command.append(endpoint)
    raw = _call(command, runner=runner)
    if paginate:
        return [_json(line) for line in raw.splitlines() if line]
    return _json(raw)


def _check_rules(repository: str, branch: str, *, policy_branch: bool, runner: Callable,
                 rules: list[Any] | None = None) -> None:
    if rules is None:
        rules = _api(f"repos/{repository}/rules/branches/{quote(branch, safe='')}?per_page=100",
                     runner=runner, paginate=True)
    if any(not isinstance(rule, dict) for rule in rules):
        raise ledger.LedgerError("State branch rules are malformed.")
    required = {"non_fast_forward", "deletion"} | ({"pull_request"} if policy_branch else set())
    types = {rule.get("type") for rule in rules}
    if not required.issubset(types):
        raise ledger.LedgerError("Required state or trust-policy branch protections are missing.")
    if not policy_branch and ("pull_request" in types or "update" in types):
        raise ledger.LedgerError("State branch rules prevent direct writes without a bypass.")
    # An empty check list is harmless; a nonempty required-check rule would block
    # exact-parent state commits. Do not ask for or assume a bypass.
    if not policy_branch:
        for rule in rules:
            if rule.get("type") == "required_status_checks":
                params = rule.get("parameters")
                if not isinstance(params, dict) or params.get("required_status_checks") != []:
                    raise ledger.LedgerError("State branch requires status checks before direct writes.")
    checked: set[int] = set()
    for rule in rules:
        if rule.get("type") not in required:
            continue
        rid = rule.get("ruleset_id")
        if type(rid) is not int or rid <= 0:
            raise ledger.LedgerError("State branch rule identity is unavailable.")
        if rid in checked:
            continue
        checked.add(rid)
        detail = _api(f"repos/{repository}/rulesets/{rid}?includes_parents=true", runner=runner)
        if (not isinstance(detail, dict) or detail.get("id") != rid or detail.get("enforcement") != "active"
                or detail.get("current_user_can_bypass") != "never"
                or ("bypass_actors" in detail and detail["bypass_actors"] != [])):
            raise ledger.LedgerError("Cannot verify that the state credential obeys the required protections.")


def _verify_checkout(repo_dir: str, config: StateConfig | SameRepositoryConfig, *, runner: Callable) -> None:
    top = _call(["git", "rev-parse", "--show-toplevel"], cwd=repo_dir, runner=runner).decode("utf-8").strip()
    if Path(top).resolve() != Path(repo_dir).resolve():
        raise ledger.LedgerError("State checkout is not an isolated Git repository.")
    remote = _call(["git", "remote", "get-url", "--all", "origin"], cwd=repo_dir, runner=runner).decode("utf-8").splitlines()
    push_remote = _call(["git", "remote", "get-url", "--push", "--all", "origin"], cwd=repo_dir, runner=runner).decode("utf-8").splitlines()
    allowed = {f"https://github.com/{config.repository}".casefold(), f"https://github.com/{config.repository}.git".casefold()}
    if len(remote) != 1 or len(push_remote) != 1 or remote[0].casefold() not in allowed or push_remote[0].casefold() not in allowed:
        raise ledger.LedgerError("State checkout does not point exclusively to the reviewed repository.")


def _verify_same_repository(config: SameRepositoryConfig, repo_dir: str, source: str, *, runner: Callable) -> None:
    if config.repository != source:
        raise ledger.LedgerError("State repository must match the source repository.")
    try:
        source_id = int(os.environ.get("GITHUB_REPOSITORY_ID", ""))
    except ValueError as exc:
        raise ledger.LedgerError("The source repository identity is unavailable.") from exc
    if source_id != config.repository_id:
        raise ledger.LedgerError("The configured state repository ID does not match the workflow source.")
    _verify_checkout(repo_dir, config, runner=runner)
    metadata = _api(f"repos/{source}", runner=runner)
    if (not isinstance(metadata, dict) or metadata.get("id") != config.repository_id
            or str(metadata.get("full_name", "")).casefold() != source.casefold()
            or metadata.get("archived") is not False or metadata.get("disabled") is not False):
        raise ledger.LedgerError("State repository identity or availability is not confirmed.")
    default_branch = metadata.get("default_branch")
    if (not isinstance(default_branch, str) or not BRANCH.fullmatch(default_branch)
            or default_branch.startswith("-") or default_branch == STATE_BRANCH):
        raise ledger.LedgerError("The source default branch is unavailable or is the state branch.")
    branch = _api(f"repos/{source}/branches/{quote(STATE_BRANCH, safe='')}", runner=runner)
    if not isinstance(branch, dict) or branch.get("name") != STATE_BRANCH:
        raise ledger.LedgerError("The dedicated delivery state branch is unavailable.")
    state_rules = _api(f"repos/{source}/rules/branches/{quote(STATE_BRANCH, safe='')}?per_page=100",
                       runner=runner, paginate=True)
    main_rules = _api(f"repos/{source}/rules/branches/{quote(default_branch, safe='')}?per_page=100",
                      runner=runner, paginate=True)
    if (not isinstance(state_rules, list) or not isinstance(main_rules, list)
            or any(not isinstance(rule, dict) for rule in state_rules + main_rules)):
        raise ledger.LedgerError("Effective repository branch rules are unavailable.")
    main_pr_rulesets = {rule.get("ruleset_id") for rule in main_rules if rule.get("type") == "pull_request"}
    if (not main_pr_rulesets or any(type(rid) is not int or rid <= 0 for rid in main_pr_rulesets)
            or any(rule.get("ruleset_id") in main_pr_rulesets for rule in state_rules)):
        raise ledger.LedgerError("The protected default-branch ruleset must not apply to the state branch.")
    _check_rules(source, STATE_BRANCH, policy_branch=False, runner=runner, rules=state_rules)


def verify_state(config: StateConfig | SameRepositoryConfig, repo_dir: str, source: str, *,
                 runner: Callable = subprocess.run) -> None:
    """Recheck effective rules before sends; repository writers remain a shared trust boundary."""
    if isinstance(config, SameRepositoryConfig):
        _verify_same_repository(config, repo_dir, source, runner=runner)
        return
    # Compatibility for the previously implemented private-repository policy.
    # The delivery workflow no longer provisions or uses its broker.
    try:
        source_id = int(os.environ.get("GITHUB_REPOSITORY_ID", ""))
        owner_id = int(os.environ.get("GITHUB_REPOSITORY_OWNER_ID", ""))
    except ValueError as exc:
        raise ledger.LedgerError("The immutable source repository identity is missing.") from exc
    _verify_checkout(repo_dir, config, runner=runner)
    installation = _api("installation/repositories?per_page=100", runner=runner)
    if (not isinstance(installation, dict) or installation.get("total_count") != 1
            or not isinstance(installation.get("repositories"), list)
            or len(installation["repositories"]) != 1
            or not isinstance(installation["repositories"][0], dict)
            or installation["repositories"][0].get("id") != config.repository_id):
        raise ledger.LedgerError("The state token is not scoped exclusively to the reviewed repository.")
    metadata = _api(f"repos/{config.repository}", runner=runner)
    if (not isinstance(metadata, dict) or metadata.get("private") is not True
            or metadata.get("archived") is not False or metadata.get("id") != config.repository_id
            or not isinstance(metadata.get("full_name"), str)
            or metadata["full_name"].casefold() != config.repository.casefold()
            or not isinstance(metadata.get("owner"), dict)
            or metadata["owner"].get("type") != "User" or metadata["owner"].get("id") != owner_id
            or metadata.get("default_branch") != config.policy_branch):
        raise ledger.LedgerError("The reviewed state repository is missing, public, archived or inconsistent.")
    actions = _api(f"repos/{config.repository}/actions/permissions", runner=runner)
    if not isinstance(actions, dict) or actions.get("enabled") is not False:
        raise ledger.LedgerError("Actions must be disabled in the private state repository.")
    collaborators = _api(f"repos/{config.repository}/collaborators?affiliation=all&per_page=100",
                         runner=runner, paginate=True)
    writers: set[int] = set()
    for person in collaborators:
        if (not isinstance(person, dict) or type(person.get("id")) is not int or person["id"] <= 0
                or not isinstance(person.get("permissions"), dict)):
            raise ledger.LedgerError("State-repository collaborator permissions are unverifiable.")
        permissions = person["permissions"]
        if type(permissions.get("push")) is not bool or type(permissions.get("admin")) is not bool:
            raise ledger.LedgerError("State-repository collaborator permissions are unverifiable.")
        if permissions["push"] or permissions["admin"]:
            if not permissions["admin"] or person["id"] not in config.trusted_admin_ids:
                raise ledger.LedgerError("An unreviewed user can write the state repository.")
            writers.add(person["id"])
    if writers != set(config.trusted_admin_ids):
        raise ledger.LedgerError("The reviewed state administrators do not match visible writable collaborators.")
    keys = _api(f"repos/{config.repository}/keys?per_page=100", runner=runner, paginate=True)
    if any(not isinstance(key, dict) or key.get("read_only") is not True for key in keys):
        raise ledger.LedgerError("A writable or unverified state-repository deploy key exists.")
    _check_rules(config.repository, STATE_BRANCH, policy_branch=False, runner=runner)
    _check_rules(config.repository, config.policy_branch, policy_branch=True, runner=runner)
    expected = expected_policy(source, source_id, owner_id, os.environ.get("GITHUB_WORKFLOW_REF", ""),
                               os.environ.get("GITHUB_WORKFLOW_SHA", ""), os.environ.get("GITHUB_EVENT_NAME", ""))
    _call(["git", "fetch", "--no-tags", "origin", f"refs/heads/{config.policy_branch}"], cwd=repo_dir, runner=runner)
    files = _call(["git", "ls-tree", "-r", "--name-only", "FETCH_HEAD", "--", ".github/chainguard/"],
                  cwd=repo_dir, runner=runner).decode("utf-8").splitlines()
    if files != [POLICY_PATH]:
        raise ledger.LedgerError("The private state repository contains unreviewed broker policy files.")
    raw = _call(["git", "show", f"FETCH_HEAD:{POLICY_PATH}"], cwd=repo_dir, runner=runner)
    if _json(raw) != expected:
        raise ledger.LedgerError("The protected OIDC trust policy is missing or broader than this exact workflow identity.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate the reviewed storage configuration; never opens a Telegram session.")
    parser.add_argument("--check-config", action="store_true", required=True)
    args = parser.parse_args()
    del args
    try:
        config = load_config(os.environ.get("GITHUB_REPOSITORY", ""))
        if not isinstance(config, SameRepositoryConfig):
            raise ledger.LedgerError("The delivery workflow requires same-repository branch storage.")
        output = os.environ.get("GITHUB_OUTPUT", "")
        if not output:
            raise ledger.LedgerError("GitHub Actions output is unavailable.")
        with open(output, "a", encoding="utf-8") as handle:
            handle.write(f"repository={config.repository}\nbranch={STATE_BRANCH}\n")
    except (ledger.LedgerError, OSError) as exc:
        print(f"::error::{exc}")
        return 1
    print("Same-repository state configuration validated; live access is checked separately.")
    print("Repository writers and workflows with contents:write can also write the state branch.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
