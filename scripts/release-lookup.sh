#!/usr/bin/env bash
#
# Resolve a published StreamBridge GitHub release and the iOS IPA it carries.
#
# This is the release lookup that the "Update Sideload Source" workflow used to
# do inline. It lives in a script so the failure modes that broke the v0.1.13
# release are covered by tests (scripts/tests/release-lookup-test.sh):
#
#   * `GET /repos/{owner}/{repo}/releases/tags/{tag}` answers **404 for a draft
#     release**, not just for a release that does not exist. The workflow used to
#     treat that 404 as a fatal `gh: Not Found` for a release that was merely still
#     a draft, which is exactly what happened for v0.1.13.
#   * Android and iOS publish independently, so "release exists but has no IPA
#     yet" must stay a successful skip instead of a failure.
#   * store.json must never be written from a draft, a missing release, or an
#     asset that has not finished uploading.
#   * A 401/403/5xx must never be downgraded to "nothing to do".
#
# Exit codes (deliberately distinct so callers can branch on them):
#   0   published release resolved (optionally with one uploaded IPA)
#   1   API, authentication, permission or network error  -> hard failure
#   20  release exists but is a draft                     -> skip, store.json unchanged
#   21  release not found (after the bounded retry)        -> hard failure
#   22  published release has no IPA asset                 -> skip, store.json unchanged
#   23  more than one IPA, or an IPA that is not uploaded  -> hard failure
#   24  local file does not match the published asset      -> hard failure
#
# Usage:
#   release-lookup.sh resolve --repo OWNER/NAME --tag v0.1.13 [--json FILE]
#                             [--attempts N] [--delay SECONDS]
#   release-lookup.sh verify-asset --file PATH [--name NAME] --json FILE
#
# `resolve` writes the release JSON to --json (default: $RUNNER_TEMP/release.json)
# and prints IPA_NAME / IPA_SIZE / IPA_DIGEST / IPA_URL lines when the release has
# an IPA. When $GITHUB_OUTPUT is set the same values are appended there for the
# workflow step that follows.

set -uo pipefail

readonly EXIT_API_ERROR=1
readonly EXIT_DRAFT=20
readonly EXIT_NOT_FOUND=21
readonly EXIT_NO_IPA=22
readonly EXIT_BAD_IPA=23
readonly EXIT_ASSET_MISMATCH=24

log() { printf '%s\n' "$*" >&2; }
notice() { printf '::notice::%s\n' "$*"; }
error() { printf '::error::%s\n' "$*"; }

usage() {
  sed -n '2,45p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

# Print the HTTP status gh reported, if it reported one at all.
http_status_of() {
  local output="$1"
  printf '%s' "${output}" | grep -oE 'HTTP [0-9]{3}' | tail -1 | awk '{print $2}'
}

release_json_for_tag() {
  # $1 repository, $2 tag, $3 output file. Returns gh's exit status.
  local repo="$1" tag="$2" out="$3"
  gh api "repos/${repo}/releases/tags/${tag}" >"${out}" 2>"${out}.err"
}

release_json_from_list() {
  # $1 repository, $2 tag, $3 output file. Works for drafts too, because a token
  # with write access sees drafts in the release list. Returns 0 when the tag is
  # present, 1 when it is not.
  local repo="$1" tag="$2" out="$3" listing
  listing="$(gh api "repos/${repo}/releases?per_page=100" --paginate 2>/dev/null)" || return 2
  printf '%s' "${listing}" | jq --arg tag "${tag}" \
    'map(select(.tag_name == $tag)) | first // empty' >"${out}" 2>/dev/null || return 2
  if [[ ! -s "${out}" || "$(jq -r 'type' "${out}" 2>/dev/null)" != "object" ]]; then
    return 1
  fi
  return 0
}

emit_ipa() {
  local json="$1" ipa_name ipa_size ipa_digest ipa_url
  ipa_name="$(jq -r '.assets[] | select(.name | endswith(".ipa")) | .name' "${json}")"
  ipa_size="$(jq -r --arg name "${ipa_name}" '.assets[] | select(.name == $name) | .size' "${json}")"
  ipa_digest="$(jq -r --arg name "${ipa_name}" '.assets[] | select(.name == $name) | .digest // empty' "${json}")"
  ipa_url="$(jq -r --arg name "${ipa_name}" '.assets[] | select(.name == $name) | .browser_download_url' "${json}")"

  printf 'IPA_NAME=%s\nIPA_SIZE=%s\nIPA_DIGEST=%s\nIPA_URL=%s\n' \
    "${ipa_name}" "${ipa_size}" "${ipa_digest}" "${ipa_url}"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
      printf 'ipa_name=%s\n' "${ipa_name}"
      printf 'ipa_size=%s\n' "${ipa_size}"
      printf 'ipa_digest=%s\n' "${ipa_digest}"
      printf 'ipa_url=%s\n' "${ipa_url}"
    } >>"${GITHUB_OUTPUT}"
  fi
}

