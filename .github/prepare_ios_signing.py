#!/usr/bin/env python3
"""Install validated StreamBridge distribution signing inputs on an ephemeral macOS runner.

Private inputs are read only from the environment, never logged. --cleanup
restores the original keychain search list and removes only installed files.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import secrets
import subprocess
import sys

REQUIRED = ("IOS_DISTRIBUTION_P12_BASE64", "IOS_DISTRIBUTION_P12_PASSWORD",
            "IOS_APP_PROVISIONING_PROFILE_BASE64", "IOS_WIDGET_PROVISIONING_PROFILE_BASE64", "IOS_TEAM_ID")
APP_ID = "com.streambridge.app"
WIDGET_ID = "com.streambridge.app.DownloadsWidgetExtension"
METHODS = {"release-testing", "app-store-connect", "enterprise"}


def run(command: list[str]) -> bytes:
    result = subprocess.run(command, capture_output=True)
    if result.returncode:
        # Command arguments can include a password. Never render the command or
        # CalledProcessError; Apple's tool output can include profile contents.
        raise ValueError(f"{Path(command[0]).name} failed during signing setup (exit {result.returncode})")
    return result.stdout


def decode(value: str, destination: Path) -> None:
    try:
        contents = base64.b64decode("".join(value.split()), validate=True)
    except (ValueError, base64.binascii.Error):
        raise ValueError("A signing input is not valid base64") from None
    if not contents:
        raise ValueError("A signing input decoded to an empty file")
    destination.write_bytes(contents)
    destination.chmod(0o600)


def validate_profile(profile: dict, team: str, bundle: str, method: str, now: datetime | None = None) -> str:
    if method not in METHODS:
        raise ValueError("Unsupported iOS export method")
    if profile.get("TeamIdentifier") != [team]:
        raise ValueError("Provisioning profile belongs to a different Apple team")
    entitlements = profile.get("Entitlements", {})
    if entitlements.get("application-identifier") != f"{team}.{bundle}":
        raise ValueError(f"Provisioning profile must explicitly match {bundle}")
    if entitlements.get("com.apple.developer.team-identifier") != team:
        raise ValueError("Provisioning entitlement team does not match")
    if entitlements.get("get-task-allow", False):
        raise ValueError("Development profiles are not production distribution profiles")
    expires = profile.get("ExpirationDate")
    if not isinstance(expires, datetime):
        raise ValueError("Provisioning profile has no expiration date")
    if expires.replace(tzinfo=timezone.utc) <= (now or datetime.now(timezone.utc)):
        raise ValueError("Provisioning profile is expired")
    uuid = profile.get("UUID", "")
    if not re.fullmatch(r"[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}", uuid):
        raise ValueError("Provisioning profile UUID is invalid")
    devices = profile.get("ProvisionedDevices", [])
    enterprise = profile.get("ProvisionsAllDevices", False)
    if method == "release-testing" and (not devices or enterprise):
        raise ValueError("release-testing requires Ad Hoc profiles with registered device UDIDs")
    if method == "app-store-connect" and (devices or enterprise):
        raise ValueError("app-store-connect requires App Store distribution profiles")
    if method == "enterprise" and not enterprise:
        raise ValueError("enterprise requires an Enterprise distribution profile")
    if not profile.get("DeveloperCertificates"):
        raise ValueError("Provisioning profile contains no distribution certificate")
    return uuid


def cleanup(directory: Path) -> None:
    state_path = directory / "cleanup.json"
    if not state_path.is_file():
        return
    state = json.loads(state_path.read_text())
    failures = []
    for keychain in state.get("keychain", []):
        if Path(keychain).parent != directory:
            raise ValueError("Unsafe keychain cleanup path")
        try:
            run(["security", "delete-keychain", keychain])
        except ValueError as error:
            failures.append(str(error))
    search_list = state.get("search_list", [])
    if search_list:
        run(["security", "list-keychains", "-d", "user", "-s", *search_list])
    for path in state.get("profiles", []):
        target = Path(path)
        if target.parent != Path.home() / "Library/MobileDevice/Provisioning Profiles":
            raise ValueError("Unsafe profile cleanup path")
        target.unlink(missing_ok=True)
    for path in directory.iterdir():
        if path.is_file():
            path.unlink()
    if failures:
        raise ValueError("; ".join(failures))


def prepare(directory: Path, output: Path) -> None:
    missing = [name for name in REQUIRED if not os.environ.get(name)]
    if missing:
        raise ValueError("Missing required GitHub Secrets at iOS signing stage: " + ", ".join(missing) + ". See docs/IOS.md.")
    team = os.environ["IOS_TEAM_ID"]
    if not re.fullmatch(r"[A-Z0-9]{10}", team):
        raise ValueError("IOS_TEAM_ID must be your ten-character Apple team identifier")
    method = os.environ.get("IOS_EXPORT_METHOD", "release-testing")
    if method not in METHODS:
        raise ValueError("Unsupported iOS export method")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    state_path = directory / "cleanup.json"
    old_keychains = re.findall(r'"([^"]+)"', run(["security", "list-keychains", "-d", "user"]).decode())
    state = {"search_list": old_keychains, "keychain": [], "profiles": []}
    def save_state():
        state_path.write_text(json.dumps(state))
        state_path.chmod(0o600)
    save_state()
    p12 = directory / "distribution.p12"
    decode(os.environ["IOS_DISTRIBUTION_P12_BASE64"], p12)
    profiles = []
    for role, secret, bundle in (("app", "IOS_APP_PROVISIONING_PROFILE_BASE64", APP_ID),
                                  ("widget", "IOS_WIDGET_PROVISIONING_PROFILE_BASE64", WIDGET_ID)):
        path = directory / f"{role}.mobileprovision"
        decode(os.environ[secret], path)
        try:
            profile = plistlib.loads(run(["security", "cms", "-D", "-i", str(path)]))
        except (plistlib.InvalidFileException, ValueError):
            raise ValueError(f"Could not decode the {role} provisioning profile") from None
        uuid = validate_profile(profile, team, bundle, method)
        profiles.append((path, profile, uuid, bundle))
    keychain = directory / "streambridge-signing.keychain-db"
    password = secrets.token_urlsafe(32)
    if os.environ.get("GITHUB_ACTIONS"):
        print(f"::add-mask::{password}")
    run(["security", "create-keychain", "-p", password, str(keychain)])
    state["keychain"] = [str(keychain)]
    save_state()
    run(["security", "set-keychain-settings", "-lut", "21600", str(keychain)])
    run(["security", "unlock-keychain", "-p", password, str(keychain)])
    run(["security", "import", str(p12), "-P", os.environ["IOS_DISTRIBUTION_P12_PASSWORD"],
         "-k", str(keychain), "-T", "/usr/bin/codesign", "-T", "/usr/bin/security"])
    run(["security", "set-key-partition-list", "-S", "apple-tool:,apple:", "-s", "-k", password, str(keychain)])
    run(["security", "list-keychains", "-d", "user", "-s", str(keychain), *old_keychains])
    identities = re.findall(r'\b([0-9A-F]{40})\s+"Apple Distribution[^"\n]*"',
                            run(["security", "find-identity", "-v", "-p", "codesigning", str(keychain)]).decode())
    compatible = set(identities)
    for _, profile, _, _ in profiles:
        compatible &= {hashlib.sha1(cert).hexdigest().upper() for cert in profile["DeveloperCertificates"]}
    if len(compatible) != 1:
        raise ValueError("App and widget profiles must share exactly one usable Apple Distribution identity from the P12")
    identity = compatible.pop()
    destination = Path.home() / "Library/MobileDevice/Provisioning Profiles"
    destination.mkdir(parents=True, exist_ok=True)
    for source, _, uuid, _ in profiles:
        target = destination / f"{uuid}.mobileprovision"
        if target.exists():
            raise ValueError("Refusing to replace an existing provisioning profile on the runner")
        state["profiles"].append(str(target))
        save_state()
        target.write_bytes(source.read_bytes())
        target.chmod(0o600)
    export_options = directory / "ExportOptions.plist"
    export_options.write_bytes(plistlib.dumps({
        "method": method, "teamID": team, "signingStyle": "manual", "signingCertificate": identity,
        "provisioningProfiles": {bundle: uuid for _, _, uuid, bundle in profiles},
        "manageAppVersionAndBuildNumber": False, "stripSwiftSymbols": True,
    }))
    export_options.chmod(0o600)
    # Only public identities/UUIDs/path references go to step outputs.
    values = {"identity": identity, "app_profile": profiles[0][2], "widget_profile": profiles[1][2],
              "export_options": str(export_options)}
    with output.open("a") as file:
        for key, value in values.items():
            file.write(f"{key}={value}\n")
    p12.unlink()
    print("Validated StreamBridge app/widget profiles and installed a temporary distribution identity.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cleanup", action="store_true")
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("Apple signing setup requires macOS")
    directory = Path(os.environ["RUNNER_TEMP"]) / "streambridge-ios-signing"
    try:
        if args.cleanup:
            cleanup(directory)
        else:
            prepare(directory, Path(os.environ["GITHUB_OUTPUT"]))
    except ValueError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
