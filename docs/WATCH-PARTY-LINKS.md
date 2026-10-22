# Watch-party invites and deep links

A watch-party invite has to do three things: carry the six-character party code, open the party in
StreamBridge when the app is installed, and still be useful to somebody who does not have the app
yet. This document records what the app generates, what accepts it, and the one piece of hosting
that is not in the repository.

## What an invite contains

`partyInviteLink(code)` in
[`composeApp/src/commonMain/kotlin/com/nuvio/app/features/party/PartyRepository.kt`](../composeApp/src/commonMain/kotlin/com/nuvio/app/features/party/PartyRepository.kt)
returns a StreamBridge-owned deep link:

```
streambridge://party/FXRXFV
```

The code is normalised before it is put in the link (uppercased, filtered to the party alphabet
`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`), so the link always carries exactly the code the party was
created with. The displayed code keeps its space (`FXR XFV`); the link never does.

The shared message (`party_invite_message` in
[`strings_party.xml`](../composeApp/src/commonMain/composeResources/values/strings_party.xml)) is:

```
Join my StreamBridge watch party with code FXR XFV
streambridge://party/FXRXFV

New to StreamBridge? https://github.com/sascio/MyBridge/releases/latest
```

The last line is the fallback: it is an ordinary https link that works for everybody, whether or not
Pages is enabled and whether or not the app is installed.

### What changed for the 0.1.13 release target

The original `v0.1.13` tag generated invites that read *"Join my Nuvio watch party with code FXR XFV"*
and linked to `https://nuvio.tv/party/FXRXFV`. `nuvio.tv` is NuvioMedia's domain: StreamBridge does
not control it, cannot point it at its own app, and should not hand it out. The corrected invite uses
StreamBridge branding and the registered `streambridge://party/<code>` scheme. Older Nuvio links remain
parseable for backward compatibility.

## What accepts the link

| Platform | Registration |
|---|---|
| Android | `LinkActivity` intent filters in [`AndroidManifest.xml`](../androidApp/src/main/AndroidManifest.xml): the `streambridge` scheme, plus `https://sascio.github.io/MyBridge/party…` (not auto-verified) |
| iOS | `CFBundleURLSchemes` in [`Info.plist`](../iosApp/iosApp/Info.plist) |

`parseAppDeepLink` (in [`AppUrlBridge.kt`](../composeApp/src/commonMain/kotlin/com/nuvio/app/core/deeplink/AppUrlBridge.kt))
accepts all of these and resolves each to the same party:

- `streambridge://party/FXRXFV` and `streambridge://party?code=FXRXFV` — what this app generates
- `nuvio://party/FXRXFV` — the scheme the app still registers (OAuth callbacks, older notifications)
- `https://nuvio.tv/party/FXRXFV` — legacy invites that were already shared; parsed, never generated
- `https://sascio.github.io/MyBridge/party/FXRXFV` and `…/party/?code=FXRXFV` — the redirect page below

Anything else is rejected, including a code of the wrong length and look-alike hosts.

## The redirect page (`site/`)

[`site/party/index.html`](../site/party/index.html) turns an https invite into the deep link:

1. It reads the code from `?code=` (or from a `/party/<code>` path).
2. It hands over to `streambridge://party/<code>` immediately.
3. If the page is still in the foreground after roughly two seconds the app is not installed, so it
   shows the party code and a link to the release page instead of a dead end.

`site/404.html` forwards path-style links (`…/party/FXRXFV`) to the canonical `?code=` form, because
GitHub Pages is static hosting and cannot rewrite paths.

### The one external step

Publishing the redirect page needs a GitHub Pages site for this repository. Creating that Pages site
requires a repository administrator:

**Settings → Pages → Build and deployment → Source: GitHub Actions**

Neither the workflow token nor the automation token used to prepare a release is allowed to create
Pages (`Resource not accessible by integration`), so
[`.github/workflows/pages.yml`](../.github/workflows/pages.yml) is manual (`workflow_dispatch`) and
its first step reports exactly this if Pages is not enabled. Once Pages is enabled, run
**Publish invite site** once and the redirect is live at:

```
https://sascio.github.io/MyBridge/party/?code=FXRXFV
```

Nothing in the app depends on that being done: `partyInviteLink` returns the deep link, which needs no
hosting, and the invite message always carries the https install fallback. Switching the generated
invite to the https redirect is then a one-line change in `partyInviteLink`.

A custom domain (for example `streambridge.app`) would be better than the Pages URL. It requires DNS
and a Pages custom-domain setting, neither of which can be done from the repository, so no such
domain is referenced anywhere in the app.
