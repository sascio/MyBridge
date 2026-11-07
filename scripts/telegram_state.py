#!/usr/bin/env python3
"""Fail-closed configuration and policy checks for Git-backed delivery state.

The selected production path uses a dedicated branch in the source repository.
No branch, credential or Telegram session is created here. GitHub's repository
GITHUB_TOKEN is shared with other eligible workflows: it is not a workflow-level
exclusive credential. No separate repository or broker is used.
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
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
BRANCH = re.compile(r"^[A-Za-z0-9._/-]+$")


@dataclass(frozen=True)
class SameRepositoryConfig:
    repository: str
    repository_id: int


def _json(raw: bytes) -> Any:
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=ledger._no_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ledger.LedgerError("State configuration or policy is not valid JSON.") from exc


def load_config(source_repository: str, path: str = CONFIG_PATH) -> SameRepositoryConfig:
    """Load only explicitly reviewed same-repository storage; otherwise fail closed."""
    try:
        doc = _json(Path(path).read_bytes())
    except OSError as exc:
        raise ledger.LedgerError("Reviewed state storage configuration is missing.") from exc
    required = {"enabled", "mode", "repository_id", "shared_writers_acknowledged"}
    if (not isinstance(doc, dict) or set(doc) != required or doc.get("enabled") is not True
            or doc.get("mode") != "repository-branch" or doc.get("shared_writers_acknowledged") is not True
            or not isinstance(source_repository, str) or not REPOSITORY.fullmatch(source_repository)
            or any(part in {".", ".."} for part in source_repository.split("/"))
            or type(doc.get("repository_id")) is not int or doc["repository_id"] <= 0):
        raise ledger.LedgerError("Same-repository state storage is disabled or its configuration is unsafe.")
    return SameRepositoryConfig(source_repository, doc["repository_id"])


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


def require_disabled_legacy(repository: str, *, api: Callable = _api) -> None:
    """The historical workflow must stay disabled before every live operation."""
    workflow = api(f"repos/{repository}/actions/workflows/407947021")
    if (not isinstance(workflow, dict) or workflow.get("state") != "disabled_manually"
            or workflow.get("path") != ".github/workflows/telegram-release-announcement.yml"):
        raise ledger.LedgerError("The legacy Telegram workflow is not confirmed disabled.")


def _check_rules(repository: str, branch: str, *, runner: Callable,
                 rules: list[Any] | None = None) -> None:
    if rules is None:
        rules = _api(f"repos/{repository}/rules/branches/{quote(branch, safe='')}?per_page=100",
                     runner=runner, paginate=True)
    if any(not isinstance(rule, dict) for rule in rules):
        raise ledger.LedgerError("State branch rules are malformed.")
    required = {"non_fast_forward", "deletion"}
    types = {rule.get("type") for rule in rules}
    if not required.issubset(types):
        raise ledger.LedgerError("Required state or trust-policy branch protections are missing.")
    if "pull_request" in types or "update" in types:
        raise ledger.LedgerError("State branch rules prevent direct writes without a bypass.")
    # An empty check list is harmless; a nonempty required-check rule would block
    # exact-parent state commits. Do not ask for or assume a bypass.
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


def _verify_checkout(repo_dir: str, config: SameRepositoryConfig, *, runner: Callable) -> None:
    top = _call(["git", "rev-parse", "--show-toplevel"], cwd=repo_dir, runner=runner).decode("utf-8").strip()
    if Path(top).resolve() != Path(repo_dir).resolve():
        raise ledger.LedgerError("State checkout is not an isolated Git repository.")
    remote = _call(["git", "remote", "get-url", "--all", "origin"], cwd=repo_dir, runner=runner).decode("utf-8").splitlines()
    push_remote = _call(["git", "remote", "get-url", "--push", "--all", "origin"], cwd=repo_dir, runner=runner).decode("utf-8").splitlines()
    allowed = {f"https://github.com/{config.repository}".casefold(), f"https://github.com/{config.repository}.git".casefold()}
    if len(remote) != 1 or len(push_remote) != 1 or remote[0].casefold() not in allowed or push_remote[0].casefold() not in allowed:
        raise ledger.LedgerError("State checkout does not point exclusively to the reviewed repository.")


def _verify_same_repository(config: SameRepositoryConfig, repo_dir: str, source: str, *, runner: Callable,
                            require_ref: bool = True) -> None:
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
    if require_ref:
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
    _check_rules(source, STATE_BRANCH, runner=runner, rules=state_rules)


def verify_bootstrap_rules(config: SameRepositoryConfig, repo_dir: str, source: str, *,
                           runner: Callable = subprocess.run) -> None:
    """Check effective state rules even before the dedicated ref is created."""
    _verify_same_repository(config, repo_dir, source, runner=runner, require_ref=False)


def verify_state(config: SameRepositoryConfig, repo_dir: str, source: str, *,
                 runner: Callable = subprocess.run) -> None:
    """Recheck same-repository state rules; all repo writers remain trusted."""
    if not isinstance(config, SameRepositoryConfig):
        raise ledger.LedgerError("Only same-repository branch storage is supported.")
    _verify_same_repository(config, repo_dir, source, runner=runner)


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate the reviewed storage configuration; never opens a Telegram session.")
    parser.add_argument("--check-config", action="store_true", required=True)
    args = parser.parse_args()
    del args
    try:
        config = load_config(os.environ.get("GITHUB_REPOSITORY", ""))
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
