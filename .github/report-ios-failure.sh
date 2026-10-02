#!/usr/bin/env bash
#
# Surfaces iOS build failures.
#
# GitHub keeps the raw build log behind signed blob URLs that are not always
# reachable, so this reporter re-publishes the interesting part of the log
# through channels that survive: workflow annotations (readable through the
# check-runs API even when the log itself cannot be downloaded) and, on pull
# requests, a comment on the PR.
#
# Usage: report-ios-failure.sh <log-file> [max-lines]
# Environment: PR_NUMBER (optional), GH_TOKEN (optional), GITHUB_REPOSITORY,
#              GITHUB_STEP_SUMMARY, GITHUB_RUN_ID

set -uo pipefail

log_file="${1:-}"
max_lines="${2:-40}"

if [[ -z "${log_file}" || ! -f "${log_file}" ]]; then
    echo "::error::The iOS build failed and no build log was captured."
    exit 0
fi

# Lines that explain a build failure.
pattern='error:|error |fatal error|FAILED|FAILURE: Build failed|The following build commands failed|Undefined symbols|ld: |linker command failed|No such file or directory|not found|Execution failed for task|What went wrong|Caused by|Compilation error|^e: '

mapfile -t matches < <(grep -aEn "${pattern}" "${log_file}" | tail -n "${max_lines}" | cut -c1-900)
mapfile -t tail_lines < <(tail -n "${max_lines}" "${log_file}" | cut -c1-900)

# Never end up with an unreported failure: when nothing matched the known
# failure patterns, the tail of the log is the best evidence available.
if (( ${#matches[@]} == 0 )); then
    matches=("${tail_lines[@]}")
fi

echo "::error::iOS build failed; ${#matches[@]} diagnostic line(s) reported as annotations."
# GitHub shows at most ten annotations per check run; the rest stay in the
# step summary and the pull-request comment.
for line in "${matches[@]:0:10}"; do
    echo "::error::${line}"
done

{
    echo "## StreamBridge iOS build failure"
    echo
    echo 'Diagnostic lines:'
    echo
    echo '```'
    printf '%s\n' "${matches[@]}"
    echo '```'
    echo
    echo "Last ${#tail_lines[@]} log lines:"
    echo
    echo '```'
    printf '%s\n' "${tail_lines[@]}"
    echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"

if [[ -n "${PR_NUMBER:-}" && -n "${GH_TOKEN:-}" && -n "${GITHUB_REPOSITORY:-}" ]]; then
    body_file="$(mktemp)"
    {
        echo "### StreamBridge iOS build failed"
        echo
        echo "Run: \`${GITHUB_RUN_ID:-}\`"
        echo
        echo 'Diagnostic lines:'
        echo
        echo '```'
        printf '%s\n' "${matches[@]}" | head -n 60
        echo '```'
        echo
        echo 'Last 60 log lines:'
        echo
        echo '```'
        tail -n 60 "${log_file}" | cut -c1-500
        echo '```'
    } > "${body_file}"
    if ! gh api "repos/${GITHUB_REPOSITORY}/issues/${PR_NUMBER}/comments" \
        --method POST -F "body=@${body_file}" >/dev/null 2>&1; then
        echo "::warning::Could not publish the iOS build log to pull request ${PR_NUMBER}."
    fi
    rm -f "${body_file}"
fi
