#!/usr/bin/env bash
#
# Fail closed unless the checked-out tree is the authorized StreamBridge 0.1.18
# release target, code 118, and contains the approved watch-party and release
# pipeline fixes. Release workflows pass --verify-tag-ref so the tag itself must
# point to the exact commit being built.

set -euo pipefail

readonly EXPECTED_TAG="v0.1.18"
readonly EXPECTED_VERSION="0.1.18"
readonly EXPECTED_CODE="118"

usage() {
  cat >&2 <<'USAGE'
Usage: verify-release-target.sh --tag TAG --commit SHA [--verify-tag-ref]

The release workflow must pass --verify-tag-ref. Without it, only the checked-out
source tree and its release identity are verified (useful for local CI tests).
USAGE
  exit "${1:-1}"
}

TAG=""
COMMIT=""
VERIFY_TAG_REF=false
while (($#)); do
  case "$1" in
    --tag) TAG="${2:-}"; shift 2 ;;
    --commit) COMMIT="${2:-}"; shift 2 ;;
    --verify-tag-ref) VERIFY_TAG_REF=true; shift ;;
    -h|--help) usage 0 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; usage 1 ;;
  esac
done

fail() { printf '::error::%s\n' "$*" >&2; exit 1; }

[[ -n "${TAG}" && -n "${COMMIT}" ]] || usage 1
[[ "${TAG}" == "${EXPECTED_TAG}" ]] || fail "Only ${EXPECTED_TAG} is authorized for this release task; got ${TAG}."
[[ -f streambridge.version.properties ]] || fail "streambridge.version.properties is missing."
[[ -f iosApp/Configuration/Version.xcconfig ]] || fail "Xcode version configuration is missing."

property() {
  local key="$1" count value
  count="$(grep -c "^${key}=" streambridge.version.properties || true)"
  [[ "${count}" == "1" ]] || fail "Expected exactly one ${key} in streambridge.version.properties; found ${count}."
  value="$(sed -n "s/^${key}=//p" streambridge.version.properties | tr -d '\r')"
  printf '%s' "${value}"
}

version_name="$(property STREAMBRIDGE_VERSION_NAME)"
version_code="$(property STREAMBRIDGE_VERSION_CODE)"
[[ "${version_name}" == "${EXPECTED_VERSION}" ]] || fail "Expected app version ${EXPECTED_VERSION}; found ${version_name}."
[[ "${version_code}" == "${EXPECTED_CODE}" ]] || fail "Expected version code ${EXPECTED_CODE}; found ${version_code}."
[[ "${TAG#v}" == "${version_name}" ]] || fail "Tag ${TAG} does not match app version ${version_name}."

grep -Fqx "CURRENT_PROJECT_VERSION=${EXPECTED_CODE}" iosApp/Configuration/Version.xcconfig \
  || fail "Xcode CURRENT_PROJECT_VERSION is not ${EXPECTED_CODE}."
grep -Fqx "MARKETING_VERSION=${EXPECTED_VERSION}" iosApp/Configuration/Version.xcconfig \
  || fail "Xcode MARKETING_VERSION is not ${EXPECTED_VERSION}."

grep -Fq "version <strong>${EXPECTED_VERSION}</strong> (${EXPECTED_CODE})" README.md \
  || fail "README version banner does not identify ${EXPECTED_VERSION} (${EXPECTED_CODE})."
grep -Fq "## Highlights in ${EXPECTED_VERSION}" README.md \
  || fail "README highlights do not identify ${EXPECTED_VERSION}."
grep -Fq "StreamBridge-${EXPECTED_VERSION}-iOS-unsigned.ipa" README.md \
  || fail "README iOS asset name does not match ${EXPECTED_VERSION}."

grep -Fq "default: \"${EXPECTED_TAG}\"" .github/workflows/release-draft.yml \
  || fail "Release workflow default input is not ${EXPECTED_TAG}."
grep -Fq -- "- \"${EXPECTED_TAG}\"" .github/workflows/release-draft.yml \
  || fail "Release workflow tag trigger is not restricted to ${EXPECTED_TAG}."
grep -Fq "## StreamBridge ${EXPECTED_VERSION}" "docs/RELEASE-NOTES-${EXPECTED_VERSION}.md" \
  || fail "Release notes header does not identify ${EXPECTED_VERSION}."
