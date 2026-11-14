# Upstream baselines

StreamBridge’s runnable core (Android and, since 0.1.08, iOS) is derived from
NuvioMobile and its Enhanced feature overlay. The current verified pins are:

| Project | Clone URL | Ref | Commit | License | In this repo |
|---|---|---|---|---|---|
| NuvioMobile | https://github.com/NuvioMedia/NuvioMobile | tag `0.6.12-beta` (`cmp-rewrite`) | `4135f96cd29f235b7c412f78dcfc13164c4b1e80` | GPL-3.0 | `composeApp/`, `androidApp/`, `iosApp/`, `gradle/` |
| NuvioMobile-Enhanced | https://github.com/luqmanfadlli/NuvioMobile-Enhanced | tag `0.6.12-beta` | `ac86e820e76362284c9d7524bb523976df32e195` | GPL-3.0 | unique-feature overlay and fixes on the official core |
| NuvioTV | https://github.com/NuvioMedia/NuvioTV | `dev` | `e7a335ebb38f41fe95e69a7592520d404c1326ae` | GPL-3.0 | not copied (TV-only) |
| nuvio-engine | https://github.com/NuvioMedia/nuvio-engine | default | `772f8c055049def5e87ae5ddb17b4ad15a71085a` | GPL-3.0-or-later | Android AAR (`composeApp/libs/lib-nuvio-engine-android-0.1.2.aar`) and the iOS Apple XCFramework fetched at build time |
| MPVKit | https://github.com/NuvioMedia/MPVKit | `Nuvio` | fetched by `scripts/prepare-ios-dependencies.sh` (not vendored) | LGPL-3.0 | iOS playback local Swift package (`../MPVKit`) |

The exact Enhanced `0.6.12-beta` tag and commit were checked in
[`luqmanfadlli/NuvioMobile-Enhanced`](https://github.com/luqmanfadlli/NuvioMobile-Enhanced).
The official core tag `0.6.12-beta` is an ancestor of the Enhanced tag. This update integrates
source files only; it does not merge upstream Git history.

Previous StreamBridge pins: NuvioMobile `89f7d97b` (0.6.11-beta), `42e37ace` (0.6.9-beta),
`3e156936` (0.6.7-beta), `1cfae0fe` (0.6.2-beta), `79b750f3` (0.6.1-beta), `5c6028f2`
(0.5.5-beta), `bb3c1e4c` (0.5.2-beta), `b88fef2e` (0.5.1), `ec441c7b` (0.4.25-beta),
`d177eb57` (0.4.17), `465266d` (0.4.16), `e377942` (0.4.15); NuvioMobile-Enhanced
`254ae152` (0.6.11-beta), `01879330` (0.6.9-beta), `376f8619` (0.6.7-beta), `5d978096`
(0.6.2-beta), `504014d3` (0.6.1-beta), `8b3bd867` (0.5.5-beta), `3da8d06f` (0.5.2-beta),
`9d311c18` (0.5.1-beta), `ce4492ec` (0.4.23-beta), `d4a3b23` (0.4.16).

> History note: StreamBridge is an overlay copy and shares no git history with either upstream.
> The 0.1.10 sync (`51924933`) merged the Enhanced tree with a real unrelated-history merge,
> which imported 3,329 upstream commits into `main` and added their authors to GitHub's contributor
> statistics. The intended method remains a source-level integration, as described in
> [UPSTREAM-SYNC.md](UPSTREAM-SYNC.md). See [REPOSITORY-IDENTITY-AUDIT.md](REPOSITORY-IDENTITY-AUDIT.md).

See [`streambridge.version.properties`](../streambridge.version.properties), [NOTICE](../NOTICE),
and [UPSTREAM-SYNC.md](UPSTREAM-SYNC.md) for source pins, attribution, and per-file sync records.
