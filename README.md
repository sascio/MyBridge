# Stream Bridge

**A cinematic, addon- and plugin-driven media discovery & streaming client
for Android.**

Stream Bridge is an original, dark-first Android app for browsing and playing
media. It ships **completely empty** — no catalogs, no metadata, no streams,
no providers. You bring your own sources in two separate systems:
**addons** (Stremio-compatible HTTP protocol sources) and **plugins**
(Nuvio-compatible JavaScript providers that run locally in a sandboxed
engine). Everything you see in the app comes from sources you explicitly
install and enable.

> Stream Bridge provides no content of its own. Use only content you are
> legally entitled to access. The app does not bundle, host or recommend any
> content source.

---

## Features

### Discovery
- Cinematic **hero banner** — swipeable artwork with snap paging and
  pausable auto-rotation
- Rails built from every enabled addon catalog, plus merged
  **Movies** / **TV Shows** rails across sources
- **Continue Watching** with real resume positions
- **Recommended for you** — genre affinity from your watch history
- **Browse by genre** across all addons
- Recently added to your library
- Polished loading, empty and error states everywhere

### Search
- Real search with 350 ms debounce across all addons that declare a
  `search` extra (plus TMDB if you enable it)
- Separate movie / series result sections, retry on failure

### Details
- Full detail pages: backdrop, poster, year, runtime, rating, genres,
  overview, cast, related titles
- **Adaptive detail pages**: the dominant color of each title's artwork
  tints the page background — a Spotify-style gradient, cached per title,
  with readability-preserving color math (unit tested)
- **Series support**: seasons, episodes with thumbnails, overviews and
  per-episode watch progress
- Library actions: **favorites** and **watchlist** (persisted with Room)

### Player
- Real playback with **Media3 / ExoPlayer**
- Play/pause, seek, buffering indicator, fullscreen immersive mode
- Resume from persisted position
- **Next / previous episode** with an episode queue and autoplay
- Errors with retry; per-title **stream selection** sheet
- **Subtitles**: side-loaded from addons and the built-in Open Subtitles
  source (with per-source request headers), selectable in the player
- Playback positions and history persisted locally

### Addons (Stremio protocol)
- **Zero addons bundled, preinstalled or hidden** — the list starts empty
  (the only built-ins are the app's own Metadata resolution, always on,
  and Open Subtitles V3, which activates only with *your* credentials)
- Add any Stremio-compatible addon by manifest URL
- Manifest is **fetched and validated** before install (id, name, version,
  resources, catalogs)
- Enable / disable / remove / refresh individual addons, refresh all
- **Reorder** addons — order is the aggregation priority used across
  catalogs, search, streams, subtitles and metadata merging
- Community **addon catalogs** (`addon_catalog` resource): browse and
  install addons recommended by other addons you already trust
- Rich, honest error reporting for unreachable or invalid addons; one
  broken or slow addon never breaks browsing, search or playback

### Plugins (Nuvio-compatible, executed locally)
- **Zero plugins bundled, preinstalled or hidden** — starts empty
- Add a Nuvio-compatible plugin repository by manifest URL; its providers
  are discovered from the manifest
- Enable / disable providers per repository, refresh, remove — all choices
  persisted on-device
- Enabled providers run for movies **and** TV episodes
  (`getStreams(tmdbId, mediaType, season, episode)`) whenever you open a
  stream picker, and their results appear alongside addon streams in the
  same unified picker and player
- Per-provider failure isolation and timeouts: one broken plugin never
  affects the others, browsing or playback

**Plugin code is untrusted and treated that way.** Every call runs in a
fresh QuickJS instance (embedded via quickjs-kt) with:

- Memory, stack and execution-time limits; a hard request-count cap
- A `fetch` that only speaks plain HTTP GET/POST with per-request
  timeouts and response-size caps — no file, Android or Java access of
  any kind
- Controlled failures: a plugin error surfaces as a message, never a
  crash; unsupported providers are reported as unsupported

