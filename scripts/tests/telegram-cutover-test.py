#!/usr/bin/env python3
"""Offline tests for frozen-history preparation and a source-free root commit."""
from __future__ import annotations

import contextlib
import copy
import datetime as dt
import io
import json
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
import telegram_cutover as cutover
import telegram_ledger as ledger
import telegram_state as state

TOKEN = "123456789:fake-bot-token-for-offline-tests"
NOW = dt.datetime(2026, 12, 1, tzinfo=dt.timezone.utc)
CUTOFF = "2026-12-01T04:00:00Z"
PRIVATE_TAG = "draft-tag-not-for-public-state"
PRIVATE_ID = 991234567


def setUpModule():
    old = os.environ.get("GIT_ALLOW_PROTOCOL")
    os.environ["GIT_ALLOW_PROTOCOL"] = "file"
    def restore():
        if old is None:
            os.environ.pop("GIT_ALLOW_PROTOCOL", None)
        else:
            os.environ["GIT_ALLOW_PROTOCOL"] = old
    unittest.addModuleCleanup(restore)


def fixtures():
    public = [{"id": rid, "tag_name": tag, "draft": False, "published_at": "2026-11-05T01:00:00Z"}
              for rid, tag in ledger.KNOWN_HISTORICAL_RELEASES.items()]
    draft = {"id": PRIVATE_ID, "tag_name": PRIVATE_TAG, "draft": True, "published_at": None}
    refs = [{"ref": "refs/tags/" + item["tag_name"], "object": {"type": "commit", "sha": "a" * 40}}
            for item in public]
    refs.append({"ref": "refs/tags/" + PRIVATE_TAG, "object": {"type": "tag", "sha": "b" * 40}})
    return public + [draft], refs


def snapshot(releases=None, refs=None, cutoff=CUTOFF, legacy=None):
    releases_default, refs_default = fixtures()
    return cutover.build_snapshot(releases if releases is not None else releases_default,
                                  refs if refs is not None else refs_default,
                                  legacy if legacy is not None else {"schema": 1, "releases": []}, cutoff, TOKEN,
                                  resolve=lambda _tag: "a" * 40, now=NOW)


