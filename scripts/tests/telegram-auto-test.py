#!/usr/bin/env python3
"""Offline tests for automatic bootstrap and scheduled future-release polling."""
from __future__ import annotations

import contextlib
import copy
import datetime as dt
import importlib.util
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
from offline import sitecustomize as offline_guard  # noqa: E402,F401
sys.path.insert(0, str(SCRIPTS))
import telegram_auto as auto
import telegram_cutover as cutover
import telegram_ledger as ledger
import telegram_publish as publish
import telegram_state as state

# Reuse the fake Telegram server from the existing offline delivery suite.
spec = importlib.util.spec_from_file_location("telegram_idempotent_fixtures", SCRIPTS / "tests/telegram-idempotent-test.py")
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
TOKEN, CHAT, API_HASH = fixtures.TOKEN, fixtures.CHAT, fixtures.API_HASH
NOW = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def utc(value):
    return value.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def snapshot(*, observed_tags=None):
    releases = [{"id": rid, "tag_name": tag, "draft": False, "published_at": utc(NOW - dt.timedelta(days=3))}
                for rid, tag in ledger.KNOWN_HISTORICAL_RELEASES.items()]
    releases.append({"id": 991234567, "tag_name": "private-draft", "draft": True, "published_at": None})
    refs = [{"ref": "refs/tags/" + item["tag_name"], "object": {"type": "commit", "sha": "a" * 40}}
            for item in releases]
    return cutover.build_snapshot(releases, refs, {"schema": 1, "releases": []},
                                  ledger.BOOTSTRAP_PENDING_FENCE, TOKEN, resolve=lambda _: "a" * 40,
                                  now=NOW, observed_old_tags=observed_tags or set())


def pending():
    doc = snapshot(observed_tags={"deleted-history"})
    doc["activation"]["ready"] = False
    ledger.validate(doc)
    return doc


def ready():
    doc = pending()
    doc["activation"]["ready"] = True
    doc["activation"]["published_after"] = utc(NOW - dt.timedelta(hours=1))
    ledger.validate(doc)
    return doc


def payload(release_id, tag, when, **overrides):
    result = {"id": release_id, "tag_name": tag, "name": "StreamBridge " + tag,
              "body": "## What's New\n- Improved playback.",
              "html_url": "https://github.com/sascio/MyBridge/releases/tag/" + tag,
              "created_at": utc(when - dt.timedelta(minutes=1)), "published_at": utc(when),
              "draft": False, "prerelease": False, "assets": []}
    result.update(overrides)
    return result


class NoSendTransport:
    def __init__(self, store, *, logout=True, connect_error=False):
        self.store, self.logout, self.connect_error = store, logout, connect_error
        self.calls = []

    def connect(self):
        assert self.store.read().document["session"] is not None
        self.calls.append("connect")
        if self.connect_error:
            raise RuntimeError("never log a remote exception")

    def close(self):
        self.calls.append("logout")
        return self.logout

    def send(self, *args, **kwargs):
        raise AssertionError("readiness must never send")

    def prepare_document(self, *args, **kwargs):
        raise AssertionError("readiness must never upload")


