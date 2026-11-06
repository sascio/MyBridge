#!/usr/bin/env bash
#
# Repository-side tests that do not need a JDK: the release lookup that publishes
# store.json, the Telegram release announcement (rendering, splitting, duplicate
# protection, retries -- all offline), the store metadata itself, and the
# release/README version wiring.
#
# Run locally with `scripts/tests/run-tests.sh`; CI runs the same script
# (`Script tests` job in .github/workflows/build.yml).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPOSITORY_ROOT}"

# The entire Python suite is offline: loopback fixtures are allowed, external
# DNS/TCP is blocked, and Git may only use local file remotes in these tests.
export PYTHONPATH="${SCRIPT_DIR}/offline${PYTHONPATH:+:${PYTHONPATH}}"
export GIT_ALLOW_PROTOCOL=file

failures=0

section() { printf '\n== %s\n' "$1"; }

run() {
  local label="$1"; shift
  if "$@"; then
    printf '  ok   %s\n' "${label}"
  else
    printf '  FAIL %s\n' "${label}"
    failures=$((failures + 1))
  fi
}

section "Release lookup"
if ! "${SCRIPT_DIR}/release-lookup-test.sh"; then
  failures=$((failures + 1))
fi
if ! "${SCRIPT_DIR}/release-api-test.sh"; then
  failures=$((failures + 1))
fi

section "Telegram release announcement"
# The announcement tests parse the workflow to check its own guardrails; PyYAML
# is already present on GitHub's ubuntu runners. Without it those checks skip
# rather than fail.
python3 -c "import yaml" 2>/dev/null \
  || printf '  note PyYAML is not installed; the workflow YAML checks will skip\n'
# Telethon is only needed by the large-file upload tests, which drive the real
# client over a stubbed RPC layer. Without it those tests skip rather than fail.
python3 -c "import telethon" 2>/dev/null \
  || printf '  note Telethon is not installed; the MTProto upload tests will skip\n'
# Formatting, escaping, pagination, asset detection, idempotency, retries and the
# workflow's own guardrails. Runs entirely offline against a mock Bot API, so no
# announcement can ever reach the public channel from CI.
if ! "${SCRIPT_DIR}/telegram-announcement-test.py"; then
  failures=$((failures + 1))
fi
# Durable write-ahead, exact-parent Git commits, bot-only raw MTProto and
# multipart/recovery regressions; all Telegram/GitHub RPCs are offline fakes.
if ! python3 "${SCRIPT_DIR}/telegram-idempotent-test.py"; then
  failures=$((failures + 1))
fi
# Same-repository state rules, legacy migration compatibility, and local Git checks.
if ! python3 "${SCRIPT_DIR}/telegram-state-test.py"; then
  failures=$((failures + 1))
fi
# Bot login/logout preflight uses only fakes here; it cannot send or upload.
if ! python3 "${SCRIPT_DIR}/telegram-mtproto-preflight-test.py"; then
  failures=$((failures + 1))
fi

section "Release workflows"

run "the sideload workflow uses the tested lookup instead of an inline releases/tags call" \
  bash -c '! grep -qE "^\s*gh api .*releases/tags/" .github/workflows/update-store-source.yml &&
           grep -q "scripts/release-lookup.sh resolve" .github/workflows/update-store-source.yml'

run "the sideload workflow verifies size and digest before writing store.json" \
  bash -c 'grep -q "verify-asset" .github/workflows/update-store-source.yml'

run "manual sideload-source dispatch derives the release version from its tag input" \
  bash -c 'grep -Fq '\''RELEASE_VERSION: ${{ inputs.version || inputs.tag || github.event.release.tag_name }}'\'' .github/workflows/update-store-source.yml'

run "the release workflow refuses to reuse a tag for a newer commit" \
  bash -c 'grep -q "refusing to build or publish from a mismatched tag" scripts/verify-release-target.sh'

run "draft releases and assets are handled by ID rather than tag-based gh release commands" \
  bash -c 'grep -q "scripts/release-api.sh lookup" .github/workflows/release-draft.yml &&
           grep -q "scripts/release-api.sh ensure-asset" .github/workflows/release-draft.yml &&
           grep -q "scripts/release-api.sh update-body" .github/workflows/release-draft.yml &&
           ! grep -qE "^[[:space:]]*gh release (view|upload|edit)([[:space:]]|$)" .github/workflows/release-draft.yml'

