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
sys.path.insert(0, str(SCRIPTS))

import telegram_announce as announce  # noqa: E402
import telegram_assets as assets  # noqa: E402
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

    def test_send_path_uses_html_and_inline_keyboard(self) -> None:
        mock = MockTelegram()
        run = announce.send_announcement(
            client(mock), self.release, self.announcement, self.pages, throttle=lambda _s: None
        )
        self.assertEqual(run.sent_message_ids, [1000, 1001])
        self.assertTrue(run.pinned)
        for payload in mock.sent_messages():
            self.assertEqual(payload["parse_mode"], "HTML")
            self.assertEqual(payload["chat_id"], CHAT)
            self.assertTrue(payload["link_preview_options"]["is_disabled"])
        first = mock.sent_messages()[0]
        rows = first["reply_markup"]["inline_keyboard"]
        self.assertEqual(rows[0], [{"text": notes.DOWNLOAD_BUTTON_LABEL, "url": RELEASE_URL}])
        # One row per verified binary (four ABIs + the IPA) plus the checksum file.
        self.assertEqual(len(rows), 7)
        labels = [row[0]["text"] for row in rows]
        for expected in ("Android ARM64", "Android ARMv7", "Android x86", "Android x86_64", "iOS IPA"):
            self.assertTrue(
                any(expected in label for label in labels), f"no direct button for {expected}: {labels}"
            )
        for row in rows:
            self.assertTrue(row[0]["url"].startswith("https://"))

    def test_marker_is_on_every_page_and_matches_the_release_id(self) -> None:
        for text in self.pages:
            self.assertIn("#SB987654321", text)
        self.assertEqual(announce.marker_for(987654321), "#SB987654321")

    def test_duplicate_release_event_is_not_announced_twice(self) -> None:
        mock = MockTelegram()
        mock.pinned = {"message_id": 42, "text": "old announcement\n#StreamBridge #SB987654321"}
        reason = announce.check_already_announced(client(mock), self.release, "")
        self.assertIsNotNone(reason)
        self.assertIn("#SB987654321", reason)

    def test_marker_is_found_in_button_markup_and_caption_too(self) -> None:
        self.assertEqual(
            announce.marker_in_message({"reply_markup": {"inline_keyboard": [[{"url": "https://x/#SB123"}]]}}),
            "#SB123",
        )
        self.assertEqual(announce.marker_in_message({"caption": "text #SB55 here"}), "#SB55")
        self.assertIsNone(announce.marker_in_message({"text": "no marker"}))

    def test_previous_release_pin_does_not_block_a_new_release(self) -> None:
        mock = MockTelegram()
        mock.pinned = {"message_id": 41, "text": "#StreamBridge #SB111111111"}
        self.assertIsNone(announce.check_already_announced(client(mock), self.release, ""))

    def test_post_ledger_blocks_a_resend_when_the_channel_is_unreadable(self) -> None:
        ledger = json.dumps({"releases": [{"release_id": 987654321}]})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "ledger.json")
            path.write_text(ledger, encoding="utf-8")
            # No pinned message is visible (the bot was restarted, say), but the
            # ledger written after the last successful send still remembers it.
            mock = MockTelegram()
            reason = announce.check_already_announced(client(mock), self.release, str(path))
            self.assertIsNotNone(reason)
            self.assertIn("post ledger", reason)

    def test_ledger_is_written_only_with_confirmed_message_ids(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "ledger.json")
            announce.record_in_ledger(str(path), self.release, [1000, 1001], CHAT)
            data = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(data["releases"][0]["message_ids"], [1000, 1001])
            self.assertEqual(data["releases"][0]["release_id"], 987654321)
            self.assertEqual(data["releases"][0]["tag_name"], "v9.9.9")
            self.assertNotIn(CHAT, path.read_text(encoding="utf-8"))

    def test_bot_client_repr_does_not_contain_secrets(self) -> None:
        telegram = announce.TelegramClient(TOKEN, CHAT)
        self.assertNotIn(TOKEN, repr(telegram))
        self.assertNotIn(CHAT, repr(telegram))

    def test_a_lost_send_response_can_duplicate_a_page_during_retries(self) -> None:
        bot = MockTelegram()
        accepted: list[dict] = []

        def opener(request, timeout=None):
            if request.full_url.endswith("/sendMessage"):
                accepted.append(json.loads(request.data.decode("utf-8")))
                if len(accepted) == 1:
                    # The server accepted the first page, but its response was
                    # lost. A retry cannot distinguish this from no delivery.
                    raise urllib.error.URLError("response lost after acceptance")
            return bot.urlopen(request, timeout=timeout)

        telegram = announce.TelegramClient(TOKEN, CHAT, retries=2, sleeper=lambda _s: None, opener=opener)
        run = announce.send_announcement(telegram, self.release, self.announcement, self.pages[:1], pin=False)
        self.assertEqual(len(run.sent_message_ids), 1)
        self.assertEqual(len(accepted), 2)
        self.assertEqual(accepted[0]["text"], accepted[1]["text"])

    def test_retry_after_is_honoured_and_the_send_then_succeeds(self) -> None:
        mock = MockTelegram()
        sleeps: list[float] = []
        mock.fail["sendMessage"] = [
            _HttpError(429, {"ok": False, "error_code": 429, "description": "Too Many Requests: retry after 3", "parameters": {"retry_after": 3}}),
        ]
        telegram = announce.TelegramClient(
            TOKEN, CHAT, retries=3, sleeper=sleeps.append, opener=mock.urlopen
        )
        run = announce.send_announcement(telegram, self.release, self.announcement, self.pages)
        self.assertEqual(len(run.sent_message_ids), len(self.pages))
        self.assertTrue(any(3.0 <= value < 4.0 for value in sleeps))

    def test_server_error_is_retried(self) -> None:
        mock = MockTelegram()
        mock.fail["sendMessage"] = [
            _HttpError(502, {"ok": False, "error_code": 502, "description": "Bad Gateway"}),
        ]
        run = announce.send_announcement(
            announce.TelegramClient(TOKEN, CHAT, retries=3, sleeper=lambda _s: None, opener=mock.urlopen),
            self.release,
            self.announcement,
            self.pages,
        )
        self.assertEqual(len(run.sent_message_ids), len(self.pages))

    def test_permanent_error_is_not_retried(self) -> None:
        mock = MockTelegram()
        mock.fail["sendMessage"] = [
            _HttpError(401, {"ok": False, "error_code": 401, "description": "Unauthorized"}),
        ]
        telegram = announce.TelegramClient(
            TOKEN, CHAT, retries=3, sleeper=lambda _s: None, opener=mock.urlopen
        )
        caught = expect_announcement_error(
            self,
            lambda: announce.send_announcement(telegram, self.release, self.announcement, self.pages),
        )
        self.assertIn("Unauthorized", str(caught))
        self.assertEqual(mock.methods().count("sendMessage"), 1)

    def test_network_failure_is_retried_then_reported(self) -> None:
        mock = MockTelegram()
        mock.fail["sendMessage"] = [
            urllib.error.URLError("connection reset"),
            urllib.error.URLError("connection reset"),
            urllib.error.URLError("connection reset"),
        ]
        telegram = announce.TelegramClient(
            TOKEN, CHAT, retries=3, sleeper=lambda _s: None, opener=mock.urlopen
        )
        caught = expect_announcement_error(
            self,
            lambda: announce.send_announcement(telegram, self.release, self.announcement, self.pages),
        )
        self.assertIn("network error", str(caught))
        self.assertEqual(mock.methods().count("sendMessage"), 3)

    def test_partial_failure_reports_delivered_messages(self) -> None:
        mock = MockTelegram()
        # Page 1 succeeds; page 2 is rejected permanently (400s are not retried).
        mock.fail["sendMessage"] = [
            None,
            _HttpError(
                400,
                {"ok": False, "error_code": 400, "description": "Bad Request: can't parse entities"},
            ),
        ]
        telegram = announce.TelegramClient(
            TOKEN, CHAT, retries=1, sleeper=lambda _s: None, opener=mock.urlopen
        )
        caught = expect_announcement_error(
            self,
            lambda: announce.send_announcement(telegram, self.release, self.announcement, self.pages),
        )
        message = str(caught)
        self.assertIn("Delivered so far: 1000", message)
        self.assertIn("page 2/2", message)
        self.assertIn("GitHub release is unaffected", message)

    def test_missing_secrets_fail_clearly_without_sending(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            release_file = Path(directory, "release.json")
            release_file.write_text(json.dumps(release_payload()), encoding="utf-8")
            for variable in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID"):
                os.environ.pop(variable, None)
            buffer = io.StringIO()
            stdout, sys.stdout = sys.stdout, buffer
            try:
                code = announce.main(["--release-json", str(release_file)])
            finally:
                sys.stdout = stdout
            self.assertEqual(code, announce.EXIT_CONFIG)
            self.assertIn("TELEGRAM_BOT_TOKEN secret is not configured", buffer.getvalue())

    def test_token_is_masked_and_never_printed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            release_file = Path(directory, "release.json")
            release_file.write_text(json.dumps(release_payload()), encoding="utf-8")
            summary = Path(directory, "summary.json")
            mock = MockTelegram()
            os.environ["TELEGRAM_BOT_TOKEN"] = TOKEN
            os.environ["TELEGRAM_CHAT_ID"] = CHAT
            self.addCleanup(os.environ.pop, "TELEGRAM_BOT_TOKEN", None)
            self.addCleanup(os.environ.pop, "TELEGRAM_CHAT_ID", None)
            patch_client(self, mock)
            try:
                buffer = io.StringIO()
                stdout, sys.stdout = sys.stdout, buffer
                try:
                    code = announce.main(
                        ["--release-json", str(release_file), "--summary", str(summary)]
                    )
                finally:
                    sys.stdout = stdout
            finally:
                sys.stdout = stdout
            output = buffer.getvalue()
            self.assertEqual(code, announce.EXIT_OK)
            self.assertIn(f"::add-mask::{TOKEN}", output)
            self.assertNotIn(TOKEN, output.replace(f"::add-mask::{TOKEN}", ""))
            for payload in mock.sent_messages():
                self.assertNotIn(TOKEN, json.dumps(payload))
            self.assertNotIn(TOKEN, summary.read_text(encoding="utf-8"))

    def test_rerun_after_a_successful_send_exits_already_posted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            release_file = Path(directory, "release.json")
            release_file.write_text(json.dumps(release_payload()), encoding="utf-8")
            ledger = Path(directory, "ledger.json")
            mock = MockTelegram()
            os.environ["TELEGRAM_BOT_TOKEN"] = TOKEN
            os.environ["TELEGRAM_CHAT_ID"] = CHAT
            self.addCleanup(os.environ.pop, "TELEGRAM_BOT_TOKEN", None)
            self.addCleanup(os.environ.pop, "TELEGRAM_CHAT_ID", None)
            patch_client(self, mock)
            buffer = io.StringIO()
            stdout, sys.stdout = sys.stdout, buffer
            try:
                first = announce.main(["--release-json", str(release_file), "--ledger", str(ledger), "--summary", str(Path(directory, "s1.json"))])
                sent_after_first = len(mock.sent_messages())
                second = announce.main(["--release-json", str(release_file), "--ledger", str(ledger), "--summary", str(Path(directory, "s2.json"))])
                sent_after_second = len(mock.sent_messages())
            finally:
                sys.stdout = stdout
            self.assertEqual(first, announce.EXIT_OK)
            self.assertEqual(second, announce.EXIT_ALREADY_POSTED)
            self.assertEqual(sent_after_first, sent_after_second)
            self.assertIn("already announced", buffer.getvalue())

    def test_persisted_ledger_blocks_sends_without_any_history_lookup(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            release_file = Path(directory, "release.json")
            release_file.write_text(json.dumps(release_payload()), encoding="utf-8")
            ledger = Path(directory, "ledger.json")
            announce.record_in_ledger(str(ledger), self.release, [1000], CHAT)
            bot = MockTelegram()
            patch_client(self, bot)
            env = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
                   "TELEGRAM_API_ID": "73124689", "TELEGRAM_API_HASH": "0123456789abcdef" * 2}
            with unittest_mock.patch.dict(os.environ, env), \
                    unittest_mock.patch.object(assets, "MtprotoUploader", side_effect=AssertionError("lookup not needed")), \
                    contextlib.redirect_stdout(io.StringIO()):
                code = announce.main(["--release-json", str(release_file), "--ledger", str(ledger)])
            self.assertEqual(code, announce.EXIT_ALREADY_POSTED)
            self.assertNotIn("sendMessage", bot.methods())

    def test_a_hypothetical_history_match_stops_a_send_without_a_ledger(self) -> None:
        # This tests the caller's duplicate guard, not bot history availability.
        with tempfile.TemporaryDirectory() as directory:
            release_file = Path(directory, "release.json")
            release_file.write_text(json.dumps(release_payload()), encoding="utf-8")
            bot = MockTelegram()
            patch_client(self, bot)
            uploader = unittest_mock.Mock()
            uploader.find_marker.return_value = True
            env = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
                   "TELEGRAM_API_ID": "73124689", "TELEGRAM_API_HASH": "0123456789abcdef" * 2}
            with unittest_mock.patch.dict(os.environ, env), \
                    unittest_mock.patch.object(assets, "MtprotoUploader", return_value=uploader), \
                    contextlib.redirect_stdout(io.StringIO()):
                code = announce.main(["--release-json", str(release_file), "--ledger", str(Path(directory, "missing.json"))])
            self.assertEqual(code, announce.EXIT_ALREADY_POSTED)
            uploader.find_marker.assert_called_once_with("#SB987654321")
            uploader.close.assert_called_once()
            self.assertNotIn("sendMessage", bot.methods())

    def test_failed_bot_history_cannot_protect_a_partial_announcement_retry(self) -> None:
        class BotMethodInvalidError(RuntimeError):
            pass

        with tempfile.TemporaryDirectory() as directory:
            release_file = Path(directory, "release.json")
            release_file.write_text(json.dumps(release_payload(body=self.release.body)), encoding="utf-8")
            ledger = Path(directory, "ledger.json")
            bot = MockTelegram()
            bot.fail["sendMessage"] = [None, _HttpError(400, {"ok": False, "error_code": 400, "description": "rejected"})]
            patch_client(self, bot)
            env = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
                   "TELEGRAM_API_ID": "73124689", "TELEGRAM_API_HASH": "0123456789abcdef" * 2}
            uploader = unittest_mock.Mock()
            uploader.find_marker.side_effect = BotMethodInvalidError(" ".join(env.values()))
            output = io.StringIO()
            with unittest_mock.patch.dict(os.environ, env), \
                    unittest_mock.patch.object(assets, "MtprotoUploader", return_value=uploader), \
                    contextlib.redirect_stdout(output):
                first = announce.main(["--release-json", str(release_file), "--ledger", str(ledger)])
                self.assertFalse(ledger.exists(), "partial delivery must not be recorded as complete")
                second = announce.main(["--release-json", str(release_file), "--ledger", str(ledger)])
            self.assertEqual(first, announce.EXIT_ERROR)
            self.assertEqual(second, announce.EXIT_OK)
            # Page one was accepted in the first run and is sent again on retry.
            sends = bot.sent_messages()
            self.assertEqual(sends[0]["text"], sends[2]["text"])
            self.assertEqual(uploader.find_marker.call_count, 2)
            self.assertIn("otherwise be posted again", output.getvalue())
            visible = "\n".join(line for line in output.getvalue().splitlines() if not line.startswith("::add-mask::"))
            for secret in env.values():
                self.assertNotIn(secret, visible)

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

    def test_unpin_failure_does_not_fail_the_announcement(self) -> None:
        mock = MockTelegram()
        mock.fail["pinChatMessage"] = [
            _HttpError(400, {"ok": False, "error_code": 400, "description": "Bad Request: not enough rights to pin a message"})
        ]
        run = announce.send_announcement(
            announce.TelegramClient(TOKEN, CHAT, retries=1, sleeper=lambda _s: None, opener=mock.urlopen),
            self.release,
            self.announcement,
            self.pages,
        )
        self.assertFalse(run.pinned)
        self.assertEqual(len(run.sent_message_ids), len(self.pages))
        self.assertTrue(any("could not be pinned" in item for item in run.warnings))

    def test_message_id_is_required_to_confirm_delivery(self) -> None:
        mock = MockTelegram()
        mock.fail["sendMessage"] = [{"ok": True, "result": {"no_message_id": True}}]
        caught = expect_announcement_error(
            self,
            lambda: announce.send_announcement(
                announce.TelegramClient(TOKEN, CHAT, retries=1, sleeper=lambda _s: None, opener=mock.urlopen),
                self.release,
                self.announcement,
                self.pages,
            ),
        )
        self.assertIn("unconfirmed", str(caught))


