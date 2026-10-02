#!/usr/bin/env bash
#
# StreamBridge iOS release packaging.
#
# Adapted from NuvioMedia/NuvioMobile scripts/build-ios-ipa.sh (0.5.5-beta) for
# StreamBridge: the product is StreamBridge.app, the artifact is named after
# StreamBridge, and the reported version is STREAMBRIDGE_VERSION_NAME.
#
# Two signing modes:
#
#   unsigned (default)
#     Produces an unsigned StreamBridge IPA. This is what CI does when no Apple
#     signing material is available: it is a real, buildable, installable-after-
#     resigning artifact and is not claimed to be a signed release. The build
#     fails if the application unexpectedly comes out signed.
#
#   signed (STREAMBRIDGE_IOS_SIGNING=1)
#     Requires Apple credentials in the environment and fails when they are
#     missing. See the "Signing" section of docs/RELEASE-IOS.md.
#
# Environment:
#   IOS_CONFIGURATION             Debug | Release (default Release)
#   STREAMBRIDGE_IOS_SIGNING      "1" to require a signed build
#   STREAMBRIDGE_IOS_TEAM_ID      Apple Developer team identifier
#   STREAMBRIDGE_IOS_SIGN_IDENTITY  Codesign identity, e.g. "Apple Distribution"
#   STREAMBRIDGE_IOS_PROFILE_SPECIFIER
#                                 Named provisioning profile, or
#   STREAMBRIDGE_IOS_PROFILE_PATH Local .mobileprovision file (optional; a
#                                 temporary keychain is created from
#                                 STREAMBRIDGE_IOS_KEYCHAIN_PASSWORD when
#                                 STREAMBRIDGE_IOS_CERTIFICATE_BASE64 is set)
#   STREAMBRIDGE_IOS_EXPORT_METHOD app-store | ad-hoc | enterprise (default app-store)
#   STREAMBRIDGE_IOS_EXPORT_OPTIONS_PLIST
#                                 Full exportOptions.plist override
#   STREAMBRIDGE_IOS_KEYCHAIN_PASSWORD
#                                 Password for the temporary CI keychain
#   STREAMBRIDGE_IOS_CERTIFICATE_BASE64
#                                 Base64 .p12/.pfx distribution certificate
#   STREAMBRIDGE_IOS_CERTIFICATE_PASSWORD
#                                 Password of that certificate
#   IOS_DERIVED_DATA_PATH         DerivedData directory override
#   IOS_IPA_OUTPUT_DIR            Output directory (default build/ios-ipa)
#
# No credential value is ever printed.

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
version_file="${repository_root}/iosApp/Configuration/Version.xcconfig"
streambridge_properties="${repository_root}/streambridge.version.properties"

# StreamBridge's own version wins. NuvioMobile's MARKETING_VERSION stays in
# Version.xcconfig only so the Kotlin/Xcode plumbing keeps working; the value
# that reaches the IPA, the artifact name and the release tag is the
# StreamBridge one.
version="${1:-}"
if [[ -z "${version}" ]]; then
    version="$(sed -nE 's/^[[:space:]]*STREAMBRIDGE_VERSION_NAME[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' \
        "${streambridge_properties}" | head -n 1)"
fi
if [[ -z "${version}" ]]; then
    version="$(sed -nE 's/^[[:space:]]*MARKETING_VERSION[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' \
        "${version_file}" | head -n 1)"
fi

configuration="${IOS_CONFIGURATION:-Release}"
case "${configuration}" in
    Debug) configuration_slug="debug" ;;
    Release) configuration_slug="release" ;;
    *)
        echo "Unsupported iOS configuration: ${configuration}" >&2
        exit 1
        ;;
esac

derived_data="${IOS_DERIVED_DATA_PATH:-${repository_root}/build/ios-derived-full-${configuration_slug}}"
output_directory="${IOS_IPA_OUTPUT_DIR:-${repository_root}/build/ios-ipa}"
clang_module_cache="${CLANG_MODULE_CACHE_PATH:-${derived_data}/ModuleCache.noindex}"
swiftpm_module_cache="${SWIFTPM_MODULECACHE_OVERRIDE:-${derived_data}/SwiftPMModuleCache.noindex}"
required_signing="${STREAMBRIDGE_IOS_SIGNING:-0}"

