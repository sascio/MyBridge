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

section "Android SDK provisioning"
# The f41ea7a build failed before running a single test because the runner's
# NDK directory was not an NDK. This drives the repair script against a fake
# SDK, with no network, JDK or real SDK.
if ! "${SCRIPT_DIR}/setup-android-sdk-test.sh"; then
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
# State-only verification exercises local Git races and recovery, never GitHub/Telegram.
if ! python3 "${SCRIPT_DIR}/telegram-state-verification-test.py"; then
  failures=$((failures + 1))
fi
# Historical cutover and orphan initialization run against fake API data/local Git only.
if ! python3 "${SCRIPT_DIR}/telegram-cutover-test.py"; then
  failures=$((failures + 1))
fi
# Automatic bootstrap, session locking and polling use only local Git and fakes.
if ! python3 "${SCRIPT_DIR}/telegram-auto-test.py"; then
  failures=$((failures + 1))
fi
# Exact tag resolution and confirmed-response reconciliation use fake GitHub data only.
if ! python3 "${SCRIPT_DIR}/telegram-identity-test.py"; then
  failures=$((failures + 1))
fi
if ! python3 "${SCRIPT_DIR}/telegram-reconcile-test.py"; then
  failures=$((failures + 1))
fi

section "Release workflows"
run "production tracking credential gates and optional Trakt" python3 scripts/tests/release-tracking-test.py
run "independent preflight asset inspection" python3 scripts/tests/preflight-assets-test.py
run "iOS production signing and provisioning" python3 scripts/tests/ios-signing-test.py
run "Unsigned iOS release verification" python3 scripts/tests/unsigned-ipa-test.py

run "iOS IPA and simulator tests share one prepared dependency artifact" \
  python3 - <<'PY'
import yaml
release = yaml.safe_load(open(".github/workflows/ios-release.yml", encoding="utf-8"))
simulator = yaml.safe_load(open(".github/workflows/ios-simulator-tests.yml", encoding="utf-8"))
name = "streambridge-ios-dependencies"
prepare = release["jobs"]["ios_dependencies"]
assert any(step.get("name") == "Prepare iOS dependencies" for step in prepare["steps"])
assert any(step.get("name") == "Upload shared iOS dependencies" and step.get("with", {}).get("name") == name for step in prepare["steps"])
for job_name in ("ipa", "simulator_tests"):
    job = release["jobs"][job_name]
    assert "ios_dependencies" in job.get("needs", [])
    if job_name == "ipa":
        assert any(step.get("name") == "Download shared iOS dependencies" and step.get("with", {}).get("name") == name for step in job["steps"])
        assert not any(step.get("name") == "Prepare iOS dependencies" for step in job["steps"])
assert release["jobs"]["simulator_tests"].get("with", {}).get("use_prepared_dependencies") is True
sim_job = simulator["jobs"]["ios-simulator-tests"]
assert any(step.get("name") == "Download shared iOS dependencies" for step in sim_job["steps"])
assert any(step.get("name") == "Prepare iOS dependencies independently" and step.get("if") == "${{ !inputs.use_prepared_dependencies }}" for step in sim_job["steps"])
PY

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
           ! grep -qE "^[[:space:]]*gh release (view|upload|edit)([[:space:]]|$)" .github/workflows/release-draft.yml'

run "release gate accepts the 0.1.20 source identity" \
  bash -c 'scripts/verify-release-target.sh --tag v0.1.20 --commit "$(git rev-parse HEAD)"'

run "release draft requires both Android and iOS builds" \
  python3 - <<'PYTEST'
