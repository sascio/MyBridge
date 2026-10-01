# Building and releasing StreamBridge

## Canonical metadata

`streambridge.version.properties` owns the release version, build number, Apple
marketing version and exact official/Enhanced source pins. Current values:

- Android/shared/release version: `0.5.5-beta`
- Android `versionCode` and Apple build: `108`
- Apple numeric marketing version: `0.5.5`
- Release tag: `v0.5.5-beta` (StreamBridge beta/prerelease policy)

```sh
python3 .github/streambridge_release.py
python3 .github/streambridge_release.py --write-xcconfig
python3 .github/streambridge_release.py --tag v0.5.5-beta
```

The helper fails on missing/noncanonical metadata, stale Xcode configuration,
unknown pins and tag/version mismatch. Never copy upstream build numbers or
provide a silent Nuvio version fallback. Apple does not permit `-beta` in
`CFBundleShortVersionString`; the full beta label is retained separately.

## Automatic validation, not production publication

`.github/workflows/build.yml` runs on source pushes/PRs and manual dispatch:

1. Exact source/clean ancestry, no-CloudStream, StreamBridge identity, license
   retention, metadata/unit tests and checksum-verified actionlint guards.
2. Android debug Kotlin, release dependency resolution/insight, shared/Android
   host tests, universal debug, four minified release APKs and a separate AAB.
3. macOS/Xcode package resolution and a full **unsigned** device Release archive
   plus packaged IPA validation when the actual archive succeeds.

Bash `pipefail` is mandatory: a `tee`/report upload must not turn a failed compiler
into a green build. Unit failures remain a hard gate after preserving diagnostics.
Reports and app packages have separate artifact names. Doc-only pushes do not
repeat expensive application builds. A validation artifact is not a release.

## Android APKs and AABs

The target AGP 9.2 shrinker cannot consume split-APK and unsplit-AAB resource
layouts simultaneously. APK splits also apply to every variant requested in an
invocation. Therefore build debug, split release and bundle separately.

```sh
# Debug is universal, not split by a concurrent release-APK task.
./gradlew :androidApp:assembleFullDebug

# Four ABI APKs. In production, supply the established key and strict signing.
./gradlew :androidApp:assembleFullRelease
python3 .github/package_android.py --debug

# Save APK mapping before generated app outputs are cleaned.
mkdir -p build/reports/android-apk-mapping
cp -R androidApp/build/outputs/mapping/fullRelease/. build/reports/android-apk-mapping/

# The APKs above are already staged in ignored build/artifacts/android/.
# Clean application outputs to avoid old per-ABI shrunk-resource protos.
./gradlew :androidApp:clean :androidApp:bundleFullRelease
python3 .github/package_android.py --aab-only
```

For production use `--production` in both packaging commands and omit `--debug`.
The helper validates application ID, label, version/build, exact native ABI,
absence of excluded runtime DEX markers, APK signature and the established
production certificate. It requires all four ABI outputs. `--aab-only` checks
previous APK hashes before appending a separately built signed AAB. AABs are for
bundle-aware tooling/store distribution; users do not install an AAB directly.

Production asset names:

```text
StreamBridge-0.5.5-beta-arm64-v8a.apk
StreamBridge-0.5.5-beta-armeabi-v7a.apk
StreamBridge-0.5.5-beta-x86.apk
StreamBridge-0.5.5-beta-x86_64.apk
StreamBridge-0.5.5-beta-full.aab
checksums.sha256
```

A universal debug APK is supported. A universal production APK is not promised
by the default ABI-split configuration. Do not disable R8 or change signing just
to work around the AGP resource-layout error.

## iOS

See [IOS.md](IOS.md). The exact MPVKit submodule and hash-verified engine archive
are bootstrap inputs; they are not downloaded from a sibling checkout. An
unsigned validation archive is distinct from a signed export. The app, widget
and exported IPA must all have matching StreamBridge identities and versions.
The archive helper must build the shared Kotlin framework, not use an IDE skip
flag or stale framework from another generation.

## Signed build and draft workflow

`.github/workflows/release-draft.yml` is manual (or triggered by an exact `v*` tag)
and never automatically publishes a release. Select `test-build` first:

```sh
gh workflow run release-draft.yml --ref arena/01a0f91c-mybridge \
  -f mode=test-build -f target=both -f tag=v0.5.5-beta \
  -f ios_export_method=release-testing
```

This requires GitHub Actions dispatch permissions. A 403 is an integration/access
block, not evidence that Apple secrets are missing. Reconnect the GitHub
integration with the required access rather than posting credentials in chat.
The task's current connection rejected dispatch; actual signed build/export
results must not be inferred from configuration or helper tests.

Targets are `both`, `android`, or `ios`. Each selected platform must produce a
complete validated production asset set. Android uses the existing upload key
and public certificate fingerprint, never a new debug key. iOS has a separate
credential-validation stage with exact profiles/team/distribution identity.
Missing Apple credentials fail there clearly; unsigned output never enters a
signed release draft. Optional API/OAuth settings do not become signing keys.

Use `mode=draft` only after the platform build and device checks are satisfactory.
The coordinator verifies each artifact's original checksum, rejects upstream,
debug, unsigned, incomplete or wrong-version sets, creates one combined manifest
and attaches the selected Android/iOS assets to a **draft** for the exact source
commit. An existing tag must point at that commit. Already published releases
are not overwritten or silently converted back to drafts.

Publication remains an explicit maintainer action. `0.5.5-beta` is marked
prerelease for StreamBridge even though both upstream APIs mark their beta-named
releases as non-prereleases. Store/TestFlight uploads are not automated here.
Before publishing, retain GPL corresponding-source access and the dependency
notices, finish lawful-source/device regression checks and review APK/IPA sizes.
