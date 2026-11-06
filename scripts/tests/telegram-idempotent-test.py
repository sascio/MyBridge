#!/usr/bin/env python3
"""Offline regressions for durable bot-only delivery; no Telegram/GitHub network."""
from __future__ import annotations

import contextlib
import copy
import dataclasses
import datetime as dt
import hashlib
import importlib.util
import io
import json
import multiprocessing
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS / "tests"))
from offline import sitecustomize as offline_guard  # noqa: E402,F401 - block external network in direct invocations
sys.path.insert(0, str(SCRIPTS))
import telegram_announce as announce
import telegram_assets as assets
import telegram_ledger as ledger
import telegram_state as state
import telegram_mtproto as mtproto
import telegram_publish as publish
import telegram_release_notes as notes

try:
    import telethon
    from telethon import errors
    from telethon.tl import functions, types
except ImportError:
    telethon = None

TOKEN = "123456789:abcdefghijklmnopqrstuvwx_fake"
CHAT = "@StreamBridge_App"
API_HASH = "0123456789abcdef0123456789abcdef"
EMPTY_INVENTORY = {"release_ids": [], "tags": []}
STATE_CONFIG = state.SameRepositoryConfig("sascio/MyBridge", 1349123452)


def setUpModule():
    previous = os.environ.get("GIT_ALLOW_PROTOCOL")
    os.environ["GIT_ALLOW_PROTOCOL"] = "file"
    def restore():
        if previous is None:
            os.environ.pop("GIT_ALLOW_PROTOCOL", None)
        else:
            os.environ["GIT_ALLOW_PROTOCOL"] = previous
    unittest.addModuleCleanup(restore)


def utc(when: dt.datetime) -> str:
    return when.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sample_release(*, assets_count: int = 0, body: str = "## What's new\n- A new player feature.",
                   release_id: int = 123, tag: str = "v9.9.9") -> notes.Release:
    now = dt.datetime.now(dt.timezone.utc)
    files = []
    for i in range(assets_count):
        content = (f"payload-{i}" * 10).encode()
        files.append(notes.Asset(name=f"StreamBridge-9.9.9-android-{i}.apk", size=len(content),
                                 url=f"https://github.com/example/release/file-{i}.apk",
                                 digest="sha256:" + hashlib.sha256(content).hexdigest(), id=100 + i))
    return notes.Release(id=release_id, tag_name=tag, name=f"StreamBridge {tag}", body=body,
                         html_url=f"https://github.com/example/releases/tag/{tag}",
                         created_at=utc(now - dt.timedelta(minutes=3)),
                         published_at=utc(now - dt.timedelta(minutes=2)), assets=tuple(files))


def make_release(release, operations, used):
    return publish.make_release(release, operations, used, "a" * 40)


def mark_attempt(item, status, message_id=None):
    item.update(state=status, attempts=1, attempted_at="2026-01-01T00:00:00Z",
                workflow_run_id=10001, run_attempt=1, tooling_sha="b" * 40)
    if status == "sent":
        item.update(message_id=message_id, delivered_at="2026-01-01T00:00:01Z")
    elif status == "delivery_unknown":
        item["reconciliation"] = {"status": "review_required", "reason": "unconfirmed_telegram_response"}


def sync_status(entry):
    entry["status"] = ledger.release_status(entry["items"])


def migrated() -> dict:
    cutoff = utc(dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1))
    return ledger.migrate({"schema": 1, "releases": []}, cutoff, EMPTY_INVENTORY)


class MemoryStore:
    """Models verified remote snapshots and exact-parent writes, not Telegram."""
    def __init__(self, doc=None):
        self.doc = copy.deepcopy(doc or migrated())
        self.version = 1
        self.history = []
        self.fault = None

    def read(self):
        ledger.validate(self.doc)
        return ledger.Snapshot(str(self.version), copy.deepcopy(self.doc), ledger.canonical(self.doc))

    def save(self, old, document):
        if old.parent != str(self.version):
            raise ledger.LedgerConflict("CAS conflict")
        ledger.validate(document)
        mode = self.fault(document) if self.fault else None
        if mode == "before":
            self.fault = None
            raise ledger.LedgerError("write refused")
        self.doc = copy.deepcopy(document)
        self.version += 1
        self.history.append(copy.deepcopy(document))
        if mode == "after":
            self.fault = None
            raise ledger.LedgerError("response lost after remote update")
        return self.read()


class FakeServer:
    def __init__(self, store):
        self.store = store
        self.calls = []
        self.accepted = {}
        self.next_id = 9000
        self.lost_first = False
        self.errors = []
        self.fail_upload_number = None
        self.uploads = 0
        self.close_ok = True
        self.sessions = 0
        self.max_sessions = 0

    def invoke(self, request):
        # The remote write-ahead entry must already say sending with this ID.
        items = [item for entry in self.store.doc["releases"] for item in entry["items"]]
        match = [item for item in items if item["random_id"] == request.random_id]
        assert len(match) == 1 and match[0]["state"] == "sending" and match[0]["attempts"] > 0
        self.calls.append(request.random_id)
        if self.errors:
            problem = self.errors.pop(0)
            if problem is not None:
                raise problem
        if request.random_id not in self.accepted:
            self.next_id += 1
            self.accepted[request.random_id] = self.next_id
            if self.lost_first:
                self.lost_first = False
                raise TimeoutError("accepted but response lost")
        return self.accepted[request.random_id]


class FakeTransport:
    def __init__(self, server):
        self.server = server
        self.connected = False
        self.calls = []

    def connect(self):
        self.server.sessions += 1
        self.server.max_sessions = max(self.server.max_sessions, self.server.sessions)
        self.connected = True

    def close(self):
        if self.connected:
            self.server.sessions -= 1
        return self.server.close_ok

    def text_request(self, markup, buttons, random_id):
        return SimpleNamespace(random_id=random_id, markup=markup, buttons=buttons, kind="page")

    def prepare_document(self, path, filename):
        self.server.uploads += 1
        if self.server.uploads == self.server.fail_upload_number:
            raise ConnectionError("temporary part upload failure")
        return Path(path).read_bytes()

    def media_request(self, media, caption, random_id):
        return SimpleNamespace(random_id=random_id, media=media, caption=caption, kind="asset")

    def send(self, request, **_kwargs):
        self.calls.append(request)
        return self.server.invoke(request)


def download_fake(asset, destination, **_kwargs):
    index = asset.id - 100
    payload = (f"payload-{index}" * 10).encode()
    Path(destination).write_bytes(payload)
    return destination, hashlib.sha256(payload).hexdigest()


