#!/usr/bin/env bash
#
# Draft-safe operations on GitHub Release objects.
#
# `GET /releases/tags/{tag}` deliberately returns 404 for draft releases. Resolve
# release objects through the authenticated releases list instead, then use the
# release ID for draft metadata and the release's uploads.github.com upload_url
# for assets. Existing assets are verified by SHA-256 and are never clobbered.
#
# Commands:
#   lookup      --repo OWNER/NAME --tag TAG --json FILE [--attempts N] [--delay S]
#   ensure-asset --repo OWNER/NAME --json FILE --file FILE [--name NAME]
#   update-body --repo OWNER/NAME --json FILE --body-file FILE
#
# lookup exit codes: 0 found, 3 not found, 1 API/validation error.

set -uo pipefail

log() { printf '%s\n' "$*" >&2; }
notice() { printf '::notice::%s\n' "$*" >&2; }
error() { printf '::error::%s\n' "$*" >&2; }

usage() {
  cat >&2 <<'USAGE'
Usage:
  release-api.sh lookup --repo OWNER/NAME --tag TAG --json FILE [--attempts N] [--delay S]
  release-api.sh ensure-asset --repo OWNER/NAME --json FILE --file FILE [--name NAME]
  release-api.sh update-body --repo OWNER/NAME --json FILE --body-file FILE
USAGE
  exit "${1:-1}"
}

cmd_lookup() {
  local repo="" tag="" json="" attempts=1 delay=0
  while (($#)); do
    case "$1" in
      --repo) repo="${2:-}"; shift 2 ;;
      --tag) tag="${2:-}"; shift 2 ;;
      --json) json="${2:-}"; shift 2 ;;
      --attempts) attempts="${2:-}"; shift 2 ;;
      --delay) delay="${2:-}"; shift 2 ;;
      *) error "Unknown lookup argument: $1"; return 1 ;;
    esac
  done

  [[ -n "${repo}" && -n "${tag}" && -n "${json}" ]] || { usage 1; }
  [[ "${attempts}" =~ ^[1-9][0-9]*$ && "${delay}" =~ ^[0-9]+$ ]] || {
    error "--attempts must be positive and --delay must be non-negative."
    return 1
  }
  mkdir -p "$(dirname "${json}")"

  local listing count attempt
  listing="$(mktemp)"
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if ! gh api "repos/${repo}/releases?per_page=100" --paginate >"${listing}"; then
      error "Could not list GitHub Releases for ${repo}."
      rm -f "${listing}"
      return 1
    fi
    if ! jq -e 'type == "array"' "${listing}" >/dev/null 2>&1; then
      error "GitHub returned an invalid release list for ${repo}."
      rm -f "${listing}"
      return 1
    fi

    count="$(jq --arg tag "${tag}" '[.[] | select(.tag_name == $tag)] | length' "${listing}")"
    if [[ "${count}" -gt 1 ]]; then
      error "More than one release object uses tag ${tag}; refusing to choose or modify one."
      rm -f "${listing}"
      return 1
    fi
    if [[ "${count}" -eq 1 ]]; then
      jq --arg tag "${tag}" 'first(.[] | select(.tag_name == $tag))' "${listing}" >"${json}"
      rm -f "${listing}"
      local draft_value
      draft_value="$(jq -r '.draft' "${json}")"
      log "Resolved ${tag} through the releases list (draft=${draft_value})."
      return 0
    fi

    if ((attempt < attempts)); then
      log "Release ${tag} is not visible yet (attempt ${attempt}/${attempts}); retrying in ${delay}s."
      sleep "${delay}"
    fi
  done

  rm -f "${listing}"
  notice "No release object exists for ${tag}."
  return 3
}

