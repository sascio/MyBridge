# Upstream baselines

StreamBridge’s runnable core **is** NuvioMobile at the pin below.
NuvioTV and nuvio-engine remain reference trees (not copied as source).

| Project | Clone URL | Branch (origin HEAD) | Commit | License | In this repo |
|---|---|---|---|---|---|
| NuvioMobile | https://github.com/NuvioMedia/NuvioMobile | `cmp-rewrite` | `e37794282b968812d059f8c77a9ea8a1eaa3b758` (`chore(store): publish 0.4.15`) | GPL-3.0 | `composeApp/`, `androidApp/`, `iosApp/`, `gradle/` |
| NuvioTV | https://github.com/NuvioMedia/NuvioTV | `dev` | `e7a335ebb38f41fe95e69a7592520d404c1326ae` | GPL-3.0 | not copied (TV-only) |
| nuvio-engine | https://github.com/NuvioMedia/nuvio-engine | default | `772f8c055049def5e87ae5ddb17b4ad15a71085a` | GPL-3.0-or-later | Android AAR only (`composeApp/libs/lib-nuvio-engine-android-0.1.1.aar`) |

See [NOTICE](../NOTICE) and [milestone-1.md](milestone-1.md).
The Milestone 0 mapping remains in [nuvio-core-audit.md](nuvio-core-audit.md).
