# OAuth branding: why AniList and WeTrakr show "Nuvio"

This document explains where the application name on the AniList and WeTrakr
authorization pages comes from, what StreamBridge can change from its own source
code (very little), and the exact third-party steps required to make those pages
say **StreamBridge**.

## Short answer

**The name is not in our code.** Both providers print the name recorded in *their*
records for the OAuth client id we send. StreamBridge only sends the client id,
so renaming the app is a change in the provider's registration, not a code change.

| Provider | What our code sends | Where "Nuvio" is stored |
| --- | --- | --- |
| AniList | `GET https://anilist.co/api/v2/oauth/authorize?client_id=53329&response_type=token` | AniList's developer settings for API client `53329` |
| WeTrakr | `POST https://api.wetrakr.com/oauth/device/code` with `{"client_id": "193cadea…"}` | WeTrakr's records for that client id |

## AniList in detail

* Authorization URL: `AniListTracker.kt` → `AniListConfig.AUTHORIZE_URL` plus
  `client_id` and `response_type=token`. Nothing else is sent: AniList's OAuth
  endpoint has no `app_name`/`name` parameter at all.
* Callback: `nuvio://auth/anilist` (`AniListConfig.REDIRECT_URI`), handled by the
  `LinkActivity` intent filter that accepts the `nuvio` scheme.
* The client id `53329` is upstream Nuvio's registration, inherited with the
  NuvioMobile source this project is based on. AniList shows the name that
  registration was created with, which is why the consent page says "Nuvio".
* We cannot rename it: only the AniList account that owns client `53329` can edit
  that registration, and that is not this project.

### Required dashboard steps (you must do this)

1. Sign in at <https://anilist.co>.
2. Open <https://anilist.co/settings/developer> ("Settings → Developer").
3. Choose **Create New Client**.
4. **Name:** `StreamBridge`
5. **Redirect URL:** `nuvio://auth/anilist` — exactly this, with no trailing
   slash and no `https://`. It must match the callback the app already handles.
6. Save, then copy the new numeric **Client ID**.
7. Put it into the build in one of two ways:
   * **No code edit (recommended):** add `ANILIST_CLIENT_ID=<your new id>` to the
     `local.properties` content stored in `NUVIO_LOCAL_PROPERTIES_BASE64`
     (see [TRAKT-SIMKL-CONFIG.md](TRAKT-SIMKL-CONFIG.md)), or
   * **Code edit:** change `DEFAULT_CLIENT_ID` in
     `composeApp/src/commonMain/kotlin/com/nuvio/app/features/anilist/AniListConfig.kt`.
8. Rebuild. The generated config log line reports
   `ANILIST_CLIENT_ID override=true`, and the next connection attempt shows
   **StreamBridge** on AniList's consent page.

> AniList OAuth uses an implicit token grant, so an existing token belongs to the
> account, not to the client id. If AniList rejects a token after the switch,
> use *Disconnect* then *Connect* once. Callback handling, scopes and token
> storage are unchanged by this switch.

## WeTrakr in detail

* Authorization is the **device authorization grant**, not a browser redirect:
  the app posts the client id to `/oauth/device/code`, WeTrakr returns a user
  code and the verification page <https://wetrakr.com/activate>, and the app
  polls `/oauth/device/token`.
* The app sends `User-Agent`, `wetrakr-api-key` (the client id) and
  `wetrakr-api-version`. There is no display-name parameter, so the name on the
  activation page is WeTrakr's record for the client id.
* The client id `193cadea493eaf3f7f4cdcbba020f577` is the registration the app
  inherited from upstream Nuvio. Its recorded application name is "Nuvio".

### Required steps (third-party; we cannot do this for you)

WeTrakr does not expose a documented self-service developer console in this
repository, and this environment has no access to wetrakr.com, so treat this as
an ownership/support action rather than a code change:

1. Ask WeTrakr (the account or operator that issued client id
   `193cadea493eaf3f7f4cdcbba020f577`) to **rename that client's application
   name to StreamBridge**, or
2. Ask WeTrakr for a **new client id registered as StreamBridge**, then add
   `WETRAKR_CLIENT_ID=<new id>` to the `local.properties` content behind
   `NUVIO_LOCAL_PROPERTIES_BASE64` (or change `DEFAULT_CLIENT_ID` in
   `WeTrakrConfig.kt`) and rebuild.

If neither is done, the activation page will keep saying "Nuvio" no matter what
this repository builds.

> The `User-Agent` header (`WeTrakrConfig.USER_AGENT`) is deliberately left as
> `NuvioMobile`. It is not user-visible branding, it is not what the activation
> page prints, and changing it could affect provider-side allowlisting. Renaming
> it would not change the displayed name.

## What was changed in this repository

| File | Change |
| --- | --- |
| `composeApp/build.gradle.kts` | generates `TrackingClientOverrides` from optional `ANILIST_CLIENT_ID` / `WETRAKR_CLIENT_ID`; presence-only log line |
| `AniListConfig.kt` | `CLIENT_ID` is now the override when supplied, otherwise the previous `53329` |
| `WeTrakrConfig.kt` | `CLIENT_ID` is now the override when supplied, otherwise the previous id |

Defaults are unchanged, so an unconfigured build behaves exactly as before:
redirect URIs (`nuvio://auth/anilist`, `nuvio://auth/simkl`, `nuvio://auth/trakt`),
scopes, token handling, account linking and disconnect/reconnect are untouched.

## How to verify

1. Trigger **Actions → Build Stream Bridge → Run workflow**, or push a commit.
2. In the `generateRuntimeConfigs` log line look for
   `ANILIST_CLIENT_ID override=true` / `WETRAKR_CLIENT_ID override=true`.
3. Install the build, press *Connect* (AniList) or start the WeTrakr device flow,
   and read the provider page. It must say **StreamBridge**.
4. If it still says "Nuvio", the id was not switched or the provider has not
   applied the rename; no code change can override that.
