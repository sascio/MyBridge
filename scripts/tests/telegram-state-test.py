#!/usr/bin/env python3
"""Offline tests for same-repository state policy and isolated local Git races."""
from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS / "tests"))
from offline import sitecustomize as offline_guard  # noqa: E402,F401
sys.path.insert(0, str(SCRIPTS))
import telegram_ledger as ledger
import telegram_state as state

SOURCE = "sascio/MyBridge"
ENV = {"GITHUB_REPOSITORY_ID": "1349123452"}


class FakeSameRepository:
    """Fake GitHub metadata and checkout; no network or token is used."""
    def __init__(self, repo_dir: str):
        self.repo_dir = repo_dir
        self.remote = self.push_remote = f"https://github.com/{SOURCE}"
        self.metadata = {"id": 1349123452, "full_name": SOURCE, "default_branch": "main",
                         "archived": False, "disabled": False, "private": False}
        self.branch = {"name": state.STATE_BRANCH}
        self.state_rules = [{"type": "non_fast_forward", "ruleset_id": 10},
                            {"type": "deletion", "ruleset_id": 10}]
        self.main_rules = [{"type": "pull_request", "ruleset_id": 20},
                           {"type": "non_fast_forward", "ruleset_id": 20},
                           {"type": "deletion", "ruleset_id": 20}]
        self.details = {n: {"id": n, "enforcement": "active", "current_user_can_bypass": "never",
                            "bypass_actors": []} for n in (10, 20)}
        self.fail = None
        self.calls = []

    def __call__(self, command, **kwargs):
        self.calls.append(command)
        if self.fail and self.fail in " ".join(command):
            return SimpleNamespace(stdout=b"", stderr=b"do not expose secret", returncode=1)
        if command[:2] == ["git", "rev-parse"]:
            result = self.repo_dir + "\n"
        elif command[:3] == ["git", "remote", "get-url"]:
            result = (self.push_remote if "--push" in command else self.remote) + "\n"
        elif command[:2] == ["gh", "api"]:
            endpoint = command[-1]
            if endpoint == f"repos/{SOURCE}":
                value = self.metadata
            elif endpoint == f"repos/{SOURCE}/branches/{state.STATE_BRANCH}":
                value = self.branch
            elif endpoint == f"repos/{SOURCE}/rules/branches/{state.STATE_BRANCH}?per_page=100":
                value = self.state_rules
            elif endpoint == f"repos/{SOURCE}/rules/branches/main?per_page=100":
                value = self.main_rules
            elif endpoint.startswith(f"repos/{SOURCE}/rulesets/"):
                rid = int(endpoint.split("/")[-1].split("?")[0])
                value = self.details[rid]
            else:
                raise AssertionError(f"unexpected fake API call {endpoint}")
            result = ("\n".join(json.dumps(item) for item in value)
                      if "--paginate" in command else json.dumps(value))
        else:
            raise AssertionError(f"unexpected fake command {command!r}")
        return SimpleNamespace(stdout=result.encode(), stderr=b"", returncode=0)


class SameRepositoryStateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.platform = FakeSameRepository(self.temp.name)
        self.config = state.SameRepositoryConfig(SOURCE, 1349123452)

    def verify(self):
        with mock.patch.dict(os.environ, ENV, clear=False):
            state.verify_state(self.config, self.temp.name, SOURCE, runner=self.platform)

    def test_only_explicit_same_repository_shared_writer_config_is_accepted(self):
        config = Path(self.temp.name, "config.json")
        good = {"enabled": True, "mode": "repository-branch", "repository_id": 1349123452,
                "shared_writers_acknowledged": True}
        def load(doc, source=SOURCE):
            config.write_text(json.dumps(doc))
            return state.load_config(source, str(config))
        self.assertEqual(load(good), self.config)
        for key, bad in (("enabled", False), ("shared_writers_acknowledged", False),
                         ("repository_id", True), ("repository_id", 0), ("mode", "other")):
            with self.subTest(key=key), self.assertRaises(ledger.LedgerError):
                load(dict(good, **{key: bad}))
        with self.assertRaises(ledger.LedgerError):
            load(dict(good, repository="other/repo"))
        with self.assertRaises(ledger.LedgerError):
            load(good, "sascio/..")

    def test_correct_source_and_distinct_protected_state_rules_pass(self):
        self.verify()
        endpoints = [command[-1] for command in self.platform.calls if command[:2] == ["gh", "api"]]
        self.assertIn(f"repos/{SOURCE}/rules/branches/main?per_page=100", endpoints)
        self.assertIn(f"repos/{SOURCE}/rules/branches/{state.STATE_BRANCH}?per_page=100", endpoints)
        self.assertFalse(any("installation/repositories" in endpoint or "/actions/permissions" in endpoint
                             for endpoint in endpoints))

    def test_non_admin_token_may_not_see_bypass_list_which_needs_owner_review(self):
        self.platform.details[10].pop("bypass_actors")
        self.verify()
        self.assertEqual(self.platform.details[10]["current_user_can_bypass"], "never")

    def test_missing_protection_or_incorrect_identity_fails_closed(self):
        cases = [
            ("wrong source identity", lambda p: p.metadata.update(id=998)),
            ("wrong source name", lambda p: p.metadata.update(full_name="other/repo")),
            ("archived", lambda p: p.metadata.update(archived=True)),
            ("disabled", lambda p: p.metadata.update(disabled=True)),
            ("state is default branch", lambda p: p.metadata.update(default_branch=state.STATE_BRANCH)),
            ("wrong state branch", lambda p: p.branch.update(name="main")),
            ("wrong fetch URL", lambda p: setattr(p, "remote", "https://github.com/other/repo")),
            ("wrong push URL", lambda p: setattr(p, "push_remote", "https://github.com/other/repo")),
            ("missing no-force rule", lambda p: p.state_rules.pop(0)),
            ("missing no-delete rule", lambda p: p.state_rules.pop()),
            ("state requires PR", lambda p: p.state_rules.append({"type": "pull_request", "ruleset_id": 10})),
            ("state requires checks", lambda p: p.state_rules.append({"type": "required_status_checks", "ruleset_id": 10})),
            ("main ruleset applies to state", lambda p: p.state_rules.append({"type": "deletion", "ruleset_id": 20})),
            ("main PR rule missing", lambda p: p.main_rules.pop(0)),
            ("state credential can bypass", lambda p: p.details[10].update(current_user_can_bypass="always")),
            ("state ruleset has bypass actor", lambda p: p.details[10].update(bypass_actors=[{"actor_id": 1}])),
            ("state ruleset inactive", lambda p: p.details[10].update(enforcement="disabled")),
            ("rules API denied", lambda p: setattr(p, "fail", "rules/branches")),
            ("state branch missing", lambda p: setattr(p, "fail", "/branches/telegram-delivery-state")),
        ]
        for label, corrupt in cases:
            with self.subTest(label=label):
                self.platform = FakeSameRepository(self.temp.name)
                corrupt(self.platform)
                with self.assertRaises(ledger.LedgerError) as caught:
                    self.verify()
                self.assertNotIn("do not expose secret", str(caught.exception))
        with mock.patch.dict(os.environ, dict(ENV, GITHUB_REPOSITORY_ID="1"), clear=False):
            with self.assertRaises(ledger.LedgerError):
                state.verify_state(self.config, self.temp.name, SOURCE, runner=self.platform)


    def test_bootstrap_checks_rules_without_requiring_a_created_ref(self):
        self.platform.branch = {"name": "not-the-state-branch"}
        with mock.patch.dict(os.environ, ENV, clear=False):
            state.verify_bootstrap_rules(self.config, self.temp.name, SOURCE, runner=self.platform)
            with self.assertRaises(ledger.LedgerError):
                state.verify_state(self.config, self.temp.name, SOURCE, runner=self.platform)

    def test_disabled_legacy_or_duplicate_config_fails_closed(self):
        tracked = SCRIPTS.parent / state.CONFIG_PATH
        with self.assertRaises(ledger.LedgerError):
            state.load_config(SOURCE, str(tracked))
        path = Path(self.temp.name, "config.json")
        path.write_text('{"enabled":true,"enabled":false}')
        with self.assertRaises(ledger.LedgerError):
            state.load_config(SOURCE, str(path))
        path.write_text(json.dumps({"enabled": True, "repository": "other/repo", "repository_id": 123,
                                    "policy_branch": "main", "broker_domain": "sts.example.org",
                                    "trusted_admin_ids": [1], "access_controls_reviewed": True}))
        with self.assertRaises(ledger.LedgerError):
            state.load_config(SOURCE, str(path))