workflow = open(".github/workflows/release-draft.yml", encoding="utf-8").read()
assert "needs: [android, ios, preflight-verification, verify-ios, ios-tests]" in workflow
assert "needs.preflight-verification.result == 'success'" in workflow
assert "needs.verify-ios.result == 'success'" in workflow
assert "needs.ios-tests.result == 'success'" in workflow
assert "needs.draft.result == 'success'" in workflow
assert 'scripts/audit-preflight-assets.sh preflight/android --android-only' in workflow
assert 'python3 scripts/verify-unsigned-ipa.py' in workflow
assert 'STREAMBRIDGE_IOS_SIGNING: "0"' in workflow
assert 'STREAMBRIDGE_IOS_SIGNING: "1"' not in workflow
assert "inputs.include_ios" not in workflow
assert "inputs.require_tracking_credentials" not in workflow
assert 'for property_name in TRAKT_CLIENT_ID TRAKT_CLIENT_SECRET SIMKL_CLIENT_ID' in workflow
assert 'report_populated MDBLIST "$mdblist" CLIENT_ID || tracking_missing=1' in workflow
assert 'report_populated SIMKL "$simkl" CLIENT_ID || tracking_missing=1' in workflow
PYTEST

run "release gate rejects other version tags" \
  bash -c '! scripts/verify-release-target.sh --tag v0.1.18 --commit "$(git rev-parse HEAD)" &&
           ! scripts/verify-release-target.sh --tag v0.1.19 --commit "$(git rev-parse HEAD)" &&
           ! scripts/verify-release-target.sh --tag v0.1.21 --commit "$(git rev-parse HEAD)"'

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

section "CloudStream repository authorization"

# The v0.1.19 regression: an install was refused with "Authorize this repository
# in extension details before installing." while the details screen offered no
# authorization action. These checks pin the contract that fixed it, so the gate
# can never again exist without a visible way to satisfy it. The Kotlin
# semantics themselves are covered by CloudStreamRepositoryTrustTest; this is the
# wiring guard that runs without a JDK.
run "authorization is a visible action, not only an error string" \
  python3 - <<'PY'
import pathlib, re, xml.etree.ElementTree as ET

cloud = pathlib.Path("composeApp/src/commonMain/kotlin/com/nuvio/app/features/cloudstream")
detail = (cloud / "CloudStreamExtensionDetail.kt").read_text(encoding="utf-8")
screen = (cloud / "CloudStreamExtensionsScreen.kt").read_text(encoding="utf-8")
trust = (cloud / "CloudStreamRepositoryTrust.kt").read_text(encoding="utf-8")
repo = (cloud / "CloudStreamExtensionsRepository.kt").read_text(encoding="utf-8")
models = (cloud / "CloudStreamUiModels.kt").read_text(encoding="utf-8")
installation = (cloud / "CloudStreamInstallation.kt").read_text(encoding="utf-8")

# 1. The details screen renders the authorization surface. In v0.1.19 the only
#    control was a bare TextButton whose label was easy to miss, and the refusal
#    text pointed at a screen that showed no explicit state.
assert "CloudStreamRepositoryAuthorizationCard(" in detail, "details screen has no authorization card"
assert "authorized = trusted" in detail, "details screen does not pass the authorization state"
assert "CloudStreamRepositoryAuthorizationCard(" in trust, "authorization card component is missing"

# 2. The card offers the action itself, with a confirmation that names the
#    repository, and a revoke path once authorized.
assert "CloudStreamTrustDialog(" in trust
assert "setRepositoryTrusted(repositoryUrl, true)" in trust
assert "setRepositoryTrusted(repositoryUrl, false)" in trust
assert "cloudstream_trust_confirm_scope" in trust

# 3. Repositories are manageable from the list too: the gate is reachable
#    without opening an extension first.
assert "CloudStreamRepositoryAuthorizationChip(" in screen, "repository cards show no authorization state"
assert "authorizingUrl = repository.url" in screen, "repository cards have no authorize action"
assert "CloudStreamTrustDialog(" in screen, "repository-level authorize action does not confirm"

# 4. A refusal is surfaced as an actionable banner, not appended to an unrelated
#    form, and carries the button that resolves it.
assert "AuthorizationRequiredCard(" in screen, "no actionable banner for a refused install"
assert "unauthorizedRepositoryUrl" in models, "blocked repository is not derived"
assert "AuthorizationRequiredCard(" in trust
assert "cloudstream_authorization_required_title" in trust

