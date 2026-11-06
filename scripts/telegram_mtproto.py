#!/usr/bin/env python3
"""One in-memory bot MTProto client; raw individual sends with caller-supplied IDs.

This is a transport, not a deduplication store. Callers MUST durably persist the
ID and immutable payload before invoking a public send. Grouped sends, pins,
user logins and history scans are deliberately unsupported.
"""
from __future__ import annotations

import inspect
import os
from typing import Callable


class TransportError(RuntimeError):
    pass


class UnconfirmedResponse(TransportError):
    """The message might exist, but the returned evidence is insufficient."""


class BotTransport:
    def __init__(self, api_id: int, api_hash: str, token: str, channel_ref: str,
                 expected_bot_id: int, expected_chat_id: int, *, factory: Callable | None = None) -> None:
        if type(api_id) is not int or api_id <= 0 or len(api_hash) != 32 or not token or not channel_ref:
            raise TransportError("MTProto bot configuration is invalid.")
        if type(expected_bot_id) is not int or expected_bot_id <= 0 or type(expected_chat_id) is not int or expected_chat_id >= 0:
            raise TransportError("Bot or channel identity is invalid.")
        self._api_id, self._api_hash, self._token = api_id, api_hash, token
        self._channel_ref = channel_ref
        self._bot_id, self._chat_id = expected_bot_id, expected_chat_id
        self._factory = factory
        self._client = None
        self.peer = None
        self.login_attempted = False
        # A fixed, non-sensitive phase label supports no-send diagnostics; it
        # never contains credentials, peer data, request contents or errors.
        self.connection_stage = "not-started"

    def __repr__(self) -> str:
        return "BotTransport(credentials=<redacted>)"

    @staticmethod
    def _await(client: object, value: object) -> object:
        if not inspect.isawaitable(value):
            return value
        return client.loop.run_until_complete(value)  # type: ignore[attr-defined]

    def connect(self) -> None:
        from telethon import TelegramClient, utils
        from telethon.sessions import StringSession
        from telethon.tl import types

        factory = self._factory or TelegramClient
        # request_retries=0 disables high-level RPC retries. The caller decides
        # whether/how to replay the *same* persisted request ID. The short flood
        # threshold prevents hidden, unbounded sleeps inside Telethon.
        client = factory(StringSession(), self._api_id, self._api_hash, request_retries=0,
                         connection_retries=2, flood_sleep_threshold=0, timeout=30,
                         raise_last_call_error=True)
        self._client = client
        self.login_attempted = True  # set before network activity: failure may be ambiguous
        self.connection_stage = "bot-authentication"
        self._await(client, client.start(bot_token=self._token))
        self.connection_stage = "bot-identity"
        me = self._await(client, client.get_me())
        self.connection_stage = "channel-resolution"
        peer = self._await(client, client.get_input_entity(self._channel_ref))
        self.connection_stage = "identity-comparison"
        if getattr(me, "bot", False) is not True or getattr(me, "id", None) != self._bot_id:
            raise TransportError("MTProto identity does not match the Bot API bot.")
        if not isinstance(peer, types.InputPeerChannel) or utils.get_peer_id(peer) != self._chat_id:
            raise TransportError("MTProto peer does not match the Bot API channel.")
        self.peer = peer
        self.connection_stage = "ready"

    def close(self) -> bool:
        """Revoke this session; False means the durable session lock must remain."""
        client, self._client = self._client, None
        self.peer = None
        if client is None:
            return not self.login_attempted
        try:
            # log_out revokes the in-memory bot authorization rather than merely
            # disconnecting it. A lost logout response is deliberately unconfirmed.
            if self._await(client, client.log_out()) is True:
                return True
        except Exception:  # never log exception/authorization/session contents
            pass
        try:
            self._await(client, client.disconnect())
        except Exception:
            pass
        return False

    @staticmethod
    def parse_html(markup: str):
        from telethon.extensions import html
        return html.parse(markup)

    @staticmethod
    def keyboard(buttons: list[tuple[str, str]]):
        from telethon.tl import types
        if not buttons:
            return None
        return types.ReplyInlineMarkup(rows=[
            types.KeyboardInlineButtonRow(buttons=[
                types.KeyboardInlineButton(text=label, type=types.InlineButtonTypeUrl(url=url))
            ])
            for label, url in buttons
        ])

    @staticmethod
    def mime_type(filename: str) -> str:
        # Deterministic across runner OS/Python MIME databases and therefore
        # stable across retries/reruns of a frozen operation.
        return "application/vnd.android.package-archive" if filename.lower().endswith(".apk") else "application/octet-stream"

    def text_request(self, markup: str, buttons: list[tuple[str, str]], random_id: int):
        from telethon.tl import functions
        if self.peer is None or type(random_id) is not int or not random_id:
            raise TransportError("A bound peer and preallocated random_id are required.")
        text, entities = self.parse_html(markup)
        return functions.messages.SendMessageRequest(peer=self.peer, message=text, no_webpage=True,
                                                       entities=entities, reply_markup=self.keyboard(buttons),
                                                       random_id=random_id)

    def prepare_document(self, path: str, filename: str):
        from telethon.tl import types
        if self._client is None or self.peer is None:
            raise TransportError("MTProto bot is not connected.")
        handle = self._await(self._client, self._client.upload_file(
            path, part_size_kb=512, file_size=os.path.getsize(path), file_name=filename))
        return types.InputMediaUploadedDocument(file=handle, mime_type=self.mime_type(filename), force_file=True,
                                                attributes=[types.DocumentAttributeFilename(file_name=filename)])

    def media_request(self, media: object, caption: str, random_id: int):
        from telethon.tl import functions
        if self.peer is None or type(random_id) is not int or not random_id:
            raise TransportError("A bound peer and preallocated random_id are required.")
        text, entities = self.parse_html(caption)
        return functions.messages.SendMediaRequest(peer=self.peer, media=media, message=text,
                                                    entities=entities, random_id=random_id)

    def send(self, request: object, *, filename: str | None = None, size: int | None = None) -> int:
        """Send one raw request once; return only a payload-matched message ID."""
        from telethon import utils
        from telethon.tl import functions, types
        if self._client is None or self.peer is None or not isinstance(
            request, (functions.messages.SendMessageRequest, functions.messages.SendMediaRequest)
        ):
            raise TransportError("Only individual raw text/media sends are supported.")
        result = self._await(self._client, self._client(request))
        updates = getattr(result, "updates", [])
        if isinstance(result, types.UpdateShort):
            updates = [result.update]
        mapping = [u.id for u in updates if isinstance(u, types.UpdateMessageID) and u.random_id == request.random_id]
        messages = [u.message for u in updates if isinstance(u, (types.UpdateNewChannelMessage, types.UpdateNewMessage))]
        if isinstance(result, types.UpdateShortSentMessage):
            mapping = [result.id]
        if len(mapping) != 1 or type(mapping[0]) is not int or mapping[0] <= 0:
            raise UnconfirmedResponse("Telegram did not return a unique matching random_id mapping.")
        message_id = mapping[0]
        matching = [m for m in messages if getattr(m, "id", None) == message_id]
        if not matching:
            # Bot-supported, read-only lookup by a *known* ID, not history search.
            channel = types.InputChannel(channel_id=self.peer.channel_id, access_hash=self.peer.access_hash)
            fetched = self._await(self._client, self._client(functions.channels.GetMessagesRequest(
                channel=channel, id=[types.InputMessageID(id=message_id)])))
            matching = [m for m in getattr(fetched, "messages", []) if getattr(m, "id", None) == message_id]
        if len(matching) != 1:
            raise UnconfirmedResponse("Telegram's returned message could not be checked.")
        message = matching[0]
        if utils.get_peer_id(message.peer_id) != self._chat_id or (message.message or "") != request.message:
            raise UnconfirmedResponse("Telegram's returned message does not match the frozen peer/text.")
        # Formatting and buttons matter: do not confirm a different rendered post.
        expected_entities = [x.to_dict() for x in (request.entities or [])]
        actual_entities = [x.to_dict() for x in (getattr(message, "entities", None) or [])]
        if expected_entities != actual_entities:
            raise UnconfirmedResponse("Telegram's returned message entities differ from the frozen payload.")
        expected_markup = getattr(request, "reply_markup", None)
        actual_markup = getattr(message, "reply_markup", None)
        if (expected_markup.to_dict() if expected_markup else None) != (actual_markup.to_dict() if actual_markup else None):
            raise UnconfirmedResponse("Telegram's returned buttons differ from the frozen payload.")
        if isinstance(request, functions.messages.SendMediaRequest):
            document = getattr(getattr(message, "media", None), "document", None)
            names = [a.file_name for a in getattr(document, "attributes", [])
                     if isinstance(a, types.DocumentAttributeFilename)]
            if document is None or names != [filename] or getattr(document, "size", None) != size:
                raise UnconfirmedResponse("Telegram's returned document metadata does not match the frozen payload.")
        elif filename is not None:
            raise TransportError("Document metadata supplied for a text operation.")
        return message_id
