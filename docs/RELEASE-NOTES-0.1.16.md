# StreamBridge 0.1.16

## StreamBridge 0.1.16

StreamBridge version 0.1.16 (version code 116). Android package
`com.streambridge.app`, iOS bundle `com.streambridge.app`.

## Core baseline sync

This release integrates the complete applicable 0.6.9-beta core tree at source level —
150 upstream commits, 265 changed files (101 added, 161 modified, 3 removed) — while
keeping every StreamBridge-specific implementation, identifier and integration on top of
it. Every changed file was classified against the previous 0.6.7-beta baseline:
untouched files were taken verbatim, files changed on both sides were three-way merged,
and StreamBridge-owned files (versioning, sideload source, signing, release and Telegram
workflows, branding) were never overwritten.

## Player and playback

- **Audio delay** — shift the audio track against the video from the player's audio
  menu, with an option to remember the delay per output device (Android).
- **Audio amplification** — boost quiet sources above 100 % from the audio menu, with
  an option to keep the gain for the next videos (Android).
- **Downmix and centre mix** — mix surround audio down to a chosen channel count with
  the app's software decoder and raise the dialogue channel, with a "maintain original
  volume" switch (Android).
- **Stalled torrent resume** — a torrent stream that was closed mid-way now resumes at
  the stalled frame instead of restarting the download position.
- The automatic engine failover is pinned to the current source so it cannot fire for a
  stream that is no longer playing.
- libmpv load failures are surfaced in the player instead of leaving a black screen.
- Playback keeps running through phone calls and handles audio focus like a video app
  (duck, pause only on a real loss, resume afterwards).
- Surfaces are attached and detached on the libmpv playback thread, which removes a
  class of crashes when leaving the player quickly.
- Playback locks to landscape on tablets; edge swipes are left to the system back
  gesture instead of seeking.
- VC-1 video that previously played audio only is decoded again.
- **Title logo** — optionally show the title's artwork instead of its text in the
  player controls, with text as the fallback and a language fallback chain for the
  artwork.
- The loading screen shows the current download speed.
- New player-interface options hide the glass backgrounds behind the controls and the
  skip back/forward buttons.
- The chapter skip derivation keeps a post-credits scene as a skip target, never as a
  skippable segment of its own.

## Subtitles

- **Outline blur** — a new subtitle appearance control softens only the outline while
  the text stays sharp; rendered natively on iOS, through the text renderer on Android.
- Automatic subtitle sync now handles ASS/SSA add-on subtitles drawn by libass: cues are
  matched by their dialogue, the synced copy is swapped in place, and the sync falls
  back to an SRT/VTT track when no styled track fits. The subtitle the user picked is
  kept through engine switches.
- The bundled libass renderer was updated.

## Skip intro

- Anime-Skip is queried for Kitsu/MAL identifiers even without an IntroDB mapping; the
  most complete entry is chosen for an episode; open-ended segments are dropped and
  AniSkip is preferred when both have data.

## Details, ratings and streams

- **Star ratings** — rate titles and episodes from the details page. Ratings sync with
  Simkl and MDBList, and a new rating-source picker in Tracking settings decides which
  service stores them.
- The cinematic backdrop now shows behind the floating header; buttons and chips on the
  details page and on the streams list are see-through over backdrops; tablet artwork
  fades into the cinematic backdrop; the glass floating header is kept in cinematic
  mode.
- Movie and show details open faster: TMDB enrichment and related-title lookups no
  longer block the first render.
- **Smart hero framing** — cropped backdrops on Home are framed around the image's
  focal region instead of its centre.

## Home, library and search

- Smoother scrolling on Home, shelves and grids: shared card styles, poster prefetch,
  a compose stability configuration and a baseline-profile module.
- The library's cloud search box and the Discover search box are hidden behind a search
  action in their headers.
- Screen header titles are constrained so long names no longer push the actions off
  screen.

## Live TV

- **Guide, Sports and On Demand** sections next to the channel list, with section tabs,
  a collapsible search, recents / favourites / category chips that show their counts,
  and a grid/list toggle for channels.
- Sports events come from the ESPN scoreboard with a fallback when a date range is
  rejected; the section explains why it is empty instead of showing nothing.
- On Demand lists movies and series from Xtream Codes providers.
- Categories matching the query are included in Live TV search results.
- The back-to-top button is gone and the header offset matches the other tabs.

## Tracking

- Private Viewing moved from Player Interface to Tracking.
- WeTrakr shows the watched badge for completed shows and no longer pushes rewatches.
- Simkl related-title requests are signed and Simkl failures are logged.
- Plugin settings pages open again.

## iOS

- Text subtitles are drawn in Picture in Picture and in the mini player.
- Video output presets are applied when the player opens.
- The skip-ahead after Control Centre / Notification Centre is gone.
- The app background stays in place while the player is parked in PiP, and collection
  folders draw under the native navigation bar so Glass UI shows through.
- The temporary HLS playlist is written correctly again, and the loading speed reads
  the libmpv cache speed with the right type.
- Tamil is registered as an app language.

## Translations

- **Tamil** is a new locale on Android and iOS.
- Bengali, Vietnamese and Bahasa Indonesia were brought up to date with the English
  strings; French gained the audio downmix, amplification and delay strings; two pt-BR
  descriptions were corrected.
- Every new or updated locale file carries StreamBridge branding, and the watch-party
  invitation in the new locales includes the StreamBridge install fallback like every
  other locale.

## Toolchain

- Deprecated Ktor and Kotlin APIs were cleared; `-Xexpect-actual-classes` and the Coil
  experimental opt-in are declared once in the build; a baseline-profile module and
  the profile installer are wired for release builds.

## StreamBridge specifics

- App identity, package `com.streambridge.app`, launcher icon, splash, in-app updater
  (`sascio/MyBridge`), About Us, Licenses & Attributions, CloudStream runtime, Telegram
  release automation and the sideload source are unchanged.
- The upstream sideload-source and Xcode version files were not imported; StreamBridge
  keeps its own.

## Installation

APKs built by branch CI are debug-signed compile artifacts, not production builds. The
published release carries one production APK per ABI (production-certificate signed,
SHA-256 checksums attached as `checksums.sha256`) and an **unsigned** iOS IPA for
AltStore/SideStore re-signing. Install only assets downloaded from this repository's
published releases.

The in-app updater compares version codes, so 0.1.16 (code 116) is offered as an update
over any earlier version.

### iOS installation

The iOS build in this release is **unsigned**. Install it with
[AltStore](https://altstore.io) or [SideStore](https://sidestore.io): either signs the IPA with
your own Apple ID on your device, so no Apple Developer account and no App Store listing is
involved.

Add this repository as a source in AltStore or SideStore to get updates automatically:

`https://raw.githubusercontent.com/sascio/MyBridge/main/store.json`

See `docs/RELEASE-IOS.md` for the details.
