# Release signing (in-app updates)

Android will only install an in-app update over an existing StreamBridge
install if the **signing certificate matches**.

`applicationId` is `com.streambridge.app`.

## Current CI behavior

GitHub Actions `assembleFullRelease` uses the **debug keystore** when
`NUVIO_RELEASE_STORE_FILE` / `NUVIO_RELEASE_*` are unset. That APK
installs as a debug-signed app. Updates from later CI artifacts work
only against that same debug cert.

That is **not** a production signing story.

## Production requirement

To support real in-place updates from GitHub Releases:

1. Create a StreamBridge upload keystore and keep it offline / in GitHub
   Secrets (`NUVIO_RELEASE_STORE_FILE` path + passwords, or equivalent
   StreamBridge secrets wired the same way Nuvio’s Gradle already reads).
2. Sign every GitHub Release APK with that keystore.
3. Never rotate the cert without a migration plan (users would have to
   uninstall).
4. Do not mix debug-signed CI APKs and production-signed Release APKs
   on the same device as “updates”.

Until a dedicated StreamBridge upload key is configured, treat in-app
update as **PARTIAL**: the Nuvio updater UI and GitHub Releases wiring
are present; certificate-compatible installs are **not** guaranteed.
