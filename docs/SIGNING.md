# StreamBridge Android production signing

An Android in-place update requires the original `com.streambridge.app` package
and the same signing certificate. Rebranding, a higher version code or a CI
release build does not bypass Android signature checks.

The established StreamBridge production certificate SHA-256 is:

```text
5DA621D8E6F5C4FFB7396DE3FBF686F3BF61BCEC71CCCFFBA9BBBBDEF56285FC
```

This is a **public certificate fingerprint**, not a private key. The task does
not generate or replace the production upload key. `.github/package_android.py`
checks the actual APK certificate and rejects debug certificates in production
mode. The Gradle production guard refuses a release without real configuration.

## GitHub Secrets

The existing technical names remain compatible with deployed signing setup:

| Secret | Meaning |
| --- | --- |
| `NUVIO_RELEASE_STORE_BASE64` | Base64 established upload keystore |
| `NUVIO_RELEASE_STORE_PASSWORD` | Keystore password |
| `NUVIO_RELEASE_KEY_ALIAS` | Existing upload-key alias |
| `NUVIO_RELEASE_KEY_PASSWORD` | Private-key password |
| `NUVIO_LOCAL_PROPERTIES_BASE64` | Optional runtime API/OAuth properties, not a signing requirement |

The signed release workflow decodes the keystore into a mode-600 file under
`RUNNER_TEMP`, passes its path through `NUVIO_RELEASE_STORE_FILE`, enables
`STREAMBRIDGE_REQUIRE_PRODUCTION_SIGNING=true`, builds minified outputs and
verifies the known certificate before uploading production artifacts. It deletes
the private input even on failure and does not write secret-bearing Gradle state
back to the shared release cache.

Do not commit, paste or print keystores, certificates/provisioning profiles,
private keys, passwords or API secrets. Secret metadata/dispatch access is
restricted in the current Arena GitHub connection; availability is unknown.
Reconnect in Arena for an authorized signing run; do not send credentials in chat.

## Local production build

Supply the four `NUVIO_RELEASE_*` environment values and the real keystore path
through `NUVIO_RELEASE_STORE_FILE`, or supported ignored local properties. Then:

```sh
export STREAMBRIDGE_REQUIRE_PRODUCTION_SIGNING=true
./gradlew :androidApp:assembleFullRelease
python3 .github/package_android.py --production
```

Do not put literal passwords in tracked scripts/commands. See [RELEASING.md](RELEASING.md)
for separate AAB builds and strict checksums. The job defaults to artifacts-only
`test-build`; creating a draft does not publish it.

Without production configuration, local/automatic CI release APKs retain the
non-production fallback for developer validation. Their names explicitly include
`-not-production`. A universal debug APK is not a production update, and removing
the guard or signing with a fresh key will not make it compatible with installed
StreamBridge releases.

Apple signing is separate: see [IOS.md](IOS.md) for distribution P12/team/app and
widget profiles, temporary keychain cleanup and signed versus unsigned exports.
