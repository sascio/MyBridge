#!/usr/bin/env python3
"""Offline tests for the StreamBridge Telegram release announcement.

Everything here runs without network access and without Telegram credentials.
The Telegram Bot API is replaced by a scripted mock, so the idempotency,
retry and failure paths are exercised for real while nothing can reach the
public channel. The repository's own published release notes are used as
fixtures, which makes the tests check the real formatting rules against the real
notes rather than against invented samples.

Run: scripts/tests/telegram-announcement-test.py
"""

from __future__ import annotations

import ast
import contextlib
import http.server
import io
import itertools
import json
import os
import re
import sys
import subprocess
import tempfile
import threading
import unittest
import urllib.error
from unittest import mock as unittest_mock

try:
    import yaml
except ImportError:  # PyYAML is present on GitHub's runners but not everywhere
    yaml = None
import urllib.request
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS / "tests"))
from offline import sitecustomize as offline_guard  # noqa: E402,F401 - block external network in direct invocations
sys.path.insert(0, str(SCRIPTS))

import telegram_announce as announce  # noqa: E402
import telegram_assets as assets  # noqa: E402
import telegram_ledger as ledger  # noqa: E402
import telegram_release_notes as notes  # noqa: E402

try:
    import telethon  # noqa: F401  - optional: only the upload tests need it
except ImportError:  # pragma: no cover
    telethon = None

TOKEN = "123456:AAH-offline-test-token-not-a-real-secret"
CHAT = "@StreamBridge_App"

RELEASE_URL = "https://github.com/sascio/MyBridge/releases/tag/v9.9.9"


def setUpModule() -> None:
    # A developer may have real credentials in their shell. Existing send and
    # preflight tests must never inherit those and attempt real MTProto calls.
    names = ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "TELEGRAM_API_ID", "TELEGRAM_API_HASH")
    saved = {name: os.environ.pop(name) for name in names if name in os.environ}

    def restore() -> None:
        for name in names:
            os.environ.pop(name, None)
        os.environ.update(saved)

    unittest.addModuleCleanup(restore)


def release_payload(**overrides) -> dict:
    """A realistic release object shaped exactly like the GitHub REST API's."""
    payload = {
        "id": 987654321,
        "tag_name": "v9.9.9",
        "name": "StreamBridge v9.9.9",
        "draft": False,
        "prerelease": False,
        "published_at": "2026-12-01T10:00:00Z",
        "html_url": RELEASE_URL,
        "body": (
            "## StreamBridge 9.9.9\n\n"
            "StreamBridge version 9.9.9 (version code 999). Android package\n"
            "`com.streambridge.app`, iOS bundle `com.streambridge.app`.\n\n"
            "## Appearance\n\n"
            "- **Glass UI** — frosted headers with a *balanced* preset and `vivid` presets.\n"
            "  The menu now uses <angle> brackets and an ampersand & a quote \"quoted\".\n\n"
            "## Player and playback\n\n"
            "- Skip intro keeps its timer.\n\n"
            "## Subtitles and audio\n\n"
            "- ASS subtitles render through libass.\n\n"
            "## Continue watching\n\n"
            "- Mark watched from the long-press menu.\n\n"
            "## Live TV\n\n"
            "- The header pins.\n\n"
            "## iOS\n\n"
            "- Now Playing publishes on real changes.\n\n"
            "## Translations\n\n"
            "Arabic and Vietnamese locales were completed.\n\n"
            "## StreamBridge specifics\n\n"
            "- About Us regained its icon.\n\n"
            "## Download sizes\n\n"
            "| Asset | 9.9.8 | 9.9.9 |\n|---|---:|---:|\n"
            "| `androidApp-full-arm64-v8a-release.apk` | 50.00 MiB | 52.45 MiB |\n\n"
            "## Installation\n\n"
            "The iOS build is **unsigned**; install it with\n"
            "[AltStore](https://altstore.io). See `docs/RELEASE-IOS.md` for details.\n"
        ),
        "assets": [
            _asset("androidApp-full-arm64-v8a-release.apk", 55002830),
            _asset("androidApp-full-armeabi-v7a-release.apk", 53119693),
            _asset("androidApp-full-x86_64-release.apk", 56502237),
            _asset("androidApp-full-x86-release.apk", 55447497),
            _asset("checksums.sha256", 577),
            _asset("StreamBridge-9.9.9-iOS-unsigned.ipa", 73626917),
        ],
    }
    payload.update(overrides)
    return payload


def _asset(name: str, size: int, state: str = "uploaded") -> dict:
    return {
        "name": name,
        "size": size,
        "state": state,
        "digest": "sha256:" + "0" * 64,
        "browser_download_url": f"https://github.com/sascio/MyBridge/releases/download/v9.9.9/{name}",
    }


# --------------------------------------------------------------------------- #
# Mock Telegram Bot API
# --------------------------------------------------------------------------- #


class MockTelegram:
    """A scripted stand-in for the Telegram Bot API.

    ``sendMessage`` is answered with a fresh message id on every call.

    ``fail`` maps a method name to a list of responses consumed one per call, so
    a test can reproduce a 429, a 500, a permanent 400 or a network drop and then
    let the retry succeed. ``pinned`` models the channel's pinned message.
    """

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []
        self.fail: dict[str, list[object]] = {}
        self.pinned: dict | None = None
        self.next_message_id = 1000
        self.token_seen: list[str] = []
        # 1.2 s in production; the tests replace it with a no-op recorder.
        self.delay_seconds = 0.0
        self.sleeper = lambda _seconds: None

    def urlopen(self, request, timeout=None):  # noqa: ARG002 - mirrors urlopen
        url = request.full_url
        method = url.rsplit("/", 1)[-1]
        payload = json.loads(request.data.decode("utf-8")) if request.data else {}
        self.calls.append((method, payload))
        token = url.split("/bot", 1)[1].split("/", 1)[0]
        self.token_seen.append(token)

        scripted = self.fail.get(method)
        if scripted:
            outcome = scripted.pop(0)
            if isinstance(outcome, Exception):
                raise outcome
            if isinstance(outcome, _HttpError):
                raise outcome.to_error()
            if outcome is not None:
                return _Response(outcome)
            # ``None`` means "skip the scripted failure once, handle normally".

        if method == "sendMessage":
            message_id = self.next_message_id
            self.next_message_id += 1
            if self.delay_seconds:
                self.sleeper(self.delay_seconds)
            return _Response(
                {
                    "ok": True,
                    "result": {
                        "message_id": message_id,
                        "text": payload.get("text", ""),
                        "reply_markup": payload.get("reply_markup"),
                    },
                }
            )
        if method == "getChat":
            result = {"id": -1001234567890, "type": "channel", "title": "StreamBridge"}
            if self.pinned is not None:
                result["pinned_message"] = self.pinned
            return _Response({"ok": True, "result": result})
        if method == "pinChatMessage":
            message_id = payload.get("message_id")
            for method_name, sent in self.calls:
                if method_name == "sendMessage" and isinstance(sent, dict):
                    pass
            self.pinned = {"message_id": message_id, "text": "#StreamBridge #SB987654321"}
            return _Response({"ok": True, "result": True})
        return _Response({"ok": False, "error_code": 404, "description": f"unknown method {method}"})

    def sent_messages(self) -> list[dict]:
        return [payload for method, payload in self.calls if method == "sendMessage"]

    def methods(self) -> list[str]:
        return [method for method, _ in self.calls]


class _Response:
    def __init__(self, payload: dict) -> None:
        self._body = json.dumps(payload).encode("utf-8")

    def read(self) -> bytes:
        return self._body

    def __enter__(self) -> "_Response":
        return self

    def __exit__(self, *args) -> bool:
        return False


class _HttpError:
    """Builds the urllib HTTPError Telegram really produces for an API error."""

    def __init__(self, code: int, body: dict) -> None:
        self.code = code
        self.body = body

    def to_error(self) -> urllib.error.HTTPError:
        return urllib.error.HTTPError(
            url="https://api.telegram.org/botTOKEN/sendMessage",
            code=self.code,
            msg="error",
            hdrs=None,
            fp=io.BytesIO(json.dumps(self.body).encode("utf-8")),
        )


def expect_announcement_error(test: unittest.TestCase, action) -> announce.AnnouncementError:
    """Run ``action`` and return the AnnouncementError it must raise.

    ``unittest.TestCase.assertRaises`` is not used: recent Pythons call the
    callable twice inside the context manager, which would deliver a page twice
    in these tests and hide the behaviour they are checking.
    """
    try:
        action()
    except announce.AnnouncementError as exc:
        return exc
    test.fail("expected an AnnouncementError, none was raised")
    raise AssertionError("unreachable")


def patch_client(test: unittest.TestCase, mock: MockTelegram) -> None:
    """Route ``announce.TelegramClient(...)`` to the mock for one test.

    Restoring in ``addCleanup`` keeps the patch local to the test even when it
    fails; patching the module attribute is the only way to intercept the client
    ``main()`` builds from the environment.
    """
    original = announce.TelegramClient

    def factory(token, chat, **kwargs):
        return original(
            token,
            chat,
            sleeper=lambda _seconds: None,
            opener=mock.urlopen,
            **kwargs,
        )

    announce.TelegramClient = factory  # type: ignore[assignment]
    test.addCleanup(setattr, announce, "TelegramClient", original)


def client(mock: MockTelegram, retries: int = 3) -> announce.TelegramClient:
    return announce.TelegramClient(
        TOKEN,
        CHAT,
        retries=retries,
        sleeper=lambda _seconds: None,
        opener=mock.urlopen,
    )


# --------------------------------------------------------------------------- #
# Formatting
# --------------------------------------------------------------------------- #


