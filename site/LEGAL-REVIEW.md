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

## Source audit — follow-up to 80ea6d9

### Verified in client source, not runtime-tested

- Android `MainActivity.onCreate` initializes preferences and calls `SentryInitializer.start`; the manifest disables SDK auto-init. `SentrySettingsRepository` loads the saved preference or defaults to `true`, without a prior consent prompt. Initialization requires a nonblank build-time Sentry DSN. The settings dialog confirms changes, persists the choice in app-private preferences, and the initializer observes changes and calls `Sentry.close()` when disabled; the breadcrumb interceptor also checks `Sentry.isEnabled()`. This establishes intended behavior, not a device test or proof about events already queued or in flight. iOS reports this setting unsupported. The README's “opt-in” wording is inconsistent and must be corrected by the owner; it was not changed because the requested scope is website files only.
- Android Sentry configuration tags package, app version and version code; sets the release and environment; disables default PII, screenshots, view hierarchy and performance tracing; and clears event user and request fields before sending. The UI describes crash and current ANR events, stack traces and device/OS details. Custom HTTP breadcrumbs include method, host, encoded path, status, duration, exception class and potentially exception message (up to 240 characters); query and fragment are stripped from the breadcrumb URL. Paths and error messages can still contain sensitive information, and SDK-generated fields or queued reports have not been empirically inspected. See `SentryInitializer.kt`, `SentryNetworkBreadcrumbInterceptor.kt`, `SentrySettingsRepository.kt`, `AdvancedSettingsPage.kt`, platform storage and manifest. No DSN values were inspected or recorded.
- `ServerConfiguration.kt` selects a build-configured backend (default `https://api.nuvio.tv`), with a custom server option and possible fallback; `SupabaseProvider.kt` installs authentication, PostgREST, functions, realtime and storage clients. `AuthRepository.kt` sends email/password for sign-up or sign-in; guest mode creates a locally stored anonymous identifier. Non-anonymous signed-in flows trigger profile synchronization. `DeviceSessionRegistration.kt` sends installation ID, client version, platform and device name to the configured backend. Account deletion invokes a backend function, but the server's outcome and retained records are not verified.
- `SyncManager.kt`, `ProfileSettingsSync.kt` and `ProviderCredentialSync.kt` show account/profile synchronization of settings, addons/plugins, library, collections and other data. The separate provider-credential sync can push/pull configured debrid keys, TMDB and MDBList API keys, AnimeSkip client ID and IntroDB API key for a non-anonymous signed-in active profile. This is distinct from the optional sensitive-data setting for manual configuration export. `MediaServerSync.kt` and `ServerSync.kt` serialize media-server addresses, usernames, remote IDs, library selection and access tokens into the sync payload. This is a code-path finding, not proof every installed build or user's account has synchronized them.

### Still requiring owner or counsel confirmation

- Which builds have a nonempty Sentry DSN, who controls it, whether consent is legally required, actual crash/ANR payloads and delivery/queue behavior after disabling, SDK and infrastructure retention and access, and correction of the README and any conflicting in-app wording.
- Effective deployed backend URL and fallback per build, who operates each endpoint and can access data, exact live RPC/server processing, third-party processors, safeguards, retention, backups, deletion results, and where processing occurs. Do not assume client-side protections prove server encryption or access control.
- Whether all described synchronization features are enabled in distributed builds, any settings controlling synchronization or migration, what happens to credentials on account deletion, and whether the user-facing disclosure and consent are legally adequate. Avoid treating manual credential-export consent as a condition for separate backend sync.
- Resolve legal identity, private contact and rights-reporting route, governing law, applicable Indian DPDP provisions and other regional requirements, as noted above. These remain drafts for qualified review, not production-ready legal documents.

## Contact form — owner review required

The proposed contact form sends name, email, category and message to a same-origin Cloudflare Pages Function, which forwards them to a Google Apps Script web app for email to the owner's private inbox. Google infrastructure processes delivery; a configured Turnstile widget processes security-check data including IP and browser interaction data. These are implemented flows, not proof the system has been deployed or that a message has reached the inbox. Confirm the actual Google account and mailbox owner, who can access messages and script properties, provider arrangements and locations, whether Turnstile is enabled, legal bases and any notices or consent required, and response/grievance procedures. Establish retention and deletion for mailbox, logs and backups; none is verified. Obtain qualified legal review before adoption.

## Official policy alignment and source audit

The official Nuvio home page links to `https://www.nuvio.tv/terms`, and that
page links to `https://www.nuvio.tv/privacy-policy`. These were reviewed as
structural references, not as proof of StreamBridge's operator or practices.
The StreamBridge drafts cover acceptance, media and ownership, independent
providers and addons, lawful use, rights reports, GPL and attribution, accounts,
availability, disclaimers, liability, privacy and contact. The privacy draft
covers information, diagnostics, local and external processing, providers,
retention, security, rights, children and review. Nuvio-specific operator,
contact, retention, community-licensing and incident-response commitments are
not transplanted without evidence that they apply to StreamBridge.

The owner states that StreamBridge does not collect users' personal data; this
cannot be established as a blanket statement from source. A configured contact
form can forward submitted personal information to the owner's inbox. Account
and sync, hosting, user-selected providers and potentially Sentry process data;
control of the actual production services and release DSN requires independent
confirmation. A focused source search found Media3 `AnalyticsListener` uses
for playback behavior, not by itself evidence of a remote analytics service;
absence of matching SDK names is not proof that infrastructure collects none.
No application features, consent settings or data flows were disabled.
