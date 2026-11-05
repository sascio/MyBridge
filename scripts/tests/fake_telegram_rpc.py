#!/usr/bin/env python3
"""Test double: exercise the *real* Telethon upload path offline.

The durable transport uses Telethon's real ``upload_file`` chunk loop and
constructs individual raw send requests with persisted IDs. This harness
intercepts the RPC boundary and stores file parts; tests exercise real request
objects and reconstructed bytes without a Telegram connection. It models
Telegram behavior; it is not proof of live server compatibility.
"""

from __future__ import annotations

import asyncio
import struct

from telethon import TelegramClient, utils
from telethon.sessions import StringSession
from telethon.tl import functions, types
from telethon.tl.types import Document, Message, MessageMediaDocument, PeerChannel, User

FAKE_BOT_ID = 8000000001
FAKE_CHAT_ID = -1002000000001
FAKE_CHAT_ACCESS_HASH = 123456789


class FakeRpcServer:
    """Answers the RPCs the real upload code emits and records what it saw."""

    def __init__(self) -> None:
        self.parts: dict[int, dict[int, bytes]] = {}
        self.part_counts: dict[int, int] = {}
        self.send_media: list[dict] = []
        self.last_upload_file_id: int | None = None
        self.uploads: list[dict] = []
        self.next_message_id = 900_000
        self.next_file_id = 0x5EED_0000
        self.fail_after_parts: int | None = None
        self.fail_send_media: Exception | None = None
        self.save_part_calls = 0

    # -- assertions -------------------------------------------------------- #

    def uploaded_bytes(self, file_id: int) -> bytes:
        parts = self.parts.get(file_id, {})
        return b"".join(parts[index] for index in sorted(parts))

    @property
    def last_document_name(self) -> str | None:
        return self.send_media[-1].get("filename") if self.send_media else None

    # -- dispatch ---------------------------------------------------------- #

    async def call(self, request: object) -> object:
        name = type(request).__name__
        if name in ("SaveFilePartRequest", "SaveBigFilePartRequest"):
            return self._save_part(request)
        if name in ("SendMediaRequest", "SendMultiMediaRequest"):
            return self._send_media(request)
        if name in ("UploadMediaRequest",):
            raise AssertionError("documents must not go through UploadMediaRequest")
        if name in ("GetUsersRequest",):
            return [self._bot_user()]
        if name in ("GetFullUserRequest",):
            return types.UserFull(self._bot_user())
        raise AssertionError(f"unexpected RPC during an upload: {name}")

    def _save_part(self, request: object) -> bool:
        self.save_part_calls += 1
        if self.fail_after_parts is not None and self.save_part_calls > self.fail_after_parts:
            # One-shot: the next attempt should succeed, like a recovered network.
            self.fail_after_parts = None
            raise ConnectionResetError("simulated network drop during upload")
        file_id = int(getattr(request, "file_id"))
        self.last_upload_file_id = file_id
        index = int(getattr(request, "file_part"))
        data = bytes(getattr(request, "bytes"))
        self.parts.setdefault(file_id, {})[index] = data
        count = getattr(request, "file_total_parts", None)
        if count is not None:
            self.part_counts[file_id] = int(count)
        return True

    def _send_media(self, request: object) -> object:
        if self.fail_send_media is not None:
            raise self.fail_send_media
        media = getattr(request, "media", None)
        caption = getattr(request, "message", "") or ""
        filename = None
        if isinstance(media, types.InputMediaUploadedDocument):
            document = self._document(media)
            for attribute in getattr(media, "attributes", []) or []:
                if isinstance(attribute, types.DocumentAttributeFilename):
                    filename = attribute.file_name
        else:
            document = self._document(media)
        self.send_media.append(
            {
                "caption": caption,
                "filename": filename,
                "media_type": type(media).__name__,
                "mime_type": getattr(media, "mime_type", None),
                "peer": type(getattr(request, "peer", None)).__name__,
            }
        )
        message_id = self.next_message_id
        self.next_message_id += 1
        message = Message(
            id=message_id,
            peer_id=PeerChannel(FAKE_CHAT_ID),
            date=None,
            message=caption,
            media=MessageMediaDocument(document=document),
        )
        # Telethon's send_file maps its response through UpdateMessageID (which
        # carries the random_id it generated) plus UpdateNewChannelMessage, so the
        # reply has to be a real `Updates` container.
        return types.Updates(
            updates=[
                types.UpdateMessageID(id=message_id, random_id=getattr(request, "random_id", 0)),
                types.UpdateNewChannelMessage(message=message, pts=1, pts_count=1),
            ],
            users=[],
            chats=[
                types.Channel(
                    id=FAKE_CHAT_ID,
                    title="StreamBridge",
                    photo=types.ChatPhotoEmpty(),
                    date=None,
                    creator=True,
                    left=False,
                    broadcast=True,
                    megagroup=False,
                    access_hash=FAKE_CHAT_ACCESS_HASH,
                )
            ],
            date=None,
            seq=1,
        )

    def _document(self, media: object) -> Document:
        self.next_file_id += 1
        file_id = self.next_file_id
        attributes = list(getattr(media, "attributes", []) or [])
        name = next(
            (a.file_name for a in attributes if isinstance(a, types.DocumentAttributeFilename)),
            f"file-{file_id}",
        )
        self.uploads.append({"file_id": file_id, "name": name})
        return Document(
            id=file_id,
            access_hash=file_id * 3,
            file_reference=b"\x01\x02\x03\x04",
            date=None,
            mime_type=getattr(media, "mime_type", "application/octet-stream"),
            size=sum(len(part) for part in self.parts.get(file_id, {}).values()) or None,
            dc_id=2,
            attributes=attributes,
        )

    @staticmethod
    def _bot_user() -> User:
        return User(
            id=FAKE_BOT_ID,
            access_hash=1,
            first_name="StreamBridge",
            bot=True,
        )


