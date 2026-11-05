#!/usr/bin/env python3
"""Render a published StreamBridge GitHub Release as StreamBridge Telegram messages.

This module is deliberately pure: it takes a GitHub release object (the JSON the
REST API returns) and produces the exact HTML message pages and inline keyboard
used by the durable bot-only MTProto publisher. No network access, no environment
access, no secrets. That is what makes the
formatting, escaping, splitting and asset-detection rules testable offline
(``scripts/tests/telegram-announcement-test.py``).

Design rules, in order of precedence:

  * Nothing is invented. Version, version code, release notes, asset names,
    asset sizes and every URL come from the release object. A download link is
    only ever an asset's own ``browser_download_url`` or the release's
    ``html_url``; no asset URL is ever assembled from a pattern.
  * StreamBridge branding only. Upstream product names are scrubbed out of the
    notes text, and the fixed StreamBridge header, motto and footer are always
    present.
  * Markdown is converted, never dumped. The raw GitHub Markdown is parsed into
    sections, bullets, paragraphs and code, then rendered as Telegram HTML.
  * Messages never exceed Telegram's 4096-character limit (measured the way
    Telegram measures it: UTF-16 code units of the visible text after tags are
    parsed away), never truncate content, and keep every HTML tag balanced.
"""

from __future__ import annotations

import html
import re
from dataclasses import dataclass, field
from typing import Sequence

PRODUCT_NAME = "StreamBridge"
MOTTO = "Your Media, Your Way"
FOOTER = "Made with ❤️ by Saif"
DOWNLOAD_BUTTON_LABEL = "⬇️ Download StreamBridge"

# Telegram Bot API: sendMessage text is "1-4096 characters after entities
# parsing". The packing target leaves headroom, and the hard limit is checked
# again on every finished page.
TELEGRAM_TEXT_LIMIT = 4096
PAGE_TARGET = 3800

ALLOWED_TAGS = frozenset({"b", "strong", "i", "em", "u", "s", "code", "pre", "a", "blockquote"})

# Canonical section order and titles for the announcement.
SECTION_TITLES: tuple[tuple[str, str], ...] = (
    ("whats_new", "✨ What's New"),
    ("appearance", "🎨 Appearance & Glass UI"),
    ("player", "🎬 Player & Playback"),
    ("subtitles", "💬 Subtitles & Audio"),
    ("progress", "⏯ Continue Watching"),
    ("live_tv", "📡 Live TV"),
    ("ios", "🍏 iOS Improvements"),
    ("translations", "🌍 Translations"),
    ("core", "🧩 Core Updates"),
    ("streambridge", "💙 StreamBridge Improvements"),
    ("downloads", "📦 Downloads"),
    ("install", "📲 Installation Notes"),
)
SECTION_ORDER = tuple(key for key, _ in SECTION_TITLES)
SECTION_TITLE_BY_KEY = dict(SECTION_TITLES)

# Route a release-notes heading to a canonical section. Order matters: the most
# specific routes are checked first, so "iOS installation" lands in Installation
# Notes rather than in iOS Improvements, and a download-size table never becomes
# a feature bullet.
_SECTION_ROUTES: tuple[tuple[str, str], ...] = (
    (
        "install",
        r"\binstall|\baltstore\b|\bsidestore\b|\bsideload\b|\bupgrad|\bverification\b"
        r"|\bsame-version\b|\bupdater\b|\bknown issue|\bandroid\b|\bapk\b",
    ),
    ("downloads", r"\bdownload|\bsizes?\b|\bassets?\b|\bchecksums?\b|\bverification\b"),
    (
        "streambridge",
        r"\bstreambridge|\bbranding\b|\bcommunity\b|\bdiscord\b|\btelegram\b|\bidentity\b|\babout us\b",
    ),
    (
        "appearance",
        r"\bappearance|\bglass\b|\btheme|\btheming\b|\bdesign\b|\bvisual|\blayout\b"
        r"|\bbackground\b|\bhero\b|\bui\b",
    ),
    ("subtitles", r"\bsubtitle|\baudio\b|\btracks?\b|\bsound\b|\bdub"),
    ("player", r"\bplayer\b|\bplayback\b|\bvideo\b|\bengine\b|\bstream|\bskip\b|\bwatch party\b"),
    (
        "progress",
        r"\bcontinue watching|\bwatch progress|\bprogress\b|\bresume\b|\bhistory\b|\blibrary\b",
    ),
    ("live_tv", r"\blive tv|\blive\b|\btv\b|\bepg\b"),
    ("ios", r"\bios\b|\biphone\b|\bipad\b|\bapple\b|\bmpvkit\b"),
    ("translations", r"\btranslation|\blocali[sz]|\blocales?\b|\blanguage|\bi18n\b"),
    (
        "whats_new",
        r"\bwhat'?s new|\bwhat'?s fixed|\bfixes?\b|\bfixed\b|\bcorrections?\b|\bbug ?fix"
        r"|\bnew\b|\bhighlights?\b|\bfeatures?\b|\bsummary\b|\boverview\b",
    ),
    ("core", r"."),
)
_ROUTE_PATTERNS = tuple((key, re.compile(pattern)) for key, pattern in _SECTION_ROUTES)