asset_result() {
  # Return 0 for an identical, uploaded asset; 5 when absent; 1 if unsafe or
  # different. No branch of this function deletes or replaces a remote asset.
  local release_json="$1" file="$2" name="$3"
  local count state size digest local_size local_digest

  count="$(jq --arg name "${name}" '[.assets[]? | select(.name == $name)] | length' "${release_json}")" || return 1
  if [[ "${count}" -eq 0 ]]; then
    return 5
  fi
  if [[ "${count}" -ne 1 ]]; then
    error "Release contains ${count} assets named ${name}; refusing to choose one."
    return 1
  fi

  state="$(jq -r --arg name "${name}" '.assets[] | select(.name == $name) | .state' "${release_json}")"
  size="$(jq -r --arg name "${name}" '.assets[] | select(.name == $name) | .size' "${release_json}")"
  digest="$(jq -r --arg name "${name}" '.assets[] | select(.name == $name) | .digest // empty' "${release_json}")"
  local_size="$(wc -c <"${file}" | tr -d ' ')"
  local_digest="sha256:$(sha256sum "${file}" | awk '{print $1}')"

  if [[ "${state}" != "uploaded" ]]; then
    error "Existing ${name} is in state '${state}', not 'uploaded'; preserving it and stopping."
    return 1
  fi
  if [[ "${size}" != "${local_size}" ]]; then
    error "Existing ${name} is ${size} bytes, but this build is ${local_size} bytes; preserving it and stopping."
    return 1
  fi
  if [[ -z "${digest}" ]]; then
    error "GitHub did not provide a digest for existing ${name}; refusing to claim it matches or overwrite it."
    return 1
  fi
  if [[ "${digest}" != "${local_digest}" ]]; then
    error "Existing ${name} digest ${digest} differs from this build ${local_digest}; preserving it and stopping."
    return 1
  fi
  return 0
}

cmd_ensure_asset() {
  local repo="" release_json="" file="" name=""
  while (($#)); do
    case "$1" in
      --repo) repo="${2:-}"; shift 2 ;;
      --json) release_json="${2:-}"; shift 2 ;;
      --file) file="${2:-}"; shift 2 ;;
      --name) name="${2:-}"; shift 2 ;;
      *) error "Unknown ensure-asset argument: $1"; return 1 ;;
    esac
  done
  [[ -n "${repo}" && -f "${release_json}" && -f "${file}" ]] || {
    error "ensure-asset requires --repo, an existing --json release object, and an existing --file."
    return 1
  }
  name="${name:-$(basename "${file}")}"

  local status=0
  asset_result "${release_json}" "${file}" "${name}" || status=$?
  if [[ "${status}" -eq 0 ]]; then
    log "Verified existing release asset ${name}; no upload or overwrite needed."
    return 0
  fi
  if [[ "${status}" -ne 5 ]]; then
    return "${status}"
  fi

  [[ -n "${GH_TOKEN:-}" ]] || { error "GH_TOKEN is required to upload a release asset."; return 1; }
  local release_id upload_url expected_url encoded_name response response_error refreshed
  release_id="$(jq -r '.id // empty' "${release_json}")"
  upload_url="$(jq -r '.upload_url // empty | sub("\\{\\?name,label\\}$"; "")' "${release_json}")"
  [[ "${release_id}" =~ ^[0-9]+$ ]] || { error "Release ID is missing or invalid."; return 1; }
  expected_url="https://uploads.github.com/repos/${repo}/releases/${release_id}/assets"
  if [[ "${upload_url}" != "${expected_url}" ]]; then
    error "Unexpected release upload URL; refusing to send an asset to ${upload_url:-<empty>}."
    return 1
  fi
  encoded_name="$(jq -nr --arg name "${name}" '$name | @uri')"
  response="$(mktemp)"
  response_error="$(mktemp)"
  refreshed="$(mktemp)"

  # Do not use `gh release upload` here: it resolves by tag through
  # GET /releases/tags/{tag}, which is 404 for drafts. Use the draft's upload_url.
  if ! curl --silent --show-error --fail-with-body \
    -H "Authorization: Bearer ${GH_TOKEN}" \
    -H 'Accept: application/vnd.github+json' \
    -H 'X-GitHub-Api-Version: 2022-11-28' \
    -H 'Content-Type: application/octet-stream' \
    --data-binary "@${file}" \
    "${upload_url}?name=${encoded_name}" >"${response}" 2>"${response_error}"; then
    # A connection can fail after GitHub has accepted the bytes. Re-read by ID
    # and accept only an exact digest match; otherwise leave any partial asset
    # untouched and report the failure for a human to inspect.
    if gh api "repos/${repo}/releases/${release_id}" >"${refreshed}" 2>/dev/null; then
      cp "${refreshed}" "${release_json}"
      status=0
      asset_result "${release_json}" "${file}" "${name}" || status=$?
      if [[ "${status}" -eq 0 ]]; then
        log "GitHub accepted ${name} before the upload connection ended; its digest matches."
        rm -f "${response}" "${response_error}" "${refreshed}"
        return 0
      fi
    fi
    error "Upload of ${name} failed; no existing asset was changed."
    if [[ -s "${response_error}" ]]; then cat "${response_error}" >&2; fi
    if [[ -s "${response}" ]]; then cat "${response}" >&2; fi
    rm -f "${response}" "${response_error}" "${refreshed}"
    return 1
  fi

  if ! gh api "repos/${repo}/releases/${release_id}" >"${refreshed}"; then
    error "Uploaded ${name}, but could not re-read release ${release_id}; re-run safely to verify the asset."
    rm -f "${response}" "${response_error}" "${refreshed}"
    return 1
  fi
  cp "${refreshed}" "${release_json}"
  status=0
  asset_result "${release_json}" "${file}" "${name}" || status=$?
  if [[ "${status}" -ne 0 ]]; then
    error "GitHub accepted the upload but its reported metadata did not verify for ${name}; preserving the asset."
    rm -f "${response}" "${response_error}" "${refreshed}"
    return 1
  fi

  local local_size local_digest
  local_size="$(wc -c <"${file}" | tr -d ' ')"
  local_digest="sha256:$(sha256sum "${file}" | awk '{print $1}')"
  log "Uploaded and verified ${name} (${local_size} bytes, ${local_digest})."
  rm -f "${response}" "${response_error}" "${refreshed}"
}

