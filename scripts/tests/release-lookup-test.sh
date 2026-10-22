#!/usr/bin/env bash
#
# Tests for scripts/release-lookup.sh, the release lookup that publishes
# store.json. They run with a fake `gh` on PATH, so every failure mode of the
# v0.1.13 release (draft release answering 404, missing release, missing IPA,
# permission error) is reproduced without touching GitHub.
#
# Run: scripts/tests/release-lookup-test.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOOKUP="${REPOSITORY_ROOT}/scripts/release-lookup.sh"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

failures=0
checks=0

pass() { checks=$((checks + 1)); printf '  ok   %s\n' "$1"; }
fail() { checks=$((checks + 1)); failures=$((failures + 1)); printf '  FAIL %s\n' "$1"; }

assert_exit() {
  local expected="$1" actual="$2" label="$3"
  if [[ "${expected}" == "${actual}" ]]; then pass "${label}"; else fail "${label} (expected exit ${expected}, got ${actual})"; fi
}

assert_contains() {
  local haystack="$1" needle="$2" label="$3"
  if [[ "${haystack}" == *"${needle}"* ]]; then pass "${label}"; else fail "${label} (missing '${needle}')"; fi
}

assert_not_contains() {
  local haystack="$1" needle="$2" label="$3"
  if [[ "${haystack}" != *"${needle}"* ]]; then pass "${label}"; else fail "${label} (unexpected '${needle}')"; fi
}

# --- fake gh -----------------------------------------------------------------

FAKE_BIN="${WORKDIR}/bin"
mkdir -p "${FAKE_BIN}"

TAG_JSON_PUBLISHED='{"tag_name":"v0.1.13","draft":false,"published_at":"2026-10-22T05:56:15Z","body":"notes","assets":[
 {"name":"androidApp-full-arm64-v8a-release.apk","size":100,"state":"uploaded","browser_download_url":"https://github.com/sascio/MyBridge/releases/download/v0.1.13/androidApp-full-arm64-v8a-release.apk"},
 {"name":"StreamBridge-0.1.13-iOS-unsigned.ipa","size":73041169,"state":"uploaded","digest":"sha256:PLACEHOLDER","browser_download_url":"https://github.com/sascio/MyBridge/releases/download/v0.1.13/StreamBridge-0.1.13-iOS-unsigned.ipa"}]}'

cat >"${FAKE_BIN}/gh" <<'FAKE'
#!/usr/bin/env bash
# A tiny stand-in for `gh api`, driven by FAKE_GH_SCENARIO.
set -uo pipefail

scenario="${FAKE_GH_SCENARIO:-published}"
state_file="${FAKE_GH_STATE:-/dev/null}"
args=("$@")
endpoint="${args[1]:-}"

attempt=0
[[ -f "${state_file}" ]] && attempt="$(cat "${state_file}")"
attempt=$((attempt + 1))
printf '%s' "${attempt}" >"${state_file}" 2>/dev/null || true

tag_json="${FAKE_GH_TAG_JSON:?}"
list_json="${FAKE_GH_LIST_JSON:?}"

not_found() { printf 'gh: Not Found (HTTP 404)\n' >&2; exit 1; }
forbidden() { printf 'gh: Resource not accessible by integration (HTTP 403)\n' >&2; exit 1; }

