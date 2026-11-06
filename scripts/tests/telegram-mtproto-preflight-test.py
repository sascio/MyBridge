#!/usr/bin/env python3
"""Offline checks for the no-send bot MTProto readiness probe."""
from __future__ import annotations

import contextlib
import io
import os
from pathlib import Path
import sys
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS / "tests"))
from offline import sitecustomize as offline_guard  # noqa: E402,F401
sys.path.insert(0, str(SCRIPTS))
import telegram_mtproto_preflight as probe

TOKEN = "123456789:fake-secret-that-must-not-be-logged"
CHAT = "@StreamBridge_App"
API_HASH = "0123456789abcdef0123456789abcdef"


class FakeTransport:
    def __init__(self, *args):
        self.args = args
        self.calls = []
        self.connect_error = False
        self.revoked = True
        self.connection_stage = "channel-resolution"

    def connect(self):
        self.calls.append("connect")
        if self.connect_error:
            raise RuntimeError(TOKEN)

    def close(self):
        self.calls.append("logout")
        return self.revoked


class PreflightTests(unittest.TestCase):
    def setUp(self):
        self.transport = FakeTransport()
        self.lookup = mock.Mock(return_value=(987654, -100123456, CHAT))
        self.factory = mock.Mock(return_value=self.transport)

    def run_probe(self, **kwargs):
        probe.verify(TOKEN, CHAT, "12345", API_HASH, bot_lookup=self.lookup,
                     factory=self.factory, **kwargs)

    def test_success_only_connects_and_revokes_the_session(self):
        self.run_probe()
        self.assertEqual(self.transport.calls, ["connect", "logout"])
        self.lookup.assert_called_once_with(TOKEN, CHAT)
        self.factory.assert_called_once_with(12345, API_HASH, TOKEN, CHAT, 987654, -100123456)

    def test_failed_login_still_attempts_logout_and_does_not_expose_exception(self):
        self.transport.connect_error = True
        with self.assertRaises(probe.PreflightError) as caught:
            self.run_probe()
        self.assertEqual(self.transport.calls, ["connect", "logout"])
        self.assertNotIn(TOKEN, str(caught.exception))
        self.assertIn("channel-resolution (RuntimeError)", str(caught.exception))
        self.transport.connection_stage = TOKEN
        with self.assertRaises(probe.PreflightError) as caught:
            self.run_probe()
        self.assertIn("unknown (RuntimeError)", str(caught.exception))
        self.assertNotIn(TOKEN, str(caught.exception))

    def test_untrusted_or_credential_shaped_exception_name_is_never_reported(self):
        for name, module in ((TOKEN + "Error", "builtins"), ("InventedError", "untrusted.module")):
            exception = type(name, (Exception,), {"__module__": module})()
            self.transport.connect = mock.Mock(side_effect=exception)
            with self.assertRaises(probe.PreflightError) as caught:
                self.run_probe()
            self.assertIn("(other)", str(caught.exception))
            self.assertNotIn(name, str(caught.exception))

    def test_unconfirmed_logout_fails_even_after_successful_login(self):
        self.transport.revoked = False
        with self.assertRaisesRegex(probe.PreflightError, "logout was not confirmed"):
            self.run_probe()
        self.assertEqual(self.transport.calls, ["connect", "logout"])

    def test_wrong_channel_or_credentials_never_start_a_session(self):
        self.lookup.return_value = (987654, -100123456, "@other_channel")
        with self.assertRaises(probe.PreflightError):
            self.run_probe()
        self.factory.assert_not_called()
        for api_id, api_hash in (("0", API_HASH), ("abc", API_HASH), ("123", "short")):
            with self.subTest(api_id=api_id, api_hash=api_hash), self.assertRaises(probe.PreflightError):
                probe.verify(TOKEN, CHAT, api_id, api_hash, bot_lookup=self.lookup, factory=self.factory)
        self.factory.assert_not_called()

    def test_bot_api_requires_the_reviewed_channel_and_post_rights(self):
        def response(ok=True, **result):
            return type("Response", (), {"ok": ok, "result": result})()
        client = mock.Mock()
        client.call.side_effect = [response(id=1, is_bot=True),
                                   response(status="administrator", can_post_messages=True)]
        client.get_chat.return_value = response(id=-100123456, type="channel", username="OtherChannel")
        with mock.patch.object(probe.publish.announce, "TelegramClient", return_value=client):
            with self.assertRaisesRegex(probe.publish.PublishError, "reviewed"):
                probe.publish.bot_details(TOKEN, CHAT)
        client.call.side_effect = [response(id=1, is_bot=True),
                                   response(status="administrator", can_post_messages=False)]
        client.get_chat.return_value = response(id=-100123456, type="channel", username="StreamBridge_App")
        with mock.patch.object(probe.publish.announce, "TelegramClient", return_value=client):
            with self.assertRaisesRegex(probe.publish.PublishError, "Post Messages"):
                probe.publish.bot_details(TOKEN, CHAT)
        client.call.side_effect = [response(id=1, is_bot=True),
                                   response(status="administrator", can_post_messages=True)]
        with mock.patch.object(probe.publish.announce, "TelegramClient", return_value=client):
            self.assertEqual(probe.publish.bot_details(TOKEN, CHAT), (1, -100123456, CHAT))
        self.assertEqual([call.args[0] for call in client.call.call_args_list],
                         ["getMe", "getChatMember"] * 3)

    def test_cli_never_prints_credentials_or_exception(self):
        env = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
               "TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": API_HASH}
        out = io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), \
             mock.patch.object(probe, "verify", side_effect=RuntimeError(TOKEN)), \
             contextlib.redirect_stdout(out):
            self.assertEqual(probe.main(), 1)
        self.assertNotIn(TOKEN, out.getvalue())
        self.assertNotIn(API_HASH, out.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
