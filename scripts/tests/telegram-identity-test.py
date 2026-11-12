#!/usr/bin/env python3
"""Offline exact-tag and immutable source-commit resolution tests."""
from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest
from types import SimpleNamespace

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS / "tests"))
from offline import sitecustomize as offline_guard  # noqa: F401,E402
sys.path.insert(0, str(SCRIPTS))
import telegram_identity as identity


class IdentityTests(unittest.TestCase):
    def test_only_exact_safe_tags_are_accepted(self):
        for tag in ("v0.1.16", "v0.1.17", "release/v1", "0.1.04", "v1+build"):
            self.assertEqual(identity.validate_tag(tag), tag)
        for tag in ("", "latest", "LATEST", " latest", "v1 ", "refs/tags/v1", "v1..v2", "a//b",
                    "a/", "a/.b", "v1.lock", "v1\nnext", "v1~1", "v1^{commit}", "--bad", None, 1):
            with self.subTest(tag=tag), self.assertRaises(identity.IdentityError):
                identity.validate_tag(tag)

    def test_lightweight_and_annotated_tags_resolve_exact_ref(self):
        calls = []
        def runner(command, **_kwargs):
            calls.append(command)
            endpoint = command[-1]
            if endpoint.endswith("git/ref/tags/release%2Fv1"):
                payload = {"ref": "refs/tags/release/v1", "object": {"type": "tag", "sha": "a" * 40}}
            elif endpoint.endswith("git/tags/" + "a" * 40):
                payload = {"sha": "a" * 40, "object": {"type": "commit", "sha": "b" * 40}}
            else:
                raise AssertionError("unexpected fake call")
            return SimpleNamespace(stdout=json.dumps(payload).encode(), stderr=b"secret test", returncode=0)
        self.assertEqual(identity.resolve_tag_commit("sascio/MyBridge", "release/v1", runner=runner), "b" * 40)
        self.assertEqual(len(calls), 2)
        self.assertTrue(all(call[:2] == ["gh", "api"] for call in calls))
        def lightweight(_command, **_kwargs):
            return SimpleNamespace(stdout=json.dumps({"ref": "refs/tags/v1", "object": {"type": "commit", "sha": "c" * 40}}).encode(),
                                   stderr=b"", returncode=0)
        self.assertEqual(identity.resolve_tag_commit("sascio/MyBridge", "v1", runner=lightweight), "c" * 40)

    def test_missing_mismatched_cyclic_or_non_commit_tags_fail_closed(self):
        for payload in ({}, {"ref": "refs/tags/v10", "object": {"type": "commit", "sha": "a" * 40}},
                        {"ref": "refs/tags/v1", "object": {"type": "tree", "sha": "a" * 40}},
                        {"ref": "refs/tags/v1", "object": {"type": "commit", "sha": "not-a-sha"}}):
            def runner(_command, **_kwargs):
                return SimpleNamespace(stdout=json.dumps(payload).encode(), stderr=b"", returncode=0)
            with self.subTest(payload=payload), self.assertRaises(identity.IdentityError):
                identity.resolve_tag_commit("sascio/MyBridge", "v1", runner=runner)
        def cyclic(command, **_kwargs):
            if "git/ref/tags/" in command[-1]:
                payload = {"ref": "refs/tags/v1", "object": {"type": "tag", "sha": "a" * 40}}
            else:
                payload = {"sha": "a" * 40, "object": {"type": "tag", "sha": "a" * 40}}
            return SimpleNamespace(stdout=json.dumps(payload).encode(), stderr=b"", returncode=0)
        with self.assertRaises(identity.IdentityError):
            identity.resolve_tag_commit("sascio/MyBridge", "v1", runner=cyclic)

    def test_failed_api_never_echoes_remote_error_or_credentials(self):
        def failed(_command, **_kwargs):
            return SimpleNamespace(stdout=b"", stderr=b"fake-secret-in-remote-error", returncode=1)
        with self.assertRaises(identity.IdentityError) as caught:
            identity.resolve_tag_commit("sascio/MyBridge", "v1", runner=failed)
        self.assertNotIn("fake-secret", str(caught.exception))


if __name__ == "__main__":
    unittest.main(verbosity=2)
