"""Offline doubles for the literal read-only preflight workflow shell.

Only Bot API getMe/getChat/getChatMember are permitted. Sockets, ledger access,
MTProto clients and all mutating operations fail if reached.
"""
from __future__ import annotations

import builtins
import json
import os
import socket
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import telegram_assets as assets  # noqa: E402
import telegram_mtproto as mtproto  # noqa: E402

TRACE = os.environ["TELEGRAM_PREFLIGHT_TRACE"]
_real_open = builtins.open


def record(event: str, **details: object) -> None:
    with _real_open(TRACE, "a", encoding="utf-8") as handle:
        handle.write(json.dumps({"event": event, **details}) + "\n")


def protected_open(file, mode="r", *args, **kwargs):
    if isinstance(file, (str, bytes, os.PathLike)) and os.path.basename(file) == "telegram-releases.json":
        record("ledger_access", mode=mode)
        raise AssertionError("preflight must not read or write the post ledger")
    return _real_open(file, mode, *args, **kwargs)


class Response:
    def __init__(self, result: dict) -> None:
        self.body = json.dumps(result).encode("utf-8")

    def read(self) -> bytes:
        return self.body

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


def fake_urlopen(request, timeout=None):  # noqa: ARG001 - urllib-compatible
    method = request.full_url.rsplit("/", 1)[-1]
    payload = json.loads(request.data.decode("utf-8")) if request.data else {}
    token = request.full_url.split("/bot", 1)[-1].split("/", 1)[0]
    record(
        "bot_api",
        method=method,
        token_matches=(token == os.environ.get("TELEGRAM_BOT_TOKEN")),
        chat_matches=("chat_id" not in payload or payload["chat_id"] == os.environ.get("TELEGRAM_CHAT_ID")),
        user_id=payload.get("user_id"),
    )
    if method not in {"getChat", "getMe", "getChatMember"}:
        raise AssertionError(f"forbidden Bot API method: {method}")
    if method == "getChat":
        if os.environ.get("FAKE_CHAT_MODE") == "unreachable":
            return Response({"ok": False, "error_code": 403, "description": "bot cannot access channel"})
        chat_type = "supergroup" if os.environ.get("FAKE_CHAT_MODE") == "wrong_type" else "channel"
        # Real getChat returns chat details, not the bot's status or post rights.
        return Response({"ok": True, "result": {"id": -100123456, "type": chat_type, "title": "Test channel"}})
    if method == "getMe":
        mode = os.environ.get("FAKE_BOT_MODE")
        if mode == "invalid":
            return Response({"ok": False, "error_code": 401, "description": "unauthorized"})
        bot = {"id": 987654, "is_bot": True, "first_name": "Test"}
        if mode == "not_bot":
            bot["is_bot"] = False
        if mode == "invalid_id":
            bot["id"] = True  # bool is an int subclass, but not a valid Telegram ID
        return Response({"ok": True, "result": bot})
    if os.environ.get("FAKE_MEMBER_MODE") == "unreachable":
        return Response({"ok": False, "error_code": 403, "description": "member inaccessible"})
    member = {
        "status": os.environ.get("FAKE_MEMBER_STATUS", "administrator"),
        "can_post_messages": os.environ.get("FAKE_CAN_POST", "true") == "true",
    }
    if os.environ.get("FAKE_MEMBER_MODE") == "missing_post_right":
        del member["can_post_messages"]
    return Response({"ok": True, "result": member})


class GuardedUploader:
    def __init__(self, *_args, **_kwargs):
        record("forbidden", method="MTProto session")
        raise AssertionError("preflight must not create an MTProto session")


class NoNetworkSocket(socket.socket):
    def connect(self, address):
        record("forbidden", method="socket.connect")
        raise AssertionError("preflight test must not reach a real network")

    def connect_ex(self, address):
        record("forbidden", method="socket.connect_ex")
        raise AssertionError("preflight test must not reach a real network")


def install() -> None:
    builtins.open = protected_open
    urllib.request.urlopen = fake_urlopen
    socket.socket = NoNetworkSocket
    def no_dns(*_args, **_kwargs):
        record("forbidden", method="socket.getaddrinfo")
        raise AssertionError("preflight must not use real DNS")
    socket.getaddrinfo = no_dns
    assets.MtprotoUploader = GuardedUploader
    mtproto.BotTransport = GuardedUploader
