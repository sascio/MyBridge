# StreamBridge — 0.5.9-beta sync status (partial)

## Verified upstream revisions
- NuvioMedia/NuvioMobile tag `0.5.9-beta` → `db0c50b503039863909c86405907462bf1752193`
  (current pin in streambridge.version.properties: `0.5.5-beta` / `5c6028f2`).
- luqmanfadlli/NuvioMobile-Enhanced tag `0.5.9-beta` → `a0b585578b6dda0d99459b11d3583f56dd543f74`
  (current pin: `0.5.5-beta` / `8b3bd867`).
- Measured delta, official 0.5.5→0.5.9: 222 files, +15009/−633.
- Measured delta, enhanced 0.5.5→0.5.9: 298 files, +25276/−1003.

## Done in this change
- Footer: `Made with ❤️ by Saif` (single source, values/strings.xml).
- Motto: `Your Media, Your Way` (strings.xml, README, milestone doc).
- Updater: user-facing update title is now `StreamBridge <tag>` instead of the
  GitHub release name; debug preview renamed to StreamBridge.
- Splash: `ic_splash_logo*` assets rescaled onto a 512px transparent canvas so the
  logo sits inside Android's 2/3 splash safe zone (originals in git history).

## Not done (remaining work)
- Full upstream 0.5.9 sync (both repos) — not performed; requires a dedicated,
  reviewed port.
- Tracking Connect visibility, profile genre/aggregation fixes, GIF avatar search,
  iOS verification — not performed in this change.

## Verification status
- Gradle/Kotlin compilation, unit tests, debug and release builds: NOT RUN.
  The sandbox has no JDK, no Android SDK, and Maven Central is not reachable.
- Splash crop fix: asset geometry verified with Pillow only; not verified on a device.
- Application identity / in-place update on device: NOT VERIFIED.

## Continuation audit (tracking + profile), no functional code change

### Trakt / Simkl / MDBList configuration path (traced)
- Trakt: `TraktConfig.CLIENT_ID` and `CLIENT_SECRET` (both required), read from `local.properties` only.
  Gate: `TraktAuthRepository.hasRequiredCredentials()`.
- Simkl: `SimklConfig.CLIENT_ID` from `local.properties` only. Gate: `SimklAuthRepository.hasRequiredCredentials()`.
- MDBList: `MdbListConfig.CLIENT_ID` (device-flow public client, no secret). Gate: `MdbListAuthRepository.hasRequiredCredentials()`.
  MDBList ratings also accept a user-entered API key (`MdbListApiKeyRow`), independent of build config.
- Connect UI is NOT hidden. `TrackingProviderCards` renders the Connect button disabled with
  `missingCredentialsMessage` when `credentialsConfigured` is false. An apparently missing Connect
  therefore means the build was produced without these keys.
- Root cause in CI: release-draft.yml supplies keys through the `NUVIO_LOCAL_PROPERTIES_BASE64` secret.
  If that secret lacks `TRAKT_*`, `SIMKL_*`, or `MDBLIST_CLIENT_ID`, the build is unconfigured.
  Individually set GitHub secrets for TRAKT_/SIMKL_ are intentionally not read (documented design).
  Changing that would be a policy change, so it was not made.
- Not verified: the authenticated OAuth round-trip (requires real accounts and a device).

### Profile genre error (`profile_insights_genre_failures`)
- Source: `ProfileInsightsSettingsPage.kt` `fetchMissingFacts` → `MetaDetailsRepository.fetchLightweight`.
- A title counts as a genre failure only when its lookup returns `MetaLookupOutcome.Failed` or throws,
  and its genres remain empty. `Unavailable` and `Throttled` are retried, not counted.
- Failures retry after 3 days (`FAILURE_RETRY_AFTER_MS`); budget 150 fetches per window; concurrency 4.
- Identifying the two failing titles and their causes requires runtime logs (`profileInsightsLog`) on a
  device with the user's addons. Not reproduced here.
- Gap: `Unavailable` titles have no user-visible indication and no explicit retry control.