@unittest.skipUnless(telethon is not None, "Telethon required for durable delivery regressions")
class DurableDeliveryTests(unittest.TestCase):
    def setUp(self):
        self.store = MemoryStore()
        self.server = FakeServer(self.store)
        self.transport_objects = []
        patch = mock.patch.object(assets, "download_asset", side_effect=download_fake)
        patch.start()
        self.addCleanup(patch.stop)

    def transport(self, *_args):
        obj = FakeTransport(self.server)
        self.transport_objects.append(obj)
        return obj

    def run_publish(self, release=None, **kwargs):
        return publish.publish(release or sample_release(), self.store, token=TOKEN, chat=CHAT,
                               api_id=12345, api_hash=API_HASH, source_sha="a" * 40, workflow_run_id=10001, run_attempt=1, tooling_sha="b" * 40, transport_factory=self.transport,
                               bot_lookup=lambda *_: (777, -1002000000001, CHAT),
                               sleeper=kwargs.pop("sleeper", lambda _: None),
                               policy_check=kwargs.pop("policy_check", lambda: None),
                               allow_new_release=kwargs.pop("allow_new_release", True), **kwargs)

    def item(self, index=0):
        return self.store.doc["releases"][0]["items"][index]

    def test_write_ahead_id_and_message_id_survive_duplicate_events(self):
        self.run_publish()
        item = self.item()
        self.assertEqual(item["state"], "sent")
        self.assertNotEqual(item["random_id"], 0)
        self.assertEqual(item["message_id"], self.server.accepted[item["random_id"]])
        self.assertIsNone(self.store.doc["session"])
        self.run_publish()
        self.assertEqual(len(self.server.calls), 1)
        self.assertEqual(len(self.transport_objects), 1)
        self.assertEqual(self.server.max_sessions, 1)

    def test_lost_response_becomes_unknown_and_never_retries(self):
        self.server.lost_first = True
        with self.assertRaisesRegex(publish.PublishError, "unresolved"):
            self.run_publish()
        self.assertEqual(len(self.server.calls), 1)
        self.assertEqual(len(self.server.accepted), 1)
        self.assertEqual(self.item()["attempts"], 1)
        self.assertEqual(self.item()["state"], "delivery_unknown")
        self.assertEqual(self.store.doc["releases"][0]["status"], "delivery_unknown")
        self.assertEqual(self.item()["reconciliation"]["status"], "review_required")
        with self.assertRaisesRegex(publish.PublishError, "no resend"):
            self.run_publish(resume_unsent=True, allow_new_release=False)
        self.assertEqual(len(self.server.calls), 1)

    def test_failed_write_ahead_commit_never_calls_telegram(self):
        self.store.fault = lambda d: "before" if any(
            i["state"] == "sending" for e in d["releases"] for i in e["items"]) else None
        with self.assertRaises(ledger.LedgerError):
            self.run_publish()
        self.assertEqual(self.server.calls, [])
        self.assertEqual(self.item()["state"], "pending")

    def test_unverified_branch_rules_stop_before_ledger_or_session(self):
        def blocked():
            raise ledger.LedgerError("rules unavailable")
        with self.assertRaises(ledger.LedgerError):
            self.run_publish(policy_check=blocked)
        self.assertEqual(self.store.history, [])
        self.assertEqual(self.transport_objects, [])
        self.assertEqual(self.server.calls, [])

    def test_removed_branch_rules_after_checkpoint_prevent_send(self):
        checks = 0
        def rules_change():
            nonlocal checks
            checks += 1
            if checks == 3:
                raise ledger.LedgerError("rules removed")
        with self.assertRaises(ledger.LedgerError):
            self.run_publish(policy_check=rules_change)
        self.assertEqual(self.server.calls, [])
        self.assertEqual(self.item()["state"], "sending")
        self.assertEqual(ledger.operation_outcome(self.item()), "unknown")
        with self.assertRaises(publish.PublishError):
            self.run_publish()

    def test_removed_branch_rules_block_part_upload(self):
        checks = 0
        def rules_change():
            nonlocal checks
            checks += 1
            if checks == 4:
                raise ledger.LedgerError("rules removed")
        with self.assertRaises(ledger.LedgerError):
            self.run_publish(sample_release(assets_count=1), policy_check=rules_change)
        self.assertEqual(len(self.server.calls), 1)
        self.assertEqual(self.server.uploads, 0)
        self.assertEqual(self.item(1)["state"], "uploading")
        self.assertEqual(ledger.operation_outcome(self.item(1)), "confirmed_unsent_by_protocol")

    def test_failed_confirmation_commit_blocks_every_rerun_and_logs_response_evidence(self):
        self.store.fault = lambda d: "before" if any(
            i["state"] == "sent" for e in d["releases"] for i in e["items"]) else None
        output = io.StringIO()
        with contextlib.redirect_stdout(output), self.assertRaises(ledger.LedgerError):
            self.run_publish()
        first_id = self.server.calls[0]
        self.assertEqual(self.item()["state"], "sending")
        self.assertIn("TELEGRAM_CONFIRMED_RESPONSE", output.getvalue())
        self.assertIn(f"operation_id={first_id}", output.getvalue())
        self.assertIn(f"message_id={self.server.accepted[first_id]}", output.getvalue())
        for manual in (False, True):
            with self.assertRaisesRegex(publish.PublishError, "no resend"):
                self.run_publish(resume_unsent=manual, allow_new_release=not manual)
        self.assertEqual(self.server.calls, [first_id])

    def test_confirmation_commit_accepted_but_response_lost_does_not_resend(self):
        self.store.fault = lambda d: "after" if any(
            i["state"] == "sent" for e in d["releases"] for i in e["items"]) else None
        with self.assertRaises(ledger.LedgerError):
            self.run_publish()
        self.assertEqual(self.item()["state"], "sent")
        self.run_publish()
        self.assertEqual(len(self.server.calls), 1)

    def test_partial_asset_uploads_preserve_success_and_resume_only_remaining(self):
        release = sample_release(assets_count=2)
        self.server.fail_upload_number = 2
        with self.assertRaisesRegex(publish.PublishError, "document failed before public send"):
            self.run_publish(release)
        items = self.store.doc["releases"][0]["items"]
        self.assertEqual([i["state"] for i in items], ["sent", "sent", "failed"])
        first_two = list(self.server.calls)
        self.server.fail_upload_number = None
        with self.assertRaisesRegex(publish.PublishError, "manual recovery"):
            self.run_publish(release)
        self.run_publish(release, resume_unsent=True, allow_new_release=False)
        self.assertEqual(self.server.calls[:2], first_two)
        self.assertEqual(len(self.server.calls), 3)
        self.assertEqual(len(set(self.server.calls)), 3)
        self.assertTrue(all(i["state"] == "sent" for i in self.store.doc["releases"][0]["items"]))
        self.assertTrue(all(i["sha256"] for i in self.store.doc["releases"][0]["items"] if i["kind"] == "asset"))

    def test_apk_and_ipa_continue_independently_after_a_pre_send_upload_failure(self):
        base = sample_release(assets_count=2)
        ipa = notes.Asset(**{**base.assets[1].__dict__, "name": "StreamBridge-ios.ipa",
                             "url": "https://github.com/example/release/file-1.ipa"})
        release = notes.Release(**{**base.__dict__, "assets": (base.assets[0], ipa)})
        plans, _ = assets.plan_uploads(release)
        self.assertEqual([plan.kind for plan in plans], ["android", "ios"])
        self.server.fail_upload_number = 1
        with self.assertRaisesRegex(publish.PublishError, "document failed before public send"):
            self.run_publish(release)
        items = self.store.doc["releases"][0]["items"]
        self.assertEqual([item["state"] for item in items], ["sent", "failed", "sent"])
        original_ids = [item["random_id"] for item in items]
        self.assertEqual(self.server.calls, [original_ids[0], original_ids[2]])  # IPA succeeded independently
        self.server.fail_upload_number = None
        self.run_publish(release, allow_new_release=False, resume_unsent=True)
        self.assertEqual(self.server.calls, [original_ids[0], original_ids[2], original_ids[1]])
        self.assertEqual(len(set(original_ids)), 3)
        self.assertTrue(all(item["state"] == "sent" for item in self.store.doc["releases"][0]["items"]))

    def test_download_failure_isolated_to_one_document_and_success_is_not_repeated(self):
        base = sample_release(assets_count=2)
        ipa = notes.Asset(**{**base.assets[1].__dict__, "name": "StreamBridge-ios.ipa"})
        release = notes.Release(**{**base.__dict__, "assets": (base.assets[0], ipa)})
        def first_unavailable(asset, destination, **kwargs):
            if asset.id == 100:
                raise assets.AssetError("unavailable")
            return download_fake(asset, destination, **kwargs)
        with mock.patch.object(assets, "download_asset", side_effect=first_unavailable):
            with self.assertRaisesRegex(publish.PublishError, "document failed before public send"):
                self.run_publish(release)
        items = self.store.doc["releases"][0]["items"]
        ids = [item["random_id"] for item in items]
        self.assertEqual([item["state"] for item in items], ["sent", "failed", "sent"])
        self.assertEqual(self.server.calls, [ids[0], ids[2]])
        self.run_publish(release, allow_new_release=False, resume_unsent=True)
        self.assertEqual(self.server.calls, [ids[0], ids[2], ids[1]])

    def test_unknown_asset_send_still_stops_later_documents(self):
        release = sample_release(assets_count=2)
        self.server.errors = [None, RuntimeError("unrecognized send error")]
        with self.assertRaisesRegex(publish.PublishError, "unresolved"):
            self.run_publish(release)
        items = self.store.doc["releases"][0]["items"]
        self.assertEqual([item["state"] for item in items], ["sent", "delivery_unknown", "pending"])
        self.assertEqual(len(self.server.calls), 2)
        with self.assertRaisesRegex(publish.PublishError, "no resend"):
            self.run_publish(release)
        self.assertEqual(len(self.server.calls), 2)

    def test_distinct_pages_and_assets_have_distinct_nonzero_ids_before_any_send(self):
        release = sample_release(assets_count=2, body="## New\n" + "\n".join("- " + ("abc " * 70) for _ in range(40)))
        self.run_publish(release)
        first_plan = next(x for x in self.store.history if x["releases"])
        items = first_plan["releases"][0]["items"]
        ids = [x["random_id"] for x in items]
        self.assertGreater(len([x for x in items if x["kind"] == "page"]), 1)
        self.assertEqual(len(ids), len(set(ids)))
        self.assertNotIn(0, ids)
        self.assertTrue(all(x["state"] == "pending" for x in items))

    def test_changed_release_or_assets_fail_closed_after_partial_delivery(self):
        release = sample_release(assets_count=1)
        self.run_publish(release, upload_assets=False)
        changed = notes.Release(**{**release.__dict__, "body": release.body + "\n- Another feature"})
        with self.assertRaisesRegex(publish.PublishError, "changed"):
            self.run_publish(changed)
        self.assertEqual(len(self.server.calls), 1)

    def test_changed_wire_entity_parser_blocks_rerun(self):
        release = sample_release(assets_count=1)
        self.run_publish(release, upload_assets=False)
        with mock.patch.object(mtproto.BotTransport, "parse_html", return_value=("different wire text", [])):
            with self.assertRaisesRegex(publish.PublishError, "changed"):
                self.run_publish(release)
        self.assertEqual(len(self.server.calls), 1)

    def test_manual_text_only_then_assets_resume_without_reposting(self):
        release = sample_release(assets_count=1)
        self.run_publish(release, upload_assets=False)
        self.assertEqual([x["state"] for x in self.store.doc["releases"][0]["items"]], ["sent", "pending"])
        self.run_publish(release, allow_new_release=False, resume_unsent=True)
        self.assertEqual(len(self.server.calls), 2)
        self.assertEqual(len(set(self.server.calls)), 2)

    def test_manual_recovery_cannot_create_or_send_a_new_release(self):
        with self.assertRaisesRegex(publish.PublishError, "already planned"):
            self.run_publish(sample_release(), allow_new_release=False, resume_unsent=True)
        self.assertEqual(self.store.history, [])
        self.assertEqual(self.transport_objects, [])
        self.assertEqual(self.server.calls, [])

    def test_changed_bot_or_channel_is_blocked_before_another_send(self):
        release = sample_release(assets_count=1)
        self.run_publish(release, upload_assets=False)
        with self.assertRaisesRegex(publish.PublishError, "identity"):
            publish.publish(release, self.store, token=TOKEN + "changed", chat=CHAT, api_id=12345, api_hash=API_HASH, source_sha="a" * 40, workflow_run_id=10001, run_attempt=1, tooling_sha="b" * 40,
                            transport_factory=self.transport, bot_lookup=lambda *_: (777, -1002000000001, CHAT),
                            policy_check=lambda: None, allow_new_release=True)
        self.assertEqual(len(self.server.calls), 1)

    def test_crash_stale_session_lock_blocks_even_with_recovery_flag(self):
        self.store.doc["session"] = {"owner": "run-crashed"}
        with self.assertRaisesRegex(publish.PublishError, "session"):
            self.run_publish(resume_unsent=True)
        self.assertEqual(self.server.calls, [])
        self.assertEqual(len(self.transport_objects), 0)

    def test_changed_lock_before_send_fails_closed(self):
        original_read = self.store.read
        def replaced_lock():
            snapshot = original_read()
            if snapshot.document["session"] is not None:
                snapshot.document["session"] = {"owner": "different-worker"}
            return snapshot
        with mock.patch.object(self.store, "read", side_effect=replaced_lock):
            with self.assertRaisesRegex(publish.PublishError, "lock"):
                self.run_publish()
        self.assertEqual(self.server.calls, [])
        self.assertIsNotNone(self.store.doc["session"])

    def test_replaced_lock_during_page_pacing_blocks_following_pages(self):
        release = sample_release(body="## New\n" + "\n".join("- " + ("abc " * 70) for _ in range(40)))
        def replace_lock(_seconds):
            self.store.doc["session"] = {"owner": "different-worker"}
        with self.assertRaisesRegex(publish.PublishError, "lock"):
            self.run_publish(release, sleeper=replace_lock)
        self.assertEqual(len(self.server.calls), 1)
        self.assertEqual(self.store.doc["session"], {"owner": "different-worker"})

    def test_unconfirmed_logout_leaves_lock_and_blocks_next_run(self):
        self.server.close_ok = False
        with self.assertRaisesRegex(publish.PublishError, "revocation"):
            self.run_publish()
        self.assertIsNotNone(self.store.doc["session"])
        with self.assertRaisesRegex(publish.PublishError, "session"):
            self.run_publish()
        self.assertEqual(len(self.server.calls), 1)

    def test_flood_wait_is_not_automatically_retried(self):
        self.server.errors = [errors.FloodWaitError(request=None, capture=4), None]
        sleeps = []
        with self.assertRaises(publish.PublishError):
            self.run_publish(sleeper=sleeps.append)
        self.assertEqual(sleeps, [])
        self.assertEqual(len(self.server.calls), 1)
        self.assertEqual(self.item()["state"], "delivery_unknown")

    def test_long_flood_wait_stops_unconfirmed_without_sleep(self):
        self.server.errors = [errors.FloodWaitError(request=None, capture=600)]
        sleeps = []
        with self.assertRaises(publish.PublishError):
            self.run_publish(sleeper=sleeps.append)
        self.assertEqual(sleeps, [])
        self.assertEqual(self.item()["state"], "delivery_unknown")
        with self.assertRaises(publish.PublishError):
            self.run_publish()
        self.assertEqual(len(self.server.calls), 1)

    def test_random_id_duplicate_does_not_count_as_confirmation(self):
        self.server.errors = [errors.RandomIdDuplicateError(request=None)] * 3
        with self.assertRaises(publish.PublishError):
            self.run_publish()
        self.assertEqual(self.item()["state"], "delivery_unknown")
        self.assertIsNone(self.item()["message_id"])
        self.assertEqual(len(set(self.server.calls)), 1)

    def test_legacy_and_before_cutover_release_block_before_session(self):
        self.store.doc = {"schema": 1, "releases": []}
        with self.assertRaises(ledger.LedgerError):
            self.run_publish()
        self.store.doc = migrated()
        self.store.doc["activation"]["published_after"] = utc(dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=30))
        with self.assertRaisesRegex(publish.PublishError, "cutoff"):
            self.run_publish()
        self.assertEqual(self.transport_objects, [])

    def test_new_release_can_reference_an_older_commit_without_bypassing_history(self):
        release = sample_release()
        release = dataclasses.replace(release, created_at="2020-01-01T00:00:00Z")
        self.run_publish(release=release)
        self.assertEqual(self.item()["state"], "sent")
        self.assertEqual(len(self.server.calls), 1)

    def test_same_legacy_tag_blocks_new_release(self):
        self.store.doc = ledger.migrate({"schema": 2, "releases": [{"tag_name": "v9.9.9", "release_id": 1}]},
                                        utc(dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1)), EMPTY_INVENTORY)
        with self.assertRaisesRegex(publish.PublishError, "ledger"):
            self.run_publish()
        self.assertEqual(self.server.calls, [])

    def test_inventoried_old_release_id_or_tag_blocks_before_session(self):
        for inventory in ({"release_ids": [123], "tags": []},
                          {"release_ids": [], "tags": ["v9.9.9"]}):
            with self.subTest(inventory=inventory):
                cutoff = utc(dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1))
                self.store = MemoryStore(ledger.migrate({"schema": 1, "releases": []}, cutoff, inventory))
                self.server = FakeServer(self.store)
                with self.assertRaisesRegex(publish.PublishError, "historical inventory"):
                    self.run_publish(sample_release())
                self.assertEqual(self.server.calls, [])
                self.assertEqual(self.store.history, [])

    def test_protected_historical_identity_and_changed_token_block_before_session(self):
        known_ids = set(ledger.KNOWN_HISTORICAL_RELEASES)
        known_tags = set(ledger.KNOWN_HISTORICAL_RELEASES.values())
        exclusions = []
        for rid, tag in ledger.KNOWN_HISTORICAL_RELEASES.items():
            known_message = ledger.KNOWN_MESSAGE_ID if rid == ledger.KNOWN_RELEASE_ID else None
            exclusions.append({"release_id": rid, "tag": tag, "source_sha": None,
                               "published_at": utc(dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=2)),
                               "status": "historical_excluded", "reason": ledger.HISTORICAL_REASON,
                               "message_id": known_message,
                               "evidence": ledger.KNOWN_EVIDENCE if known_message else None, "delivered_at": None})
        raw = {"release_ids": [123, 999, *known_ids], "tags": ["v9.9.9", "private-draft-tag", *known_tags],
               "message_ids": [ledger.KNOWN_MESSAGE_ID], "exclusions": exclusions}
        protected = ledger.protect_inventory(raw, known_ids, known_tags, TOKEN)
        cutoff = utc(dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1))
        for release in (sample_release(release_id=123, tag="new-tag"),
                        sample_release(release_id=456, tag="private-draft-tag")):
            with self.subTest(release_id=release.id):
                doc = ledger.migrate({"schema": 1, "releases": []}, cutoff, protected)
                doc["activation"]["ready"] = True
                self.store = MemoryStore(doc)
                self.server = FakeServer(self.store)
                with self.assertRaisesRegex(publish.PublishError, "historical inventory"):
                    self.run_publish(release, require_protected_inventory=True)
                self.assertEqual(self.store.history, [])
                self.assertEqual(self.server.calls, [])
        changed_key = ledger.protect_inventory(raw, known_ids, known_tags, TOKEN + "-changed")
        self.store = MemoryStore(ledger.migrate({"schema": 1, "releases": []}, cutoff, changed_key))
        self.server = FakeServer(self.store)
        with self.assertRaisesRegex(ledger.LedgerError, "key changed"):
            self.run_publish(sample_release(release_id=456, tag="new-tag"), require_protected_inventory=True)
        self.assertEqual(self.store.history, [])
        self.assertEqual(self.transport_objects, [])
        self.store = MemoryStore()
        with self.assertRaisesRegex(ledger.LedgerError, "requires a protected"):
            self.run_publish(require_protected_inventory=True)

    def test_observed_v0115_announcement_is_never_initialized_as_new(self):
        inventory = {"release_ids": [434756896], "tags": ["v0.1.15"], "message_ids": [5]}
        cutoff = utc(dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1))
        self.store = MemoryStore(ledger.migrate({"schema": 1, "releases": []}, cutoff, inventory))
        self.server = FakeServer(self.store)
        historical = sample_release(release_id=434756896, tag="v0.1.15")
        with self.assertRaisesRegex(publish.PublishError, "historical inventory"):
            self.run_publish(historical)
        self.assertEqual(self.store.history, [])
        self.assertEqual(self.server.calls, [])
        self.assertEqual(self.transport_objects, [])

    def test_draft_and_prerelease_never_acquire_lock_or_send(self):
        release = sample_release()
        for field in ("draft", "prerelease"):
            self.run_publish(notes.Release(**{**release.__dict__, field: True}))
        self.assertEqual(self.store.history, [])
        self.assertEqual(self.server.calls, [])

    def test_oversized_checksum_manifest_blocks_before_download(self):
        base = sample_release(assets_count=1)
        manifest = notes.Asset("checksums.sha256", assets.MAX_CHECKSUM_BYTES + 1,
                               "https://github.com/example/checksums.sha256", id=900)
        release = notes.Release(**{**base.__dict__, "assets": base.assets + (manifest,)})
        with mock.patch.object(assets, "_fetch_text") as fetch:
            with self.assertRaisesRegex(publish.PublishError, "size"):
                self.run_publish(release)
        fetch.assert_not_called()
        self.assertEqual(self.server.calls, [])

    def test_invalid_or_missing_published_checksum_manifest_blocks(self):
        base = sample_release(assets_count=1)
        manifest = notes.Asset("checksums.sha256", 100, "https://github.com/example/checksums.sha256", id=900)
        release = notes.Release(**{**base.__dict__, "assets": base.assets + (manifest,)})
        with mock.patch.object(assets, "_fetch_text", side_effect=OSError("private URL")):
            with self.assertRaisesRegex(publish.PublishError, "checksum"):
                self.run_publish(release)
        self.assertEqual(self.server.calls, [])
        self.assertEqual(self.store.history, [])

    def test_replayed_asset_with_changed_bytes_is_not_sent(self):
        release = sample_release(assets_count=1)
        self.server.fail_upload_number = 1
        with self.assertRaises(publish.PublishError):
            self.run_publish(release)
        with mock.patch.object(assets, "download_asset", return_value=("ignored", "0" * 64)):
            with self.assertRaisesRegex(publish.PublishError, "SHA-256"):
                self.run_publish(release, resume_unsent=True, allow_new_release=False)
        self.assertEqual(len(self.server.calls), 1)  # announcement only

    def test_no_secret_or_exception_content_is_printed_on_unexpected_failure(self):
        secret = TOKEN + " " + API_HASH + " 12345 " + CHAT
        self.server.errors = [RuntimeError(secret)]
        output = io.StringIO()
        with contextlib.redirect_stdout(output), mock.patch.dict(os.environ, {
            "TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_API_HASH": API_HASH,
            "TELEGRAM_API_ID": "12345", "TELEGRAM_CHAT_ID": CHAT,
        }):
            with self.assertRaises(publish.PublishError):
                self.run_publish()
        for part in (TOKEN, API_HASH, "12345", CHAT):
            self.assertNotIn(part, output.getvalue())

    def test_lost_media_response_is_unknown_and_never_resent(self):
        release = sample_release(assets_count=1)
        self.run_publish(release, upload_assets=False)
        self.server.lost_first = True
        with self.assertRaises(publish.PublishError):
            self.run_publish(release)
        self.assertEqual(len(self.server.calls), 2)  # one text and one media request
        self.assertEqual(len(self.server.accepted), 2)
        self.assertEqual(self.store.doc['releases'][0]['items'][1]['attempts'], 1)
        self.assertEqual(self.store.doc['releases'][0]['items'][1]['state'], 'delivery_unknown')
        with self.assertRaises(publish.PublishError):
            self.run_publish(release, resume_unsent=True, allow_new_release=False)
        self.assertEqual(len(self.server.calls), 2)

    def test_permanent_send_error_is_not_retried(self):
        self.server.errors = [errors.ChatAdminRequiredError(request=None)]
        with self.assertRaises(publish.PublishError):
            self.run_publish()
        self.assertEqual(len(self.server.calls), 1)
        self.assertEqual(self.item()['state'], 'delivery_unknown')

    def test_changed_asset_id_or_digest_fails_frozen_plan(self):
        release = sample_release(assets_count=1)
        self.run_publish(release, upload_assets=False)
        original = release.assets[0]
        for change in ({'id': original.id + 1}, {'digest': 'sha256:' + 'f' * 64}, {'size': original.size + 1}):
            replacement = notes.Asset(**{**original.__dict__, **change})
            changed = notes.Release(**{**release.__dict__, 'assets': (replacement,)})
            with self.subTest(change=change), self.assertRaisesRegex(publish.PublishError, 'changed'):
                self.run_publish(changed)
        self.assertEqual(len(self.server.calls), 1)

    def test_same_tag_with_different_release_id_is_blocked(self):
        release = sample_release()
        self.run_publish(release)
        replacement = notes.Release(**{**release.__dict__, 'id': release.id + 1})
        with self.assertRaisesRegex(publish.PublishError, 'ledger'):
            self.run_publish(replacement)
        self.assertEqual(len(self.server.calls), 1)

    def test_malformed_asset_identity_blocks_before_writes(self):
        release = sample_release(assets_count=1)
        invalid = notes.Asset(**{**release.assets[0].__dict__, 'id': None})
        release = notes.Release(**{**release.__dict__, 'assets': (invalid,)})
        with self.assertRaisesRegex(publish.PublishError, 'asset id'):
            self.run_publish(release)
        self.assertEqual(self.store.history, [])
        self.assertEqual(self.server.calls, [])

    def test_unresolved_page_blocks_later_asset(self):
        release = sample_release(assets_count=1)
        self.server.errors = [errors.ChatAdminRequiredError(request=None)]
        with self.assertRaises(publish.PublishError):
            self.run_publish(release)
        self.assertEqual(self.server.uploads, 0)
        self.assertEqual([i['state'] for i in self.store.doc['releases'][0]['items']], ['delivery_unknown', 'pending'])

    def test_commit_failure_during_upload_never_sends_media(self):
        release = sample_release(assets_count=1)
        self.store.fault = lambda d: 'before' if any(i['state'] == 'uploading' for e in d['releases'] for i in e['items']) else None
        with self.assertRaises(ledger.LedgerError):
            self.run_publish(release)
        self.assertEqual(self.server.uploads, 0)
        self.assertEqual(len(self.server.calls), 1)
        self.assertEqual(self.store.doc['releases'][0]['items'][1]['state'], 'pending')

    def test_cli_cannot_publish_outside_the_serialized_actions_context(self):
        with mock.patch.dict(os.environ, {'GITHUB_ACTIONS': '', 'GITHUB_RUN_ID': ''}), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            code = publish.main(['--release-json', 'does-not-exist.json', '--branch', 'main', '--state-dir', '/tmp/state-checkout'])
        self.assertEqual(code, 1)
        self.assertIn('restricted', output.getvalue())
        self.assertEqual(self.server.calls, [])