Stream Bridge distinguishes two kinds of sources in the UI — managed in
**Settings → Content & Discovery**, always separate:

| Concept | Where | What it is |
| --- | --- | --- |
| **Addon** | Addon screen | HTTP *protocol* sources (Stremio manifests, incl. Cloudstream repository browsing). Declarative, validated, safe to query. |
| **Plugin** | Plugin screen | Nuvio-compatible JavaScript *code* providers. Executed locally inside the sandboxed QuickJS engine. |

### Stremio-compatible architecture
The core speaks the open Stremio addon protocol:
`manifest.json`, `catalog/{type}/{id}[/{extras}].json`,
`meta/{type}/{id}.json`, `stream/{type}/{videoId}.json`,
`subtitles/{type}/{videoId}.json` and
`addon_catalog/{type}/{id}.json` — for movies, series and episodes, with
lenient parsing for real-world addon responses (camelCase and snake_case
fields, string/object cast lists, numeric ratings, episode
`name`/`description` title aliases, …).

Aggregation across many addons handles:

- **Priority** — your addon order (reorder in the Addon screen)
- **Deduplication** — identical streams/subtitles/metas collapse
- **Isolation** — timeouts and per-addon error containment
- **ID mapping** — IMDb (`tt…`) vs TMDB (`tmdb:…`) vs addon-local ids,
  including the `{imdb}:{season}:{episode}` convention
- **Metadata merging** — one authoritative meta (your preferred metadata
  addon or the source addon), gaps filled from others

Every stream is normalized into a unified model (classification
direct/torrent/external, resolution, quality, language, size, seeders)
and grouped by provider in the player's stream picker.

### Cloudstream compatibility — an honest limitation
Cloudstream *plugins* are compiled Kotlin (`.cs3`) code that must run
inside a Cloudstream fork. Executing them would be unsafe and is **not
implemented** — no fake install or play buttons. What Stream Bridge
offers instead is repository *browsing*: Cloudstream `repo.json`
descriptors can be added as read-only addon sources that list their
plugins (name, version, description, language, TV types, status).

Stremio addons and Nuvio-compatible *JavaScript* plugins, by contrast,
are fully supported — the former as declarative HTTP sources, the latter
inside the sandboxed engine described above.


### LAN bridge server & QR
- Optional local HTTP server exposes all your installed addons as one
  aggregated Stremio-compatible addon on your Wi-Fi: catalogs, details,
  streams **and subtitles**, merged across every enabled addon with
  URL-based deduplication
- Automatic LAN IPv4 detection (nothing hard-coded), automatic or custom
  port, network-change aware
- **QR code** of the addon URL plus copy/share actions, so a TV or desktop
  client can add your bridge in seconds

### Library (all on-device)
Favorites · Watchlist · Continue Watching · History · playback positions

### Optional integrations — OFF by default
- **TMDB** (trending/popular rails, search, metadata)
- **MDBList** (your lists as Home rails)

Both require *your own* API key, stored only on your device. No key is
bundled or committed, and the app works fully without them.

### Settings
Appearance (accent colors, pure-black OLED mode) · Playback (autoplay,
watched threshold) · **Content & Discovery** (separate Plugin and Addon
management) · Integrations · Network/LAN bridge · About & credits

---

## Roadmap

**Universal provider compatibility — in active development.** The
plugin sandbox is being upgraded into a full compatibility runtime so
that JavaScript providers written for *other* runtimes work unmodified
inside the same hardened sandbox.

Shipped so far:

- A dedicated provider HTTP engine: all standard methods, cookies
  scoped to the call, redirects, transparent gzip/deflate/brotli
  decoding, binary bodies both ways, per-request timeouts
- A compatibility layer in every provider execution: real CommonJS
  `require()` with built-in Node adapters (buffer, path, url,
  querystring, util, events, assert, stream, crypto, process, timers),
  `Buffer`, real-delay timers, `EventEmitter`, WebCrypto-shaped
  `crypto.subtle`, and a `crypto` module backed by the platform's
  javax.crypto (hashing, HMAC, AES-CBC/CTR/ECB/GCM, PBKDF2)
