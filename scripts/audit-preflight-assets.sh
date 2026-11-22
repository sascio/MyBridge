#!/usr/bin/env bash
# Inspect a separately downloaded, non-published production-build artifact set.
set -euo pipefail
[[ $# -eq 2 ]] || { echo 'Usage: audit-preflight-assets.sh ANDROID_DIR IOS_DIR|--android-only' >&2; exit 2; }
android="$(cd "$1" && pwd)"
if [[ "$2" != --android-only ]]; then ios="$(cd "$2" && pwd)"; fi
expected_cert=5da621d8e6f5c4ffb7396de3fbf686f3bf61bcec71cccffba9bbbbdef56285fc
build_tools="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/build-tools/35.0.0"
aapt="$build_tools/aapt"; apksigner="$build_tools/apksigner"
[[ -x "$aapt" && -x "$apksigner" ]] || { echo 'Missing Android inspection tools' >&2; exit 1; }
python3 - "$android" <<'PY'
import hashlib, pathlib, re, sys
root = pathlib.Path(sys.argv[1]); manifest = root / 'checksums.sha256'
expected = {f'androidApp-full-{abi}-release.apk' for abi in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')}
seen = set()
for line in manifest.read_text().splitlines():
    match = re.fullmatch(r'([0-9a-fA-F]{64})\s+\*?(\S+)', line)
    if not match: raise SystemExit('Malformed checksum line')
    digest, name = match.groups()
    path = pathlib.PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or path.name not in expected or path.name in seen:
        raise SystemExit('Unexpected or duplicate APK checksum entry')
    allowed = pathlib.PurePosixPath('androidApp/build/outputs/apk/full/release') / path.name
    if path not in (pathlib.PurePosixPath(path.name), allowed): raise SystemExit('Unexpected APK checksum path')
    target = root.joinpath(*allowed.parts)
    if not target.is_file(): raise SystemExit(f'Missing APK: {path.name}')
    with target.open('rb') as f: actual = hashlib.file_digest(f, 'sha256').hexdigest()
    if actual != digest.lower(): raise SystemExit(f'Checksum mismatch: {path.name}')
    seen.add(path.name)
if seen != expected: raise SystemExit('APK checksum set is incomplete')
PY
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  apk="$android/androidApp/build/outputs/apk/full/release/androidApp-full-${abi}-release.apk"
  [[ -f "$apk" ]] || { echo "Missing $apk" >&2; exit 1; }
  badging="$("$aapt" dump badging "$apk")"
  package_line="$(printf '%s\n' "$badging" | grep -m1 '^package:')"
  [[ "$package_line" == *"name='com.streambridge.app'"* && "$package_line" == *"versionCode='120'"* && "$package_line" == *"versionName='0.1.20'"* ]] || { echo "Unexpected APK identity: $package_line" >&2; exit 1; }
  certs="$("$apksigner" verify --print-certs "$apk")"
  [[ "$(printf '%s\n' "$certs" | grep -c 'certificate SHA-256 digest:')" == 1 ]] || { echo 'Expected exactly one APK signer' >&2; exit 1; }
  cert="$(printf '%s\n' "$certs" | sed -n 's/.*Signer #1 certificate SHA-256 digest:[[:space:]]*//p' | head -n1 | tr 'A-F' 'a-f' | tr -d ' :')"
  [[ "$cert" == "$expected_cert" ]] || { echo 'Unexpected APK signing certificate' >&2; exit 1; }
  checksum="$(sha256sum "$apk" | awk '{print $1}')"
  echo "${checksum}  $(basename "$apk")"
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::notice title=Verified production APK::$(basename "$apk") SHA-256 ${checksum}; package com.streambridge.app; version 0.1.20 (120); signer ${cert}"
  fi
done
if [[ "$2" == --android-only ]]; then
  echo "Downloaded Android APKs independently verified."
  exit 0
fi
python3 - "$ios/StreamBridge-0.1.20-iOS-unsigned.ipa" <<'PY'
import hashlib, plistlib, sys, zipfile
from pathlib import PurePosixPath
path = sys.argv[1]
with zipfile.ZipFile(path) as z:
    if z.testzip() is not None: raise SystemExit('IPA archive failed CRC validation')
    names = z.namelist()
    if len(names) != len(set(names)): raise SystemExit('IPA contains duplicate entries')
    app_infos = [n for n in names if len(PurePosixPath(n).parts) == 3 and PurePosixPath(n).parts[0] == 'Payload' and PurePosixPath(n).parts[1].endswith('.app') and PurePosixPath(n).parts[2] == 'Info.plist']
    if len(app_infos) != 1: raise SystemExit('Expected exactly one app Info.plist')
    if any('_CodeSignature/' in n or n.lower().endswith('embedded.mobileprovision') for n in names): raise SystemExit('IPA must be unsigned')
    info = plistlib.loads(z.read(app_infos[0]))
    if (info.get('CFBundleIdentifier'), info.get('CFBundleShortVersionString'), str(info.get('CFBundleVersion'))) != ('com.streambridge.app', '0.1.20', '120'):
        raise SystemExit('IPA bundle identity or version mismatch')
with open(path, 'rb') as f:
    print(hashlib.file_digest(f, 'sha256').hexdigest(), path)
print('Downloaded preflight assets verified; no release was published.')
PY
