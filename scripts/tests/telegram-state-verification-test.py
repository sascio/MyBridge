#!/usr/bin/env python3
"""Offline Git regressions for the owner-triggered, non-delivery verification job."""
from __future__ import annotations

import copy
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS / "tests"))
from offline import sitecustomize as offline_guard  # noqa: F401,E402
sys.path.insert(0, str(SCRIPTS))
import telegram_ledger as ledger
import telegram_state as state
import telegram_state_verify as verification


def setUpModule():
    old = os.environ.get("GIT_ALLOW_PROTOCOL")
    os.environ["GIT_ALLOW_PROTOCOL"] = "file"
    def restore():
        if old is None:
            os.environ.pop("GIT_ALLOW_PROTOCOL", None)
        else:
            os.environ["GIT_ALLOW_PROTOCOL"] = old
    unittest.addModuleCleanup(restore)


class StateOnlyVerificationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        remote, work = self.root / "remote.git", self.root / "checkout"
        self.git("init", "--bare", "--initial-branch=telegram-delivery-state", str(remote), cwd=self.root)
        self.git("clone", str(remote), str(work), cwd=self.root)
        self.git("config", "user.name", "Test", cwd=work)
        self.git("config", "user.email", "test@example.invalid", cwd=work)
        doc = ledger.migrate({"schema": 1, "releases": []}, "2020-01-01T00:00:00Z",
                             {"release_ids": [], "tags": []})
        (work / ledger.LEDGER_PATH).write_bytes(ledger.canonical(doc))
        self.git("add", ledger.LEDGER_PATH, cwd=work)
        self.git("commit", "-m", "initial state", cwd=work)
        self.git("push", "origin", "HEAD:refs/heads/telegram-delivery-state", cwd=work)
        self.store = ledger.GitLedger(str(work), state.STATE_BRANCH)

    @staticmethod
    def git(*args, cwd):
        return subprocess.run(["git", *args], cwd=cwd, check=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE).stdout

    def test_read_write_race_retry_and_archival_preserve_history(self):
        before = self.store.read()
        report = verification.verify(self.store, 12345, policy_check=lambda: None)
        after = self.store.read()
        self.assertEqual(report, {"head": after.parent, "records_archived": 6, "definite_conflicts_recovered": 1,
                                  "interrupted_verification_recovered": True, "synthetic_ambiguous_guard_checked": True})
        self.assertEqual(len(after.document["verification"]), 6)
        self.assertTrue(all(r["status"] == "archived" and r["run_id"] == 12345 for r in after.document["verification"]))
        self.assertEqual({r["phase"] for r in after.document["verification"]},
                         {"interrupted", "unknown-simulation", "first", "second", "race-a", "race-b"})
        self.assertEqual(len({r["id"] for r in after.document["verification"]}), 6)
        for key in ("activation", "legacy", "inventory", "identity", "session", "releases"):
            self.assertEqual(before.document[key], after.document[key])
        self.assertEqual(self.git("rev-list", "--count", f"{before.parent}..{after.parent}", cwd=self.store.repo).strip(), b"7")
        self.assertEqual(self.git("diff", "--name-only", before.parent, after.parent, cwd=self.store.repo).splitlines(),
                         [ledger.LEDGER_PATH.encode()])

    def test_synthetic_unknown_exercises_the_actual_publisher_guard_without_a_release_record(self):
        before = self.store.read()
        marker = verification._marker(123, "unknown-simulation")
        doc = copy.deepcopy(before.document)
        verification._append(doc, marker)
        ledger.validate(doc)
        verification._check_synthetic_ambiguity(doc, marker)
        with mock.patch.object(verification.publish, "require_no_unresolved_items"):
            with self.assertRaises(verification.VerificationError):
                verification._check_synthetic_ambiguity(doc, marker)
        self.assertEqual(self.store.read().raw, before.raw)
        self.assertEqual(self.store.read().document["releases"], [])

    def test_policy_failure_does_not_write(self):
        before = self.store.read()
        def refuse():
            raise ledger.LedgerError("rules not verified")
        with self.assertRaises(ledger.LedgerError):
            verification.verify(self.store, 123, policy_check=refuse)
        self.assertEqual(self.store.read().raw, before.raw)

    def test_failed_verification_can_archive_only_its_non_delivery_records(self):
        class FailSecond(ledger.GitLedger):
            def __init__(self, *args):
                super().__init__(*args)
                self.count = 0
            def save(self, old, doc):
                self.count += 1
                if self.count == 2:
                    raise ledger.LedgerError("simulated state-write failure")
                return super().save(old, doc)
        broken = FailSecond(self.store.repo, self.store.branch)
        with self.assertRaises(ledger.LedgerError):
            verification.verify(broken, 123, policy_check=lambda: None)
        self.assertEqual([r["status"] for r in self.store.read().document["verification"]], ["active", "active"])
        with self.assertRaises(verification.VerificationError):
            verification.verify(self.store, 456, policy_check=lambda: None)
        with self.assertRaises(verification.VerificationError):
            verification.archive_run(self.store, 999, policy_check=lambda: None)
        self.assertEqual(verification.archive_run(self.store, 123, policy_check=lambda: None), 2)
        self.assertTrue(all(r["status"] == "archived" for r in self.store.read().document["verification"]))
        report = verification.verify(self.store, 456, policy_check=lambda: None)
        self.assertEqual(report["records_archived"], 6)
        self.assertEqual(len(self.store.read().document["verification"]), 8)

    def test_verification_records_cannot_be_deleted_or_rewritten(self):
        verification.verify(self.store, 123, policy_check=lambda: None)
        snapshot = self.store.read()
        for mutate in (lambda d: d["verification"].clear(),
                       lambda d: d["verification"][0].update(status="active"),
                       lambda d: d["verification"][0].update(run_id=456)):
            document = copy.deepcopy(snapshot.document)
            mutate(document)
            with self.assertRaises(ledger.LedgerError):
                self.store.save(snapshot, document)
            self.assertEqual(self.store.read().raw, snapshot.raw)

    def test_cli_cannot_run_outside_explicit_actions_dispatch(self):
        command = [sys.executable, str(SCRIPTS / "telegram_state_verify.py"), "--state-dir", self.store.repo]
        env = dict(os.environ, GITHUB_ACTIONS="", TELEGRAM_BOT_TOKEN="secret-test-value")
        result = subprocess.run(command, capture_output=True, text=True, env=env, timeout=30)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("secret-test-value", result.stdout + result.stderr)
        self.assertEqual(self.store.read().document["verification"], [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
