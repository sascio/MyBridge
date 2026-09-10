# Upstream baselines

These are the official Nuvio trees inspected for StreamBridge’s
Nuvio-core foundation. Do not treat this file as “Nuvio is already
inside StreamBridge.” It records **what was read**, not what was
copied.

| Project | Clone URL | Branch (origin HEAD) | Commit | License |
|---|---|---|---|---|
| NuvioMobile | https://github.com/NuvioMedia/NuvioMobile | `cmp-rewrite` | `e37794282b968812d059f8c77a9ea8a1eaa3b758` (`chore(store): publish 0.4.15`) | GPL-3.0 |
| NuvioTV | https://github.com/NuvioMedia/NuvioTV | `dev` | `e7a335ebb38f41fe95e69a7592520d404c1326ae` | GPL-3.0 |
| nuvio-engine | https://github.com/NuvioMedia/nuvio-engine | default | `772f8c055049def5e87ae5ddb17b4ad15a71085a` (`fix: make disk cache pressure nonfatal`) | GPL-3.0-or-later |

Inspected on 2026-09-11 from shallow clones. MPVKit in NuvioMobile is an
empty directory in the shallow tree (submodule / iOS native kit — not
required for the Android phone target).

See [nuvio-core-audit.md](nuvio-core-audit.md) for the component mapping.
