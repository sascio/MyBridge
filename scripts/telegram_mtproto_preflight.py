#!/usr/bin/env python3
"""Read-only bot/channel preflight; MTProto login is fenced by automatic bootstrap.

The standalone CLI intentionally creates no MTProto authorization. The actual
no-send login/logout probe runs under a durable state lock in telegram_auto.py.
"""
from __future__ import annotations

import os
import re
from typing import Callable

import telegram_publish as publish


class PreflightError(RuntimeError):
    pass


def verify_read_only(token: str, chat: str, api_id: str, api_hash: str, *,
                     bot_lookup: Callable | None = None) -> None:
    if not api_id.isdigit() or int(api_id) <= 0 or not re.fullmatch(r"[0-9a-fA-F]{32}", api_hash):
        raise PreflightError("MTProto credentials are invalid by syntax.")
    (bot_lookup or publish.bot_details)(token, chat)


def main() -> int:
    try:
        verify_read_only(*(publish.require_secret(name) for name in (
            "TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "TELEGRAM_API_ID", "TELEGRAM_API_HASH")))
    except Exception:
        # Never print network/Telegram exceptions, which may contain secrets.
        print("::error::Telegram bot/channel readiness or MTProto credential syntax could not be verified.")
        return 1
    print("Bot API identity, target channel and Post Messages rights verified; MTProto credential syntax is valid.")
    print("MTProto login/logout was not tested here. Automatic bootstrap tests it under a durable session lock.")
    print("No message, upload, channel-history read, release mutation or ledger write occurred.")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write("\n**Read-only:** bot/channel rights and MTProto credential syntax verified. "
                         "The locked bootstrap separately verifies MTProto login/logout.\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