class RenderingTests(unittest.TestCase):
    def setUp(self) -> None:
        self.release = notes.Release.from_api(release_payload())

    def test_error_messages_never_contain_the_token(self) -> None:
        secret = "123456789:AAHverysecrettokenvaluewithlotsofcharacters"
        self.assertEqual(announce.redact(f"bad token {secret}"), "bad token <redacted-bot-token>")

    def test_redaction_covers_configured_secrets_and_stripped_values(self) -> None:
        env = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": "@private-test-channel",
               "TELEGRAM_API_ID": "73124689", "TELEGRAM_API_HASH": " 0123456789abcdef0123456789abcdef "}
        with unittest_mock.patch.dict(os.environ, env):
            message = " ".join(value.strip() for value in env.values())
            result = announce.redact(message)
        for value in env.values():
            self.assertNotIn(value.strip(), result)
        self.assertEqual(result.count("<redacted>"), 4)

    def test_branding_constants(self) -> None:
        self.assertEqual(notes.PRODUCT_NAME, "StreamBridge")
        self.assertEqual(notes.MOTTO, "Your Media, Your Way")
        self.assertEqual(notes.FOOTER, "Made with ❤️ by Saif")
        self.assertEqual(notes.DOWNLOAD_BUTTON_LABEL, "⬇️ Download StreamBridge")

    def test_branding_header_motto_and_footer(self) -> None:
        announcement = notes.build_announcement(self.release)
        text = announcement.pages[0].text
        self.assertIn("🚀 <b>STREAMBRIDGE 9.9.9 IS HERE!</b>", text)
        self.assertIn("❤️ <i>Your Media, Your Way.</i>", text)
        self.assertIn("<i>Made with ❤️ by Saif</i>", text)

    def test_every_section_from_the_notes_is_rendered_in_order(self) -> None:
        announcement = notes.build_announcement(self.release)
        self.assertEqual(
            announcement.sections,
            [
                "appearance",
                "player",
                "subtitles",
                "progress",
                "live_tv",
                "ios",
                "translations",
                "streambridge",
                "downloads",
                "install",
            ],
        )
        text = " ".join(page.text for page in announcement.pages)
        for title in (
            "🎨 Appearance &amp; Glass UI",
            "🎬 Player &amp; Playback",
            "💬 Subtitles &amp; Audio",
            "⏯ Continue Watching",
            "📡 Live TV",
            "🍏 iOS Improvements",
            "🌍 Translations",
            "💙 StreamBridge Improvements",
            "📦 Downloads",
            "📲 Installation Notes",
        ):
            self.assertIn(title, text)

    def test_absent_sections_are_not_invented(self) -> None:
        release = notes.Release.from_api(
            release_payload(body="## What's new\n\n- One change only.\n")
        )
        announcement = notes.build_announcement(release)
        self.assertEqual(announcement.sections, ["whats_new", "downloads"])
        text = " ".join(page.text for page in announcement.pages)
        self.assertNotIn("Live TV", text)
        self.assertNotIn("Translations", text)

    def test_markdown_is_converted_and_never_dumped(self) -> None:
        announcement = notes.build_announcement(self.release)
        text = " ".join(page.text for page in announcement.pages)
        self.assertIn("<b>Glass UI</b>", text)
        self.assertIn("<i>balanced</i>", text)
        self.assertIn("<code>vivid</code>", text)
        for leftover in ("**", "##", "|---", "](http", "`"):
            self.assertNotIn(leftover, text)

    def test_html_escaping(self) -> None:
        release = notes.Release.from_api(
            release_payload(
                body=(
                    "## What's new\n\n"
                    "- Angle <script>alert('x')</script> and an ampersand & a quote \"q\".\n"
                )
            )
        )
        announcement = notes.build_announcement(release)
        text = announcement.pages[0].text
        self.assertEqual(announcement.html_problems, [])
        self.assertIn("&lt;script&gt;", text)
        self.assertIn("&amp;", text)
        self.assertNotIn("<script>", text)
        # Telegram needs no &quot; escapes; quotes stay literal and readable.
        self.assertIn('"q"', text)

    def test_markdown_links_become_anchors(self) -> None:
        release = notes.Release.from_api(
            release_payload(body="## Installation\n\nInstall with [AltStore](https://altstore.io).\n")
        )
        text = notes.build_announcement(release).pages[0].text
        self.assertIn('<a href="https://altstore.io">AltStore</a>', text)

    def test_internal_paths_and_upstream_branding_are_not_published(self) -> None:
        release = notes.Release.from_api(
            release_payload(
                body=(
                    "## What's new\n\n"
                    "- Upstream 0.6.3-beta is integrated; NuvioMedia and NuvioMobile 0.6.3 too.\n"
                    "- See `docs/RELEASE-IOS.md` and `scripts/release-api.sh`.\n"
                )
            )
        )
        announcement = notes.build_announcement(release)
        text = " ".join(page.text for page in announcement.pages)
        self.assertNotIn("Nuvio", text)
        self.assertNotIn("Upstream", text)
        self.assertNotIn("docs/", text)
        self.assertNotIn("scripts/", text)
        self.assertIn("StreamBridge iOS release guide", text)
        self.assertGreater(announcement.summary["upstream_tokens_scrubbed"], 0)


class AssetTests(unittest.TestCase):
    def test_android_and_ios_assets_are_detected(self) -> None:
        summary = notes.classify_assets(notes.Release.from_api(release_payload()).assets)
        self.assertEqual(
            [asset.abi for asset in summary.android],
            ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"],
        )
        self.assertEqual(summary.ios.name, "StreamBridge-9.9.9-iOS-unsigned.ipa")
        self.assertEqual(summary.checksums.name, "checksums.sha256")
        self.assertEqual(summary.missing, ())
        self.assertEqual(summary.android_primary.abi, "arm64-v8a")

    def test_missing_ios_ipa_is_reported_not_invented(self) -> None:
        payload = release_payload()
        payload["assets"] = [asset for asset in payload["assets"] if not asset["name"].endswith(".ipa")]
        announcement = notes.build_announcement(notes.Release.from_api(payload))
        self.assertIsNone(announcement.summary["ios_asset"])
        self.assertIn("iOS IPA", announcement.summary["missing_assets"])
        text = " ".join(page.text for page in announcement.pages)
        self.assertIn("no iOS IPA is attached to this release", text)
        self.assertNotIn(".ipa", " ".join(label for label, _ in announcement.keyboard))

    def test_unfinished_asset_upload_is_treated_as_absent(self) -> None:
        payload = release_payload()
        for asset in payload["assets"]:
            if asset["name"].endswith(".ipa"):
                asset["state"] = "starter"
        announcement = notes.build_announcement(notes.Release.from_api(payload))
        self.assertIsNone(announcement.summary["ios_asset"])
        self.assertNotIn("🍏 iOS IPA", [label for label, _ in announcement.keyboard])

    def test_no_android_apk_is_reported(self) -> None:
        payload = release_payload()
        payload["assets"] = [asset for asset in payload["assets"] if not asset["name"].endswith(".apk")]
        announcement = notes.build_announcement(notes.Release.from_api(payload))
        self.assertEqual(announcement.summary["android_assets"], [])
        text = " ".join(page.text for page in announcement.pages)
        self.assertIn("no Android APK is attached to this release", text)

    def test_button_urls_are_exactly_the_published_urls(self) -> None:
        release = notes.Release.from_api(release_payload())
        announcement = notes.build_announcement(release)
        buttons = dict(announcement.keyboard)
        self.assertEqual(buttons[notes.DOWNLOAD_BUTTON_LABEL], RELEASE_URL)
        published = {asset["name"]: asset["browser_download_url"] for asset in release_payload()["assets"]}
        # Every published binary gets its own exact link; none is guessed.
        self.assertEqual(buttons["🤖 Android ARM64"], published["androidApp-full-arm64-v8a-release.apk"])
        self.assertEqual(buttons["🤖 Android ARMv7"], published["androidApp-full-armeabi-v7a-release.apk"])
        self.assertEqual(buttons["🤖 Android x86"], published["androidApp-full-x86-release.apk"])
        self.assertEqual(buttons["🤖 Android x86_64"], published["androidApp-full-x86_64-release.apk"])
        self.assertEqual(buttons["🍎 iOS IPA"], published["StreamBridge-9.9.9-iOS-unsigned.ipa"])
        self.assertEqual(buttons["🔐 Checksums"], published["checksums.sha256"])
        self.assertEqual(set(buttons.values()), {RELEASE_URL, *published.values()})
        for label, url in announcement.keyboard:
            self.assertTrue(url.startswith("https://github.com/sascio/MyBridge/releases/"), url)

    def test_no_asset_url_is_constructed_for_a_missing_asset(self) -> None:
        payload = release_payload()
        payload["assets"] = []
        announcement = notes.build_announcement(notes.Release.from_api(payload))
        self.assertEqual([label for label, _ in announcement.keyboard], [notes.DOWNLOAD_BUTTON_LABEL])
        text = " ".join(page.text for page in announcement.pages)
        self.assertNotIn("/releases/download/", " ".join(page.text for page in announcement.pages))
        self.assertNotIn("MiB", text.replace("size unknown", ""))

    def test_version_code_is_only_reported_when_verifiable(self) -> None:
        with_notes = notes.Release.from_api(release_payload())
        self.assertEqual(notes.version_code(with_notes), ("999", "release notes"))
        derived = notes.Release.from_api(
            release_payload(tag_name="v1.2.3", body="## What's new\n\n- nothing numeric\n")
        )
        self.assertEqual(notes.version_code(derived), ("10203", "derived from the tag (repository convention)"))
        unknown = notes.Release.from_api(release_payload(tag_name="nightly", body="no numbers"))
        self.assertEqual(notes.version_code(unknown), (None, "unavailable"))

    def test_human_size(self) -> None:
        self.assertEqual(notes.human_size(55002830), "52.45 MiB")
        self.assertEqual(notes.human_size(0), "size unknown")
        self.assertEqual(notes.human_size(2048), "2.0 KiB")
        self.assertEqual(notes.human_size(512), "512 B")