# Upstream product tokens that must never reach the public channel. Newer notes
# are already scrubbed by scripts/verify-release-target.sh; older ones are not,
# so the renderer is defence in depth. Longest tokens first.
_BRAND_SCRUBS: tuple[tuple[str, str], ...] = (
    (r"\bNuvio(?:Mobile|Media)?[\s-]*Enhanced\b|\bNuvioMobile\b|\bNuvioMedia\b", "StreamBridge core"),
    (r"\bNuvio-compatible\b|\bnuvio-compatible\b", "compatible"),
    (r"\bnuvio\.tv\b", "the legacy link"),
    (r"\bnuvio://\S*", "the legacy link"),
    (r"\bNuvio\b|\bnuvio\b", "StreamBridge"),
    (r"\bUpstream\b|\bupstream\b", "Core"),
)

# Repository-internal references. The announcement keeps the *information* but
# never prints internal paths, so a documented store URL becomes a labelled link
# and an internal doc path becomes a plain phrase.
_INTERNAL_PATH_RE = re.compile(
    r"(?:\.github/|scripts/|docs/|composeApp/|iosApp/|androidApp/|site/|build\.gradle|"
    r"gradle\.properties|streambridge\.version\.properties)[\w./\-]*",
    re.IGNORECASE,
)
_LABELLED_PATHS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"store\.json$", re.IGNORECASE), "StreamBridge AltStore / SideStore source"),
    (re.compile(r"release-ios\.md$", re.IGNORECASE), "the StreamBridge iOS release guide"),
    (re.compile(r"signing\.md$", re.IGNORECASE), "the StreamBridge signing guide"),
    (re.compile(r"trakt-simkl-config\.md$", re.IGNORECASE), "the StreamBridge tracking guide"),
)

_ANDROID_ABI_ORDER = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
_ANDROID_ABI_RE = re.compile(r"(?:^|[-_])(arm64-v8a|armeabi-v7a|x86_64|x86)(?:[-_]|$)", re.IGNORECASE)
_VERSION_RE = re.compile(r"^v?(\d+(?:\.\d+){0,3})$")
_VERSION_CODE_RES = (
    re.compile(r"version\s*code\s*[:=]?\s*(\d{1,6})", re.IGNORECASE),
    re.compile(r"versionCode\s*[:=]?\s*(\d{1,6})", re.IGNORECASE),
    re.compile(r"\bcode\s*(\d{3,6})\b", re.IGNORECASE),
)
_INLINE_LINK_RE = re.compile(r"\[([^\]]+)\]\((https?://[^\s)]+)\)")
_BARE_URL_RE = re.compile(r"https?://[^\s<>()\[\]]+")
_TAG_RE = re.compile(r"</?([a-zA-Z]+)([^>]*)>")


# --------------------------------------------------------------------------- #
# Release model
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class Asset:
    """One release asset exactly as the GitHub API reports it."""

    name: str
    size: int
    url: str
    state: str = "uploaded"
    digest: str = ""
    id: int | None = None

    @property
    def usable(self) -> bool:
        """Only an ``uploaded`` asset with a URL can be announced."""
        return self.state == "uploaded" and bool(self.url)

    @property
    def abi(self) -> str | None:
        match = _ANDROID_ABI_RE.search(self.name)
        return match.group(1).lower() if match else None


@dataclass(frozen=True)
class Release:
    """The subset of a GitHub release the announcement needs."""

    id: int
    tag_name: str
    name: str
    body: str
    html_url: str
    draft: bool = False
    prerelease: bool = False
    published_at: str = ""
    assets: tuple[Asset, ...] = ()
    created_at: str = ""

    @classmethod
    def from_api(cls, data: dict) -> "Release":
        if not isinstance(data, dict):
            raise ValueError("release payload is not a JSON object")
        try:
            release_id = int(data["id"])
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError("release payload has no numeric id") from exc
        assets = []
        for raw in data.get("assets") or []:
            if not isinstance(raw, dict) or not raw.get("name"):
                continue
            assets.append(
                Asset(
                    name=str(raw.get("name")),
                    size=int(raw.get("size") or 0),
                    url=str(raw.get("browser_download_url") or ""),
                    state=str(raw.get("state") or "uploaded"),
                    digest=str(raw.get("digest") or ""),
                    id=raw.get("id") if type(raw.get("id")) is int and raw.get("id") > 0 else None,
                )
            )
        return cls(
            id=release_id,
            tag_name=str(data.get("tag_name") or ""),
            name=str(data.get("name") or ""),
            body=str(data.get("body") or ""),
            html_url=str(data.get("html_url") or ""),
            draft=bool(data.get("draft")),
            prerelease=bool(data.get("prerelease")),
            published_at=str(data.get("published_at") or ""),
            assets=tuple(assets),
            created_at=str(data.get("created_at") or ""),
        )

    @property
    def version(self) -> str:
        """Version from the tag (``v0.1.15`` -> ``0.1.15``); the tag verbatim if odd."""
        match = _VERSION_RE.match(self.tag_name.strip())
        return match.group(1) if match else self.tag_name.strip()

    @property
    def latest(self) -> bool:
        return "latest" in self.name.strip().lower()


