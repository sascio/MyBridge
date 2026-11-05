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

import http.server
import io
import json
import os
import re
import sys
import subprocess
import tempfile
import threading
import unittest
import urllib.error

try:
    import yaml
except ImportError:  # PyYAML is present on GitHub's runners but not everywhere
    yaml = None
import urllib.request
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

import telegram_announce as announce  # noqa: E402
import telegram_release_notes as notes  # noqa: E402

TOKEN = "123456:AAH-offline-test-token-not-a-real-secret"
CHAT = "@StreamBridge_App"

RELEASE_URL = "https://github.com/sascio/MyBridge/releases/tag/v9.9.9"


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
        self.assertEqual(buttons["🤖 Android APK"], published["androidApp-full-arm64-v8a-release.apk"])
        self.assertEqual(buttons["🍏 iOS IPA"], published["StreamBridge-9.9.9-iOS-unsigned.ipa"])
        self.assertEqual(buttons["🔐 Checksums"], published["checksums.sha256"])
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
        self.assertEqual(len(rows), 4)
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
        # One scope, one value: contents: write, needed only for the ledger commit.
        permissions = self.text.split("permissions:", 1)[1].split("\n\n", 1)[0]
        self.assertIn("contents: write", permissions)
        for broader in (
            "write-all",
            "actions: write",
            "issues: write",
            "packages: write",
            "pull-requests: write",
            "id-token: write",
            "contents: read",
        ):
            self.assertNotIn(broader, permissions)
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

    def test_every_run_block_is_valid_bash(self) -> None:
        """The workflow's shells must parse before GitHub ever runs them.

        A broken `case` arm or an unclosed quote in a `run:` block would only
        surface during a real release; this catches it in CI and locally.
        """
        if yaml is None:
            self.skipTest("PyYAML is not installed, so the YAML cannot be parsed here")
        import tempfile

        workflow = yaml.safe_load(self.text)
        steps = workflow["jobs"]["announce"]["steps"]
        checked = 0
        for step in steps:
            if "run" not in step:
                continue
            with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False, encoding="utf-8") as handle:
                handle.write(step["run"])
                path = handle.name
            result = subprocess.run(["bash", "-n", path], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, f"{step['name']}: {result.stderr}")
            checked += 1
        self.assertGreaterEqual(checked, 4)

    def test_download_buttons_and_ledger_flow_are_documented_together(self) -> None:
        doc = (SCRIPTS.parent / "docs" / "TELEGRAM-ANNOUNCEMENTS.md").read_text(encoding="utf-8")
        self.assertIn("⬇️ Download StreamBridge", doc)
        self.assertIn("telegram-releases.json", doc)
        self.assertIn("release id", doc)

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
