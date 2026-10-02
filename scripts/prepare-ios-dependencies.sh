#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
engine_version=0.1.2
engine_checksum=ed35576d962930d3207b2725fa737f71ef080e35dbfa2f0bf6f21ab1719b7029
engine_root="${NUVIO_ENGINE_ROOT:-${repository_root}/../nuvio-engine}"
engine_framework="${engine_root}/platform/apple/NuvioEngine.xcframework"
# MPVKit is the local Swift package the Xcode project references as ../MPVKit.
# Upstream NuvioMobile carries it as a git submodule; StreamBridge does not vendor
# the submodule, so fetch the same upstream repository when the working tree does
# not already provide it.
#
# The commit is PINNED to the revision NuvioMobile-Enhanced 0.5.5-beta recorded for
# its MPVKit submodule (d5cf091c, "Guard AudioUnit render buffers"). Cloning the
# branch tip instead would build the iOS app against a newer player than the
# release it was synced from; the pin keeps the StreamBridge 0.1.08 iOS build
# reproducible and identical to the upstream release it is based on.
# Override with NUVIO_MPVKIT_REPO / NUVIO_MPVKIT_COMMIT / NUVIO_MPVKIT_REF.
mpvkit_repo="${NUVIO_MPVKIT_REPO:-https://github.com/NuvioMedia/MPVKit.git}"
mpvkit_commit="${NUVIO_MPVKIT_COMMIT:-d5cf091c80368bbbc1bbf2d195fbc55d926df888}"
mpvkit_ref="${NUVIO_MPVKIT_REF:-}"
mpvkit_directory="${repository_root}/MPVKit"
mpvkit_marker="${mpvkit_directory}/.streambridge-mpvkit-revision"

if [[ ! -f "${mpvkit_directory}/Package.swift" ]]; then
    if [[ -f "${repository_root}/.gitmodules" ]]; then
        git -C "${repository_root}" submodule update --init --depth 1 MPVKit
    fi
fi

if [[ -f "${mpvkit_directory}/Package.swift" ]]; then
    echo "MPVKit already present at ${mpvkit_directory}; leaving it untouched."
elif [[ -n "${mpvkit_ref}" ]]; then
    echo "MPVKit is missing; fetching ${mpvkit_repo} (branch ${mpvkit_ref})."
    rm -rf "${mpvkit_directory}"
    if ! git clone --depth 1 --branch "${mpvkit_ref}" "${mpvkit_repo}" "${mpvkit_directory}"; then
        echo "Could not fetch MPVKit branch ${mpvkit_ref}." >&2
        exit 1
    fi
else
    echo "MPVKit is missing; fetching ${mpvkit_repo} at ${mpvkit_commit}."
    rm -rf "${mpvkit_directory}"
    mkdir -p "${mpvkit_directory}"
    if ! (
        cd "${mpvkit_directory}"
        git init --quiet
        git remote add origin "${mpvkit_repo}"
        git fetch --quiet --depth 1 origin "${mpvkit_commit}"
        git checkout --quiet FETCH_HEAD
    ); then
        echo "Could not fetch MPVKit at ${mpvkit_commit}." >&2
        echo "Clone it manually, or set NUVIO_MPVKIT_REPO / NUVIO_MPVKIT_COMMIT." >&2
        rm -rf "${mpvkit_directory}"
        exit 1
    fi
    printf '%s\n' "${mpvkit_commit}" > "${mpvkit_marker}"
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
