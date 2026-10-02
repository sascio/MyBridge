#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
engine_version=0.1.2
engine_checksum=ed35576d962930d3207b2725fa737f71ef080e35dbfa2f0bf6f21ab1719b7029
engine_root="${NUVIO_ENGINE_ROOT:-${repository_root}/../nuvio-engine}"
engine_framework="${engine_root}/platform/apple/NuvioEngine.xcframework"
# MPVKit is the local Swift package the Xcode project references as ../MPVKit.
# Upstream NuvioMobile carries it as a git submodule; StreamBridge does not vendor
# the submodule, so fetch the same upstream repository (branch Nuvio) when the
# working tree does not already provide it. Override the source with
# NUVIO_MPVKIT_REPO / NUVIO_MPVKIT_REF if you build against a fork or a pin.
mpvkit_repo="${NUVIO_MPVKIT_REPO:-https://github.com/NuvioMedia/MPVKit.git}"
mpvkit_ref="${NUVIO_MPVKIT_REF:-Nuvio}"
mpvkit_directory="${repository_root}/MPVKit"

if [[ ! -f "${mpvkit_directory}/Package.swift" ]]; then
    if [[ -f "${repository_root}/.gitmodules" ]]; then
        git -C "${repository_root}" submodule update --init --depth 1 MPVKit
    fi
fi

if [[ ! -f "${mpvkit_directory}/Package.swift" ]]; then
    echo "MPVKit is missing; fetching ${mpvkit_repo} (${mpvkit_ref}) into MPVKit/."
    rm -rf "${mpvkit_directory}"
    if ! git clone --depth 1 --branch "${mpvkit_ref}" "${mpvkit_repo}" "${mpvkit_directory}"; then
        echo "Could not fetch MPVKit. The iOS build needs it as a local Swift package at MPVKit/." >&2
        echo "Clone it manually, or set NUVIO_MPVKIT_REPO / NUVIO_MPVKIT_REF." >&2
        exit 1
    fi
fi

if [[ ! -f "${mpvkit_directory}/Package.swift" ]]; then
    echo "MPVKit/Package.swift is still missing after preparation." >&2
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