- Browser globals with real behavior: `window`/`self`, `navigator`,
  `location` derived from the provider's code URL, `document.cookie`
  backed by the HTTP engine's cookie jar, `<a>` URL parsing,
  `localStorage`/`sessionStorage` (per call, quota-enforced),
  `TextEncoder`, `AbortController` — and precise controlled errors for
  what genuinely cannot exist (DOM rendering, navigation, DOM events)
- **Real cheerio and node-forge**, bundled from the actual npm
  packages (browser builds) and shipped as app assets:
  `require('cheerio')`, `require('cheerio-without-node-native')` and
  `require('node-forge')` run the genuine libraries inside the sandbox
- Structured compatibility diagnostics (Status / Detected / Missing /
  Action) whenever a provider needs something unavailable, plus
  per-call static analysis logging and pre-fetching of relative modules

Remaining: ES module loading for providers, and live integration
testing against real provider repositories. Capability-based access
only — HTTP, HTML parsing, crypto and storage adapters; never files,
shells, process execution or arbitrary native libraries.

---

## Getting the APK

1. Go to the **Actions** tab of this repository
2. Open the latest successful **Build Stream Bridge** run
3. Download the **`Stream-Bridge-release-APK`** artifact — this is the
   optimized (R8, resource-shrunk) build and the one you should install
   (a `Stream-Bridge-debug-APK` is also available for debugging)
4. Unzip it and install `app-release.apk` on your Android 8.0+ device
   (enable "install unknown apps" if asked)

## Building from source

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
```

Requirements: JDK 17, Android SDK 36. The Gradle wrapper handles the rest.

## Project layout

```
app/src/main/java/com/streambridge/app/
├── addon/            Stremio-compatible engine: models, HTTP client,
│                     URL & manifest validation, extension manager,
│                     stream resolution — plus plugin/ (Nuvio manifest,
│                     store, sandboxed QuickJS runtime, manager)
├── core/             Time formatting, network monitoring
├── data/
│   ├── db/           Room database (extensions, library, watch progress)
│   ├── discovery/    Home aggregation, search, genre browse, merging
│   ├── integrations/ Optional TMDB + MDBList clients, OpenSubtitles V3
│   ├── library/      Favorites / watchlist / progress repository
│   └── settings/     DataStore-backed settings
├── player/           ExoPlayer holder, playback view model
├── server/           LAN bridge HTTP server + aggregation provider
├── di/               Manual dependency container
└── ui/               Compose UI: theme, components, home, search,
                     details, player, library, extensions (addons),
                     plugins, settings
```

## Security notes

- HTTPS certificate validation is never bypassed.
- Plain `http://` is allowed *only* so LAN-hosted addons and the local
  bridge can work; public addon hosts keep full TLS verification.
- Addon manifests and URLs are validated before install; nothing is
  installed or activated silently.
- No secrets, API keys or credentials are bundled with the app.
- **Controlled, sandboxed execution only**: Nuvio-compatible plugin code
  runs in an embedded QuickJS engine with no host access beyond a limited
  `fetch` — memory/stack/time limits, request caps, per-provider
  isolation. Cloudstream `.cs3` plugins are never run. Declarative HTTP
  addons (Stremio protocol) are only queried, never executed.
- The LAN bridge serves read-only JSON over GET/HEAD with CORS; it never
  executes downloaded code.

## Credits

Built with Jetpack Compose, Media3/ExoPlayer, Room, DataStore, OkHttp,
kotlinx.serialization, Coil, ZXing and quickjs-kt (embedded QuickJS;
Apache-2.0, QuickJS itself MIT). Stream Bridge implements the open Stremio
addon *protocol* and runs Nuvio-compatible plugins locally in a sandbox;
it is an independent project with original code, branding and artwork.

## License

MIT — see [LICENSE](LICENSE).
