#!/usr/bin/env python3
"""Shrink the shipped brand assets to the sizes the UI actually renders them at.

The 0.1.08 release candidate shipped PNG brand assets that were saved as
full-colour, unoptimised renders: 12.8 MB of Compose drawables and 5.3 MB of iOS
appiconsets. NuvioMobile's own pipeline (`scripts/generate_app_icon_assets.py`
at 0.5.5-beta) writes these same assets at display size with Pillow's
``optimize=True``. This script re-encodes the existing StreamBridge artwork the
same way. It never changes the artwork itself: no asset is deleted, no colourway
is dropped, and no pixel dimension that the UI depends on is reduced.

Rules
-----
composeResources/drawable/app_icon_*.png
    Rendered by the icon picker at 78 dp (`AppIconPicker.kt`) and by the
    appearance settings at 40-44 dp. Resized to a 256 px square, which is what
    NuvioMobile's generator ships for the same picker.

composeResources/drawable/app_mark_transparent.png
    Rendered by `StreamBridgeBrandLockup` at `markSize`, whose largest call site
    is 52 dp (`AuthScreen.kt`). Resized to 512 px, leaving better than 2x
    headroom at 4x density.

composeResources/drawable/app_logo_wordmark*.png
    Pixel dimensions are preserved exactly. No current call site renders the
    wordmark, so there is no measured render size to shrink to and the geometry
    is left alone; only the encoding changes.

iosApp/iosApp/Assets.xcassets/AppIcon*.appiconset/app-icon-1024.png
    Kept at exactly 1024x1024, because iOS requires the app icon at that size.
    Re-encoded to 256 colours. The icons stay opaque: iOS rejects an app icon
    that carries an alpha channel.

Every re-encoded PNG keeps its palette only as an encoding detail; the artwork is
the same StreamBridge artwork. The unmodified full-resolution masters stay in
`branding/` (`streambridge-launcher-1024.png`, `streambridge-wordmark-1600.png`,
`streambridge-mark-transparent.png` are byte-identical to the assets they feed),
so the original renders are never lost.

Usage
-----
    scripts/optimize-app-assets.py           # rewrite the assets in place
    scripts/optimize-app-assets.py --check   # exit non-zero if any asset is
                                             # still larger than this script
                                             # would make it (CI guard)
"""

from __future__ import annotations

import argparse
import io
import sys
from pathlib import Path

from PIL import Image

REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
DRAWABLE_DIRECTORY = REPOSITORY_ROOT / "composeApp/src/commonMain/composeResources/drawable"
ASSETS_CATALOGUE = REPOSITORY_ROOT / "iosApp/iosApp/Assets.xcassets"

# Files already close to this size gain nothing from a second pass, and rewriting
# them would churn the repository for no benefit.
SKIP_BELOW_BYTES = 32 * 1024

PALETTE_COLORS = 256


def quantize_and_save(image: Image.Image, keep_alpha: bool) -> bytes:
    """Return the smallest lossy PNG encoding of ``image`` this script allows."""
    converted = image.convert("RGBA") if keep_alpha else image.convert("RGB")
    quantized = converted.quantize(colors=PALETTE_COLORS, method=Image.FASTOCTREE)
    buffer = io.BytesIO()
    quantized.save(buffer, "PNG", optimize=True)
    return buffer.getvalue()


def optimization_targets() -> list[tuple[Path, int | None, bool, str]]:
    """(path, maximum edge in pixels, may keep alpha, description)."""
    targets: list[tuple[Path, int | None, bool, str]] = []

    if DRAWABLE_DIRECTORY.is_dir():
        for path in sorted(DRAWABLE_DIRECTORY.glob("app_icon_*.png")):
            targets.append((path, 256, True, "composeResources icon, rendered at 78 dp"))
        mark = DRAWABLE_DIRECTORY / "app_mark_transparent.png"
        if mark.exists():
            targets.append((mark, 512, True, "brand lockup mark, largest use is 52 dp"))
        for path in sorted(DRAWABLE_DIRECTORY.glob("app_logo_wordmark*.png")):
            targets.append((path, None, True, "wordmark, dimensions preserved"))

    if ASSETS_CATALOGUE.is_dir():
        for path in sorted(ASSETS_CATALOGUE.glob("AppIcon*.appiconset/app-icon-1024.png")):
            targets.append((path, 1024, False, "iOS app icon, must stay opaque at 1024"))

    return targets