class SplittingTests(unittest.TestCase):
    LONG_BODY = (
        "## What's new\n\n"
        + "\n".join(
            f"- **Feature {index}** — " + ("a detailed explanation of the change. " * 12)
            for index in range(40)
        )
        + "\n\n## Installation\n\n"
        + ("Install it with AltStore. " * 60)
        + "\n"
    )

    def test_long_notes_split_without_truncation(self) -> None:
        release = notes.Release.from_api(release_payload(body=self.LONG_BODY))
        announcement = notes.build_announcement(release)
        self.assertGreater(len(announcement.pages), 1)
        for page in announcement.pages:
            self.assertLessEqual(page.length, notes.TELEGRAM_TEXT_LIMIT)
            self.assertEqual(notes.validate_html(page.text), [])

        combined = " ".join(page.text for page in announcement.pages)
        for index in range(40):
            self.assertIn(f"Feature {index}", combined)
        self.assertEqual(combined.count("Install it with AltStore."), 60)

    def test_every_page_is_valid_html_on_its_own(self) -> None:
        release = notes.Release.from_api(
            release_payload(
                body="## What's new\n\n- **" + ("bold words " * 900) + "**\n"
            )
        )
        announcement = notes.build_announcement(release)
        self.assertGreater(len(announcement.pages), 1)
        for page in announcement.pages:
            self.assertEqual(notes.validate_html(page.text), [], page.text[-200:])

    def test_pages_keep_order_and_numbering(self) -> None:
        release = notes.Release.from_api(release_payload(body=self.LONG_BODY))
        announcement = notes.build_announcement(release)
        total = len(announcement.pages)
        self.assertIn("STREAMBRIDGE 9.9.9 IS HERE!", announcement.pages[0].text)
        for number, page in enumerate(announcement.pages[1:], start=2):
            self.assertIn(f"(part {number}/{total})", page.text)

    def test_download_button_is_on_every_page(self) -> None:
        release = notes.Release.from_api(release_payload(body=self.LONG_BODY))
        announcement = notes.build_announcement(release)
        self.assertEqual(
            announcement.pages[0].buttons[0][0], notes.DOWNLOAD_BUTTON_LABEL
        )
        for page in announcement.pages:
            self.assertEqual(page.buttons[0], (notes.DOWNLOAD_BUTTON_LABEL, RELEASE_URL))

    def test_split_html_rebuilds_tags(self) -> None:
        markup = "• <b>bold</b> and <i>italic</i> text " * 40
        pieces = notes.split_html(markup, 200)
        self.assertGreater(len(pieces), 1)
        for piece in pieces:
            self.assertLessEqual(notes.telegram_length(piece), 200)
            self.assertEqual(notes.validate_html(piece), [], piece)
        self.assertEqual(" ".join(notes.visible_text(p) for p in pieces), " ".join(notes.visible_text(markup).split()))

    def test_emoji_count_as_two_characters(self) -> None:
        self.assertEqual(notes.telegram_length("❤️"), 2)
        self.assertEqual(notes.telegram_length("<b>❤️</b>"), 2)
        self.assertEqual(notes.telegram_length("a &amp; b"), 5)


# --------------------------------------------------------------------------- #
# Delivery, idempotency, failure handling
# --------------------------------------------------------------------------- #


class DeliveryTests(unittest.TestCase):
    def setUp(self) -> None:
        # A notes body long enough to need two pages, so the pagination and the
        # per-page button behaviour are exercised by the delivery tests too.
        body = release_payload()["body"] + "".join(
            f"\n- Extra change {index}: " + ("explained in a full sentence. " * 6)
            for index in range(30)
        )
        self.release = notes.Release.from_api(release_payload(body=body))
        self.announcement, self.pages = announce.build_pages(self.release)
        self.assertGreater(len(self.pages), 1, "fixture must exercise multiple pages")

    def test_dry_run_sends_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            release_file = Path(directory, "release.json")
            release_file.write_text(json.dumps(release_payload()), encoding="utf-8")
            summary = Path(directory, "summary.json")
            buffer = io.StringIO()
            stdout, sys.stdout = sys.stdout, buffer
            try:
                code = announce.main(
                    [
                        "--release-json",
                        str(release_file),
                        "--dry-run",
                        "--summary",
                        str(summary),
                    ]
                )
            finally:
                sys.stdout = stdout
            self.assertEqual(code, announce.EXIT_OK)
            data = json.loads(summary.read_text(encoding="utf-8"))
            self.assertTrue(data["dry_run"])
            self.assertEqual(data["sent_message_ids"], [])
            self.assertIn("DRY RUN", buffer.getvalue())
            self.assertIn("STREAMBRIDGE 9.9.9 IS HERE!", buffer.getvalue())

    def test_dry_run_never_needs_credentials(self) -> None:
        for variable in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID"):
            self.assertNotIn(variable, os.environ)

    def test_marker_is_on_every_page_and_matches_the_release_id(self) -> None:
        for text in self.pages:
            self.assertIn("#SB987654321", text)
        self.assertEqual(announce.marker_for(987654321), "#SB987654321")

    def test_marker_is_found_in_button_markup_and_caption_too(self) -> None:
        self.assertEqual(
            announce.marker_in_message({"reply_markup": {"inline_keyboard": [[{"url": "https://x/#SB123"}]]}}),
            "#SB123",
        )
        self.assertEqual(announce.marker_in_message({"caption": "text #SB55 here"}), "#SB55")
        self.assertIsNone(announce.marker_in_message({"text": "no marker"}))

    def test_bot_client_repr_does_not_contain_secrets(self) -> None:
        telegram = announce.TelegramClient(TOKEN, CHAT)
        self.assertNotIn(TOKEN, repr(telegram))
        self.assertNotIn(CHAT, repr(telegram))

    def test_bot_errors_do_not_echo_secrets_or_sleep_for_long_flood_waits(self) -> None:
        def failing_opener(*_args, **_kwargs):
            raise OSError(TOKEN + CHAT)
        result = announce.TelegramClient(TOKEN, CHAT, retries=1, opener=failing_opener,
                                         sleeper=lambda _delay: None).get_chat()
        self.assertNotIn(TOKEN, result.description + repr(result))
        self.assertNotIn(CHAT, result.description + repr(result))
        raw = json.dumps({"ok": False, "error_code": TOKEN, "description": CHAT}).encode()
        malformed = announce.TelegramClient._parse(raw, attempts=1)
        self.assertIsNone(malformed.error_code)
        self.assertNotIn(TOKEN, repr(malformed) + malformed.description)
        self.assertNotIn(CHAT, repr(malformed) + malformed.description)
        bot = MockTelegram()
        bot.fail["getChat"] = [{"ok": False, "error_code": 429,
                                "parameters": {"retry_after": 600}}]
        result = client(bot).get_chat()
        self.assertFalse(result.ok)
        self.assertEqual(bot.methods(), ["getChat"])

    def test_draft_and_prerelease_events_are_ignored(self) -> None:
        for overrides, label in (
            ({"draft": True}, "draft"),
            ({"prerelease": True}, "pre-release"),
        ):
            with tempfile.TemporaryDirectory() as directory:
                release_file = Path(directory, "release.json")
                release_file.write_text(json.dumps(release_payload(**overrides)), encoding="utf-8")
                os.environ["TELEGRAM_BOT_TOKEN"] = TOKEN
                os.environ["TELEGRAM_CHAT_ID"] = CHAT
                self.addCleanup(os.environ.pop, "TELEGRAM_BOT_TOKEN", None)
                self.addCleanup(os.environ.pop, "TELEGRAM_CHAT_ID", None)
                mock = MockTelegram()
                patch_client(self, mock)
                buffer = io.StringIO()
                stdout, sys.stdout = sys.stdout, buffer
                try:
                    code = announce.main(["--release-json", str(release_file)])
                finally:
                    sys.stdout = stdout
                self.assertEqual(code, announce.EXIT_OK)
                self.assertEqual(mock.calls, [])
                self.assertIn(f"is a {label}", buffer.getvalue())

    def test_bot_api_mutations_and_legacy_direct_delivery_are_disabled(self) -> None:
        bot = MockTelegram()
        connection = client(bot)
        for method in ('sendMessage', 'sendDocument', 'pinChatMessage'):
            result = connection.call(method, {'chat_id': CHAT})
            self.assertFalse(result.ok)
        self.assertEqual(bot.calls, [])
        with tempfile.TemporaryDirectory() as directory:
            release_file = Path(directory, 'release.json')
            release_file.write_text(json.dumps(release_payload()), encoding='utf-8')
            self.assertEqual(announce.main(['--release-json', str(release_file)]), announce.EXIT_CONFIG)
            self.assertEqual(assets.main(['--release-json', str(release_file)]), assets.EXIT_CONFIG)

    def test_asset_only_legacy_entry_does_not_count_as_announced(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, 'ledger.json')
            path.write_text(json.dumps({'schema': 2, 'releases': [
                {'release_id': self.release.id, 'announced': False, 'assets': [{'message_id': 99}]}]}))
            self.assertFalse(announce.ledger_records(str(path), self.release.id))


# --------------------------------------------------------------------------- #
# Workflow wiring
# --------------------------------------------------------------------------- #