grep -Fq "version ${EXPECTED_VERSION} (version code ${EXPECTED_CODE})" "docs/RELEASE-NOTES-${EXPECTED_VERSION}.md" \
  || fail "Release notes do not state ${EXPECTED_VERSION} / code ${EXPECTED_CODE}."

# The release description and the sideload source are StreamBridge-facing
# texts: they must not name the upstream product at all. Licence compliance
# lives in LICENSE/NOTICE/README's attribution section, which keep their
# required references.
if grep -qi 'nuvio' "docs/RELEASE-NOTES-${EXPECTED_VERSION}.md"; then
  fail "Release notes mention the upstream product name; they must be StreamBridge-only."
fi
if grep -Eq 'Nuvio(Mobile|Media)' store.json \
  || grep store.json | grep -Ev 'Nuvio-compatible|api\.nuvio\.tv' | grep -q 'Nuvio'; then
  fail "store.json names the upstream product outside compatibility/host references."
fi

# Invitations must use the StreamBridge-owned HTTPS host and preserve the
# app-scheme handoff, legacy compatibility, and install fallback.
party_repo="composeApp/src/commonMain/kotlin/com/nuvio/app/features/party/PartyRepository.kt"
party_strings="composeApp/src/commonMain/composeResources/values/strings_party.xml"
android_manifest="androidApp/src/main/AndroidManifest.xml"
ios_info="iosApp/iosApp/Info.plist"
[[ -s "${party_repo}" ]] || fail "Watch-party repository source is missing."
[[ -s "${party_strings}" ]] || fail "Watch-party invitation strings are missing."
[[ -s "site/party/index.html" && -s "site/404.html" ]] || fail "The HTTPS invite redirect pages are missing."

grep -Fq 'fun partyInviteLink(code: String): String = "$STREAMBRIDGE_INVITE_REDIRECT_BASE_URL${normalizePartyCode(code)}"' "${party_repo}" \
  || fail "Watch-party invites are not generated as normalized first-party HTTPS links."
grep -Fq 'Join my StreamBridge watch party' "${party_strings}" \
  || fail "Default invitation copy is not StreamBridge branded."
grep -Fq 'const val STREAMBRIDGE_INSTALL_URL = "https://github.com/sascio/MyBridge/releases/latest"' "${party_repo}" \
  || fail "Watch-party invite is missing the install fallback."
grep -Fq 'const val STREAMBRIDGE_INVITE_REDIRECT_BASE_URL = "https://streambridgeapp.pages.dev/party/"' "${party_repo}" \
  || fail "The first-party HTTPS invite URL is missing."
grep -Fq 'var SCHEME = "streambridge://party/"' site/party/index.html \
  || fail "The HTTPS redirect page does not hand off to the StreamBridge scheme."
grep -Fq 'window.location.replace(base + "party/?code=" + encodeURIComponent(code.toUpperCase()))' site/404.html \
  || fail "Path-style HTTPS invite links are not redirected to the canonical page."
grep -Fq 'android:scheme="streambridge"' "${android_manifest}" \
  || fail "Android does not register the StreamBridge invite scheme."
grep -Fq '<string>streambridge</string>' "${ios_info}" \
  || fail "iOS does not register the StreamBridge invite scheme."

grep -Fq 'scripts/release-lookup.sh resolve' .github/workflows/update-store-source.yml \
  || fail "Sideload-source workflow does not use the tested published-release lookup."
grep -Fq 'scripts/release-api.sh lookup' .github/workflows/release-draft.yml \
  || fail "Release workflow does not resolve drafts through the releases list."
grep -Fq 'scripts/release-api.sh ensure-asset' .github/workflows/release-draft.yml \
  || fail "Release workflow does not use the non-clobbering, draft-safe asset uploader."
grep -Fq 'scripts/release-api.sh update-body' .github/workflows/release-draft.yml \
  || fail "Release workflow does not update draft notes by release ID."

# v0.1.14 corrected build gates: the About Us heading, the footer layout, the
# hero metadata wiring and the user-facing branding rules are part of the
# approved release content, so they are verified like every other fix.
settings_models="composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsModels.kt"
about_page="composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/AboutSettingsPage.kt"
hero_section="composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeHeroSection.kt"
home_screen="composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt"

