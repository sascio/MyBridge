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
| Footer | "Made with ❤️ by Saif" + version line (motto removed in the corrected build) |

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
6. **iOS permission prompts.** `NSFaceIDUsageDescription` and
   `NSLocalNetworkUsageDescription` in `iosApp/iosApp/Info.plist` named Nuvio in
   the Face ID and local-network prompts iOS shows the user. Both now say
   StreamBridge.
7. **Xcode test bundle identifier.** `com.nuvio.SubtitleTests` →
   `com.streambridge.app.SubtitleTests`.

## Legitimate references kept

| Where | Why |
|---|---|
| `settings_licenses_attributions_nuvio_*`, `streambridge_credits_*`, `NOTICE`, `LICENSE`, `COPYING`, `docs/UPSTREAM.md` | GPL-3.0 attribution for NuvioMobile/NuvioMedia. Required, never removed |
| `privacy_policy_network_nuvio_*`, `privacy_policy_section_supporter_body`, `privacy_policy_section_control_body` | Accurate disclosure that accounts, profiles and supporter entitlements live on the NuvioMedia-operated backend at `api.nuvio.tv` |
| `server_error_official_*`, DNS/server settings | The backend host is literally `api.nuvio.tv` |
| `trakt_library_source_nuvio`, `trakt_watch_progress_source_nuvio`, `settings_tracking_nuvio_*` | Key names mirror the `WatchProgressSource.NUVIO_SYNC` storage value; the displayed text now says StreamBridge (see below) |
| `nuvio://` scheme, `com.nuvio.*` packages/C-interop, `nuvio.composeapp.generated` | Internal compatibility identifiers; changing them breaks OAuth callbacks, saved navigation state and generated resources |
| `third_party/AIOStreams`, `composeApp/libs/NOTICE.md`, `docs/CLOUDSTREAM-*` | Third-party licence notices |

## Second-pass corrections (corrected 0.1.14 build)

The first build shipped with the About page mislabelled and a motto in the
settings footer. The corrected build fixes those and closes the translation
gap left above. Everything below is verified by
`scripts/verify-release-target.sh` and the locale scans it runs.

8. **About Us heading restored.** The About page had been retitled
   "Supporters & Contributors". `SettingsPage.SupportersContributors.titleRes`
   and the About settings row resolve `about_us_page_title` ("About Us")
   again. Page content is unchanged.
9. **Footer.** The `streambridge_tagline` ("Your Media, Your Way") render was
   removed from `settingsFooterContent`; the brand lockup, the
   "Made with ❤️ by Saif" attribution and the version line are centre-aligned
   as one column, version last. No motto remains in the footer.
10. **Translation gap closed (key-scoped, not global).** Every translated
    sentence that named Nuvio was classified first. App self-references (the
    addon store, P2P consent, notifications, updater installs, debrid and
    tracking approvals, Discord Rich Presence, Picture-in-Picture, settings
    backup, library/progress source pickers, the TMDB/IMDb/Trakt/Simkl/
    MDBList/TorBox/Premiumize/IntroDB attribution bodies that name the app
    using those services) now say StreamBridge in all 27 locales. Third-party
    licence attributions, the privacy policy's backend disclosures and the
    `api.nuvio.tv` host keep their Nuvio references, as required. One
    orphaned Italian string with no default and no code reference
    (`supporters_contributors_donate_button`, "Dona a Nuvio") was removed.
11. **Component labels.** `TrackingBrand.NUVIO.displayName` (disconnect
    dialogs, accessibility) and `DiscordRpcConfig.APP_NAME` (Rich Presence)
    say StreamBridge; the MediaBrowser device profile and the TorBox device
    authorization app name identify as StreamBridge; the device-session
    registration client name says StreamBridge.

After the second pass, a full scan of every `values*` resource file finds zero
non-exempt Nuvio strings in any locale. The "remaining known gap" recorded
during the first pass is closed; the classification table above still governs
which Nuvio references are legitimate.