# --------------------------------------------------------------------------- #
# Workflow wiring
# --------------------------------------------------------------------------- #


class WorkflowTests(unittest.TestCase):
    WORKFLOW = SCRIPTS.parent / ".github" / "workflows" / "telegram-release-announcement.yml"

    def setUp(self) -> None:
        self.text = self.WORKFLOW.read_text(encoding="utf-8")

    def test_workflow_exists_and_triggers_on_published_releases(self) -> None:
        self.assertTrue(self.WORKFLOW.is_file())
        self.assertIn("release:", self.text)
        self.assertRegex(self.text, r"release:\s*\n\s*types:\s*\[\s*published\s*\]")

    def test_workflow_has_least_privilege_permissions(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        workflow = yaml.safe_load(self.text)
        # The read-only job cannot write, while the existing ledger commit keeps
        # the contents:write token it needs in the announcement job alone.
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        self.assertEqual(workflow["jobs"]["preflight"]["permissions"], {"contents": "read"})
        self.assertEqual(workflow["jobs"]["announce"]["permissions"], {"contents": "write"})
        for job in workflow["jobs"].values():
            self.assertLessEqual(set(job.get("permissions", {})), {"contents"})
        # The workflow must not be able to change a release.
        for forbidden in ("gh release edit", "gh release create", "gh release delete", "gh release upload"):
            self.assertNotIn(forbidden, self.text)

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

    def test_the_upload_step_is_ordered_after_the_announcement_and_is_non_fatal(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        names = [step.get("name", "") for step in yaml.safe_load(self.text)["jobs"]["announce"]["steps"]]
        publish = next(i for i, name in enumerate(names) if name.startswith("Publish the announcement"))
        deliver = next(i for i, name in enumerate(names) if name.startswith("Deliver the APK"))
        record = next(i for i, name in enumerate(names) if name.startswith("Record the announced"))
        self.assertLess(publish, deliver, "the announcement must be posted before the binaries")
        self.assertLess(deliver, record, "the ledger must be committed after the delivery attempt")
        steps = {step.get("name"): step for step in yaml.safe_load(self.text)["jobs"]["announce"]["steps"]}
        # A failed upload must never turn a published release into a failed run.
        self.assertIs(steps[names[deliver]].get("continue-on-error"), True)

    def test_the_upload_step_cannot_write_to_the_repository(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        workflow = yaml.safe_load(self.text)
        self.assertEqual(workflow["jobs"]["announce"]["permissions"], {"contents": "write"})
        step = next(s for s in workflow["jobs"]["announce"]["steps"] if s.get("name", "").startswith("Deliver the APK"))
        # Downloads go to the runner temp directory, never into the checkout.
        self.assertIn("RUNNER_TEMP", step["run"])
        self.assertIn('rm -rf "${workspace}"', step["run"])

    def test_telegram_secrets_are_scoped_to_the_steps_that_use_them(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        workflow = yaml.safe_load(self.text)
        telegram = {"TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "TELEGRAM_API_ID", "TELEGRAM_API_HASH"}
        expected = {
            "Verify Telegram readiness (read-only)": telegram,
            # Do not expose the MTProto credentials to publishing merely to call
            # a user-only history method that cannot protect bot retries.
            "Publish the announcement": {"TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID"},
            "Deliver the APK and IPA files to Telegram": telegram,
        }
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
        self.assertIn("telegram-releases.json", doc)
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

    def test_a_confirmed_upload_is_recorded_and_recognised(self) -> None:
        plan = self.plans[0]
        assets.record_upload(self.path, self.release, plan, 4242, sha256="a" * 64, chat_id=CHAT)
        recorded = assets.recorded_uploads(self.path, self.release.id)
        self.assertIn(plan.asset.name, recorded)
        self.assertEqual(recorded[plan.asset.name]["message_id"], 4242)

    def test_recording_is_idempotent_per_asset(self) -> None:
        plan = self.plans[0]
        assets.record_upload(self.path, self.release, plan, 1)
        assets.record_upload(self.path, self.release, plan, 2)
        recorded = assets.recorded_uploads(self.path, self.release.id)
        self.assertEqual(len(recorded), 1)
        self.assertEqual(recorded[plan.asset.name]["message_id"], 2)

    def test_state_never_contains_a_credential(self) -> None:
        plan = self.plans[0]
        assets.record_upload(self.path, self.release, plan, 7, chat_id=CHAT)
        with open(self.path, encoding="utf-8") as handle:
            text = handle.read()
        self.assertNotIn(TOKEN, text)
        self.assertNotIn(CHAT, text)
        self.assertNotIn("api_hash", text.lower())
        self.assertNotIn("token", text.lower())

    def test_a_corrupt_ledger_is_reported_not_ignored_silently(self) -> None:
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write("{not json")
        with self.assertRaises(assets.AssetError):
            assets.load_ledger(self.path)
        # A read-only view degrades to "nothing recorded" so a rerun can proceed.
        self.assertEqual(assets.recorded_uploads(self.path, self.release.id), {})


class _RecordingTransport:
    def __init__(self, fail: set[str] | None = None) -> None:
        self.uploaded: list[tuple[str, int]] = []
        self.fail = fail or set()
        self.next_id = 700

    def upload_document(self, path: str, filename: str, caption: str) -> int:
        if filename in self.fail:
            raise assets.AssetError(f"simulated rejection of {filename}")
        self.uploaded.append((filename, os.path.getsize(path)))
        self.next_id += 1
        return self.next_id


class UploadRunTests(unittest.TestCase):
    def setUp(self) -> None:
        import tempfile

        self.dir = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))
        self.workspace = f"{self.dir}/work"
        self.ledger = f"{self.dir}/ledger.json"
        self.release = notes.Release.from_api(release_payload())
        self.body = binary_payload(150_000)
        import hashlib

        self.digest = hashlib.sha256(self.body).hexdigest()
        binaries = [
            asset["name"]
            for asset in release_payload()["assets"]
            if asset["name"].endswith((".apk", ".ipa"))
        ]
        files = {name: self.body for name in binaries}
        files["checksums.sha256"] = b"".join(
            f"{self.digest}  {name}\n".encode() for name in binaries
        )
        self.served = _ServedFiles(files)
        self.addCleanup(self.served.close)
        # Point every asset at the loopback server, keeping the API's sizes but
        # dropping the sample digest: the checksum file is the source here.
        payload = release_payload()
        for asset in payload["assets"]:
            asset["browser_download_url"] = self.served.url(asset["name"])
            asset["digest"] = ""
            if asset["name"].endswith((".apk", ".ipa")):
                asset["size"] = len(self.body)
        self.release = notes.Release.from_api(payload)

    def _run(self, transport) -> assets.UploadRun:
        return assets.upload_release_assets(
            self.release,
            transport=transport,
            workspace=self.workspace,
            ledger_path=self.ledger,
            chat_id=CHAT,
            sleeper=lambda _s: None,
        )

    def test_all_five_binaries_are_downloaded_verified_and_uploaded(self) -> None:
        transport = _RecordingTransport()
        run = self._run(transport)
        self.assertTrue(run.ok, run.records)
        self.assertEqual(len(transport.uploaded), 5)
        self.assertEqual(len(run.uploaded), 5)
        for _filename, size in transport.uploaded:
            self.assertEqual(size, len(self.body))
        # Nothing is left behind on disk.
        self.assertEqual(os.listdir(self.workspace) or [], [])
        # Every confirmed upload is in the ledger.
        self.assertEqual(len(assets.recorded_uploads(self.ledger, self.release.id)), 5)

    def test_a_rerun_uploads_nothing_again(self) -> None:
        self._run(_RecordingTransport())
        second = _RecordingTransport()
        run = self._run(second)
        self.assertEqual(second.uploaded, [])
        self.assertEqual(len(run.uploaded), 5)
        self.assertTrue(all(record.state == "already" for record in run.records))

    def test_a_partial_failure_is_retried_alone_on_the_next_run(self) -> None:
        ipa = next(plan.filename for plan in assets.plan_uploads(self.release)[0] if plan.kind == "ios")
        first = _RecordingTransport(fail={ipa})
        run = self._run(first)
        self.assertEqual(len(first.uploaded), 4)
        self.assertEqual(len(run.failed), 1)
        self.assertFalse(run.ok)
        self.assertEqual(os.listdir(self.workspace), [])

        second = _RecordingTransport()
        retry = self._run(second)
        self.assertEqual([name for name, _ in second.uploaded], [ipa])
        self.assertEqual(sum(1 for record in retry.records if record.state == "already"), 4)
        self.assertTrue(retry.ok)

    def test_an_unpersisted_ledger_can_repeat_previously_confirmed_uploads(self) -> None:
        ipa = next(plan.filename for plan in assets.plan_uploads(self.release)[0] if plan.kind == "ios")
        first = _RecordingTransport(fail={ipa})
        self._run(first)
        self.assertEqual(len(first.uploaded), 4)

        # A fresh runner cannot see the preceding runner's unpushed ledger.
        self.ledger = f"{self.dir}/fresh-runner-ledger.json"
        second = _RecordingTransport()
        retry = self._run(second)
        self.assertTrue(retry.ok)
        self.assertEqual(len(second.uploaded), 5)
        self.assertTrue(set(name for name, _ in first.uploaded) <= set(name for name, _ in second.uploaded))

    def test_upload_errors_redact_all_secrets_from_logs_and_summary(self) -> None:
        env = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": "@private-test-channel",
               "TELEGRAM_API_ID": "73124689", "TELEGRAM_API_HASH": "0123456789abcdef" * 2}

        class FailingTransport:
            def __init__(self, exception_type):
                self.exception_type = exception_type

            def upload_document(self, _path, _filename, _caption):
                raise self.exception_type("transport error: " + " ".join(env.values()))

        for exception_type in (assets.AssetError, RuntimeError):
            with self.subTest(exception_type=exception_type):
                output = io.StringIO()
                summary = f"{self.dir}/summary-{exception_type.__name__}.json"
                with unittest_mock.patch.dict(os.environ, env), contextlib.redirect_stdout(output):
                    run = self._run(FailingTransport(exception_type))
                    assets.write_summary(summary, run)
                self.assertEqual(len(run.failed), 5)
                visible = output.getvalue() + Path(summary).read_text(encoding="utf-8")
                for secret in env.values():
                    self.assertNotIn(secret, visible)
                self.assertIn("<redacted>", visible)

    def test_a_failed_asset_keeps_its_verified_github_link(self) -> None:
        transport = _RecordingTransport(fail={"androidApp-full-x86-release.apk"})
        run = self._run(transport)
        failed = run.failed[0]
        self.assertTrue(failed.url.startswith("http://127.0.0.1:"))
        self.assertEqual(failed.name, "androidApp-full-x86-release.apk")

    def test_the_temp_file_is_removed_even_when_the_upload_fails(self) -> None:
        transport = _RecordingTransport(fail={"androidApp-full-arm64-v8a-release.apk"})
        self._run(transport)
        self.assertEqual(os.listdir(self.workspace), [])

    def test_no_transport_means_no_upload_but_a_clear_failure(self) -> None:
        run = assets.upload_release_assets(
            self.release, transport=None, workspace=self.workspace, ledger_path=self.ledger
        )
        self.assertEqual(run.uploaded, [])
        self.assertEqual(len(run.failed), 5)

    def test_a_missing_secret_is_named_and_does_not_block_the_announcement(self) -> None:
        saved = {name: os.environ.pop(name, None) for name in ("TELEGRAM_API_ID", "TELEGRAM_API_HASH")}
        self.addCleanup(lambda: [os.environ.__setitem__(k, v) for k, v in saved.items() if v])
        with self.assertRaises(assets.ConfigError) as caught:
            assets.require_env("TELEGRAM_API_ID")
        self.assertIn("TELEGRAM_API_ID", str(caught.exception))
        self.assertIn("Settings -> Secrets and variables -> Actions", str(caught.exception))
        self.assertIn("announcement is still posted", str(caught.exception))

    def test_a_short_api_hash_is_rejected_before_connecting(self) -> None:
        with self.assertRaises(assets.ConfigError):
            assets.MtprotoUploader(1, "short", TOKEN, CHAT)
        with self.assertRaises(assets.ConfigError):
            assets.MtprotoUploader(0, "0" * 32, TOKEN, CHAT)


class HistoryMarkerTests(unittest.TestCase):
    def test_marker_matching_does_not_confuse_a_release_id_prefix(self) -> None:
        import asyncio
        from types import SimpleNamespace

        class HypotheticalHistoryClient:
            def __init__(self):
                self.loop = asyncio.new_event_loop()
                self.messages = ["old #SB1234", "old #SB123suffix"]

            async def get_entity(self, _chat):
                return object()

            async def iter_messages(self, _entity, limit):
                for text in self.messages[:limit]:
                    yield SimpleNamespace(message=text)

            def disconnect(self):
                pass

        fake = HypotheticalHistoryClient()
        uploader = assets.MtprotoUploader(12345, "0" * 32, TOKEN, CHAT)
        uploader._client = fake
        self.addCleanup(fake.loop.close)
        self.addCleanup(uploader.close)
        self.assertFalse(uploader.find_marker("#SB123"))
        fake.messages.append("first page #SB123\n")
        self.assertTrue(uploader.find_marker("#SB123"))


@unittest.skipUnless(telethon is not None, "Telethon is required for the upload transport tests")
class MtprotoTransportTests(unittest.TestCase):
    """The real Telethon client, driven over a stubbed RPC layer.

    These tests keep Telethon's own ``upload_file`` and ``send_file`` -- the
    chunk loop, the ``upload.saveBigFilePart`` requests, the filename attributes
    and the response parsing -- and only replace the socket. That is the layer
    where a real upload actually breaks.
    """

    def setUp(self) -> None:
        import tempfile

        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from fake_telegram_rpc import FakeRpcServer, make_client

        self.server = FakeRpcServer()
        self.client = make_client(self.server)
        self.temp = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.temp, ignore_errors=True))
        self.payload = binary_payload(12 * 1024 * 1024 + 777)
        self.path = f"{self.temp}/androidApp-full-arm64-v8a-release.apk"
        with open(self.path, "wb") as handle:
            handle.write(self.payload)

    def _uploader(self, sleeper=lambda _s: None, **kwargs) -> assets.MtprotoUploader:
        uploader = assets.MtprotoUploader(
            12345, "0" * 32, TOKEN, CHAT, sleeper=sleeper, **kwargs
        )
        uploader._client = self.client
        self.addCleanup(uploader.close)
        return uploader

    def test_a_12_mib_file_is_uploaded_completely_in_512_kib_parts(self) -> None:
        uploader = self._uploader()
        message_id = uploader.upload_document(self.path, "app.apk", "caption")
        self.assertIsInstance(message_id, int)
        file_id = self.server.last_upload_file_id
        self.assertEqual(self.server.uploaded_bytes(file_id), self.payload)
        sizes = [len(part) for index, part in sorted(self.server.parts[file_id].items())]
        # Every part but the last is exactly 512 KiB, and the parts sum to the
        # file: nothing was dropped and nothing was padded.
        self.assertEqual(set(sizes[:-1]), {assets_512k})
        self.assertLessEqual(sizes[-1], assets_512k)
        self.assertEqual(sum(sizes), len(self.payload))

    def test_files_over_10_mib_use_the_big_file_rpc(self) -> None:
        uploader = self._uploader()
        uploader.upload_document(self.path, "app.apk", "caption")
        self.assertIn("SaveBigFilePartRequest", self.client.rpc_log)

    def test_the_filename_and_document_type_are_preserved(self) -> None:
        uploader = self._uploader()
        uploader.upload_document(self.path, "StreamBridge-9.9.9-iOS-unsigned.ipa", "cap")
        self.assertEqual(self.server.last_document_name, "StreamBridge-9.9.9-iOS-unsigned.ipa")
        # Uploaded as a document (never re-encoded, never an album), to the channel.
        self.assertEqual(self.server.send_media[-1]["media_type"], "InputMediaUploadedDocument")
        self.assertEqual(self.server.send_media[-1]["peer"], "PeerChannel")
        self.assertNotIn("UploadMediaRequest", self.client.rpc_log)

    def test_a_network_drop_mid_upload_is_retried_and_the_file_still_arrives(self) -> None:
        self.server.fail_after_parts = 5
        uploader = self._uploader()
        self.assertIsInstance(uploader.upload_document(self.path, "app.apk", "cap"), int)
        self.assertEqual(self.server.uploaded_bytes(self.server.last_upload_file_id), self.payload)

    def test_a_lost_send_media_response_can_duplicate_an_upload(self) -> None:
        original = self.server._send_media
        calls = {"count": 0}

        def accept_then_drop_response(request):
            calls["count"] += 1
            response = original(request)
            if calls["count"] == 1:
                raise TimeoutError("response lost after Telegram accepted the document")
            return response

        self.server._send_media = accept_then_drop_response
        uploader = self._uploader()
        self.assertIsInstance(uploader.upload_document(self.path, "app.apk", "cap"), int)
        self.assertEqual(len(self.server.send_media), 2)
        self.assertEqual([item["filename"] for item in self.server.send_media], ["app.apk", "app.apk"])

    def test_flood_wait_is_honoured(self) -> None:
        from telethon.errors import FloodWaitError

        original = self.server._send_media
        calls = {"count": 0}

        def flood(request):
            calls["count"] += 1
            if calls["count"] == 1:
                raise FloodWaitError(request=None, capture=9)
            return original(request)

        self.server._send_media = flood
        waits: list[float] = []
        uploader = self._uploader(sleeper=waits.append)
        self.assertIsInstance(uploader.upload_document(self.path, "app.apk", "cap"), int)
        self.assertEqual(waits, [9.0])

    def test_a_permanent_rejection_is_not_retried(self) -> None:
        from telethon.errors import ChatAdminRequiredError

        class Rejected(ChatAdminRequiredError):
            def __init__(self) -> None:
                Exception.__init__(self, "CHAT_ADMIN_REQUIRED")

        attempts = {"count": 0}

        def reject(_request):
            attempts["count"] += 1
            raise Rejected()

        self.server._send_media = reject
        uploader = self._uploader()
        with self.assertRaises(assets.AssetError):
            uploader.upload_document(self.path, "app.apk", "cap")
        self.assertEqual(attempts["count"], 1)

    def test_an_unconfirmed_send_is_a_failure(self) -> None:
        from telethon.tl import types

        self.server._send_media = lambda _request: types.Updates(
            updates=[], users=[], chats=[], date=None, seq=0
        )
        uploader = self._uploader()
        with self.assertRaises(assets.AssetError) as caught:
            uploader.upload_document(self.path, "app.apk", "cap")
        self.assertIn("unconfirmed", str(caught.exception))

    def test_the_bot_token_never_appears_in_a_repr_or_a_log(self) -> None:
        uploader = self._uploader()
        self.assertNotIn(TOKEN, repr(uploader))
        self.assertNotIn(TOKEN.split(":")[1], repr(uploader))
        self.assertNotIn(CHAT, repr(uploader))
        self.assertNotIn("12345", repr(uploader))
        self.assertNotIn("0" * 32, repr(uploader))

    def test_a_file_over_the_ceiling_is_refused_before_upload(self) -> None:
        uploader = self._uploader()
        original = assets.UPLOAD_MAX_BYTES
        assets.UPLOAD_MAX_BYTES = 1024
        self.addCleanup(setattr, assets, "UPLOAD_MAX_BYTES", original)
        with self.assertRaises(assets.AssetError):
            uploader.upload_document(self.path, "app.apk", "cap")

    def test_only_a_bot_token_is_used_for_authentication(self) -> None:
        # start(bot_token=...) must go through auth.importBotAuthorization and
        # never ask for a phone number, a login code or a 2FA password.
        from telethon.tl import types

        from fake_telegram_rpc import AuthStub

        stub = AuthStub()
        stub.loop.run_until_complete(
            stub._start(None, None, TOKEN, False, None, "s", "b", 3)
        )
        self.assertIn("ImportBotAuthorizationRequest", stub.seen)
        self.assertNotIn("SendCodeRequest", stub.seen)
        self.assertNotIn("SignInRequest", stub.seen)


