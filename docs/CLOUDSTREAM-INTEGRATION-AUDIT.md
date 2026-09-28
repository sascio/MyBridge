# CloudStream end-to-end integration audit

Date: 2026-09-29
Branch: `arena/01a0e422-mybridge`

This is the source-level audit for the full Android CloudStream path. It is
separate from the physical-device validation record: a code path is not marked
PASS here merely because it compiles.

## Execution path

```text
repo.json / plugins.json
  -> CloudStreamRepositoryLoader
  -> CloudStreamRepositoryParser
  -> installed .cs3 in app-private storage
  -> SHA-256/archive/plugin-class validation
  -> PathClassLoader (Android full distribution only)
  -> BasePlugin / Plugin.load
  -> APIHolder.allProviders + extractorApis
  -> CloudStreamPlatformRuntime.AndroidCloudStreamExecutor
  -> getMainPage/search
  -> LoadResponse normalization
  -> CloudStream catalog/detail or title/episode resolution
  -> MainAPI.loadLinks / generic extractor fallback
  -> CloudStreamProviderAdapter
  -> StreamItem / LiveTvChannel
  -> existing Media3/mpv player
```

The Play Store and iOS source sets intentionally stop before package execution.
M3U, Xtream, Stalker, Nuvio and Stremio remain separate source families and
are merged only by their existing repositories/aggregators.

## Root-cause matrix

| Area | Symptom | Root cause found | Affected scope | Corrective direction |
| --- | --- | --- | --- | --- |
| Activity/context | `R81` cannot cast to `AppCompatActivity` | The old dynamic-plugin path passed `CloudStreamIdentityContext` (an obfuscated `ContextWrapper`) instead of the real host Activity | Any provider using AppCompat/UI helpers; SKTech exposed it first | Require and pass the verified `MainActivity`/launcher `AppCompatActivity`; fail explicitly when no live compatible host exists |
| Runtime initialization | `CommonActivity.activity` was absent or inconsistent | Activity publication happened too late and global CloudStream context could point at a different wrapper | UI-bound providers and WebView/Cloudflare helpers | Publish the same weak real Activity during `MainActivity.onCreate` and before plugin load |
| Provider discovery | Installed providers disappeared after restart or repository outage | Repository refresh was treated as the only discovery source | All installed extensions | Restore verified installed packages and cached manifests before asynchronous discovery; retain them on transient refresh failure |
| Provider isolation | One bad provider could look like a global empty catalog/source result | Catalog and source orchestration did not retain per-provider failure context | Multiple extensions and source picker | One target/coroutine/result per provider; diagnostics carry provider and stage; preserve other results |
| Plugin lifecycle | Updates/reloads could leave old global registrations in `APIHolder`/extractors | CloudStream registries are process-global | Updates and multiple extensions | Serialize load/unload, track registrations, and unregister before replacement |
| Main page/catalog | Homepage content was unavailable to the common UI | The backend exposed only search/source resolution | All homepage/catalog-capable providers | Add generic `getMainPage` execution and normalized catalog projection |
| Catalog sections | Multiple sections could be flattened into one source row | The response was reduced directly to a flat list | SKTech and any multi-section provider | Carry section/category identity into catalog source keys and Live TV groups; do not identify categories by provider name |
| Detail metadata | Catalog cards lost provider LoadResponse metadata | The internal model had no CloudStream metadata payload | Movies, series, anime, live | Preserve titles, artwork, plot, score, type, episodes, recommendations, trailers and provider identity |
| LoadResponse shapes | Anime/live/torrent responses could resolve as empty | Handling was too narrow and assumed one response shape | Anime, live channels, torrent-backed content | Normalize Movie, LiveStream, Torrent, TvSeries and Anime responses generically |
| Series/anime episodes | Season/episode links were missing or ambiguous | Episode lists differ by response type; anime exposes dub variants | Series/anime | Normalize all episode lists and select by explicit season/episode with deterministic dub preference |
| Source resolution | `loadLinks` could produce no visible source even when an extractor was expected | Some providers return an embed URL and rely on CloudStream extractor registry | Generic movie/series/live providers | Invoke provider `loadLinks`, then registry-driven extractor fallback only for URL-shaped payloads |
| Link metadata | Streams failed behind CDN checks | Referer, cookies and custom headers were lost between CloudStream and player | HLS/DASH/direct/live sources | Preserve headers, referer, cookies and subtitles in StreamBridge source models |
| Stream type | Extensionless HLS/DASH was treated as plain HTTP | Stream type was inferred only from URL suffix in some paths | Extensionless live/VOD sources | Preserve CloudStream link type and pass `hls`/`dash` to the existing player |
| Live fallback | A failed live resolution could try to play the catalog/detail URL | Player caller used `getOrDefault(channel)` after resolution failure | CloudStream Live TV | Surface the error and do not hand an unplayable detail URL to the player |
| Error visibility | Empty UI states were indistinguishable from successful empty responses | Failures were converted to empty lists without a stage journal | Discovery, catalog, source, playback | Bounded redacted diagnostics plus provider-specific UI error text; no fake data |
| R8/minification | Dynamic providers can fail only in release | DEX resolves host symbols by original JVM name; R8 cannot infer those references | Full release APK | Keep only measured CloudStream ABI/dependencies and verify mapping plus merged DEX |
| Security | Dynamic package execution is a trust boundary | `.cs3` is attacker-influenced code | Full sideload distribution | Keep hash/archive/class validation, private storage, read-only commit and isolated class loading; do not broaden it for one provider |

## Audit conclusions before implementation

- `CommonActivity` has one production writer: the full Android runtime. The
  host declaration is `MainActivity : AppCompatActivity`; launcher aliases
  subclass it.
- The executor stores application context for storage/executor lifetime but must
  pass the real weak Activity to plugin/resource/UI code.
- `APIHolder`, `extractorApis`, `PluginManager` and video-click actions are
  global CloudStream registries. Loading must be serialized and unloading must
  remove only registrations belonging to the selected package.
- The generic aggregator must key state by extension/provider identity, not
  display name or URL alone.
- `HomePageResponse.items` supplies section names and lists. The normalized
  catalog may be flat internally, but its source key and UI section/group must
  retain the actual section identity. No SKTech names are valid routing keys.
- A `LiveStreamLoadResponse` carries a provider payload (`dataUrl`) and generic
  LoadResponse metadata; request headers/referer/subtitles belong to subsequent
  `loadLinks`/extractor results.
- The current source-model/player path already has request headers, subtitles,
  stream type and Media3/mpv routing hooks; CloudStream must populate those
  fields rather than create another player path.
- Physical device results are tracked separately below and remain pending until
  an APK is installed and the provider matrix is exercised.

## Validation policy

Only the following count as PASS:

- the focused test actually executes;
- the debug/release artifact actually builds and is inspected;
- a provider operation actually runs on a physical device;
- playback actually starts with the preserved request metadata.

A source-level mapping or a CI result cannot be upgraded to a physical-device
PASS. The current conservative status for every required provider, build and
device row is recorded in `docs/CLOUDSTREAM-VALIDATION-MATRIX.md`; no provider
or playback row is marked PASS without execution evidence.
