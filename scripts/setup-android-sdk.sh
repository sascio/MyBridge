#!/usr/bin/env bash
#
# Prepare the Android SDK on a GitHub runner in a way that is deterministic.
#
# Why this exists (CI failure on f41ea7a, issue #309):
#
#   * `[CXX1101] NDK at /usr/local/lib/android/sdk/ndk/28.2.13676358 did not
#     have a source.properties file` -- the runner image ships an ndk/<version>
#     directory that is not a usable NDK. AGP selects it, then fails while
#     configuring :androidApp, so even the *unit test* job step fails at
#     configuration time rather than on a test.
#   * `InstallFailedException: Failed to install the following SDK components`
#     plus `java.util.zip.ZipException: Archive is not a ZIP archive` -- the
#     sdkmanager retry then tried to install the NDK from a truncated cached
#     download and failed again.
#
# The fix is to stop trusting whatever the image happens to contain: validate
# every component we depend on, remove the broken copy, drop the corrupt
# download cache, and reinstall with retries. Everything is idempotent, so the
# fast path on a healthy runner only validates and installs nothing.
#
# Usage: scripts/setup-android-sdk.sh
#
# Environment (all optional, CI sets them):
#   ANDROID_HOME / ANDROID_SDK_ROOT   SDK root (defaults to the runner image path)
#   ANDROID_COMPILE_SDK               major API level, e.g. 37
#   ANDROID_COMPILE_SDK_MINOR         minor API level, e.g. 1
#   ANDROID_BUILD_TOOLS               e.g. 36.0.0
#   ANDROID_CMAKE_VERSION             e.g. 3.22.1
#   ANDROID_NDK_VERSION               pin, e.g. 28.2.13676358 (optional; when set
#                                     the exact NDK is required rather than any)
#   ANDROID_NDK_FALLBACK_VERSION      used when the pin is unavailable (optional)
#   ANDROID_SDK_SETUP_MARKER          file to touch on success (for env export)

set -euo pipefail

