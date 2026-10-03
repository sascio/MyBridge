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
# Deliberately bash 3.2 compatible: the macOS runners ship bash 3.2 as
# /bin/bash, and the caller may run this with any bash on PATH.
#
# Usage: report-ios-failure.sh <log-file> [max-lines]
# Environment: PR_NUMBER (optional), GH_TOKEN (optional), GITHUB_REPOSITORY,
#              GITHUB_STEP_SUMMARY, GITHUB_RUN_ID

set -uo pipefail

log_file="${1:-}"
max_lines="${2:-40}"

echo "::error::iOS build failure reporter invoked."

if [ -z "${log_file}" ] || [ ! -f "${log_file}" ]; then
    echo "::error::The iOS build failed and no build log was captured."
    exit 0
fi

diagnostics="$(mktemp)"
tail_log="$(mktemp)"
trap 'rm -f "${diagnostics}" "${tail_log}"' EXIT

# Lines that explain a build failure, most specific first.
grep -aE 'error:|error |fatal error|FAILED|FAILURE: Build failed|The following build commands failed|Undefined symbols|ld: |linker command failed|No such file or directory|not found|Execution failed for task|What went wrong|Caused by|Compilation error|^e: ' \
    "${log_file}" | tail -n "${max_lines}" | cut -c1-900 > "${diagnostics}"
tail -n "${max_lines}" "${log_file}" | cut -c1-900 > "${tail_log}"

if [ ! -s "${diagnostics}" ]; then
    # Never end up with an unreported failure: when nothing matched the known
    # failure patterns, the tail of the log is the best evidence available.
    cp "${tail_log}" "${diagnostics}"
fi

# GitHub shows at most ten annotations per check run; the rest stay in the step
# summary and the pull-request comment.
echo "::error::iOS build failed; diagnostics follow ($(wc -l < "${diagnostics}" | tr -d ' ') lines)."
head -n 9 "${diagnostics}" | while IFS= read -r line; do
    echo "::error::${line}"
done

{
    echo "## StreamBridge iOS build failure"
    echo
    echo 'Diagnostic lines:'
    echo
    echo '```'
    cat "${diagnostics}"
    echo '```'
    echo
    echo "Last ${max_lines} log lines:"
    echo
    echo '```'
    cat "${tail_log}"
    echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"

if [ -n "${PR_NUMBER:-}" ] && [ -n "${GH_TOKEN:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
    body_file="$(mktemp)"
    {
        echo "### StreamBridge iOS build failed"
        echo
        echo "Run: \`${GITHUB_RUN_ID:-}\`"
        echo
        echo 'Diagnostic lines:'
        echo
        echo '```'
        head -n 60 "${diagnostics}"
        echo '```'
        echo
        echo 'Last 60 log lines:'
        echo
        echo '```'
        tail -n 60 "${log_file}" | cut -c1-500
        echo '```'
    } > "${body_file}"
    if gh api "repos/${GITHUB_REPOSITORY}/issues/${PR_NUMBER}/comments" \
        --method POST -F "body=@${body_file}" >/dev/null 2>&1; then
        echo "Published the iOS build log to pull request ${PR_NUMBER}."
    else
        echo "::warning::Could not publish the iOS build log to pull request ${PR_NUMBER}."
    fi
    rm -f "${body_file}"
fi
