# StreamBridge 0.1.17

## StreamBridge 0.1.17

StreamBridge version 0.1.17 (version code 117). Android package
`com.streambridge.app`; iOS bundle `com.streambridge.app`.

## Complete core synchronization

This release synchronizes the complete applicable 0.6.11-beta upstream source range from
StreamBridge's recorded 0.6.9-beta baseline. The file-level inventory and merge decisions are
recorded in [`SYNC-MANIFEST-0.6.11-beta.md`](SYNC-MANIFEST-0.6.11-beta.md).

## Playback and subtitles

- **Anime4K video enhancement** — optional 2× upscaling and line sharpening for the Android
  ExoPlayer and mpv playback paths, with player controls and device-local settings.
- **Subtitle shadow outline blur** — soften subtitle outlines while retaining sharp glyphs;
  rendering and preferences are supported on Android and iOS.
- Forced-subtitle selection and automatic subtitle synchronization are enabled by default.
  AutoSync can be enabled without choosing a preferred subtitle language.
- Restored subtitle tracks search further for a fit before falling back; MPV playback errors
  now include actionable engine details.
- Android playback startup, buffer limits, caption-setting reads, and network TLS setup failure
  handling were hardened. Existing user sessions are retained when a refresh attempt fails.

## Discovery and tracking

- MDBList episode matching handles differences between addon episode numbering and TMDB
  numbering, and reuses progress/watched lists until a mapping changes.
- The existing profile-aware playback, library, provider, addon/plugin, watch-party and
  tracking flows remain integrated; the sync does not replace StreamBridge's Trakt or
  CloudStream-specific implementations.

## Performance and reliability

- iOS playback reduces memory pressure during long sessions and 4K HDR/Dolby Vision playback;
  MPV surface teardown and parked Picture-in-Picture close handling are safer.
- Backdrop focus calculations and image framing account for image coverage, foreground
  separation, and dense texture. Hero image failures reset reveal state rather than leaving a
  partially revealed backdrop.
- Player service launch and subtitle restore fallbacks were corrected.

## Translations

Japanese, Hebrew, Bulgarian, Greek, French and Vietnamese text updates are included. StreamBridge
branding and the three-part watch-party install invitation are retained in localized strings.

## StreamBridge release identity

The app name, motto, footer attribution, Android application ID, iOS bundle ID, updater/repository
URLs, production signing configuration, unsigned iOS workflow, AltStore/SideStore source format,
Discord and Telegram identity, and the production website are retained. The release contains no
user-interface rebrand to the upstream project.

## Distribution

The production release is expected to contain four production-signed Android APK ABI variants,
one **unsigned** iOS IPA for AltStore/SideStore, and `checksums.sha256`. Branch-CI debug APKs are
not release artifacts. The release workflow verifies the existing production signing configuration
and refuses to substitute a debug APK or signed iOS IPA.

The in-app updater uses the numeric version code; 117 is newer than 116. The sideload source is
updated from the published release workflow after a real unsigned IPA is attached and verified.
