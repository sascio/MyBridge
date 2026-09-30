# CloudStream integration architecture

Date: 2026-09-30
Branch: `arena/01a0e422-mybridge`

This document describes the boundary between CloudStream's Android runtime and
Nuvio's common UI. It is an architecture record, not proof that a particular
third-party extension or stream played. Runtime and device evidence is tracked
in `docs/CLOUDSTREAM-VALIDATION-MATRIX.md`.

## Design goals

- Discover providers from the registered CloudStream runtime (`APIHolder` and
  loaded `MainAPI` instances), never from a provider-name allowlist.
- Keep extension, repository, registered provider, section, category and
  channel identity separate from display labels.
- Preserve the order and depth of the provider's homepage navigation while
  retaining the original detail URL for `load()` and the existing `loadLinks`
  path for playback.
- Isolate repositories, extensions and individual provider failures.
- Keep VOD, series, anime, search, subtitles, headers, cookies, stream types and
  the existing StreamBridge player path unchanged outside the adapter boundary.

## Runtime boundary

```text
CloudStream repository metadata
  -> CloudStreamExtensionsRepository
  -> verified private .cs3 package
  -> CloudStreamPlatformRuntime (Android full distribution)
  -> BasePlugin / Plugin.load(host AppCompatActivity)
  -> APIHolder.allProviders / extractorApis
  -> registered MainAPI instances
  -> CloudStreamExecution normalized contracts
  -> CloudStreamLiveHierarchy + LiveTvRepository
  -> existing Live TV controls and player
```

`androidFull` is the only source set allowed to execute `.cs3` code. Package
hash/archive/class validation, private storage, read-only package files,
classloader boundaries, Activity validation, and R8 rules remain security
boundaries. Play Store and iOS source sets do not execute arbitrary DEX.

The executor receives the real live `AppCompatActivity`, not a context wrapper.
It publishes the same host to CloudStream's `CommonActivity`, global application
context and API context immediately before loading or invoking a provider. A
missing or incompatible host is an explicit failure; it is not converted into
an empty provider list.

## Identity model

There are four different kinds of identity:

| Identity | Source | Use |
| --- | --- | --- |
| Repository | configured repository URL / repository provenance | separates equal extensions from different repositories when normalized catalog data is projected |
| Extension | manifest `internalName` and the existing extension state key | installation, enablement and package lifecycle |
| Provider | registered `MainAPI` class, name and `mainUrl`, hashed by `CloudStreamLiveIdentity.providerId` | selects the exact MainAPI when an extension registers several providers |
| Node/channel | parent node, stable identities, title, ordinal and original URL | navigation and favorite/playback-safe Live TV keys |

Names are presentation fields only. The runtime may use a display name as a
legacy fallback for old normalized test backends, but Android items carry the
stable provider ID and resolution first matches that ID.

## Navigation model

`CloudStreamLiveHierarchy` is the common representation of the runtime-shaped
Live TV tree:

```text
EXTENSION
  PROVIDER (registered MainAPI)
    SECTION
      CATEGORY
        CHANNEL (normalized homepage item + original detail URL)
```

The section/category levels are recursive and do not have a fixed maximum
depth. Common fallback normalization uses `HomePageResponse.items`,
`HomePageList.name`, and the provider's `SearchResponse` URLs. Shared prefixes
are merged; sibling paths remain separate. A channel is not claimed playable
just because its homepage URL exists. Selecting it preserves the normalized
item, and `LiveTvRepository.prepareForPlayback` calls the owning provider's
`load()` and `loadLinks`/extractor path before handing the resolved link to the
existing player.

The Live TV screen exposes the discovered extension/provider/child nodes before
the existing All Channels, Favourites, category and search controls. Selecting
a node scopes only CloudStream channels; M3U, Xtream and Stalker channels remain
on their existing paths. Once a channel is reached, the existing favorite,
recent-channel, request-header, subtitle and stream-type handling remains in
place.

## Failure isolation

- Repository discovery is isolated per configured repository.
- Provider initialization is isolated per registered `MainAPI`; a failing
  provider is removed from the runtime registry while other providers continue.
- Catalog and playback diagnostics retain provider and stage information.
- A failed live resolution never falls back to playing the detail/catalog URL.
- No channel, provider or link is fabricated when CloudStream returns no data.

A provider with no homepage capability is not synthesized into a tree. The
catalog-only `CloudStreamLiveHierarchy.fromCatalog` method is explicitly a
fallback for normalized items; it cannot represent a provider that returned no
items or richer actions not exposed by the current normalized contract. Android
runtime work should extend the normalized contract when a CloudStream runtime
response exposes additional child/action payloads rather than hiding that data
in a UI label.

## Compatibility preservation

CloudStream VOD, series and anime resolution still uses the existing generic
request flow: provider search or catalog item, `load`, episode selection,
`loadLinks`, extractor fallback where the payload is an actual URL, and
`CloudStreamProviderAdapter`. Link type, quality, referer, headers, cookies and
subtitles are normalized into the existing StreamBridge source/player models.
Live resolution uses the same provider execution backend and does not introduce
a second player implementation.

Multiple enabled extensions are aggregated through the existing source
selection layer. Provider failures are returned as per-target failures and do
not erase successful sources from other extensions.

## Evidence policy

Source inspection, unit tests, a build, CI and a device test are different kinds
of evidence. This checkout currently has no Java runtime available for a local
Gradle run and no post-change device/emulator result; those are reported as
blocked rather than marked passed. A completion claim requires, at minimum:

1. focused hierarchy/identity and existing CloudStream tests execute;
2. Android full debug/release artifacts build and the pinned AAR is inspected;
3. a configured real extension is discovered and initialized on a device;
4. homepage navigation reaches a channel and `loadLinks` returns a real link;
5. the existing player starts with the returned type, headers/referer and
   subtitles where supplied;
6. VOD, series/anime, search and existing M3U/Xtream/Stalker/Nuvio/Stremio
   regression paths remain evidenced.