assets_512k = 512 * 1024


# --------------------------------------------------------------------------- #
# Regressions found in the integration audit
# --------------------------------------------------------------------------- #


class ChecksumCoverageTests(unittest.TestCase):
    """The IPA must be digested even when it is the first asset in a release.

    The loader used to be gated on the asset kind, so a release whose checksum
    file covered the IPA but whose first asset was iOS skipped the IPA's digest
    entirely and verified it by size alone.
    """

    def setUp(self) -> None:
        import hashlib
        import tempfile

        self.dir = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))
        self.body = binary_payload(120_000)
        self.digest = hashlib.sha256(self.body).hexdigest()

    def test_the_ipa_digest_is_checked_on_an_ios_only_release(self) -> None:
        import hashlib

        served = _ServedFiles({"app.ipa": self.body, "checksums.sha256": f"{self.digest}  app.ipa\n".encode()})
        self.addCleanup(served.close)
        release = notes.Release(
            id=5,
            tag_name="v1.0.0",
            name="StreamBridge v1.0.0",
            body="## What's new\n\n- Something.\n",
            html_url="https://github.com/sascio/MyBridge/releases/tag/v1.0.0",
            assets=(
                notes.Asset("StreamBridge-1.0.0-iOS-unsigned.ipa", len(self.body), served.url("app.ipa")),
                notes.Asset("checksums.sha256", len(self.digest) + 10, served.url("checksums.sha256")),
            ),
        )
        transport = _RecordingTransport()
        run = assets.upload_release_assets(
            release,
            transport=transport,
            workspace=f"{self.dir}/work",
            ledger_path=f"{self.dir}/ledger.json",
            sleeper=lambda _s: None,
        )
        self.assertTrue(run.ok, run.records)
        # The digest from the checksum file must have been applied to the IPA.
        self.assertEqual(run.records[0].state, "uploaded")

    def test_a_corrupt_ipa_is_refused_even_when_it_is_uploaded_first(self) -> None:
        served = _ServedFiles({"app.ipa": self.body, "checksums.sha256": f"{'0' * 64}  app.ipa\n".encode()})
        self.addCleanup(served.close)
        release = notes.Release(
            id=6,
            tag_name="v1.0.0",
            name="n",
            body="body",
            html_url="https://x/y",
            assets=(
                notes.Asset("app.ipa", len(self.body), served.url("app.ipa")),
                notes.Asset("checksums.sha256", 80, served.url("checksums.sha256")),
            ),
        )
        transport = _RecordingTransport()
        run = assets.upload_release_assets(
            release,
            transport=transport,
            workspace=f"{self.dir}/work",
            ledger_path=f"{self.dir}/ledger.json",
            sleeper=lambda _s: None,
        )
        self.assertEqual(transport.uploaded, [], "a file failing its digest must never be uploaded")
        self.assertEqual(len(run.failed), 1)
        self.assertIn("digest mismatch", run.failed[0].detail)