# --------------------------------------------------------------------------- #
# Assets
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class AssetSummary:
    """What the release actually publishes, and what it does not."""

    android: tuple[Asset, ...]
    ios: Asset | None
    checksums: Asset | None
    ignored: tuple[Asset, ...]
    missing: tuple[str, ...]

    @property
    def android_primary(self) -> Asset | None:
        """Preferred single APK for a one-tap button: arm64 first."""
        for abi in _ANDROID_ABI_ORDER:
            for asset in self.android:
                if asset.abi == abi:
                    return asset
        return self.android[0] if self.android else None


def classify_assets(assets: Sequence[Asset]) -> AssetSummary:
    """Split published assets into Android APKs, the iOS IPA and checksums.

    Anything that is not ``uploaded`` counts as absent, because an unfinished
    upload would hand users a truncated file. Nothing is assumed to exist: a
    release with no IPA opens with a statement that iOS is not available.
    """
    android: list[Asset] = []
    ios: Asset | None = None
    checksums: Asset | None = None
    ignored: list[Asset] = []
    missing: list[str] = []

    for asset in assets:
        lowered = asset.name.lower()
        if not asset.usable:
            state = asset.state or "unknown state"
            if lowered.endswith(".apk"):
                missing.append(f"Android APK `{asset.name}` ({state})")
            elif lowered.endswith(".ipa"):
                missing.append(f"iOS IPA `{asset.name}` ({state})")
            continue
        if lowered.endswith(".apk"):
            android.append(asset)
        elif lowered.endswith(".ipa"):
            if ios is None:
                ios = asset
            else:
                ignored.append(asset)
        elif lowered.endswith(".sha256") or lowered.endswith("checksums.txt"):
            if checksums is None:
                checksums = asset
            else:
                ignored.append(asset)
        else:
            ignored.append(asset)

    android.sort(
        key=lambda a: (
            _ANDROID_ABI_ORDER.index(a.abi) if a.abi in _ANDROID_ABI_ORDER else 99,
            a.name,
        )
    )
    if not android:
        missing.append("Android APK")
    if ios is None:
        missing.append("iOS IPA")
    return AssetSummary(
        android=tuple(android),
        ios=ios,
        checksums=checksums,
        ignored=tuple(ignored),
        missing=tuple(missing),
    )


def human_size(size: int) -> str:
    """Format a byte count the way the release notes do (MiB, two decimals)."""
    if size <= 0:
        return "size unknown"
    mib = size / (1024 * 1024)
    if mib >= 1:
        return f"{mib:.2f} MiB"
    kib = size / 1024
    if kib >= 1:
        return f"{kib:.1f} KiB"
    return f"{size} B"


def version_code(release: Release) -> tuple[str | None, str]:
    """Return ``(version_code, source)`` for a release.

    The code is only reported when the release itself supports it: an explicit
    "version code" in the notes wins. Otherwise it is derived from the tag with
    the repository's own convention (0.1.15 -> 115, see
    ``streambridge.version.properties``) and labelled as derived, so the summary
    says where the number came from. When neither works the code is omitted
    rather than guessed.
    """
    for pattern in _VERSION_CODE_RES:
        match = pattern.search(release.body or "")
        if match:
            return match.group(1), "release notes"
    match = _VERSION_RE.match(release.tag_name.strip())
    if match:
        parts = [int(piece) for piece in match.group(1).split(".")]
        while len(parts) < 3:
            parts.append(0)
        major, minor, patch = parts[0], parts[1], parts[2]
        if major < 100 and minor < 100 and patch < 100:
            return str(major * 10000 + minor * 100 + patch), "derived from the tag (repository convention)"
    return None, "unavailable"


# --------------------------------------------------------------------------- #
# Text helpers
# --------------------------------------------------------------------------- #


def visible_text(markup: str) -> str:
    """Strip Telegram HTML tags and decode entities, i.e. what a reader sees."""
    return html.unescape(_TAG_RE.sub("", markup))


def telegram_length(text: str) -> int:
    """Length the Telegram Bot API counts: UTF-16 code units of visible text."""
    return len(visible_text(text).encode("utf-16-le")) // 2


def scrub_branding(text: str) -> tuple[str, int]:
    """Remove upstream product branding from release-notes text.

    Returns the cleaned text and how many upstream tokens were replaced, so the
    workflow summary can state that sanitisation happened instead of hiding it.
    """
    replaced = 0
    cleaned = text
    for pattern, replacement in _BRAND_SCRUBS:
        cleaned, count = re.subn(pattern, replacement, cleaned)
        replaced += count
    return cleaned, replaced


def _clean_internal_paths(text: str) -> str:
    """Replace repository-internal paths with a readable phrase."""

    def label_for(path: str) -> str:
        for pattern, label in _LABELLED_PATHS:
            if pattern.search(path):
                return label
        return ""

    def replace(match: re.Match[str]) -> str:
        return label_for(match.group(0).strip("`").rstrip(".,;:)"))

    # A backticked internal path is prose once it is labelled, so the backticks go
    # with it instead of producing `<code>the guide</code>`.
    cleaned = re.sub(r"`(" + _INTERNAL_PATH_RE.pattern + r")`", replace, text)
    cleaned = _INTERNAL_PATH_RE.sub(replace, cleaned)
    return re.sub(r"[ \t]{2,}", " ", cleaned).strip()


