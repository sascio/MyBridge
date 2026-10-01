#!/usr/bin/env bash
# StreamBridge's native host + shared framework, full sideload distribution.
# unsigned: validation archive/IPA only; signed: validated profiles + export.
set -euo pipefail
repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "${repository_root}"
mode="${1:-unsigned}"
if [[ "$(uname -s)" != Darwin ]]; then
    echo "StreamBridge iOS builds require macOS and Xcode 26.6 (see docs/IOS.md)." >&2
    exit 1
fi
case "${mode}" in unsigned|signed) ;; *) echo "Expected unsigned or signed." >&2; exit 1 ;; esac
python3 .github/streambridge_release.py
version="$(python3 -c 'import sys; sys.path.insert(0,".github"); from streambridge_release import check_release; print(check_release().version)')"
output="${repository_root}/build/artifacts/ios"
archive="${repository_root}/build/ios/StreamBridge-${version}.xcarchive"
derived_data="${repository_root}/build/ios/DerivedData"
mkdir -p "${output}"
# Full Compose release linking exceeded the verified upstream 4608M heap on
# macos-26. Keep optimizations enabled; budget 8 GiB for Native's whole-program
# analysis and pass it explicitly to Gradle rather than relying on project-env
# aliases for daemon/system settings.
export NUVIO_GRADLE_JVMARGS="${NUVIO_GRADLE_JVMARGS:--Xmx8g -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=768M}"
export NUVIO_KOTLIN_NATIVE_JVMARGS="${NUVIO_KOTLIN_NATIVE_JVMARGS:--Xmx8g}"
export NUVIO_IOS_DISTRIBUTION=full
if [[ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-NO}" == YES ]]; then
    echo 'Release archives must build the shared framework, not reuse an IDE override.' >&2
    exit 1
fi
export GRADLE_OPTS="${GRADLE_OPTS:--Dfile.encoding=UTF-8}"
export KOTLIN_DAEMON_JVMARGS="${KOTLIN_DAEMON_JVMARGS:--Xmx2048M}"
export CLANG_MODULE_CACHE_PATH="${derived_data}/ModuleCache.noindex"
export SWIFTPM_MODULECACHE_OVERRIDE="${derived_data}/SwiftPMModuleCache.noindex"
build_environment=(env
    "ORG_GRADLE_PROJECT_org.gradle.jvmargs=${NUVIO_GRADLE_JVMARGS:--Xmx8g -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=768M}"
    "ORG_GRADLE_PROJECT_kotlin.native.jvmArgs=${NUVIO_KOTLIN_NATIVE_JVMARGS:--Xmx8g}")
./scripts/prepare-ios-dependencies.sh
xcodebuild -version
xcrun --sdk iphoneos --show-sdk-version
"${build_environment[@]}" xcodebuild -resolvePackageDependencies \
    -project iosApp/iosApp.xcodeproj -scheme StreamBridge \
    -derivedDataPath "${derived_data}"
signing_settings=(CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=)
if [[ "${mode}" == signed ]]; then
    required=(IOS_TEAM_ID IOS_SIGNING_IDENTITY IOS_APP_PROFILE_UUID IOS_WIDGET_PROFILE_UUID IOS_EXPORT_OPTIONS)
    for name in "${required[@]}"; do
        if [[ -z "${!name:-}" ]]; then
            echo "Missing ${name}; signing setup must succeed before archiving. See docs/IOS.md." >&2
            exit 1
        fi
    done
    test -f "${IOS_EXPORT_OPTIONS}"
    signing_settings=(CODE_SIGNING_ALLOWED=YES CODE_SIGNING_REQUIRED=YES
        "STREAMBRIDGE_TEAM_ID=${IOS_TEAM_ID}" STREAMBRIDGE_CODE_SIGN_STYLE=Manual
        "STREAMBRIDGE_CODE_SIGN_IDENTITY=${IOS_SIGNING_IDENTITY}"
        "STREAMBRIDGE_APP_PROFILE_UUID=${IOS_APP_PROFILE_UUID}"
        "STREAMBRIDGE_WIDGET_PROFILE_UUID=${IOS_WIDGET_PROFILE_UUID}")
fi
"${build_environment[@]}" xcodebuild \
    -project iosApp/iosApp.xcodeproj -scheme StreamBridge \
    -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
    -derivedDataPath "${derived_data}" -archivePath "${archive}" \
    "${signing_settings[@]}" archive
python3 .github/verify_ios_archive.py "${archive}" --mode "${mode}"
if [[ "${mode}" == unsigned ]]; then
    package_root="$(mktemp -d "${TMPDIR:-/tmp}/streambridge-unsigned-ipa.XXXXXX")"
    trap 'rm -rf "${package_root}"' EXIT
    mkdir -p "${package_root}/Payload"
    ditto "${archive}/Products/Applications/StreamBridge.app" "${package_root}/Payload/StreamBridge.app"
    (cd "${package_root}" && /usr/bin/zip -qry "${output}/StreamBridge-${version}-unsigned.ipa" Payload)
    /usr/bin/ditto -c -k --keepParent "${archive}" "${output}/StreamBridge-${version}-unsigned.xcarchive.zip"
    python3 .github/verify_ios_archive.py "${output}/StreamBridge-${version}-unsigned.ipa" --mode unsigned
else
    export_path="${repository_root}/build/ios/export"
    xcodebuild -exportArchive -archivePath "${archive}" \
        -exportOptionsPlist "${IOS_EXPORT_OPTIONS}" -exportPath "${export_path}"
    test -s "${export_path}/StreamBridge.ipa"
    cp "${export_path}/StreamBridge.ipa" "${output}/StreamBridge-${version}.ipa"
    /usr/bin/ditto -c -k --keepParent "${archive}" "${output}/StreamBridge-${version}.xcarchive.zip"
    # Verify the exported app/extension as well as the archive; export re-signs.
    python3 .github/verify_ios_archive.py "${output}/StreamBridge-${version}.ipa" --mode signed
fi
python3 - "${output}" <<'PY'
import hashlib,sys
from pathlib import Path
root=Path(sys.argv[1])
files=sorted(p for p in root.iterdir() if p.suffix in {'.ipa','.zip'})
(root/'checksums.sha256').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in files))
print('\n'.join(p.name for p in files))
PY
