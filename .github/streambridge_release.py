#!/usr/bin/env python3
"""Canonical StreamBridge metadata. No upstream version fallback or secret inputs."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$")


def properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "//")):
            continue
        key, separator, value = line.partition("=")
        if separator:
            key = key.strip()
            if key in result:
                raise ValueError(f"Duplicate metadata key: {key}")
            result[key] = value.strip()
    return result


@dataclass(frozen=True)
class Release:
    version: str
    build: int
    ios_version: str

    @property
    def tag(self) -> str:
        return f"v{self.version}"

    @property
    def prerelease(self) -> bool:
        return "-" in self.version

    def xcconfig(self) -> str:
        return (
            "// Generated from streambridge.version.properties; check with .github/streambridge_release.py\n"
            f"MARKETING_VERSION = {self.ios_version}\n"
            f"CURRENT_PROJECT_VERSION = {self.build}\n"
            f"STREAMBRIDGE_RELEASE_VERSION = {self.version}\n"
        )

    def outputs(self) -> dict[str, str]:
        return {"version": self.version, "build": str(self.build), "ios_version": self.ios_version,
                "tag": self.tag, "prerelease": str(self.prerelease).lower()}


def read_release(root: Path = ROOT) -> Release:
    data = properties(root / "streambridge.version.properties")
    version = data["STREAMBRIDGE_VERSION_NAME"]
    match = VERSION.fullmatch(version)
    if not match:
        raise ValueError("STREAMBRIDGE_VERSION_NAME must be a three-component version with an optional prerelease")
    code = data["STREAMBRIDGE_VERSION_CODE"]
    if not code.isdigit() or not 1 <= int(code) <= 2_100_000_000:
        raise ValueError("STREAMBRIDGE_VERSION_CODE must be a positive Android-compatible integer")
    ios_version = data["STREAMBRIDGE_IOS_MARKETING_VERSION"]
    if ios_version != ".".join(match.group(i) for i in (1, 2, 3)):
        raise ValueError("Apple marketing version must match the numeric StreamBridge version")
    for prefix in ("NUVIO_UPSTREAM", "NUVIO_ENHANCED"):
        if not re.fullmatch(r"[0-9a-f]{40}", data[f"{prefix}_COMMIT"]):
            raise ValueError(f"{prefix}_COMMIT must be an exact 40-character source pin")
    return Release(version, int(code), ios_version)


def check_release(root: Path = ROOT) -> Release:
    release = read_release(root)
    if (root / "iosApp/Configuration/Version.xcconfig").read_text() != release.xcconfig():
        raise ValueError("iOS metadata is stale: run python3 .github/streambridge_release.py --write-xcconfig")
    return release


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-xcconfig", action="store_true")
    parser.add_argument("--tag", help="Reject a requested tag that differs from canonical metadata")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    release = read_release()
    if args.tag is not None and args.tag != release.tag:
        parser.error(f"Requested tag must be {release.tag}; release metadata cannot be overridden")
    if args.write_xcconfig:
        (ROOT / "iosApp/Configuration/Version.xcconfig").write_text(release.xcconfig())
    check_release()
    if args.github_output:
        with args.github_output.open("a") as output:
            for key, value in release.outputs().items():
                output.write(f"{key}={value}\n")
    print(json.dumps(release.outputs(), indent=2))


if __name__ == "__main__":
    main()