if [[ ! "${version}" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
    echo "Invalid IPA version: ${version}" >&2
    exit 1
fi

team_id="${STREAMBRIDGE_IOS_TEAM_ID:-}"
sign_identity="${STREAMBRIDGE_IOS_SIGN_IDENTITY:-}"
profile_specifier="${STREAMBRIDGE_IOS_PROFILE_SPECIFIER:-}"
profile_path="${STREAMBRIDGE_IOS_PROFILE_PATH:-}"
export_method="${STREAMBRIDGE_IOS_EXPORT_METHOD:-app-store}"
export_options_plist="${STREAMBRIDGE_IOS_EXPORT_OPTIONS_PLIST:-}"
keychain_password="${STREAMBRIDGE_IOS_KEYCHAIN_PASSWORD:-}"
certificate_base64="${STREAMBRIDGE_IOS_CERTIFICATE_BASE64:-}"
certificate_password="${STREAMBRIDGE_IOS_CERTIFICATE_PASSWORD:-}"
temporary_keychain=""

cleanup() {
    if [[ -n "${temporary_keychain}" ]]; then
        security delete-keychain "${temporary_keychain}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${profile_copy:-}" && -f "${profile_copy}" ]]; then
        rm -f "${profile_copy}"
    fi
}
trap cleanup EXIT

