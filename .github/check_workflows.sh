#!/usr/bin/env bash
set -euo pipefail
# Release-tool dependency only; app dependency versions remain source-pinned.
version=1.7.12
sha256=8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8
temp="$(mktemp -d "${RUNNER_TEMP:-/tmp}/streambridge-actionlint.XXXXXX")"
trap 'rm -rf "$temp"' EXIT
curl --fail --location --retry 3 --silent --show-error \
  "https://github.com/rhysd/actionlint/releases/download/v${version}/actionlint_${version}_linux_amd64.tar.gz" \
  -o "$temp/actionlint.tar.gz"
printf '%s  %s\n' "$sha256" "$temp/actionlint.tar.gz" | sha256sum --check --status
tar -xzf "$temp/actionlint.tar.gz" -C "$temp"
"$temp/actionlint" -shellcheck='' .github/workflows/*.yml
