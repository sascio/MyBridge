#!/usr/bin/env bash
# Unit tests for draft-safe GitHub Release lookup, asset upload and notes updates.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RELEASE_API="${REPOSITORY_ROOT}/scripts/release-api.sh"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

failures=0
checks=0

pass() { printf '  ok   %s\n' "$1"; checks=$((checks + 1)); }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

mkdir -p "${WORKDIR}/bin"
cat >"${WORKDIR}/bin/gh" <<'FAKE_GH'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "api" ]] || { echo "unexpected gh command" >&2; exit 90; }
shift
method=GET
endpoint=""
input=""
while (($#)); do
  case "$1" in
    --method) method="${2:-}"; shift 2 ;;
    --input) input="${2:-}"; shift 2 ;;
    --paginate) shift ;;
    *)
      if [[ -z "${endpoint}" ]]; then endpoint="$1"; fi
      shift
      ;;
  esac
done
case "${method}:${endpoint}" in
  GET:repos/test/repo/releases\?per_page=100)
    cat "${FAKE_RELEASE_LIST_FILE}"
    ;;
  GET:repos/test/repo/releases/42)
    if [[ -f "${FAKE_PATCH_MARKER:-}" ]]; then
      cat "${FAKE_RELEASE_AFTER_PATCH_FILE}"
    elif [[ -f "${FAKE_UPLOAD_MARKER:-}" ]]; then
      cat "${FAKE_RELEASE_AFTER_UPLOAD_FILE}"
    else
      cat "${FAKE_RELEASE_FILE}"
    fi
    ;;
  PATCH:repos/test/repo/releases/42)
    [[ -n "${input}" && -f "${input}" ]] || { echo "missing --input" >&2; exit 91; }
    cp "${input}" "${FAKE_PATCH_CAPTURE}"
    touch "${FAKE_PATCH_MARKER}"
    printf '{"ok":true}\n'
    ;;
  *)
    echo "unexpected fake gh API request: ${method} ${endpoint}" >&2
    exit 92
    ;;
esac
FAKE_GH
cat >"${WORKDIR}/bin/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >"${FAKE_CURL_CAPTURE}"
touch "${FAKE_UPLOAD_MARKER}"
printf '{"id":987,"state":"uploaded"}\n'
FAKE_CURL
chmod +x "${WORKDIR}/bin/gh" "${WORKDIR}/bin/curl"
export PATH="${WORKDIR}/bin:${PATH}"
export GH_TOKEN="test-token"
export FAKE_RELEASE_LIST_FILE="${WORKDIR}/releases.json"
export FAKE_RELEASE_FILE="${WORKDIR}/release.json"
export FAKE_RELEASE_AFTER_UPLOAD_FILE="${WORKDIR}/after-upload.json"
export FAKE_RELEASE_AFTER_PATCH_FILE="${WORKDIR}/after-patch.json"
export FAKE_UPLOAD_MARKER="${WORKDIR}/upload.called"
export FAKE_CURL_CAPTURE="${WORKDIR}/curl.args"
export FAKE_PATCH_MARKER="${WORKDIR}/patch.called"
export FAKE_PATCH_CAPTURE="${WORKDIR}/patch.json"

asset_file="${WORKDIR}/StreamBridge-0.1.13-iOS-unsigned.ipa"
printf 'test ipa bytes\n' >"${asset_file}"
asset_size="$(wc -c <"${asset_file}" | tr -d ' ')"
asset_digest="sha256:$(sha256sum "${asset_file}" | awk '{print $1}')"

cat >"${WORKDIR}/empty-releases.json" <<'JSON'
[]
JSON
cat >"${WORKDIR}/draft-release.json" <<'JSON'
{
  "id":42,
  "tag_name":"v0.1.13",
  "name":"StreamBridge v0.1.13",
  "draft":true,
  "target_commitish":"abc123",
  "body":"## StreamBridge 0.1.13",
  "upload_url":"https://uploads.github.com/repos/test/repo/releases/42/assets{?name,label}",
  "assets":[]
}
JSON
jq -s '.' "${WORKDIR}/draft-release.json" >"${FAKE_RELEASE_LIST_FILE}"
rm -f "${FAKE_UPLOAD_MARKER}" "${FAKE_PATCH_MARKER}"

lookup_json="${WORKDIR}/lookup.json"
"${RELEASE_API}" lookup --repo test/repo --tag v0.1.13 --json "${lookup_json}" --attempts 1 --delay 0 >/dev/null
if [[ "$(jq -r '.draft' "${lookup_json}")" == "true" && "$(jq -r '.id' "${lookup_json}")" == "42" ]]; then
  pass "lookup resolves a draft through the release list"
else
  fail "lookup resolves a draft through the release list"
fi

printf '[]\n' >"${FAKE_RELEASE_LIST_FILE}"
status=0
"${RELEASE_API}" lookup --repo test/repo --tag v0.1.13 --json "${WORKDIR}/missing.json" --attempts 1 --delay 0 >/dev/null 2>&1 || status=$?
if [[ "${status}" == "3" ]]; then
  pass "lookup distinguishes a missing release from an API failure"
