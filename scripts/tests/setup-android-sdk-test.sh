#!/usr/bin/env bash
#
# Offline test for scripts/setup-android-sdk.sh.
#
# The f41ea7a CI failure (issue #309) was not a code bug: the runner shipped an
# `ndk/<version>` directory that was not an NDK, and sdkmanager then kept
# failing on a truncated download cache. This test drives the real script
# against a fake SDK root and a fake sdkmanager, and asserts that the script
#
#   * repairs a preinstalled NDK directory that has no source.properties
#   * drops sdkmanager's download cache before retrying
#   * retries a failing install and succeeds on a later attempt
#   * fails (non-zero) when no usable NDK can be produced
#   * leaves a healthy SDK untouched (no reinstall, no deletion)
#
# No network, no JDK and no real SDK are needed.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETUP="${SCRIPT_DIR}/../setup-android-sdk.sh"
[[ -x "${SETUP}" ]] || { echo "FAIL: ${SETUP} is missing or not executable" >&2; exit 1; }

failures=0
check() {
  local label="$1"
  shift
  if "$@"; then
    printf '  ok   %s\n' "${label}"
  else
    printf '  FAIL %s\n' "${label}"
    failures=$((failures + 1))
  fi
}

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

# --- fake SDK toolkit -------------------------------------------------------

# sdkmanager stub. Writes its arguments to a log and materialises the package
# directory for the requested spec, honouring FAIL_SPECS (fail the first N times)
# and CORRUPT_SPECS (create the directory without source.properties).
make_sdkmanager() {
  local root="$1" log="$2" bindir="$3"
  mkdir -p "${bindir}"
  cat > "${bindir}/sdkmanager" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail
log="${FAKE_SDKMANAGER_LOG:?}"
echo "$*" >> "${log}"
if [[ "$*" == "--version" ]]; then echo "999.0"; exit 0; fi
if [[ "$*" == *"--licenses"* ]]; then exit 0; fi
spec=""
for arg in "$@"; do
  case "${arg}" in
    platforms\;*|build-tools\;*|cmake\;*|ndk\;*) spec="${arg}" ;;
  esac
done
[[ -n "${spec}" ]] || exit 1
kind="${spec%%;*}"
version="${spec#*;}"
case "${kind}" in
  platforms) dir="${FAKE_SDK}/platforms/${version}" ;;
  build-tools) dir="${FAKE_SDK}/build-tools/${version}" ;;
  cmake) dir="${FAKE_SDK}/cmake/${version}" ;;
  ndk) dir="${FAKE_SDK}/ndk/${version}" ;;
  *) exit 1 ;;
esac
# Failure injection: count attempts per spec.
count_file="${FAKE_STATE}/count-${kind}-${version}"
count=0
[[ -f "${count_file}" ]] && count="$(cat "${count_file}")"
count=$((count + 1))
echo "${count}" > "${count_file}"
if [[ ",${FAIL_SPECS:-}," == *",${spec},"* ]]; then
  limit="${FAIL_ATTEMPTS:-1}"
  if (( count <= limit )); then
    # Leave a partial directory behind, exactly like a truncated install.
    mkdir -p "${dir}"
    echo "simulated truncated install of ${spec}" >&2
    exit 1
  fi
fi
mkdir -p "${dir}"
if [[ ",${CORRUPT_SPECS:-}," == *",${spec},"* ]]; then
  echo "installed but corrupt: ${spec}" >&2
  exit 0
fi
case "${kind}" in
  ndk)
    echo "Pkg.Desc = Android NDK" > "${dir}/source.properties"
    : > "${dir}/ndk-build"
    ;;
  *)
    echo "Pkg.Desc = ${kind}" > "${dir}/source.properties"
    ;;
esac
STUB
  chmod +x "${bindir}/sdkmanager"
  : > "${log}"
}

# Seeds a "runner image" SDK: healthy platform/build-tools/cmake plus one NDK
# directory that is present but unusable (the real failure).
seed_image() {
  local sdk="$1" ndk="$2"
  mkdir -p "${sdk}/platforms/android-37.1" "${sdk}/build-tools/36.0.0" \
    "${sdk}/cmake/3.22.1" "${sdk}/ndk/${ndk}" "${sdk}/.downloadIntermediates/partial"
  echo "Pkg.Desc = platform" > "${sdk}/platforms/android-37.1/source.properties"
  echo "Pkg.Desc = build-tools" > "${sdk}/build-tools/36.0.0/source.properties"
  echo "Pkg.Desc = cmake" > "${sdk}/cmake/3.22.1/source.properties"
  # Deliberately incomplete: no source.properties, no ndk-build.
  echo "not really an ndk" > "${sdk}/ndk/${ndk}/README"
}

