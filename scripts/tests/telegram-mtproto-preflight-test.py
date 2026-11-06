#!/usr/bin/env python3
"""The standalone Telegram preflight is read-only and opens no MTProto session."""
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


class PreflightTests(unittest.TestCase):
    def test_success_only_checks_bot_api_and_credential_syntax(self):
        lookup = mock.Mock(return_value=(987654, -100123456, CHAT))
        with mock.patch.object(probe.publish.mtproto, "BotTransport", side_effect=AssertionError("must not log in")):
            probe.verify_read_only(TOKEN, CHAT, "12345", API_HASH, bot_lookup=lookup)
        lookup.assert_called_once_with(TOKEN, CHAT)

    def test_bad_mtproto_syntax_fails_before_bot_api(self):
        lookup = mock.Mock()
        for api_id, api_hash in (("0", API_HASH), ("abc", API_HASH), ("123", "short")):
            with self.subTest(api_id=api_id, api_hash=api_hash), self.assertRaises(probe.PreflightError):
                probe.verify_read_only(TOKEN, CHAT, api_id, api_hash, bot_lookup=lookup)
        lookup.assert_not_called()

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
        self.assertEqual([call.args[0] for call in client.call.call_args_list], ["getMe", "getChatMember"] * 3)

    def test_cli_never_prints_credentials_or_raw_exception(self):
        env = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
               "TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": API_HASH}
        out = io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), \
             mock.patch.object(probe, "verify_read_only", side_effect=RuntimeError(TOKEN)), \
             contextlib.redirect_stdout(out):
            self.assertEqual(probe.main(), 1)
        self.assertNotIn(TOKEN, out.getvalue())
        self.assertNotIn(API_HASH, out.getvalue())

    def test_cli_reports_login_was_not_tested(self):
        env = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
               "TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": API_HASH}
        out = io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), \
             mock.patch.object(probe.publish, "bot_details", return_value=(1, -100123456, CHAT)), \
             mock.patch.object(probe.publish.mtproto, "BotTransport", side_effect=AssertionError("must not log in")), \
             contextlib.redirect_stdout(out):
            self.assertEqual(probe.main(), 0)
        self.assertIn("MTProto login/logout was not tested", out.getvalue())
        self.assertNotIn(TOKEN, out.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