class SnapshotTests(unittest.TestCase):
    def test_cutover_gates_must_be_exact_false_booleans(self):
        with tempfile.TemporaryDirectory() as tmp:
            publishing, storage = Path(tmp, "publishing.json"), Path(tmp, "storage.json")
            publishing.write_text('{"enabled":false}')
            storage.write_text('{"enabled":false}')
            with mock.patch.object(ledger, "PUBLISHING_CONFIG_PATH", str(publishing)), \
                 mock.patch.object(state, "CONFIG_PATH", str(storage)), \
                 mock.patch.dict(os.environ, {"TELEGRAM_PUBLISHING_ENABLED": "false"}):
                cutover._require_disabled_gates()
                for bad in ('{"enabled":0}', '{"enabled":false,"enabled":false}', '{"enabled":true}'):
                    storage.write_text(bad)
                    with self.subTest(bad=bad), self.assertRaises((cutover.CutoverError, ledger.LedgerError)):
                        cutover._require_disabled_gates()
                storage.write_text('{"enabled":false}')
                with mock.patch.dict(os.environ, {"TELEGRAM_PUBLISHING_ENABLED": "true"}), \
                     self.assertRaises(cutover.CutoverError):
                    cutover._require_disabled_gates()

    def test_all_public_releases_are_excluded_and_draft_metadata_is_blinded(self):
        doc = snapshot()
        inventory = doc["inventory"]
        self.assertEqual(doc["schema"], 4)
        self.assertEqual(len(inventory["exclusions"]), 15)
        self.assertTrue(all(e["status"] == "historical_excluded" and e["reason"] == ledger.HISTORICAL_REASON
                            for e in inventory["exclusions"]))
        known = next(e for e in inventory["exclusions"] if e["release_id"] == ledger.KNOWN_RELEASE_ID)
        self.assertEqual((known["tag"], known["message_id"], known["evidence"], known["delivered_at"]),
                         ("v0.1.15", 5, ledger.KNOWN_EVIDENCE, None))
        self.assertTrue(all(e["message_id"] is None and e["delivered_at"] is None for e in inventory["exclusions"]
                            if e["release_id"] != ledger.KNOWN_RELEASE_ID))
        encoded = ledger.canonical(doc).decode()
        for private in (TOKEN, PRIVATE_TAG, str(PRIVATE_ID)):
            self.assertNotIn(private, encoded)
        ledger.require_inventory_key(inventory, TOKEN, required=True)
        self.assertTrue(ledger.historically_seen(inventory, TOKEN, PRIVATE_ID, "new-tag"))
        self.assertTrue(ledger.historically_seen(inventory, TOKEN, 1, PRIVATE_TAG))
        self.assertTrue(ledger.historically_seen(inventory, TOKEN, 1, "v0.1.15"))

    def test_incomplete_history_invalid_cutoff_and_nonempty_legacy_fail(self):
        releases, refs = fixtures()
        with self.assertRaises(ledger.LedgerError):
            snapshot(releases=releases[1:])  # omit a known published identity
        with self.assertRaises(cutover.CutoverError):
            snapshot(cutoff="2026-12-01T00:30:00Z")
        with self.assertRaises(cutover.CutoverError):
            snapshot(legacy={"schema": 1, "releases": [{"release_id": 123, "tag_name": "old"}]})
        invalid = copy.deepcopy(releases)
        invalid[0]["published_at"] = "2027-01-01T00:00:00Z"
        with self.assertRaises(cutover.CutoverError):
            snapshot(releases=invalid)

    def test_snapshot_fingerprint_detects_tag_release_or_cutoff_changes(self):
        original = snapshot()
        releases, refs = fixtures()
        refs.append({"ref": "refs/tags/new-public-tag", "object": {"type": "commit", "sha": "c" * 40}})
        self.assertNotEqual(ledger.digest(original), ledger.digest(snapshot(refs=refs)))
        self.assertNotEqual(ledger.digest(original), ledger.digest(snapshot(cutoff="2026-12-01T05:00:00Z")))
        releases[-1]["id"] += 1
        self.assertNotEqual(ledger.digest(original), ledger.digest(snapshot(releases=releases)))
        _, moved_refs = fixtures()
        self.assertEqual(cutover.tag_ref_snapshot(moved_refs, TOKEN), cutover.tag_ref_snapshot(list(reversed(moved_refs)), TOKEN))
        moved_refs[-1]["object"]["sha"] = "d" * 40  # same tag name, different immutable ref object
        self.assertNotEqual(ledger.digest(original), ledger.digest(snapshot(refs=moved_refs)))
        moved_refs[-1]["object"]["sha"] = "invalid"
        with self.assertRaises(cutover.CutoverError):
            snapshot(refs=moved_refs)
        original_fingerprint = cutover.preview_fingerprint(original, "a" * 40, "a" * 40, "c" * 40)
        self.assertNotEqual(original_fingerprint, cutover.preview_fingerprint(original, "b" * 40, "a" * 40, "c" * 40))
        self.assertNotEqual(original_fingerprint, cutover.preview_fingerprint(original, "a" * 40, "b" * 40, "c" * 40))
        self.assertNotEqual(original_fingerprint, cutover.preview_fingerprint(original, None, "a" * 40, "c" * 40))
        self.assertNotEqual(original_fingerprint, cutover.preview_fingerprint(original, "a" * 40, "a" * 40, "d" * 40))
        with self.assertRaises(cutover.CutoverError):
            cutover.preview_fingerprint(original, "invalid", "a" * 40, "c" * 40)

    def test_collect_refuses_tag_object_drift_during_snapshot(self):
        releases, refs = fixtures()
        changed = copy.deepcopy(refs)
        changed[-1]["object"]["sha"] = "c" * 40
        with mock.patch.object(state, "_api", side_effect=[releases, refs, changed]), \
             mock.patch.object(cutover.identity, "resolve_tag_commit", return_value="a" * 40):
            with self.assertRaisesRegex(cutover.CutoverError, "Tag ref objects changed"):
                cutover.collect("sascio/MyBridge", {"schema": 1, "releases": []}, CUTOFF, TOKEN)

    def test_preview_requires_no_false_freeze_claim_but_initialization_does(self):
        with tempfile.TemporaryDirectory() as tmp:
            legacy = Path(tmp, "legacy.json")
            legacy.write_text(json.dumps({"schema": 1, "releases": []}))
            env = {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "workflow_dispatch",
                   "GITHUB_REPOSITORY": "sascio/MyBridge", "GITHUB_REPOSITORY_ID": "1349123452",
                   "GITHUB_SHA": "c" * 40, "GH_TOKEN": "fake-token", "TELEGRAM_BOT_TOKEN": TOKEN,
                   "GITHUB_STEP_SUMMARY": str(Path(tmp, "summary.md"))}
            def api(endpoint):
                if endpoint.endswith("/actions/workflows/407947021"):
                    return {"state": "disabled_manually", "path": ".github/workflows/telegram-release-announcement.yml"}
                return {"id": 1349123452, "full_name": "sascio/MyBridge", "default_branch": "main"}
            def arguments(mode):
                return ["tool", "--mode", mode, "--cutoff", CUTOFF, "--fingerprint", "",
                        "--acknowledge", "", "--source-dir", tmp]
            output = io.StringIO()
            with mock.patch.dict(os.environ, env), mock.patch.object(ledger, "LEGACY_PATH", str(legacy)), \
                 mock.patch.object(cutover, "_require_disabled_gates"), mock.patch.object(state, "_api", side_effect=api), \
                 mock.patch.object(ledger, "GitLedger") as store_class, \
                 mock.patch.object(cutover, "remote_head", return_value="a" * 40), \
                 mock.patch.object(cutover, "collect", return_value=snapshot()), \
                 mock.patch.object(cutover, "initialize") as initializer, contextlib.redirect_stdout(output):
                store_class.return_value._git.return_value.stdout = ("c" * 40).encode()
                with mock.patch.object(sys, "argv", arguments("preview")):
                    self.assertEqual(cutover.main(), 0)
                self.assertIn("Unapproved preview only", output.getvalue())
                step_summary = Path(env["GITHUB_STEP_SUMMARY"]).read_text()
                self.assertIn("\n```json\n", step_summary)
                self.assertIn("::notice title=Unapproved Telegram history preview::", output.getvalue())
                for rendered in (output.getvalue(), step_summary):
                    self.assertNotIn(PRIVATE_TAG, rendered)
                    self.assertNotIn(TOKEN, rendered)
                with mock.patch.dict(os.environ, {"GITHUB_SHA": "d" * 40}), \
                     mock.patch.object(sys, "argv", arguments("preview")):
                    self.assertEqual(cutover.main(), 1)
                with mock.patch.object(sys, "argv", arguments("initialize")):
                    self.assertEqual(cutover.main(), 1)
                initializer.assert_not_called()

    def test_draft_is_not_resolved_as_a_public_release(self):
        releases, refs = fixtures()
        resolved = []
        cutover.build_snapshot(releases, refs, {"schema": 1, "releases": []}, CUTOFF, TOKEN,
                               resolve=lambda tag: resolved.append(tag) or "a" * 40, now=NOW)
        self.assertEqual(len(resolved), 15)
        self.assertNotIn(PRIVATE_TAG, resolved)


class InitializationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.remote, self.work = self.root / "remote.git", self.root / "source"
        self.git("init", "--bare", "--initial-branch=main", str(self.remote), cwd=self.root)
        self.git("clone", str(self.remote), str(self.work), cwd=self.root)
        self.git("config", "user.name", "Test", cwd=self.work)
        self.git("config", "user.email", "test@example.invalid", cwd=self.work)
        (self.work / "source.txt").write_text("unrelated source code", encoding="utf-8")
        legacy_path = self.work / ledger.LEGACY_PATH
        legacy_path.parent.mkdir(parents=True, exist_ok=True)
        legacy_path.write_bytes(ledger.canonical({"schema": 1, "releases": []}))
        self.git("add", "source.txt", ledger.LEGACY_PATH, cwd=self.work)
        self.git("commit", "-m", "main fixture", cwd=self.work)
        self.git("push", "origin", "HEAD:refs/heads/main", cwd=self.work)
        self.store = ledger.GitLedger(str(self.work), state.STATE_BRANCH)

    @staticmethod
    def git(*args, cwd):
        return subprocess.run(["git", *args], cwd=cwd, check=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE).stdout

    def test_root_is_single_file_and_does_not_touch_main_or_worktree(self):
        main_before = self.git("rev-parse", "refs/heads/main", cwd=self.remote)
        checkout_before = self.git("rev-parse", "HEAD", cwd=self.work)
        expected = snapshot()
        head = cutover.initialize(self.store, expected, expected_parent=None, default_branch="main",
                                   expected_main=main_before.decode().strip())
        actual = self.store.read()
        self.assertEqual(actual.parent, head)
        self.assertEqual(actual.document, expected)
        self.assertEqual(self.git("ls-tree", "-r", "--name-only", head, cwd=self.work).splitlines(),
                         [ledger.LEDGER_PATH.encode()])
        self.assertEqual(self.git("rev-list", "--parents", "-n", "1", head, cwd=self.work).split(), [head.encode()])
        self.assertEqual(self.git("rev-parse", "refs/heads/main", cwd=self.remote), main_before)
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.work), checkout_before)
        self.assertEqual(self.git("status", "--porcelain", cwd=self.work), b"")
        with self.assertRaises(cutover.CutoverError):
            cutover.initialize(self.store, expected, expected_parent=None, default_branch="main",
                               expected_main=main_before.decode().strip())
        self.assertEqual(self.store.read().parent, head)

    def test_changed_default_tip_refuses_orphan_initialization(self):
        main = cutover.remote_head(self.store, "main")
        self.assertIsNotNone(main)
        with self.assertRaises(cutover.CutoverError):
            cutover.initialize(self.store, snapshot(), expected_parent=None, default_branch="main", expected_main="f" * 40)
        self.assertIsNone(cutover.remote_head(self.store, state.STATE_BRANCH))
        self.assertEqual(cutover.remote_head(self.store, "main"), main)

    def test_existing_exact_main_tip_gets_a_single_file_fast_forward_child(self):
        main_before = self.git("rev-parse", "refs/heads/main", cwd=self.remote).decode().strip()
        self.git("push", "origin", "HEAD:refs/heads/telegram-delivery-state", cwd=self.work)
        self.assertEqual(cutover.remote_head(self.store, state.STATE_BRANCH), main_before)
        with self.assertRaises(ledger.LedgerError):
            self.store.read()  # inherited application tree is not yet a valid state tip
        expected = snapshot()
        head = cutover.initialize(self.store, expected, expected_parent=main_before, default_branch="main",
                                   expected_main=main_before)
        self.assertEqual(self.store.read().document, expected)
        self.assertEqual(self.git("rev-list", "--parents", "-n", "1", head, cwd=self.work).split(),
                         [head.encode(), main_before.encode()])
        self.assertEqual(self.git("ls-tree", "-r", "--name-only", head, cwd=self.work).splitlines(),
                         [ledger.LEDGER_PATH.encode()])
        self.assertEqual(self.git("rev-parse", "refs/heads/main", cwd=self.remote).decode().strip(), main_before)
        self.assertEqual(self.git("status", "--porcelain", cwd=self.work), b"")
        with self.assertRaises(cutover.CutoverError):
            cutover.initialize(self.store, expected, expected_parent=head, default_branch="main",
                               expected_main=main_before)

    def test_existing_branch_that_advanced_or_differs_from_reviewed_main_is_never_overwritten(self):
        main_before = self.git("rev-parse", "refs/heads/main", cwd=self.remote).decode().strip()
        self.git("push", "origin", "HEAD:refs/heads/telegram-delivery-state", cwd=self.work)
        (self.work / "source.txt").write_text("unreviewed changed source", encoding="utf-8")
        self.git("commit", "-am", "concurrent fixture change", cwd=self.work)
        self.git("push", "origin", "HEAD:refs/heads/telegram-delivery-state", cwd=self.work)
        advanced = cutover.remote_head(self.store, state.STATE_BRANCH)
        for expected in (main_before, advanced):
            with self.subTest(expected=expected), self.assertRaises(cutover.CutoverError):
                cutover.initialize(self.store, snapshot(), expected_parent=expected, default_branch="main",
                                   expected_main=main_before)
        self.assertEqual(cutover.remote_head(self.store, state.STATE_BRANCH), advanced)
        self.assertEqual(self.git("rev-parse", "refs/heads/main", cwd=self.remote).decode().strip(), main_before)

    def test_ref_drift_immediately_before_push_is_detected(self):
        head = cutover.remote_head(self.store, "main")
        self.git("push", "origin", "HEAD:refs/heads/telegram-delivery-state", cwd=self.work)
        with mock.patch.object(cutover, "remote_head", side_effect=[head, head, "f" * 40]):
            with self.assertRaisesRegex(cutover.CutoverError, "no state push"):
                cutover.initialize(self.store, snapshot(), expected_parent=head, default_branch="main", expected_main=head)
        self.assertEqual(cutover.remote_head(self.store, state.STATE_BRANCH), head)
        self.assertEqual(cutover.remote_head(self.store, "main"), head)

    def test_existing_main_based_branch_requires_exact_preserved_legacy_ledger(self):
        path = self.work / ledger.LEGACY_PATH
        path.write_bytes(ledger.canonical({"schema": 2, "releases": []}))
        self.git("commit", "-am", "different legacy fixture", cwd=self.work)
        self.git("push", "origin", "HEAD:refs/heads/main", cwd=self.work)
        self.git("push", "origin", "HEAD:refs/heads/telegram-delivery-state", cwd=self.work)
        head = cutover.remote_head(self.store, state.STATE_BRANCH)
        with self.assertRaises(cutover.CutoverError):
            cutover.initialize(self.store, snapshot(), expected_parent=head, default_branch="main", expected_main=head)
        self.assertEqual(cutover.remote_head(self.store, state.STATE_BRANCH), head)


if __name__ == "__main__":
    unittest.main(verbosity=2)
