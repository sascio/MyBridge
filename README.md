# StreamBridge

**Your Media, Your Way**

A cinematic, addon- and plugin-driven media discovery and streaming
client for Android.

**StreamBridge is NuvioMobile core with StreamBridge branding and a
Nuvio-style in-app updater.** The runnable app is official
[NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)
(`cmp-rewrite` `d177eb57`, **0.4.17**), GPL-3.0. Kotlin packages stay
`com.nuvio.app`. `applicationId` is `com.streambridge.app`.

StreamBridge app version is **0.1.01** (see
`streambridge.version.properties`). That is not Nuvio’s 0.4.17 tag.

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

Output: `androidApp/build/outputs/apk/full/debug/`

Release (debug-signed when no upload keystore is present — see
[docs/SIGNING.md](docs/SIGNING.md)):

```bash
./gradlew :androidApp:assembleFullRelease
```

## Updates

In-app updates use **Nuvio’s updater UI**, pointed at
**GitHub Releases of `sascio/MyBridge`**, never NuvioMedia releases.
The checker is silent unless a real newer APK exists (no “no update”
or “cannot reach servers” popups). Settings → Check for updates uses
the same rule. Production in-place updates need a stable signing
certificate.

## Layout

```
androidApp/     Nuvio Android host (applicationId com.streambridge.app)
composeApp/     Nuvio KMP app, including PlayerEngine.android.kt
iosApp/         Nuvio iOS project (version config required by Gradle)
branding/       StreamBridge logo sources
legacy/         Pre-M1 StreamBridge client (disconnected)
docs/           Pins, signing, upstream sync
```

## License

**GPL-3.0** — see [LICENSE](LICENSE), [COPYING](COPYING), and
[NOTICE](NOTICE). Based on NuvioMobile by NuvioMedia. StreamBridge
includes modifications and additional functionality.
