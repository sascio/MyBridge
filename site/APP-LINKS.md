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