case "${scenario}" in
  missing)
    if [[ "${endpoint}" == *"/releases/tags/"* ]]; then not_found; fi
    printf '[]'
    ;;
  draft)
    # A draft release answers 404 on /releases/tags/... but is listed.
    if [[ "${endpoint}" == *"/releases/tags/"* ]]; then not_found; fi
    printf '%s' "${FAKE_GH_LIST_DRAFT:?}"
    ;;
  published)
    if [[ "${endpoint}" == *"/releases/tags/"* ]]; then printf '%s' "${tag_json}"; else printf '[%s]' "${tag_json}"; fi
    ;;
  published-no-ipa)
    if [[ "${endpoint}" == *"/releases/tags/"* ]]; then printf '%s' "${FAKE_GH_NO_IPA:?}"; else printf '[%s]' "${FAKE_GH_NO_IPA}"; fi
    ;;
  published-two-ipa)
    if [[ "${endpoint}" == *"/releases/tags/"* ]]; then printf '%s' "${FAKE_GH_TWO_IPA:?}"; else printf '[%s]' "${FAKE_GH_TWO_IPA}"; fi
    ;;
  starter-state)
    if [[ "${endpoint}" == *"/releases/tags/"* ]]; then printf '%s' "${FAKE_GH_STARTER:?}"; else printf '[%s]' "${FAKE_GH_STARTER}"; fi
    ;;
  forbidden)
    forbidden
    ;;
  forbidden-list)
    if [[ "${endpoint}" == *"/releases/tags/"* ]]; then not_found; fi
    forbidden
    ;;
  flaky)
    # The release is not visible on the first attempt, then it is published.
    if [[ "${endpoint}" == *"/releases/tags/"* ]]; then
      if [[ "${attempt}" -lt 2 ]]; then not_found; fi
      printf '%s' "${tag_json}"
    else
      if [[ "${attempt}" -lt 2 ]]; then printf '[]'; else printf '[%s]' "${tag_json}"; fi
    fi
    ;;
  *)
    printf 'fake gh: unknown scenario %s\n' "${scenario}" >&2
    exit 2
    ;;
esac
FAKE
chmod +x "${FAKE_BIN}/gh"

export PATH="${FAKE_BIN}:${PATH}"
export FAKE_GH_TAG_JSON="${TAG_JSON_PUBLISHED}"
export FAKE_GH_LIST_JSON="[${TAG_JSON_PUBLISHED}]"
export FAKE_GH_LIST_DRAFT='[{"tag_name":"v0.1.13","draft":true,"assets":[]}]'
export FAKE_GH_NO_IPA='{"tag_name":"v0.1.13","draft":false,"assets":[{"name":"app.apk","size":1,"state":"uploaded","browser_download_url":"https://example.invalid/app.apk"}]}'
export FAKE_GH_TWO_IPA='{"tag_name":"v0.1.13","draft":false,"assets":[{"name":"a.ipa","size":1,"state":"uploaded","browser_download_url":"https://example.invalid/a.ipa"},{"name":"b.ipa","size":1,"state":"uploaded","browser_download_url":"https://example.invalid/b.ipa"}]}'
export FAKE_GH_STARTER='{"tag_name":"v0.1.13","draft":false,"assets":[{"name":"a.ipa","size":1,"state":"starter","browser_download_url":"https://example.invalid/a.ipa"}]}'

run_resolve() {
  local scenario="$1"; shift
  export FAKE_GH_SCENARIO="${scenario}"
  export FAKE_GH_STATE="${WORKDIR}/attempts-${scenario}"
  rm -f "${FAKE_GH_STATE}"
  output="$("${LOOKUP}" resolve --repo sascio/MyBridge --tag v0.1.13 --attempts 2 --delay 0 --json "${WORKDIR}/release-${scenario}.json" 2>&1)"
  status=$?
}

echo "release-lookup.sh resolve"

run_resolve published
assert_exit 0 "${status}" "published release with one IPA resolves"
assert_contains "${output}" "IPA_NAME=StreamBridge-0.1.13-iOS-unsigned.ipa" "IPA name is reported"
assert_contains "${output}" "IPA_SIZE=73041169" "IPA size is reported"
assert_contains "${output}" "IPA_URL=https://github.com/sascio/MyBridge/releases/download/v0.1.13/StreamBridge-0.1.13-iOS-unsigned.ipa" "download URL is reported"

run_resolve draft
assert_exit 20 "${status}" "draft release is a skip, not a failure"
assert_contains "${output}" "still a draft" "draft skip is explained"
assert_contains "${output}" "::notice::" "draft skip is a notice, not an error"

