# Stream Bridge

**A cinematic, extension-driven media discovery & streaming client for Android.**

Stream Bridge is an original, dark-first Android app for browsing and playing
media. It ships **completely empty** — no catalogs, no metadata, no streams,
no providers. You bring your own sources by installing **Stremio-compatible
extensions** (addons). Everything you see in the app comes from extensions
you explicitly install and enable.

> Stream Bridge provides no content of its own. Use only content you are
> legally entitled to access. The app does not bundle, host or recommend any
> content source.

---

## Features

### Discovery
- Cinematic **hero banner** with auto-rotating featured titles
- Rails built from every enabled extension catalog, plus merged
  **Movies** / **TV Shows** rails across sources
- **Continue Watching** with real resume positions
- **Recommended for you** — genre affinity from your watch history
- **Browse by genre** across all extensions
- Recently added to your library
- Polished loading, empty and error states everywhere

### Search
- Real search with 350 ms debounce across all extensions that declare a
  `search` extra (plus TMDB if you enable it)
- Separate movie / series result sections, retry on failure

### Details
- Full detail pages: backdrop, poster, year, runtime, rating, genres,
  overview, cast, related titles
- **Series support**: seasons, episodes with thumbnails, overviews and
  per-episode watch progress
- Library actions: **favorites** and **watchlist** (persisted with Room)

### Player
- Real playback with **Media3 / ExoPlayer**
- Play/pause, seek, buffering indicator, fullscreen immersive mode
- Resume from persisted position
- **Next / previous episode** with an episode queue and autoplay
- Errors with retry; per-title **stream selection** sheet
- Playback positions and history persisted locally

### Extensions
- **Zero extensions bundled, preinstalled or hidden** — the list starts empty
- Add any Stremio-compatible addon by manifest URL
- Manifest is **fetched and validated** before install (id, name, version,
  resources, catalogs)
- Enable / disable / remove / refresh individual extensions, refresh all
- Rich, honest error reporting for unreachable or invalid addons

### Stremio-compatible architecture
The core speaks the open Stremio addon protocol:
`manifest.json`, `catalog/{type}/{id}[/{extras}].json`,
`meta/{type}/{id}.json`, `stream/{type}/{videoId}.json` — for movies,
series and episodes, with lenient parsing for real-world addon responses
(camelCase and snake_case fields, string/object cast lists, numeric
ratings, …).

### LAN bridge server & QR
- Optional local HTTP server exposes all your installed extensions as one
  aggregated Stremio-compatible addon on your Wi-Fi
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
watched threshold) · Extensions · Integrations · Network/LAN bridge ·
About & credits

---

## Getting the APK

1. Go to the **Actions** tab of this repository
2. Open the latest successful **Build Stream Bridge** run
3. Download the **`Stream-Bridge-debug-APK`** artifact
4. Unzip it and install `app-debug.apk` on your Android 8.0+ device
   (enable "install unknown apps" if asked)

## Building from source

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
```

Requirements: JDK 17, Android SDK 35. The Gradle wrapper handles the rest.

## Project layout

```
app/src/main/java/com/streambridge/app/
├── addon/            Stremio-compatible engine: models, HTTP client,
│                     URL & manifest validation, extension manager,
│                     stream resolution
├── core/             Time formatting, network monitoring
├── data/
│   ├── db/           Room database (extensions, library, watch progress)
│   ├── discovery/    Home aggregation, search, genre browse, merging
│   ├── integrations/ Optional TMDB + MDBList clients
│   ├── library/      Favorites / watchlist / progress repository
│   └── settings/     DataStore-backed settings
├── player/           ExoPlayer holder, playback view model
├── server/           LAN bridge HTTP server + aggregation provider
├── di/               Manual dependency container
└── ui/               Compose UI: theme, components, home, search,
                     details, player, library, extensions, settings
```

## Security notes

- HTTPS certificate validation is never bypassed.
- Plain `http://` is allowed *only* so LAN-hosted addons and the local
  bridge can work; public addon hosts keep full TLS verification.
- Extension manifests and URLs are validated before install; nothing is
  installed or activated silently.
- No secrets, API keys or credentials are bundled with the app.
- The LAN bridge serves read-only JSON over GET/HEAD with CORS; it never
  executes downloaded code.

## Credits

Built with Jetpack Compose, Media3/ExoPlayer, Room, DataStore, OkHttp,
kotlinx.serialization, Coil and ZXing. Stream Bridge implements the open
Stremio addon *protocol*; it is an independent project with original code,
branding and artwork.

## License

MIT — see [LICENSE](LICENSE).