class PinningPolicyTests(unittest.TestCase):
    """Pinning is opt-in, so the bot only needs 'Post Messages'."""

    def _args(self, *extra: str):
        return announce.parse_args(["--release-json", "x", *extra])

    def test_pinning_is_off_by_default(self) -> None:
        self.assertFalse(self._args().pin)
        self.assertFalse(self._args("--pin").pin is False)

    def test_announcement_send_without_pinning_never_calls_pin_chat_message(self) -> None:
        release = notes.Release.from_api(release_payload())
        announcement, pages = announce.build_pages(release)
        mock = MockTelegram()
        patch_client(self, mock)
        run = announce.send_announcement(
            announce.TelegramClient(TOKEN, CHAT),
            release,
            announcement,
            pages,
            pin=False,
            throttle=lambda _s: None,
        )
        self.assertFalse(run.pinned)
        self.assertNotIn("pinChatMessage", [method for method, _ in mock.calls])


class PreflightTests(unittest.TestCase):
    """The read-only check must never post, upload or pin."""

    def test_preflight_issues_only_read_calls(self) -> None:
        release = notes.Release.from_api(release_payload())
        mock = MockTelegram()
        patch_client(self, mock)
        code = announce.run_preflight(release, "@StreamBridge_App", TOKEN)
        methods = {method for method, _ in mock.calls}
        self.assertIn("getChat", methods)
        for forbidden in ("sendMessage", "pinChatMessage", "sendDocument"):
            self.assertNotIn(forbidden, methods)
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
        import contextlib
        import io

        release = notes.Release.from_api(release_payload())
        mock = MockTelegram()
        patch_client(self, mock)
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            announce.run_preflight(release, "@StreamBridge_App", TOKEN)
        output = buffer.getvalue()
        # The one permitted appearance is GitHub's own masking directive, which
        # is what keeps the value out of every later log line.
        without_masks = "\n".join(
            line for line in output.splitlines() if not line.startswith("::add-mask::")
        )
        self.assertNotIn(TOKEN, without_masks)
        self.assertNotIn(TOKEN.split(":")[1], without_masks)


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
        path = SCRIPTS.parent / ".github/workflows/telegram-release-announcement.yml"
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

    def test_preflight_input_and_job_are_isolated_and_least_privileged(self) -> None:
        # PyYAML's YAML 1.1 parser reads the key `on` as True.
        triggers = self.workflow.get("on", self.workflow.get(True))
        self.assertEqual(triggers["release"], {"types": ["published"]})
        input_spec = triggers["workflow_dispatch"]["inputs"]["preflight"]
        self.assertEqual(input_spec["type"], "boolean")
        self.assertIs(input_spec["default"], False)
        self.assertNotIn("needs", self.preflight)
        self.assertEqual(self.preflight["permissions"], {"contents": "read"})
        checkout, setup, install, verify = self.preflight["steps"]
        self.assertRegex(checkout["uses"], r"^actions/checkout@[0-9a-f]{40}$")
        self.assertRegex(setup["uses"], r"^actions/setup-python@[0-9a-f]{40}$")
        self.assertEqual(checkout["with"]["ref"], "${{ github.event.repository.default_branch }}")
        self.assertIs(checkout["with"]["persist-credentials"], False)
        self.assertEqual(setup["with"]["python-version"], "3.12")
        for dependency in ("telethon==1.42.0", "pyaes==1.6.1", "rsa==4.9.1", "pyasn1==0.6.1"):
            self.assertIn(dependency, install["run"])
        for job_or_step in (self.preflight, checkout, setup, install):
            self.assertFalse(set((job_or_step.get("env") or {})) & set(self.SECRETS))
        for secret in self.SECRETS:
            self.assertEqual(verify["env"][secret], "${{ secrets." + secret + " }}")
        self.assertFalse(any("GH_TOKEN" in (part.get("env") or {}) for part in (self.preflight, *self.preflight["steps"])))

    def test_preflight_takes_precedence_over_all_other_inputs_and_guards_mutating_steps(self) -> None:
        steps = {step.get("id", step.get("name")): step for step in self.announce_job["steps"]}
        guards = [steps["publish"]["if"], steps["upload"]["if"], steps["Record the announced release"]["if"]]
        for preflight, dry_run, tag, upload in itertools.product((False, True), (False, True), ("", "v9.9.9"), (False, True)):
            context = {
                "github.event_name": "workflow_dispatch", "inputs.preflight": preflight,
                "inputs.dry_run": dry_run, "inputs.tag": tag, "inputs.upload_assets": upload,
                "github.event.release.draft": None, "github.event.release.prerelease": None,
                "steps.release.outputs.skip": "false", "steps.release.outputs.dry_run": "false",
                "steps.publish.outputs.announced": "true",
            }
            with self.subTest(preflight=preflight, dry_run=dry_run, tag=tag, upload=upload):
                self.assertEqual(evaluate_actions_if(self.preflight["if"], context), preflight)
                self.assertEqual(evaluate_actions_if(self.announce_job["if"], context), not preflight)
                if preflight:
                    for condition in guards:
                        self.assertFalse(evaluate_actions_if(condition, context), condition)
        run = self.step["run"]
        command = next(line.strip() for line in run.splitlines() if line.strip().startswith("python3 scripts/telegram_announce.py"))
        self.assertEqual(command, 'python3 scripts/telegram_announce.py --release-json "${release_json}" --preflight')
        self.assertNotIn("--dry-run", command)
        self.assertNotIn("--ledger", command)
        self.assertNotIn("--pin", command)
        for forbidden in ("gh api", "gh release", "git push", "git commit", "telegram_assets.py", "releases/latest"):
            self.assertNotIn(forbidden, run)
        group = self.workflow["concurrency"]["group"]
        self.assertIn("format('telegram-preflight-{0}', github.run_id)", group)
        self.assertIn("format('telegram-announcement-{0}', github.event.release.id || inputs.tag || 'latest')", group)

    def test_non_preflight_dispatch_preserves_dry_run_upload_and_ledger_gates(self) -> None:
        steps = {step.get("id", step.get("name")): step for step in self.announce_job["steps"]}
        for dry_run, upload, skip in itertools.product((False, True), repeat=3):
            context = {
                "github.event_name": "workflow_dispatch", "inputs.preflight": False,
                "inputs.upload_assets": upload, "github.event.release.draft": None,
                "github.event.release.prerelease": None,
                "steps.release.outputs.skip": "true" if skip else "false",
                "steps.release.outputs.dry_run": "true" if dry_run else "false",
                "steps.publish.outputs.announced": "false" if skip else "true",
            }
            with self.subTest(dry_run=dry_run, upload=upload, skip=skip):
                self.assertTrue(evaluate_actions_if(self.announce_job["if"], context))
                self.assertEqual(evaluate_actions_if(steps["publish"]["if"], context), not skip)
                self.assertEqual(evaluate_actions_if(steps["upload"]["if"], context), not (skip or dry_run) and upload)
                self.assertEqual(evaluate_actions_if(steps["Record the announced release"]["if"], context), not (skip or dry_run))

    def test_published_release_behavior_and_filters_are_preserved(self) -> None:
        steps = {step.get("id", step.get("name")): step for step in self.announce_job["steps"]}
        for draft, prerelease in itertools.product((False, True), repeat=2):
            context = {
                "github.event_name": "release", "inputs.preflight": None, "inputs.upload_assets": None,
                "github.event.release.draft": draft, "github.event.release.prerelease": prerelease,
                "steps.release.outputs.skip": "false", "steps.release.outputs.dry_run": "false",
                "steps.publish.outputs.announced": "true",
            }
            with self.subTest(draft=draft, prerelease=prerelease):
                self.assertFalse(evaluate_actions_if(self.preflight["if"], context))
                self.assertEqual(evaluate_actions_if(self.announce_job["if"], context), not (draft or prerelease))
                if not (draft or prerelease):
                    for key in ("publish", "upload", "Record the announced release"):
                        self.assertTrue(evaluate_actions_if(steps[key]["if"], context), key)
        resolve = steps["release"]["run"]
        self.assertIn('if [[ "${EVENT_NAME}" == "release" ]]; then', resolve)
        self.assertIn('gh api "repos/${GITHUB_REPOSITORY}/releases/${EVENT_RELEASE_ID}"', resolve)
        self.assertIn('echo "dry_run=false"', resolve)

    def test_existing_release_resolution_and_dry_run_behavior_with_mock_gh(self) -> None:
        resolve = next(step["run"] for step in self.announce_job["steps"] if step.get("id") == "release")
        cases = (
            ("release", "true", "ignored", "false", "api repos/sascio/MyBridge/releases/987654321", "false", "false"),
            ("release", "true", "ignored", "true", None, "false", "true"),
            ("workflow_dispatch", "true", "v9.9.9", "false", "api repos/sascio/MyBridge/releases/tags/v9.9.9", "true", "false"),
            ("workflow_dispatch", "false", "", "false", "api repos/sascio/MyBridge/releases/latest", "false", "false"),
        )
        for event, dry_run, tag, prerelease, expected_call, expected_dry_run, expected_skip in cases:
            with self.subTest(event=event, dry_run=dry_run, tag=tag, prerelease=prerelease), tempfile.TemporaryDirectory() as directory:
                temp = Path(directory)
                bin_dir = temp / "bin"
                bin_dir.mkdir()
                (bin_dir / "python3").symlink_to(sys.executable)
                gh = bin_dir / "gh"
                gh.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$GH_TEST_TRACE"\ncat "$GH_TEST_RELEASE"\n', encoding="utf-8")
                gh.chmod(0o755)
                trace, payload, output = temp / "gh-trace", temp / "payload.json", temp / "output"
                payload.write_text(json.dumps(release_payload()), encoding="utf-8")
                output.touch()
                env = os.environ.copy()
                env.pop("GH_TOKEN", None)
                env.pop("GITHUB_TOKEN", None)
                env.update({
                    "PATH": f"{bin_dir}{os.pathsep}{env.get('PATH', '')}",
                    "GH_TEST_TRACE": str(trace), "GH_TEST_RELEASE": str(payload),
                    "GITHUB_OUTPUT": str(output), "RUNNER_TEMP": str(temp),
                    "GITHUB_REPOSITORY": "sascio/MyBridge", "EVENT_NAME": event,
                    "EVENT_RELEASE_ID": "987654321", "EVENT_RELEASE_TAG": "v9.9.9",
                    "EVENT_RELEASE_DRAFT": "false", "EVENT_RELEASE_PRERELEASE": prerelease,
                    "INPUT_TAG": tag, "DRY_RUN_INPUT": dry_run,
                })
                result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", resolve],
                                        cwd=SCRIPTS.parent, env=env, capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                outputs = dict(line.split("=", 1) for line in output.read_text().splitlines())
                self.assertEqual(outputs["dry_run"], expected_dry_run)
                self.assertEqual(outputs["skip"], expected_skip)
                calls = trace.read_text().splitlines() if trace.exists() else []
                self.assertEqual(calls, [expected_call] if expected_call else [])

    def test_hypothetical_history_success_still_calls_only_reads(self) -> None:
        # Exercise the shell's success branch without pretending that Telegram
        # actually permits a bot to call messages.getHistory.
        result, events, summary, fixture = self._run_step({"FAKE_MTPROTO_MODE": "hypothetical_history_success"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("**Passed:**", summary)
        self.assertEqual(fixture["id"], 0)
        self.assertEqual(fixture["assets"], [])
        self.assertFalse(fixture["draft"] or fixture["prerelease"])
        self.assertEqual(fixture["html_url"], "https://example.invalid/preflight")
        bot_calls = [event for event in events if event["event"] == "bot_api"]
        self.assertEqual([event["method"] for event in bot_calls], ["getMe", "getChat", "getChatMember", "getChat"])
        self.assertTrue(all(event["token_matches"] and event["chat_matches"] for event in bot_calls))
        self.assertEqual(bot_calls[2]["user_id"], 987654)
        uploader = next(event for event in events if event["event"] == "mtproto_uploader")
        self.assertTrue(all(uploader[key] for key in ("api_id_matches", "api_hash_matches", "token_matches", "chat_matches")))
        self.assertTrue(next(event for event in events if event["event"] == "mtproto_login")["token_matches"])
        self.assertTrue(next(event for event in events if event["event"] == "mtproto_get_entity")["chat_matches"])
        self.assertEqual(next(event for event in events if event["event"] == "mtproto_history")["limit"], 100)
        self.assertTrue(any(event["event"] == "mtproto_disconnect" for event in events))
        self.assertIn("Administrator status and Post Messages permission verified", result.stdout)
        visible = "\n".join(line for line in (result.stdout + result.stderr).splitlines() if not line.startswith("::add-mask::"))
        for secret in self.SECRETS.values():
            self.assertIn(f"::add-mask::{secret}", result.stdout)
            self.assertNotIn(secret, visible)
            self.assertNotIn(secret, summary)

    def test_bot_history_restriction_fails_closed_without_side_effects(self) -> None:
        result, events, summary, _fixture = self._run_step()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Telegram does not allow bot accounts", result.stdout)
        self.assertIn("**Failed:**", summary)
        self.assertNotIn("Preflight passed:", result.stdout)
        self.assertTrue(any(event["event"] == "mtproto_history" for event in events))
        self.assertTrue(any(event["event"] == "mtproto_disconnect" for event in events))
        self.assertFalse([event for event in events if event["event"] in {"forbidden", "ledger_access"}])

    def test_mtproto_exception_does_not_expose_any_secret(self) -> None:
        result, _events, summary, _fixture = self._run_step({"FAKE_MTPROTO_MODE": "history_secret_failure"})
        self.assertNotEqual(result.returncode, 0)
        visible = "\n".join(line for line in (result.stdout + result.stderr).splitlines()
                            if not line.startswith("::add-mask::"))
        for secret in self.SECRETS.values():
            self.assertNotIn(secret, visible)
            self.assertNotIn(secret, summary)

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

    def test_invalid_mtproto_credentials_or_history_fail_closed(self) -> None:
        cases = (
            ({"TELEGRAM_API_ID": "invalid"}, "MTProto credentials are unusable"),
            ({"TELEGRAM_API_HASH": "short"}, "MTProto credentials are unusable"),
            ({"FAKE_MTPROTO_MODE": "auth_failure"}, "MTProto check failed"),
            ({"FAKE_MTPROTO_MODE": "history_failure"}, "MTProto check failed"),
        )
        for overrides, message in cases:
            with self.subTest(overrides=overrides):
                result, _events, summary, _fixture = self._run_step(overrides)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stdout)
                self.assertIn("**Failed:**", summary)


@unittest.skipUnless(telethon is not None, "Telethon is required for the read-only RPC test")
class PreflightRpcTests(unittest.TestCase):
    def test_real_bot_auth_and_user_only_history_rpc_fail_without_mutating_calls(self) -> None:
        import asyncio
        from telethon.errors import BotMethodInvalidError
        from telethon.tl import functions, types
        from fake_telegram_rpc import AuthStub

        class HistoryClient(AuthStub):
            async def get_entity(self, _chat):
                return types.InputPeerChannel(channel_id=123, access_hash=456)

            async def get_input_entity(self, entity):
                return entity

            async def __call__(self, request, ordered=False, flood_sleep_threshold=None):
                if isinstance(request, functions.messages.GetHistoryRequest):
                    self.seen.append(type(request).__name__)
                    # Follow Telegram's documented bot restriction rather than
                    # faking an empty successful response to this user-only RPC.
                    raise BotMethodInvalidError(request=request)
                if isinstance(request, (
                    functions.auth.ImportBotAuthorizationRequest,
                    functions.updates.GetStateRequest,
                    functions.updates.GetDifferenceRequest,
                )):
                    return await super().__call__(request, ordered, flood_sleep_threshold)
                self.seen.append("FORBIDDEN:" + type(request).__name__)
                raise AssertionError(f"unexpected MTProto RPC: {type(request).__name__}")

        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        self.addCleanup(asyncio.set_event_loop, None)
        self.addCleanup(loop.close)
        rpc = HistoryClient()
        bot = MockTelegram()
        patch_client(self, bot)
        real_uploader = assets.MtprotoUploader
        init_args = []

        def uploader_factory(api_id, api_hash, token, chat_id):
            init_args.append((api_id, api_hash, token, chat_id))
            return real_uploader(api_id, api_hash, token, chat_id, client_factory=lambda *_a, **_kw: rpc)

        with tempfile.TemporaryDirectory() as directory:
            release_file = Path(directory, "release.json")
            release_file.write_text(json.dumps(release_payload()), encoding="utf-8")
            ledger = Path(directory, "ledger.json")
            summary = Path(directory, "summary.json")
            env = {"TELEGRAM_BOT_TOKEN": TOKEN, "TELEGRAM_CHAT_ID": CHAT,
                   "TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": "0" * 32}
            output = io.StringIO()
            with unittest_mock.patch.dict(os.environ, env), \
                    unittest_mock.patch.object(assets, "MtprotoUploader", side_effect=uploader_factory), \
                    unittest_mock.patch.object(real_uploader, "upload_document", side_effect=AssertionError("upload forbidden")), \
                    unittest_mock.patch.object(real_uploader, "_send", side_effect=AssertionError("send forbidden")), \
                    unittest_mock.patch.object(assets, "upload_release_assets", side_effect=AssertionError("upload forbidden")), \
                    unittest_mock.patch.object(assets, "record_upload", side_effect=AssertionError("ledger forbidden")), \
                    unittest_mock.patch.object(announce, "send_announcement", side_effect=AssertionError("send forbidden")), \
                    unittest_mock.patch.object(announce, "record_in_ledger", side_effect=AssertionError("ledger forbidden")), \
                    unittest_mock.patch.object(announce, "load_ledger", side_effect=AssertionError("ledger forbidden")), \
                    unittest_mock.patch.object(announce, "write_summary", side_effect=AssertionError("summary forbidden")), \
                    contextlib.redirect_stdout(output):
                code = announce.main(["--release-json", str(release_file), "--preflight", "--pin",
                                      "--ledger", str(ledger), "--summary", str(summary)])
            self.assertEqual(code, announce.EXIT_ERROR, output.getvalue())
            self.assertIn("Telegram does not allow bot accounts", output.getvalue())
            self.assertFalse(ledger.exists())
            self.assertFalse(summary.exists())
        self.assertEqual(init_args, [(12345, "0" * 32, TOKEN, CHAT)])
        self.assertEqual(bot.methods(), ["getChat"])
        self.assertIn("ImportBotAuthorizationRequest", rpc.seen)
        self.assertIn("GetHistoryRequest", rpc.seen)
        self.assertTrue(set(rpc.seen) <= {"ImportBotAuthorizationRequest", "GetStateRequest",
                                         "GetDifferenceRequest", "GetHistoryRequest"}, rpc.seen)
        for forbidden in ("SendMessageRequest", "SendMediaRequest", "SendMultiMediaRequest",
                          "SaveFilePartRequest", "SaveBigFilePartRequest", "UploadMediaRequest",
                          "UpdatePinnedMessageRequest"):
            self.assertNotIn(forbidden, rpc.seen)


class WorkflowRefTests(unittest.TestCase):
    def test_checkout_uses_the_default_branch_not_the_release_tag(self) -> None:
        """A release event checks out the tag; the tooling must come from main.

        Otherwise every future release would run the scripts frozen at its own
        tag, and a fix to the announcement could never reach a release.
        """
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        workflow = yaml.safe_load((SCRIPTS.parent / ".github/workflows/telegram-release-announcement.yml").read_text(encoding="utf-8"))
        checkout = next(
            step for step in workflow["jobs"]["announce"]["steps"] if "checkout" in str(step.get("uses", ""))
        )
        self.assertEqual(checkout["with"]["ref"], "${{ github.event.repository.default_branch }}")
        # The ledger commit needs a token, so credentials must be persisted.
        self.assertIs(checkout["with"]["persist-credentials"], True)

    def test_the_announcement_step_does_not_request_pin_rights(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")
        workflow = yaml.safe_load((SCRIPTS.parent / ".github/workflows/telegram-release-announcement.yml").read_text(encoding="utf-8"))
        publish = next(
            step for step in workflow["jobs"]["announce"]["steps"] if step.get("name") == "Publish the announcement"
        )
        self.assertNotIn("--pin", publish["run"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
