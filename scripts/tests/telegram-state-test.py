#!/usr/bin/env python3
"""Offline tests for same-repository state and legacy private-policy compatibility."""
from __future__ import annotations

import copy
import io
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
STATE = "sascio/private-state-test"
WORKFLOW_REF = SOURCE + "/.github/workflows/telegram-release-delivery.yml@refs/tags/v9.9.9"
SHA = "a" * 40
ENV = {"GITHUB_REPOSITORY_ID": "1349123452", "GITHUB_REPOSITORY_OWNER_ID": "321979606",
       "GITHUB_WORKFLOW_REF": WORKFLOW_REF, "GITHUB_WORKFLOW_SHA": SHA, "GITHUB_EVENT_NAME": "release"}
CONFIG = {"enabled": True, "repository": STATE, "repository_id": 999,
          "policy_branch": "main", "broker_domain": "sts.example.org", "trusted_admin_ids": [101], "access_controls_reviewed": True}


class FakePlatform:
    """Fake GitHub API and checkout. No network, token, or provider is used."""
    def __init__(self, repo_dir: str):
        self.repo_dir = repo_dir
        self.remote = f"https://github.com/{STATE}"
        self.push_remote = self.remote
        self.metadata = {"private": True, "archived": False, "id": 999, "full_name": STATE,
                         "owner": {"type": "User", "id": 321979606}, "default_branch": "main"}
        self.installation = {"total_count": 1, "repositories": [{"id": 999}]}
        self.actions = {"enabled": False}
        self.collaborators = [{"id": 101, "permissions": {"admin": True, "push": True}}]
        self.keys = []
        self.state_rules = [{"type": "non_fast_forward", "ruleset_id": 10},
                            {"type": "deletion", "ruleset_id": 10}]
        self.policy_rules = [{"type": "non_fast_forward", "ruleset_id": 20},
                             {"type": "deletion", "ruleset_id": 20},
                             {"type": "pull_request", "ruleset_id": 20}]
        self.details = {n: {"id": n, "enforcement": "active", "current_user_can_bypass": "never",
                            "bypass_actors": []} for n in (10, 20)}
        self.policy = state.expected_policy(SOURCE, 1349123452, 321979606, WORKFLOW_REF, SHA, "release")
        self.policy_files = [state.POLICY_PATH]
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
        elif command[:2] == ["git", "fetch"]:
            result = ""
        elif command[:2] == ["git", "ls-tree"]:
            result = "\n".join(self.policy_files)
        elif command[:2] == ["git", "show"]:
            result = json.dumps(self.policy)
        elif command[:2] == ["gh", "api"]:
            endpoint = command[-1]
            if endpoint == "installation/repositories?per_page=100":
                value = self.installation
            elif endpoint == f"repos/{STATE}":
                value = self.metadata
            elif endpoint == f"repos/{STATE}/actions/permissions":
                value = self.actions
            elif endpoint == f"repos/{STATE}/collaborators?affiliation=all&per_page=100":
                value = self.collaborators
            elif endpoint == f"repos/{STATE}/keys?per_page=100":
                value = self.keys
            elif endpoint == f"repos/{STATE}/rules/branches/{state.STATE_BRANCH}?per_page=100":
                value = self.state_rules
            elif endpoint == f"repos/{STATE}/rules/branches/main?per_page=100":
                value = self.policy_rules
            elif endpoint.startswith(f"repos/{STATE}/rulesets/"):
                rid = int(endpoint.split("/")[-1].split("?")[0])
                value = self.details[rid]
            else:
                raise AssertionError(f"unexpected fake API call {endpoint}")
            result = ("\n".join(json.dumps(item) for item in value)
                      if "--paginate" in command else json.dumps(value))
        else:
            raise AssertionError(f"unexpected fake command {command!r}")
        return SimpleNamespace(stdout=result.encode(), stderr=b"", returncode=0)