cmd_resolve() {
  local repo="" tag="" json="" attempts=6 delay=10
  while (($#)); do
    case "$1" in
      --repo) repo="${2:-}"; shift 2 ;;
      --tag) tag="${2:-}"; shift 2 ;;
      --json) json="${2:-}"; shift 2 ;;
      --attempts) attempts="${2:-}"; shift 2 ;;
      --delay) delay="${2:-}"; shift 2 ;;
      *) log "Unknown argument: $1"; exit "${EXIT_API_ERROR}" ;;
    esac
  done
  [[ -n "${repo}" && -n "${tag}" ]] || { log "--repo and --tag are required"; exit "${EXIT_API_ERROR}"; }
  json="${json:-${RUNNER_TEMP:-/tmp}/release.json}"
  mkdir -p "$(dirname "${json}")"

  local attempt=1 status="" combined="" found=0
  while ((attempt <= attempts)); do
    if release_json_for_tag "${repo}" "${tag}" "${json}"; then
      found=1
      break
    fi

    combined="$(cat "${json}.err" 2>/dev/null || true)"
    status="$(http_status_of "${combined}")"
    if [[ "${status}" != "404" ]]; then
      # Anything that is not a plain "not found" is a real failure: an expired
      # token, a missing permission, a broken API. Never retire it as a skip.
      error "Release lookup for ${tag} failed: ${combined:-gh api exited non-zero without an HTTP status}"
      exit "${EXIT_API_ERROR}"
    fi

    # 404 is ambiguous: the release may be a draft, or it may not exist yet.
    local list_status=0
    release_json_from_list "${repo}" "${tag}" "${json}" || list_status=$?
    if [[ "${list_status}" -eq 0 ]]; then
      if [[ "$(jq -r '.draft' "${json}")" == "true" ]]; then
        notice "Release ${tag} is still a draft; store.json is left unchanged."
        if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
          printf 'skip=true\nskip_reason=draft\n' >>"${GITHUB_OUTPUT}"
        fi
        exit "${EXIT_DRAFT}"
      fi
      found=1
      break
    fi
    if [[ "${list_status}" -eq 2 ]]; then
      error "Could not list releases to classify the 404 for ${tag}."
      exit "${EXIT_API_ERROR}"
    fi

    if ((attempt < attempts)); then
      log "Release ${tag} not visible yet (attempt ${attempt}/${attempts}); retrying in ${delay}s."
      sleep "${delay}"
    fi
    attempt=$((attempt + 1))
  done

  if ((found == 0)); then
    error "Release ${tag} does not exist in ${repo}. Publish the release before updating store.json."
    exit "${EXIT_NOT_FOUND}"
  fi

  if [[ "$(jq -r '.draft' "${json}")" != "false" ]]; then
    notice "Release ${tag} is still a draft; store.json is left unchanged."
    if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
      printf 'skip=true\nskip_reason=draft\n' >>"${GITHUB_OUTPUT}"
    fi
    exit "${EXIT_DRAFT}"
  fi

  local ipa_count
  ipa_count="$(jq -r '[.assets[] | select(.name | endswith(".ipa"))] | length' "${json}")"
  if [[ "${ipa_count}" -eq 0 ]]; then
    # Android and iOS publish independently: nothing to publish into store.json.
    notice "Release ${tag} has no IPA asset yet; store.json is left unchanged."
    if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
      printf 'skip=true\nskip_reason=no-ipa\n' >>"${GITHUB_OUTPUT}"
    fi
    exit "${EXIT_NO_IPA}"
  fi
  if [[ "${ipa_count}" -gt 1 ]]; then
    error "Release ${tag} has ${ipa_count} IPA assets; store.json expects exactly one."
    exit "${EXIT_BAD_IPA}"
  fi

  local state
  state="$(jq -r '.assets[] | select(.name | endswith(".ipa")) | .state' "${json}")"
  if [[ "${state}" != "uploaded" ]]; then
    # An asset that is still "starter" or "open" would download as a truncated
    # file; publishing its digest now would poison the source permanently.
    error "The IPA asset on ${tag} is in state '${state}', not 'uploaded'."
    exit "${EXIT_BAD_IPA}"
  fi

  [[ "$(jq -r '.assets[] | select(.name | endswith(".ipa")) | .browser_download_url' "${json}")" != "null" ]] \
    || { error "The IPA asset on ${tag} has no download URL."; exit "${EXIT_BAD_IPA}"; }

  emit_ipa "${json}"
  exit 0
}