def _url_label(url: str) -> str:
    """Human label for a bare URL: host names and internal repositories stay hidden."""
    for pattern, label in _LABELLED_PATHS:
        if pattern.search(url):
            return label
    if "/releases" in url:
        return "Release downloads"
    if "/issues" in url:
        return "Report an issue"
    if "github.com" in url or "raw.githubusercontent.com" in url:
        return "StreamBridge on GitHub"
    return re.sub(r"^https?://", "", url).split("/")[0]


# --------------------------------------------------------------------------- #
# Inline Markdown -> Telegram HTML
# --------------------------------------------------------------------------- #

_RESTORE_CODE = "\x00"
_RESTORE_LINK = "\x01"
_OPEN_TAG_NAME_RE = re.compile(r"^<([a-zA-Z]+)")


def render_inline(markdown_text: str) -> str:
    """Convert one line of Markdown to Telegram HTML.

    Escaping happens first, so any literal HTML in the notes is inert text. Then
    the inline Markdown StreamBridge notes actually use is converted:
    ``**bold**``, ``*italic*``, `` `code` ``, ``[label](url)`` and bare URLs.
    Upstream product names are scrubbed here, which is the single place every
    character of announcement text passes through.
    """
    text = markdown_text.strip()
    if not text:
        return ""
    text, _ = scrub_branding(text)
    escaped = html.escape(text, quote=False)

    code_spans: list[str] = []

    def _stash_code(match: re.Match[str]) -> str:
        code_spans.append(match.group(1))
        return f"{_RESTORE_CODE}{len(code_spans) - 1}{_RESTORE_CODE}"

    escaped = re.sub(r"`([^`]+)`", _stash_code, escaped)

    links: list[tuple[str, str]] = []

    def _stash_link(match: re.Match[str]) -> str:
        links.append((match.group(1), match.group(2)))
        return f"{_RESTORE_LINK}{len(links) - 1}{_RESTORE_LINK}"

    escaped = _INLINE_LINK_RE.sub(_stash_link, escaped)

    def _stash_bare(match: re.Match[str]) -> str:
        url = match.group(0).rstrip(".,;:")
        links.append((html.escape(_url_label(url), quote=False), url))
        return f"{_RESTORE_LINK}{len(links) - 1}{_RESTORE_LINK}"

    escaped = _BARE_URL_RE.sub(_stash_bare, escaped)

    # Emphasis. Bold before italic so **x** is not eaten by the italic rule.
    escaped = re.sub(r"\*\*(?=\S)(.+?)(?<=\S)\*\*", r"<b>\1</b>", escaped)
    escaped = re.sub(r"(?<![*\w])\*(?=\S)([^*]+?)(?<=\S)\*(?![*\w])", r"<i>\1</i>", escaped)
    escaped = re.sub(r"(?<![\w_])_(?=\S)([^_]+?)(?<=\S)_(?![\w_])", r"<i>\1</i>", escaped)
    escaped = re.sub(r"~~(?=\S)(.+?)(?<=\S)~~", r"<s>\1</s>", escaped)

    escaped = re.sub(
        rf"{_RESTORE_CODE}(\d+){_RESTORE_CODE}",
        lambda m: f"<code>{code_spans[int(m.group(1))]}</code>",
        escaped,
    )

    def _restore_link(match: re.Match[str]) -> str:
        label, url = links[int(match.group(1))]
        return f'<a href="{html.escape(url, quote=True)}">{label}</a>'

    escaped = re.sub(rf"{_RESTORE_LINK}(\d+){_RESTORE_LINK}", _restore_link, escaped)
    return re.sub(r"\s+", " ", escaped).strip()


def _annotate(markup: str) -> list[tuple[str, tuple[str, ...]]]:
    """Pair every visible character with the inline tags open around it.

    ``<b>a</b> b`` becomes ``[("a", ("<b>",)), (" ", ()), ("b", ())]``. Splitting
    this list anywhere and rebuilding the markup re-opens and closes exactly the
    tags each piece needs, so no piece can ever be unbalanced.
    """
    annotated: list[tuple[str, tuple[str, ...]]] = []
    stack: list[str] = []
    index = 0
    while index < len(markup):
        match = _TAG_RE.search(markup, index)
        if not match:
            for char in markup[index:]:
                annotated.append((char, tuple(stack)))
            break
        for char in markup[index : match.start()]:
            annotated.append((char, tuple(stack)))
        token = match.group(0)
        if token.startswith("</"):
            if stack:
                stack.pop()
        else:
            stack.append(token)
        index = match.end()
    return annotated


def _rebuild(annotated: Sequence[tuple[str, tuple[str, ...]]]) -> str:
    """Turn annotated characters back into balanced HTML."""
    out: list[str] = []
    previous: tuple[str, ...] = ()
    for char, tags in annotated:
        common = 0
        while common < len(previous) and common < len(tags) and previous[common] == tags[common]:
            common += 1
        for token in reversed(previous[common:]):
            name = _OPEN_TAG_NAME_RE.match(token)
            out.append(f"</{name.group(1)}>" if name else "")
        out.extend(tags[common:])
        out.append(char)
        previous = tags
    for token in reversed(previous):
        name = _OPEN_TAG_NAME_RE.match(token)
        out.append(f"</{name.group(1)}>" if name else "")
    return "".join(out)