run_setup() {
  local sdk="$1" bindir="$2" log="$3"; shift 3
  (
    export PATH="${bindir}:${PATH}"
    export FAKE_SDK="${sdk}"
    export FAKE_SDKMANAGER_LOG="${log}"
    export FAKE_STATE="${sdk}/fake-state"
    export ANDROID_HOME="${sdk}"
    export ANDROID_SDK_ROOT="${sdk}"
    export ANDROID_COMPILE_SDK=37 ANDROID_COMPILE_SDK_MINOR=1
    export ANDROID_BUILD_TOOLS=36.0.0 ANDROID_CMAKE_VERSION=3.22.1
    export GITHUB_ENV="${sdk}/github-env"
    mkdir -p "${FAKE_STATE}"
    env "$@" "${SETUP}"
  ) >"${sdk}/setup.log" 2>&1
}

echo "== Android SDK setup"

# --- 1. the actual f41ea7a scenario ----------------------------------------
sdk="${work}/sdk-broken"; bindir="${work}/bin-broken"; log="${work}/log-broken"
seed_image "${sdk}" "28.2.13676358"
make_sdkmanager "${sdk}" "${log}" "${bindir}"
check "repairs a runner image whose NDK has no source.properties" \
  run_setup "${sdk}" "${bindir}" "${log}" ANDROID_NDK_VERSION=28.2.13676358
check "the repaired NDK is genuinely usable" \
  test -f "${sdk}/ndk/28.2.13676358/source.properties"
check "the repaired NDK has ndk-build" \
  test -f "${sdk}/ndk/28.2.13676358/ndk-build"
check "the corrupt download cache was dropped" \
  test ! -d "${sdk}/.downloadIntermediates"
check "the NDK this time was installed through sdkmanager" \
  grep -q 'ndk;28.2.13676358' "${log}"
check "the validated NDK version is exported for Gradle" \
  grep -q '^ANDROID_NDK_VERSION=28.2.13676358$' "${sdk}/github-env"
check "the validated NDK home is exported" \
  grep -q "^ANDROID_NDK_HOME=${sdk}/ndk/28.2.13676358$" "${sdk}/github-env"

# --- 2. a healthy SDK is left alone ----------------------------------------
sdk="${work}/sdk-healthy"; bindir="${work}/bin-healthy"; log="${work}/log-healthy"
mkdir -p "${sdk}/platforms/android-37.1" "${sdk}/build-tools/36.0.0" \
  "${sdk}/cmake/3.22.1" "${sdk}/ndk/28.2.13676358"
for part in platforms/android-37.1 build-tools/36.0.0 cmake/3.22.1 ndk/28.2.13676358; do
  echo "Pkg.Desc = ok" > "${sdk}/${part}/source.properties"
done
: > "${sdk}/ndk/28.2.13676358/ndk-build"
make_sdkmanager "${sdk}" "${log}" "${bindir}"
check "a healthy SDK is accepted without changes" \
  run_setup "${sdk}" "${bindir}" "${log}" ANDROID_NDK_VERSION=28.2.13676358
check "nothing was (re)installed on a healthy SDK" \
  bash -c "! grep -qE '^(ndk|platforms|build-tools|cmake);' '${log}'"

# --- 3. transient install failure is retried, not fatal --------------------
sdk="${work}/sdk-retry"; bindir="${work}/bin-retry"; log="${work}/log-retry"
seed_image "${sdk}" "28.0.0"
make_sdkmanager "${sdk}" "${log}" "${bindir}"
check "retries a transient sdkmanager failure and then succeeds" \
  run_setup "${sdk}" "${bindir}" "${log}" \
    ANDROID_NDK_VERSION=28.2.13676358 FAIL_SPECS='ndk;28.2.13676358' FAIL_ATTEMPTS=1
check "the retried NDK ended up usable" \
  test -f "${sdk}/ndk/28.2.13676358/source.properties"
check "the preinstalled broken NDK was repaired too" \
  test -f "${sdk}/ndk/28.0.0/source.properties"

# --- 4. an install that never produces a usable NDK fails closed -----------
sdk="${work}/sdk-corrupt"; bindir="${work}/bin-corrupt"; log="${work}/log-corrupt"
seed_image "${sdk}" "28.2.13676358"
make_sdkmanager "${sdk}" "${log}" "${bindir}"
if run_setup "${sdk}" "${bindir}" "${log}" \
    ANDROID_NDK_VERSION=28.2.13676358 CORRUPT_SPECS='ndk;28.2.13676358' \
    ANDROID_NDK_FALLBACK_VERSION=28.2.13676358; then
  printf '  FAIL %s\n' "fails closed when no usable NDK can be produced"
  failures=$((failures + 1))
else
  printf '  ok   %s\n' "fails closed when no usable NDK can be produced"
fi
check "the failure explains itself" \
  grep -q 'No usable Android NDK' "${sdk}/setup.log"
check "a corrupt-but-present NDK is never reported as ready" \
  bash -c "! grep -q 'Android SDK ready' '${sdk}/setup.log'"

# --- 5. the script is shell-lint clean enough to run under set -e ----------
check "the script parses" bash -n "${SETUP}"
check "the script is executable" test -x "${SETUP}"
check "the NDK repair is documented" \
  bash -c "grep -q 'CXX1101' '${SETUP}'"

printf '\n'
if [[ "${failures}" -ne 0 ]]; then
  printf '%d Android SDK setup check(s) failed\n' "${failures}"
  exit 1
fi
printf 'Android SDK setup checks passed\n'