else
  fail "lookup distinguishes a missing release from an API failure (exit ${status})"
fi

# An identical existing asset is accepted without contacting the upload host.
jq --arg name "$(basename "${asset_file}")" --arg digest "${asset_digest}" --argjson size "${asset_size}" \
  '.assets=[{"id":1,"name":$name,"state":"uploaded","size":$size,"digest":$digest}]' \
  "${WORKDIR}/draft-release.json" >"${FAKE_RELEASE_FILE}"
cp "${FAKE_RELEASE_FILE}" "${WORKDIR}/existing.json"
rm -f "${FAKE_UPLOAD_MARKER}" "${FAKE_CURL_CAPTURE}"
"${RELEASE_API}" ensure-asset --repo test/repo --json "${WORKDIR}/existing.json" --file "${asset_file}" >/dev/null
if [[ ! -e "${FAKE_UPLOAD_MARKER}" ]]; then
  pass "identical uploaded asset is verified and never overwritten"
else
  fail "identical uploaded asset is verified and never overwritten"
fi

# A same-name but different digest fails without contacting the upload host.
jq '.assets[0].digest="sha256:deadbeef"' "${FAKE_RELEASE_FILE}" >"${WORKDIR}/mismatch.json"
rm -f "${FAKE_UPLOAD_MARKER}" "${FAKE_CURL_CAPTURE}"
status=0
"${RELEASE_API}" ensure-asset --repo test/repo --json "${WORKDIR}/mismatch.json" --file "${asset_file}" >/dev/null 2>&1 || status=$?
if [[ "${status}" != "0" && ! -e "${FAKE_UPLOAD_MARKER}" ]]; then
  pass "different existing asset is preserved and causes a hard failure"
else
  fail "different existing asset is preserved and causes a hard failure"
fi

# A missing asset is sent to the release upload_url, then re-read by release ID.
cp "${WORKDIR}/draft-release.json" "${FAKE_RELEASE_FILE}"
jq '.assets=[]' "${FAKE_RELEASE_FILE}" >"${WORKDIR}/base-no-assets.json"
cp "${WORKDIR}/base-no-assets.json" "${FAKE_RELEASE_FILE}"
jq --arg name "$(basename "${asset_file}")" --arg digest "${asset_digest}" --argjson size "${asset_size}" \
  '.assets=[{"id":2,"name":$name,"state":"uploaded","size":$size,"digest":$digest}]' \
  "${WORKDIR}/base-no-assets.json" >"${FAKE_RELEASE_AFTER_UPLOAD_FILE}"
printf '[%s]\n' "$(cat "${FAKE_RELEASE_FILE}")" >"${FAKE_RELEASE_LIST_FILE}"
rm -f "${FAKE_UPLOAD_MARKER}" "${FAKE_CURL_CAPTURE}"
"${RELEASE_API}" ensure-asset --repo test/repo --json "${FAKE_RELEASE_FILE}" --file "${asset_file}" >/dev/null
if [[ -e "${FAKE_UPLOAD_MARKER}" \
  && "$(jq -r '.assets[0].digest' "${FAKE_RELEASE_FILE}")" == "${asset_digest}" \
  && "$(cat "${FAKE_CURL_CAPTURE}")" == *"uploads.github.com/repos/test/repo/releases/42/assets?name=StreamBridge-0.1.13-iOS-unsigned.ipa"* ]]; then
  pass "missing asset uploads through upload_url and is verified by release ID"
else
  fail "missing asset uploads through upload_url and is verified by release ID"
fi

# Notes updates PATCH the release ID and do not include a draft-state change.
old_body="$(jq -r '.body' "${FAKE_RELEASE_FILE}")"
new_body="${old_body}"$'\n\n### iOS installation\n\nUnsigned test IPA.'
printf '%s\n' "${new_body}" >"${WORKDIR}/body.md"
jq --rawfile body "${WORKDIR}/body.md" '.body=$body' "${FAKE_RELEASE_FILE}" >"${FAKE_RELEASE_AFTER_PATCH_FILE}"
rm -f "${FAKE_PATCH_MARKER}"
"${RELEASE_API}" update-body --repo test/repo --json "${FAKE_RELEASE_FILE}" --body-file "${WORKDIR}/body.md" >/dev/null
if [[ "$(jq -r '.draft' "${FAKE_RELEASE_FILE}")" == "true" \
  && "$(jq -r '.body' "${FAKE_RELEASE_FILE}")" == "${new_body}" \
  && "$(jq -r 'keys | join(",")' "${FAKE_PATCH_CAPTURE}")" == "body" ]]; then
  pass "notes update by release ID preserves the draft/publication state"
else
  fail "notes update by release ID preserves the draft/publication state"
fi

printf '\n%d checks, %d failure(s)\n' "${checks}" "${failures}"
[[ "${failures}" -eq 0 ]]