log() { printf '%s\n' "$*"; }
fail() { printf '::error::%s\n' "$*" >&2; exit 1; }
notice() { printf '::notice::%s\n' "$*"; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
COMPILE_SDK="${ANDROID_COMPILE_SDK:-37}"
COMPILE_SDK_MINOR="${ANDROID_COMPILE_SDK_MINOR:-1}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-36.0.0}"
CMAKE_VERSION="${ANDROID_CMAKE_VERSION:-3.22.1}"
NDK_PIN="${ANDROID_NDK_VERSION:-}"
NDK_FALLBACK="${ANDROID_NDK_FALLBACK_VERSION:-28.2.13676358}"
MARKER="${ANDROID_SDK_SETUP_MARKER:-}"

[[ -d "$SDK" ]] || fail "Android SDK root does not exist: $SDK"
PLATFORM="android-${COMPILE_SDK}.${COMPILE_SDK_MINOR}"

SDKMANAGER="$(command -v sdkmanager || true)"
if [[ -z "$SDKMANAGER" ]]; then
  for candidate in \
    "$SDK/cmdline-tools/latest/bin/sdkmanager" \
    "$SDK/cmdline-tools/bin/sdkmanager" \
    "$SDK/tools/bin/sdkmanager"; do
    if [[ -x "$candidate" ]]; then SDKMANAGER="$candidate"; break; fi
  done
fi
[[ -n "$SDKMANAGER" ]] || fail "sdkmanager was not found under $SDK"
log "sdkmanager: $SDKMANAGER"
"$SDKMANAGER" --version
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

# The download cache is what turned one bad download into a permanent failure:
# sdkmanager reused the truncated archive on every retry. Drop it whenever a
# component is missing so the next attempt downloads from scratch.
clear_download_cache() {
  local cache="$1"
  [[ -d "$cache" ]] || return 0
  log "Dropping the SDK download cache at $cache"
  rm -rf "$cache"
}

# A component directory is only usable when its package.xml and (for NDK,
# build-tools, cmake) source.properties exist. AGP validates exactly that, so a
# directory that fails these checks is removed rather than added to.
component_is_usable() {
  local dir="$1"
  [[ -f "$dir/source.properties" || -f "$dir/package.xml" ]]
}

install_component() {
  local spec="$1" dest="$2" attempts="${3:-3}"
  if component_is_usable "$dest"; then
    log "$spec already installed and usable at $dest"
    return 0
  fi
  if [[ -d "$dest" ]]; then
    log "Removing the incomplete $spec at $dest"
    rm -rf "$dest"
  fi
  local attempt
  for attempt in $(seq 1 "$attempts"); do
    clear_download_cache "$SDK/.downloadIntermediates"
    log "Installing $spec (attempt ${attempt}/${attempts})"
    if "$SDKMANAGER" --install "$spec"; then
      if component_is_usable "$dest"; then
        return 0
      fi
      log "$spec installed but left no source.properties/package.xml at $dest"
      rm -rf "$dest"
    else
      log "$spec install attempt ${attempt} failed"
    fi
    sleep $((attempt * 5))
  done
  return 1
}

# 1. Platform. The runner ships minor-versioned platforms (android-37.0,
#    android-37.1, ...); the unsuffixed package no longer exists.
install_component "platforms;$PLATFORM" "$SDK/platforms/$PLATFORM" \
  || fail "Could not install platforms;$PLATFORM"

# 2. Build tools. AGP needs this exact version for aapt2/apksigner.
install_component "build-tools;$BUILD_TOOLS" "$SDK/build-tools/$BUILD_TOOLS" \
  || fail "Could not install build-tools;$BUILD_TOOLS"

# 3. CMake, used by :androidApp's libmpv/libass module.
install_component "cmake;$CMAKE_VERSION" "$SDK/cmake/$CMAKE_VERSION" \
  || fail "Could not install cmake;$CMAKE_VERSION"

# 4. NDK. :androidApp declares `ndkVersion`, so AGP resolves an NDK at
#    configuration time for every task, including unit tests. Whatever the image
#    preinstalled must actually be a working NDK, or nothing builds.
ndk_usable() {
  local dir="$1"
  [[ -f "$dir/source.properties" && -f "$dir/ndk-build" ]]
}

repair_ndk() {
  local version="$1"
  local dest="$SDK/ndk/$version"
  if ndk_usable "$dest"; then
    log "NDK $version already installed and usable"
    return 0
  fi
  if [[ -d "$dest" ]]; then
    # THIS is the f41ea7a failure: the directory existed but was not an NDK.
    log "Removing the unusable NDK directory $dest (missing source.properties/ndk-build)"
    rm -rf "$dest"
  fi
  install_component "ndk;$version" "$dest" || return 1
  ndk_usable "$dest"
}

installed_ndk_versions() {
  [[ -d "$SDK/ndk" ]] || return 0
  find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort
}

# The image's broken directories are repaired even when a different version is
# selected, because AGP falls back to "the installed NDK" and would otherwise
# pick a broken one again.
while IFS= read -r version; do
  [[ -n "$version" ]] || continue
  # Best effort: an NDK we cannot repair is removed so AGP cannot select it, and
  # the selection loop below still has to find a usable one. Losing one obsolete
  # version must not fail the build by itself.
  if ! repair_ndk "$version"; then
    log "Discarding the unusable NDK $version"
    rm -rf "$SDK/ndk/$version"
  fi
done < <(installed_ndk_versions)

selected_ndk=""
for candidate in ${NDK_PIN:+$NDK_PIN} "$NDK_FALLBACK"; do
  [[ -n "$candidate" ]] || continue
  if repair_ndk "$candidate"; then
    selected_ndk="$candidate"
    break
  fi
  log "NDK $candidate could not be installed; trying the next candidate"
done

if [[ -z "$selected_ndk" ]]; then
  # Last resort: any usable NDK already on the image.
  for version in $(installed_ndk_versions); do
    if ndk_usable "$SDK/ndk/$version"; then selected_ndk="$version"; break; fi
  done
fi
[[ -n "$selected_ndk" ]] || fail "No usable Android NDK is available under $SDK/ndk"

# Pin the build to the NDK we validated instead of letting AGP guess. The Gradle
# property is read by androidApp/build.gradle.kts when present.
echo "ANDROID_HOME=$SDK" >> "${GITHUB_ENV:-/dev/null}"
echo "ANDROID_SDK_ROOT=$SDK" >> "${GITHUB_ENV:-/dev/null}"
echo "ANDROID_NDK_HOME=$SDK/ndk/$selected_ndk" >> "${GITHUB_ENV:-/dev/null}"
echo "ANDROID_NDK_VERSION=$selected_ndk" >> "${GITHUB_ENV:-/dev/null}"
[[ -n "$MARKER" ]] && touch "$MARKER"
notice "Android SDK ready: platform=$PLATFORM build-tools=$BUILD_TOOLS cmake=$CMAKE_VERSION ndk=$selected_ndk"