class ContextTests(unittest.TestCase):
    def test_only_enabled_default_workflow_schedule_or_main_push_is_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            event = Path(tmp, "event.json")
            repository = {"id": auto.REPOSITORY_ID, "full_name": auto.REPOSITORY, "default_branch": "main"}
            env = {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "schedule", "GITHUB_REF": "refs/heads/main",
                   "GITHUB_REPOSITORY": auto.REPOSITORY, "GITHUB_REPOSITORY_ID": str(auto.REPOSITORY_ID),
                   "GITHUB_WORKFLOW_REF": auto.REPOSITORY + "/" + auto.WORKFLOW + "@refs/heads/main",
                   "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1", "GH_TOKEN": "not-a-real-token",
                   "GITHUB_EVENT_PATH": str(event), "TELEGRAM_PUBLISHING_ENABLED": "true"}
            # Scheduled events may omit the repository object.
            event.write_text('{"schedule":"*/5 * * * *"}')
            with mock.patch.dict(os.environ, env, clear=True):
                self.assertEqual(auto.require_context("main"), (123, 1))
                for key, value in (("GITHUB_EVENT_NAME", "workflow_dispatch"), ("GITHUB_EVENT_NAME", "release"),
                                   ("GITHUB_REF", "refs/heads/other"), ("GITHUB_REPOSITORY_ID", "1"),
                                   ("GITHUB_WORKFLOW_REF", auto.REPOSITORY + "/.github/workflows/build.yml@refs/heads/main"),
                                   ("TELEGRAM_PUBLISHING_ENABLED", "false"), ("GH_TOKEN", ""),
                                   ("GITHUB_RUN_ID", "0")):
                    with self.subTest(key=key, value=value), mock.patch.dict(os.environ, {key: value}):
                        with self.assertRaises(auto.AutomaticDeliveryError):
                            auto.require_context("main")
                event.write_text(json.dumps({"repository": repository, "ref": "refs/heads/main", "deleted": False}))
                with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "push"}):
                    self.assertEqual(auto.require_context("main"), (123, 1))
                    event.write_text(json.dumps({"repository": repository, "ref": "refs/heads/other", "deleted": False}))
                    with self.assertRaises(auto.AutomaticDeliveryError):
                        auto.require_context("main")


    def test_explicit_no_send_initialization_requires_default_branch_and_live_switch_off(self):
        with tempfile.TemporaryDirectory() as tmp:
            event = Path(tmp, "event.json")
            repository = {"id": auto.REPOSITORY_ID, "full_name": auto.REPOSITORY, "default_branch": "main"}
            payload = {"repository": repository, "inputs": {"initialize_state": "true", "preflight": "false",
                       "dry_run": "false", "resume_unsent": "true", "tag": "v9.9.9", "release_id": "123"}}
            event.write_text(json.dumps(payload))
            env = {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
                   "GITHUB_REPOSITORY": auto.REPOSITORY, "GITHUB_REPOSITORY_ID": str(auto.REPOSITORY_ID),
                   "GITHUB_WORKFLOW_REF": auto.REPOSITORY + "/" + auto.WORKFLOW + "@refs/heads/main",
                   "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1", "GH_TOKEN": "not-a-real-token",
                   "GITHUB_EVENT_PATH": str(event), "TELEGRAM_PUBLISHING_ENABLED": ""}
            with mock.patch.dict(os.environ, env, clear=True):
                self.assertEqual(auto.require_context("main"), (123, 1))
                for key, value in (("GITHUB_REF", "refs/heads/other"), ("TELEGRAM_PUBLISHING_ENABLED", "true"),
                                   ("GITHUB_REPOSITORY_ID", "1"), ("GITHUB_WORKFLOW_REF", "untrusted/workflow")):
                    with self.subTest(key=key, value=value), mock.patch.dict(os.environ, {key: value}):
                        with self.assertRaises(auto.AutomaticDeliveryError):
                            auto.require_context("main")
                for change in ({"initialize_state": False}, {"initialize_state": "false"}, {"initialize_state": 1},
                               {"preflight": True}, {"preflight": "true"}, {"preflight": 0}):
                    changed = copy.deepcopy(payload)
                    changed["inputs"].update(change)
                    event.write_text(json.dumps(changed))
                    with self.subTest(change=change), self.assertRaises(auto.AutomaticDeliveryError):
                        auto.require_context("main")
                for missing in ("repository", "inputs"):
                    changed = copy.deepcopy(payload)
                    del changed[missing]
                    event.write_text(json.dumps(changed))
                    with self.subTest(missing=missing), self.assertRaises(auto.AutomaticDeliveryError):
                        auto.require_context("main")


class InitializationCliTests(unittest.TestCase):
    def test_dispatch_bootstraps_but_cannot_enter_poll_or_delivery_path(self):
        document = ready()
        document["identity"] = publish.identity_binding(TOKEN, 777, -1002000000001)
        snapshot = ledger.Snapshot("c" * 40, document, ledger.canonical(document))
        store = fixtures.MemoryStore(document)
        secrets = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
                   "TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": API_HASH}
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as tmp:
            summary = Path(tmp, "summary.md")
            env = {"SOURCE_BRANCH": "main", "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_SHA": "b" * 40,
                   "GITHUB_STEP_SUMMARY": str(summary)}
            with mock.patch.dict(os.environ, env, clear=True), \
                 mock.patch.object(auto, "require_context", return_value=(123, 1)), \
                 mock.patch.object(ledger, "GitLedger", return_value=store), \
                 mock.patch.object(publish, "require_secret", side_effect=secrets.__getitem__), \
                 mock.patch.object(state, "require_disabled_legacy"), \
                 mock.patch.object(cutover, "remote_head", return_value="b" * 40), \
                 mock.patch.object(publish, "tooling_commit", return_value="b" * 40), \
                 mock.patch.object(auto, "bootstrap", return_value=snapshot) as bootstrap, \
                 mock.patch.object(auto, "poll") as poll, \
                 mock.patch.object(publish, "publish") as sender, contextlib.redirect_stdout(output):
                self.assertEqual(auto.main(), 0)
                bootstrap.assert_called_once()
                poll.assert_not_called()
                sender.assert_not_called()
            report = summary.read_text()
        self.assertIn("Live publishing remains off", output.getvalue())
        self.assertIn("actual delivery was not tested", report)
        self.assertIn(snapshot.parent, report)
        self.assertNotIn(TOKEN, output.getvalue() + report)

    def test_readiness_report_rejects_unready_or_locked_state(self):
        locked = ready()
        locked["identity"] = "b" * 64
        locked["session"] = {"owner": "prior-run"}
        for document in (pending(), ready(), locked):
            snapshot = ledger.Snapshot("c" * 40, document, ledger.canonical(document))
            with self.assertRaises(auto.AutomaticDeliveryError):
                auto.report_initialization(snapshot)