run "the approved tag/version/code and invite fixes are explicitly verified" \
  bash -c 'scripts/verify-release-target.sh --tag v0.1.15 --commit "$(git rev-parse HEAD)"'

section "store.json"

run "store.json is valid JSON with a version entry per app" \
  python3 - <<'PY'
import json, sys
store = json.load(open("store.json", encoding="utf-8"))
for app in store["apps"]:
    versions = app.get("versions") or []
    assert versions, "app has no versions"
    for entry in versions:
        for key in ("version", "buildVersion", "date", "downloadURL", "size", "sha256"):
            assert entry.get(key) not in (None, ""), f"{app['bundleIdentifier']} {entry.get('version')} is missing {key}"
        assert entry["downloadURL"].startswith("https://github.com/sascio/MyBridge/releases/download/"), entry["downloadURL"]
        assert len(entry["sha256"]) == 64, entry["sha256"]
        assert int(entry["size"]) > 0
PY

run "store.json version entries are unique and ordered newest first" \
  python3 - <<'PY'
import json
store = json.load(open("store.json", encoding="utf-8"))
for app in store["apps"]:
    names = [entry["version"] for entry in app["versions"]]
    assert len(names) == len(set(names)), f"{app['bundleIdentifier']} has duplicate versions: {names}"
    def key(name):
        return tuple(int(part) for part in name.split("."))
    assert names == sorted(names, key=key, reverse=True), f"{app['bundleIdentifier']} versions are not newest first: {names}"
PY

section "Version wiring"

run "README, version properties, Xcode, workflow input and release notes agree on 0.1.15 / 115" \
  python3 - <<'PY'
props = dict(
    line.split("=", 1)
    for line in open("streambridge.version.properties", encoding="utf-8").read().splitlines()
    if line and not line.startswith("#")
)
name, code = props["STREAMBRIDGE_VERSION_NAME"].strip(), props["STREAMBRIDGE_VERSION_CODE"].strip()
assert (name, code) == ("0.1.15", "115"), (name, code)
readme = open("README.md", encoding="utf-8").read()
assert f"version <strong>{name}</strong> ({code})" in readme, "README version banner is stale"
assert f"## Highlights in {name}" in readme, "README highlights section is stale"
assert f"StreamBridge-{name}-iOS-unsigned.ipa" in readme, "README iOS asset name is stale"
xcconfig = open("iosApp/Configuration/Version.xcconfig", encoding="utf-8").read()
assert f"MARKETING_VERSION={name}" in xcconfig
assert f"CURRENT_PROJECT_VERSION={code}" in xcconfig
workflow = open(".github/workflows/release-draft.yml", encoding="utf-8").read()
assert f'default: "v{name}"' in workflow, "release workflow default tag is stale"
assert f'tags:\n      - "v{name}"' in workflow, "release workflow tag trigger is not restricted to the approved tag"
notes = open("docs/RELEASE-NOTES-0.1.15.md", encoding="utf-8").read()
assert f"## StreamBridge {name}" in notes, "release notes header is stale"
assert f"version {name} (version code {code})" in notes, "release notes version is stale"
for path in ("streambridge.version.properties", "iosApp/Configuration/Version.xcconfig", "README.md", ".github/workflows/release-draft.yml", "docs/RELEASE-NOTES-0.1.15.md"):
    text = open(path, encoding="utf-8").read()
    assert "0.1.13" not in text and "113" not in text and "0.1.14" not in text and "114" not in text, f"{path} still references an earlier version"
PY

run "no watch-party invite string mentions Nuvio branding" \
  bash -c '! grep -rnE "(Join my Nuvio|Nuvio watch party|https://nuvio\.tv/party|nuvio://party/\$)" composeApp/src/commonMain/composeResources composeApp/src/commonMain/kotlin/com/nuvio/app/features/party site/party/index.html'

printf '\n'
if [[ "${failures}" -ne 0 ]]; then
  printf '%d failing group(s)\n' "${failures}"
  exit 1
fi
printf 'All script tests passed\n'