class ChecksumAndCutoverTests(unittest.TestCase):
    def test_ios_only_manifest_is_applied_before_upload(self):
        body = b'iOS-unsigned-fixture'
        digest = hashlib.sha256(body).hexdigest()
        ipa = notes.Asset('StreamBridge-ios.ipa', len(body), 'https://example.invalid/app.ipa', id=501)
        manifest = notes.Asset('checksums.sha256', 100, 'https://example.invalid/checksums.sha256', id=502)
        base = sample_release()
        release = notes.Release(**{**base.__dict__, 'assets': (ipa, manifest)})
        plans, _ = assets.plan_uploads(release)
        with mock.patch.object(assets, '_fetch_text', return_value=f'{digest}  {ipa.name}\n'):
            checksums = publish.checksum_manifest(release, plans)
        with tempfile.TemporaryDirectory() as tmp:
            path, actual = assets.download_asset(ipa, os.path.join(tmp, ipa.name), checksums=checksums,
                                                 opener=lambda *_a, **_k: io.BytesIO(body), attempts=1)
            self.assertEqual(Path(path).read_bytes(), body)
            self.assertEqual(actual, digest)

    def test_corrupt_ios_bytes_are_rejected_by_published_manifest(self):
        body = b'iOS-unsigned-fixture'
        ipa = notes.Asset('StreamBridge-ios.ipa', len(body), 'https://example.invalid/app.ipa', id=501)
        manifest = notes.Asset('checksums.sha256', 100, 'https://example.invalid/checksums.sha256', id=502)
        base = sample_release()
        release = notes.Release(**{**base.__dict__, 'assets': (ipa, manifest)})
        plans, _ = assets.plan_uploads(release)
        with mock.patch.object(assets, '_fetch_text', return_value=f'{"f" * 64}  {ipa.name}\n'):
            checksums = publish.checksum_manifest(release, plans)
        with tempfile.TemporaryDirectory() as tmp, self.assertRaisesRegex(assets.AssetError, 'digest mismatch'):
            assets.download_asset(ipa, os.path.join(tmp, ipa.name), checksums=checksums,
                                  opener=lambda *_a, **_k: io.BytesIO(body), attempts=1)

    def test_conflicting_manifest_entries_fail_closed(self):
        base = sample_release(assets_count=1)
        manifest = notes.Asset('checksums.sha256', 100, 'https://example.invalid/checksums.sha256', id=502)
        release = notes.Release(**{**base.__dict__, 'assets': base.assets + (manifest,)})
        plans, _ = assets.plan_uploads(release)
        name = base.assets[0].name
        with mock.patch.object(assets, '_fetch_text', return_value=f'{"a" * 64}  {name}\n{"b" * 64}  {name}\n'):
            with self.assertRaisesRegex(publish.PublishError, 'checksum'):
                publish.checksum_manifest(release, plans)

    def test_manifest_disagreement_or_missing_asset_is_fail_closed(self):
        base = sample_release(assets_count=1)
        manifest = notes.Asset('checksums.sha256', 100, 'https://example.invalid/checksums.sha256', id=502)
        release = notes.Release(**{**base.__dict__, 'assets': base.assets + (manifest,)})
        plans, _ = assets.plan_uploads(release)
        with mock.patch.object(assets, '_fetch_text', return_value=f'{"f" * 64}  {base.assets[0].name}\n'):
            with self.assertRaisesRegex(publish.PublishError, 'disagree'):
                publish.checksum_manifest(release, plans)
        unsigned = notes.Asset(**{**base.assets[0].__dict__, 'digest': ''})
        release = notes.Release(**{**base.__dict__, 'assets': (unsigned, manifest)})
        plans, _ = assets.plan_uploads(release)
        with mock.patch.object(assets, '_fetch_text', return_value=''):
            with self.assertRaisesRegex(publish.PublishError, 'no checksum'):
                publish.checksum_manifest(release, plans)

    def test_invalid_legacy_entries_and_boolean_schema_cannot_be_migrated(self):
        for legacy in ({'schema': True, 'releases': []},
                       {'schema': 1, 'releases': [{'release_id': 1}]},
                       {'schema': 1, 'releases': [{'release_id': True, 'tag_name': 'v1'}]},
                       {'schema': 2, 'releases': [{'release_id': 1, 'tag_name': 'v1', 'assets': 'bad'}]}):
            with self.subTest(legacy=legacy), self.assertRaises(ledger.LedgerError):
                ledger.migrate(legacy, '2026-01-01T00:00:00Z', EMPTY_INVENTORY)

    def test_unsafe_attempt_reset_is_rejected(self):
        doc = migrated()
        entry = make_release(sample_release(), [{'key': 'page:1', 'kind': 'page', 'payload': 'a' * 64}], set())
        doc['releases'] = [entry]
        entry['items'][0]['attempts'] = 1
        with self.assertRaisesRegex(ledger.LedgerError, 'reset'):
            ledger.validate(doc)