class LedgerModeCliTests(unittest.TestCase):
    def test_unready_ledger_blocks_event_before_tag_resolution_or_publish(self):
        release = fixtures.sample_release()
        values = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
                  "TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": API_HASH}
        out = io.StringIO()
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "release"}), \
             mock.patch.object(publish, "require_workflow_context", return_value=(auto.REPOSITORY, release.id, release.tag_name)), \
             mock.patch.object(ledger, "publishing_ledger_mode", return_value=True), \
             mock.patch.object(state, "load_config", return_value=state.SameRepositoryConfig(auto.REPOSITORY, auto.REPOSITORY_ID)), \
             mock.patch.object(publish, "require_secret", side_effect=values.__getitem__), \
             mock.patch.object(publish.announce, "load_release", return_value=release), \
             mock.patch.object(ledger, "GitLedger", return_value=fixtures.MemoryStore(pending())), \
             mock.patch.object(publish.identity, "resolve_tag_commit") as resolve, \
             mock.patch.object(publish, "publish") as sender, contextlib.redirect_stdout(out):
            self.assertEqual(publish.main(["--release-json", "fixture.json", "--branch", "main", "--state-dir", "state"]), 1)
            resolve.assert_not_called()
            sender.assert_not_called()
        self.assertIn("not yet ready", out.getvalue())
        self.assertNotIn(TOKEN, out.getvalue())

    def test_manual_recovery_cannot_create_a_new_plan_or_publish_old_tags(self):
        store = fixtures.MemoryStore(ready())
        server = fixtures.FakeServer(store)
        for release in (fixtures.sample_release(release_id=987654, tag="v1.0.0"),
                        fixtures.sample_release(release_id=987655, tag="deleted-history")):
            with self.subTest(tag=release.tag_name), self.assertRaises(publish.PublishError):
                publish.publish(release, store, token=TOKEN, chat=CHAT, api_id=12345, api_hash=API_HASH,
                                source_sha="a" * 40, workflow_run_id=123, run_attempt=1, tooling_sha="b" * 40,
                                resume_unsent=True, policy_check=lambda: None, allow_new_release=False,
                                require_protected_inventory=True,
                                transport_factory=lambda *_: fixtures.FakeTransport(server),
                                bot_lookup=lambda *_: (777, -1002000000001, CHAT), sleeper=lambda _: None)
        self.assertEqual(store.doc["releases"], [])
        self.assertEqual(server.calls, [])


