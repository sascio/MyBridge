#!/usr/bin/env python3
"""Verify the actual bot MTProto login without sending or uploading anything.

A fresh in-memory authorization is always logged out. If login or logout cannot
be confirmed, fail closed and require investigation before activation. This
check does not inspect channel history or prove send/recovery semantics.
"""
from __future__ import annotations

import os
import re
from typing import Callable

import telegram_mtproto as mtproto
import telegram_publish as publish


class PreflightError(RuntimeError):
    pass


def verify(token: str, chat: str, api_id: str, api_hash: str, *,
           bot_lookup: Callable = publish.bot_details, factory: Callable = mtproto.BotTransport) -> None:
    if not api_id.isdigit() or int(api_id) <= 0 or not re.fullmatch(r"[0-9a-fA-F]{32}", api_hash):
        raise PreflightError("MTProto credentials are invalid.")
    bot_id, channel_id, channel_ref = bot_lookup(token, chat)
    if channel_ref.casefold() != "@streambridge_app":
        raise PreflightError("The configured channel is not the reviewed destination.")
    transport = factory(int(api_id), api_hash, token, channel_ref, bot_id, channel_id)
    connected = False
    try:
        transport.connect()
        connected = True
    except Exception:
        # Third-party exceptions can contain request or authorization data.
        pass
    finally:
        # log_out revokes this in-memory authorization, not the bot token.
        # Do not report success if revocation is ambiguous.
        revoked = transport.close()
    if not revoked:
        raise PreflightError("MTProto session logout was not confirmed; investigate before activation.")
    if not connected:
        raise PreflightError("MTProto bot authentication or channel resolution failed.")


def main() -> int:
    try:
        verify(*(publish.require_secret(name) for name in (
            "TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "TELEGRAM_API_ID", "TELEGRAM_API_HASH")))
    except PreflightError as exc:
        print(f"::error::{exc}")
        return 1
    except Exception:
        # Never print network/Telegram exceptions, which may contain secrets.
        print("::error::Telegram bot or channel readiness could not be verified.")
        return 1
    print("Bot API identity, target channel, administrator Post Messages rights, MTProto bot login and session logout verified.")
    print("No message, upload, channel-history read, release mutation or ledger write occurred.")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write("\n**MTProto:** bot login, matching channel and session logout verified; no message or upload was sent.\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
