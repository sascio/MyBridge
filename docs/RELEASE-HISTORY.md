# StreamBridge release history

A retrospective summary of StreamBridge's published release notes, beginning with 0.1.01. The
summaries below condense the release descriptions recorded on the linked StreamBridge releases.
The 0.1.01–0.1.03 descriptions are sparse; no additional feature history is inferred for them.
Build and device-test claims are not independently re-verified by this retrospective.

## 0.1.01

[Published release record](https://github.com/sascio/MyBridge/releases/tag/0.1.01). The initial
release description records the app version, its source baseline, and the requirement that in-app
updates use the production signing certificate. It does not enumerate user-facing features.

## 0.1.02

[Published release record](https://github.com/sascio/MyBridge/releases/tag/0.1.02). The description
repeats the initial baseline and production-certificate update note; it does not enumerate new
features.

## 0.1.03

[Published release record](https://github.com/sascio/MyBridge/releases/tag/0.1.03). The archived
description contains only a brief version and baseline reference, with no feature list.

## 0.1.04

[Published release record](https://github.com/sascio/MyBridge/releases/tag/0.1.04). Focused on
stability of optional Trakt and Simkl configuration: runtime-property handling, whitespace and
quoted values, and behavior when credentials are absent. The notes also state that credentials and
signing secrets are not embedded in the app.

## 0.1.05

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.05). Recorded
improvements to the Home hero, playback controls and timeline, gestures, brightness persistence,
next-episode behavior, watch history, external-player handling, subtitles (including CJK fonts),
shared UI styling, and Greek and Vietnamese translations. StreamBridge metadata, ratings, provider
integrations, addons, plugins, and personalization were retained.

## 0.1.06

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.06). Continued the
mobile-core update and carried forward improvements to Home, playback, watch history, subtitles,
shared styling, and translations while retaining StreamBridge-specific integrations and
personalization.

## 0.1.07

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.07). Added Simkl
recommendations, per-screen custom poster URL patterns with profile-settings sync, playback lifecycle
and post-credits timing work, gesture percentage overlays, profile-insight charts, password-manager
autofill, and device-local appearance and navigation sheets. The iOS lifecycle, player bridge, and
dependency preparation were also updated.

## 0.1.08

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.08). Introduced the
iOS app as an unsigned IPA for AltStore and SideStore, and reduced the size of shipped brand assets.
Added in-app ratings, episode shuffle, subtitle timing controls, asynchronous plugin timers, and
library downloads access. The release also refined stream loading and seek previews, debrid file
selection, ratings and profile-insight data, and added Bengali and Urdu translations.

## 0.1.09

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.09). Fixed Simkl
authorization, added the About Us page and Telegram community link, and updated Settings branding.
The release notes document unsigned iOS installation through AltStore or SideStore and the
StreamBridge source URL.

## 0.1.10

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.10). Refined the
footer, tagline, and app name presentation; gave Licenses & Attribution its own icon; fixed a cropped
splash logo; and changed Profile Overview so missing metadata is not treated as a failed genre load.

## 0.1.11

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.11). Added Watch
Party chat and fixed an invite/settings crash. Introduced an optional Liquid Glass interface and
player controls including pinch zoom, Fill stretch, portrait-video orientation, and optional styled
ASS/SSA subtitle playback on ExoPlayer. Tracking added shared anime-ID handling and watched-state
improvements; stream selection gained binge-group matching. Greek and Brazilian Portuguese
translations were added or improved.

## 0.1.12

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.12). Improved
multi-season anime handling in Continue Watching, resolved next-up items to their series, retained
Simkl identifiers through library refreshes, and filtered already-watched titles from the end-of-movie
recommendation card.

## 0.1.13

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.13). Added Watch
Party chat and phone-companion flows, refined player badges and controls, expanded subtitle font and
shadow customization, and added Liquid Glass navigation/search/filter options. Custom posters also
appear in library catalogs; binge-group matching retains filename and info-hash data. Discord Rich
Presence is optional and off by default. A background-session restoration fix preserves navigation
history, and Hungarian, Vietnamese, and Brazilian Portuguese translations were updated.

## 0.1.14

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.14). Stabilized the
Home hero with neighbor prefetching and draw-time transitions, and filled its metadata from cached
details when catalog previews are incomplete. Added subtitle appearance controls, chapter navigation,
Matroska chapter parsing, skip-interval handling, configurable skip-intro behavior, and audio night
mode. Settings were reorganized and configuration backup/restore was added. Other changes covered
player and library details, offline behavior, Rust-based seek-bar thumbnails and container indexes,
and the iOS subtitle and playback stack. StreamBridge branding corrections and CloudStream extension
management were also recorded. The release was rebuilt at the same version and code to correct
identified regressions; the release notes document that existing 0.1.14 installs would not receive
the same-code rebuild through version-code comparison.

## 0.1.15

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.15). Added the
experimental glass appearance family and app-wide background selection, refined search and library
headers, and improved playback fallback, subtitle rendering, subtitle hashes, Continue Watching, and
Live TV. iOS audio-session, Now Playing, and profile-insight presentation were updated. The release
also records a broad source-baseline synchronization and restored StreamBridge About Us and community
presentation.

## 0.1.16

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.16). Added audio
amplification, delay and downmix controls; improved source resume, engine failover, playback surfaces,
subtitle synchronization and appearance, and skip-intro data. Details gained rating-source options
and backdrop refinements; Home, library, and search received scrolling and layout updates. Live TV
gained Guide, Sports, and On Demand sections. iOS Picture in Picture subtitles and playback controls
were refined, and Tamil was added. The release notes also record profile, tracking, and toolchain
updates.

## 0.1.17

[Published release record](https://github.com/sascio/MyBridge/releases/tag/v0.1.17). Launched the
StreamBridge website and recorded a broad source-baseline update. Playback and subtitle reliability,
Anime4K controls, metadata matching, profile-aware discovery, iOS playback memory handling, Picture
in Picture teardown, and Home backdrop behavior were refined. Japanese, Hebrew, Bulgarian, Greek,
French, and Vietnamese translations were updated while preserving StreamBridge identity and
community links.

## 0.1.18

The planned StreamBridge 0.1.18 release notes are maintained in
[`RELEASE-NOTES-0.1.18.md`](RELEASE-NOTES-0.1.18.md). Publication, asset availability, and release
validation remain separate gates; this history does not imply that 0.1.18 has been released.