def split_html(markup: str, limit: int) -> list[str]:
    """Split one inline HTML run into pieces of at most ``limit`` visible chars.

    Pieces are cut at word boundaries (never mid-word unless a single word is
    longer than the limit) and rebuilt so every piece is valid Telegram HTML on
    its own. Nothing is dropped: concatenating the pieces reproduces the text.
    """
    if telegram_length(markup) <= limit:
        return [markup]
    annotated = _annotate(markup)
    pieces: list[str] = []
    start = 0
    while start < len(annotated):
        # Consume characters until the next character would overflow the limit.
        used = 0
        end = start
        while end < len(annotated):
            width = len(annotated[end][0].encode("utf-16-le")) // 2
            if used + width > limit:
                break
            used += width
            end += 1
        if end == start:  # a single character wider than the limit
            end = start + 1
        if end < len(annotated):
            # Prefer breaking after a space, so words are never cut in half.
            cut = end
            while cut > start and not annotated[cut - 1][0].isspace():
                cut -= 1
            if cut > start:
                end = cut
        piece = _trim(annotated[start:end])
        if piece:
            pieces.append(_rebuild(piece))
        # The separator space belongs to neither message; each piece is sent on
        # its own, so keeping it would only double the gap on screen.
        while end < len(annotated) and annotated[end][0].isspace():
            end += 1
        start = end
    return [piece for piece in pieces if visible_text(piece).strip()]


def _trim(items: Sequence[tuple[str, tuple[str, ...]]]) -> list[tuple[str, tuple[str, ...]]]:
    """Drop leading and trailing whitespace from an annotated character run."""
    start = 0
    end = len(items)
    while start < end and items[start][0].isspace():
        start += 1
    while end > start and items[end - 1][0].isspace():
        end -= 1
    return list(items[start:end])


# --------------------------------------------------------------------------- #
# Markdown document -> sections
# --------------------------------------------------------------------------- #


@dataclass
class Block:
    """One logical unit of the announcement: a bullet, paragraph, quote or code.

    ``text`` holds cleaned Markdown inline syntax (never raw HTML); the HTML is
    rendered from it at layout time.
    """

    kind: str
    text: str

    def render(self) -> str:
        if self.kind == "code":
            return f"<pre>{html.escape(self.text, quote=False)}</pre>"
        markup = render_inline(self.text)
        if not markup:
            return ""
        if self.kind == "bullet":
            markup = "• " + markup
        elif self.kind == "note":
            markup = f"<blockquote>{markup}</blockquote>"
        return markup

    def html(self) -> str:
        return self.render()


@dataclass
class Section:
    key: str
    blocks: list[Block] = field(default_factory=list)

    @property
    def title(self) -> str:
        return SECTION_TITLE_BY_KEY[self.key]

    @property
    def heading(self) -> str:
        return f"<b>{html.escape(self.title, quote=False)}</b>"


_HEADING_RE = re.compile(r"^(#{1,6})\s+(.*)$")
_BULLET_RE = re.compile(r"^\s{0,3}(?:[-*+]|\d+[.)])\s+(.*)$")
_QUOTE_RE = re.compile(r"^\s{0,3}>\s?(.*)$")
_TABLE_ROW_RE = re.compile(r"^\s*\|.*\|\s*$")
_TABLE_RULE_RE = re.compile(r"^\s*\|?[\s:|-]+\|[\s:|-]*$")
_FENCE_RE = re.compile(r"^\s*(```|~~~)(.*)$")
_RULE_RE = re.compile(r"^\s*(?:-{3,}|\*{3,}|_{3,})\s*$")
_IMAGE_RE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
_TITLE_HEADING_RE = re.compile(
    r"^(?:stream\s*bridge\s*)?v?\d+(?:\.\d+){1,3}(?:[-+][\w.\-]*)?$", re.IGNORECASE
)
_METADATA_RE = re.compile(
    r"^stream\s*bridge\s+version\s+v?\d+(?:\.\d+){1,3}\s*\([^)]*\)", re.IGNORECASE
)


def route_heading(heading: str) -> str:
    """Map a release-notes heading onto one of the canonical sections."""
    lowered = re.sub(r"[*_`]", "", heading).strip().lower()
    for key, pattern in _ROUTE_PATTERNS:
        if pattern.search(lowered):
            return key
    return "core"


@dataclass
class ParsedNotes:
    sections: list[Section]
    counters: dict[str, int]


