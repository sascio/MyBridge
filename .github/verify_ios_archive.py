#!/usr/bin/env python3
"""Validate actual StreamBridge archive/export metadata; unsigned != a release."""
from __future__ import annotations

import argparse
from pathlib import Path
import plistlib
import re
import subprocess
import tempfile
import zipfile

from streambridge_release import check_release


def validate_bundle(path: Path, bundle_id: str, display_name: str, mode: str) -> None:
    release = check_release()
    with (path / "Info.plist").open("rb") as source:
        info = plistlib.load(source)
    expected = {
        "CFBundleIdentifier": bundle_id,
        "CFBundleDisplayName": display_name,
        "CFBundleShortVersionString": release.ios_version,
        "CFBundleVersion": str(release.build),
        "StreamBridgeReleaseVersion": release.version,
    }
    for key, value in expected.items():
        if info.get(key) != value:
            raise ValueError(f"{path.name}: {key} must be {value}, got {info.get(key)!r}")
    executable = path / info["CFBundleExecutable"]
    if not executable.is_file():
        raise ValueError(f"Missing executable: {path.name}")
    architectures = subprocess.check_output(["xcrun", "lipo", "-archs", str(executable)], text=True).split()
    if architectures != ["arm64"]:
        raise ValueError(f"Expected a device arm64 bundle, got {architectures}")
    if mode == "unsigned":
        if (path / "_CodeSignature").exists() or (path / "embedded.mobileprovision").exists():
            raise ValueError("Unsigned validation bundle unexpectedly contains app signing material")
    else:
        if not (path / "embedded.mobileprovision").is_file():
            raise ValueError("Signed bundle is missing its distribution profile")
        subprocess.run(["codesign", "--verify", "--deep", "--strict", str(path)], check=True)
        profile = plistlib.loads(subprocess.check_output(["security", "cms", "-D", "-i",
                                                          str(path / "embedded.mobileprovision")]))
        team = profile["TeamIdentifier"][0]
        if profile["Entitlements"].get("application-identifier") != f"{team}.{bundle_id}":
            raise ValueError("Exported profile does not match the StreamBridge bundle")
        if profile["Entitlements"].get("get-task-allow", False):
            raise ValueError("Development/debug provisioning is not a production release")
        signature = subprocess.run(["codesign", "-dvv", str(path)], text=True, capture_output=True, check=True)
        if not re.search(r"^TeamIdentifier=" + re.escape(team) + r"$", signature.stderr, re.M):
            raise ValueError("Exported code-signing team does not match the provisioning profile")


def validate_app(app: Path, mode: str) -> None:
    validate_bundle(app, "com.streambridge.app", "StreamBridge", mode)
    info = plistlib.loads((app / "Info.plist").read_bytes())
    schemes = {scheme for entry in info.get("CFBundleURLTypes", []) for scheme in entry.get("CFBundleURLSchemes", [])}
    if not {"streambridge", "nuvio", "stremio"} <= schemes:
        raise ValueError("StreamBridge URL scheme/legacy compatibility is missing")
    if not isinstance(info.get("UILaunchScreen"), dict) or "UILaunchScreen" in info["UILaunchScreen"]:
        raise ValueError("Invalid launch screen metadata")
    validate_bundle(app / "PlugIns/DownloadsWidgetExtension.appex",
                    "com.streambridge.app.DownloadsWidgetExtension", "StreamBridge Downloads", mode)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--mode", choices=("unsigned", "signed"), required=True)
    args = parser.parse_args()
    if args.artifact.suffix == ".ipa":
        with zipfile.ZipFile(args.artifact) as archive:
            for name in archive.namelist():
                if not name.startswith("Payload/") or ".." in Path(name).parts:
                    raise ValueError("Unexpected IPA archive path")
        with tempfile.TemporaryDirectory(prefix="streambridge-ipa-check-") as temporary:
            # ditto preserves symlinks and framework signatures during extraction.
            subprocess.run(["ditto", "-x", "-k", str(args.artifact), temporary], check=True)
            validate_app(Path(temporary) / "Payload/StreamBridge.app", args.mode)
    else:
        validate_app(args.artifact / "Products/Applications/StreamBridge.app", args.mode)
    print(f"Verified StreamBridge {args.mode} artifact: {args.artifact.name}")


if __name__ == "__main__":
    main()
