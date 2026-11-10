# Legal drafts — verification and review checklist

These pages are drafts, not legal advice or approved terms. Before adoption, confirm:

- The legal operator/controller, legal name, registered or contact address, and whether any company exists.
- A private, monitored contact and process for privacy requests, complaints, security and copyright reports; whether a grievance officer or other designated contact is required. Public GitHub issues are not suitable for sensitive requests.
- Applicable governing law, courts and dispute handling; mandatory consumer protections; age and parental-consent rules; enforceability of any liability or indemnity language.
- Actual Cloudflare configuration, infrastructure logging, analytics, cookies, processors, retention, deletion, locations, security controls and transfers; account ownership and access to logs.
- Default and custom backend operator arrangements, actual data fields, credential-bearing sync/export behavior, deletion and retention, and independent provider responsibilities.
- Who controls the Sentry DSN in each release and what reports actually contain. The README says opt-in, but `SentrySettingsRepository` defaults to enabled, Android indicates support, and `SentryInitializer` initializes when a nonempty DSN is configured; iOS marks the setting unsupported. Resolve the conflict and verify consent before finalizing.
- Actual release-specific integration scopes, token/cached-data deletion and server-side revocation results, especially with failed network requests and device backups. Confirm whether integration or backend data reaches any StreamBridge-operated systems.
- Applicable Indian DPDP Act and rules, commencement and obligations, other relevant regional laws, notices, legal bases, rights, children’s processing and complaint procedures with counsel.
- Review website and app content, copyright complaints, third-party licences and upstream attribution against `COPYING`, `LICENSE`, `NOTICE` and component notices. Do not restrict GPL rights.

Audit pointers: website `site.js` (theme preference and GitHub requests), `site/APP-LINKS.md` (Pages), `README.md` privacy section; app `features/settings/PrivacyPolicyPage.kt` and resource strings; `core/auth` and sync/configuration transfer; provider auth, API, sync, and platform persistence under `features/{trakt,simkl,anilist,mdblist,wetrakr}`; Android diagnostics and settings. Findings reflect source inspection, not a production infrastructure audit or proof of remote retention/deletion.
