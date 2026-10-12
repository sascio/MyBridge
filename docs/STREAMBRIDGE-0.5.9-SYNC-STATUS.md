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