class StateConfigTests(unittest.TestCase):
    def test_tracked_config_is_disabled(self):
        self.assertEqual(json.loads((SCRIPTS.parent / state.CONFIG_PATH).read_text()), {"enabled": False})
        with self.assertRaisesRegex(ledger.LedgerError, "disabled"):
            state.load_config(SOURCE, str(SCRIPTS.parent / state.CONFIG_PATH))

    def test_only_explicit_separate_reviewed_config_is_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp, "config.json")
            def load(doc):
                config.write_text(json.dumps(doc))
                return state.load_config(SOURCE, str(config))
            actual = load(CONFIG)
            self.assertEqual(actual, state.StateConfig(STATE, 999, "main", "sts.example.org", (101,)))
            for key, bad in (("enabled", False), ("access_controls_reviewed", False),
                             ("repository", SOURCE), ("repository", "other/private-state"),
                             ("repository", "sascio/.."), ("repository", "owner/x?token=abc"),
                             ("repository_id", True), ("repository_id", 0), ("trusted_admin_ids", []),
                             ("trusted_admin_ids", [101, 101]), ("trusted_admin_ids", [True]),
                             ("policy_branch", state.STATE_BRANCH), ("policy_branch", "--bad"),
                             ("broker_domain", "localhost"), ("broker_domain", "sts.invalid"),
                             ("broker_domain", "https://sts.example.org/path")):
                doc = dict(CONFIG, **{key: bad})
                with self.subTest(key=key, bad=bad), self.assertRaises(ledger.LedgerError):
                    load(doc)
            with self.assertRaises(ledger.LedgerError):
                load(dict(CONFIG, extra="unexpected"))
            config.write_text('{"enabled":true,"enabled":false}')
            with self.assertRaises(ledger.LedgerError):
                state.load_config(SOURCE, str(config))

    def test_policy_pins_immutable_source_workflow_ref_sha_and_event(self):
        policy = state.expected_policy(SOURCE, 1349123452, 321979606, WORKFLOW_REF, SHA, "release")
        self.assertEqual(policy["subject"], "repo:sascio@321979606/MyBridge@1349123452:environment:telegram-delivery")
        self.assertEqual(policy["permissions"], {"contents": "write", "administration": "read"})
        import re
        claims = {"repository_id": "1349123452", "repository_owner_id": "321979606",
                  "workflow_ref": WORKFLOW_REF, "workflow_sha": SHA, "event_name": "release"}
        self.assertEqual(set(policy["claim_pattern"]), set(claims))
        for name, value in claims.items():
            pattern = policy["claim_pattern"][name]
            with self.subTest(claim=name):
                # Use search rather than fullmatch: the policy itself must be
                # anchored even if a broker uses an unanchored regex matcher.
                self.assertIsNotNone(re.search(pattern, value))
                self.assertIsNone(re.search(pattern, "x" + value))
                self.assertIsNone(re.search(pattern, value + "-other"))
        self.assertIsNone(re.search(policy["claim_pattern"]["workflow_ref"],
                                    WORKFLOW_REF.replace("v9.9.9", "v9X9X9")))
        for ref, sha, event in ((WORKFLOW_REF.replace("telegram-release-delivery", "other"), SHA, "release"),
                                (WORKFLOW_REF, "a" * 39, "release"), (WORKFLOW_REF, SHA, "push")):
            with self.subTest(ref=ref, sha=sha, event=event), self.assertRaises(ledger.LedgerError):
                state.expected_policy(SOURCE, 1349123452, 321979606, ref, sha, event)


class StateGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config = state.StateConfig(STATE, 999, "main", "sts.example.org", (101,))
        self.platform = FakePlatform(self.temp.name)

    def verify(self):
        with mock.patch.dict(os.environ, ENV, clear=False):
            state.verify_state(self.config, self.temp.name, SOURCE, runner=self.platform)

    def test_exact_private_policy_and_protections_pass(self):
        self.verify()
        commands = self.platform.calls
        self.assertTrue(any(c[-1].endswith("/actions/permissions") for c in commands if c[:2] == ["gh", "api"]))
        self.assertTrue(any(c[:2] == ["git", "show"] and state.POLICY_PATH in c[-1] for c in commands))
        self.assertTrue(all("do not expose secret" not in " ".join(c) for c in commands))

    def test_missing_private_identity_or_storage_policy_fails_closed(self):
        cases = [
            ("cross-repository token", lambda p: p.installation.update(total_count=2, repositories=[{"id": 999}, {"id": 1000}])),
            ("wrong token repository", lambda p: p.installation.update(repositories=[{"id": 1000}])),
            ("public", lambda p: p.metadata.update(private=False)),
            ("wrong repository id", lambda p: p.metadata.update(id=998)),
            ("organization-owned state repo", lambda p: p.metadata["owner"].update(type="Organization")),
            ("wrong owner identity", lambda p: p.metadata["owner"].update(id=123)),
            ("archived", lambda p: p.metadata.update(archived=True)),
            ("wrong default policy branch", lambda p: p.metadata.update(default_branch="elsewhere")),
            ("actions enabled", lambda p: p.actions.update(enabled=True)),
            ("unreviewed writer", lambda p: p.collaborators.append({"id": 102, "permissions": {"admin": False, "push": True}})),
            ("unreviewed admin", lambda p: p.collaborators.append({"id": 102, "permissions": {"admin": True, "push": True}})),
            ("missing trusted admin", lambda p: p.collaborators.clear()),
            ("writable deploy key", lambda p: p.keys.append({"read_only": False})),
            ("unverifiable deploy key", lambda p: p.keys.append({})),
            ("wrong fetch URL", lambda p: setattr(p, "remote", "https://github.com/sascio/MyBridge")),
            ("wrong push URL", lambda p: setattr(p, "push_remote", "https://github.com/attacker/repo")),
            ("missing no force rule", lambda p: p.state_rules.pop(0)),
            ("state requires PR", lambda p: p.state_rules.append({"type": "pull_request", "ruleset_id": 10})),
            ("state requires checks", lambda p: p.state_rules.append({"type": "required_status_checks", "ruleset_id": 10,
                                                                        "parameters": {"required_status_checks": ["ci"]}})),
            ("policy branch lacks PR", lambda p: p.policy_rules.pop()),
            ("state credential bypass", lambda p: p.details[10].update(current_user_can_bypass="always")),
            ("unverified caller bypass", lambda p: p.details[10].pop("current_user_can_bypass")),
            ("visible bypass actor", lambda p: p.details[20].update(bypass_actors=[{"actor_id": 1}])),
            ("extra trust policy", lambda p: p.policy_files.append(".github/chainguard/other.sts.yaml")),
            ("wrong workflow", lambda p: p.policy["claim_pattern"].update(workflow_ref=".*")),
            ("unanchored workflow", lambda p: p.policy["claim_pattern"].update(
                workflow_ref=p.policy["claim_pattern"]["workflow_ref"].removeprefix("^").removesuffix("$"))),
            ("wrong SHA", lambda p: p.policy["claim_pattern"].update(workflow_sha=".*")),
            ("wrong event", lambda p: p.policy["claim_pattern"].update(event_name=".*")),
            ("extra app permission", lambda p: p.policy["permissions"].update(administration="write")),
            ("api denied", lambda p: setattr(p, "fail", "actions/permissions")),
            ("policy unavailable", lambda p: setattr(p, "fail", "git show")),
        ]
        for label, corrupt in cases:
            with self.subTest(label=label):
                self.platform = FakePlatform(self.temp.name)
                corrupt(self.platform)
                with self.assertRaises(ledger.LedgerError) as caught:
                    self.verify()
                self.assertNotIn("do not expose secret", str(caught.exception))


class FakeSameRepository(FakePlatform):
    def __init__(self, repo_dir: str):
        super().__init__(repo_dir)
        self.remote = self.push_remote = f"https://github.com/{SOURCE}"
        self.metadata = {"id": 1349123452, "full_name": SOURCE, "default_branch": "main",
                         "archived": False, "disabled": False, "private": False}
        self.branch = {"name": state.STATE_BRANCH}
        self.main_rules = [{"type": "pull_request", "ruleset_id": 20},
                           {"type": "non_fast_forward", "ruleset_id": 20},
                           {"type": "deletion", "ruleset_id": 20}]

    def __call__(self, command, **kwargs):
        if command[:2] != ["gh", "api"]:
            return super().__call__(command, **kwargs)
        self.calls.append(command)
        if self.fail and self.fail in " ".join(command):
            return SimpleNamespace(stdout=b"", stderr=b"do not expose secret", returncode=1)
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


