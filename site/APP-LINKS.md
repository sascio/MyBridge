# Android App Links: deployment blocked pending certificate verification

`assetlinks.json` deliberately has an empty fingerprint list. It does not verify
any app. Do not merge or deploy this phase until the certificate on the actual
published production APK has been independently verified and the list is filled
with its public SHA-256 fingerprint. `docs/SIGNING.md` is a candidate, not proof
of the certificate in the published binary. Include any additional certificates
only when they belong to an intentionally supported distribution channel.

After review and deployment, verify that
`https://streambridgeapp.pages.dev/.well-known/assetlinks.json` returns HTTP 200,
JSON and no redirects, then verify domain association and root-link routing on a
real Android device with the production APK. This is not proven by the files in
this repository. Cloudflare Pages serves `site/`; `_headers` requests JSON for
this path. The root-only filter leaves party paths on the website.

The iOS IPA is unsigned and its build clears signing entitlements. Universal
Links require a controlled host with an AASA file, the correct signed App ID
prefix and bundle identifier, an Associated Domains entitlement and a
provisioning/re-signing method that preserves and authorizes that entitlement.
These are intentionally outside this phase.

## Remaining verification and owner actions

- Android already declares an HTTPS root-only `autoVerify` filter and the shared parser maps that root to Home. Other website routes remain browser routes. On a trusted machine, download the actual intended release APK and run Android SDK `apksigner verify --print-certs <release.apk>`; compare its public SHA-256 with the intended signing certificate and publish only verified fingerprints. Never share keystores or passwords. Then check the deployed JSON without redirects and test domain verification and browser fallback on a device. The release asset could not be fetched in the current sandbox, so its certificate is not verified here.
- iOS SwiftUI forwards opened URLs to the shared parser, but this alone does not establish Universal Links. The owner must confirm an Apple Developer account, the signed App ID prefix (which is not always the Team ID), the `com.streambridge.app` App ID and an Associated Domains-capable provisioning profile. The currently documented unsigned IPA is re-signed by installers and the unsigned build explicitly clears entitlements. Do not claim a universal link will work for those installations. A controlled signed distribution needs the `applinks:streambridgeapp.pages.dev` entitlement and a matching AASA file with the verified App ID prefix and root-only rules, served as JSON without a redirect. Coordinate that signing change before editing the existing distribution flow; do not guess identifiers or publish a placeholder AASA.
- Test both platforms with the app installed and absent, on actual devices, including a supported root link and unsupported website routes. Browser or in-app-browser behavior, user preferences, and operating-system association caches can affect automatic opening. Website fallback is expected when no app claims a link, not a guarantee across all browser contexts.
