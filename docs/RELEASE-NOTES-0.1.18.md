# StreamBridge 0.1.18

## StreamBridge 0.1.18

StreamBridge version 0.1.18 (version code 118). Android package
`com.streambridge.app`; iOS bundle `com.streambridge.app`.

## Core and playback updates

- Integrated the applicable Enhanced `0.6.11-beta` → `0.6.12-beta` source range. The exact tag,
  commit, path-by-path import dispositions, and StreamBridge-specific resolutions are recorded in
  [`SYNC-MANIFEST-0.6.12-beta.md`](SYNC-MANIFEST-0.6.12-beta.md).
- Subtitle preferences can be separated per device, exported/imported with settings, and cleared
  when a profile is removed.
- Chapter navigation and chapter-title controls now cover both playback engines and iOS. Android
  ExoPlayer adds Blu-ray ISO, BDMV and M2TS playback support.
- Live TV playback fixes address endless buffering and black screens, and restore the HLS quality
  chooser. HLS streams missing SPS/PPS initialization data and DASH streams with explicit MIME
  hints are routed more reliably.
- MPV position-zero glitches and playback error messages are improved. Saved tokenized stream links
  can be reused and refreshed after expiry.
- Folder views respect the catalog-type setting; IMDb actions in Discord Rich Presence are fixed.
- The Anime4K line sharpener has stronger settings. Dutch, French, Vietnamese and other translated
  strings were updated while retaining StreamBridge wording and project identity.

## Simkl and community

- Simkl AUTH V2 authorization supports PKCE browser flow and device authorization, with protected
  refresh-token storage and automatic refresh. StreamBridge's profile-aware sync and settings remain
  in place.
- About Us links directly to the official website. The website footer now exposes accessible
  Telegram and Discord links with visible labels and icons.

## StreamBridge identity and distribution

The app name, motto **Your Media, Your Way**, footer credit **Made with ❤️ by Saif**, Android and
 iOS identifiers, updater and distribution metadata, GPL-3.0 licensing, production Android signing
configuration, Telegram automation, and unsigned iOS IPA distribution remain unchanged. The
AltStore/SideStore source continues to be updated only by the published-release workflow after it
verifies the actual unsigned IPA and digest; this source file is not hand-edited for a draft.

The production release workflow is expected to attach four production-signed Android APK ABI
variants, one unsigned iOS IPA named `StreamBridge-0.1.18-iOS-unsigned.ipa`, and
`checksums.sha256`. Branch-CI debug artifacts are not production release files. Release builds must
pass the workflow's version/tag checks and signing guards before publication.