class BranchPolicyAndContextTests(unittest.TestCase):
    def setUp(self):
        # This group exercises the older explicit timestamp gate. Ledger-mode
        # activation and automatic polling have their own dedicated tests.
        patcher = mock.patch.object(ledger, "publishing_ledger_mode", return_value=False)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_effective_rules_require_both_force_push_and_deletion_restrictions(self):
        def runner(stdout, returncode=0):
            return lambda *_a, **_k: SimpleNamespace(stdout=stdout, stderr=b"private error", returncode=returncode)
        ledger.require_branch_rules("sascio/MyBridge", "main", runner=runner(b"non_fast_forward\ndeletion\n"))
        for output, code in ((b"", 0), (b"non_fast_forward\n", 0), (b"deletion\n", 0),
                             (b"non_fast_forward\ndeletion\n", 1)):
            with self.subTest(output=output, code=code), self.assertRaises(ledger.LedgerError):
                ledger.require_branch_rules("sascio/MyBridge", "main", runner=runner(output, code))
        with self.assertRaisesRegex(ledger.LedgerError, "PR requirement conflicts"):
            ledger.require_branch_rules("sascio/MyBridge", "main",
                                        runner=runner(b"non_fast_forward\ndeletion\npull_request\n"))
        def timeout(*_args, **_kwargs):
            raise subprocess.TimeoutExpired("secret-command", 60)
        with self.assertRaises(ledger.LedgerError) as caught:
            ledger.require_branch_rules("sascio/MyBridge", "main", runner=timeout)
        self.assertNotIn("secret-command", str(caught.exception))

    def test_rules_request_uses_metadata_api_and_escapes_branch(self):
        seen = []
        def runner(command, **kwargs):
            seen.append((command, kwargs))
            return SimpleNamespace(stdout=b"deletion\nnon_fast_forward\n", returncode=0)
        ledger.require_branch_rules("sascio/MyBridge", "release/branch", runner=runner)
        self.assertEqual(seen[0][0][-1], "repos/sascio/MyBridge/rules/branches/release%2Fbranch?per_page=100")
        self.assertIn("--paginate", seen[0][0])
        self.assertEqual(seen[0][1]["stderr"], subprocess.PIPE)
        for repo, branch in (("../other/repo", "main"), ("owner/repo", "--bad")):
            with self.assertRaises(ledger.LedgerError):
                ledger.require_branch_rules(repo, branch, runner=runner)

    def test_cli_wires_real_policy_guard_to_publisher_without_logging_secrets(self):
        values = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
                  "TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": API_HASH}
        capture = io.StringIO()
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "release", "GITHUB_SHA": "a" * 40, "GITHUB_RUN_ID": "10001", "GITHUB_RUN_ATTEMPT": "1"}), \
             mock.patch.object(publish.identity, "resolve_tag_commit", return_value="a" * 40), \
             mock.patch.object(publish, "require_workflow_context", return_value=("sascio/MyBridge", 123, "v9.9.9")), \
             mock.patch.object(ledger, "publishing_not_before", return_value=dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1)), \
             mock.patch.object(state, "load_config", return_value=STATE_CONFIG), \
             mock.patch.object(publish, "require_secret", side_effect=values.__getitem__), \
             mock.patch.object(publish.announce, "load_release", return_value=sample_release()), \
             mock.patch.object(ledger, "GitLedger", return_value=MemoryStore()), \
             mock.patch.object(state, "verify_state") as branch_rules, \
             mock.patch.object(state, "require_disabled_legacy") as legacy_check, \
             mock.patch.object(publish, "publish") as publisher, contextlib.redirect_stdout(capture):
            self.assertEqual(publish.main(["--release-json", "fixture.json", "--branch", "main", "--state-dir", "/tmp/state-checkout"]), 0)
            publisher.call_args.kwargs["policy_check"]()
            branch_rules.assert_called_once_with(STATE_CONFIG, "/tmp/state-checkout", "sascio/MyBridge")
            legacy_check.assert_called_once_with("sascio/MyBridge")
        for secret in values.values():
            self.assertNotIn(secret, capture.getvalue())

    def test_disabled_tracked_activation_blocks_before_secrets_or_ledger(self):
        with mock.patch.object(publish, "require_workflow_context", return_value=("sascio/MyBridge", 123, "v9.9.9")), \
             mock.patch.object(publish, "require_secret") as secrets_read, \
             mock.patch.object(ledger, "GitLedger") as store, contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(publish.main(["--release-json", "fixture.json", "--branch", "main", "--state-dir", "/tmp/state-checkout"]), 1)
            self.assertIn("configuration is disabled", output.getvalue())
            secrets_read.assert_not_called()
            store.assert_not_called()

    def test_disabled_state_configuration_blocks_before_telegram_secrets(self):
        fence = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=10)
        with mock.patch.object(publish, "require_workflow_context", return_value=("sascio/MyBridge", 123, "v9.9.9")), \
             mock.patch.object(ledger, "publishing_not_before", return_value=fence), \
             mock.patch.object(state, "load_config", side_effect=ledger.LedgerError("state storage is disabled")), \
             mock.patch.object(publish, "require_secret") as secrets_read, \
             mock.patch.object(ledger, "GitLedger") as store, contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(publish.main(["--release-json", "fixture.json", "--branch", "main", "--state-dir", "/tmp/state-checkout"]), 1)
            self.assertIn("state storage is disabled", output.getvalue())
            secrets_read.assert_not_called()
            store.assert_not_called()

    def test_future_activation_fence_blocks_before_secrets_or_ledger(self):
        fence = dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=10)
        with mock.patch.object(publish, "require_workflow_context", return_value=("sascio/MyBridge", 123, "v9.9.9")), \
             mock.patch.object(ledger, "publishing_not_before", return_value=fence), \
             mock.patch.object(publish, "require_secret") as secrets_read, \
             mock.patch.object(ledger, "GitLedger") as store, contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(publish.main(["--release-json", "fixture.json", "--branch", "main", "--state-dir", "/tmp/state-checkout"]), 1)
            self.assertIn("not yet effective", output.getvalue())
            secrets_read.assert_not_called()
            store.assert_not_called()

    def test_queued_release_from_before_activation_fence_cannot_publish(self):
        values = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
                  "TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": API_HASH}
        release = sample_release()  # created and published before this fence
        fence = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=1)
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "release"}), \
             mock.patch.object(publish, "require_workflow_context", return_value=("sascio/MyBridge", release.id, release.tag_name)), \
             mock.patch.object(ledger, "publishing_not_before", return_value=fence), \
             mock.patch.object(state, "load_config", return_value=STATE_CONFIG), \
             mock.patch.object(publish, "require_secret", side_effect=values.__getitem__), \
             mock.patch.object(publish.announce, "load_release", return_value=release), \
             mock.patch.object(ledger, "GitLedger") as store, \
             mock.patch.object(publish, "publish") as publisher, contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(publish.main(["--release-json", "fixture.json", "--branch", "main", "--state-dir", "/tmp/state-checkout"]), 1)
            self.assertIn("before the reviewed activation fence", output.getvalue())
            store.assert_not_called()
            publisher.assert_not_called()

    def test_manual_cli_requires_recovery_flag_and_disallows_new_release_plan(self):
        values = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
                  "TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": API_HASH}
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_RUN_ID": "10001", "GITHUB_RUN_ATTEMPT": "1"}), \
             mock.patch.object(publish.identity, "resolve_tag_commit", return_value="a" * 40), \
             mock.patch.object(publish, "require_workflow_context", return_value=("sascio/MyBridge", 123, "v9.9.9")), \
             mock.patch.object(ledger, "publishing_not_before", return_value=dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1)), \
             mock.patch.object(state, "load_config", return_value=STATE_CONFIG), \
             mock.patch.object(publish, "require_secret", side_effect=values.__getitem__) as secrets_read, \
             mock.patch.object(publish.announce, "load_release", return_value=sample_release()), \
             mock.patch.object(ledger, "GitLedger", return_value=MemoryStore()), \
             mock.patch.object(publish, "publish") as publisher, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(publish.main(["--release-json", "fixture.json", "--branch", "main", "--state-dir", "/tmp/state-checkout"]), 1)
            secrets_read.assert_not_called()
            publisher.assert_not_called()
            self.assertEqual(publish.main(["--release-json", "fixture.json", "--branch", "main", "--state-dir", "/tmp/state-checkout", "--resume-unsent"]), 0)
            self.assertFalse(publisher.call_args.kwargs["allow_new_release"])

    def test_live_cli_rejects_release_or_tag_that_does_not_match_event(self):
        values = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
                  "TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": API_HASH}
        for event_name, context, extra in (("release", ("sascio/MyBridge", 999, "v9.9.9"), []),
                                           ("workflow_dispatch", ("sascio/MyBridge", 123, "v-other"), ["--resume-unsent"])):
            with self.subTest(event=event_name), mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": event_name}), \
                 mock.patch.object(publish, "require_workflow_context", return_value=context), \
                 mock.patch.object(ledger, "publishing_not_before", return_value=dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1)), \
                 mock.patch.object(state, "load_config", return_value=STATE_CONFIG), \
                 mock.patch.object(publish, "require_secret", side_effect=values.__getitem__), \
                 mock.patch.object(publish.announce, "load_release", return_value=sample_release()), \
                 mock.patch.object(ledger, "GitLedger") as store, \
                 mock.patch.object(publish, "publish") as publisher, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(publish.main(["--release-json", "fixture.json", "--branch", "main", "--state-dir", "/tmp/state-checkout", *extra]), 1)
                store.assert_not_called()
                publisher.assert_not_called()

    def test_live_cli_requires_exact_workflow_and_default_branch_for_dispatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            event = Path(tmp, "event.json")
            payload = {"repository": {"full_name": "sascio/MyBridge", "default_branch": "main"},
                       "action": "published", "release": {"id": 123, "tag_name": "v1"},
                       "inputs": {"preflight": "false", "dry_run": "false", "resume_unsent": "true", "tag": "v1", "release_id": "123"}}
            event.write_text(json.dumps(payload))
            env = {"GITHUB_ACTIONS": "true", "GITHUB_RUN_ID": "101", "GH_TOKEN": "fake-gh-token",
                   "TELEGRAM_PUBLISHING_ENABLED": "true",
                   "GITHUB_REPOSITORY": "sascio/MyBridge", "GITHUB_EVENT_NAME": "workflow_dispatch",
                   "GITHUB_WORKFLOW_REF": "sascio/MyBridge/.github/workflows/telegram-release-delivery.yml@refs/heads/main",
                   "GITHUB_EVENT_PATH": str(event), "GITHUB_REF": "refs/heads/main"}
            with mock.patch.dict(os.environ, env, clear=True):
                self.assertEqual(publish.require_workflow_context("main"), ("sascio/MyBridge", 123, "v1"))
                for key, value in (("GITHUB_WORKFLOW_REF", "sascio/MyBridge/.github/workflows/other.yml@refs/heads/main"),
                                   ("GITHUB_WORKFLOW_REF", "sascio/MyBridge/.github/workflows/telegram-release-announcement.yml@refs/heads/main"),
                                   ("GITHUB_REF", "refs/heads/old-branch"), ("GITHUB_EVENT_NAME", "push"),
                                   ("GH_TOKEN", ""), ("TELEGRAM_PUBLISHING_ENABLED", ""),
                                   ("TELEGRAM_PUBLISHING_ENABLED", "TRUE")):
                    with self.subTest(key=key, value=value), mock.patch.dict(os.environ, {key: value}):
                        with self.assertRaises(publish.PublishError):
                            publish.require_workflow_context("main")
                with self.assertRaises(publish.PublishError):
                    publish.require_workflow_context("untrusted-branch")
                for field, value in (("preflight", "true"), ("dry_run", "true"),
                                     ("resume_unsent", "false"), ("tag", ""), ("tag", "   "), ("tag", "latest"), ("release_id", "")):
                    malformed = copy.deepcopy(payload)
                    malformed["inputs"][field] = value
                    event.write_text(json.dumps(malformed))
                    with self.subTest(field=field), self.assertRaises(publish.PublishError):
                        publish.require_workflow_context("main")
                event.write_text(json.dumps(payload))
                with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "release", "GITHUB_REF": "refs/tags/v1"}):
                    self.assertEqual(publish.require_workflow_context("main"), ("sascio/MyBridge", 123, "v1"))
                    for field, value in (("action", "created"), ("release", {"id": True}), ("release", {})):
                        malformed = copy.deepcopy(payload)
                        malformed[field] = value
                        event.write_text(json.dumps(malformed))
                        with self.subTest(field=field), self.assertRaises(publish.PublishError):
                            publish.require_workflow_context("main")


