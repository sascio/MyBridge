# StreamBridge

A cinematic, addon- and plugin-driven media discovery and streaming
client for Android.

**StreamBridge is NuvioMobile core with StreamBridge branding.** The
runnable app is official [NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)
(`cmp-rewrite` `e377942`, 0.4.15), GPL-3.0, plus identity overlays
(name, applicationId, launcher mark). Kotlin packages stay
`com.nuvio.app`.

The app ships **completely empty** — no catalogs, no metadata, no
streams, no providers. You add Stremio-compatible addons and
Nuvio-compatible plugin repositories yourself. Optional TMDB, MDBList,
Trakt, and Simkl stay **unconfigured / off** unless you supply keys.

> StreamBridge provides no content of its own. Use only content you are
> legally entitled to access. The app does not bundle, host, or recommend
> any content source.

## Build

```bash
./gradlew :androidApp:assembleFullDebug
```

That is the Nuvio **full** phone APK (plugins + P2P wiring + vendored
decoder AARs). Output:

```
androidApp/build/outputs/apk/full/debug/
```

Release (debug-signed when no Nuvio release keystore is present):

```bash
./gradlew :androidApp:assembleFullRelease
```

Requirements: JDK 17+, Android SDK. Gradle wrapper 9.4.1.

The old single-module `:app` client is quarantined under
`legacy/streambridge-app/` and is **not** on the runtime or CI path.

## Getting the APK

1. Open the **Actions** tab
2. Open the latest successful **Build Stream Bridge** run
3. Download **`Stream-Bridge-full-debug-APK`** (or the release artifact)
4. Install on Android 8.0+ (enable “install unknown apps” if asked)

CI compiling an APK is **not** device playback proof.
**REAL DEVICE VALIDATION REQUIRED.**

## Layout

```
androidApp/     Nuvio Android host (applicationId com.streambridge.app)
composeApp/     Nuvio KMP app, including PlayerEngine.android.kt and libs/*.aar
iosApp/         Nuvio iOS project (version config is required by Gradle)
legacy/         Pre-M1 StreamBridge client (disconnected)
docs/           Audit, upstream pins, Milestone 1 report
```

## License

**GPL-3.0** — see [LICENSE](LICENSE), [COPYING](COPYING), and
[NOTICE](NOTICE). This is a GPL derivative of NuvioMobile, not an MIT
reimplementation.