grep -Fq 'titleRes = Res.string.about_us_page_title' "${settings_models}" \
  || fail "The About Us page heading is not about_us_page_title."
grep -Fq 'title = stringResource(Res.string.about_us_page_title)' "${about_page}" \
  || fail "The About settings row does not use the About Us heading."
if grep -Fq 'streambridge_tagline' "${about_page}"; then
  fail "The settings footer still renders the motto; it must stay removed."
fi
grep -Fq 'Res.string.streambridge_credits_made_with' "${about_page}" \
  || fail "The footer attribution line is missing."
grep -Fq 'Res.string.compose_about_version_format' "${about_page}" \
  || fail "The footer version line is missing."
grep -Fq 'horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally' "${about_page}" \
  || fail "The footer column is not centred."
grep -Fq 'HomeHeroMetaRows(' "${hero_section}" \
  || fail "The hero section does not render the rich metadata rows."
grep -Fq 'showImdbRating = homeHeroState.showImdbRating' "${home_screen}" \
  || fail "Home does not pass the rating visibility settings to the hero section."
# The hero stability machinery ported from the reference implementation must
# stay wired: neighbour prefetch, the draw-time content crossfade and keyed
# content layers are what prevent the hero from moving on content changes.
grep -Fq 'heroPrefetchItemIndices(pagerState.settledPage' "${hero_section}" \
  || fail "The hero neighbour prefetch is missing."
grep -Fq 'alpha = heroContentAlpha(offset)' "${hero_section}" \
  || fail "The hero content crossfade is not the draw-time reference curve."
grep -Fq 'key(layerItem.stableKey())' "${hero_section}" \
  || fail "Hero content layers are not keyed by item."

# The default-locale resource files must name the app StreamBridge. Backend
# disclosures and licence attributions legitimately keep Nuvio references, so
# those lines are exempted by key-name prefix before scanning; anything left is
# a leak.
if grep -hvE '<string name="(privacy_policy_|settings_licenses_attributions_nuvio|streambridge_credits_based_on|streambridge_credits_nuvio_media|streambridge_based_on_nuvio|server_error_official|action_support_nuvio)' \
    composeApp/src/commonMain/composeResources/values/strings.xml \
    composeApp/src/commonMain/composeResources/values/strings_party.xml \
    composeApp/src/commonMain/composeResources/values/strings_pip.xml \
    composeApp/src/commonMain/composeResources/values/strings_anilist.xml \
    composeApp/src/commonMain/composeResources/values/strings_discord.xml \
    composeApp/src/commonMain/composeResources/values/strings_wetrakr.xml \
    composeApp/src/commonMain/composeResources/values/strings_settings_backup.xml \
    | grep -q 'Nuvio'; then
  fail "Default-locale resources name Nuvio outside the exempt attribution/disclosure keys."
fi
if grep -q 'displayName = "Nuvio"' composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/TrackingProviderCards.kt \
  || grep -q 'APP_NAME = "Nuvio"' composeApp/src/commonMain/kotlin/com/nuvio/app/features/discord/DiscordRpcConfig.kt; then
  fail "A user-facing component label is back to Nuvio."
fi

actual_head="$(git rev-parse HEAD^{commit})"
[[ "${actual_head}" == "${COMMIT}" ]] || fail "Checked-out HEAD ${actual_head} does not match the requested build commit ${COMMIT}."

if [[ "${VERIFY_TAG_REF}" == true ]]; then
  git diff --quiet HEAD -- \
    || fail "Tracked source changes are not committed at ${COMMIT}; refusing to release a tree that differs from its target commit."
  if ! git rev-parse "refs/tags/${TAG}^{commit}" >/dev/null 2>&1; then
    git fetch --no-tags origin "refs/tags/${TAG}:refs/tags/${TAG}" \
      || fail "Could not fetch the approved tag ${TAG} from origin."
  fi
  tag_commit="$(git rev-parse "refs/tags/${TAG}^{commit}")"
  [[ "${tag_commit}" == "${COMMIT}" ]] \
    || fail "Existing release tag ${TAG} points to ${tag_commit}, not approved fixes commit ${COMMIT}; refusing to build or publish from a mismatched tag."
fi

printf 'Verified release target: tag=%s version=%s code=%s commit=%s\n' \
  "${TAG}" "${version_name}" "${version_code}" "${COMMIT}"