class WorkflowTests(unittest.TestCase):
    WORKFLOW = SCRIPTS.parent / ".github" / "workflows" / "telegram-release-delivery.yml"

    def setUp(self) -> None:
        self.text = self.WORKFLOW.read_text(encoding="utf-8")

    def test_workflow_exists_and_triggers_on_published_releases(self) -> None:
        self.assertTrue(self.WORKFLOW.is_file())
        self.assertIn("release:", self.text)
        self.assertRegex(self.text, r"release:\s*\n\s*types:\s*\[\s*published\s*\]")

    def test_legacy_workflow_is_a_distinct_retired_identity_without_sends(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        old = SCRIPTS.parent / '.github/workflows/telegram-release-announcement.yml'
        self.assertNotEqual(old, self.WORKFLOW)
        text = old.read_text(encoding='utf-8')
        workflow = yaml.safe_load(text)
        self.assertEqual(set(workflow.get('on', workflow.get(True))), {'workflow_dispatch'})
        self.assertEqual(workflow['permissions'], {})
        steps = workflow['jobs']['retired']['steps']
        self.assertEqual(len(steps), 1)
        self.assertIn('exit 1', steps[0]['run'])
        for forbidden in ('secrets.', 'api.telegram.org', 'telegram_publish.py',
                          'telegram_announce.py', 'telegram_assets.py', 'actions/checkout'):
            self.assertNotIn(forbidden, text)

    def test_workflow_has_least_privilege_permissions(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        workflow = yaml.safe_load(self.text)
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        self.assertEqual(workflow["jobs"]["preflight"]["permissions"], {"contents": "read"})
        self.assertEqual(workflow["jobs"]["announce"]["permissions"], {"contents": "read"})
        self.assertEqual(workflow["jobs"]["publish"]["permissions"], {"contents": "write"})
        for job in workflow["jobs"].values():
            self.assertLessEqual(set(job.get("permissions", {})), {"contents"})

    def test_workflow_never_prints_or_persists_credentials(self) -> None:
        for risky in ("echo ${{ secrets.TELEGRAM_BOT_TOKEN }}", "token: ${{ secrets.TELEGRAM_BOT_TOKEN }}"):
            self.assertNotIn(risky, self.text)
        self.assertNotIn("upload-artifact", self.text)
        # The token is only ever passed through the step environment.
        self.assertIn("TELEGRAM_BOT_TOKEN: ${{ secrets.TELEGRAM_BOT_TOKEN }}", self.text)

    def test_workflow_guards_against_non_official_events(self) -> None:
        self.assertIn("github.event.release.draft == false", self.text)
        self.assertIn("github.event.release.prerelease == false", self.text)

    def test_workflow_uses_the_tested_scripts(self) -> None:
        self.assertIn("scripts/telegram_announce.py", self.text)
        self.assertIn('gh api "repos/${GITHUB_REPOSITORY}/releases/${EVENT_RELEASE_ID}"', self.text)
        self.assertIn("--dry-run", self.text)

    def test_one_durable_delivery_step_sends_pages_before_assets(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        workflow = yaml.safe_load(self.text)
        self.assertEqual(workflow["jobs"]["publish"]["needs"], "announce")
        step = next(x for x in workflow["jobs"]["publish"]["steps"] if x.get("name") == "Publish individual messages and documents")
        self.assertIn("scripts/telegram_publish.py", step["run"])
        self.assertNotIn("continue-on-error", step)

    def test_binary_downloads_are_outside_the_checkout(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        workflow = yaml.safe_load(self.text)
        step = next(x for x in workflow["jobs"]["publish"]["steps"] if x.get("name") == "Publish individual messages and documents")
        self.assertEqual(step["env"]["TMPDIR"], "${{ runner.temp }}")
        self.assertNotIn("upload-artifact", self.text)

    def test_telegram_secrets_are_scoped_to_the_steps_that_use_them(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        workflow = yaml.safe_load(self.text)
        telegram = {"TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "TELEGRAM_API_ID", "TELEGRAM_API_HASH"}
        expected = {"Verify Telegram readiness (read-only)": telegram,
                    "Verify MTProto bot login without sending": telegram,
                    "Publish individual messages and documents": telegram}
        self.assertFalse(telegram & set(workflow.get("env", {})))
        for job in workflow["jobs"].values():
            self.assertFalse(telegram & set(job.get("env", {})))
            for step in job["steps"]:
                present = telegram & set(step.get("env", {}))
                self.assertEqual(present, expected.get(step.get("name"), set()), step.get("name"))
                for name in present:
                    self.assertEqual(step["env"][name], "${{ secrets." + name + " }}")

    def test_every_run_block_is_valid_bash(self) -> None:
        """The workflow's shells must parse before GitHub ever runs them.

        A broken `case` arm or an unclosed quote in a `run:` block would only
        surface during a real release; this catches it in CI and locally.
        """
        if yaml is None:
            self.skipTest("PyYAML is not installed, so the YAML cannot be parsed here")
        workflow = yaml.safe_load(self.text)
        checked = 0
        for job in workflow["jobs"].values():
            for step in job["steps"]:
                if "run" not in step:
                    continue
                result = subprocess.run(["bash", "-n"], input=step["run"], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, f"{step['name']}: {result.stderr}")
                checked += 1
        self.assertGreaterEqual(checked, 6)

    def test_download_buttons_and_ledger_flow_are_documented_together(self) -> None:
        doc = (SCRIPTS.parent / "docs" / "TELEGRAM-ANNOUNCEMENTS.md").read_text(encoding="utf-8")
        self.assertIn("⬇️ Download StreamBridge", doc)
        self.assertIn(ledger.LEGACY_PATH, doc)
        self.assertIn(ledger.LEDGER_PATH, doc)
        self.assertIn(f"show FETCH_HEAD:{ledger.LEDGER_PATH}", doc)
        self.assertIn("release id", doc)

    def test_docs_state_the_default_branch_requirement(self) -> None:
        # A release-event workflow that only exists on a side branch never runs;
        # the docs must say so, because that is the one setup step the code
        # cannot perform for itself.
        doc = " ".join(
            (SCRIPTS.parent / "docs" / "TELEGRAM-ANNOUNCEMENTS.md").read_text(encoding="utf-8").split()
        )
        self.assertIn("default branch", doc)
        self.assertIn("not backfilled", doc)

    def test_ledger_starts_empty_and_parses(self) -> None:
        ledger = json.loads(
            (SCRIPTS.parent / ".github" / "telegram-releases.json").read_text(encoding="utf-8")
        )
        self.assertEqual(ledger["schema"], 1)
        self.assertEqual(ledger["releases"], [])

    def test_documentation_exists_and_lists_the_secrets(self) -> None:
        doc = (SCRIPTS.parent / "docs" / "TELEGRAM-ANNOUNCEMENTS.md").read_text(encoding="utf-8")
        flat = " ".join(doc.split())
        for secret in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID"):
            self.assertIn(secret, doc)
        self.assertIn("@StreamBridge_App", doc)
        self.assertIn("Your Media, Your Way", flat)
        self.assertIn("Made with ❤️ by Saif", flat)

    def test_workflow_does_not_touch_the_release_or_other_workflows(self) -> None:
        for forbidden in ("gh release edit", "gh release create", "gh release delete", "gh release upload"):
            self.assertNotIn(forbidden, self.text)
        for other in ("release-draft.yml", "ios-release.yml", "build.yml", "update-store-source.yml"):
            self.assertNotIn(f"gh workflow run {other}", self.text)


# --------------------------------------------------------------------------- #
# Binary delivery: download, verify, upload
# --------------------------------------------------------------------------- #


def binary_payload(size: int = 4096) -> bytes:
    return bytes((index * 37) % 251 for index in range(size))


class _ServedFiles:
    """A tiny HTTP server, so downloads run the real urllib code.

    Returns raw bytes from a dict, and can be told to truncate or corrupt a
    response so the verification path is exercised rather than mocked.
    """

    def __init__(self, files: dict[str, bytes]) -> None:
        import http.server
        import socketserver
        import threading

        self.files = dict(files)
        self.truncate: dict[str, int] = {}
        self.requests: list[str] = []
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_args):  # noqa: D102 - silence the test server
                pass

            def do_GET(self):  # noqa: N802
                name = self.path.lstrip("/")
                outer.requests.append(name)
                body = outer.files.get(name, b"")
                self.send_response(200 if name in outer.files else 404)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                cut = outer.truncate.get(name)
                self.wfile.write(body if cut is None else body[:cut])

        self._server = socketserver.TCPServer(("127.0.0.1", 0), Handler)
        self.port = self._server.server_address[1]
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def url(self, name: str) -> str:
        return f"http://127.0.0.1:{self.port}/{name}"

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()


class DownloadTests(unittest.TestCase):
    def setUp(self) -> None:
        import hashlib
        import tempfile

        self.temp = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.temp, ignore_errors=True))
        self.body = binary_payload(200_000)
        self.digest = hashlib.sha256(self.body).hexdigest()
        self.served = _ServedFiles(
            {
                "app.apk": self.body,
                "short.apk": self.body,
                "checksums.sha256": f"{self.digest}  app.apk\n".encode(),
            }
        )
        self.addCleanup(self.served.close)

    def _asset(self, name="app.apk", size=200_000, digest="") -> notes.Asset:
        return notes.Asset(name=name, size=size, url=self.served.url(name), digest=digest)

    def test_downloads_and_verifies_the_published_bytes(self) -> None:
        path, digest = assets.download_asset(self._asset(), f"{self.temp}/app.apk")
        with open(path, "rb") as handle:
            self.assertEqual(handle.read(), self.body)
        self.assertEqual(digest, self.digest)

    def test_checksum_file_is_parsed_both_ways(self) -> None:
        parsed = assets.parse_checksums(
            f"{self.digest}  app.apk\nSHA256 (other.apk) = {self.digest}\n"
        )
        self.assertEqual(parsed["app.apk"], self.digest)
        self.assertEqual(parsed["other.apk"], self.digest)

    def test_checksum_file_entries_win_over_nothing_else(self) -> None:
        asset = self._asset(digest=f"sha256:{self.digest}")
        self.assertEqual(assets.expected_digest(asset, {}), self.digest)
        self.assertIsNone(assets.expected_digest(self._asset(), {}))

    def test_truncated_response_is_rejected_and_leaves_no_file(self) -> None:
        self.served.truncate["app.apk"] = 1000
        with self.assertRaises(assets.AssetError) as caught:
            assets.download_asset(self._asset(), f"{self.temp}/app.apk", attempts=1, sleeper=lambda _s: None)
        self.assertIn("incomplete download", str(caught.exception))
        self.assertFalse(os.path.exists(f"{self.temp}/app.apk"))
        self.assertFalse(os.path.exists(f"{self.temp}/app.apk.part"))

    def test_wrong_digest_is_rejected(self) -> None:
        with self.assertRaises(assets.AssetError) as caught:
            assets.download_asset(
                self._asset(), f"{self.temp}/app.apk", checksums={"app.apk": "0" * 64}, attempts=1,
                sleeper=lambda _s: None,
            )
        self.assertIn("digest mismatch", str(caught.exception))

    def test_a_missing_url_is_refused_before_any_request(self) -> None:
        asset = notes.Asset(name="app.apk", size=10, url="")
        with self.assertRaises(assets.AssetError):
            assets.download_asset(asset, f"{self.temp}/app.apk")
        self.assertEqual(self.served.requests, [])

    def test_a_zero_size_asset_is_refused(self) -> None:
        asset = notes.Asset(name="app.apk", size=0, url=self.served.url("app.apk"))
        with self.assertRaises(assets.AssetError) as caught:
            assets.download_asset(asset, f"{self.temp}/app.apk")
        self.assertIn("no size", str(caught.exception))

    def test_an_asset_over_the_mtproto_ceiling_is_refused(self) -> None:
        asset = notes.Asset(
            name="huge.apk", size=assets.UPLOAD_MAX_BYTES + 1, url=self.served.url("app.apk")
        )
        with self.assertRaises(assets.AssetError) as caught:
            assets.download_asset(asset, f"{self.temp}/huge.apk")
        self.assertIn("MTProto ceiling", str(caught.exception))

    def test_a_server_error_is_retried_then_reported(self) -> None:
        asset = notes.Asset(name="gone.apk", size=10, url=self.served.url("gone.apk"))
        delays: list[float] = []
        with self.assertRaises(assets.AssetError):
            assets.download_asset(asset, f"{self.temp}/gone.apk", sleeper=delays.append)
        self.assertEqual(len(delays), assets.DOWNLOAD_ATTEMPTS - 1)

    def test_the_announcement_uses_the_verified_asset_metadata(self) -> None:
        # The caption must carry the exact URL and size from the API object.
        payload = release_payload()
        release = notes.Release.from_api(payload)
        plans, _skipped = assets.plan_uploads(release)
        self.assertTrue(plans)
        for plan in plans:
            self.assertIn(plan.asset.url, plan.caption)
            self.assertIn(notes.human_size(plan.asset.size), plan.caption)
            self.assertIn(release.version, plan.caption)


class PlanUploadTests(unittest.TestCase):
    def test_every_published_abi_and_the_ipa_are_planned_in_order(self) -> None:
        release = notes.Release.from_api(release_payload())
        plans, skipped = assets.plan_uploads(release)
        kinds = [plan.kind for plan in plans]
        self.assertEqual(kinds.count("android"), 4)
        self.assertEqual(kinds.count("ios"), 1)
        self.assertEqual(
            [plan.asset.abi for plan in plans if plan.kind == "android"],
            ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"],
        )
        # The checksum file is reported, never uploaded as a binary.
        self.assertTrue(any("checksums" in reason for reason in skipped))

    def test_the_ios_filename_is_detected_dynamically(self) -> None:
        payload = release_payload()
        for asset in payload["assets"]:
            if asset["name"].endswith(".ipa"):
                asset["name"] = "StreamBridge-7.4.2-iOS-unsigned.ipa"
        release = notes.Release.from_api(payload)
        plans, _ = assets.plan_uploads(release)
        ios = [plan for plan in plans if plan.kind == "ios"]
        self.assertEqual(len(ios), 1)
        self.assertEqual(ios[0].filename, "StreamBridge-7.4.2-iOS-unsigned.ipa")

    def test_an_unfinished_asset_is_never_planned(self) -> None:
        payload = release_payload()
        for asset in payload["assets"]:
            if asset["name"].endswith(".ipa"):
                asset["state"] = "starter"
        release = notes.Release.from_api(payload)
        plans, skipped = assets.plan_uploads(release)
        self.assertFalse([plan for plan in plans if plan.kind == "ios"])
        self.assertTrue(any("not published yet" in reason for reason in skipped))

    def test_android_labels_are_human_readable(self) -> None:
        release = notes.Release.from_api(release_payload())
        plans, _ = assets.plan_uploads(release)
        by_abi = {plan.asset.abi: plan.caption for plan in plans}
        self.assertIn("ARM64-v8a", by_abi["arm64-v8a"])
        self.assertIn("ARMv7", by_abi["armeabi-v7a"])

    def test_captions_stay_inside_the_telegram_limit(self) -> None:
        release = notes.Release.from_api(release_payload())
        plans, _ = assets.plan_uploads(release)
        for plan in plans:
            self.assertLessEqual(notes.telegram_length(plan.caption), assets.CAPTION_LIMIT)
            self.assertEqual(notes.validate_html(plan.caption), [])

    def test_a_release_without_binaries_plans_nothing(self) -> None:
        payload = release_payload()
        payload["assets"] = [asset for asset in payload["assets"] if not asset["name"].endswith((".apk", ".ipa"))]
        release = notes.Release.from_api(payload)
        plans, _ = assets.plan_uploads(release)
        self.assertEqual(plans, [])


class LedgerStateTests(unittest.TestCase):
    def setUp(self) -> None:
        import tempfile

        self.dir = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))
        self.path = f"{self.dir}/ledger.json"
        self.release = notes.Release.from_api(release_payload())
        self.plans, _ = assets.plan_uploads(self.release)

    def test_a_corrupt_or_missing_legacy_ledger_fails_closed(self) -> None:
        with self.assertRaises(assets.AssetError):
            assets.recorded_uploads(self.path, self.release.id)
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write("{not json")
        with self.assertRaises(assets.AssetError):
            assets.recorded_uploads(self.path, self.release.id)
        for schema in (3, 4, 99, True):
            Path(self.path).write_text(json.dumps({"schema": schema, "releases": []}))
            with self.subTest(schema=schema), self.assertRaises(assets.AssetError):
                assets.recorded_uploads(self.path, self.release.id)


assets_512k = 512 * 1024


# --------------------------------------------------------------------------- #
# Regressions found in the integration audit
# --------------------------------------------------------------------------- #


class PreflightTests(unittest.TestCase):
    """The read-only check must never post, upload or pin."""

    def test_direct_preflight_ignores_release_dry_run_summary_ledger_and_pin(self) -> None:
        with tempfile.TemporaryDirectory() as directory, unittest_mock.patch.dict(os.environ, {
            "TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
        }), unittest_mock.patch.object(announce, "run_preflight", return_value=announce.EXIT_OK) as check, \
                unittest_mock.patch.object(announce, "load_release", side_effect=AssertionError("must not read release")):
            summary = str(Path(directory, "summary.json"))
            ledger = str(Path(directory, "ledger.json"))
            code = announce.main(["--preflight", "--dry-run", "--pin", "--release-json", "ignored.json",
                                  "--summary", summary, "--ledger", ledger])
            self.assertEqual(code, announce.EXIT_OK)
            check.assert_called_once_with(None, CHAT, TOKEN)
            self.assertFalse(Path(summary).exists())
            self.assertFalse(Path(ledger).exists())

    def test_preflight_issues_only_read_calls(self) -> None:
        release = notes.Release.from_api(release_payload())
        bot = MockTelegram()
        patch_client(self, bot)
        with unittest_mock.patch.dict(os.environ, {"TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": "0" * 32}):
            code = announce.run_preflight(release, CHAT, TOKEN)
        self.assertEqual(bot.methods(), ["getChat"])
        self.assertEqual(code, announce.EXIT_OK)

    def test_preflight_reports_an_unreachable_channel(self) -> None:
        release = notes.Release.from_api(release_payload())
        mock = MockTelegram()
        mock.fail["getChat"] = [
            _HttpError(403, {"ok": False, "error_code": 403, "description": "Forbidden: bot is not a member"}
        ).to_error()
        ]
        patch_client(self, mock)
        code = announce.run_preflight(release, "@StreamBridge_App", TOKEN)
        self.assertEqual(code, announce.EXIT_ERROR)

    def test_preflight_never_prints_the_token(self) -> None:
        release = notes.Release.from_api(release_payload())
        bot = MockTelegram()
        patch_client(self, bot)
        output = io.StringIO()
        with contextlib.redirect_stdout(output), unittest_mock.patch.dict(os.environ, {
            "TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": "0" * 32}):
            announce.run_preflight(release, CHAT, TOKEN)
        self.assertNotIn(TOKEN, output.getvalue())
        self.assertNotIn(TOKEN.split(":")[1], output.getvalue())


def evaluate_actions_if(expression: str, values: dict[str, object]) -> bool:
    """Evaluate the small, deliberately restricted Actions expressions under test."""
    for name in sorted(values, key=len, reverse=True):
        expression = re.sub(rf"(?<![\w.]){re.escape(name)}(?![\w.])", lambda _: repr(values[name]), expression)
    expression = expression.replace("&&", " and ").replace("||", " or ")
    expression = re.sub(r"(?<![\w'\"])true(?![\w'\"])", "True", expression)
    expression = re.sub(r"(?<![\w'\"])false(?![\w'\"])", "False", expression)
    tree = ast.parse(expression.strip(), mode="eval")
    allowed = (ast.Expression, ast.BoolOp, ast.And, ast.Or, ast.Compare, ast.Eq, ast.NotEq, ast.Constant)
    if any(not isinstance(node, allowed) for node in ast.walk(tree)):
        raise AssertionError(f"unexpected Actions expression: {expression}")
    return bool(eval(compile(tree, "<workflow-if>", "eval"), {"__builtins__": {}}, {}))


@unittest.skipUnless(yaml is not None, "PyYAML is required for workflow preflight tests")
class PreflightWorkflowTests(unittest.TestCase):
    """Test the actual workflow gates and execute its shell with offline clients."""

    SECRETS = {
        "TELEGRAM_BOT_TOKEN": TOKEN,
        "TELEGRAM_CHAT_ID": "@private-test-channel",
        "TELEGRAM_API_ID": "73124689",
        "TELEGRAM_API_HASH": "0123456789abcdef0123456789abcdef",
    }

    @classmethod
    def setUpClass(cls) -> None:
        path = SCRIPTS.parent / ".github/workflows/telegram-release-delivery.yml"
        cls.workflow = yaml.safe_load(path.read_text(encoding="utf-8"))
        cls.preflight = cls.workflow["jobs"]["preflight"]
        cls.announce_job = cls.workflow["jobs"]["announce"]
        cls.step = next(s for s in cls.preflight["steps"] if s.get("name") == "Verify Telegram readiness (read-only)")

    def _run_step(self, overrides: dict[str, str] | None = None):
        """Execute the literal Actions run block; all external clients are fakes."""
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            bin_dir = temp / "bin"
            bin_dir.mkdir()
            (bin_dir / "python3").symlink_to(sys.executable)
            # Prevent any Git/GitHub/network executable from accidentally running.
            for name in ("git", "gh", "curl", "wget", "ssh", "scp"):
                command = bin_dir / name
                command.write_text(
                    '#!/bin/sh\nprintf "{\\"event\\":\\"forbidden\\",\\"method\\":\\"%s\\"}\\n" '
                    '"$(basename "$0")" >> "$TELEGRAM_PREFLIGHT_TRACE"\nexit 99\n',
                    encoding="utf-8",
                )
                command.chmod(0o755)
            (temp / "sitecustomize.py").write_text(
                "from telegram_preflight_mock import install\ninstall()\n", encoding="utf-8"
            )
            trace = temp / "trace.jsonl"
            summary = temp / "summary.md"
            summary.touch()
            fixture = temp / "telegram-preflight-release.json"
            env = {
                key: value for key, value in os.environ.items()
                if not key.startswith(("TELEGRAM_", "FAKE_")) and key not in {"GH_TOKEN", "GITHUB_TOKEN"}
            }
            env.update(self.SECRETS)
            env.update(overrides or {})
            env.update({
                "PATH": f"{bin_dir}{os.pathsep}{env.get('PATH', '')}",
                "PYTHONPATH": os.pathsep.join((str(temp), str(SCRIPTS / "tests"))),
                "RUNNER_TEMP": str(temp),
                "GITHUB_STEP_SUMMARY": str(summary),
                "TELEGRAM_PREFLIGHT_TRACE": str(trace),
            })
            ledger = SCRIPTS.parent / ".github/telegram-releases.json"
            before = ledger.read_bytes()
            result = subprocess.run(
                ["bash", "-e", "-o", "pipefail", "-c", self.step["run"]],
                cwd=SCRIPTS.parent, env=env, capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(ledger.read_bytes(), before, "preflight changed the repository ledger")
            events = [json.loads(line) for line in trace.read_text(encoding="utf-8").splitlines()] if trace.exists() else []
            self.assertFalse([event for event in events if event["event"] in {"forbidden", "ledger_access"}], events)
            return result, events, summary.read_text(encoding="utf-8"), json.loads(fixture.read_text()) if fixture.exists() else None

    def test_preflight_is_default_off_and_bot_api_step_does_not_create_a_session(self) -> None:
        triggers = self.workflow.get('on', self.workflow.get(True))
        self.assertEqual(triggers['release'], {'types': ['published']})
        self.assertNotIn('push', triggers)
        self.assertNotIn('pull_request', triggers)
        self.assertIs(triggers['workflow_dispatch']['inputs']['preflight']['default'], False)
        self.assertIs(triggers['workflow_dispatch']['inputs']['dry_run']['default'], True)
        self.assertIs(triggers['workflow_dispatch']['inputs']['resume_unsent']['default'], False)
        self.assertEqual(self.preflight['permissions'], {'contents': 'read'})
        self.assertNotIn('needs', self.preflight)
        checkout, setup, verify = self.preflight['steps'][:3]
        self.assertFalse(checkout['with']['persist-credentials'])
        self.assertEqual(checkout['with']['ref'], '${{ github.event.repository.default_branch }}')
        self.assertNotIn('telethon', verify['run'].lower())
        group = self.workflow['concurrency']['group']
        self.assertEqual(group, "${{ github.event_name == 'workflow_dispatch' && inputs.preflight == true && "
                                "format('telegram-preflight-{0}', github.run_id) || 'telegram-streambridge-channel' }}")
        self.assertEqual(self.workflow['concurrency']['queue'], 'max')
        self.assertIs(self.workflow['concurrency']['cancel-in-progress'], False)
        self.assertTrue(all(verify['env'][name] == '${{ secrets.' + name + ' }}' for name in self.SECRETS))
        blocked, install, login = self.preflight['steps'][3:]
        self.assertEqual(blocked['if'], "vars.TELEGRAM_PUBLISHING_ENABLED == 'true'")
        self.assertIn('full preflight is not verified', blocked['run'])
        result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', blocked['run']],
                                capture_output=True, text=True, timeout=30)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('MTProto login was not checked', result.stdout)
        self.assertEqual(install['if'], "vars.TELEGRAM_PUBLISHING_ENABLED != 'true'")
        self.assertEqual(login['if'], install['if'])
        self.assertIn('telethon==1.45.0', install['run'])
        self.assertEqual(login['run'], 'python3 scripts/telegram_mtproto_preflight.py')
        self.assertTrue(all(login['env'][name] == '${{ secrets.' + name + ' }}' for name in self.SECRETS))

    def test_all_dispatch_combinations_require_explicit_publish_and_preflight_wins(self) -> None:
        publish_job = self.workflow['jobs']['publish']
        for preflight, dry, upload, recover, tag, rid, enabled, activated in itertools.product(
                (False, True), (False, True), (False, True), (False, True), ('', 'v9.9.9'), ('', '987654321'), ('', 'true'), ('false', 'true')):
            context = {'github.event_name': 'workflow_dispatch', 'inputs.preflight': preflight,
                       'inputs.dry_run': dry, 'inputs.upload_assets': upload, 'inputs.resume_unsent': recover,
                       'inputs.tag': tag, 'inputs.release_id': rid, 'vars.TELEGRAM_PUBLISHING_ENABLED': enabled,
                       'github.event.release.draft': None, 'github.event.release.prerelease': None,
                       'needs.announce.outputs.skip': 'false', 'needs.announce.outputs.activated': activated,
                       'needs.announce.outputs.dry_run': 'true' if dry else 'false'}
            with self.subTest(preflight=preflight, dry=dry, upload=upload, recover=recover, tag=tag,
                              enabled=enabled, activated=activated):
                self.assertEqual(evaluate_actions_if(self.preflight['if'], context), preflight)
                self.assertEqual(evaluate_actions_if(self.announce_job['if'], context), not preflight)
                self.assertEqual(evaluate_actions_if(publish_job['if'], context),
                                 enabled == 'true' and activated == 'true' and not preflight and not dry and recover and bool(tag) and bool(rid))
        for draft, prerelease, enabled, activated in itertools.product(
                (False, True), (False, True), ('', 'true'), ('false', 'true')):
            context = {'github.event_name': 'release', 'inputs.preflight': None, 'inputs.resume_unsent': None,
                       'inputs.tag': None, 'inputs.release_id': None, 'vars.TELEGRAM_PUBLISHING_ENABLED': enabled,
                       'github.event.release.draft': draft, 'github.event.release.prerelease': prerelease,
                       'needs.announce.outputs.skip': 'true' if draft or prerelease else 'false',
                       'needs.announce.outputs.dry_run': 'false', 'needs.announce.outputs.activated': activated}
            self.assertEqual(evaluate_actions_if(self.announce_job['if'], context), not (draft or prerelease))
            self.assertEqual(evaluate_actions_if(publish_job['if'], context),
                             enabled == 'true' and activated == 'true' and not (draft or prerelease))

    def test_actual_activation_step_is_default_off_even_if_implementation_is_missing(self) -> None:
        step = next(s for s in self.announce_job['steps'] if s.get('id') == 'activation')['run']
        self.assertEqual(self.announce_job['outputs']['activated'], '${{ steps.activation.outputs.enabled }}')
        for cwd in (SCRIPTS.parent, None):
            with self.subTest(cwd=cwd), tempfile.TemporaryDirectory() as directory:
                output = Path(directory, 'output')
                output.touch()
                env = os.environ.copy()
                env['GITHUB_OUTPUT'] = str(output)
                result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', step],
                                        cwd=cwd or directory, env=env, capture_output=True, text=True, timeout=30)
                if cwd is None:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('::error::', result.stdout)
                    self.assertEqual(output.read_text().strip(), '')
                else:
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertEqual(output.read_text().strip(), 'enabled=false')
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            (temp / 'scripts').symlink_to(SCRIPTS, target_is_directory=True)
            (temp / '.github').mkdir()
            (temp / '.github/telegram-publishing.json').write_text('{"enabled":true,"not_before":"2000-01-01T00:00:00Z"}')
            output = temp / 'output'
            output.touch()
            env = os.environ.copy()
            env['GITHUB_OUTPUT'] = str(output)
            result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', step],
                                    cwd=temp, env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(output.read_text().strip(), 'enabled=true')

    def test_actual_preflight_shell_uses_only_bot_api_reads_and_logs_no_secrets(self) -> None:
        result, events, summary, fixture = self._run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('**Passed:**', summary)
        self.assertIn('MTProto login, channel history and uploads were not tested', result.stdout)
        self.assertIsNone(fixture)
        bot_calls = [event for event in events if event['event'] == 'bot_api']
        self.assertEqual([event['method'] for event in bot_calls], ['getMe', 'getChat', 'getChatMember'])
        self.assertTrue(all(event['token_matches'] and event['chat_matches'] for event in bot_calls))
        self.assertEqual(bot_calls[-1]['user_id'], 987654)
        self.assertFalse([event for event in events if event['event'].startswith('mtproto')])
        for secret in self.SECRETS.values():
            self.assertNotIn(secret, result.stdout + result.stderr + summary)
        self.assertNotIn('::add-mask::', result.stdout)
        for forbidden in ('gh api', 'gh release', 'git push', 'git commit', 'telegram_assets.py', 'releases/latest'):
            self.assertNotIn(forbidden, self.step['run'])

    def test_invalid_mtproto_credential_syntax_fails_without_authentication(self) -> None:
        for override in ({'TELEGRAM_API_ID': 'invalid'}, {'TELEGRAM_API_HASH': 'short'}):
            result, events, summary, _ = self._run_step(override)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('MTProto credentials are unusable', result.stdout)
            self.assertIn('**Failed:**', summary)
            self.assertFalse([e for e in events if e['event'].startswith('mtproto')])

    def test_preflight_does_not_claim_mtproto_authentication_even_if_a_fake_would_fail(self) -> None:
        result, events, summary, _ = self._run_step({'FAKE_MTPROTO_MODE': 'auth_failure'})
        self.assertEqual(result.returncode, 0)
        self.assertIn('MTProto login, channel history and uploads were not tested', result.stdout)
        self.assertFalse([e for e in events if e['event'].startswith('mtproto')])
        self.assertIn('**Passed:**', summary)

    def test_published_release_event_and_pinned_dependencies(self) -> None:
        publish_job = self.workflow['jobs']['publish']
        install = next(s for s in publish_job['steps'] if s.get('name') == 'Install pinned MTProto dependencies')
        for dep in ('telethon==1.45.0', 'pyaes==1.6.1', 'rsa==4.9.1', 'pyasn1==0.6.1'):
            self.assertIn(dep, install['run'])
        resolve = next(s for s in self.announce_job['steps'] if s.get('id') == 'release')['run']
        self.assertIn('gh api "repos/${GITHUB_REPOSITORY}/releases/${EVENT_RELEASE_ID}"', resolve)
        self.assertIn('echo "dry_run=false"', resolve)

    def test_exact_release_resolution_and_dry_run_behavior_with_mock_gh(self) -> None:
        resolve = next(step["run"] for step in self.announce_job["steps"] if step.get("id") == "release")
        self.assertNotIn("releases/latest", resolve)
        # event, dry, tag, ID, prerelease, resume, expected GitHub call, succeeds
        cases = (
            ("release", "true", "ignored", "", "false", "false", "api repos/sascio/MyBridge/releases/987654321", True),
            ("release", "true", "ignored", "", "true", "false", None, True),
            ("workflow_dispatch", "true", "v9.9.9", "", "false", "false", "api repos/sascio/MyBridge/releases/tags/v9.9.9", True),
            ("workflow_dispatch", "true", "release/v1", "", "false", "false", "api repos/sascio/MyBridge/releases/tags/release%2Fv1", True),
            ("workflow_dispatch", "true", "", "", "false", "false", None, False),
            ("workflow_dispatch", "true", "latest", "", "false", "false", None, False),
            ("workflow_dispatch", "true", "LATEST", "", "false", "false", None, False),
            ("workflow_dispatch", "true", " v1", "", "false", "false", None, False),
            ("workflow_dispatch", "false", "v9.9.9", "987654321", "false", "true", "api repos/sascio/MyBridge/releases/tags/v9.9.9", True),
            ("workflow_dispatch", "false", "v9.9.9", "", "false", "true", None, False),
            ("workflow_dispatch", "false", "v9.9.9", "987654321", "false", "false", None, False),
            ("workflow_dispatch", "false", "v9.9.9", "123", "false", "true", "api repos/sascio/MyBridge/releases/tags/v9.9.9", False),
        )
        for event, dry_run, tag, rid, prerelease, resume, expected_call, succeeds in cases:
            with self.subTest(event=event, dry_run=dry_run, tag=tag, rid=rid, resume=resume), tempfile.TemporaryDirectory() as directory:
                temp = Path(directory)
                bin_dir = temp / "bin"
                bin_dir.mkdir()
                (bin_dir / "python3").symlink_to(sys.executable)
                gh = bin_dir / "gh"
                gh.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$GH_TEST_TRACE"\ncat "$GH_TEST_RELEASE"\n', encoding="utf-8")
                gh.chmod(0o755)
                trace, payload, output = temp / "gh-trace", temp / "payload.json", temp / "output"
                payload.write_text(json.dumps(release_payload(tag_name=tag if event == "workflow_dispatch" else "v9.9.9")), encoding="utf-8")
                output.touch()
                env = os.environ.copy()
                env.pop("GH_TOKEN", None)
                env.pop("GITHUB_TOKEN", None)
                env.update({
                    "PATH": f"{bin_dir}{os.pathsep}{env.get('PATH', '')}",
                    "GH_TEST_TRACE": str(trace), "GH_TEST_RELEASE": str(payload),
                    "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(temp / "summary"), "RUNNER_TEMP": str(temp),
                    "GITHUB_REPOSITORY": "sascio/MyBridge", "EVENT_NAME": event,
                    "EVENT_RELEASE_ID": "987654321", "EVENT_RELEASE_DRAFT": "false", "EVENT_RELEASE_PRERELEASE": prerelease,
                    "INPUT_TAG": tag, "INPUT_RELEASE_ID": rid, "DRY_RUN_INPUT": dry_run, "RESUME_INPUT": resume,
                    "PUBLISHING_ENABLED": "true",
                })
                result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", resolve],
                                        cwd=SCRIPTS.parent, env=env, capture_output=True, text=True, timeout=30)
                calls = trace.read_text().splitlines() if trace.exists() else []
                self.assertEqual(calls, [expected_call] if expected_call else [])
                self.assertEqual(result.returncode == 0, succeeds, result.stdout + result.stderr)
                if succeeds:
                    outputs = dict(line.split("=", 1) for line in output.read_text().splitlines())
                    self.assertEqual(outputs["dry_run"], "true" if event == "workflow_dispatch" and dry_run == "true" else "false")
                    self.assertEqual(outputs["skip"], "true" if prerelease == "true" else "false")

    def test_each_missing_or_blank_credential_fails_before_any_client_is_used(self) -> None:
        for name, value in itertools.product(self.SECRETS, ("", "   ")):
            with self.subTest(secret=name, value=value):
                result, events, summary, fixture = self._run_step({name: value})
                self.assertEqual(result.returncode, 3, result.stdout + result.stderr)
                self.assertIn(f"{name} secret is not configured", result.stdout)
                self.assertEqual(events, [])
                self.assertIsNone(fixture)
                self.assertIn("**Failed:**", summary)

    def test_secrets_with_whitespace_fail_without_exposing_values(self) -> None:
        for name, value in itertools.product(self.SECRETS, ("first-line\nsecond-line", " first-line", "first-line ")):
            with self.subTest(secret=name, value=value):
                result, events, summary, fixture = self._run_step({name: value})
                self.assertEqual(result.returncode, 3, result.stdout + result.stderr)
                self.assertIn(f"{name} secret must be a single line", result.stdout)
                self.assertNotIn("first-line", result.stdout + result.stderr)
                self.assertNotIn("second-line", result.stdout + result.stderr)
                self.assertEqual(events, [])
                self.assertIsNone(fixture)
                self.assertIn("**Failed:**", summary)

    def test_unreachable_channel_or_non_channel_fails_closed(self) -> None:
        for mode, message in (("unreachable", "did not return a channel"), ("wrong_type", "did not return a channel")):
            with self.subTest(mode=mode):
                result, _events, summary, _fixture = self._run_step({"FAKE_CHAT_MODE": mode})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stdout)
                self.assertIn("**Failed:**", summary)

    def test_invalid_bot_or_administrator_permissions_fail_closed(self) -> None:
        cases = (
            ({"FAKE_BOT_MODE": "invalid"}, "Bot authentication failed"),
            ({"FAKE_BOT_MODE": "not_bot"}, "Bot authentication failed"),
            ({"FAKE_BOT_MODE": "invalid_id"}, "Bot authentication failed"),
            ({"FAKE_MEMBER_MODE": "unreachable"}, "Could not verify bot membership"),
            ({"FAKE_MEMBER_STATUS": "member"}, "not an administrator"),
            ({"FAKE_MEMBER_STATUS": "restricted"}, "not an administrator"),
            ({"FAKE_CAN_POST": "false"}, "lacks the required Post Messages"),
            ({"FAKE_MEMBER_MODE": "missing_post_right"}, "lacks the required Post Messages"),
        )
        for overrides, message in cases:
            with self.subTest(overrides=overrides):
                result, _events, summary, _fixture = self._run_step(overrides)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stdout)
                self.assertNotIn("Preflight passed:", result.stdout)
                self.assertIn("**Failed:**", summary)