class LockedPreflightTests(unittest.TestCase):
    def setUp(self):
        self.store = fixtures.MemoryStore(pending())
        self.transport = NoSendTransport(self.store)
        self.factory = mock.Mock(return_value=self.transport)
        self.lookup = mock.Mock(return_value=(777, -1002000000001, CHAT))

    def probe(self):
        return auto.verify_bot_with_lock(self.store, token=TOKEN, chat=CHAT, api_id=12345, api_hash=API_HASH,
                                         run_id=123, policy_check=lambda: None, bot_lookup=self.lookup,
                                         transport_factory=self.factory)

    def test_login_is_under_durable_lock_and_unlocks_only_after_logout(self):
        self.probe()
        self.assertEqual(self.transport.calls, ["connect", "logout"])
        self.assertIsNone(self.store.doc["session"])
        self.assertEqual(self.store.doc["identity"], publish.identity_binding(TOKEN, 777, -1002000000001))
        self.assertTrue(any(d["session"] is not None for d in self.store.history))
        self.assertEqual(self.store.doc["releases"], [])
        self.assertFalse(self.store.doc["activation"]["ready"])

    def test_ambiguous_logout_leaves_lock_and_blocks_automatic_retry(self):
        self.transport.logout = False
        with self.assertRaisesRegex(auto.AutomaticDeliveryError, "lock remains"):
            self.probe()
        self.assertIsNotNone(self.store.doc["session"])
        with self.assertRaises(auto.AutomaticDeliveryError):
            self.probe()
        self.assertEqual(self.transport.calls, ["connect", "logout"])
        self.assertEqual(self.factory.call_count, 1)

    def test_login_failure_with_confirmed_logout_clears_lock_but_does_not_activate(self):
        self.transport.connect_error = True
        with self.assertRaises(auto.AutomaticDeliveryError):
            self.probe()
        self.assertEqual(self.transport.calls, ["connect", "logout"])
        self.assertIsNone(self.store.doc["session"])
        self.assertFalse(self.store.doc["activation"]["ready"])

    def test_bot_api_failure_never_starts_transport(self):
        self.lookup.side_effect = RuntimeError(TOKEN)
        with self.assertRaises(auto.AutomaticDeliveryError) as caught:
            self.probe()
        self.assertNotIn(TOKEN, str(caught.exception))
        self.factory.assert_not_called()
        self.assertIsNone(self.store.doc["session"])

    def test_already_locked_or_ready_never_starts_preflight(self):
        for doc in (ready(), pending()):
            if doc["activation"]["ready"] is False:
                doc["session"] = {"owner": "previous-run"}
            self.store = fixtures.MemoryStore(doc)
            with self.subTest(ready=doc["activation"]["ready"]), self.assertRaises(auto.AutomaticDeliveryError):
                self.probe()
        self.factory.assert_not_called()


class LedgerTransitionTests(unittest.TestCase):
    def test_history_union_is_protected_and_only_additive(self):
        first, newer = pending(), snapshot(observed_tags={"deleted-history", "deleted-later"})
        combined = auto.merge_exclusions(first["inventory"], newer["inventory"])
        self.assertTrue(ledger.historically_seen(combined, TOKEN, 1, "deleted-history"))
        self.assertTrue(ledger.historically_seen(combined, TOKEN, 1, "deleted-later"))
        self.assertNotIn(b"deleted-later", ledger.canonical(combined))
        self.assertTrue(ledger._bootstrap_inventory_extends(first["inventory"], combined))
        bad = copy.deepcopy(combined)
        bad["protected"]["tag_digests"].remove(ledger._inventory_mac(TOKEN, "tag", "deleted-history"))
        self.assertFalse(ledger._bootstrap_inventory_extends(first["inventory"], bad))

    def test_cannot_arm_without_persisted_identity_and_complete_archived_verification(self):
        old = pending()
        new = copy.deepcopy(old)
        new["activation"].update(ready=True, published_after=utc(NOW))
        ledger.validate(new)
        with self.assertRaises(ledger.LedgerError):
            ledger.validate_append_only(old, new)
        old["identity"] = new["identity"] = "b" * 64
        with self.assertRaises(ledger.LedgerError):
            ledger.validate_append_only(old, new)
        old["verification"] = [{"id": f"{i:032x}", "run_id": 123, "phase": phase, "status": "archived"}
                               for i, phase in enumerate(("interrupted", "unknown-simulation", "first", "second", "race-a", "race-b"), 1)]
        new["verification"] = copy.deepcopy(old["verification"])
        ledger.validate(old)
        ledger.validate(new)
        ledger.validate_append_only(old, new)
        # A future fence recreates the pre-fence unseen-tag gap, while a stale
        # snapshot could silently backdate the publication cutoff.
        for unsafe in (NOW + dt.timedelta(minutes=10), NOW - dt.timedelta(minutes=16)):
            altered = copy.deepcopy(new)
            altered["activation"]["published_after"] = utc(unsafe)
            with self.subTest(fence=unsafe), self.assertRaises(ledger.LedgerError):
                ledger.validate_append_only(old, altered)
        reversed_gate = copy.deepcopy(new)
        reversed_gate["activation"]["published_after"] = ledger.BOOTSTRAP_PENDING_FENCE
        reversed_gate["activation"]["ready"] = False
        with self.assertRaises(ledger.LedgerError):
            ledger.validate_append_only(new, reversed_gate)