class LedgerValidationTests(unittest.TestCase):
    def test_publishing_activation_is_default_off_and_malformed_values_fail_closed(self):
        tracked = SCRIPTS.parent / ledger.PUBLISHING_CONFIG_PATH
        self.assertEqual(json.loads(tracked.read_text()), {"enabled": True, "activation": "ledger"})
        self.assertTrue(ledger.publishing_ledger_mode(str(tracked)))
        # Tracked opt-in alone is not evidence that the protected ledger is ready.
        self.assertFalse(ledger.publishing_enabled(str(tracked)))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "activation.json")
            self.assertFalse(ledger.publishing_enabled(str(path)))
            for raw in (b'', b'{', b'null', b'{"enabled":false}', b'{"enabled":1}',
                        b'{"enabled":"true"}', b'{"enabled":true}', b'{"enabled":true,"extra":0}',
                        b'{"enabled":true,"not_before":"invalid"}',
                        b'{"enabled":true,"not_before":"2999-01-01T00:00:00Z"}',
                        b'{"enabled":false,"enabled":true}', b'\xff'):
                path.write_bytes(raw)
                with self.subTest(raw=raw):
                    self.assertFalse(ledger.publishing_enabled(str(path)))
            path.write_text('{"enabled":true,"not_before":"2000-01-01T00:00:00Z"}', encoding="utf-8")
            self.assertTrue(ledger.publishing_enabled(str(path)))

    def test_strict_activation_distinguishes_disabled_from_malformed(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "activation.json")
            with self.assertRaises(ledger.LedgerError):
                ledger.strict_publishing_status(str(path))
            for raw in (b'{"enabled":0}', b'null', b'{"enabled":false,"extra":0}',
                        b'{"enabled":false,"enabled":false}', b'{"enabled":true}'):
                path.write_bytes(raw)
                with self.subTest(raw=raw), self.assertRaises(ledger.LedgerError):
                    ledger.strict_publishing_status(str(path))
            path.write_text('{"enabled":false}', encoding="utf-8")
            self.assertFalse(ledger.strict_publishing_status(str(path)))
            path.write_text('{"enabled":true,"not_before":"2000-01-01T00:00:00Z"}', encoding="utf-8")
            self.assertTrue(ledger.strict_publishing_status(str(path)))
            path.write_text('{"enabled":true,"activation":"ledger"}', encoding="utf-8")
            self.assertTrue(ledger.strict_publishing_status(str(path)))
            self.assertTrue(ledger.publishing_ledger_mode(str(path)))
            self.assertFalse(ledger.publishing_enabled(str(path)))
            for raw in ('{"enabled":1,"activation":"ledger"}', '{"enabled":true,"activation":"other"}',
                        '{"enabled":true,"activation":"ledger","extra":0}'):
                path.write_text(raw, encoding="utf-8")
                self.assertFalse(ledger.publishing_ledger_mode(str(path)))
                with self.assertRaises(ledger.LedgerError):
                    ledger.strict_publishing_status(str(path))

    def test_offline_guard_blocks_external_dns_and_tcp(self):
        import socket
        with self.assertRaisesRegex(OSError, "blocked"):
            socket.getaddrinfo("example.invalid", 443)
        with socket.socket() as sock, self.assertRaisesRegex(OSError, "blocked"):
            sock.connect(("203.0.113.1", 443))

    def test_corrupt_missing_duplicate_keys_and_wrong_schema_fail_closed(self):
        for raw in (b"", b"{", b"null", b'{"schema":3,"schema":3}', b'{"schema":1,"releases":[]}'):
            with self.subTest(raw=raw), self.assertRaises(ledger.LedgerError):
                ledger.parse(raw)

    def test_protected_inventory_blinds_nonpublic_ids_and_tags_and_detects_key_changes(self):
        original = {"release_ids": [10, 1122334455], "tags": ["public-tag", "nonpublic-draft-tag"],
                    "message_ids": [5]}
        before = copy.deepcopy(original)
        inventory = ledger.protect_inventory(original, {10}, {"public-tag"}, TOKEN)
        encoded = ledger.canonical(inventory).decode()
        self.assertEqual(original, before)
        self.assertNotIn("1122334455", encoded)
        self.assertNotIn("nonpublic-draft-tag", encoded)
        self.assertNotIn(TOKEN, encoded)
        self.assertEqual(inventory["release_ids"], [10])
        self.assertEqual(inventory["tags"], ["public-tag"])
        ledger.require_inventory_key(inventory, TOKEN, required=True)
        self.assertTrue(ledger.historically_seen(inventory, TOKEN, 1122334455, "another"))
        self.assertTrue(ledger.historically_seen(inventory, TOKEN, 11, "nonpublic-draft-tag"))
        self.assertFalse(ledger.historically_seen(inventory, TOKEN, 11, "new-tag"))
        with self.assertRaises(ledger.LedgerError):
            ledger.require_inventory_key(inventory, TOKEN + "-changed", required=True)
        doc = ledger.migrate({"schema": 1, "releases": []}, "2026-01-01T00:00:00Z", inventory)
        self.assertEqual(doc["inventory"], inventory)
        self.assertEqual(ledger.summarize(doc)["inventoried_release_ids"], 2)
        self.assertEqual(ledger.summarize(doc)["inventoried_tags"], 2)
        self.assertNotIn("nonpublic-draft-tag", ledger.canonical(doc).decode())
        with self.assertRaisesRegex(ledger.LedgerError, "non-public legacy"):
            ledger.migrate({"schema": 2, "releases": [{"release_id": 1122334455,
                                                       "tag_name": "nonpublic-draft-tag"}]},
                           "2026-01-01T00:00:00Z", inventory)
        for bad in ({"key_check": "invalid", "release_id_digests": [], "tag_digests": []},
                    {"key_check": "a" * 64, "release_id_digests": ["b" * 64] * 2, "tag_digests": []},
                    {"key_check": "a" * 64, "release_id_digests": [], "tag_digests": [123]},
                    {"key_check": "a" * 64, "release_id_digests": [], "tag_digests": [], "tag_ref_snapshot": "invalid"}):
            altered = copy.deepcopy(inventory)
            altered["protected"] = bad
            with self.subTest(bad=bad), self.assertRaises(ledger.LedgerError):
                ledger.validate_inventory(altered)

    def test_inventory_protection_cli_is_new_file_only_and_never_prints_private_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            private = Path(tmp, "private.json")
            public = Path(tmp, "public.json")
            output = Path(tmp, "protected.json")
            private.write_text(json.dumps({"release_ids": [101, 1122334455],
                                           "tags": ["v-public", "private-draft"], "message_ids": [5]}))
            public.write_text(json.dumps({"release_ids": [101], "tags": ["v-public"]}))
            env = dict(os.environ, TELEGRAM_BOT_TOKEN=TOKEN)
            cmd = [sys.executable, str(SCRIPTS / "telegram_ledger.py"), "--protect-inventory",
                   "--inventory", str(private), "--public-inventory", str(public), "--output", str(output)]
            result = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertNotIn(TOKEN, result.stdout + result.stderr)
            self.assertNotIn("private-draft", result.stdout + result.stderr + output.read_text())
            self.assertNotIn("1122334455", output.read_text())
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            protected = json.loads(output.read_text())
            ledger.require_inventory_key(protected, TOKEN, required=True)
            self.assertTrue(ledger.historically_seen(protected, TOKEN, 1122334455, "unused"))
            previous = output.read_bytes()
            self.assertNotEqual(subprocess.run(cmd, env=env, capture_output=True, timeout=30).returncode, 0)
            self.assertEqual(output.read_bytes(), previous)
            output.unlink()
            env["TELEGRAM_BOT_TOKEN"] = ""
            self.assertNotEqual(subprocess.run(cmd, env=env, capture_output=True, timeout=30).returncode, 0)
            self.assertFalse(output.exists())

    def test_legacy_snapshot_is_preserved_and_hash_verified(self):
        old = {"schema": 2, "releases": [{"release_id": 1, "tag_name": "v1.0", "assets": [{"name": "old.apk", "message_id": 9}]}]}
        doc = ledger.migrate(old, "2026-01-01T00:00:00Z", EMPTY_INVENTORY)
        self.assertEqual(doc["legacy"], old)
        doc["legacy"]["releases"].clear()
        with self.assertRaises(ledger.LedgerError):
            ledger.validate(doc)

    def test_migration_preserves_all_legacy_announcement_and_upload_fields(self):
        fixtures = [
            {"schema": 1, "description": "original announcement ledger", "releases": [
                {"release_id": 1, "tag_name": "v1", "version": "1.0", "message_ids": [2, 3],
                 "announced_at": "2026-01-01T00:00:00Z", "extra_evidence": {"preserve": True}}]},
            {"schema": 2, "description": "preserve every original field", "releases": [
                {"release_id": 10, "tag_name": "v1", "announced": True, "message_ids": [11, 12],
                 "announced_at": "2026-01-01T00:00:00Z", "assets": [
                     {"name": "one.apk", "message_id": 13, "sha256": "a" * 64, "size": 17},
                     {"name": "two.ipa", "message_id": 14, "sha256": None, "size": 18}]},
                {"release_id": 20, "tag_name": "v2", "announced": False, "message_ids": [],
                 "assets": [{"name": "partial.apk", "message_id": 21, "custom_evidence": {"old": True}}]},
            ]},
        ]
        for old in fixtures:
            with self.subTest(schema=old["schema"]):
                original = copy.deepcopy(old)
                doc = ledger.migrate(old, "2026-01-01T00:00:00Z", EMPTY_INVENTORY)
                self.assertEqual(doc["legacy"], original)
                self.assertEqual(doc["activation"]["legacy_sha256"], ledger.digest(original))
                self.assertEqual(doc["activation"]["inventory_sha256"], ledger.digest(doc["inventory"]))
                self.assertEqual(set(doc["inventory"]["release_ids"]), {e["release_id"] for e in original["releases"]})
                self.assertEqual(set(doc["inventory"]["tags"]), {e["tag_name"] for e in original["releases"]})
                self.assertEqual(old, original)
                self.assertEqual(ledger.summarize(doc)["legacy_channel_history"], "unknown")

    def test_tracked_legacy_migration_is_only_prepared_and_never_overwrites_input(self):
        source = SCRIPTS.parent / ledger.LEGACY_PATH
        before = source.read_bytes()
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp, "prepared.json")
            inventory = Path(tmp, "inventory.json")
            inventory.write_text(json.dumps({"release_ids": [42], "tags": ["v-old"]}))
            cutoff = utc(dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=1))
            args = [sys.executable, str(SCRIPTS / "telegram_ledger.py"), "--legacy", str(source),
                    "--inventory", str(inventory), "--output", str(output), "--published-after", cutoff,
                    "--acknowledge-legacy-is-unknown", "--acknowledge-release-freeze"]
            missing_ack = subprocess.run(args[:-1], capture_output=True, text=True, timeout=30)
            self.assertNotEqual(missing_ack.returncode, 0)
            self.assertFalse(output.exists())
            inventory.write_text('{"release_ids":[],"tags":[],"tags":[]}')
            duplicate_keys = subprocess.run(args, capture_output=True, text=True, timeout=30)
            self.assertNotEqual(duplicate_keys.returncode, 0)
            self.assertFalse(output.exists())
            inventory.write_bytes(b'\xff')
            invalid_utf8 = subprocess.run(args, capture_output=True, text=True, timeout=30)
            self.assertNotEqual(invalid_utf8.returncode, 0)
            self.assertNotIn('Traceback', invalid_utf8.stdout + invalid_utf8.stderr)
            self.assertFalse(output.exists())
            inventory.write_text(json.dumps({"release_ids": [42], "tags": ["v-old"]}))
            result = subprocess.run(args, capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            prepared = ledger.parse(output.read_bytes())
            self.assertEqual(prepared["legacy"], json.loads(before))
            self.assertEqual(prepared["inventory"], {"release_ids": [42], "tags": ["v-old"]})
            preserved_output = output.read_bytes()
            second = subprocess.run(args, capture_output=True, text=True, timeout=30)
            self.assertNotEqual(second.returncode, 0)
            self.assertEqual(output.read_bytes(), preserved_output)
        self.assertEqual(source.read_bytes(), before)

    def test_schema_four_cannot_overlap_preserved_legacy_id_or_tag(self):
        release = sample_release()
        operations = [{"key": "page:1", "kind": "page", "payload": "a" * 64}]
        for legacy_entry in ({"release_id": release.id, "tag_name": "v-old"},
                             {"release_id": 999, "tag_name": release.tag_name}):
            doc = ledger.migrate({"schema": 1, "releases": [legacy_entry]},
                                 utc(dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1)), EMPTY_INVENTORY)
            doc["releases"] = [make_release(release, operations, set())]
            with self.subTest(legacy_entry=legacy_entry), self.assertRaisesRegex(ledger.LedgerError, "overlaps"):
                ledger.validate(doc)

    def test_schema_four_cannot_confirm_a_preserved_legacy_message_id(self):
        release = sample_release()
        legacy_entry = {"release_id": 999, "tag_name": "v-old", "message_ids": [10],
                        "assets": [{"name": "old.apk", "message_id": 11}]}
        for message_id in (10, 11):
            doc = ledger.migrate({"schema": 1, "releases": [legacy_entry]},
                                 utc(dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1)), EMPTY_INVENTORY)
            operation = {"key": "page:1", "kind": "page", "payload": "a" * 64}
            entry = make_release(release, [operation], set())
            mark_attempt(entry["items"][0], "sent", message_id)
            sync_status(entry)
            doc["identity"] = "b" * 64
            doc["releases"] = [entry]
            with self.subTest(message_id=message_id), self.assertRaisesRegex(ledger.LedgerError, "repeated message id"):
                ledger.validate(doc)

    def test_audited_message_ids_are_preserved_and_cannot_be_reconfirmed(self):
        inventory = {"release_ids": [434756896], "tags": ["v0.1.15"], "message_ids": [5]}
        doc = ledger.migrate({"schema": 1, "releases": []},
                             utc(dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1)), inventory)
        self.assertEqual(doc["inventory"], inventory)
        self.assertEqual(ledger.summarize(doc)["inventoried_message_ids"], 1)
        release = sample_release()
        entry = make_release(release, [{"key": "page:1", "kind": "page", "payload": "a" * 64}], set())
        mark_attempt(entry["items"][0], "sent", 5)
        sync_status(entry)
        doc["identity"] = "b" * 64
        doc["releases"] = [entry]
        with self.assertRaisesRegex(ledger.LedgerError, "repeated message id"):
            ledger.validate(doc)
        for ids in ([5, 5], [0], [True], "5"):
            with self.subTest(ids=ids), self.assertRaises(ledger.LedgerError):
                ledger.migrate({"schema": 1, "releases": []}, "2026-01-01T00:00:00Z",
                               {"release_ids": [], "tags": [], "message_ids": ids})

    def test_schema_four_rejects_inventory_overlap_even_if_legacy_ledger_was_empty(self):
        release = sample_release()
        operation = {"key": "page:1", "kind": "page", "payload": "a" * 64}
        for inventory in ({"release_ids": [release.id], "tags": []},
                          {"release_ids": [], "tags": [release.tag_name]}):
            doc = ledger.migrate({"schema": 1, "releases": []},
                                 utc(dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1)), inventory)
            doc["releases"] = [make_release(release, [operation], set())]
            with self.subTest(inventory=inventory), self.assertRaisesRegex(ledger.LedgerError, "overlaps"):
                ledger.validate(doc)

    def test_inventory_validation_and_snapshot_checksum_fail_closed(self):
        old_preview = migrated()
        del old_preview["inventory"]
        with self.assertRaises(ledger.LedgerError):
            ledger.validate(old_preview)
        bad = (None, {}, {"release_ids": [True], "tags": []},
               {"release_ids": [1, 1], "tags": []}, {"release_ids": [], "tags": [None]},
               {"release_ids": [], "tags": ["v1", "v1"]}, {"release_ids": [], "tags": ["v1\n"]})
        for inventory in bad:
            with self.subTest(inventory=inventory), self.assertRaises(ledger.LedgerError):
                ledger.migrate({"schema": 1, "releases": []}, "2026-01-01T00:00:00Z", inventory)
        doc = ledger.migrate({"schema": 1, "releases": [{"release_id": 42, "tag_name": "old"}]},
                             "2026-01-01T00:00:00Z", EMPTY_INVENTORY)
        doc["inventory"]["tags"].clear()
        with self.assertRaisesRegex(ledger.LedgerError, "checksum"):
            ledger.validate(doc)
        doc["activation"]["inventory_sha256"] = ledger.digest(doc["inventory"])
        with self.assertRaisesRegex(ledger.LedgerError, "omits"):
            ledger.validate(doc)

    def test_modified_frozen_plan_is_rejected(self):
        doc = migrated()
        entry = make_release(sample_release(), [{"key": "page:1", "kind": "page", "payload": "a" * 64}], set())
        doc["releases"] = [entry]
        entry["items"][0]["payload"] = "b" * 64
        with self.assertRaises(ledger.LedgerError):
            ledger.validate(doc)

    def test_duplicate_random_ids_are_rejected(self):
        doc = migrated()
        entry = make_release(sample_release(), [{"key": "page:1", "kind": "page", "payload": "a" * 64},
                                                        {"key": "page:2", "kind": "page", "payload": "b" * 64}], set())
        entry["items"][1]["random_id"] = entry["items"][0]["random_id"]
        doc["releases"] = [entry]
        with self.assertRaises(ledger.LedgerError):
            ledger.validate(doc)

    def test_unfinished_pages_cannot_be_skipped_or_followed_by_asset_sends(self):
        planned = [{"key": "page:1", "kind": "page", "payload": "a" * 64},
                   {"key": "page:2", "kind": "page", "payload": "b" * 64},
                   {"key": "asset:1", "kind": "asset", "payload": "c" * 64}]
        for target in (1, 2):
            doc = migrated()
            entry = make_release(sample_release(), planned, set())
            item = entry["items"][target]
            item.update(state="sending", attempts=1)
            if item["kind"] == "asset":
                item["sha256"] = "0" * 64
            doc["releases"] = [entry]
            with self.subTest(target=target), self.assertRaises(ledger.LedgerError):
                ledger.validate(doc)

    def test_asset_only_and_out_of_order_page_plans_are_rejected(self):
        for planned in ([{"key": "asset:1", "kind": "asset", "payload": "a" * 64}],
                        [{"key": "page:2", "kind": "page", "payload": "a" * 64}]):
            doc = migrated()
            doc["releases"] = [make_release(sample_release(), planned, set())]
            with self.subTest(planned=planned), self.assertRaises(ledger.LedgerError):
                ledger.validate(doc)

    def test_independent_asset_states_remain_valid_when_an_earlier_file_is_unfinished(self):
        doc = migrated()
        entry = make_release(sample_release(), [
            {"key": "page:1", "kind": "page", "payload": "a" * 64},
            {"key": "asset:1", "kind": "asset", "payload": "b" * 64},
            {"key": "asset:2", "kind": "asset", "payload": "c" * 64}], set())
        mark_attempt(entry["items"][0], "sent", 77)
        mark_attempt(entry["items"][2], "sent", 78)
        entry["items"][2]["sha256"] = "d" * 64
        sync_status(entry)
        doc["identity"] = "e" * 64
        doc["releases"] = [entry]
        ledger.validate(doc)
        mark_attempt(entry["items"][1], "sending")
        entry["items"][1]["sha256"] = "f" * 64
        sync_status(entry)
        ledger.validate(doc)  # recovering the earlier file does not invalidate the later confirmation

    def test_redacted_inspection_distinguishes_sent_unsent_and_unknown(self):
        doc = migrated()
        entry = make_release(sample_release(), [
            {"key": "page:1", "kind": "page", "payload": "a" * 64},
            {"key": "asset:1", "kind": "asset", "payload": "b" * 64},
            {"key": "asset:2", "kind": "asset", "payload": "c" * 64}], set())
        mark_attempt(entry["items"][0], "sent", 77)
        mark_attempt(entry["items"][1], "delivery_unknown")
        entry["items"][1]["sha256"] = "d" * 64
        sync_status(entry)
        doc["identity"] = "e" * 64
        doc["releases"] = [entry]
        expected = {"confirmed_sent": 1, "confirmed_unsent_by_protocol": 1, "unknown": 1}
        self.assertEqual(ledger.summarize(doc)["operations"], expected)
        self.assertEqual(ledger.operation_outcome({"state": "uploading"}), "confirmed_unsent_by_protocol")
        self.assertEqual(ledger.operation_outcome({"state": "sending"}), "unknown")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "state.json")
            path.write_bytes(ledger.canonical(doc))
            result = subprocess.run([sys.executable, str(SCRIPTS / "telegram_ledger.py"), "--inspect", str(path)],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report["operations"], expected)
            self.assertEqual(report["legacy_channel_history"], "unknown")
            for secret in (TOKEN, CHAT, API_HASH, "v9.9.9"):
                self.assertNotIn(secret, result.stdout)

    def test_session_data_never_contains_token_or_session_material(self):
        doc = migrated()
        doc["identity"] = publish.identity_binding(TOKEN, 1, -1001)
        doc["session"] = {"owner": "run-42-abcdef"}
        encoded = ledger.canonical(doc).decode()
        for secret in (TOKEN, API_HASH, CHAT):
            self.assertNotIn(secret, encoded)


class RealGitLedgerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.remote = self.root / "remote.git"
        self.work = self.root / "work"
        self.git("init", "--bare", "--initial-branch=telegram-delivery-state", str(self.remote), cwd=self.root)
        self.git("clone", str(self.remote), str(self.work), cwd=self.root)
        self.git("config", "user.name", "Test", cwd=self.work)
        self.git("config", "user.email", "test@example.invalid", cwd=self.work)
        path = self.work / ledger.LEDGER_PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(ledger.canonical(migrated()))
        self.git("add", ".", cwd=self.work)
        self.git("commit", "-m", "initial", cwd=self.work)
        self.git("push", "origin", "HEAD:refs/heads/telegram-delivery-state", cwd=self.work)
        self.store = ledger.GitLedger(str(self.work), state.STATE_BRANCH)

    @staticmethod
    def git(*args, cwd):
        return subprocess.run(["git", *args], cwd=cwd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

    def test_exact_parent_commit_changes_only_ledger_and_does_not_touch_checkout(self):
        original_head = self.git("rev-parse", "HEAD", cwd=self.work).strip()
        snap = self.store.read()
        new = copy.deepcopy(snap.document)
        new["session"] = {"owner": "run-test"}
        saved = self.store.save(snap, new)
        self.assertEqual(saved.document["session"], {"owner": "run-test"})
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.work).strip(), original_head)
        changed = self.git("diff", "--name-only", snap.parent, saved.parent, cwd=self.work).decode().splitlines()
        self.assertEqual(changed, [ledger.LEDGER_PATH])

    def test_concurrent_non_fast_forward_write_is_not_lost(self):
        stale = self.store.read()
        fresh = self.store.read()
        current = copy.deepcopy(fresh.document)
        current["session"] = {"owner": "winner"}
        self.store.save(fresh, current)
        losing = copy.deepcopy(stale.document)
        losing["session"] = {"owner": "loser"}
        with self.assertRaises(ledger.LedgerError):
            self.store.save(stale, losing)
        self.assertEqual(self.store.read().document["session"], {"owner": "winner"})

    def test_ambiguous_push_response_is_reconciled_by_refetch(self):
        snap = self.store.read()
        new = copy.deepcopy(snap.document)
        new["session"] = {"owner": "ambiguous"}
        original = self.store._git
        def lose_response(*args, **kwargs):
            result = original(*args, **kwargs)
            if args and args[0] == "push":
                raise ledger.LedgerError("simulated lost response")
            return result
        with mock.patch.object(self.store, "_git", side_effect=lose_response):
            saved = self.store.save(snap, new)
        self.assertEqual(saved.document["session"], {"owner": "ambiguous"})

    def test_preserved_history_cannot_be_rewritten_even_with_new_checksums(self):
        original = self.store.read()
        for key in ("legacy", "inventory", "activation"):
            with self.subTest(field=key):
                document = copy.deepcopy(original.document)
                if key == "legacy":
                    document["legacy"]["description"] = "rewritten"
                    document["activation"]["legacy_sha256"] = ledger.digest(document["legacy"])
                elif key == "inventory":
                    document["inventory"]["tags"].append("old-tag")
                    document["activation"]["inventory_sha256"] = ledger.digest(document["inventory"])
                else:
                    document["activation"]["published_after"] = "2000-01-01T00:00:00Z"
                ledger.validate(document)
                with self.assertRaisesRegex(ledger.LedgerError, "immutable"):
                    self.store.save(original, document)
                self.assertEqual(self.store.read().raw, original.raw)

    def test_mutated_snapshot_fails_closed(self):
        snapshot = self.store.read()
        snapshot.document["session"] = {"owner": "modified-locally"}
        with self.assertRaisesRegex(ledger.LedgerError, "snapshot"):
            self.store.save(snapshot, copy.deepcopy(snapshot.document))

    def test_missing_remote_ledger_is_not_recreated(self):
        self.git("rm", ledger.LEDGER_PATH, cwd=self.work)
        self.git("commit", "-m", "remove ledger", cwd=self.work)
        self.git("push", "origin", "HEAD:telegram-delivery-state", cwd=self.work)
        with self.assertRaisesRegex(ledger.LedgerError, "missing"):
            self.store.read()

    @unittest.skipUnless(telethon is not None, "Telethon required for publisher plan rendering")
    def test_two_simultaneous_publishers_use_one_session_and_one_send(self):
        other = self.root / "other"
        self.git("clone", str(self.remote), str(other), cwd=self.root)
        barrier = threading.Barrier(2)
        mutex = threading.Lock()
        stats = {"sessions": 0, "max_sessions": 0, "calls": []}
        outcomes = []

        class RacingStore(ledger.GitLedger):
            def __init__(worker, repo):
                super().__init__(str(repo), state.STATE_BRANCH)
                worker.first = True
            def read(worker):
                snapshot = super().read()
                if worker.first:
                    worker.first = False
                    barrier.wait(timeout=20)  # both workers saw the same unlocked parent
                return snapshot

        class Transport:
            def connect(worker):
                with mutex:
                    stats["sessions"] += 1
                    stats["max_sessions"] = max(stats["max_sessions"], stats["sessions"])
            def close(worker):
                with mutex:
                    stats["sessions"] -= 1
                return True
            def text_request(worker, markup, buttons, random_id):
                return SimpleNamespace(random_id=random_id)
            def send(worker, request, **_kwargs):
                with mutex:
                    stats["calls"].append(request.random_id)
                return 12345

        release = sample_release()
        def run(store):
            try:
                publish.publish(release, store, token=TOKEN, chat=CHAT, api_id=12345, api_hash=API_HASH, source_sha="a" * 40, workflow_run_id=10001, run_attempt=1, tooling_sha="b" * 40,
                                transport_factory=lambda *_: Transport(), bot_lookup=lambda *_: (777, -1002000000001, CHAT),
                                sleeper=lambda _: None, policy_check=lambda: None, allow_new_release=True)
                outcomes.append("success")
            except Exception as exc:
                outcomes.append(type(exc).__name__)

        threads = [threading.Thread(target=run, args=(RacingStore(path),)) for path in (self.work, other)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=30)
        self.assertTrue(all(not thread.is_alive() for thread in threads))
        self.assertEqual(len(outcomes), 2)
        self.assertIn("success", outcomes)
        self.assertTrue(all(outcome in {"success", "LedgerError", "PublishError"} for outcome in outcomes))
        self.assertEqual(stats["max_sessions"], 1)
        self.assertEqual(len(stats["calls"]), 1)
        document = self.store.read().document
        self.assertIsNone(document["session"])
        self.assertEqual(document["releases"][0]["items"][0]["state"], "sent")

    @unittest.skipUnless(telethon is not None and hasattr(signal, "SIGKILL"), "POSIX and Telethon required")
    def test_killed_worker_preserves_ambiguous_send_and_session_lock(self):
        release = sample_release()
        accepted = self.root / "fake-accepted-id"

        class CrashingTransport:
            def connect(worker):
                pass
            def close(worker):
                return True
            def text_request(worker, markup, buttons, random_id):
                return SimpleNamespace(random_id=random_id)
            def send(worker, request, **_kwargs):
                accepted.write_text(str(request.random_id))  # fake server acceptance, no network
                os.kill(os.getpid(), signal.SIGKILL)  # cleanup/finally cannot run

        def child():
            publish.publish(release, self.store, token=TOKEN, chat=CHAT, api_id=12345, api_hash=API_HASH, source_sha="a" * 40, workflow_run_id=10001, run_attempt=1, tooling_sha="b" * 40,
                            transport_factory=lambda *_: CrashingTransport(),
                            bot_lookup=lambda *_: (777, -1002000000001, CHAT),
                            sleeper=lambda _: None, policy_check=lambda: None, allow_new_release=True)

        process = multiprocessing.get_context("fork").Process(target=child)
        process.start()
        process.join(timeout=30)
        if process.is_alive():
            process.kill()
            process.join(timeout=5)
            self.fail("crash simulation did not finish")
        self.assertEqual(process.exitcode, -signal.SIGKILL)
        snapshot = self.store.read()
        item = snapshot.document["releases"][0]["items"][0]
        self.assertEqual(item["state"], "sending")
        self.assertEqual(item["random_id"], int(accepted.read_text()))
        self.assertIsNotNone(snapshot.document["session"])
        with self.assertRaisesRegex(publish.PublishError, "session"):
            publish.publish(release, self.store, token=TOKEN, chat=CHAT, api_id=12345, api_hash=API_HASH, source_sha="a" * 40, workflow_run_id=10001, run_attempt=1, tooling_sha="b" * 40,
                            resume_unsent=True, transport_factory=lambda *_: self.fail("must not log in"),
                            bot_lookup=lambda *_: self.fail("must not contact Bot API"),
                            policy_check=lambda: None, allow_new_release=False)
        self.assertEqual(self.store.read().document, snapshot.document)