def parse_notes(body: str) -> ParsedNotes:
    """Parse GitHub-flavoured Markdown release notes into canonical sections.

    Sections come back in canonical order and only when they have content, so
    the announcement only ever shows sections the release notes support.
    """
    buckets: dict[str, list[Block]] = {key: [] for key in SECTION_ORDER}
    lead: list[Block] = []
    counters = {
        "tables": 0,
        "table_rows_dropped": 0,
        "title_headings_skipped": 0,
        "version_metadata_skipped": 0,
        "unsupported_blocks_skipped": 0,
    }

    current: str | None = None
    paragraph: list[str] = []
    bullet: list[str] = []
    quote: list[str] = []
    in_code = False
    code_lines: list[str] = []
    table: list[str] = []

    def target(block: Block) -> None:
        if current is None:
            lead.append(block)
        else:
            buckets[current].append(block)

    def flush_paragraph() -> None:
        nonlocal paragraph
        if not paragraph:
            return
        joined = _clean_internal_paths(" ".join(paragraph))
        paragraph = []
        if not joined:
            return
        if _METADATA_RE.match(joined):
            counters["version_metadata_skipped"] += 1
            return
        target(Block("paragraph", joined))

    def flush_bullet() -> None:
        nonlocal bullet
        if not bullet:
            return
        joined = _clean_internal_paths(" ".join(bullet))
        bullet = []
        if joined:
            target(Block("bullet", joined))

    def flush_quote() -> None:
        nonlocal quote
        if not quote:
            return
        joined = _clean_internal_paths(" ".join(quote))
        quote = []
        if joined:
            target(Block("note", joined))

    def flush_table() -> None:
        nonlocal table
        if not table:
            return
        counters["tables"] += 1
        rows, table = table, []
        if current == "downloads":
            # The Downloads section is generated from the published assets, so a
            # hand-written size table would only repeat (or contradict) it.
            counters["table_rows_dropped"] += len(rows)
            return
        for row in rows:
            cells = [cell.strip() for cell in row.strip().strip("|").split("|")]
            if all(set(cell) <= set("-: ") for cell in cells):
                continue
            pieces = [cell for cell in cells if cell]
            if not pieces:
                continue
            target(Block("bullet", _clean_internal_paths(" — ".join(pieces))))

    def flush_code() -> None:
        nonlocal code_lines
        if not code_lines:
            return
        block = Block("code", "\n".join(code_lines))
        code_lines = []
        if current is None:
            counters["unsupported_blocks_skipped"] += 1
            return
        buckets[current].append(block)

    def flush_all() -> None:
        flush_paragraph()
        flush_bullet()
        flush_quote()
        flush_table()

    for raw_line in body.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        fence = _FENCE_RE.match(raw_line)
        if in_code:
            if fence:
                in_code = False
                flush_code()
            else:
                code_lines.append(raw_line)
            continue
        if fence:
            flush_all()
            in_code = True
            continue
        if not raw_line.strip():
            flush_all()
            continue
        if _TABLE_ROW_RE.match(raw_line):
            flush_paragraph()
            flush_bullet()
            flush_quote()
            table.append(raw_line)
            continue
        flush_table()
        if _RULE_RE.match(raw_line):
            flush_all()
            continue
        heading = _HEADING_RE.match(raw_line)
        if heading:
            flush_all()
            text = _IMAGE_RE.sub("", heading.group(2)).strip()
            if not text:
                continue
            normalized = re.sub(r"\s+", " ", text)
            if _TITLE_HEADING_RE.match(normalized.replace(" ", "")):
                counters["title_headings_skipped"] += 1
                current = None
                continue
            current = route_heading(text)
            continue
        quote_match = _QUOTE_RE.match(raw_line)
        if quote_match:
            flush_paragraph()
            flush_bullet()
            quote.append(quote_match.group(1).strip())
            continue
        flush_quote()
        bullet_match = _BULLET_RE.match(raw_line)
        if bullet_match:
            flush_paragraph()
            flush_bullet()
            text = _IMAGE_RE.sub("", bullet_match.group(1)).strip()
            bullet.append(text)
            continue
        # A plain text line: it continues whichever block is open, which is how
        # GitHub renders the hard-wrapped bullets and paragraphs in the notes.
        if bullet:
            bullet.append(raw_line.strip())
        elif paragraph:
            paragraph.append(raw_line.strip())
        else:
            paragraph = [raw_line.strip()]

    if in_code:
        flush_code()
    flush_all()

    if lead:
        whats_new = next((section for section in _ordered(buckets) if section.key == "whats_new"), None)
        if whats_new is None:
            buckets["whats_new"] = list(lead)
            counters["lead_paragraphs_moved"] = len(lead)
        else:
            # Keep the document's own order: the lead text opens the section.
            whats_new.blocks = list(lead) + whats_new.blocks
    return ParsedNotes([section for section in _ordered(buckets) if section.blocks], counters)


def _ordered(buckets: dict[str, list[Block]]) -> list[Section]:
    return [Section(key, buckets[key]) for key in SECTION_ORDER if buckets.get(key)]


# --------------------------------------------------------------------------- #
# Downloads, installation and header text
# --------------------------------------------------------------------------- #


def _download_blocks(summary: AssetSummary) -> list[Block]:
    """Downloads bullets, built only from assets that are actually published."""
    blocks: list[Block] = []
    if summary.android:
        count = len(summary.android)
        noun = "APK" if count == 1 else "APKs"
        blocks.append(
            Block(
                "paragraph",
                f"**Android** — production-signed, {count} {noun}, one per device ABI.",
            )
        )
        for asset in summary.android:
            label = asset.abi or asset.name
            blocks.append(Block("bullet", f"`{label}` — {human_size(asset.size)}"))
    else:
        blocks.append(Block("paragraph", "**Android** — no Android APK is attached to this release."))

    if summary.ios is not None:
        blocks.append(
            Block(
                "paragraph",
                f"**iOS** — unsigned IPA for AltStore / SideStore "
                f"(`{summary.ios.name}`, {human_size(summary.ios.size)}).",
            )
        )
    else:
        blocks.append(Block("paragraph", "**iOS** — no iOS IPA is attached to this release."))

    if summary.checksums is not None:
        blocks.append(
            Block(
                "paragraph",
                "**Checksums** — `checksums.sha256` is attached; verify every download.",
            )
        )
    return blocks