class LocalGitBootstrapTests(unittest.TestCase):
    def setUp(self):
        self.old_protocol = os.environ.get("GIT_ALLOW_PROTOCOL")
        os.environ["GIT_ALLOW_PROTOCOL"] = "file"
        self.addCleanup(self.restore_protocol)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.remote, self.work = self.root / "remote.git", self.root / "checkout"
        self.git("init", "--bare", "--initial-branch=main", str(self.remote), cwd=self.root)
        self.git("clone", str(self.remote), str(self.work), cwd=self.root)
        self.git("config", "user.name", "Offline fixture", cwd=self.work)
        self.git("config", "user.email", "fixture@example.invalid", cwd=self.work)
        legacy = self.work / ledger.LEGACY_PATH
        legacy.parent.mkdir(parents=True, exist_ok=True)
        legacy.write_bytes(ledger.canonical({"schema": 1, "releases": []}))
        (self.work / "source.txt").write_text("base")
        self.git("add", ".", cwd=self.work)
        self.git("commit", "-m", "pre-existing base", cwd=self.work)
        self.base = self.git("rev-parse", "HEAD", cwd=self.work).decode().strip()
        self.git("push", "origin", "HEAD:refs/heads/main", "HEAD:refs/heads/telegram-delivery-state", cwd=self.work)
        (self.work / "source.txt").write_text("merged feature")
        self.git("commit", "-am", "simulated merge", cwd=self.work)
        self.git("push", "origin", "HEAD:refs/heads/main", cwd=self.work)
        self.main = self.git("rev-parse", "HEAD", cwd=self.work).decode().strip()
        self.store = ledger.GitLedger(str(self.work), state.STATE_BRANCH)

    def restore_protocol(self):
        if self.old_protocol is None:
            os.environ.pop("GIT_ALLOW_PROTOCOL", None)
        else:
            os.environ["GIT_ALLOW_PROTOCOL"] = self.old_protocol

    @staticmethod
    def git(*args, cwd):
        return subprocess.run(["git", *args], cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout

    def test_bootstrap_fast_forwards_pinned_ancestor_verifies_and_never_sends(self):
        first, fresh = snapshot(observed_tags={"deleted-before"}), snapshot(observed_tags={"deleted-before", "deleted-during"})
        trace, values, scan_times = [], iter((first, first, fresh, fresh)), []
        def collect(*_args, **_kwargs):
            trace.append("scan")
            return next(values)
        def clock():
            trace.append("clock")
            value = dt.datetime.now(dt.timezone.utc)
            scan_times.append(value)
            return value
        collected = mock.Mock(side_effect=collect)
        transport = NoSendTransport(self.store)
        def probe(store, **kwargs):
            auto.verify_bot_with_lock(store, **kwargs, bot_lookup=lambda *_: (777, -1002000000001, CHAT),
                                      transport_factory=lambda *_: transport)
        with mock.patch.object(auto, "BOOTSTRAP_BASE", self.base):
            result = auto.bootstrap(self.store, default_branch="main", main_head=self.main, token=TOKEN, chat=CHAT,
                                    api_id=12345, api_hash=API_HASH, run_id=123, policy_check=lambda: None,
                                    collect=collected, probe=probe, now=clock)
            before = result.parent
            again = auto.bootstrap(self.store, default_branch="main", main_head=self.main, token=TOKEN, chat=CHAT,
                                   api_id=12345, api_hash=API_HASH, run_id=124, policy_check=lambda: None,
                                   collect=collected, probe=mock.Mock(side_effect=AssertionError("must not repeat")))
        self.assertEqual(again.parent, before)
        self.assertEqual(collected.call_count, 4)
        self.assertEqual(trace, ["scan", "scan", "clock", "scan", "scan", "clock"])
        self.assertEqual(transport.calls, ["connect", "logout"])
        doc = result.document
        self.assertIs(doc["activation"]["ready"], True)
        self.assertEqual(doc["activation"]["published_after"], utc(scan_times[0]))
        self.assertLessEqual(ledger.timestamp(doc["activation"]["published_after"]), dt.datetime.now(dt.timezone.utc))
        self.assertEqual(doc["releases"], [])
        self.assertIsNone(doc["session"])
        self.assertEqual({r["status"] for r in doc["verification"]}, {"archived"})
        self.assertTrue(ledger.historically_seen(doc["inventory"], TOKEN, 1, "deleted-before"))
        self.assertTrue(ledger.historically_seen(doc["inventory"], TOKEN, 1, "deleted-during"))
        # Even a later publication with a tag captured only by the final scan
        # must never create a send plan. This is a no-I/O fake poll.
        cutoff = ledger.timestamp(doc["activation"]["published_after"])
        later = payload(987650, "deleted-during", cutoff + dt.timedelta(seconds=1))
        sender = mock.Mock(side_effect=AssertionError("an observed old tag must not be sent"))
        result_count = auto.poll(fixtures.MemoryStore(doc), repository=auto.REPOSITORY,
                                 repository_id=auto.REPOSITORY_ID, token=TOKEN, chat=CHAT,
                                 api_id=12345, api_hash=API_HASH, run_id=125, run_attempt=1,
                                 tooling_sha="b" * 40, policy_check=lambda: None,
                                 api=lambda *_args, **_kwargs: [later], send_release=sender,
                                 now=lambda: cutoff + dt.timedelta(seconds=2))
        self.assertEqual(result_count, 0)
        sender.assert_not_called()
        self.assertEqual(self.git("ls-tree", "-r", "--name-only", before, cwd=self.work).splitlines(), [ledger.LEDGER_PATH.encode()])
        self.assertEqual(self.git("merge-base", "--is-ancestor", self.base, before, cwd=self.work), b"")
        self.assertEqual(self.git("rev-parse", "refs/heads/main", cwd=self.remote).decode().strip(), self.main)
        self.assertEqual(self.git("status", "--porcelain", cwd=self.work), b"")

    def test_final_scan_drift_leaves_ledger_unready(self):
        base, changed = snapshot(), snapshot(observed_tags={"appeared-during-final-scan"})
        transport = NoSendTransport(self.store)
        def probe(store, **kwargs):
            auto.verify_bot_with_lock(store, **kwargs, bot_lookup=lambda *_: (777, -1002000000001, CHAT),
                                      transport_factory=lambda *_: transport)
        with mock.patch.object(auto, "BOOTSTRAP_BASE", self.base):
            with self.assertRaisesRegex(auto.AutomaticDeliveryError, "changed before activation"):
                auto.bootstrap(self.store, default_branch="main", main_head=self.main, token=TOKEN, chat=CHAT,
                               api_id=12345, api_hash=API_HASH, run_id=123, policy_check=lambda: None,
                               collect=mock.Mock(side_effect=[base, base, base, changed]), probe=probe)
        document = self.store.read().document
        self.assertIs(document["activation"]["ready"], False)
        self.assertEqual(document["activation"]["published_after"], ledger.BOOTSTRAP_PENDING_FENCE)
        self.assertEqual(document["releases"], [])
        self.assertIsNone(document["session"])
        self.assertEqual(transport.calls, ["connect", "logout"])

    def test_clock_reversal_or_slow_final_scan_leaves_ledger_unready(self):
        base = snapshot()
        transport = NoSendTransport(self.store)
        def probe(store, **kwargs):
            auto.verify_bot_with_lock(store, **kwargs, bot_lookup=lambda *_: (777, -1002000000001, CHAT),
                                      transport_factory=lambda *_: transport)
        clock = mock.Mock(side_effect=[NOW, NOW - dt.timedelta(seconds=1)])
        with mock.patch.object(auto, "BOOTSTRAP_BASE", self.base):
            with self.assertRaisesRegex(auto.AutomaticDeliveryError, "trusted clock window"):
                auto.bootstrap(self.store, default_branch="main", main_head=self.main, token=TOKEN, chat=CHAT,
                               api_id=12345, api_hash=API_HASH, run_id=123, policy_check=lambda: None,
                               collect=mock.Mock(return_value=base), probe=probe, now=clock)
        document = self.store.read().document
        self.assertIs(document["activation"]["ready"], False)
        self.assertEqual(document["releases"], [])
        self.assertIsNone(document["session"])
        clock = mock.Mock(side_effect=[NOW, NOW + dt.timedelta(minutes=16)])
        with mock.patch.object(auto, "BOOTSTRAP_BASE", self.base):
            with self.assertRaisesRegex(auto.AutomaticDeliveryError, "trusted clock window"):
                auto.bootstrap(self.store, default_branch="main", main_head=self.main, token=TOKEN, chat=CHAT,
                               api_id=12345, api_hash=API_HASH, run_id=124, policy_check=lambda: None,
                               collect=mock.Mock(return_value=base), probe=probe, now=clock)
        self.assertIs(self.store.read().document["activation"]["ready"], False)

    def test_ambiguous_bootstrap_logout_keeps_state_unready_across_runs(self):
        base = snapshot()
        transport = NoSendTransport(self.store, logout=False)
        def probe(store, **kwargs):
            auto.verify_bot_with_lock(store, **kwargs, bot_lookup=lambda *_: (777, -1002000000001, CHAT),
                                      transport_factory=lambda *_: transport)
        with mock.patch.object(auto, "BOOTSTRAP_BASE", self.base):
            with self.assertRaisesRegex(auto.AutomaticDeliveryError, "lock remains"):
                auto.bootstrap(self.store, default_branch="main", main_head=self.main, token=TOKEN, chat=CHAT,
                               api_id=12345, api_hash=API_HASH, run_id=123, policy_check=lambda: None,
                               collect=mock.Mock(return_value=base), probe=probe)
            after = self.store.read()
            self.assertFalse(after.document["activation"]["ready"])
            self.assertIsNotNone(after.document["session"])
            never_verify, never_probe = mock.Mock(), mock.Mock()
            with self.assertRaisesRegex(auto.AutomaticDeliveryError, "unconfirmed bot session"):
                auto.bootstrap(self.store, default_branch="main", main_head=self.main, token=TOKEN, chat=CHAT,
                               api_id=12345, api_hash=API_HASH, run_id=124, policy_check=lambda: None,
                               collect=mock.Mock(return_value=base), verify_state=never_verify, probe=never_probe)
            never_verify.assert_not_called()
            never_probe.assert_not_called()
            self.assertEqual(self.store.read().parent, after.parent)
        self.assertEqual(transport.calls, ["connect", "logout"])

    def test_a_pinned_but_unrelated_state_parent_is_not_initialized(self):
        tree = self.git("write-tree", cwd=self.work).decode().strip()
        orphan = self.git("commit-tree", tree, "-m", "unrelated fixture", cwd=self.work).decode().strip()
        self.git("push", "origin", f"{orphan}:refs/heads/unrelated-state", cwd=self.work)
        store = ledger.GitLedger(str(self.work), "unrelated-state")
        with self.assertRaisesRegex(cutover.CutoverError, "not an ancestor"):
            cutover.initialize(store, snapshot(), expected_parent=orphan, default_branch="main",
                               expected_main=self.main, bootstrap_base=orphan)
        self.assertEqual(cutover.remote_head(store, "unrelated-state"), orphan)

    def test_changed_default_legacy_ledger_cannot_be_discarded(self):
        legacy = self.work / ledger.LEGACY_PATH
        legacy.write_bytes(ledger.canonical({"schema": 2, "releases": []}))
        self.git("commit", "-am", "newer legacy fixture", cwd=self.work)
        self.git("push", "origin", "HEAD:refs/heads/main", cwd=self.work)
        changed_main = self.git("rev-parse", "HEAD", cwd=self.work).decode().strip()
        with self.assertRaisesRegex(cutover.CutoverError, "legacy ledger changed"):
            cutover.initialize(self.store, snapshot(), expected_parent=self.base, default_branch="main",
                               expected_main=changed_main, bootstrap_base=self.base)
        self.assertEqual(cutover.remote_head(self.store, state.STATE_BRANCH), self.base)

    def test_drifting_inventory_or_unexpected_parent_never_initializes(self):
        first, changed = snapshot(), snapshot(observed_tags={"new-during-scan"})
        with mock.patch.object(auto, "BOOTSTRAP_BASE", self.base):
            with self.assertRaisesRegex(auto.AutomaticDeliveryError, "changed"):
                auto.bootstrap(self.store, default_branch="main", main_head=self.main, token=TOKEN, chat=CHAT,
                               api_id=12345, api_hash=API_HASH, run_id=123, policy_check=lambda: None,
                               collect=mock.Mock(side_effect=[first, changed]))
        self.assertEqual(cutover.remote_head(self.store, state.STATE_BRANCH), self.base)
        # A state ref different from the pinned base is never overwritten.
        with mock.patch.object(auto, "BOOTSTRAP_BASE", "f" * 40):
            with self.assertRaises(ledger.LedgerError):
                auto.bootstrap(self.store, default_branch="main", main_head=self.main, token=TOKEN, chat=CHAT,
                               api_id=12345, api_hash=API_HASH, run_id=123, policy_check=lambda: None)
        self.assertEqual(cutover.remote_head(self.store, state.STATE_BRANCH), self.base)


class PollTests(unittest.TestCase):
    def setUp(self):
        self.store = fixtures.MemoryStore(ready())
        self.server = fixtures.FakeServer(self.store)
        self.calls = []

    def poll(self, listing, details, **kwargs):
        def api(endpoint, paginate=False):
            self.calls.append(endpoint)
            if endpoint.endswith("/releases?per_page=100") and paginate:
                return copy.deepcopy(listing)
            for item in details:
                if endpoint.endswith("/releases/" + str(item["id"])):
                    return copy.deepcopy(item)
            raise AssertionError("unexpected fake GitHub API call " + endpoint)
        def send(release, store, **options):
            return publish.publish(release, store, **options, transport_factory=lambda *_: fixtures.FakeTransport(self.server),
                                   bot_lookup=lambda *_: (777, -1002000000001, CHAT), sleeper=lambda _: None)
        return auto.poll(self.store, repository=auto.REPOSITORY, repository_id=auto.REPOSITORY_ID,
                         token=TOKEN, chat=CHAT, api_id=12345, api_hash=API_HASH, run_id=123, run_attempt=1,
                         tooling_sha="b" * 40, policy_check=lambda: None, api=api, send_release=send,
                         resolve=lambda _repo, _tag: "a" * 40, **kwargs)

    def test_github_token_release_is_detected_once_without_a_release_event(self):
        new = payload(987654, "v1.0.0", NOW - dt.timedelta(minutes=2))
        old_id = next(iter(ledger.KNOWN_HISTORICAL_RELEASES))
        listing = [new, payload(old_id, "some-new-tag", NOW - dt.timedelta(minutes=2)),
                   payload(9999, "deleted-history", NOW - dt.timedelta(minutes=2)),
                   payload(9998, "pre-fence", NOW - dt.timedelta(hours=2)),
                   payload(9997, "draft", NOW - dt.timedelta(minutes=2), draft=True),
                   payload(9996, "preview", NOW - dt.timedelta(minutes=2), prerelease=True)]
        self.assertEqual(self.poll(listing, [new]), 1)
        self.assertEqual(len(self.server.calls), 1)
        self.assertEqual(self.store.doc["releases"][0]["status"], "sent")
        self.assertEqual(self.poll(listing, [new]), 0)
        # The release-event path and polling share the same durable plan.
        release = fixtures.notes.Release.from_api(new)
        publish.publish(release, self.store, token=TOKEN, chat=CHAT, api_id=12345, api_hash=API_HASH,
                        source_sha="a" * 40, workflow_run_id=124, run_attempt=1, tooling_sha="b" * 40,
                        upload_assets=True, policy_check=lambda: None, allow_new_release=True,
                        require_protected_inventory=True, transport_factory=lambda *_: fixtures.FakeTransport(self.server),
                        bot_lookup=lambda *_: (777, -1002000000001, CHAT), sleeper=lambda _: None)
        self.assertEqual(len(self.server.calls), 1)
        self.assertFalse(any("/events" in call for call in self.calls))

    def test_lost_telegram_response_stops_polling_and_is_never_retried(self):
        new = payload(987654, "v1.0.0", NOW - dt.timedelta(minutes=2))
        other = payload(987655, "v1.0.1", NOW - dt.timedelta(minutes=1))
        self.server.lost_first = True
        with self.assertRaises(publish.PublishError):
            self.poll([new, other], [new, other])
        self.assertEqual(len(self.server.calls), 1)
        self.assertEqual(self.store.doc["releases"][0]["status"], "delivery_unknown")
        with self.assertRaises(publish.PublishError):
            self.poll([new, other], [new, other])
        self.assertEqual(len(self.server.calls), 1)

    def test_not_ready_future_fence_and_bad_identities_never_fetch_or_send(self):
        for doc in (pending(), ready()):
            if doc["activation"]["ready"]:
                doc["activation"]["published_after"] = utc(NOW + dt.timedelta(minutes=10))
            self.store = fixtures.MemoryStore(doc)
            if doc["activation"]["ready"]:
                self.assertEqual(self.poll([], []), 0)
            else:
                with self.assertRaises(auto.AutomaticDeliveryError):
                    self.poll([], [])
        self.assertEqual(self.calls, [])
        self.assertEqual(self.server.calls, [])

    def test_changed_release_or_duplicate_api_identity_fails_closed(self):
        new = payload(987654, "v1.0.0", NOW - dt.timedelta(minutes=2))
        changed = {**new, "tag_name": "v1.0.1"}
        with self.assertRaises(auto.AutomaticDeliveryError):
            self.poll([new], [changed])
        with self.assertRaises(auto.AutomaticDeliveryError):
            self.poll([new, new], [new])
        self.assertEqual(self.server.calls, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
