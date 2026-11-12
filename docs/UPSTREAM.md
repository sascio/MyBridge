# Upstream baselines

StreamBridge’s runnable core (Android and, since 0.1.08, iOS) **is**
NuvioMobile at the pin below.

| Project | Clone URL | Branch | Commit | License | In this repo |
|---|---|---|---|---|---|
| NuvioMobile | https://github.com/NuvioMedia/NuvioMobile | `cmp-rewrite` | `89f7d97b66910ecf8a75fd71a24e262320f0c6be` (tag `0.6.11-beta`) | GPL-3.0 | `composeApp/`, `androidApp/`, `iosApp/`, `gradle/` |
| NuvioMobile-Enhanced | https://github.com/yesnt10/NuvioMobile-Enhanced | `enhanced` | `254ae152fe94a2541c5a32b897a814594363d97f` (0.6.11-beta source commit; official 0.6.11-beta is an ancestor) | GPL-3.0 | unique-feature overlay on official 0.6.11-beta |
| NuvioTV | https://github.com/NuvioMedia/NuvioTV | `dev` | `e7a335ebb38f41fe95e69a7592520d404c1326ae` | GPL-3.0 | not copied (TV-only) |
| nuvio-engine | https://github.com/NuvioMedia/nuvio-engine | default | `772f8c055049def5e87ae5ddb17b4ad15a71085a` | GPL-3.0-or-later | Android AAR (`composeApp/libs/lib-nuvio-engine-android-0.1.2.aar`) and the iOS Apple XCFramework fetched at build time |
| MPVKit | https://github.com/NuvioMedia/MPVKit | `Nuvio` | fetched by `scripts/prepare-ios-dependencies.sh` (not vendored) | LGPL-3.0 | iOS playback local Swift package (`../MPVKit`) |

Previous StreamBridge pins: NuvioMobile `42e37ace` (0.6.9-beta), `3e156936` (0.6.7-beta), `1cfae0fe` (0.6.2-beta), `79b750f3` (0.6.1-beta), `5c6028f2` (0.5.5-beta), `bb3c1e4c`
(0.5.2-beta), `b88fef2e` (0.5.1), `ec441c7b` (0.4.25-beta), `d177eb57`
(0.4.17), `465266d` (0.4.16), `e377942` (0.4.15); NuvioMobile-Enhanced `01879330` (0.6.9-beta), `376f8619` (0.6.7-beta), `5d978096` (0.6.2-beta), `504014d3` (0.6.1-beta), `8b3bd867` (0.5.5-beta), `3da8d06f` (0.5.2-beta), `9d311c18` (0.5.1-beta),
`ce4492ec` (0.4.23-beta), `d4a3b23` (0.4.16).

> History note: StreamBridge is an overlay copy and shares no git history
> with either upstream. The 0.1.10 sync (`51924933`) merged the Enhanced
> tree with a real unrelated-history merge, which imported 3,329 upstream
> commits into `main` and added their authors to GitHub's contributor
> statistics. The intended method remains a squashed source-level
> integration, as described in [UPSTREAM-SYNC.md](UPSTREAM-SYNC.md). See
> [REPOSITORY-IDENTITY-AUDIT.md](REPOSITORY-IDENTITY-AUDIT.md).

The `0.6.11-beta` tag ref was verified in the project's former upstream repository
(`luqmanfadlli/NuvioMobile-Enhanced`) and resolves to `254ae152fe94a2541c5a32b897a814594363d97f`; the exact commit is also retrievable from the requested `yesnt10/NuvioMobile-Enhanced` repository, where the tag ref is not advertised. The official `0.6.11-beta` commit is an ancestor of that Enhanced commit.

See [`streambridge.version.properties`](../streambridge.version.properties),
[NOTICE](../NOTICE), and [UPSTREAM-SYNC.md](UPSTREAM-SYNC.md).
