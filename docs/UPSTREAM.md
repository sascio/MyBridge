# Upstream baselines

StreamBridge’s runnable core **is** NuvioMobile at the pin below.

| Project | Clone URL | Branch | Commit | License | In this repo |
|---|---|---|---|---|---|
| NuvioMobile | https://github.com/NuvioMedia/NuvioMobile | `cmp-rewrite` | `d177eb57dfc8989e56de577bda8f4c2714b2bd64` (`bump version`, 0.4.17) | GPL-3.0 | `composeApp/`, `androidApp/`, `iosApp/`, `gradle/` |
| NuvioTV | https://github.com/NuvioMedia/NuvioTV | `dev` | `e7a335ebb38f41fe95e69a7592520d404c1326ae` | GPL-3.0 | not copied (TV-only) |
| nuvio-engine | https://github.com/NuvioMedia/nuvio-engine | default | `772f8c055049def5e87ae5ddb17b4ad15a71085a` | GPL-3.0-or-later | Android AAR only (`composeApp/libs/lib-nuvio-engine-android-0.1.1.aar`) |

Previous StreamBridge pins: NuvioMobile `465266d` (0.4.16), `e377942` (0.4.15).

See [`streambridge.version.properties`](../streambridge.version.properties),
[NOTICE](../NOTICE), and [UPSTREAM-SYNC.md](UPSTREAM-SYNC.md).
