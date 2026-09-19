# CloudStream compatibility in StreamBridge

## Summary

StreamBridge can discover CloudStream repositories, read their plugin
catalogues, and report precisely how compatible each extension is. It does
**not** execute CloudStream extensions, and it never pretends to.

## Why extensions cannot be executed

A CloudStream extension (`.cs3`) is a renamed ZIP containing exactly two
entries. Verified against a real published plugin
(`AllMovieLandProvider.cs3`, 96,001 bytes):

| Entry | Size | Contents |
|---|---|---|
| `manifest.json` | 131 B | `pluginClassName`, `name`, `version`, `requiresResources` |
| `classes.dex` | 242,188 B | Compiled Android DEX bytecode |

All provider behaviour — `search`, `load`, `loadLinks`, subtitle resolution,
header construction — exists **only** inside `classes.dex`. There is no
declarative description of endpoints or scraping rules.

Executing it would mean loading arbitrary remote bytecode into StreamBridge
with full application privileges: unreviewed code, no sandbox, updatable by
third parties at any time. StreamBridge does not do this, so no downloaded DEX
is ever loaded.

Consequently every real-world CloudStream plugin is reported as
`UNSUPPORTED` / `REQUIRES_NATIVE_EXECUTION`. This is the honest result, not a
defect.

## What is implemented

### Repository discovery (works today)

```
repo.json  ->  CloudStreamRepositoryManifest
                 name, description, manifestVersion, pluginLists[]
plugins.json -> CloudStreamPluginManifest[]
                 name, internalName, url, version, description, authors[],
                 status, tvTypes[], language, iconUrl, repositoryUrl,
                 fileSize, fileHash, apiVersion
```

Behaviour:

- Reuses StreamBridge's existing HTTP stack (`httpGetText`) — no second
  networking implementation, so redirects, gzip/deflate/Brotli and timeouts
  behave identically to the rest of the app.
- Unreachable or malformed repositories degrade to a `FAILED` repository with
  an actionable message; nothing throws into the caller.
- A single broken entry in `plugins.json` does not discard the rest of the
  list (element-wise salvage parse).
- One dead plugin list does not sink a repository that has others.
- Duplicate plugin ids collapse to the highest version.
- Unknown/future JSON fields are ignored rather than failing.
- Non-`http(s)` URLs are rejected before any request is made.
- Discovery **never** marks a plugin installed.

### Compatibility model

| State | Meaning |
|---|---|
| `COMPATIBLE` | Metadata complete and API version understood |
| `PARTIALLY_COMPATIBLE` | Usable but incomplete (e.g. no plugin lists) |
| `UNSUPPORTED` | Understood but not executable by StreamBridge |
| `FAILED` | Could not be parsed or reached |

Reasons: `NONE`, `REQUIRES_NATIVE_EXECUTION`, `UNSUPPORTED_API_VERSION`,
`INCOMPLETE_METADATA`, `MALFORMED_OR_UNREACHABLE`.

### Adapter layer (built and tested, no execution backend)

The full CloudStream → StreamBridge translation is implemented and unit-tested
against fixtures, because the mapping is the part that is easy to get subtly
wrong:

| CloudStream | StreamBridge |
|---|---|
| `SearchResponse` | `CloudStreamSearchResult` |
| `Episode` | `CloudStreamEpisode` |
| `ExtractorLink` | `StreamItem` |
| `SubtitleFile` | `StreamSubtitle` |

Header fidelity — the usual cause of silent playback failure — is handled
explicitly:

- `Referer` is preserved.
- Custom headers (e.g. `User-Agent`) are preserved and **override** the
  derived `Referer` when both are present.
- Cookies are folded into a single RFC 6265 `Cookie` header, and an explicit
  `Cookie` header is never clobbered.
- All of it lands in `StreamBehaviorHints.proxyHeaders.request`, which the
  existing StreamBridge player and HTTP layer already honour.

This means CloudStream-derived streams would flow through the **existing**
player — there is no separate CloudStream player — and HLS (`m3u8`), DASH and
direct HTTP are distinguished via `streamType`.

`CloudStreamPluginExecutor` is the deliberately-unimplemented seam where a
future *sanctioned* execution backend would plug in. It has no production
implementation. With no executor present, `resolveStreams` returns an empty
list: no fabricated sources, ever.

### Failure isolation

`CloudStreamSourceProvider` resolves each plugin independently and contains
every failure. A plugin that throws yields an empty result for that plugin
only; other providers keep working and the aggregator never sees an exception.

## Tests

`composeApp/src/commonTest/.../cloudstream/`

- `CloudStreamRepositoryParserTest` — real-schema parsing, malformed JSON,
  partial damage, unknown fields, identity/API-version classification.
- `CloudStreamRepositoryLoaderTest` — load success, unreachable repo,
  unreachable plugin list, empty lists, future manifest version, URL
  validation, deduplication, never-installed/never-executable invariants.
- `CloudStreamProviderAdapterTest` — Referer/User-Agent/cookie preservation,
  header precedence, HLS/DASH/HTTP typing, quality labels, subtitle mapping,
  blank-URL rejection.
- `CloudStreamSourceProviderTest` — no-backend safety, unsupported plugins
  never executed, failure isolation, multi-plugin aggregation.

All network dependencies are injected; no test performs real I/O.