@unittest.skipUnless(telethon is not None, "Telethon is required for raw RPC/part tests")
class RawTransportTests(unittest.TestCase):
    """Run real Telethon upload_file chunking and raw TL objects against fake RPCs."""

    def setUp(self):
        import asyncio
        from fake_telegram_rpc import FakeRpcClient, FakeRpcServer, FAKE_BOT_ID, FAKE_CHAT_ID, FAKE_CHAT_ACCESS_HASH
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        self.addCleanup(asyncio.set_event_loop, None)
        self.addCleanup(self.loop.close)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.channel_id = 2000000001

        class RawServer(FakeRpcServer):
            def __init__(server):
                super().__init__()
                server.raw_calls = []
                server.responses = {}
                server.lose_response = False
            async def call(server, request):
                if isinstance(request, (functions.messages.SendMessageRequest, functions.messages.SendMediaRequest)):
                    server.raw_calls.append(request)
                    if request.random_id in server.responses:
                        return server.responses[request.random_id]
                    server.next_message_id += 1
                    media = None
                    if isinstance(request, functions.messages.SendMediaRequest):
                        handle = request.media.file
                        size = sum(len(x) for x in server.parts[handle.id].values())
                        document = types.Document(id=4321, access_hash=1, file_reference=b'x', date=None,
                                                  mime_type=request.media.mime_type, size=size, dc_id=2,
                                                  attributes=request.media.attributes)
                        media = types.MessageMediaDocument(document=document)
                    message = types.Message(id=server.next_message_id, peer_id=types.PeerChannel(2000000001),
                                            date=None, message=request.message, media=media,
                                            entities=request.entities, reply_markup=request.reply_markup)
                    result = types.Updates(updates=[types.UpdateMessageID(id=message.id, random_id=request.random_id),
                                                     types.UpdateNewChannelMessage(message=message, pts=1, pts_count=1)],
                                           users=[], chats=[], date=None, seq=1)
                    server.responses[request.random_id] = result
                    if server.lose_response:
                        server.lose_response = False
                        raise TimeoutError("response dropped after acceptance")
                    return result
                if isinstance(request, (functions.messages.SendMultiMediaRequest, functions.messages.UploadMediaRequest)):
                    raise AssertionError("grouped media is forbidden")
                return await super().call(request)

        class ChunkClient(FakeRpcClient):
            async def start(client, *, bot_token):
                client.started_with_bot = bot_token == TOKEN
                return client
            async def get_input_entity(client, peer):
                return types.InputPeerChannel(channel_id=2000000001, access_hash=FAKE_CHAT_ACCESS_HASH)
            async def log_out(client):
                client.logged_out = True
                return True

        self.server = RawServer()
        self.client = ChunkClient(self.server)
        self.transport = mtproto.BotTransport(12345, API_HASH, TOKEN, CHAT, FAKE_BOT_ID, FAKE_CHAT_ID,
                                               factory=lambda *_a, **_k: self.client)
        self.transport.connect()
        self.addCleanup(self.transport.close)

    def test_raw_text_has_fixed_random_id_html_entities_and_download_buttons(self):
        request = self.transport.text_request('<b>StreamBridge</b> &amp; <i>more</i>',
                                              [('⬇️ Download StreamBridge', 'https://github.com/a/b')], -42)
        self.assertIsInstance(request, functions.messages.SendMessageRequest)
        self.assertEqual(request.random_id, -42)
        self.assertTrue(request.no_webpage)
        self.assertEqual(request.message, 'StreamBridge & more')
        self.assertTrue(any(isinstance(x, types.MessageEntityBold) for x in request.entities))
        self.assertIsInstance(self.transport.send(request), int)
        button = request.reply_markup.rows[0].buttons[0]
        self.assertIsInstance(button, types.KeyboardInlineButton)
        self.assertIsInstance(button.type, types.InlineButtonTypeUrl)
        self.assertEqual(button.type.url, 'https://github.com/a/b')
        bytes(request)  # actual TL serialization, not just construction

    def test_raw_document_uses_real_chunking_and_same_preallocated_id(self):
        path = Path(self.tmp.name, 'app.apk')
        body = bytes(range(256)) * (12 * 1024 * 1024 // 256 + 7)
        path.write_bytes(body)
        media = self.transport.prepare_document(str(path), 'app.apk')
        self.assertIn('SaveBigFilePartRequest', self.client.rpc_log)
        self.assertEqual(self.server.uploaded_bytes(self.server.last_upload_file_id), body)
        sizes = [len(value) for _, value in sorted(self.server.parts[self.server.last_upload_file_id].items())]
        self.assertEqual(set(sizes[:-1]), {512 * 1024})
        self.assertLessEqual(sizes[-1], 512 * 1024)
        request = self.transport.media_request(media, '<b>StreamBridge</b> IPA', 987654321)
        self.assertIsInstance(request, functions.messages.SendMediaRequest)
        self.assertEqual(request.random_id, 987654321)
        bytes(request)  # verify the prepared upload handle and media TL schema serialize
        self.assertEqual(self.transport.send(request, filename='app.apk', size=len(body)), self.server.next_message_id)
        self.assertNotIn('SendMultiMediaRequest', self.client.rpc_log)
        self.assertNotIn('UploadMediaRequest', self.client.rpc_log)

    def test_lost_media_response_is_not_retried_inside_transport(self):
        path = Path(self.tmp.name, 'app.ipa')
        path.write_bytes(b'ipa payload')
        media = self.transport.prepare_document(str(path), path.name)
        request = self.transport.media_request(media, '<b>Caption</b>', -321)
        self.server.lose_response = True
        with self.assertRaises(TimeoutError):
            self.transport.send(request, filename=path.name, size=path.stat().st_size)
        self.assertEqual([r.random_id for r in self.server.raw_calls], [-321])
        self.assertEqual(len(self.server.responses), 1)

    def test_same_id_with_different_content_is_not_accepted_as_confirmation(self):
        first = self.transport.text_request('<b>first</b>', [], 123456)
        self.transport.send(first)
        changed = self.transport.text_request('<b>changed</b>', [], 123456)
        with self.assertRaises(mtproto.UnconfirmedResponse):
            self.transport.send(changed)

    def test_missing_random_id_or_non_individual_request_is_rejected(self):
        with self.assertRaises(mtproto.TransportError):
            self.transport.text_request('x', [], 0)
        group = functions.messages.SendMultiMediaRequest(peer=self.transport.peer, multi_media=[])
        with self.assertRaises(mtproto.TransportError):
            self.transport.send(group)
        self.assertEqual(self.server.raw_calls, [])

    def test_wrong_channel_or_message_metadata_is_not_confirmed(self):
        req = self.transport.text_request('x', [], 333)
        self.transport.send(req)
        result = self.server.responses[333]
        result.updates[1].message.peer_id = types.PeerChannel(123)
        with self.assertRaises(mtproto.UnconfirmedResponse):
            self.transport.send(req)

    def test_authentication_uses_only_bot_authorization_not_phone_login(self):
        from fake_telegram_rpc import AuthStub
        rpc = AuthStub()
        self.loop.run_until_complete(rpc._start(None, None, TOKEN, False, None, 's', 'b', 3))
        self.assertIn('ImportBotAuthorizationRequest', rpc.seen)
        self.assertNotIn('SendCodeRequest', rpc.seen)
        self.assertNotIn('SignInRequest', rpc.seen)

    def test_login_retries_only_confirmed_user_dc_migration_and_keeps_send_retries_off(self):
        base = type(self.client)
        class MigratingClient(base):
            def __init__(client, server):
                super().__init__(server)
                client.starts = 0
            async def start(client, *, bot_token):
                client.starts += 1
                self.assertEqual(bot_token, TOKEN)
                if client.starts == 1:
                    # Telethon's zero-retry _call has already switched this DC
                    # before surfacing the migrate error.
                    client.session.set_dc(4, "127.0.0.1", 443)
                    raise errors.UserMigrateError(request=None, capture=4)
                return await super().start(bot_token=bot_token)
        client = MigratingClient(self.server)
        kwargs = {}
        def factory(*args, **options):
            kwargs.update(options)
            return client
        transport = mtproto.BotTransport(12345, API_HASH, TOKEN, CHAT, 8000000001, -1002000000001,
                                         factory=factory)
        transport.connect()
        self.assertEqual(client.starts, 2)
        self.assertEqual(kwargs["request_retries"], 0)
        self.assertIs(kwargs["auto_reconnect"], False)  # Telethon otherwise requeues pending sends after disconnect.
        self.assertEqual(transport.connection_stage, "ready")
        self.assertTrue(transport.close())

    def test_unconfirmed_or_repeated_login_migration_fails_closed(self):
        base = type(self.client)
        for confirm_switch in (False, True):
            class MigratingClient(base):
                def __init__(client, server):
                    super().__init__(server)
                    client.starts = 0
                async def start(client, *, bot_token):
                    client.starts += 1
                    if confirm_switch:
                        client.session.set_dc(4, "127.0.0.1", 443)
                    raise errors.UserMigrateError(request=None, capture=4)
            client = MigratingClient(self.server)
            transport = mtproto.BotTransport(12345, API_HASH, TOKEN, CHAT, 8000000001, -1002000000001,
                                             factory=lambda *_a, **_k: client)
            with self.subTest(confirm_switch=confirm_switch), self.assertRaises(mtproto.TransportError):
                transport.connect()
            self.assertEqual(client.starts, 3 if confirm_switch else 1)
            self.assertTrue(transport.close())
        class OtherFailure(base):
            def __init__(client, server):
                super().__init__(server)
                client.starts = 0
            async def start(client, *, bot_token):
                client.starts += 1
                raise errors.ApiIdInvalidError(request=None)
        client = OtherFailure(self.server)
        transport = mtproto.BotTransport(12345, API_HASH, TOKEN, CHAT, 8000000001, -1002000000001,
                                         factory=lambda *_a, **_k: client)
        with self.assertRaises(errors.ApiIdInvalidError):
            transport.connect()
        self.assertEqual(client.starts, 1)
        self.assertTrue(transport.close())

    def test_logout_revokes_the_one_session_without_logging_secrets(self):
        self.assertTrue(self.client.started_with_bot)
        self.assertTrue(self.transport.close())
        self.assertTrue(self.client.logged_out)
        for value in (TOKEN, API_HASH, CHAT, '12345'):
            self.assertNotIn(value, repr(self.transport))



if __name__ == "__main__":
    unittest.main(verbosity=2)