_ABI_BUTTON_LABELS = {
    "arm64-v8a": "🤖 Android ARM64",
    "armeabi-v7a": "🤖 Android ARMv7",
    "x86": "🤖 Android x86",
    "x86_64": "🤖 Android x86_64",
}


def download_buttons(summary: AssetSummary, release: Release) -> list[tuple[str, str]]:
    """Inline keyboard rows: the release page first, then verified asset links only.

    Every button URL is the ``browser_download_url`` GitHub reported for an
    asset in ``state: uploaded``; nothing is derived from a pattern. Telegram
    allows 100 buttons, far more than the five binaries plus checksums here, so
    each architecture gets its own direct link instead of a single generic APK.
    """
    buttons: list[tuple[str, str]] = []
    if release.html_url:
        buttons.append((DOWNLOAD_BUTTON_LABEL, release.html_url))
    for asset in summary.android:
        label = _ABI_BUTTON_LABELS.get(asset.abi or "", "🤖 Android APK")
        if not summary.android_primary or asset.name != summary.android_primary.name:
            label = f"{label}"
        buttons.append((label, asset.url))
    if summary.ios is not None:
        buttons.append(("🍎 iOS IPA", summary.ios.url))
    if summary.checksums is not None:
        buttons.append(("🔐 Checksums", summary.checksums.url))
    return buttons


_MONTHS = (
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
)


def release_date(release: Release) -> str:
    """``2026-11-05T06:45:29Z`` -> ``5 November 2026`` (no locale dependency)."""
    match = re.match(r"^(\d{4})-(\d{2})-(\d{2})", release.published_at or "")
    if not match:
        return ""
    year, month, day = int(match.group(1)), int(match.group(2)), int(match.group(3))
    if not 1 <= month <= 12:
        return ""
    return f"{day} {_MONTHS[month - 1]} {year}"


# --------------------------------------------------------------------------- #
# Pages
# --------------------------------------------------------------------------- #


@dataclass
class Page:
    text: str
    buttons: list[tuple[str, str]] = field(default_factory=list)

    @property
    def length(self) -> int:
        return telegram_length(self.text)


@dataclass
class Announcement:
    release_id: int
    tag_name: str
    version: str
    pages: list[Page]
    sections: list[str]
    keyboard: list[tuple[str, str]]
    summary: dict
    html_problems: list[str] = field(default_factory=list)

    @property
    def text(self) -> str:
        return "\n\n".join(page.text for page in self.pages)


def _footer(tag: str = "") -> str:
    """Brand footer, plus the release tag that also serves as the duplicate guard.

    ``tag`` is a channel hashtag such as ``#StreamBridge #SB434756896``: it reads
    as the release's serial tag to a reader, and it lets a later run recognise an
    announcement that is already in the channel even when the post ledger is not
    available (see scripts/telegram_announce.py).
    """
    footer = f"<i>{html.escape(FOOTER, quote=False)}</i>"
    if tag:
        footer = f"{footer}\n{html.escape(tag, quote=False)}"
    return footer


_PAGE_FOOTER = _footer()


def page_header(release: Release, page_number: int, total: int = 0) -> str:
    """Page header. Page one carries the full announcement banner."""
    version = html.escape(release.version, quote=False)
    if page_number == 1:
        lines = [
            f"🚀 <b>STREAMBRIDGE {version} IS HERE!</b>",
            f"❤️ <i>{html.escape(MOTTO, quote=False)}.</i>",
        ]
        date = release_date(release)
        if date:
            lines.append(f"📅 {html.escape(date, quote=False)}")
        if release.latest:
            lines.append("🏷 Latest release")
        return "\n".join(lines)
    suffix = f" (part {page_number}/{total})" if total else f" (part {page_number})"
    return f"🚀 <b>STREAMBRIDGE {version} IS HERE!</b>{html.escape(suffix, quote=False)}"


