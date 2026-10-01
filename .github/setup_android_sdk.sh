#!/usr/bin/env bash
set -euo pipefail
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
sdkmanager="$(command -v sdkmanager || printf '%s' "${sdk}/cmdline-tools/latest/bin/sdkmanager")"
# AGP 9.2 maps compileSdk 37 + minor 0 to android-37.0, not android-37.
platform="$(python3 - <<'PY'
import tomllib
with open('gradle/libs.versions.toml','rb') as source:
    versions=tomllib.load(source)['versions']
print('android-'+versions['android-compileSdk']+'.'+versions['android-compileSdkMinor'])
PY
)"
yes | "${sdkmanager}" --licenses >/dev/null 2>&1 || true
if [[ ! -d "${sdk}/platforms/${platform}" ]]; then
    "${sdkmanager}" --install "platforms;${platform}"
fi
if [[ ! -d "${sdk}/build-tools/36.0.0" ]]; then
    "${sdkmanager}" --install 'build-tools;36.0.0'
fi
if [[ -n "${GITHUB_ENV:-}" ]]; then
    printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "${sdk}" "${sdk}" >> "${GITHUB_ENV}"
fi
