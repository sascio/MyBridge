# CloudStream metadata and live-response audit

Date: 2026-09-27

## Finding

The working CloudStream runtime was not the metadata problem. The Android full
runtime already called `MainAPI.load()` and `MainAPI.loadLinks()`, but the
normalization seam only retained `dataUrl`/episode data and `ExtractorLink`
fields. A `LiveStreamLoadResponse` was therefore reduced to a URL during
resolution; its title, poster, logo, description, provider name, type and other
`LoadResponse` fields never crossed into a StreamBridge model. There was also no CloudStream homepage/catalog path at all, so live providers
could execute but could not contribute entries to the Live TV catalog. The
runtime now adds a separate CloudStream-to-Live-TV path; it does not pretend
that a CloudStream extension is a Nuvio/Stremio addon.

The loss points were:

- `CloudStreamLoadResponseTargets.movieTarget()` retained only the playable
  payload. It did not expose a normalized metadata object.
- `AndroidCloudStreamExecutor.resolve()` called `collectLinks()` and returned
  only links/subtitles.
- `CloudStreamProviderAdapter.adaptLinks()` created `StreamItem` without
  response metadata.
- `LiveTvRepository` only loaded M3U, Xtream and Stalker sources; it never
  asked CloudStream providers for a homepage/catalog.

## Actual AAR contract

The pinned AAR is `cloudstream-runtime-api-4.8.0-3496e5f.aar`, built from
CloudStream commit `3496e5f8d2ebae4c1b5bdf264782f58375c1eb06`. The actual
`LoadResponse` interface contains:

- `name`, `url`, `apiName`, `type`, `posterUrl`, `backgroundPosterUrl`,
  `logoUrl`, `posterHeaders`
- `plot`, `year`, `score`/legacy rating, `duration`, `tags`, `contentRating`
- `actors`, `trailers`, `recommendations`, `syncData`, `comingSoon`,
  `uniqueUrl`

The concrete payload fields are:

| AAR type | Payload field(s) | Additional shape |
| --- | --- | --- |
| `MovieLoadResponse` | `dataUrl` | one playable target |
| `LiveStreamLoadResponse` | `dataUrl` | same metadata contract as movie; `type` is normally `Live` |
| `TorrentLoadResponse` | `magnet` / `torrent` | torrent payload |
| `TvSeriesLoadResponse` | `episodes: List<Episode>` | episode data, season/episode, poster, description, runtime |
| `AnimeLoadResponse` | `episodes: Map<DubStatus, List<Episode>>` | parallel subbed/dubbed/none lists; also English/Japanese title fields |

`LiveStreamLoadResponse` itself has **no HTTP headers, referer, cookies or
subtitle collection**. Those fields are supplied by the subsequent
`loadLinks` callbacks as `ExtractorLink` and `SubtitleFile`. The implementation
keeps that distinction: response metadata is normalized separately and link
metadata remains in `StreamItem.behaviorHints.proxyHeaders` and
`externalSubtitles`.

## Mapping matrix

| CloudStream field | Normalized model | StreamBridge destination | Status |
| --- | --- | --- | --- |
| name / English/Japanese title | `CloudStreamResponseMetadata.title/originalTitle` | `StreamMediaMetadata`, `StreamItem.mediaMetadata` | preserved |
| poster / backdrop / logo | `poster/backdrop/logo` | `StreamMediaMetadata`; live channel artwork | preserved |
| poster headers | `posterHeaders` | normalized metadata | preserved; not incorrectly used as playback headers |
| plot | `description` | normalized metadata and live player/channel | preserved |
| year / score / duration | typed normalized fields | `StreamMediaMetadata` | preserved |
| tags | `tags` and `genres` fallback | `StreamMediaMetadata` | preserved |
| actors | `StreamCastMember` | `StreamMediaMetadata.cast` | preserved |
| apiName | `providerName` | source name, live channel, player | preserved |
| url / uniqueUrl | `url/uniqueUrl` | normalized metadata and catalog identity | preserved |
| TvType | `mediaType`, `isLive` | stream/live model | preserved |
| Live `dataUrl` | `dataUrl` | live resolution target | preserved |
| Movie `dataUrl` | `dataUrl` | existing stream resolution | preserved |
| torrent / magnet | target selection | existing torrent path | preserved |
| episode season/number/data | episode refs/metadata | existing selector and link resolution | preserved |
| ExtractorLink URL/type | `CloudStreamLink` → `StreamItem` | URL + `http`/`hls`/`dash`/`torrent` | preserved |
| ExtractorLink quality | quality label | source picker description | preserved |
| ExtractorLink headers/referer/cookies | request header merge | `behaviorHints.proxyHeaders.request` | preserved |
| SubtitleFile URL/lang/headers | `StreamSubtitle` | player subtitle inputs | preserved |

There is no special check for an extension, provider, channel name, URL host,
or service. `LiveStreamLoadResponse` is selected by its actual response type
and all generic fields are copied.

## Live flow after the change

```text
Installed and enabled CloudStream MainAPI (generic catalog capability)
  -> MainAPI.getMainPage / HomePageResponse
  -> MainAPI.load / actual LiveStreamLoadResponse when available
  -> CloudStreamLiveCatalogItem
  -> existing Live TV catalog and channel cards
  -> MainAPI.load / LiveStreamLoadResponse
  -> CloudStreamResponseMetadata + ExtractorLink/SubtitleFile
  -> LiveTvChannel / request headers / stream type
  -> existing PlayerLaunch and Media3/mpv player
```

The Android full runtime also accepts an authoritative direct HTTP `dataUrl`
from a live response when `loadLinks` emits no links. This is not fabricated
metadata: it is the response's own payload, and it prevents valid extensionless,
redirecting, HLS or DASH URLs from being rejected. Link-provided headers and
referer still take precedence when they exist.

## Validation

Host tests instantiate the actual AAR `MovieLoadResponse`,
`TvSeriesLoadResponse`, `AnimeLoadResponse`, `LiveStreamLoadResponse` and
`TorrentLoadResponse` classes. Common tests cover metadata attachment, multiple
sources, HLS/DASH, extensionless URLs, headers, referer, cookies, subtitles,
empty metadata and invalid URLs.

A physical device and a real CNCVerse/SKTechProvider session were not available
in this environment. The runtime path is unchanged except for the generic
catalog/metadata calls described above; no provider-specific test fixture or
production branch was added.
