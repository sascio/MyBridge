#!/usr/bin/env python3
"""Validate and name StreamBridge Android assets; CI APKs are never production."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

from streambridge_release import ROOT, check_release

ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
FORBIDDEN_DEX = (b"Lcom/lagradost/cloudstream3/", b"Ldalvik/system/PathClassLoader;")
PRODUCTION_CERTIFICATE = "5da621d8e6f5c4ffb7396de3fbf686f3bf61bcec71cccffba9bbbbdef56285fc"


def tool(name: str) -> str:
    if result := shutil.which(name):
        return result
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = sorted((Path(sdk) / "build-tools").glob(f"*/{name}"))
        if candidates:
            return str(candidates[-1])
    raise ValueError(f"Android SDK tool not found: {name}")


def validate_apk(path: Path, abi: str, production: bool) -> None:
    release = check_release()
    badging = subprocess.check_output([tool("aapt2"), "dump", "badging", str(path)], text=True)
    if not re.search(r"package: name='com\.streambridge\.app'.*versionCode='" + str(release.build) +
                     r"'.*versionName='" + re.escape(release.version) + "'", badging):
        raise ValueError(f"Wrong package/version metadata in {path.name}")
    if "application-label:'StreamBridge'" not in badging:
        raise ValueError(f"Wrong launcher identity in {path.name}")
    with zipfile.ZipFile(path) as archive:
        native_abis = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/")}
        if native_abis != {abi}:
            raise ValueError(f"{path.name}: expected only {abi}, got {sorted(native_abis)}")
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                if any(symbol in archive.read(name) for symbol in FORBIDDEN_DEX):
                    raise ValueError(f"Forbidden CloudStream/DEX loader code in {path.name}")
    signature = subprocess.check_output([tool("apksigner"), "verify", "--print-certs", str(path)], text=True)
    if production:
        if "Android Debug" in signature:
            raise ValueError("Refusing a debug-signed production release")
        digests = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
        if not digests or any(d.lower() != PRODUCTION_CERTIFICATE for d in digests):
            raise ValueError(f"{path.name}: production certificate continuity check failed")


def package_apks(source: Path, output: Path, production: bool) -> list[Path]:
    release = check_release()
    metadata = json.loads((source / "output-metadata.json").read_text())
    if metadata.get("applicationId") != "com.streambridge.app":
        raise ValueError("Not a StreamBridge Android output directory")
    seen: set[str] = set()
    assets: list[Path] = []
    output.mkdir(parents=True, exist_ok=True)
    for element in metadata["elements"]:
        filters = element.get("filters", [])
        abi_filters = [f["value"] for f in filters if f.get("filterType") == "ABI"]
        if len(abi_filters) != 1 or abi_filters[0] not in ABIS:
            raise ValueError("Release APKs must use the four established ABI splits")
        abi = abi_filters[0]
        if abi in seen:
            raise ValueError(f"Duplicate ABI: {abi}")
        seen.add(abi)
        if element["versionCode"] != release.build or element["versionName"] != release.version:
            raise ValueError("APK output metadata does not match the release")
        filename = element["outputFile"]
        if Path(filename).name != filename:
            raise ValueError("Invalid APK output filename")
        path = source / filename
        validate_apk(path, abi, production)
        suffix = "" if production else "-not-production"
        destination = output / f"StreamBridge-{release.version}-{abi}{suffix}.apk"
        shutil.copyfile(path, destination)
        assets.append(destination)
    if seen != ABIS:
        raise ValueError(f"Missing release ABIs: {sorted(ABIS - seen)}")
    return assets


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--production", action="store_true")
    parser.add_argument("--source", type=Path, default=ROOT / "androidApp/build/outputs/apk/full/release")
    parser.add_argument("--output", type=Path, default=ROOT / "build/artifacts/android")
    parser.add_argument("--debug", action="store_true")
    parser.add_argument("--aab", action="store_true")
    args = parser.parse_args()
    assets = package_apks(args.source, args.output, args.production)
    release = check_release()
    if args.debug:
        if args.production:
            parser.error("Debug artifacts cannot accompany a production release")
        files = list((ROOT / "androidApp/build/outputs/apk/full/debug").glob("*.apk"))
        if len(files) != 1:
            raise ValueError("Expected one universal debug APK")
        target = args.output / f"StreamBridge-{release.version}-debug-not-production.apk"
        shutil.copyfile(files[0], target)
        assets.append(target)
    if args.aab:
        files = list((ROOT / "androidApp/build/outputs/bundle/fullRelease").glob("*.aab"))
        if len(files) != 1:
            raise ValueError("Expected one full release AAB")
        # AABs use JAR signing rather than APK signing. Check their signature and
        # the same public production certificate before publishing one.
        subprocess.run(["jarsigner", "-verify", str(files[0])], check=True, stdout=subprocess.DEVNULL)
        if args.production:
            cert = subprocess.check_output(["keytool", "-printcert", "-jarfile", str(files[0])], text=True)
            digest = re.search(r"SHA256:\s*([0-9A-Fa-f:]+)", cert)
            if not digest or digest[1].replace(":", "").lower() != PRODUCTION_CERTIFICATE:
                raise ValueError("AAB production certificate continuity check failed")
        suffix = "" if args.production else "-not-production"
        target = args.output / f"StreamBridge-{release.version}-full{suffix}.aab"
        shutil.copyfile(files[0], target)
        assets.append(target)
    checksums = args.output / "checksums.sha256"
    checksums.write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in sorted(assets)))
    print("\n".join(p.name for p in [*assets, checksums]))


if __name__ == "__main__":
    main()