class SameRepositoryGitTests(unittest.TestCase):
    """A single local bare repo models main and the source-free state ref."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.remote = self.root / "remote.git"
        source = self.root / "source-work"
        self.git("init", "--bare", "--initial-branch=main", str(self.remote), cwd=self.root)
        self.git("clone", str(self.remote), str(source), cwd=self.root)
        self.git("config", "user.name", "Test", cwd=source)
        self.git("config", "user.email", "test@example.invalid", cwd=source)
        (source / "source.txt").write_text("protected source", encoding="utf-8")
        self.git("add", "source.txt", cwd=source)
        self.git("commit", "-m", "source fixture", cwd=source)
        self.git("push", "origin", "HEAD:refs/heads/main", cwd=source)
        self.main_before = self.git("rev-parse", "refs/heads/main", cwd=self.remote)
        path = source / ledger.LEDGER_PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(ledger.canonical(ledger.migrate(
            {"schema": 2, "releases": [{"release_id": 1, "tag_name": "old", "message_ids": [7]}]},
            "2020-01-01T00:00:00Z", {"release_ids": [1], "tags": ["old"]})))
        self.git("rm", "--cached", "-r", ".", cwd=source)
        self.git("add", ledger.LEDGER_PATH, cwd=source)
        tree = self.git("write-tree", cwd=source).decode().strip()
        root = self.git("commit-tree", tree, "-m", "initial state fixture", cwd=source).decode().strip()
        self.git("push", "origin", f"{root}:refs/heads/{state.STATE_BRANCH}", cwd=source)
        self.work = self.root / "state-work"
        self.git("clone", "--branch", state.STATE_BRANCH, str(self.remote), str(self.work), cwd=self.root)
        self.store = ledger.GitLedger(str(self.work), state.STATE_BRANCH)

    @staticmethod
    def git(*args, cwd):
        return subprocess.run(["git", *args], cwd=cwd, check=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE).stdout

    def test_state_updates_are_fast_forward_and_never_touch_main(self):
        before = self.store.read()
        document = copy.deepcopy(before.document)
        document["session"] = {"owner": "run-one"}
        after = self.store.save(before, document)
        self.assertEqual(after.document["legacy"]["releases"][0]["message_ids"], [7])
        self.assertEqual(self.git("rev-parse", "refs/heads/main", cwd=self.remote), self.main_before)
        self.assertEqual(self.git("diff", "--name-only", before.parent, after.parent, cwd=self.work).splitlines(),
                         [ledger.LEDGER_PATH.encode()])
        self.git("merge-base", "--is-ancestor", before.parent, after.parent, cwd=self.work)

    def test_two_state_clones_cannot_overwrite_each_other(self):
        other = self.root / "other"
        self.git("clone", "--branch", state.STATE_BRANCH, str(self.remote), str(other), cwd=self.root)
        second = ledger.GitLedger(str(other), state.STATE_BRANCH)
        first_snapshot, second_snapshot = self.store.read(), second.read()
        winner, loser = copy.deepcopy(first_snapshot.document), copy.deepcopy(second_snapshot.document)
        winner["session"], loser["session"] = {"owner": "winner"}, {"owner": "loser"}
        self.store.save(first_snapshot, winner)
        with self.assertRaises(ledger.LedgerError):
            second.save(second_snapshot, loser)
        self.assertEqual(second.read().document["session"], {"owner": "winner"})
        self.assertEqual(self.git("rev-parse", "refs/heads/main", cwd=self.remote), self.main_before)


if __name__ == "__main__":
    unittest.main(verbosity=2)