def _pack(
    sections: list[Section],
    summary: AssetSummary,
    release: Release,
    target: int = PAGE_TARGET,
    tag: str = "",
) -> list[Page]:
    """Pack rendered sections into pages that stay under ``target`` characters.

    Packing works on complete, tag-balanced lines, so it may split anywhere
    between two lines or between two words and still emit valid HTML. Nothing is
    dropped: when a block does not fit on the page in progress, it continues on
    the next page under a repeated section heading.
    """
    buttons = download_buttons(summary, release)
    footer = _footer(tag)
    overhead = telegram_length(page_header(release, 9, 99)) + telegram_length(footer) + 8
    budget = max(target - overhead, 400)

    bodies: list[list[str]] = [[]]
    used: list[int] = [0]
    headings_on_page: list[set[str]] = [set()]

    def new_page() -> None:
        bodies.append([])
        used.append(0)
        headings_on_page.append(set())

    def push(line: str) -> None:
        bodies[-1].append(line)
        used[-1] += telegram_length(line) + 2

    def ensure_heading(section: Section) -> None:
        """Put the section heading on the current page unless it is already there."""
        if section.key in headings_on_page[-1]:
            return
        heading_len = telegram_length(section.heading)
        if bodies[-1] and used[-1] + heading_len + 2 > budget:
            new_page()
        push(section.heading)
        headings_on_page[-1].add(section.key)

    for section in sections:
        for block in section.blocks:
            markup = block.render()
            if not markup:
                continue
            heading_len = telegram_length(section.heading)
            # Keep a heading and the opening line of its block on one page.
            first_len = telegram_length(split_html(markup, budget - heading_len - 4)[0]) if markup else 0
            if bodies[-1] and used[-1] + heading_len + first_len + 4 > budget:
                new_page()
            ensure_heading(section)

            pieces = split_html(markup, budget - 2)
            for piece in pieces:
                if used[-1] + telegram_length(piece) + 2 > budget:
                    new_page()
                    ensure_heading(section)
                push(piece)

    bodies = [body for body in bodies if body]
    total = len(bodies)
    pages: list[Page] = []
    for number, body in enumerate(bodies, start=1):
        text = "\n\n".join([page_header(release, number, total), *body, footer])
        # Every page repeats the release button so a reader who starts mid-thread
        # can still reach the downloads.
        pages.append(Page(text, buttons if number == 1 else buttons[:1]))
    return pages


def validate_html(markup: str) -> list[str]:
    """Return every HTML problem Telegram would reject, as readable strings."""
    problems: list[str] = []
    stack: list[str] = []
    for match in _TAG_RE.finditer(markup):
        closing, tag, attrs = match.group(0).startswith("</"), match.group(1).lower(), match.group(2)
        if tag not in ALLOWED_TAGS:
            problems.append(f"unsupported tag <{tag}>")
            continue
        if closing:
            if not stack or stack[-1] != tag:
                problems.append(f"unbalanced closing </{tag}>")
            else:
                stack.pop()
            continue
        if tag == "a" and 'href="' not in attrs:
            problems.append("anchor without href")
        stack.append(tag)
    problems.extend(f"unclosed <{tag}>" for tag in stack)
    if "&" in re.sub(r"&(?:amp|lt|gt|quot|#\d+|#x[0-9a-fA-F]+);", "", markup):
        problems.append("bare & must be escaped as &amp;")
    for match in re.finditer(r'<a href="([^"]*)"', markup):
        if not match.group(1).startswith("https://"):
            problems.append(f"link is not https: {match.group(1)}")
    return problems


def build_announcement(release: Release, target: int = PAGE_TARGET, tag: str = "") -> Announcement:
    """Build the complete, validated StreamBridge Telegram announcement."""
    summary = classify_assets(release.assets)
    parsed = parse_notes(release.body)
    sections = list(parsed.sections)

    downloads = next((section for section in sections if section.key == "downloads"), None)
    if downloads is None:
        downloads = Section("downloads", [])
        sections.append(downloads)
    downloads.blocks = _download_blocks(summary) + downloads.blocks

    sections = [section for section in sections if section.blocks]
    sections.sort(key=lambda section: SECTION_ORDER.index(section.key))

    _, scrubbed = scrub_branding(release.body)
    pages = _pack(sections, summary, release, target, tag)

    problems: list[str] = []
    for page in pages:
        problems.extend(validate_html(page.text))
        if page.length > TELEGRAM_TEXT_LIMIT:
            problems.append(f"page of {page.length} characters exceeds the Telegram limit")

    keyboard = download_buttons(summary, release)
    for label, url in keyboard:
        if not re.match(r"^https://", url):
            problems.append(f"button {label!r} does not use an https URL")
    if not keyboard:
        problems.append("release has no usable download target")

    code, code_source = version_code(release)
    summary_data = {
        "release_id": release.id,
        "tag_name": release.tag_name,
        "release_name": release.name,
        "version": release.version,
        "version_code": code,
        "version_code_source": code_source,
        "release_url": release.html_url,
        "published_at": release.published_at,
        "sections": [section.key for section in sections],
        "pages": len(pages),
        "characters": sum(page.length for page in pages),
        "android_assets": [asset.name for asset in summary.android],
        "ios_asset": summary.ios.name if summary.ios else None,
        "checksums_asset": summary.checksums.name if summary.checksums else None,
        "ignored_assets": [asset.name for asset in summary.ignored],
        "missing_assets": list(summary.missing),
        "upstream_tokens_scrubbed": scrubbed,
        **parsed.counters,
    }
    return Announcement(
        release_id=release.id,
        tag_name=release.tag_name,
        version=release.version,
        pages=pages,
        sections=[section.key for section in sections],
        keyboard=keyboard,
        summary=summary_data,
        html_problems=problems,
    )


__all__ = [
    "Announcement",
    "Asset",
    "AssetSummary",
    "Block",
    "DOWNLOAD_BUTTON_LABEL",
    "FOOTER",
    "MOTTO",
    "PRODUCT_NAME",
    "Page",
    "Release",
    "Section",
    "TELEGRAM_TEXT_LIMIT",
    "build_announcement",
    "classify_assets",
    "download_buttons",
    "human_size",
    "page_header",
    "parse_notes",
    "release_date",
    "render_inline",
    "route_heading",
    "scrub_branding",
    "telegram_length",
    "validate_html",
    "version_code",
    "visible_text",
]
