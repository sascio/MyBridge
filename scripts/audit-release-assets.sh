#!/usr/bin/env bash
set -euo pipefail

repo="sascio/MyBridge"
release_id="${RELEASE_ID:-446011741}"
target_commit="bd81e40521bfa85448a8f7f98b24216c2c7ee229"
tag="v0.1.18"
out="${RUNNER_TEMP:-/tmp}/streambridge-v0.1.18-audit"
mkdir -p "${out}"

[[ "${release_id}" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid release ID." >&2; exit 2; }
[[ -n "${GH_TOKEN:-}" ]] || { echo "GH_TOKEN is required." >&2; exit 2; }

release_json="${out}/release.json"
gh api "repos/${repo}/releases/${release_id}" >"${release_json}"
jq -e --arg tag "${tag}" --arg sha "${target_commit}" \
  '.id == 446011741 and .tag_name == $tag and .draft == true and .prerelease == false and .target_commitish == $sha' \
  "${release_json}" >/dev/null || {
  echo "Release metadata does not match the expected v0.1.18 draft and target." >&2
  exit 1
}

# Resolve the annotated tag without following a symbolic/local tag alias.
ref_json="${out}/tag-ref.json"
gh api "repos/${repo}/git/ref/tags/${tag}" >"${ref_json}"
tag_type="$(jq -r '.object.type' "${ref_json}")"
tag_sha="$(jq -r '.object.sha' "${ref_json}")"
if [[ "${tag_type}" == "tag" ]]; then
  target="$(gh api "repos/${repo}/git/tags/${tag_sha}" --jq '.object.sha')"
elif [[ "${tag_type}" == "commit" ]]; then
  target="${tag_sha}"
else
  echo "Tag resolved to unexpected object type ${tag_type}." >&2
  exit 1
fi
[[ "${target}" == "${target_commit}" ]] || {
  echo "${tag} points to ${target}, expected ${target_commit}." >&2
  exit 1
}

expected_names=(
  androidApp-full-arm64-v8a-release.apk
  androidApp-full-armeabi-v7a-release.apk
  androidApp-full-x86-release.apk
  androidApp-full-x86_64-release.apk
  StreamBridge-0.1.18-iOS-unsigned.ipa
  checksums.sha256
)
actual_names="$(jq -r '[.assets[].name] | sort | join("\n")' "${release_json}")"
expected_sorted="$(printf '%s\n' "${expected_names[@]}" | sort | paste -sd '\n' -)"
[[ "${actual_names}" == "${expected_sorted}" ]] || {
  echo "Release asset list is not exactly the expected five binaries plus checksums.sha256." >&2
  printf 'Expected:\n%s\nActual:\n%s\n' "${expected_sorted}" "${actual_names}" >&2
  exit 1
}

# Download every asset via the authenticated GitHub Releases API. The hosted
# runner follows GitHub's short-lived release-assets redirect; no local cached
# build artifact is substituted for a release download.
while IFS=$'\t' read -r name asset_id state api_digest api_size; do
  [[ "${state}" == "uploaded" ]] || { echo "${name} is not uploaded (state=${state})." >&2; exit 1; }
  [[ "${api_digest}" == sha256:* ]] || { echo "GitHub has no SHA-256 digest for ${name}." >&2; exit 1; }
  path="${out}/${name}"
  asset_url="$(jq -r --argjson id "${asset_id}" '.assets[] | select(.id == $id) | .browser_download_url' "${release_json}")"
  [[ "${asset_url}" == https://github.com/${repo}/releases/download/* ]] || {
    echo "Unexpected browser download URL for ${name}: ${asset_url}." >&2; exit 1;
  }
  curl --silent --show-error --fail-with-body --location \
    --retry 3 --retry-all-errors --connect-timeout 30 --max-time 900 \
    -H "Authorization: Bearer ${GH_TOKEN}" \
    -H 'Accept: application/octet-stream' \
    "${asset_url}" --output "${path}"
  [[ -s "${path}" ]] || { echo "Downloaded ${name} is empty." >&2; exit 1; }
  actual_size="$(wc -c <"${path}" | tr -d ' ')"
  actual_sha="$(sha256sum "${path}" | awk '{print $1}')"
  [[ "${actual_size}" == "${api_size}" ]] || {
    echo "${name} size mismatch: download=${actual_size}, API=${api_size}." >&2; exit 1;
  }
  [[ "sha256:${actual_sha}" == "${api_digest}" ]] || {
    echo "${name} digest mismatch: download=sha256:${actual_sha}, API=${api_digest}." >&2; exit 1;
  }
  printf '%s\t%s\t%s\n' "${name}" "${actual_size}" "${actual_sha}"
done < <(jq -r '.assets[] | [.name,.id,.state,.digest,.size] | @tsv' "${release_json}") | tee "${out}/verified-assets.tsv"

# Check the shipped manifest against the bytes fetched from the actual release.
declare -A expected_apk_hashes=()
while read -r digest filename; do
  [[ "${digest}" =~ ^[[:xdigit:]]{64}$ && -n "${filename:-}" ]] || {
    echo "Malformed checksums.sha256 line." >&2; exit 1;
  }
  base="$(basename -- "${filename}")"
  [[ "${base}" == androidApp-full-*.apk ]] || {
    echo "Unexpected entry in checksums.sha256: ${filename}." >&2; exit 1;
  }
  [[ -z "${expected_apk_hashes[${base}]+x}" ]] || {
    echo "Duplicate checksum entry for ${base}." >&2; exit 1;
  }
  expected_apk_hashes["${base}"]="${digest,,}"
done < "${out}/checksums.sha256"
[[ "${#expected_apk_hashes[@]}" -eq 4 ]] || {
  echo "checksums.sha256 must contain exactly four Android APK entries." >&2; exit 1;
}

build_tools="${ANDROID_HOME:-${ANDROID_SDK_ROOT}}/build-tools/35.0.0"
aapt="${build_tools}/aapt"
apksigner="${build_tools}/apksigner"
[[ -x "${aapt}" && -x "${apksigner}" ]] || {
  echo "Android aapt/apksigner tools are missing from ${build_tools}." >&2; exit 1;
}

cert_sha=""
apks=(
  androidApp-full-arm64-v8a-release.apk
  androidApp-full-armeabi-v7a-release.apk
  androidApp-full-x86-release.apk
  androidApp-full-x86_64-release.apk
)
for name in "${apks[@]}"; do
  apk="${out}/${name}"
  [[ -n "${expected_apk_hashes[${name}]+x}" ]] || {
    echo "No checksum entry for ${name}." >&2; exit 1;
  }
  actual_sha="$(sha256sum "${apk}" | awk '{print $1}')"
  [[ "${actual_sha}" == "${expected_apk_hashes[${name}]}" ]] || {
    echo "checksums.sha256 does not match the downloaded ${name}." >&2; exit 1;
  }
  badging="$("${aapt}" dump badging "${apk}")"
  package_line="$(printf '%s\n' "${badging}" | grep -m1 '^package:')"
  [[ "${package_line}" == *"name='com.streambridge.app'"* \
    && "${package_line}" == *"versionCode='118'"* \
    && "${package_line}" == *"versionName='0.1.18'"* ]] || {
    echo "Unexpected Android package/version in ${name}: ${package_line}." >&2; exit 1;
  }
  signature="$("${apksigner}" verify --print-certs "${apk}")"
  cert_line="$(printf '%s\n' "${signature}" | grep -m1 'Signer #1 certificate SHA-256 digest:')"
  normalized_cert="$(printf '%s' "${cert_line#*: }" | tr 'A-F' 'a-f' | tr -d ' :')"
  [[ "${normalized_cert}" =~ ^[[:xdigit:]]{64}$ ]] || {
    echo "Could not validate the signer certificate for ${name}." >&2; exit 1;
  }
  if [[ -z "${cert_sha}" ]]; then cert_sha="${normalized_cert}"; fi
  [[ "${normalized_cert}" == "${cert_sha}" ]] || {
    echo "Android APKs do not share one production signing certificate." >&2; exit 1;
  }
  echo "Verified Android: ${name}, package=com.streambridge.app, version=0.1.18, versionCode=118, cert-sha256=${normalized_cert}"
done

python3 - "${out}/StreamBridge-0.1.18-iOS-unsigned.ipa" <<'PY'
import plistlib
import sys
import zipfile
from pathlib import PurePosixPath

ipa = sys.argv[1]
with zipfile.ZipFile(ipa) as archive:
    names = archive.namelist()
    app_infos = [
        name for name in names
        if len(PurePosixPath(name).parts) == 3
        and PurePosixPath(name).parts[0] == "Payload"
        and PurePosixPath(name).parts[1].endswith(".app")
        and PurePosixPath(name).parts[2] == "Info.plist"
    ]
    if len(app_infos) != 1:
        raise SystemExit(f"Expected exactly one app Info.plist; found {len(app_infos)}")
    if any("_CodeSignature/" in name for name in names):
        raise SystemExit("The IPA contains a code signature; expected unsigned output")
    if any(name.lower().endswith("embedded.mobileprovision") for name in names):
        raise SystemExit("The IPA contains a provisioning profile; expected unsigned output")
    info = plistlib.loads(archive.read(app_infos[0]))

bundle = info.get("CFBundleIdentifier")
version = info.get("CFBundleShortVersionString")
code = str(info.get("CFBundleVersion", ""))
if (bundle, version, code) != ("com.streambridge.app", "0.1.18", "118"):
    raise SystemExit(f"Unexpected iOS metadata: bundle={bundle}, version={version}, code={code}")
print(f"Verified iOS: {app_infos[0]}, bundle={bundle}, version={version}, build={code}, unsigned=true")
PY

{
  echo "### v0.1.18 production release asset audit"
  echo
  echo "- Release ID: ${release_id} (draft; not published by this audit)."
  echo "- Tag: ${tag} -> ${target_commit}."
  echo "- Downloaded and SHA-256-verified all four APKs, the unsigned IPA, and checksums.sha256 against GitHub's asset metadata."
  echo "- checksums.sha256 matches the actual downloaded APKs."
  echo "- Android package/version/versionCode and shared production signer verified."
  echo "- iOS bundle/version/build and unsigned status verified."
  echo
  echo '| Asset | Bytes | SHA-256 |'
  echo '|---|---:|---|'
  while IFS=$'\t' read -r name size digest; do
    echo "| \`${name}\` | ${size} | \`${digest}\` |"
  done < "${out}/verified-assets.tsv"
} >>"${GITHUB_STEP_SUMMARY}"