cmd_verify_asset() {
  local file="" name="" json=""
  while (($#)); do
    case "$1" in
      --file) file="${2:-}"; shift 2 ;;
      --name) name="${2:-}"; shift 2 ;;
      --json) json="${2:-}"; shift 2 ;;
      *) log "Unknown argument: $1"; exit "${EXIT_API_ERROR}" ;;
    esac
  done
  json="${json:-${RUNNER_TEMP:-/tmp}/release.json}"
  [[ -f "${json}" ]] || { error "Release metadata ${json} is missing."; exit "${EXIT_API_ERROR}"; }
  if [[ -z "${name}" ]]; then
    name="$(jq -r '.assets[] | select(.name | endswith(".ipa")) | .name' "${json}")"
  fi
  [[ -n "${file}" && -f "${file}" ]] || { error "Downloaded IPA ${file:-<unset>} is missing."; exit "${EXIT_ASSET_MISMATCH}"; }

  local remote_size local_size remote_digest local_digest
  remote_size="$(jq -r --arg name "${name}" '.assets[] | select(.name == $name) | .size' "${json}")"
  local_size="$(wc -c <"${file}" | tr -d ' ')"
  if [[ "${remote_size}" != "${local_size}" ]]; then
    error "Published IPA size ${remote_size} does not match the downloaded size ${local_size}."
    exit "${EXIT_ASSET_MISMATCH}"
  fi

  remote_digest="$(jq -r --arg name "${name}" '.assets[] | select(.name == $name) | .digest // empty' "${json}")"
  if [[ -n "${remote_digest}" ]]; then
    local_digest="sha256:$(sha256sum "${file}" | awk '{print $1}')"
    if [[ "${remote_digest}" != "${local_digest}" ]]; then
      error "Published IPA digest ${remote_digest} does not match ${local_digest}."
      exit "${EXIT_ASSET_MISMATCH}"
    fi
    log "Verified ${name}: ${remote_size} bytes, ${remote_digest}."
  else
    log "Verified ${name}: ${remote_size} bytes (the API exposes no digest for this asset)."
  fi
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf 'sha256=%s\n' "$(sha256sum "${file}" | awk '{print $1}')" >>"${GITHUB_OUTPUT}"
  fi
  exit 0
}

main() {
  local command="${1:-}"
  shift || true
  case "${command}" in
    resolve) cmd_resolve "$@" ;;
    verify-asset) cmd_verify_asset "$@" ;;
    -h|--help|help) usage 0 ;;
    *) usage "${EXIT_API_ERROR}" ;;
  esac
}

main "$@"