if [[ "${required_signing}" == "1" ]]; then
    missing=()
    [[ -n "${team_id}" ]] || missing+=("STREAMBRIDGE_IOS_TEAM_ID")
    [[ -n "${sign_identity}" ]] || missing+=("STREAMBRIDGE_IOS_SIGN_IDENTITY")
    if [[ -z "${profile_specifier}" && -z "${profile_path}" ]]; then
        missing+=("STREAMBRIDGE_IOS_PROFILE_SPECIFIER or STREAMBRIDGE_IOS_PROFILE_PATH")
    fi
    if [[ -n "${certificate_base64}" && -z "${keychain_password}" ]]; then
        missing+=("STREAMBRIDGE_IOS_KEYCHAIN_PASSWORD")
    fi
    if [[ -n "${certificate_base64}" && -z "${certificate_password}" ]]; then
        missing+=("STREAMBRIDGE_IOS_CERTIFICATE_PASSWORD")
    fi
    if (( ${#missing[@]} > 0 )); then
        echo "::error::Signed StreamBridge iOS release requested but Apple signing is not configured. Missing: ${missing[*]}."
        echo "No IPA was produced. Configure the missing values as GitHub Actions secrets (see docs/RELEASE-IOS.md)." >&2
        exit 1
    fi
    if [[ -n "${certificate_base64}" ]]; then
        temporary_keychain="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/streambridge-ios-signing.keychain-db"
        rm -f "${temporary_keychain}"
        security create-keychain -p "${keychain_password}" "${temporary_keychain}"
        security set-keychain-settings -lut 900 "${temporary_keychain}"
        security unlock-keychain -p "${keychain_password}" "${temporary_keychain}"
        certificate_path="$(mktemp "${TMPDIR:-/tmp}/streambridge-certificate.XXXXXX.p12")"
        trap 'rm -f "${certificate_path}"; cleanup' EXIT
        printf '%s' "${certificate_base64}" | base64 --decode > "${certificate_path}"
        security import "${certificate_path}" -k "${temporary_keychain}" -P "${certificate_password}" \
            -T /usr/bin/codesign -T /usr/bin/security >/dev/null
        rm -f "${certificate_path}"
        security set-key-partition-list -S apple-tool:,apple: -k "${keychain_password}" \
            "${temporary_keychain}" >/dev/null
        security list-keychains -d user -s "${temporary_keychain}" $(security list-keychains -d user | tr -d '"')
    fi
    if [[ -n "${profile_path}" ]]; then
        if [[ ! -f "${profile_path}" ]]; then
            echo "::error::STREAMBRIDGE_IOS_PROFILE_PATH does not point to a file." >&2
            exit 1
        fi
        profiles_directory="${HOME}/Library/MobileDevice/Provisioning Profiles"
        mkdir -p "${profiles_directory}"
        profile_copy="${profiles_directory}/streambridge-release.mobileprovision"
        cp "${profile_path}" "${profile_copy}"
    fi
    if [[ -n "${certificate_base64}" ]]; then
        # The default keychain was replaced above; make sure xcodebuild sees it.
        export HOME="${HOME}"
    fi
    export STREAMBRIDGE_IOS_SIGNING=1
fi

cd "${repository_root}"
build_environment=(
    env
    NUVIO_IOS_DISTRIBUTION=full
    CLANG_MODULE_CACHE_PATH="${clang_module_cache}"
    SWIFTPM_MODULECACHE_OVERRIDE="${swiftpm_module_cache}"
)
if [[ -n "${NUVIO_GRADLE_JVMARGS:-}" ]]; then
    build_environment+=("ORG_GRADLE_PROJECT_org.gradle.jvmargs=${NUVIO_GRADLE_JVMARGS}")
fi
if [[ -n "${NUVIO_KOTLIN_NATIVE_JVMARGS:-}" ]]; then
    build_environment+=("ORG_GRADLE_PROJECT_kotlin.native.jvmArgs=${NUVIO_KOTLIN_NATIVE_JVMARGS}")
fi

signing_arguments=()
archive_path=""
if [[ "${required_signing}" == "1" ]]; then
    # A signed IPA must come from an archive: only an archive carries the
    # provisioning profile and certificate through to a distributable .ipa.
    archive_path="${derived_data}/StreamBridge-${configuration}.xcarchive"
    profile_argument=()
    if [[ -n "${profile_specifier}" ]]; then
        profile_argument+=(PROVISIONING_PROFILE_SPECIFIER="${profile_specifier}")
    fi
    signing_arguments+=(
        -allowProvisioningUpdates
        -archivePath "${archive_path}"
        DEVELOPMENT_TEAM="${team_id}"
        CODE_SIGN_STYLE=Manual
        CODE_SIGN_IDENTITY="${sign_identity}"
        ${profile_argument[@]+"${profile_argument[@]}"}
    )
    "${build_environment[@]}" \
        xcodebuild \
        -project iosApp/iosApp.xcodeproj \
        -scheme iosApp \
        -configuration "${configuration}" \
        -sdk iphoneos \
        -destination 'generic/platform=iOS' \
        -derivedDataPath "${derived_data}" \
        "${signing_arguments[@]}" \
        archive

    if [[ ! -d "${archive_path}" ]]; then
        echo "::error::iOS archive was not produced at ${archive_path}." >&2
        exit 1
    fi

    if [[ -z "${export_options_plist}" ]]; then
        export_options_plist="${derived_data}/ExportOptions.plist"
        team_argument=""
        if [[ -n "${team_id}" ]]; then
            team_argument="<key>teamID</key><string>${team_id}</string>"
        fi
        cat > "${export_options_plist}" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>${export_method}</string>
    ${team_argument}
    <key>signingStyle</key>
    <string>manual</string>
    <key>destination</key>
    <string>export</string>
    <key>stripSwiftSymbols</key>
    <true/>
</dict>
</plist>
PLIST
    fi

    export_directory="${derived_data}/export-${configuration_slug}"
    rm -rf "${export_directory}"
    "${build_environment[@]}" \
        xcodebuild \
        -exportArchive \
        -archivePath "${archive_path}" \
        -exportPath "${export_directory}" \
        -exportOptionsPlist "${export_options_plist}" \
        -allowProvisioningUpdates

    exported_ipa="$(find "${export_directory}" -maxdepth 1 -type f -name '*.ipa' -print -quit)"
    if [[ -z "${exported_ipa}" ]]; then
        echo "::error::Signed export produced no IPA in ${export_directory}." >&2
        exit 1
    fi
    app_path="$(mktemp -d "${TMPDIR:-/tmp}/streambridge-ipa-check.XXXXXX")"
    trap 'rm -rf "${app_path}"; cleanup' EXIT
    unzip -q "${exported_ipa}" -d "${app_path}"
    app_path="${app_path}/Payload/StreamBridge.app"
else
    signing_arguments=(
        CODE_SIGNING_ALLOWED=NO
        CODE_SIGNING_REQUIRED=NO
        CODE_SIGN_IDENTITY=
        CODE_SIGN_ENTITLEMENTS=
    )
    "${build_environment[@]}" \
        xcodebuild \
        -project iosApp/iosApp.xcodeproj \
        -scheme iosApp \
        -configuration "${configuration}" \
        -sdk iphoneos \
        -destination 'generic/platform=iOS' \
        -derivedDataPath "${derived_data}" \
        "${signing_arguments[@]}" \
        build

    app_path="${derived_data}/Build/Products/${configuration}-iphoneos/StreamBridge.app"
fi

if [[ ! -d "${app_path}" ]]; then
    echo "iOS build did not produce ${app_path}." >&2
    exit 1
fi

built_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${app_path}/Info.plist")"
if [[ "${built_version}" != "${version}" ]]; then
    echo "Built iOS version ${built_version} does not match ${version}." >&2
    exit 1
fi

built_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleName' "${app_path}/Info.plist")"
if [[ "${built_name}" != "StreamBridge" ]]; then
    echo "Built iOS application is named ${built_name}, expected StreamBridge." >&2
    exit 1
fi

built_bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${app_path}/Info.plist")"
if [[ "${built_bundle_id}" != com.streambridge.app* ]]; then
    echo "Built iOS application bundle identifier is ${built_bundle_id}, expected com.streambridge.app*." >&2
    exit 1
fi

executable="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${app_path}/Info.plist")"
architectures="$(xcrun lipo -archs "${app_path}/${executable}")"
if [[ " ${architectures} " != *" arm64 "* ]]; then
    echo "Built iOS application does not contain arm64." >&2
    exit 1
fi

if ! launch_screen_plist="$(plutil -extract UILaunchScreen xml1 -o - "${app_path}/Info.plist" 2>/dev/null)"; then
    echo "Built iOS application does not contain UILaunchScreen." >&2
    exit 1
fi
if [[ "${launch_screen_plist}" == *"<key>UILaunchScreen</key>"* ]]; then
    echo "Built iOS application contains a nested UILaunchScreen." >&2
    exit 1
fi

if [[ "${required_signing}" != "1" && -d "${app_path}/_CodeSignature" ]]; then
    echo "Built iOS application is unexpectedly signed." >&2
    exit 1
fi

widget_path="${app_path}/PlugIns/DownloadsWidgetExtension.appex"
if [[ ! -d "${widget_path}" ]]; then
    echo "Built iOS application does not contain the downloads widget." >&2
    exit 1
fi
widget_executable="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${widget_path}/Info.plist")"
widget_architectures="$(xcrun lipo -archs "${widget_path}/${widget_executable}")"
if [[ " ${widget_architectures} " != *" arm64 "* ]]; then
    echo "Built downloads widget does not contain arm64." >&2
    exit 1
fi

mkdir -p "${output_directory}"
output_directory="$(cd "${output_directory}" && pwd -P)"

if [[ "${required_signing}" == "1" ]]; then
    ipa_path="${output_directory}/streambridge-${version}-full-${configuration_slug}.ipa"
    cp "${exported_ipa}" "${ipa_path}"
else
    package_root="$(mktemp -d "${TMPDIR:-/tmp}/streambridge-ios-ipa.XXXXXX")"
    trap 'rm -rf "${package_root}"; cleanup' EXIT
    mkdir -p "${package_root}/Payload"
    ditto "${app_path}" "${package_root}/Payload/StreamBridge.app"

    ipa_path="${output_directory}/streambridge-${version}-full-${configuration_slug}.ipa"
    temporary_ipa="${package_root}/streambridge-${version}-full-${configuration_slug}.ipa"
    (
        cd "${package_root}"
        /usr/bin/zip -qry "${temporary_ipa}" Payload
    )
    unzip -tq "${temporary_ipa}"
    mv "${temporary_ipa}" "${ipa_path}"
fi

echo "Created ${ipa_path}"
if [[ "${required_signing}" == "1" ]]; then
    echo "Signing: signed (${sign_identity})"
else
    echo "Signing: unsigned (no Apple signing material was provided)"
fi