# 5. Explicit, per-repository consent is preserved end to end: adding a
#    repository still does not authorize it, and the gate checks the configured
#    URL plus the stored key (never a global "trust everything" flag).
assert "REPOSITORY_UNAUTHORIZED" in installation, "refusal is not typed distinctly"
# The container itself may use UNKNOWN for unrelated failures; the install
# refusal path specifically must carry the typed authorization error.
guard = repo[repo.index("if (!isRepositoryTrusted(extension.repositoryUrl))"):]
guard = guard[:guard.index("if (!CloudStreamPackageInstaller.supportsInstallation)")]
assert "REPOSITORY_UNAUTHORIZED" in guard, "install refusal still reports an untyped error"
assert "cloudStreamRepositoryAuthorized(" in repo, "authorization check is not shared"
assert "configuredUrls.any { sameCloudStreamRepositoryUrl(it, repositoryUrl) }" in repo, \
    "authorization is not tied to a configured repository URL"
assert "cloudStreamTrustKey(" in repo
# No blanket trust: authorization must never be derived from repository count.
assert "trusted = true" not in repo, "authorization is granted implicitly"
assert "isRepositoryTrusted" in repo and "setRepositoryTrusted" in repo

# 6. Every new user-facing string used by the UI exists in the default locale.
strings = {item.get("name") for item in ET.parse(
    "composeApp/src/commonMain/composeResources/values/strings.xml").getroot().findall("string")}
for source, name in [(trust, "trust"), (screen, "screen"), (detail, "detail")]:
    for resource in re.findall(r"Res\.string\.(cloudstream_[A-Za-z0-9_]+)", source):
        assert resource in strings, f"{name} references undefined string {resource}"
print("authorization wiring verified")
PY

run "authorization stays explicit per repository in the tests" \
  python3 - <<'PY'
import pathlib
source = pathlib.Path(
    "composeApp/src/commonTest/kotlin/com/nuvio/app/features/cloudstream/"
    "CloudStreamRepositoryTrustTest.kt").read_text(encoding="utf-8")
# The acceptance criteria: authorized repositories install, unauthorized ones are
# rejected, one grant covers every extension of that repository, and a key for a
# repository that is no longer configured grants nothing.
for required in (
    "addingARepositoryDoesNotAuthorizeIt",
    "explicitAuthorizationIsRecognisedForTheConfiguredUrl",
    "authorizationCoversEveryExtensionOfTheSameRepository",
    "aDifferentRepositoryStaysUnauthorized",
    "anAuthorizationKeyForAnUnconfiguredRepositoryGrantsNothing",
    "installRefusalNamesAnUnauthorizedRepositoryAndCarriesTheAction",
    "authorizingEveryRepositoryClearsThePrompt",
):
    assert required in source, f"missing authorization test: {required}"
print("authorization tests present")
PY

section "Version wiring"

run "README, version properties and Xcode agree on 0.1.20 / 120" \
  python3 - <<'PYTEST'
props = dict(line.split("=", 1) for line in open("streambridge.version.properties", encoding="utf-8").read().splitlines() if line and not line.startswith("#"))
name, code = props["STREAMBRIDGE_VERSION_NAME"].strip(), props["STREAMBRIDGE_VERSION_CODE"].strip()
assert (name, code) == ("0.1.20", "120"), (name, code)
readme = open("README.md", encoding="utf-8").read()
assert f"version <strong>{name}</strong> ({code})" in readme
xcconfig = open("iosApp/Configuration/Version.xcconfig", encoding="utf-8").read()
assert f"MARKETING_VERSION={name}" in xcconfig
assert f"CURRENT_PROJECT_VERSION={code}" in xcconfig
# Retargeted only to the requested version; release execution still verifies
# the exact tag commit, signing, notes and generated asset digests.
assert 'readonly EXPECTED_TAG="v0.1.20"' in open("scripts/verify-release-target.sh", encoding="utf-8").read()
PYTEST

run "no watch-party invite string mentions Nuvio branding" \
  bash -c '! grep -rnE "(Join my Nuvio|Nuvio watch party|https://nuvio\.tv/party|nuvio://party/\$)" composeApp/src/commonMain/composeResources composeApp/src/commonMain/kotlin/com/nuvio/app/features/party site/party/index.html'

printf '\n'
if [[ "${failures}" -ne 0 ]]; then
  printf '%d failing group(s)\n' "${failures}"
  exit 1
fi
printf 'All script tests passed\n'