cmd_update_body() {
  local repo="" release_json="" body_file=""
  while (($#)); do
    case "$1" in
      --repo) repo="${2:-}"; shift 2 ;;
      --json) release_json="${2:-}"; shift 2 ;;
      --body-file) body_file="${2:-}"; shift 2 ;;
      *) error "Unknown update-body argument: $1"; return 1 ;;
    esac
  done
  [[ -n "${repo}" && -f "${release_json}" && -f "${body_file}" ]] || {
    error "update-body requires --repo, an existing --json release object, and an existing --body-file."
    return 1
  }

  local release_id current_body draft_state payload updated_body
  release_id="$(jq -r '.id // empty' "${release_json}")"
  [[ "${release_id}" =~ ^[0-9]+$ ]] || { error "Release ID is missing or invalid."; return 1; }
  current_body="$(jq -r '.body // ""' "${release_json}")"
  updated_body="$(cat "${body_file}")"
  if [[ "${current_body}" == "${updated_body}" ]]; then
    log "Release notes already contain the requested body; no edit needed."
    return 0
  fi
  draft_state="$(jq -r '.draft' "${release_json}")"
  payload="$(mktemp)"
  local refreshed
  refreshed="$(mktemp)"
  jq -n --rawfile body "${body_file}" '{body: $body}' >"${payload}"

  # PATCH by release ID works for drafts. Omitting `draft` preserves the
  # current public/draft state and never publishes a release as a side effect.
  if ! gh api --method PATCH "repos/${repo}/releases/${release_id}" --input "${payload}" >/dev/null; then
    error "Could not update the notes on release ${release_id}."
    rm -f "${payload}" "${refreshed}"
    return 1
  fi
  if ! gh api "repos/${repo}/releases/${release_id}" >"${refreshed}"; then
    error "Updated the notes, but could not re-read release ${release_id}."
    rm -f "${payload}" "${refreshed}"
    return 1
  fi
  if ! jq -e --rawfile body "${body_file}" '.body == $body' "${refreshed}" >/dev/null; then
    error "GitHub did not return the requested release notes after the update."
    rm -f "${payload}" "${refreshed}"
    return 1
  fi
  if [[ "$(jq -r '.draft' "${refreshed}")" != "${draft_state}" ]]; then
    error "Release ${release_id} changed publication state during notes update; no state change was requested."
    rm -f "${payload}" "${refreshed}"
    return 1
  fi
  cp "${refreshed}" "${release_json}"
  log "Updated release notes for ${release_id} without changing its publication state."
  rm -f "${payload}" "${refreshed}"
}

main() {
  local command="${1:-}"
  shift || true
  case "${command}" in
    lookup) cmd_lookup "$@" ;;
    ensure-asset) cmd_ensure_asset "$@" ;;
    update-body) cmd_update_body "$@" ;;
    -h|--help|help) usage 0 ;;
    *) usage 1 ;;
  esac
}

main "$@"
