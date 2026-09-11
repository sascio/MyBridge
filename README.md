# StreamBridge

**Your App, Your Way**

StreamBridge is an Android media client. You bring the catalogs, addons,
and plugins. The app does not ship any content.

It is **NuvioMobile core with StreamBridge identity** (name, icon, package,
in-app updater). The runnable app is official
[NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)
(`cmp-rewrite` `d177eb57`, **0.4.17**), GPL-3.0, plus unique features
overlaid from [NuvioMobile-Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced).
Kotlin packages stay `com.nuvio.app`. `applicationId` is `com.streambridge.app`.

StreamBridge app version is **0.1.01** (versionCode 101). That is not
Nuvio’s 0.4.17 tag. See `streambridge.version.properties`.

The app ships **empty**: no catalogs, no metadata, no streams, no
providers. Add Stremio-compatible addons and Nuvio-compatible plugin
repositories yourself. Optional TMDB, MDBList, Trakt, and Simkl stay
**off** unless you supply keys.

> StreamBridge provides no content of its own. Use only content you are
> legally entitled to access. The app does not bundle, host, or recommend
> any content source.

## Build

```bash
./gradlew :androidApp:assembleFullDebug
```

Output: `androidApp/build/outputs/apk/full/debug/`

Branch CI APKs are **debug-signed compile artifacts**, not production.
A production GitHub Release is a **manual draft** and **fails** unless
upload-keystore GitHub Secrets are set. See
[docs/SIGNING.md](docs/SIGNING.md).

```bash
./gradlew :androidApp:assembleFullRelease
```

## Updates

In-app updates use Nuvio’s updater UI, pointed at **GitHub Releases of
`sascio/MyBridge` only** (never NuvioMedia). Drafts and prereleases are
ignored. The checker stays silent unless a real newer compatible APK
exists. Settings → Check for updates says you are up to date when
nothing newer is published. In-place installs need a stable signing
certificate ([docs/SIGNING.md](docs/SIGNING.md)).

## Identity

Splash, intro, auth, profile, and Settings credits use a horizontal
lockup: transparent StreamBridge mark + **StreamBridge**, with
**Your App, Your Way** under the splash. Required NuvioMobile / GPL
attribution stays in Settings credits and the Licenses page.

Launcher / adaptive icons keep the full rounded mark. In-app surfaces
use the transparent glyph (`branding/streambridge-mark-transparent.png`).

## Layout

```
androidApp/     Android host (applicationId com.streambridge.app)
composeApp/     Nuvio KMP app, including PlayerEngine.android.kt
iosApp/         iOS project (version config required by Gradle)
branding/       StreamBridge logo sources
legacy/         Pre-M1 StreamBridge client (disconnected)
docs/           Pins, signing, upstream sync
```

## License

**GPL-3.0** — see [LICENSE](LICENSE), [COPYING](COPYING), and
[NOTICE](NOTICE). Based on NuvioMobile by NuvioMedia. StreamBridge
includes modifications and additional functionality.