@unittest.skipUnless(yaml is not None, "PyYAML is required for CI verification-mode tests")
class CiVerificationWorkflowTests(unittest.TestCase):
    def test_opt_in_verification_runs_all_checks_without_uploads_or_issue_updates(self) -> None:
        workflow = yaml.safe_load((SCRIPTS.parent / ".github/workflows/build.yml").read_text())
        triggers = workflow.get("on", workflow.get(True))
        self.assertIs(triggers["workflow_dispatch"]["inputs"]["upload_artifacts"]["default"], False)
        mode = workflow["env"]["CI_VERIFICATION_ONLY"]
        for clause in ("github.event_name == 'workflow_dispatch' && inputs.upload_artifacts != true",
                       "github.event_name == 'push' && contains(github.event.head_commit.message, '[ci no-upload]')",
                       "github.event_name == 'pull_request' && contains(github.event.pull_request.body, '<!-- ci:no-upload -->')"):
            self.assertIn(clause, mode)
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        self.assertEqual(workflow["jobs"]["build"]["permissions"], {"contents": "read", "issues": "write"})
        jobs = workflow["jobs"]
        self.assertEqual(set(jobs), {"build", "tracking-config-check", "script-tests", "telegram-readiness",
                                    "telegram-state-verification", "telegram-state-cutover", "telegram-input-validation",
                                    "telegram-history-preview"})
        for name in ("build", "tracking-config-check", "script-tests"):
            condition = jobs[name]["if"]
            self.assertIn("inputs.telegram_readiness != true", condition)
            self.assertIn("inputs.telegram_state_verification != true", condition)
            self.assertIn("inputs.telegram_cutover_mode == 'none'", condition)
        probe = jobs["telegram-readiness"]
        self.assertIn("github.event_name == 'workflow_dispatch' && inputs.telegram_readiness == true", probe["if"])
        self.assertIn("inputs.telegram_state_verification != true", probe["if"])
        self.assertEqual(jobs["telegram-state-verification"]["permissions"], {"contents": "write"})
        self.assertEqual(jobs["telegram-state-cutover"]["permissions"], {"contents": "write", "actions": "read"})
        preview = jobs["telegram-history-preview"]
        self.assertEqual(preview["permissions"], {"contents": "write", "actions": "read"})  # GitHub draft visibility
        self.assertIn("inputs.telegram_cutover_mode == 'preview'", preview["if"])
        self.assertNotIn("inputs.telegram_cutover_mode == 'preview'", jobs["telegram-state-cutover"]["if"])
        self.assertTrue(any("--mode preview" in step.get("run", "") for step in preview["steps"]))
        combined_steps = jobs["telegram-state-cutover"]["steps"]
        verification = next(step for step in combined_steps if step.get("name") == "Verify initialized state without delivery")
        self.assertEqual(verification["if"], "inputs.telegram_cutover_mode == 'initialize'")
        self.assertEqual(verification["env"]["STATE_VERIFICATION_REQUESTED"], "true")
        self.assertIn("python3 scripts/telegram_state_verify.py", verification["run"])
        self.assertEqual(jobs["telegram-input-validation"]["permissions"], {})
        for name in ("telegram-state-verification", "telegram-state-cutover"):
            self.assertEqual(jobs[name]["concurrency"],
                             {"group": "telegram-streambridge-channel", "queue": "max", "cancel-in-progress": False})
        self.assertEqual(probe["permissions"], {"contents": "read"})
        self.assertIs(triggers["workflow_dispatch"]["inputs"]["telegram_readiness"]["default"], False)
        self.assertTrue(any(step.get("run") == "python3 scripts/telegram_mtproto_preflight.py" for step in probe["steps"]))
        build = jobs["build"]["steps"]
        uploads = [step for job in jobs.values() for step in job["steps"]
                   if "actions/upload-artifact@" in step.get("uses", "")]
        issue_updates = [step for step in build if step.get("name") in
                         {"Close resolved CI-failure issues", "Report failure log as an issue"}]
        self.assertEqual(len(uploads), 3)
        self.assertEqual(len(issue_updates), 2)
        for step in uploads + issue_updates:
            self.assertIn("env.CI_VERIFICATION_ONLY != 'true'", step["if"])
        for job in (jobs["build"], jobs["tracking-config-check"]):
            setup = next(step for step in job["steps"] if "gradle/actions/setup-gradle@" in step.get("uses", ""))
            self.assertIn("env.CI_VERIFICATION_ONLY == 'true'", setup["with"]["cache-disabled"])
            self.assertIn("env.CI_VERIFICATION_ONLY == 'true'", setup["with"]["cache-read-only"])
            self.assertEqual(setup["with"]["dependency-graph"], "disabled")
            self.assertIs(setup["with"]["build-scan-publish"], False)
        names = {step.get("name") for step in build}
        self.assertTrue({"Run unit tests", "Assemble Nuvio full debug and release APKs",
                         "Run Android lint (full release)",
                         "Verify Play Store distribution excludes the CloudStream runtime"} <= names)
        self.assertTrue(any(step.get("name") == "Run script tests" for step in jobs["script-tests"]["steps"]))

    def test_script_test_failures_are_annotated_without_uploading_a_report(self) -> None:
        workflow = yaml.safe_load((SCRIPTS.parent / ".github/workflows/build.yml").read_text())
        step = next(step for step in workflow["jobs"]["script-tests"]["steps"]
                    if step.get("name") == "Run script tests")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture = root / "scripts/tests/run-tests.sh"
            fixture.parent.mkdir(parents=True)
            env = dict(os.environ, RUNNER_TEMP=tmp)
            def run_fixture(body):
                fixture.write_text("#!/usr/bin/env bash\n" + body, encoding="utf-8")
                fixture.chmod(0o755)
                return subprocess.run(["bash", "-e", "-o", "pipefail", "-c", step["run"]],
                                      cwd=root, env=env, text=True, capture_output=True)
            passed = run_fixture("echo 'all passed'\necho '::error::simulated offline failure'\nexit 0\n")
            self.assertEqual(passed.returncode, 0)
            self.assertNotIn("::error title=Script", passed.stdout)
            pause = re.search(r"::stop-commands::([0-9a-f]{64})", passed.stdout)
            self.assertIsNotNone(pause)
            self.assertLess(passed.stdout.index("::error::simulated"), passed.stdout.index(f"::{pause.group(1)}::"))
            failed = run_fixture("printf '%s\\n' 'FAIL: test_bad (suite.Test)' "
                                 "'ERROR: test_other (suite.Test)' '  FAIL release check'\nexit 2\n")
            self.assertNotEqual(failed.returncode, 0)
            for name in ("FAIL: test_bad (suite.Test)", "ERROR: test_other (suite.Test)", "  FAIL release check"):
                self.assertIn("::error title=Script test case failed::" + name, failed.stdout)
            self.assertFalse((root / "script-tests.log").exists())


