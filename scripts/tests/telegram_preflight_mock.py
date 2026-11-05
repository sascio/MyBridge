"""Offline doubles for executing the actual preflight workflow run block.

Loaded via a temporary sitecustomize.py in a subprocess. Bot API methods are
allowlisted; the real MtprotoUploader.find_marker drives an in-memory client
whose send/upload operations fail if touched. No real network is reachable.
"""
from __future__ import annotations

import asyncio
import builtins
import json
import os
import socket
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import telegram_assets as assets  # noqa: E402

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


class FakeMtprotoClient:
    def __init__(self, *_args, **_kwargs) -> None:
        self.loop = asyncio.new_event_loop()
        record("mtproto_client")

    def start(self, *, bot_token):
        record("mtproto_login", token_matches=(bot_token == os.environ.get("TELEGRAM_BOT_TOKEN")))
        if os.environ.get("FAKE_MTPROTO_MODE") == "auth_failure":
            self.loop.close()
            raise RuntimeError("MTProto bot authentication failed")
        return self

    async def get_entity(self, chat):
        record("mtproto_get_entity", chat_matches=(chat == os.environ.get("TELEGRAM_CHAT_ID")))
        return chat

    async def iter_messages(self, _entity, limit):
        record("mtproto_history", limit=limit)
        if os.environ.get("FAKE_MTPROTO_MODE") == "history_failure":
            raise RuntimeError("channel history is not accessible")
        if False:  # make this an empty asynchronous generator
            yield None

    def disconnect(self):
        record("mtproto_disconnect")
        self.loop.close()

    def upload_file(self, *_args, **_kwargs):
        record("forbidden", method="upload_file")
        raise AssertionError("preflight must never upload")

    def send_file(self, *_args, **_kwargs):
        record("forbidden", method="send_file")
        raise AssertionError("preflight must never send media")

    def __call__(self, request, *_args, **_kwargs):
        record("forbidden", method=type(request).__name__)
        raise AssertionError("unexpected MTProto request")


class GuardedUploader(assets.MtprotoUploader):
    def __init__(self, api_id, api_hash, bot_token, chat_id, **kwargs) -> None:
        record(
            "mtproto_uploader",
            api_id_matches=(str(api_id) == os.environ.get("TELEGRAM_API_ID")),
            api_hash_matches=(api_hash == os.environ.get("TELEGRAM_API_HASH")),
            token_matches=(bot_token == os.environ.get("TELEGRAM_BOT_TOKEN")),
            chat_matches=(chat_id == os.environ.get("TELEGRAM_CHAT_ID")),
        )
        super().__init__(api_id, api_hash, bot_token, chat_id, client_factory=FakeMtprotoClient, **kwargs)

    def upload_document(self, *_args, **_kwargs):
        record("forbidden", method="upload_document")
        raise AssertionError("preflight must never upload a document")


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
    assets.MtprotoUploader = GuardedUploader