class FakeRpcClient(TelegramClient):
    """A real ``TelegramClient`` whose only stub is the network call.

    ``upload_file``, ``send_file``, the chunking loop, the TL request objects and
    the response parsing are all Telethon's own code.
    """

    def __init__(self, server: FakeRpcServer) -> None:  # noqa: D107
        super().__init__(StringSession(), 12345, "0123456789abcdef0123456789abcdef")
        self._server = server
        self.rpc_log: list[str] = []
        # A pre-shared authorisation is enough: nothing here speaks to a server,
        # so no key exchange or login code is involved.
        self.session.set_dc(2, "127.0.0.1", 443)
        self.session.auth_key = _FakeAuthKey()

    async def __call__(self, request, ordered=False, flood_sleep_threshold=None):  # noqa: D105
        self.rpc_log.append(type(request).__name__)
        return await self._server.call(request)

    async def connect(self):  # noqa: D105 - never dial a real server
        return None

    def is_connected(self):  # noqa: D105
        return True

    async def disconnect(self):  # noqa: D105
        return None

    async def get_input_entity(self, peer):  # noqa: D105
        if isinstance(peer, str) and peer.startswith("@"):
            return PeerChannel(FAKE_CHAT_ID)
        if isinstance(peer, int):
            return PeerChannel(peer)
        return PeerChannel(FAKE_CHAT_ID)

    async def _get_entity(self, peer):  # noqa: D105
        return PeerChannel(FAKE_CHAT_ID)

    async def get_me(self):  # noqa: D105
        return self._server._bot_user()


class _FakeAuthKey:
    """Placeholder key: satisfies the attribute, never used for encryption."""

    key = b"\x00" * 256

    def calc_new_nonce_hash(self, *args, **kwargs):  # noqa: D102, ANN002
        return 0


def make_client(server: FakeRpcServer) -> FakeRpcClient:
    return FakeRpcClient(server)


class AuthStub(TelegramClient):
    """A client that answers the bot-login RPCs and records them.

    Used to prove the authentication path: ``start(bot_token=...)`` must issue
    ``auth.importBotAuthorization`` and must never reach the phone-number,
    login-code or 2FA-password branches.
    """

    def __init__(self) -> None:  # noqa: D107
        super().__init__(StringSession(), 12345, "0123456789abcdef0123456789abcdef")
        self.seen: list[str] = []

    async def connect(self):  # noqa: D102
        return None

    def is_connected(self):  # noqa: D102
        return True

    async def disconnect(self):  # noqa: D102
        return None

    async def get_me(self):  # noqa: D102
        return None

    async def __call__(self, request, ordered=False, flood_sleep_threshold=None):  # noqa: D105
        import datetime

        from telethon.tl import functions, types

        self.seen.append(type(request).__name__)
        if isinstance(request, functions.auth.ImportBotAuthorizationRequest):
            return types.auth.Authorization(
                user=types.User(
                    id=FAKE_BOT_ID, access_hash=1, first_name="StreamBridge", bot=True
                ),
                setup_password_required=None,
            )
        return types.updates.State(
            pts=1,
            qts=1,
            date=datetime.datetime(2026, 11, 5, tzinfo=datetime.timezone.utc),
            seq=1,
            unread_count=0,
        )


__all__ = ["AuthStub", "FakeRpcClient", "FakeRpcServer", "make_client"]