class SeparateRepositoryGitTests(unittest.TestCase):
    """Real local Git remotes; neither remote is the user's checkout or GitHub."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source.git"
        self.remote = self.root / "state.git"
        self.work = self.root / "state-work"
        self.git("init", "--bare", "--initial-branch=main", str(self.source), cwd=self.root)
        source_work = self.root / "source-work"
        self.git("clone", str(self.source), str(source_work), cwd=self.root)
        self.git("config", "user.name", "Test", cwd=source_work)
        self.git("config", "user.email", "test@example.invalid", cwd=source_work)
        (source_work / "source.txt").write_text("protected source", encoding="utf-8")
        self.git("add", "source.txt", cwd=source_work)
        self.git("commit", "-m", "source fixture", cwd=source_work)
        self.git("push", "origin", "HEAD:refs/heads/main", cwd=source_work)
        self.source_before = self.git("rev-parse", "refs/heads/main", cwd=self.source)
        self.git("init", "--bare", "--initial-branch=telegram-delivery-state", str(self.remote), cwd=self.root)
        self.git("clone", str(self.remote), str(self.work), cwd=self.root)
        self.git("config", "user.name", "Test", cwd=self.work)
        self.git("config", "user.email", "test@example.invalid", cwd=self.work)
        doc = ledger.migrate({"schema": 2, "releases": [{"release_id": 1, "tag_name": "old", "message_ids": [7]}]},
                             "2020-01-01T00:00:00Z", {"release_ids": [1], "tags": ["old"]})
        path = self.work / ledger.LEDGER_PATH
        path.parent.mkdir(parents=True)
        path.write_bytes(ledger.canonical(doc))
        self.git("add", ".", cwd=self.work)
        self.git("commit", "-m", "fixture", cwd=self.work)
        self.git("push", "origin", f"HEAD:refs/heads/{state.STATE_BRANCH}", cwd=self.work)
        self.store = ledger.GitLedger(str(self.work), state.STATE_BRANCH)

    @staticmethod
    def git(*args, cwd):
        return subprocess.run(["git", *args], cwd=cwd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

    def test_state_write_never_updates_the_separate_source_remote(self):
        old = self.store.read()
        doc = copy.deepcopy(old.document)
        doc["session"] = {"owner": "run-one"}
        saved = self.store.save(old, doc)
        self.assertEqual(saved.document["legacy"]["schema"], 2)
        self.assertEqual(saved.document["legacy"]["releases"][0]["message_ids"], [7])
        self.assertEqual(self.git("rev-parse", "refs/heads/main", cwd=self.source), self.source_before)
        refs = self.git("show-ref", cwd=self.source).decode().splitlines()
        self.assertEqual(len(refs), 1)
        self.assertTrue(refs[0].endswith(" refs/heads/main"))
        self.assertEqual(self.git("diff", "--name-only", old.parent, saved.parent, cwd=self.work).splitlines(),
                         [ledger.LEDGER_PATH.encode()])

    def test_same_remote_state_updates_never_touch_main(self):
        # This fixture is a separate local bare repository, never GitHub or the
        # user's working tree. Both refs intentionally share the same remote.
        source_work = self.root / "source-work"
        self.git("checkout", "--detach", cwd=source_work)
        path = source_work / ledger.LEDGER_PATH
        path.parent.mkdir(parents=True)
        path.write_bytes(ledger.canonical(ledger.migrate(
            {"schema": 1, "releases": []}, "2020-01-01T00:00:00Z", {"release_ids": [], "tags": []})))
        self.git("add", ledger.LEDGER_PATH, cwd=source_work)
        self.git("commit", "-m", "initial state fixture", cwd=source_work)
        self.git("push", "origin", f"HEAD:refs/heads/{state.STATE_BRANCH}", cwd=source_work)
        work = self.root / "same-remote-work"
        self.git("clone", "--branch", state.STATE_BRANCH, str(self.source), str(work), cwd=self.root)
        store = ledger.GitLedger(str(work), state.STATE_BRANCH)
        before = store.read()
        new = copy.deepcopy(before.document)
        new["session"] = {"owner": "run-same-repository"}
        after = store.save(before, new)
        self.assertEqual(after.document["session"], {"owner": "run-same-repository"})
        self.assertEqual(self.git("rev-parse", "refs/heads/main", cwd=self.source), self.source_before)
        self.assertEqual(self.git("diff", "--name-only", before.parent, after.parent, cwd=work).splitlines(),
                         [ledger.LEDGER_PATH.encode()])
        self.git("merge-base", "--is-ancestor", before.parent, after.parent, cwd=work)

    def test_git_policy_tree_query_finds_only_exact_reviewed_policy(self):
        policy = self.work / state.POLICY_PATH
        policy.parent.mkdir(parents=True)
        policy.write_text('{}', encoding="utf-8")
        self.git("add", str(policy.relative_to(self.work)), cwd=self.work)
        self.git("commit", "-m", "local policy fixture", cwd=self.work)
        paths = self.git("ls-tree", "-r", "--name-only", "HEAD", "--", ".github/chainguard/", cwd=self.work)
        self.assertEqual(paths.decode().splitlines(), [state.POLICY_PATH])

    def test_two_state_clones_cannot_overwrite_each_other(self):
        other = self.root / "other"
        self.git("clone", str(self.remote), str(other), cwd=self.root)
        second = ledger.GitLedger(str(other), state.STATE_BRANCH)
        first_snapshot, second_snapshot = self.store.read(), second.read()
        winner, loser = copy.deepcopy(first_snapshot.document), copy.deepcopy(second_snapshot.document)
        winner["session"], loser["session"] = {"owner": "winner"}, {"owner": "loser"}
        self.store.save(first_snapshot, winner)
        with self.assertRaises(ledger.LedgerError):
            second.save(second_snapshot, loser)
        self.assertEqual(second.read().document["session"], {"owner": "winner"})


if __name__ == "__main__":
    unittest.main(verbosity=2)
