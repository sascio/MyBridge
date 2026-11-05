# StreamBridge 0.1.15 — Branding audit

Carries forward the standing classification from
[BRANDING-AUDIT-0.1.14.md](BRANDING-AUDIT-0.1.14.md). This file records what the
0.6.7-beta sync changed.

## Sync scrub

The sync integrated 96 upstream commits; their i18n improvements re-imported the
upstream product name in translated user-facing strings. Every leak was resolved:

- Strings that already had a StreamBridge-scrubbed translation in the previous
  release kept exactly that translation (the upstream rewording was declined for
  those strings).
- Strings new in this baseline (community membership, profile background note, watch
  party invites, licenses text and others) were brand-substituted in place, keeping
  the upstream translation quality.

The release gate (`scripts/verify-release-target.sh`) enforces the same rules as
0.1.14: release notes must not mention the upstream product, `store.json` must be
free of upstream naming outside the compatibility/host exemptions, and the resource
sweep across all 27 locales must come back empty.

## Kept for legal or technical reasons (unchanged classification)

- `LICENSE`, `COPYING`, `NOTICE`, README attribution and backend-disclosure sections.
- `privacy_policy_*` strings (they disclose where the source comes from; legally
  required provenance).
- `settings_licenses_attributions_nuvio*`, `streambridge_credits_*` (GPL/attribution).
- `server_error_official`, `action_support_nuvio` (backend host references).
- `Nuvio-compatible` plugin-format statements and `api.nuvio.tv` host strings
  (technical compatibility).
- Outbound `User-Agent` / client identifiers (`Nuvio/1.0`, `NuvioMobile`,
  `Client="Nuvio"` for Media Browser, the AniList developer-app name): these are
  server-visible identifiers required for the services to accept traffic; they are
  not user-facing UI.
- `NUVIO_UPSTREAM_*` / `NUVIO_ENHANCED_*` pins in `streambridge.version.properties`
  and the `com.nuvio.app` package/imports: source provenance and technical
  identifiers, not product branding.

## New in 0.1.15

- The About Us row icon and subtitle were restored verbatim from the 0.1.13 root
  page (`Icons.Rounded.Info` + `about_us_settings_subtitle`); the heart icon
  introduced by the 0.1.14 settings regroup is gone from every surface
  (row, page icon map, search target).
- The Discord invitation (`https://discord.gg/T9apykrjW`) was added as a
  StreamBridge community entry; it references StreamBridge only.
