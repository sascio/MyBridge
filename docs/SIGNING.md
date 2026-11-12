# StreamBridge Android signing

Production updates require **one stable upload certificate**. Mixing the
Android Debug certificate with a later production keystore breaks in-place
updates (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Users must uninstall first.

This repository never contains a keystore, passwords, or private keys.

## What CI does vs production

| Path | Workflow | Signing | Published? |
| --- | --- | --- | --- |
| Branch / PR compile | `.github/workflows/build.yml` | Debug fallback. **Not production.** | Actions artifacts only. Names include `not-production`. |
| Production draft (Android) | `.github/workflows/release-draft.yml` (`workflow_dispatch` or a `v*` tag) | Upload keystore from GitHub Secrets. **Fails if secrets are missing.** | Draft GitHub Release only. Never auto-published. |
| Production draft (iOS) | same workflow, `ios` job | **None.** Always unsigned; the job fails if the application comes out signed. | Unsigned IPA attached to the draft; AltStore/SideStore re-sign it with the installer's own Apple ID. No Apple credentials are involved. See [RELEASE-IOS.md](RELEASE-IOS.md). |

`assembleFullRelease` without `NUVIO_RELEASE_*` still compiles and
debug-signs so CI stays a compile check. That APK must not be treated as
a StreamBridge release.

Set `STREAMBRIDGE_REQUIRE_PRODUCTION_SIGNING=true` to **fail the Gradle
build** instead of falling back to debug. The production draft workflow
always sets this.

## GitHub Secrets (production only)

Use the existing production keystore and release secrets. Do not generate or
replace a key as part of website or App Links setup. Never commit the keystore,
passwords or private keys. On a trusted machine, a release maintainer can compare
the public certificate from the existing keystore with the certificate extracted
from the actual published APK; never share the keystore or passwords.

Repo secrets:

| Secret | Value |
| --- | --- |
| `NUVIO_RELEASE_STORE_BASE64` | Base64 of the `.jks` |
| `NUVIO_RELEASE_STORE_PASSWORD` | Store password |
| `NUVIO_RELEASE_KEY_ALIAS` | Key alias (example: `streambridge`) |
| `NUVIO_RELEASE_KEY_PASSWORD` | Key password |

Gradle also accepts the same names (except `STORE_BASE64`) from
`local.properties` or the environment, plus `NUVIO_RELEASE_STORE_FILE`
as a filesystem path. `local.properties` is gitignored.

Public production upload certificate SHA-256 (never a password):

```
Production cert SHA-256: 5DA621D8E6F5C4FFB7396DE3FBF686F3BF61BCEC71CCCFFBA9BBBBDEF56285FC
```

## Operator checklist

1. Secrets above are set on `sascio/MyBridge`.
2. `streambridge.version.properties` is the version you intend to ship
   (do not bump only to exercise the updater).
3. Run **Draft production GitHub Release** with tag `v<STREAMBRIDGE_VERSION_NAME>`
   (for this release, `v0.1.08`).
4. Workflow fails closed without secrets, if the tag does not match, if
   the APK is debug-signed, or if `apksigner` cannot verify.
5. Inspect the **draft**. Device-validate install + playback before
   publishing. Publishing is a human action, not CI.
6. Keep the same upload keystore for every later tag. In-app updates
   only install over the same signing certificate.
7. Attach **all** per-ABI APKs from `full/release/` (arm64-v8a,
   armeabi-v7a, x86, x86_64). Do not ship a universal fat APK. The
   updater matches `arm64-v8a` (etc.) in the asset name.
8. iOS: the workflow attaches an **unsigned** `StreamBridge-<version>-iOS-unsigned.ipa`.
   It is intended for re-signing and installation with AltStore or SideStore,
   not direct installation as a signed App Store app. See [RELEASE-IOS.md](RELEASE-IOS.md).

## Updater

The app reads `https://api.github.com/repos/sascio/MyBridge/releases`.
Drafts and prereleases are ignored. APK assets must be `https://`.
When several APKs are attached, the client prefers the filename that
contains the device ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`).
Debug-signed CI APKs and production-signed Release APKs are **not**
interchangeable updates.

## Rotation

If the upload key is lost, in-place updates from that identity are
impossible. Ship a new package only with a migration plan (uninstall
or a new `applicationId`). Do not commit a replacement keystore.
