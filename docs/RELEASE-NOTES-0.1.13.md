## StreamBridge 0.1.13

### What's fixed

- **Watch Party invites:** invite copy is StreamBridge-branded and uses a normalized, StreamBridge-owned `streambridge://party/<code>` deep link instead of sending recipients to `nuvio.tv`. Existing `nuvio://` and legacy `nuvio.tv` links remain accepted. The invitation includes the releases page as an install fallback. A static HTTPS redirect page is included for GitHub Pages; app-to-app invites do not depend on Pages being enabled.
- **Release pipeline:** a draft release is resolved through the release list because GitHub's tag lookup endpoint returns 404 for drafts. The iOS IPA is attached by release ID, existing assets are verified by SHA-256 and never clobbered, and `store.json` is updated only from a published release with a verified uploaded IPA.

### What's new

- Upstream 0.6.3: the official NuvioMobile 0.6.3-beta and NuvioMobile-Enhanced 0.6.3-beta trees are both integrated.
- Watch Party: in-party chat, plus a phone companion that links to the party with the same profile.
- Player: badges for what is actually playing, the engine switch moved to the top row behind a confirmation, and centre controls that stay put on pause.
- Subtitles: import your own subtitle font and tune a text shadow on both the ExoPlayer and libmpv paths.
- Liquid Glass: configurable navigation-bar glass with a live preview, plus glass search and stream filtering.
- Library: custom posters now also appear in the library catalog view; binge groups keep the info hash and filename.
- Discord Rich Presence: optional and off by default (Settings -> Integrations).
- Backgrounding: pausing a video and switching apps no longer returns you to profile selection or Home; the restored screen keeps its navigation history.
- Languages: Hungarian and Vietnamese coverage, plus pt-BR additions for party and autosync.
- Updated the app to version 0.1.13 (version code 113).

### Android

Production-signed, one APK per ABI. Install the APK that matches your device; in-place updates need the same signing certificate. Verify downloads against `checksums.sha256`.

### iOS status

The unsigned iOS IPA is built from the same verified tag and attached after its build completes. It does not block the Android build; this draft remains unpublished until release checks pass and it is explicitly published.
