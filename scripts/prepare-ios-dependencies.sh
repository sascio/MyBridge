#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
engine_version=0.1.6
engine_checksum=f556718ec0fd122a61bc5408307b19fcd54397595954c46c6ff8db1e5a179421
engine_root="${NUVIO_ENGINE_ROOT:-${repository_root}/../nuvio-engine}"
engine_framework="${engine_root}/platform/apple/NuvioEngine.xcframework"
# MPVKit is the local Swift package referenced by the Xcode project. Pin the
# exact gitlink in Official Nuvio 0.6.12-beta first, then apply only the three
# MPVKit files changed by Enhanced 0.6.13-beta relative to that Official pin.
# This keeps Official as the native build baseline while adding Enhanced's
# Dolby Vision RPU callback, audio filters, and checksum-pinned nuvio.7 binaries.
readonly OFFICIAL_MPVKIT_REPO="https://github.com/NuvioMedia/MPVKit.git"
readonly OFFICIAL_MPVKIT_COMMIT="da4b7da256a5d67dee74f576762ceb9067fc7a71"
readonly ENHANCED_MPVKIT_COMMIT="359a885342d4eeda757f8e1d2c24fb01fe6d39c2"
readonly MPVKIT_OVERLAY="${repository_root}/scripts/patches/mpvkit-enhanced-0.6.13.patch"
mpvkit_directory="${repository_root}/MPVKit"
mpvkit_marker="${mpvkit_directory}/.streambridge-mpvkit-revision"
expected_marker="$(printf 'official=%s\nenhanced-overlay=%s' "${OFFICIAL_MPVKIT_COMMIT}" "${ENHANCED_MPVKIT_COMMIT}")"

if [[ ! -f "${MPVKIT_OVERLAY}" ]]; then
    echo "Pinned Enhanced MPVKit overlay is missing: ${MPVKIT_OVERLAY}" >&2
    exit 1
fi

if [[ ! -f "${mpvkit_directory}/Package.swift" ]]; then
    if [[ -e "${mpvkit_directory}" ]]; then
        echo "MPVKit path exists but has no Package.swift; refusing to replace local files." >&2
        exit 1
    fi
    mkdir -p "${mpvkit_directory}"
    if ! (
        cd "${mpvkit_directory}"
        git init --quiet
        git remote add origin "${OFFICIAL_MPVKIT_REPO}"
        git fetch --quiet --depth 1 origin "${OFFICIAL_MPVKIT_COMMIT}"
        git checkout --quiet FETCH_HEAD
    ); then
        echo "Could not fetch Official Nuvio MPVKit at ${OFFICIAL_MPVKIT_COMMIT}." >&2
        rm -rf "${mpvkit_directory}"
        exit 1
    fi
fi

if [[ ! -f "${mpvkit_directory}/Package.swift" || ! -d "${mpvkit_directory}/.git" ]]; then
    echo "MPVKit must be a Git checkout of the pinned Official source." >&2
    exit 1
fi
actual_base="$(git -C "${mpvkit_directory}" rev-parse HEAD^{commit})"
if [[ "${actual_base}" != "${OFFICIAL_MPVKIT_COMMIT}" ]]; then
    echo "MPVKit base is ${actual_base}; required Official pin is ${OFFICIAL_MPVKIT_COMMIT}." >&2
    exit 1
fi
if [[ -f "${mpvkit_marker}" ]]; then
    if [[ "$(cat "${mpvkit_marker}")" != "${expected_marker}" ]]; then
        echo "MPVKit preparation marker does not match the approved Official/Enhanced pins." >&2
        exit 1
    fi
else
    if ! git -C "${mpvkit_directory}" apply --check "${MPVKIT_OVERLAY}"; then
        echo "Enhanced MPVKit overlay does not apply cleanly to the Official pin." >&2
        exit 1
    fi
    git -C "${mpvkit_directory}" apply "${MPVKIT_OVERLAY}"
    printf '%b\n' "${expected_marker}" > "${mpvkit_marker}"
fi

if ! grep -Fq "0.41.0-n8.1.2-nuvio.7" "${mpvkit_directory}/Package.swift" \
    || ! grep -Fq "nuvio_pip_dovi_metadata" "${mpvkit_directory}/Sources/BuildScripts/patch/libmpv/0007-optional-ios-picture-in-picture.patch" \
    || ! grep -Fq -- '"--enable-filter=acompressor"' "${mpvkit_directory}/Sources/BuildScripts/XCFrameworkBuild/main.swift"; then
    echo "Prepared MPVKit does not contain the verified Enhanced overlay behavior." >&2
    exit 1
fi

if [[ -f "${engine_framework}/Info.plist" ]]; then
    exit 0
fi

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ios-dependencies.XXXXXX")"
trap 'rm -rf "${temporary_directory}"' EXIT

archive="${temporary_directory}/nuvio-engine-apple-${engine_version}.zip"
curl --fail --location --retry 5 --retry-all-errors --silent --show-error \
    --output "${archive}" \
    "https://github.com/NuvioMedia/nuvio-engine/releases/download/v${engine_version}/nuvio-engine-apple-${engine_version}.zip"

actual_checksum="$(shasum -a 256 "${archive}" | awk '{print $1}')"
if [[ "${actual_checksum}" != "${engine_checksum}" ]]; then
    echo "Nuvio Engine Apple package checksum mismatch." >&2
    exit 1
fi

extraction_root="${temporary_directory}/extracted"
mkdir -p "${extraction_root}"
unzip -q "${archive}" -d "${extraction_root}"
source_framework="$(find "${extraction_root}" -type d -name NuvioEngine.xcframework -print -quit)"
if [[ -z "${source_framework}" || ! -f "${source_framework}/Info.plist" ]]; then
    echo "Nuvio Engine Apple package does not contain NuvioEngine.xcframework." >&2
    exit 1
fi
if [[ ! -f "${source_framework}/ios-arm64/libCNuvioEngine.a" ]]; then
    echo "Nuvio Engine Apple package does not contain the iOS arm64 library." >&2
    exit 1
fi

mkdir -p "$(dirname "${engine_framework}")"
ditto "${source_framework}" "${engine_framework}"