def lossless_reencode(image: Image.Image) -> bytes:
    """Re-encode without touching a single pixel, using the smallest PNG settings."""
    buffer = io.BytesIO()
    image.save(buffer, "PNG", optimize=True)
    return buffer.getvalue()


def encoded_size(path: Path, maximum_edge: int | None, keep_alpha: bool) -> tuple[bytes, tuple[int, int]]:
    image = Image.open(path)
    image.load()
    needs_resize = maximum_edge is not None and max(image.size) > maximum_edge

    # An image that is already palette-based has been through this pipeline (or
    # was authored that way). Quantising it again would silently re-palette it on
    # every run: each pass loses a little fidelity and shrinks a little more, so
    # the check below could never report a clean tree. Only re-encode such an
    # image losslessly, which is a stable, repeatable no-op.
    if image.mode == "P" and not needs_resize:
        return lossless_reencode(image), image.size

    if keep_alpha:
        image = image.convert("RGBA")
    else:
        image = image.convert("RGB")
    if needs_resize:
        scale = maximum_edge / max(image.size)
        image = image.resize(
            (max(1, round(image.width * scale)), max(1, round(image.height * scale))),
            Image.LANCZOS,
        )
    return quantize_and_save(image, keep_alpha), image.size


def verify_invariants(path: Path, keep_alpha: bool, expected_height: int | None) -> None:
    """Reject anything that would break the platform the asset ships to."""
    written = Image.open(path)
    written.load()
    if keep_alpha:
        return
    if written.mode in {"RGBA", "LA", "PA"} or "transparency" in written.info:
        raise SystemExit(f"{path}: iOS app icons must not carry an alpha channel")
    if expected_height is not None and written.size != (expected_height, expected_height):
        raise SystemExit(f"{path}: expected a {expected_height}x{expected_height} app icon, got {written.size}")
    # Xcode and the App Store both reject an app icon whose PNG has an alpha
    # channel, so check the raw chunks as well as the decoded mode.
    if b"tRNS" in path.read_bytes():
        raise SystemExit(f"{path}: iOS app icons must not contain a tRNS chunk")


def human(count: int) -> str:
    return f"{count:,d} B"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--check",
        action="store_true",
        help="Report assets that are still larger than this script would make them, without writing.",
    )
    args = parser.parse_args()

    targets = optimization_targets()
    if not targets:
        print("No brand assets found; nothing to do.", file=sys.stderr)
        return 1

    total_before = 0
    total_after = 0
    changed: list[str] = []
    stale: list[str] = []

    print(f"{'asset':56s} {'before':>13s} {'after':>13s} {'saving':>9s}")
    print("-" * 96)
    for path, maximum_edge, keep_alpha, description in targets:
        before = path.stat().st_size
        total_before += before

        if before < SKIP_BELOW_BYTES and maximum_edge is None:
            total_after += before
            continue

        payload, size = encoded_size(path, maximum_edge, keep_alpha)
        encoded = len(payload)
        # Never make a file bigger than it was.
        if encoded >= before:
            total_after += before
            continue

        total_after += encoded
        ratio = before / encoded
        print(
            f"{str(path.relative_to(REPOSITORY_ROOT)):56s} "
            f"{human(before):>13s} {human(encoded):>13s} {ratio:8.1f}x  {size[0]}x{size[1]}  {description}"
        )
        if args.check:
            stale.append(f"{path.relative_to(REPOSITORY_ROOT)} ({human(before)} -> {human(encoded)})")
        else:
            previous_size = Image.open(path).size
            path.write_bytes(payload)
            verify_invariants(path, keep_alpha, maximum_edge if maximum_edge and not keep_alpha else None)
            new_size = Image.open(path).size
            if previous_size != new_size:
                changed.append(f"{path.relative_to(REPOSITORY_ROOT)}: {previous_size} -> {new_size}")

    print("-" * 96)
    saving = total_before - total_after
    percent = (saving / total_before * 100) if total_before else 0.0
    print(
        f"{'TOTAL':56s} {human(total_before):>13s} {human(total_after):>13s} "
        f"{percent:8.1f}%  saved {human(saving)}"
    )
    if changed:
        print("\nResized:")
        for entry in changed:
            print(f"  {entry}")

    if args.check:
        if stale:
            print("\nThese assets are not optimised. Run scripts/optimize-app-assets.py:", file=sys.stderr)
            for entry in stale:
                print(f"  {entry}", file=sys.stderr)
            return 1
        print("\nAll brand assets are already optimised.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
