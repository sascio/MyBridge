#!/usr/bin/env python3
"""Publish a StreamBridge release into the AltStore/SideStore source (store.json).

Ported from NuvioMedia/NuvioMobile 0.5.5-beta (scripts/update-store-source.py,
MIT-compatible GPL-3.0 project) and adapted to StreamBridge: the source is
validated against the StreamBridge application identity, and the release date and
download URL always come from the actual published GitHub release.

The script refuses to publish anything that is not a genuine unsigned
StreamBridge IPA:

  * the IPA must contain exactly one application bundle
  * the IPA must not be signed (no _CodeSignature entries)
  * CFBundleIdentifier must be the StreamBridge application identifier
  * CFBundleShortVersionString must equal the release version
  * the download URL must be HTTPS and end in .ipa
  * the release date must carry a timezone
"""

import argparse
import hashlib
import json
import os
import plistlib
import re
import sys
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse

PRIVACY_KEY_PATTERN = re.compile(r"^NS.+UsageDescription$")
APP_INFO_PATTERN = re.compile(r"^Payload/[^/]+\.app/Info\.plist$")
INFO_PATTERN = re.compile(r"^Payload/[^/]+\.app(?:/PlugIns/[^/]+\.appex)?/Info\.plist$")

# StreamBridge identity guards. Nuvio's script matched whatever the IPA declared;
# StreamBridge refuses to publish a Nuvio-branded or wrong-version artifact.
EXPECTED_BUNDLE_IDENTIFIER = "com.streambridge.app"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--ipa", required=True, type=Path)
    parser.add_argument("--release-notes", required=True, type=Path)
    parser.add_argument("--release-version", required=True)
    parser.add_argument("--release-date", required=True)
    parser.add_argument("--download-url", required=True)
    parser.add_argument(
        "--icon-url",
        type=str,
        default="",
        help="Refresh the source icon URL (e.g. pointing at the immutable release tag).",
    )
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def require_string(mapping: dict, key: str, context: str) -> str:
    value = mapping.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{context} is missing {key}")
    return value


def read_source(path: Path) -> dict:
    with path.open(encoding="utf-8") as source_file:
        source = json.load(source_file)
    if not isinstance(source, dict):
        raise ValueError("source root must be an object")
    apps = source.get("apps")
    if not isinstance(apps, list) or not apps:
        raise ValueError("source must contain at least one app")
    return source


def read_ipa_metadata(path: Path) -> tuple[dict, dict[str, str]]:
    if not path.is_file():
        raise ValueError(f"IPA does not exist: {path}")
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        app_info_names = [name for name in names if APP_INFO_PATTERN.fullmatch(name)]
        if len(app_info_names) != 1:
            raise ValueError(
                f"IPA must contain exactly one application Info.plist, found {len(app_info_names)}"
            )
        if any("/_CodeSignature/" in name for name in names):
            raise ValueError("IPA must be unsigned")
        app_info = plistlib.loads(archive.read(app_info_names[0]))
        privacy: dict[str, str] = {}
        for name in names:
            if not INFO_PATTERN.fullmatch(name):
                continue
            info = plistlib.loads(archive.read(name))
            for key, value in info.items():
                if not PRIVACY_KEY_PATTERN.fullmatch(key) or not isinstance(value, str):
                    continue
                existing = privacy.get(key)
                if existing is not None and existing != value:
                    raise ValueError(f"IPA contains conflicting values for {key}")
                privacy[key] = value
    return app_info, privacy


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source_file:
        for chunk in iter(lambda: source_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_release_date(value: str) -> None:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("release date must include a timezone")


def validate_download_url(value: str) -> None:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.netloc or not parsed.path.endswith(".ipa"):
        raise ValueError("download URL must be an HTTPS IPA URL")


def validate_icon_url(value: str) -> None:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.netloc:
        raise ValueError("icon URL must be an HTTPS URL")


def find_app(source: dict, bundle_identifier: str) -> dict:
    matches = [app for app in source["apps"] if app.get("bundleIdentifier") == bundle_identifier]
    if len(matches) != 1:
        raise ValueError(
            f"source must contain exactly one app with bundle identifier {bundle_identifier}"
        )
    return matches[0]


def update_versions(app: dict, entry: dict) -> None:
    """Prepend the new version, refusing to republish an existing one.

    A version or build number that is already published is an error rather than an
    overwrite: an unsigned IPA is not byte-reproducible, so a rebuild of the same
    version would silently swap the binary behind a version users already installed.
    Rebuilt artifacts must be published under a new version.
    """
    versions = app.get("versions")
    if not isinstance(versions, list):
        raise ValueError("source app versions must be an array")
    for version in versions:
        if not isinstance(version, dict):
            raise ValueError("source app version entries must be objects")
        existing_version = version.get("version")
        existing_build = version.get("buildVersion")
        if existing_version == entry["version"]:
            raise ValueError(f"version {entry['version']} is already published")
        if existing_build == entry["buildVersion"]:
            raise ValueError(f"build {entry['buildVersion']} is already published")
    app["versions"] = [entry, *versions]


def write_source(path: Path, source: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output_file:
            json.dump(source, output_file, ensure_ascii=False, indent=2)
            output_file.write("\n")
        os.replace(temporary_name, path)
    except BaseException:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
        raise


def main() -> int:
    args = parse_args()
    source = read_source(args.source)
    app_info, privacy = read_ipa_metadata(args.ipa)

    bundle_identifier = require_string(app_info, "CFBundleIdentifier", "application Info.plist")
    if bundle_identifier != EXPECTED_BUNDLE_IDENTIFIER:
        raise ValueError(
            f"IPA bundle identifier {bundle_identifier} is not {EXPECTED_BUNDLE_IDENTIFIER}"
        )
    version = require_string(app_info, "CFBundleShortVersionString", "application Info.plist")
    build_version = require_string(app_info, "CFBundleVersion", "application Info.plist")
    minimum_os = require_string(app_info, "MinimumOSVersion", "application Info.plist")
    if version != args.release_version:
        raise ValueError(f"IPA version {version} does not match release version {args.release_version}")

    validate_release_date(args.release_date)
    validate_download_url(args.download_url)
    if args.icon_url:
        validate_icon_url(args.icon_url)

    release_notes = args.release_notes.read_bytes().decode("utf-8")
    if not release_notes.strip():
        raise ValueError("release notes must not be empty")

    app = find_app(source, bundle_identifier)
    permissions = app.get("appPermissions")
    if not isinstance(permissions, dict):
        raise ValueError("source app must contain appPermissions")
    permissions["entitlements"] = []
    permissions["privacy"] = privacy
    if args.icon_url:
        app["iconURL"] = args.icon_url
        source["iconURL"] = args.icon_url

    entry = {
        "version": version,
        "buildVersion": build_version,
        "date": args.release_date,
        "localizedDescription": release_notes,
        "downloadURL": args.download_url,
        "size": args.ipa.stat().st_size,
        "sha256": sha256(args.ipa),
        "minOSVersion": minimum_os,
    }
    update_versions(app, entry)

    output = args.output or args.source
    write_source(output, source)
    print(f"Updated {output} with {version} ({build_version})")
    print(f"IPA size: {entry['size']}")
    print(f"IPA SHA-256: {entry['sha256']}")
    print(f"Download URL: {entry['downloadURL']}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError, plistlib.InvalidFileException) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
