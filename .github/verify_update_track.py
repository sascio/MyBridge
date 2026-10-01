#!/usr/bin/env python3
"""Guard the stable StreamBridge track: source pins, identity, licensing and isolation."""
from __future__ import annotations

from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

from streambridge_release import ROOT, check_release, properties

BASELINE = "ee6f99b20ef06236029979d1636ebde5b151d677"
FORBIDDEN_COMMITS = ("789a391", "6d4d5aa985039280bc9c42f48c2c386d4095c720",
                     "c0538b16f9b2fad903c48e9d512e7e0184b58af0")
BRAND = re.compile(r"nuvio(?:mobile|media)?|nuvio\.tv", re.I)
# These name a real external backend/program or credit upstream, not this app.
EXTERNAL_STRINGS = {
    "settings_licenses_attributions_nuvio_title", "settings_licenses_attributions_nuvio_body",
    "streambridge_based_on_nuvio", "streambridge_credits_based_on", "streambridge_credits_nuvio_media",
    "privacy_policy_network_addons_body", "privacy_policy_network_nuvio_body", "privacy_policy_network_nuvio_title",
    "privacy_policy_section_control_body", "privacy_policy_section_supporter_body",
    "profile_background_member_note", "compose_auth_link_open", "server_error_official_active", "server_error_official_available",
    "action_support_nuvio", "community_section_description", "community_membership_title", "community_membership_description",
    "community_membership_connected_description", "supporters_contributors_donate_button",
}


def verify(root: Path = ROOT, ancestry: bool = True) -> None:
    check_release(root)
    data = properties(root / "streambridge.version.properties")
    assert data["NUVIO_UPSTREAM_RELEASE"] == "0.5.5-beta"
    assert data["NUVIO_UPSTREAM_COMMIT"] == "5c6028f24d3017f27fc4aeb0b6234b18fcfd2ee8"
    assert data["NUVIO_ENHANCED_RELEASE"] == "0.5.5-beta"
    assert data["NUVIO_ENHANCED_COMMIT"] == "8b3bd867e172757c9fd7373da301e6489914f3fc"
    if ancestry:
        subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=root, check=True)
        commits = subprocess.check_output(["git", "rev-list", "HEAD"], cwd=root, text=True).splitlines()
        if any(commit.startswith(bad) for commit in commits for bad in FORBIDDEN_COMMITS):
            raise ValueError("The isolated update track contains forbidden CloudStream ancestry")
    for area in ("composeApp", "androidApp", "iosApp", "gradle"):
        for path in (root / area).rglob("*"):
            if not path.is_file() or path.relative_to(root / area).parts[0] == "build":
                continue
            if "cloudstream" in path.name.lower():
                raise ValueError(f"Forbidden runtime file: {path.relative_to(root)}")
            if path.suffix in {".kt", ".kts", ".java", ".swift", ".xml", ".toml", ".pro", ".def"}:
                if re.search(r"cloudstream|PathClassLoader|com\.lagradost", path.read_text(), re.I):
                    raise ValueError(f"Forbidden runtime reference: {path.relative_to(root)}")
    for path in (root / "composeApp/src/commonMain/composeResources").rglob("*.xml"):
        resource = ET.parse(path).getroot()
        if resource.tag != "resources":
            continue
        seen = set()
        for item in resource:
            key = item.attrib.get("name", "")
            if (item.tag, key) in seen:
                raise ValueError(f"Duplicate resource: {path.name}: {key}")
            seen.add((item.tag, key))
            text = "".join(item.itertext())
            if item.tag == "string" and key == "app_brand_name" and text != "StreamBridge":
                raise ValueError(f"Wrong application name in {path}")
            if BRAND.search(text) and key not in EXTERNAL_STRINGS:
                raise ValueError(f"Unclassified visible upstream branding: {path}: {key}")
    for path in (root / "composeApp/src/androidMain/res").rglob("*.xml"):
        for item in ET.parse(path).getroot():
            if item.attrib.get("name") == "app_name" and item.text != "StreamBridge":
                raise ValueError(f"Wrong Android launcher name: {path}")
    config = (root / "iosApp/Configuration/Config.xcconfig").read_text()
    project = (root / "iosApp/iosApp.xcodeproj/project.pbxproj").read_text()
    if "8QBDZ766S3" in project or "com.nuvio" in project or "Nuvio.app" in project:
        raise ValueError("iOS project still uses upstream distribution identity/signing")
    assert "PRODUCT_NAME = StreamBridge" in config
    assert "STREAMBRIDGE_APP_BUNDLE_IDENTIFIER = com.streambridge.app" in config
    assert 'name = StreamBridge;' in project
    assert (root / "iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/StreamBridge.xcscheme").is_file()
    icon = root / "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png"
    assert icon.read_bytes() == (root / "branding/streambridge-launcher-1024.png").read_bytes()
    updater = (root / "composeApp/src/commonMain/kotlin/com/nuvio/app/features/updater/AppUpdaterRepository.kt").read_text()
    assert 'GITHUB_OWNER = "sascio"' in updater and 'GITHUB_REPO = "MyBridge"' in updater
    assert '"User-Agent" to "StreamBridge"' in updater
    for name in ("LICENSE", "COPYING"):
        assert "GNU GENERAL PUBLIC LICENSE" in (root / name).read_text()
    assert "SIL OPEN FONT LICENSE" in (root / "iosApp/iosApp/SubtitleFonts/OFL.txt").read_text()
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    for name in tracked:
        if Path(name).suffix.lower() in {".jks", ".keystore", ".p12", ".p8", ".pk8", ".key", ".mobileprovision", ".provisionprofile"}:
            raise ValueError(f"Signing material must not be tracked: {name}")
    print("Verified: exact upstream pins, clean ancestry/source, StreamBridge identity, legal notices, no tracked signing inputs")


if __name__ == "__main__":
    verify()
