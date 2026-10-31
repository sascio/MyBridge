# StreamBridge branding audit — 0.1.14

Scope: the complete application source and build configuration after the
Nuvio 0.6.6-beta integration. Every `Nuvio`/`nuvio` match was classified as a
user-visible branding leak, a legitimate internal/upstream reference, or a
required licence/attribution notice. Only leaks were changed.

## Identity (unchanged)

| Item | Value |
|---|---|
| App name | StreamBridge (`app_name`, `app_brand_name` in every locale) |
| Android application ID | `com.streambridge.app` |
| Android namespace | `com.nuvio.android` (internal, unchanged since 0.1.08) |
| iOS bundle identifier | `com.streambridge.app` |
| Widget extension | `com.streambridge.app.DownloadsWidgetExtension` |
| iOS Xcode test target | `com.streambridge.app.SubtitleTests` (rebranded this release, see below) |
| Updater | `sascio/MyBridge` releases |
| Footer / motto | "Made with ❤️ by Saif" / "Your Media, Your Way" |

## Leaks found and fixed

1. **Watch-party invitations (4 locales).** The 0.6.6 sync added fully
   translated `values-bg`, `values-es`, `values-hr` and `values-sk` files whose
   invitation read "join my Nuvio party" and which had no install fallback.
   All party strings in every locale now say StreamBridge, keep `%1$s` (code),
   `%2$s` (deep link) and `%3$s` (install fallback). Enforced by
   `PartyInviteBrandingTest.everyLocaleInvitesToAStreamBridgeParty` and
   `everyLocaleDropsNuvioBrandingFromTheSignInPrompt`.
2. **App self-references (235 strings, 27 files).** Where the app speaks about
   itself — Trakt sign-in/disconnect copy, Simkl sync copy, library and
   watch-progress source pickers, the DNS note, the supporter/community copy and
   the Trakt device-activation instruction — the name is now StreamBridge.
3. **Privacy-policy link.** Upstream's About section opened
   `https://nuvio.tv/privacy-policy`. StreamBridge shows its own in-app policy
   page (`SettingsPage.PrivacyPolicy`), so no NuvioMedia URL is handed out.
4. **Settings footer.** Upstream's About page footer rendered the Nuvio member
   wordmark plus "made with" copy. It now renders the StreamBridge lockup, "Made
   with ❤️ by Saif", "Your Media, Your Way" and the version line.
5. **`autoVerify` on `nuvio.tv`.** The legacy invite intent filter claimed App
   Links verification for a domain StreamBridge does not control. The filter
   stays (old invites keep working) but no longer requests verification.
6. **Xcode test bundle identifier.** `com.nuvio.SubtitleTests` →
   `com.streambridge.app.SubtitleTests`.

## Legitimate references kept

| Where | Why |
|---|---|
| `settings_licenses_attributions_nuvio_*`, `streambridge_credits_*`, `NOTICE`, `LICENSE`, `COPYING`, `docs/UPSTREAM.md` | GPL-3.0 attribution for NuvioMobile/NuvioMedia. Required, never removed |
| `privacy_policy_network_nuvio_*`, `privacy_policy_section_supporter_body`, `privacy_policy_section_control_body` | Accurate disclosure that accounts, profiles and supporter entitlements live on the NuvioMedia-operated backend at `api.nuvio.tv` |
| `server_error_official_*`, DNS/server settings | The backend host is literally `api.nuvio.tv` |
| `trakt_library_source_nuvio`, `trakt_watch_progress_source_nuvio`, `settings_tracking_nuvio_*` | Names of the backend services (Nuvio Library / Nuvio Sync), not the app |
| `nuvio://` scheme, `com.nuvio.*` packages/C-interop, `nuvio.composeapp.generated` | Internal compatibility identifiers; changing them breaks OAuth callbacks, saved navigation state and generated resources |
| `third_party/AIOStreams`, `composeApp/libs/NOTICE.md`, `docs/CLOUDSTREAM-*` | Third-party licence notices |

## Remaining known gap (not a leak by classification)

Older locale translations (everything except the party strings and the keys
fixed above) still contain translated sentences that name Nuvio, exactly as they
did in 0.1.13. They were not touched wholesale: a blind global replacement is
prohibited, and the sentences are third-party-service descriptions in some cases
and app self-references in others. The English strings, the party strings and
every identity-bearing resource are clean; the remaining translation debt is
tracked here rather than silently rewritten.
