# Watch-party invites

The app shares first-party HTTPS links in the form
`https://streambridgeapp.pages.dev/party/FXRXFV`. The six-character code is
normalized with the existing party alphabet. The shared parser accepts only the
Pages host over HTTPS on port 443 and a six-character valid party path; it keeps
existing custom-scheme and older GitHub/Nuvio invite parsing for compatibility.

Android declares verified-link filters for the root and six-character party
paths, but verification is **blocked** until the actual release APK signing
certificate is verified and `site/.well-known/assetlinks.json` is created with its public SHA-256.
Android's simple path pattern matches any six characters; invalid codes are
rejected by the parser but may still open the app rather than the web page.

When no verified app handles a path-style invite, Pages serves `site/404.html`,
which forwards a valid code to `/party/?code=FXRXFV`. That page offers the
StreamBridge app scheme, displays the code, and links to the real release page.
It requires JavaScript for the path-style redirect and does not prove app
installation. Previously shared custom-scheme and GitHub URLs remain supported.

The iOS shared parser and `.onOpenURL` can process a first-party URL once a
Universal Link association is configured, but the App ID prefix, entitlement,
AASA and signed distribution are not established. See `site/APP-LINKS.md`.
Device verification and deployment are required before calling links ready.
