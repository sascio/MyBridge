#!/usr/bin/env bash
# Adapted from the verified Nuvio Mobile / Enhanced 0.5.5-beta bootstrap.
set -euo pipefail
repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
engine_version=0.1.2
engine_checksum=ed35576d962930d3207b2725fa737f71ef080e35dbfa2f0bf6f21ab1719b7029
engine_root="${NUVIO_ENGINE_ROOT:-${repository_root}/.cache/ios/nuvio-engine}"
engine_framework="${engine_root}/platform/apple/NuvioEngine.xcframework"
mpv_commit=bb1d0250ddcfa9d220761fcdad6011ac9fecf15e

git -C "${repository_root}" submodule update --init --depth 1 MPVKit
if [[ "$(git -C "${repository_root}/MPVKit" rev-parse HEAD)" != "${mpv_commit}" ]]; then
    echo "MPVKit does not match the verified 0.5.5-beta dependency pin." >&2
    exit 1
fi

marker="${engine_root}/.streambridge-engine-sha256"
if [[ -f "${engine_framework}/Info.plist" && -f "${marker}" ]] &&
   [[ "$(cat "${marker}")" == "${engine_checksum}" ]] &&
   [[ -f "${engine_framework}/ios-arm64/libCNuvioEngine.a" ]]; then
    exit 0
fi

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/streambridge-ios-dependencies.XXXXXX")"
trap 'rm -rf "${temporary_directory}"' EXIT
archive="${temporary_directory}/nuvio-engine-apple-${engine_version}.zip"
curl --fail --location --retry 5 --retry-all-errors --silent --show-error \
    --output "${archive}" \
    "https://github.com/NuvioMedia/nuvio-engine/releases/download/v${engine_version}/nuvio-engine-apple-${engine_version}.zip"
actual_checksum="$(shasum -a 256 "${archive}" | awk '{print $1}')"
if [[ "${actual_checksum}" != "${engine_checksum}" ]]; then
    echo "Upstream engine Apple package checksum mismatch; refusing the dependency." >&2
    exit 1
fi
extraction_root="${temporary_directory}/extracted"
mkdir -p "${extraction_root}"
unzip -q "${archive}" -d "${extraction_root}"
source_framework="$(find "${extraction_root}" -type d -name NuvioEngine.xcframework -print -quit)"
if [[ -z "${source_framework}" || ! -f "${source_framework}/Info.plist" ||
      ! -f "${source_framework}/ios-arm64/libCNuvioEngine.a" ]]; then
    echo "Upstream engine package is missing the required Apple framework/arm64 library." >&2
    exit 1
fi
mkdir -p "$(dirname "${engine_framework}")"
rm -rf "${engine_framework}"
ditto "${source_framework}" "${engine_framework}"
printf '%s\n' "${engine_checksum}" > "${marker}"
