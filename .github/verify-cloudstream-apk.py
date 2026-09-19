#!/usr/bin/env python3
"""Verify the CloudStream runtime boundary in a built APK.

Why this exists as a script rather than shell: the dex string pool of the Full
APK is tens of megabytes across ~25 `classes*.dex` files. An earlier shell
implementation captured it into a variable and echoed it back through `grep`,
which overflowed the argument list and made every check fail regardless of the
APK's real contents -- and made the Play Store *negative* check pass vacuously,
silently voiding the no-DEX guarantee.

This reads each dex entry as bytes straight from the zip and searches those
bytes, so there is no shell quoting, no argument-size limit and no truncation.

Usage:
    verify-cloudstream-apk.py --apk <path> --expect-runtime
    verify-cloudstream-apk.py --apk <path> --expect-no-runtime
"""

from __future__ import annotations

import argparse
import sys
import zipfile

# Markers that must be present in a build that can execute CloudStream plugins.
# Each is a raw dex string; they are searched as bytes.
REQUIRED_WHEN_EXECUTING = {
    "the CloudStream plugin package": b"com/lagradost/cloudstream3/plugins",
    "the BasePlugin type": b"BasePlugin",
    "the controlled class loader": b"dalvik/system/PathClassLoader",
    "the CloudStream extractor API": b"com/lagradost/cloudstream3/utils/ExtractorApi",
    "the provider base type (MainAPI)": b"com/lagradost/cloudstream3/MainAPI",
}

# Any occurrence of this in a Play Store build means the runtime leaked in.
RUNTIME_NAMESPACE = b"com/lagradost"


def dex_blobs(apk_path: str) -> list[tuple[str, bytes]]:
    """Returns (entry name, raw bytes) for every dex in the APK."""
    blobs = []
    with zipfile.ZipFile(apk_path) as apk:
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                blobs.append((name, apk.read(name)))
    return blobs


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--expect-runtime", action="store_true")
    group.add_argument("--expect-no-runtime", action="store_true")
    args = parser.parse_args()

    blobs = dex_blobs(args.apk)
    if not blobs:
        print(f"::error::No classes*.dex found in {args.apk}")
        return 1

    total = sum(len(b) for _, b in blobs)
    print(f"Inspecting {args.apk}: {len(blobs)} dex file(s), {total:,} bytes")

    failures = []

    if args.expect_runtime:
        for label, marker in REQUIRED_WHEN_EXECUTING.items():
            hits = sum(blob.count(marker) for _, blob in blobs)
            if hits:
                print(f"  OK      {label}: {hits} reference(s)")
            else:
                failures.append(label)
                print(f"  MISSING {label}")
        if failures:
            print(
                "::error::Full APK is missing "
                + ", ".join(failures)
                + ". The no-DEX stub was selected, a Gradle dependency is not "
                "packaged, or R8 stripped required classes."
            )
            return 1
        print("PASS: the Full APK contains the real CloudStream execution runtime.")
        return 0

    # Negative check: the Play Store build must contain none of it.
    hits = sum(blob.count(RUNTIME_NAMESPACE) for _, blob in blobs)
    print(f"  com/lagradost references: {hits} (expected 0)")
    if hits:
        # Show which classes leaked so the cause is actionable.
        seen: set[bytes] = set()
        for _, blob in blobs:
            start = 0
            while len(seen) < 10:
                index = blob.find(RUNTIME_NAMESPACE, start)
                if index == -1:
                    break
                end = index
                while end < len(blob) and 0x20 <= blob[end] < 0x7F:
                    end += 1
                seen.add(blob[index:end])
                start = index + 1
        for symbol in sorted(seen):
            print(f"    leaked: {symbol.decode('ascii', 'replace')}")
        print(
            f"::error::Play Store APK contains {hits} CloudStream runtime "
            "references; the no-DEX boundary is broken."
        )
        return 1

    print("PASS: the Play Store APK contains no CloudStream execution runtime.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
