# Upstream baselines

StreamBridge’s runnable core **is** NuvioMobile at the pin below.

| Project | Clone URL | Branch | Commit | License | In this repo |
|---|---|---|---|---|---|
| NuvioMobile | https://github.com/NuvioMedia/NuvioMobile | `cmp-rewrite` | `b88fef2e655609443a9949e578c5267110be5fac` (`chore(store): publish 0.5.1`) | GPL-3.0 | `composeApp/`, `androidApp/`, `iosApp/`, `gradle/` |
| NuvioMobile-Enhanced | https://github.com/luqmanfadlli/NuvioMobile-Enhanced | `enhanced` | `9d311c18` (tag `0.5.1-beta`) | GPL-3.0 | unique-feature overlay on official 0.5.1 |
| NuvioTV | https://github.com/NuvioMedia/NuvioTV | `dev` | `e7a335ebb38f41fe95e69a7592520d404c1326ae` | GPL-3.0 | not copied (TV-only) |
| nuvio-engine | https://github.com/NuvioMedia/nuvio-engine | default | `772f8c055049def5e87ae5ddb17b4ad15a71085a` | GPL-3.0-or-later | Android AAR only (`composeApp/libs/lib-nuvio-engine-android-0.1.1.aar`) |

Previous StreamBridge pins: NuvioMobile `ec441c7b` (0.4.25-beta), `d177eb57` (0.4.17), `465266d` (0.4.16), `e377942` (0.4.15); NuvioMobile-Enhanced `ce4492ec` (0.4.23-beta), `d4a3b23` (0.4.16).

See [`streambridge.version.properties`](../streambridge.version.properties),
[NOTICE](../NOTICE), and [UPSTREAM-SYNC.md](UPSTREAM-SYNC.md).
