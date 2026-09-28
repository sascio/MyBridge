# CloudStream metadata and live-response audit

Date: 2026-09-29

## Finding

The working CloudStream runtime was not the metadata problem. The Android full
runtime already called `MainAPI.load()` and `MainAPI.loadLinks()`, but the
normalization seam only retained `dataUrl`/episode data and `ExtractorLink`
fields. A `LiveStreamLoadResponse` was therefore reduced to a URL during
resolution; its title, poster, logo, description, provider name, type and other
`LoadResponse` fields never crossed into a StreamBridge model. There was also
no CloudStream homepage/catalog path at all, so live providers could execute
but could not contribute entries to the Live TV catalog. The runtime now adds
separate CloudStream homepage-to-Home and CloudStream-to-Live-TV paths; it does
not pretend that a CloudStream extension is a Nuvio/Stremio addon. Homepage
items retain a process-local provider identity so the existing detail, source,
and player routes can resolve the provider-owned URL directly.

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

## Catalog flow after the change

```text
Installed and enabled CloudStream MainAPI
  -> MainAPI.getMainPage / HomePageResponse
  -> MainAPI.load / LoadResponse metadata
  -> CloudStreamCatalogItem
  -> separate CloudStream Home rows and existing detail screen
  -> CloudStream source adapter / direct provider URL
  -> existing PlayerLaunch and Media3/mpv player
```

The Nuvio/Stremio Home empty state remains scoped to that addon domain. A
CloudStream row is a separate, real UI boundary and is not inserted into addon
settings or represented as a fake addon.

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

## Activity/Context compatibility audit

The source-level audit identified the next failure after discovery: a provider
reached `MainAPI.getMainPage()` and then attempted to use an
`androidx.appcompat.app.AppCompatActivity`, but received an obfuscated runtime
object reported as `R81`. The previous runtime retained the real Activity only
for `CommonActivity.activity` while constructing and exposing a
`CloudStreamIdentityContext` wrapper around the application context to plugin
code and CloudStream's global `app` context. A ContextWrapper is not an
AppCompatActivity, even when its base context ultimately belongs to one.

The StreamBridge source host is `com.nuvio.app.MainActivity :
AppCompatActivity`. Every manifest launcher alias (`AppIconDefault`,
`AppIconArcticBlue`, `AppIconEmerald`, `AppIconRoseGold`, `AppIconCopper`, and
`AppIconGraphite`) subclasses that host, so the source Activity hierarchy is
AppCompat-compatible. R8 obfuscation can rename the concrete class to a name
such as `R81`; it cannot change its superclass hierarchy. The full-build rules
now keep the host and launcher names as well as the CloudStream ABI so a device
report can identify the concrete hierarchy without confusing obfuscation with
an incompatible superclass.

The fix publishes the actual verified `AppCompatActivity` instance before any
provider is loaded, assigns that same instance to `CommonActivity.activity`,
sets CloudStream's global `app` context to that Activity, and passes the actual
Activity to `Plugin.load(Context)`. There is no ContextWrapper fallback: a
background caller or a destroyed Activity receives an explicit
`CloudStreamActivityRequiredException` before provider execution. Runtime
diagnostics record the concrete class and superclass chain; a provider's
`ClassCastException` remains visible if it has another incompatible assumption.

The source/runtime contract is covered by an AppCompat host test, minification
keep-rule tests, and the existing full/Play Store boundary checks. The actual
installed SKTechProvider still requires a fresh APK on a physical device to
confirm that the R81 object now has the expected AppCompat superclass and that
`getMainPage()` returns items.

## Persistence, failure visibility and audit corrections

The previous device report exposed two observability defects: a catalog/runtime
exception was converted to an empty list before Home or Live TV could explain
it, and a failed repository refresh replaced the installed-extension cache.
The current implementation now:

- restores verified packages from private storage before asynchronous discovery;
- retains installed cached extensions when a repository is temporarily
  unreachable or omits an already-installed entry;
- records discovery, installation, classloader, provider-init,
  `getMainPage`, `search`, `load`, `loadLinks`, normalization, UI and playback
  outcomes in a bounded in-memory diagnostic journal;
- redacts URLs and credential-shaped values before diagnostics are shown; and
- shows the latest classified failure on Home/Live TV and the full recent
  journal on the CloudStream Extensions screen instead of presenting an
  unexplained empty success.

The homepage fallback also now derives `isLive`, `mediaType`, `liveStatus`, and
`channelName` from the actual response classification. A non-live item that
lacks detail metadata is no longer mislabeled as a live channel.

## Validation status

Host/common tests cover response normalization, source identity, persistence
policy, diagnostics redaction, multiple sources, HLS/DASH, extensionless URLs,
headers, referer, cookies, subtitles, empty metadata and invalid URLs. The
pinned AAR API surface and APK boundary tests remain in place. Local Android
execution is unavailable in this environment because Java and `adb` are not
installed.

A real Android device has not yet been attached to this checkout, so discovery,
restart restoration, provider construction, `getMainPage`, `load`,
`loadLinks`, source-picker visibility and Media3/mpv playback remain **pending
physical-device validation**. No provider or channel is hardcoded and no
success is claimed from compilation, unit tests or CI alone. The diagnostic APK
must be installed and exercised with multiple real extensions, including
AllMovieLand, a movie/series/anime provider, CNCVerse, SKTechProvider and a
second live provider where available; the resulting device diagnostics and
playback observations are the acceptance evidence.
