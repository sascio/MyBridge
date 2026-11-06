#!/usr/bin/env python3
"""Offline manual reconciliation tests; all GitHub/Telegram operations are fakes."""
from __future__ import annotations

import copy
from pathlib import Path
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS / "tests"))
from offline import sitecustomize as offline_guard  # noqa: F401,E402
sys.path.insert(0, str(SCRIPTS))
import telegram_ledger as ledger
import telegram_publish as publish
import telegram_reconcile as reconcile
import telegram_release_notes as notes


class MemoryStore:
    def __init__(self, document):
        self.document, self.version = copy.deepcopy(document), 1
    def read(self):
        ledger.validate(self.document)
        return ledger.Snapshot(str(self.version), copy.deepcopy(self.document), ledger.canonical(self.document))
    def save(self, old, new):
        if old.parent != str(self.version):
            raise ledger.LedgerConflict("simulated conflict")
        ledger.validate(new)
        ledger.validate_append_only(old.document, new)
        self.document = copy.deepcopy(new)
        self.version += 1
        return self.read()


class ReconciliationTests(unittest.TestCase):
    def setUp(self):
        release = notes.Release(id=123, tag_name="v9.9.9", name="test", body="test", html_url="https://example.invalid",
                                created_at="2026-01-01T00:00:00Z", published_at="2026-01-02T00:00:00Z")
        self.doc = ledger.migrate({"schema": 1, "releases": []}, "2020-01-01T00:00:00Z", {"release_ids": [], "tags": []})
        entry = publish.make_release(release, [{"key": "page:1", "kind": "page", "payload": "a" * 64}], set(), "c" * 40)
        item = entry["items"][0]
        item.update(state="sending", attempts=1, attempted_at="2026-01-03T00:00:00Z", workflow_run_id=500,
                    run_attempt=2, tooling_sha="b" * 40)
        entry["status"] = "sending"
        self.doc["identity"] = "d" * 64
        self.doc["releases"] = [entry]
        self.store = MemoryStore(self.doc)
        self.request = {"release_id": 123, "tag": "v9.9.9", "operation": "page:1", "message_id": 77,
                        "job_id": 600, "acknowledge": reconcile.ACKNOWLEDGEMENT}
        self.job = {"id": 600, "run_id": 500, "run_attempt": 2, "name": reconcile.JOB_NAME, "status": "completed"}
        self.run = {"id": 500, "status": "completed", "event": "release", "workflow_id": 700,
                    "repository": {"id": 1349123452}, "head_repository": {"id": 1349123452}}
        self.workflow = {"id": 700, "path": reconcile.WORKFLOW_PATH}
        self.marker = (f"2026-01-03T00:00:01Z TELEGRAM_CONFIRMED_RESPONSE run_id=500 run_attempt=2 "
                       f"release_id=123 operation=page:1 operation_id={item['random_id']} message_id=77 "
                       f"confirmed_at=2026-01-03T00:00:01Z tooling_sha={'b' * 40}\n").encode()

    def api(self, endpoint):
        if "/actions/jobs/" in endpoint:
            return self.job
        if "/actions/runs/" in endpoint:
            return self.run
        if "/actions/workflows/" in endpoint:
            return self.workflow
        raise AssertionError("unexpected fake endpoint")

    def perform(self, request=None, log=None):
        return reconcile.reconcile(self.store, request or self.request, repository="sascio/MyBridge", api=self.api,
                                   get_log=lambda *_: self.marker if log is None else log, policy_check=lambda: None)

    def test_failed_final_write_can_be_reconciled_only_from_exact_confirmed_response(self):
        saved = self.perform()
        item = saved.document["releases"][0]["items"][0]
        self.assertEqual((item["state"], item["message_id"], item["delivered_at"]), ("sent", 77, "2026-01-03T00:00:01Z"))
        self.assertEqual(item["reconciliation"], {"status": "confirmed_response", "source": "github_actions_log", "job_id": 600})
        self.assertEqual(saved.document["releases"][0]["status"], "sent")
        with self.assertRaises(reconcile.ReconciliationError):
            self.perform()  # already confirmed; cannot rewrite

    def test_confirmed_scheduled_poll_can_be_reconciled_without_a_resend(self):
        self.job["name"] = reconcile.POLL_JOB_NAME
        self.run["event"] = "schedule"
        saved = self.perform()
        self.assertEqual(saved.document["releases"][0]["items"][0]["state"], "sent")

    def test_scheduled_and_release_job_identities_cannot_be_interchanged(self):
        for name, event in ((reconcile.POLL_JOB_NAME, "release"), (reconcile.POLL_JOB_NAME, "push"),
                            (reconcile.JOB_NAME, "schedule")):
            self.job["name"], self.run["event"] = name, event
            with self.subTest(name=name, event=event), self.assertRaises(reconcile.ReconciliationError):
                self.perform()
        self.assertEqual(self.store.read().document, self.doc)

    def test_delivery_unknown_without_exact_marker_stays_blocked(self):
        item = self.store.document["releases"][0]["items"][0]
        item.update(state="delivery_unknown", reconciliation={"status": "review_required", "reason": "unconfirmed_telegram_response"})
        self.store.document["releases"][0]["status"] = "delivery_unknown"
        before = self.store.read()
        for log in (b"", self.marker.replace(b"message_id=77", b"message_id=78"), self.marker + self.marker,
                    self.marker.replace(b"tooling_sha=" + b"b" * 40, b"tooling_sha=" + b"c" * 40)):
            with self.subTest(log=log), self.assertRaises(reconcile.ReconciliationError):
                self.perform(log=log)
            self.assertEqual(self.store.read().raw, before.raw)
        self.perform()  # exact independently reviewed confirmation, not a resend
        self.assertEqual(self.store.read().document["releases"][0]["items"][0]["state"], "sent")

    def test_wrong_run_workflow_job_or_unrevoked_session_fails(self):
        for field, value in (("id", 601), ("run_id", 501), ("run_attempt", 1), ("name", "other"), ("status", "in_progress")):
            old = self.job[field]
            self.job[field] = value
            with self.subTest(field=field), self.assertRaises(reconcile.ReconciliationError):
                self.perform()
            self.job[field] = old
        self.workflow["path"] = ".github/workflows/telegram-release-announcement.yml"
        with self.assertRaises(reconcile.ReconciliationError):
            self.perform()
        self.workflow["path"] = reconcile.WORKFLOW_PATH
        self.store.document["session"] = {"owner": "run-unrevoked"}
        with self.assertRaises(reconcile.ReconciliationError):
            self.perform()
        self.assertEqual(self.store.document["releases"][0]["items"][0]["state"], "sending")

    def test_exact_id_tag_operation_and_review_ack_are_required(self):
        for field, value in (("release_id", 0), ("message_id", True), ("job_id", 0), ("tag", "latest"),
                             ("tag", ""), ("operation", "page:0"), ("acknowledge", "yes")):
            request = dict(self.request, **{field: value})
            with self.subTest(field=field), self.assertRaises(reconcile.ReconciliationError):
                self.perform(request=request)
        self.assertEqual(self.store.read().document, self.doc)

    def test_timestamps_cannot_be_invented_or_predate_attempt(self):
        bad = self.marker.replace(b"confirmed_at=2026-01-03T00:00:01Z", b"confirmed_at=2020-01-01T00:00:00Z")
        with self.assertRaises(reconcile.ReconciliationError):
            self.perform(log=bad)
        self.assertEqual(self.store.read().document, self.doc)


if __name__ == "__main__":
    unittest.main(verbosity=2)
