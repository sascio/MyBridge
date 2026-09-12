# StreamBridge Android signing

Production updates require **one stable upload certificate**. Mixing the
Android Debug certificate with a later production keystore breaks in-place
updates (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Users must uninstall first.

This repository never contains a keystore, passwords, or private keys.

## What CI does vs production

| Path | Workflow | Signing | Published? |
| --- | --- | --- | --- |
| Branch / PR compile | `.github/workflows/build.yml` | Debug fallback. **Not production.** | Actions artifacts only. Names include `not-production`. |
| Production draft | `.github/workflows/release-draft.yml` (`workflow_dispatch` only) | Upload keystore from GitHub Secrets. **Fails if secrets are missing.** | Draft GitHub Release only. Never auto-published. |

`assembleFullRelease` without `NUVIO_RELEASE_*` still compiles and
debug-signs so CI stays a compile check. That APK must not be treated as
a StreamBridge release.

Set `STREAMBRIDGE_REQUIRE_PRODUCTION_SIGNING=true` to **fail the Gradle
build** instead of falling back to debug. The production draft workflow
always sets this.

## GitHub Secrets (production only)

Create an upload keystore **on a trusted machine**. Do not invent a
test keystore in git. Do not commit `*.jks` / `*.keystore`.

```bash
keytool -genkeypair -v \
  -keystore streambridge-upload.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias streambridge \
  -storepass '<store-password>' \
  -keypass '<key-password>' \
  -dname 'CN=StreamBridge, O=Stream Bridge, C=IN'
```

Record the **public** cert SHA-256 (this is not a secret):

```bash
keytool -list -v -keystore streambridge-upload.jks -alias streambridge
```

Base64 the store file (do not log the output in CI transcripts you share):

```bash
base64 -w 0 streambridge-upload.jks
```

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

After a real production APK exists, paste the **certificate SHA-256**
here (never the password):

```
Production cert SHA-256: (not issued — secrets are not configured)
```

## Operator checklist

1. Secrets above are set on `sascio/MyBridge`.
2. `streambridge.version.properties` is the version you intend to ship
   (do not bump only to exercise the updater).
3. Run **Draft production GitHub Release** with tag = `STREAMBRIDGE_VERSION_NAME`
   (currently `0.1.01`).
4. Workflow fails closed without secrets, if the tag does not match, if
   the APK is debug-signed, or if `apksigner` cannot verify.
5. Inspect the **draft**. Device-validate install + playback before
   publishing. Publishing is a human action, not CI.
6. Keep the same upload keystore for every later tag. In-app updates
   only install over the same signing certificate.

## Updater

The app reads `https://api.github.com/repos/sascio/MyBridge/releases`.
Drafts and prereleases are ignored. APK assets must be `https://`.
Debug-signed CI APKs and production-signed Release APKs are **not**
interchangeable updates.

## Rotation

If the upload key is lost, in-place updates from that identity are
impossible. Ship a new package only with a migration plan (uninstall
or a new `applicationId`). Do not commit a replacement keystore.