class WorkflowRefTests(unittest.TestCase):
    def test_checkout_uses_the_default_branch_not_the_release_tag(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        workflow = yaml.safe_load((SCRIPTS.parent / ".github/workflows/telegram-release-delivery.yml").read_text())
        for name, persisted in (("preflight", False), ("announce", False), ("publish", False)):
            checkout = next(step for step in workflow["jobs"][name]["steps"] if "checkout" in str(step.get("uses", "")))
            self.assertEqual(checkout["with"]["ref"], "${{ github.event.repository.default_branch }}")
            self.assertIs(checkout["with"]["persist-credentials"], persisted)

    def test_state_access_uses_same_repo_github_token_and_is_default_off(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        workflow = yaml.safe_load((SCRIPTS.parent / ".github/workflows/telegram-release-delivery.yml").read_text())
        job = workflow["jobs"]["publish"]
        self.assertNotIn("environment", job)
        self.assertEqual(job["permissions"], {"contents": "write"})
        self.assertEqual(json.loads((SCRIPTS.parent / ".github/telegram-state.json").read_text()), {"enabled": False})
        steps = job["steps"]
        config_index = next(i for i, step in enumerate(steps) if step.get("id") == "state")
        state_index = next(i for i, step in enumerate(steps) if step.get("name") == "Check out the dedicated state branch")
        publish_index = next(i for i, step in enumerate(steps) if step.get("name") == "Publish individual messages and documents")
        self.assertLess(config_index, state_index)
        self.assertLess(state_index, publish_index)
        self.assertFalse(any("octo-sts" in step.get("uses", "") for step in steps))
        checkout = steps[state_index]["with"]
        self.assertEqual(checkout["repository"], "${{ steps.state.outputs.repository }}")
        self.assertEqual(checkout["ref"], "${{ steps.state.outputs.branch }}")
        self.assertEqual(checkout["token"], "${{ github.token }}")
        self.assertEqual(checkout["path"], ".telegram-state")
        self.assertIn('--state-dir "${STATE_DIR}"', steps[publish_index]["run"])

    def test_the_delivery_step_does_not_request_pin_rights(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        workflow = yaml.safe_load((SCRIPTS.parent / ".github/workflows/telegram-release-delivery.yml").read_text())
        publish = next(step for step in workflow["jobs"]["publish"]["steps"] if step.get("name") == "Publish individual messages and documents")
        self.assertNotIn("--pin", publish["run"])
        self.assertEqual(publish["env"]["GH_TOKEN"], "${{ github.token }}")
        self.assertEqual(publish["env"]["TELEGRAM_PUBLISHING_ENABLED"], "${{ vars.TELEGRAM_PUBLISHING_ENABLED }}")
        self.assertEqual(workflow["jobs"]["publish"]["permissions"], {"contents": "write"})


if __name__ == "__main__":
    unittest.main(verbosity=2)