run_resolve missing
assert_exit 21 "${status}" "missing release fails after the bounded retry"
assert_contains "${output}" "does not exist" "missing release is explained"
assert_contains "${output}" "::error::" "missing release is an error"

run_resolve forbidden
assert_exit 1 "${status}" "a permission error is a hard failure"
assert_contains "${output}" "Resource not accessible" "permission error is surfaced verbatim"
assert_not_contains "${output}" "::notice::" "permission error is never downgraded to a notice"

run_resolve forbidden-list
assert_exit 1 "${status}" "an unreadable release list is a hard failure"
assert_not_contains "${output}" "::notice::" "unreadable release list is never downgraded to a notice"

run_resolve published-no-ipa
assert_exit 22 "${status}" "published release without an IPA is a successful skip"
assert_contains "${output}" "no IPA asset" "no-IPA skip is explained"

run_resolve published-two-ipa
assert_exit 23 "${status}" "two IPAs is a hard failure"
assert_contains "${output}" "2 IPA assets" "two-IPA failure is explained"

run_resolve starter-state
assert_exit 23 "${status}" "an unfinished upload is a hard failure"
assert_contains "${output}" "not 'uploaded'" "unfinished upload is explained"

run_resolve flaky
assert_exit 0 "${status}" "a release that appears after a moment is resolved by the retry"
assert_contains "${output}" "IPA_NAME=" "retried lookup still reports the IPA"

echo "release-lookup.sh verify-asset"

ipa="${WORKDIR}/StreamBridge-0.1.13-iOS-unsigned.ipa"
printf 'unsigned ipa payload' >"${ipa}"
size="$(wc -c <"${ipa}" | tr -d ' ')"
digest="sha256:$(sha256sum "${ipa}" | awk '{print $1}')"
export FAKE_GH_SCENARIO=published

matching='{"assets":[{"name":"StreamBridge-0.1.13-iOS-unsigned.ipa","size":'"${size}"',"digest":"'"${digest}"'","browser_download_url":"https://example.invalid/x.ipa"}]}'
printf '%s' "${matching}" >"${WORKDIR}/matching.json"
output="$("${LOOKUP}" verify-asset --file "${ipa}" --json "${WORKDIR}/matching.json" 2>&1)"; status=$?
assert_exit 0 "${status}" "matching size and digest verify"
assert_contains "${output}" "${digest}" "verified digest is reported"

ipa_name="StreamBridge-0.1.13-iOS-unsigned.ipa"
wrong_digest="sha256:0000000000000000000000000000000000000000000000000000000000000000"
printf '{"assets":[{"name":"%s","size":%s,"digest":"%s","browser_download_url":"https://example.invalid/x.ipa"}]}' \
  "${ipa_name}" "${size}" "${wrong_digest}" >"${WORKDIR}/mismatch-digest.json"
output="$("${LOOKUP}" verify-asset --file "${ipa}" --json "${WORKDIR}/mismatch-digest.json" 2>&1)"; status=$?
assert_exit 24 "${status}" "a digest mismatch is a hard failure"
assert_contains "${output}" "does not match" "digest mismatch is explained"

wrong_size='{"assets":[{"name":"StreamBridge-0.1.13-iOS-unsigned.ipa","size":999,"digest":"'"${digest}"'","browser_download_url":"https://example.invalid/x.ipa"}]}'
printf '%s' "${wrong_size}" >"${WORKDIR}/wrong-size.json"
output="$("${LOOKUP}" verify-asset --file "${ipa}" --json "${WORKDIR}/wrong-size.json" 2>&1)"; status=$?
assert_exit 24 "${status}" "a size mismatch is a hard failure"

output="$("${LOOKUP}" verify-asset --file "${WORKDIR}/nope.ipa" --json "${WORKDIR}/matching.json" 2>&1)"; status=$?
assert_exit 24 "${status}" "a missing download is a hard failure"

printf '\n%d checks, %d failures\n' "${checks}" "${failures}"
[[ "${failures}" -eq 0 ]]
