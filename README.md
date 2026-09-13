<p align="center">
  <img src="branding/streambridge-mark-transparent.png" alt="StreamBridge" width="168">
</p>

<h1 align="center">StreamBridge</h1>

<p align="center"><strong>Your App, Your Way</strong></p>

<p align="center">
  Android media client · GPL-3.0 · version <strong>0.1.02</strong> (102)
</p>

StreamBridge is an Android media client. You bring catalogs, addons, and
plugins. The app does not ship, host, or recommend any content.

It is **NuvioMobile core with StreamBridge identity** (name, icon, package,
in-app updater). The runnable app is official
[NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)
(`cmp-rewrite` `d177eb57`, **0.4.17**), GPL-3.0, plus unique features
overlaid from [NuvioMobile-Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced).
Kotlin packages stay `com.nuvio.app`. `applicationId` is `com.streambridge.app`.

The app ships **empty**: no catalogs, no metadata, no streams, no
providers. Add Stremio-compatible addons and Nuvio-compatible plugin
repositories yourself. Optional TMDB, MDBList, Trakt, and Simkl stay
**off** unless you supply keys.

> StreamBridge provides no content of its own. Use only content you are
> legally entitled to access.

## Features

Implemented in this tree. Live TV providers and metadata services depend
on configuration you add. Avatar images need a working account backend.

**Home and discovery**
- Home catalogs from your addons, Continue Watching, and configurable hero styles (full-bleed or card)
- Search
- Title details, trailers, and play-random-episode for series

**Playback**
- Nuvio ExoPlayer pipeline (the only playback path)
- HLS quality chooser when a stream exposes HLS master variants
- Timeline tap-to-seek, picture-in-picture, background playback, and an optional external player

**Live TV**
- Channels, EPG, and Live TV settings when you configure sources
- Channel layout and navigation options
- Provider availability is **configuration-dependent** — nothing is bundled

**Library and tracking**
- Library, calendar, and lists
- Optional Trakt device-code sign-in and Simkl PIN/device sign-in (off by default)

**Profiles**
- Multiple profiles
- Avatar catalog loaded from the official Nuvio backend (public client configuration) with Ready, Empty, and Failed states plus retry; supporter avatars require a membership
- Custom profile background URL where the profile entitlement allows it

**Downloads**
- Download manager and a Wi-Fi-only (no mobile data) setting

**Addons, plugins, and settings**
- Empty addon and plugin lists until you add them
- Separate Plugin and Addon entries in Settings
- In-app Privacy & Policy page describing what the app stores and which services it can contact
- Licenses and attributions (StreamBridge as the app; NuvioMobile as upstream)

**Updates**
- In-app updates from published GitHub Releases of `sascio/MyBridge` only
- Drafts and prereleases are ignored
- The client selects the newest compatible stable APK for the device ABI

## Install

Production builds are the **published** GitHub Releases of this repository.
Each release attaches one APK per ABI:

- `androidApp-full-arm64-v8a-release.apk` (most phones)
- `androidApp-full-armeabi-v7a-release.apk`
- `androidApp-full-x86_64-release.apk`
- `androidApp-full-x86-release.apk`

Plus `checksums.sha256`. Branch CI APKs are **debug-signed compile
artifacts**, not production. In-place updates need the same production
certificate. See [docs/SIGNING.md](docs/SIGNING.md).

## Build

```bash
./gradlew :androidApp:assembleFullDebug
```

Debug output: `androidApp/build/outputs/apk/full/debug/`

```bash
./gradlew :androidApp:assembleFullRelease
```

Release output is **one APK per ABI** under
`androidApp/build/outputs/apk/full/release/`. A production GitHub Release
is a **manual** workflow and **fails** unless upload-keystore GitHub
Secrets are set.

## License

**GPL-3.0** — see [LICENSE](LICENSE), [COPYING](COPYING), and
[NOTICE](NOTICE). Based on NuvioMobile by NuvioMedia. StreamBridge
includes modifications and additional functionality.
