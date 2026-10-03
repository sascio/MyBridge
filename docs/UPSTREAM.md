# Upstream baselines

StreamBridge’s runnable core (Android and, since 0.1.08, iOS) **is**
NuvioMobile at the pin below.

| Project | Clone URL | Branch | Commit | License | In this repo |
|---|---|---|---|---|---|
| NuvioMobile | https://github.com/NuvioMedia/NuvioMobile | `cmp-rewrite` | `5c6028f24d3017f27fc4aeb0b6234b18fcfd2ee8` (tag `0.5.5-beta`) | GPL-3.0 | `composeApp/`, `androidApp/`, `iosApp/`, `gradle/` |
| NuvioMobile-Enhanced | https://github.com/luqmanfadlli/NuvioMobile-Enhanced | `enhanced` | `8b3bd867e172757c9fd7373da301e6489914f3fc` (tag `0.5.5-beta`) | GPL-3.0 | unique-feature overlay on official 0.5.5-beta |
| NuvioTV | https://github.com/NuvioMedia/NuvioTV | `dev` | `e7a335ebb38f41fe95e69a7592520d404c1326ae` | GPL-3.0 | not copied (TV-only) |
| nuvio-engine | https://github.com/NuvioMedia/nuvio-engine | default | `772f8c055049def5e87ae5ddb17b4ad15a71085a` | GPL-3.0-or-later | Android AAR (`composeApp/libs/lib-nuvio-engine-android-0.1.2.aar`) and the iOS Apple XCFramework fetched at build time |
| MPVKit | https://github.com/NuvioMedia/MPVKit | `Nuvio` | fetched by `scripts/prepare-ios-dependencies.sh` (not vendored) | LGPL-3.0 | iOS playback local Swift package (`../MPVKit`) |

Previous StreamBridge pins: NuvioMobile `bb3c1e4c` (0.5.2-beta), `b88fef2e`
(0.5.1), `ec441c7b` (0.4.25-beta), `d177eb57` (0.4.17), `465266d` (0.4.16),
`e377942` (0.4.15); NuvioMobile-Enhanced `3da8d06f` (0.5.2-beta), `9d311c18`
(0.5.1-beta), `ce4492ec` (0.4.23-beta), `d4a3b23` (0.4.16).

See [`streambridge.version.properties`](../streambridge.version.properties),
[NOTICE](../NOTICE), and [UPSTREAM-SYNC.md](UPSTREAM-SYNC.md).
