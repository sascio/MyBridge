<p align="center">
  <img src="branding/streambridge-mark-transparent.png" alt="StreamBridge" width="168">
</p>

<h1 align="center">StreamBridge</h1>

<p align="center"><strong>Your Media, Your Way</strong></p>

<p align="center">
  Android media client · iOS in 0.1.08+ · GPL-3.0 · version <strong>0.1.10</strong> (110)
</p>

StreamBridge is an independent, GPL-3.0 media client for Android, with an iOS
release starting in 0.1.08. It is developed and published by the StreamBridge
project at [sascio/MyBridge](https://github.com/sascio/MyBridge). You bring
catalogs, addons, and plugins. The app does not ship, host, or recommend any
content.

StreamBridge is built on the open-source NuvioMobile codebase and adds its own
identity (name, icon, package, iOS bundle identifier, in-app updater), release
engineering, and features. The current tree is synced to
[NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) `cmp-rewrite`
`79b750f3` (**0.6.1-beta**) and
[NuvioMobile-Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced)
`enhanced` `504014d3` (**0.6.1-beta**), both GPL-3.0. Upstream authors and
copyright holders are credited in [NOTICE](NOTICE); StreamBridge is not an
official NuvioMobile release and is not endorsed by NuvioMedia.

Kotlin packages stay `com.nuvio.app`. `applicationId` is `com.streambridge.app`;
the iOS bundle identifier is `com.streambridge.app`. The repository name
`MyBridge` is historical; the product is StreamBridge.

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
- Title details, trailers, and episode shuffle for series

**Playback**
- Nuvio ExoPlayer pipeline (the only Android playback path)
- HLS quality chooser when a stream exposes HLS master variants
- Timeline tap-to-seek, picture-in-picture, background playback, and an optional external player
- Subtitle controls including per-episode restore, styling, and "sync by ear" alignment

**Live TV**
- Channels, EPG, and Live TV settings when you configure sources
- Channel layout and navigation options
- Provider availability is **configuration-dependent** — nothing is bundled

**Library and tracking**
- Library, calendar, lists, and the downloads entry point
- In-app ratings synced with Trakt, Simkl, and MDBList
- Optional Trakt device-code sign-in and Simkl PIN/device sign-in (off by default)

**Profiles**
- Multiple profiles
- Avatar catalog loaded from the official Nuvio backend (public client configuration) with Ready, Empty, and Failed states plus retry; supporter avatars require a membership
- Custom profile background URL where the profile entitlement allows it
- Profile Insights computed off the main thread and cached across launches

**Downloads**
- Download manager and a Wi-Fi-only (no mobile data) setting

**Addons, plugins, and settings**
- Empty addon and plugin lists until you add them
- Separate Plugin and Addon entries in Settings
- Plugin runtime with async timers (`setTimeout`) for Nuvio-compatible plugins
- In-app Privacy & Policy page describing what the app stores and which services it can contact
- Licenses and attributions (StreamBridge as the app; NuvioMobile as upstream)

**Updates**
- In-app updates from published GitHub Releases of `sascio/MyBridge` only
- Drafts and prereleases are ignored
- The client selects the newest compatible stable APK for the device ABI

**iOS (new in 0.1.08)**
- Same Kotlin core and MPV/ExoPlayer-equivalent playback pipeline as Android
- `StreamBridge.app`, bundle identifier `com.streambridge.app`, version 0.1.10
- Downloads Live Activity extension and bundled Noto subtitle fonts
- Distributed as an unsigned IPA through AltStore/SideStore, not the App Store
- Built by `.github/workflows/ios-release.yml`; see [docs/RELEASE-IOS.md](docs/RELEASE-IOS.md)

## Install

### Android

Production builds are the **published** GitHub Releases of this repository.
Each release attaches one APK per ABI:

- `androidApp-full-arm64-v8a-release.apk` (most phones)
- `androidApp-full-armeabi-v7a-release.apk`
- `androidApp-full-x86_64-release.apk`
- `androidApp-full-x86-release.apk`

Plus `checksums.sha256`. Branch CI APKs are **debug-signed compile
artifacts**, not production. In-place updates need the same production
certificate. See [docs/SIGNING.md](docs/SIGNING.md).

### iOS

iOS builds are published as an **unsigned** IPA on the
[published GitHub Releases](https://github.com/sascio/MyBridge/releases) of this
repository:

- `StreamBridge-0.1.10-iOS-unsigned.ipa`

There is no App Store listing. Install the IPA with **AltStore** or
**SideStore**, which sign it on your device with your own Apple ID:

1. Install [AltStore](https://altstore.io) (or [SideStore](https://sidestore.io))
   on your iPhone or iPad and complete its setup. Both work with a regular,
   free Apple ID; AltStore needs AltServer on a computer on the same network,
   SideStore pairs once and then works on its own.
2. In the app, add this repository as a source:
   `https://raw.githubusercontent.com/sascio/MyBridge/main/store.json`
   StreamBridge then appears as an installable, updatable app.
3. Tap Install. AltStore/SideStore signs the IPA with your Apple ID as it
   installs, so no Apple Developer account or Apple-issued certificate is
   involved.
4. To install the IPA file directly instead, or to re-sign it yourself, use the
   "Install from file" action with the downloaded `.ipa`.

A free Apple ID signs for **7 days** at a time, and AltStore/SideStore can
refresh StreamBridge automatically before it expires when it can reach your
device. Apple also limits a free account to 3 sideloaded apps and 10 new App IDs
per week. An Apple Developer account only changes those limits; StreamBridge
itself is the same build either way.

Because the IPA is not signed by an Apple-issued certificate, it cannot be
installed by double-clicking it in Finder, and it cannot be distributed through
TestFlight or the App Store. That is by design: the sideloading route is the one
that requires no paid account from anyone.

`store.json` in this repository is the sideload source. It is regenerated by
`.github/workflows/update-store-source.yml` from each published release, so it
always points at the real IPA of the newest version, with that file's size and
SHA-256. See [docs/RELEASE-IOS.md](docs/RELEASE-IOS.md).

## Build

### Android

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

### iOS

```bash
./scripts/prepare-ios-dependencies.sh
./scripts/build-ios-ipa.sh
```

Output: `build/ios-ipa/streambridge-0.1.10-full-release.ipa`, unsigned. The
release workflow publishes that same file as
`StreamBridge-0.1.10-iOS-unsigned.ipa` and
`.github/workflows/update-store-source.yml` records it in `store.json`. See
[docs/RELEASE-IOS.md](docs/RELEASE-IOS.md).

## Project identity, upstream credit, and repository history

StreamBridge is its own product, maintained and released by the StreamBridge
project at `sascio/MyBridge`. It is not an official NuvioMobile release and is
not published or supported by NuvioMedia.

The two upstream projects credited in [NOTICE](NOTICE) remain separate GPL-3.0
works:

- [NuvioMobile](https://github.com/NuvioMedia/NuvioMobile), pinned at
  `79b750f3` (0.6.1-beta)
- [NuvioMobile-Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced),
  pinned at `504014d3` (0.6.1-beta)

Their copyright notices, license texts, and attribution are kept in
[LICENSE](LICENSE), [COPYING](COPYING), and [NOTICE](NOTICE).

**Contributor list note.** StreamBridge was built as an overlay copy and shares
no git history with either upstream project. The 0.1.10 sync imported the
upstream source with a real merge of unrelated histories, so thousands of
upstream commits became reachable from `main`. GitHub calculates its
Contributors page from commits reachable from the default branch, which is why
upstream authors appear there alongside StreamBridge maintainers. README edits
cannot remove those historical authors from that statistic; only rewriting the
default branch's history would. The forensic findings, the prepared (unapplied)
clean-history migration, and the rollback procedure are documented in
[docs/REPOSITORY-IDENTITY-AUDIT.md](docs/REPOSITORY-IDENTITY-AUDIT.md).

## License

**GPL-3.0** — see [LICENSE](LICENSE), [COPYING](COPYING), and
[NOTICE](NOTICE). StreamBridge is based on NuvioMobile and
NuvioMobile-Enhanced, both by their respective authors, and includes
modifications and additional functionality.
