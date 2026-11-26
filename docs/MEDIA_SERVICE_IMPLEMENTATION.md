# OneTube / Music implementation increment — not parity completion

Session date: 2026-11-24. Branch: `arena/b0aacb8e-mybridge`.

## Scope and honest acceptance status

**The requested complete OneTube + YouTube Music experience is NOT implemented.**
This increment supplies real service code and connected catalogue/library operations,
not a claim that a public API browser meets the requested acceptance criteria.
No YouTube/YouTube Music playback, Google sign-in, background Music player or offline
adapter has been connected. Unsupported actions are explicitly described and have
no pretend play/download/sign-in buttons. Existing StreamBridge branding, app IDs,
player/PiP/download owners and dedicated environment architecture are retained.

## Source audit and feature mapping

Inspected a fresh checkout of `https://github.com/zoga2o3-ops/Nuvio-One`, main
commit `c47267c1d2d2a2c87731de7fd2d131526bf9e2f5`. This is a newer snapshot than
the original navigation audit. Findings below are based on implementation files,
method bodies, imports and build configuration, not README feature claims alone.
Paths in the table are relative to upstream
`app/src/main/java/com/nuvio/tv/`, except `BitChord` which denotes
`app/src/main/java/com/music/bitchord/`.

| Feature group | Actual upstream implementation/evidence | This increment / remaining gap |
|---|---|---|
| Home, subscriptions, history, playlists, Live, Music, Gaming, News, Sports, Shorts, channels | `youtube/YouTubeViewModel.kt`, `YtTab`, feed loaders / ContentService | Public most-popular catalogue only through official API; no personalized/category feeds |
| Configurable order/visibility, last tab, Home continue-watching | `youtube/YouTubeSettings.kt`, `ui/screens/home/HomeViewModelYouTubeContinueWatching.kt` | Shared normalization/filter preference model only; no settings UI/persistence binding; Home continue-watching remains off/unmodified |
| Search, suggestions/history, paging | `YouTubeViewModel.suggest`, feed/search loaders; `BitChord/data/YtMusicRepository.searchPage/searchContinuation/searchSuggestions` | Public YouTube search with channel/playlist/video results and continuation; Navidrome track search; no suggestions/history, no Music catalogue search |
| Channels / playlist contents | `YouTubeViewModel` browse methods; `BitChord/data/YtMusicRepository.browseSongs/artistPage/moreSongs` | Public channel uploads and playlist items via API, Navidrome album/playlist contents; no YT Music artist/album adapter |
| Shorts, watched, upcoming, hidden words/channels | `YouTubeViewModel.allowed`, `youtube/YouTubeSettings.kt` | Pure shared filter policy; not bound to catalogue UI or persistent settings |
| Device-code/QR sign-in, accounts and profiles | `youtube/YouTubeAccountViewModel.kt`, `YouTubeAccountScopes.kt`, `music/MusicAccountRepository.kt` | Secure account/session storage primitives, explicit profile + service namespaces; no OAuth device flow, QR, Google credentials or channel switching UI |
| Independent Music account, persistence, expiry | `YouTubeAccountScopes`, `music/MusicAccountRepository`, `BitChord/auth`, `BitChord/data/innertube/Innertube.kt` | Independent vault namespaces and expiry policy; only API/server connections wired; **not** independent operational Google sign-ins |
| History/progress sync, resume | `youtube/YouTubeProgress.kt`, player/viewmodel progress code, `music/MusicListeningStore.kt` | History/progress contract only; no new sync/resume behavior |
| Format/quality up to 4K, codecs, preferences | `YouTubeViewModel.resolveBlocking/dashUrl`, `youtube/YouTubeMpdServer.kt`, existing upstream player integration | No new stream extractor/resolver. Public Data API supplies metadata, not playable streams |
| Startup, prefetch, expired URLs | `YouTubeViewModel.lookup/prefetch/prepareNext`, `music/MusicStreamResolver.kt`, `MusicPlayer.kt` | Resolved-capability contract only; no fabricated URL or unsafe prefetch |
| Chapters, loop, speed, autoplay, next | `youtube/YouTubePlayerViewModel.kt`, `YouTubeUpNext.kt`, player integration | Immutable queue/repeat policy only; not a player/auto-next integration |
| Captions, live playback/chat | `youtube/YouTubeCaptionTracks.kt`, `YouTubeAutoCaptions.kt`, `YouTubeChatOverlay.kt` | Not connected; existing subtitles/player unchanged |
| Likes/dislikes, subscribe, save playlist, hide channel | `YouTubePlayerViewModel.kt`, `YouTubeViewModel` menus | Navidrome track like/unlike UI and playlist service mutations only; no Google account mutations |
| Comments Top/Newest, replies, scroll retention | `YouTubePlayerViewModel.kt`, `YouTubePlayerPanel.kt` | Public Data API Top/Newest/replies service methods; no comments UI or scroll restoration |
| SponsorBlock | `youtube/YouTubeSponsorBlock.kt`, engine service | Not integrated: non-commercial API/data terms require approval; no skip behavior changed |
| DeArrow | `YouTubeViewModel.fetchDeArrow`, engine service | Not integrated: service/data/access terms not cleared; no entitlement bypass |
| Music personalized Home/Explore, moods/genres | `MusicHomeScreen.kt`, `MusicExploreScreen.kt`, `BitChord/data/YtMusicRepository.home/moreHome/moodAndGenres` | No YT Music adapter; Music environment can browse a user-owned Navidrome server |
| Liked Music/library and playlist CRUD | `MusicLibrary.kt`, `MusicAccountRepository.kt`, `BitChord/data/YtMusicRepository` library/mutation methods | Navidrome playlists and liked tracks browsing, confirmed like/unlike and create-playlist UI; add/delete playlist API methods exist but not exposed in UI |
| Radio/shuffle/queue/Up Next | `MusicPlayer.kt`, `MusicQueueStore.kt`, `BitChord/data/YtMusicRepository.radio/watchQueue` | Shared queue append/select/move/remove/repeat policy only; shuffle/radio/playback queue not connected |
| Music videos/audio-video switching | `MusicVideoPlayerScreen.kt`, repository `resolveAudio/resolveVideo` and alignment maps | Not implemented |
| Synced lyrics | `MusicLyricsRepository.kt`, `BitChord/data/lyrics/` | Original shared LRC parser only; no provider licensed/integrated or lyrics UI |
| Spotify public playlist import | `MusicSpotifyImport.kt`: embed track list, capped at 100, matched via YT Music search | Not implemented; no silent claim that imported titles are playable/matched |
| Navidrome | `music/MusicNavidrome.kt`: Subsonic token auth, albums, search, playlists | New original KMP HTTPS client + UI; no upstream source copied; see operational scope below |
| WebDAV | `music/MusicWebDav.kt`: Android XML parser, PROPFIND/Basic auth | Not implemented; no copy of plaintext credential storage or Android parser into commonMain |
| Last.fm | `music/MusicScrobbler.kt`: build credentials, mobile session, listening threshold | Contract only; no app registration/credentials or scrobbling integration |
| Party mode | `MusicPartyServer.kt`, `MusicPartyService.kt` (NanoHTTPD, Android service) | Not implemented; existing StreamBridge party owner untouched |
| Local/USB/network sources | `LocalMusicRepository.kt`: MediaStore, Android permissions and removable-path walking; WebDAV separately | Not implemented. iOS requires sandbox/document-provider permission design rather than Android filesystem assumptions |
| Downloads, progress, cancellation, offline/persistence | `MusicDownloads.kt`, `MusicDownloadService.kt`: Media3 manager/cache + Android service | Contract only. No second download manager; no fake offline success |
| Settings/cache/lifecycle | `YouTubeSettings.kt`, `MusicSettings.kt`, `MusicFeedCache.kt`, viewmodels/jobs | No full parity settings/cache. New UI cancels in-flight reads on route/query/lifecycle change, closes owned HTTP client, bounds pages and allows explicit retry |

### Current target inspection

Read the dedicated `MediaEnvironmentHost` expect/actual implementations, shared
content, shell entry point, platform HTTP clients, profile-scoped storage patterns,
existing secure SIMKL storage and player launch interfaces. Existing Android player
ownership is Media3/MPV-related and iOS uses the native Swift host; no alternate
player was introduced. Existing full-screen environment and fixed Exit remain.
The previously documented asynchronous origin mutation/restoration limitation is
not solved by this increment. No new player-wide gesture detector or competing
application navigator was added.

## Implemented source behavior

### Shared service foundation

- Catalogue, library, resolver, history/progress, lyrics, comments, download,
  import and scrobble contracts with platform-independent data models.
- Service + item keys prevent YouTube/Navidrome identity collisions.
- Error categories exclude raw HTTP exception text, credentials and response bodies.
- Resolved media/session/config `toString()` is redacted.
- StreamBridge account/profile/service-scoped multi-session store supports save/select/remove/reload;
  expiry includes a safety margin. Store calls must be serialized by their owner
  (the current UI does so); this is not a global concurrent auth manager.
- Profile deletion and local account wipe clear the new service credentials; cleanup
  failure is surfaced, not treated as success. The browser observes active account/profile
  changes, clears in-memory form state across namespaces, and requires a matching active profile.
- Native vault: Android AES-GCM with Android Keystore, namespace bound as AAD;
  iOS Keychain with device-only accessibility and update-before-insert. No plaintext
  fallback. Storage failure is explicit. No imported cookies/client credentials.
- Queue/filter/tab-order/LRC parsing policies are implemented but not presented as
  connected features. No pretend queue/player/lyrics controls were added.

### YouTube public catalogue (limited alternative, not OneTube parity)

- User supplies an enabled YouTube Data API v3 project key. Key goes in
  `X-Goog-Api-Key`, not URLs. Stored separately from all other provider credentials.
- Public most-popular Home, explicit search, channel video listings and playlist
  contents are connected to real requests; manual pagination to a 300-item UI cap.
- Public Top/Newest comments and replies exist as service methods only.
- Key validation is a real Home request (consumes quota); it is **not account sign-in**.
  HTTP 403 reports permission/API/quota failure; 429 reports rate limit. No automatic
  quota-consuming retries or hidden suggestion polling.
- Video cards clearly state playback is not connected; no non-working Play button.
  Account Library explicitly explains why API access cannot supply private content.

### Music / user-owned Navidrome library

- User provides HTTPS server/reverse-proxy prefix, username and a Subsonic derived
  token + matching salt from their server administrator. No password field, raw
  password storage, MD5 password emulation or plaintext-network fallback.
- Connection succeeds only after actual `ping` returns Subsonic status `ok` and
  secure storage succeeds. Invalid API envelopes are not silently empty success.
- Newest albums with pages, track search with pages, album/playlist contents,
  playlists and liked tracks; server-confirmed track like/unlike and create playlist.
- Add-to-playlist/delete-playlist API methods validate service/key types; UI does not
  expose them yet. These methods must not be mistaken for complete playlist management.
- Auth is POST form body, not a query string; injected platform clients disable
  redirects. HTTPS URL must have no embedded credentials, query, fragment or encoded
  path tricks. Responses are bounded to 2 MiB. Explicit retry only; mutations are
  not automatically retried. Network exceptions are sanitized, cancellation rethrown.
- Connections are retained in platform-protected storage per profile. Remove-connection
  removes the saved credential. Connection fields are not rememberSaveable. No session
  or track data is sent to a third-party proxy. No playback/offline success is implied.

## Licensing and compliance decisions

No code, fonts, logos, icons, assets, OAuth registrations or embedded modules were
copied from Nuvio One/BitChord/MediaServiceCore. All implementation in this increment
is original code within this GPL-3.0 target, using existing Ktor, coroutines,
kotlinx.serialization, Compose, Android SDK and Apple SDK dependencies. No dependency
or Gradle/release/signing/application-identity file changed. Upstream attribution
sources are recorded here for the audit, not hidden in user-facing branding.

Inspected actual upstream license files and GitHub source/license metadata:

- Nuvio One root `LICENSE`: GNU GPL v3; BitChord attribution in upstream README and
  `MUSIC-PORTING.md` credits Kushagra Singh, GPL-3.0. BitChord repository
  `https://github.com/kushagrasinghx/BitChord` license text is GPL v3. Future copied
  code must preserve notices and comply with corresponding-source obligations.
- `mediaservicecore/LICENSE-MediaServiceCore` and `LICENSE-SharedModules`: MIT,
  copyright 2020-present yuliskov. **Not the whole dependency closure:** vendored
  `j2v8/LICENSE.txt` is EPL-1.0; Commons IO includes its own license and NOTICE.
  Android youtubeapi also brings Retrofit/Gson/JsonPath/RxJava/Cronet/WebKit/J2V8.
  Do not assume a blanket MIT grant or native compatibility. No module imported;
  complete transitive/binary licensing closure remains a gate before any import.
- SponsorBlock `https://github.com/ajayyy/SponsorBlock/LICENSE`: GPL v3 extension
  code; README says v3 or later. Separately read
  `https://github.com/ajayyy/SponsorBlock/wiki/Database-and-API-License` via its wiki
  Git repository: **API and database CC BY-NC-SA 4.0 unless explicit permission**.
  This task explicitly prohibits incorporating non-commercial terms without approval.
  Therefore no SponsorBlock network/data integration or upstream code was added.
- DeArrow `https://github.com/ajayyy/DeArrow/LICENSE`: GPL v3 code. README describes
  activation/license keys and separate asset credits. Code license does not establish
  API/data license or permission to bypass activation. Service/data terms are not
  fully cleared; no DeArrow integration or assets were imported.
- YouTube Data API access requires a user-owned enabled project, quota and applicable
  Google service/developer-policy compliance. This does not authorize stream extraction,
  offline YouTube media, private data or an unapproved OAuth client. No such capability
  is claimed. Full distribution/policy review remains required before shipping.
- User-owned Navidrome protocol requests are independently implemented. No Subsonic,
  Navidrome or upstream client source incorporated. Users must have permission for
  their own server/media. Streaming/download rights are not inferred from metadata access.
- Lyrics providers, Spotify scraping/matching, external artwork and private YouTube
  protocol dependencies have not been cleared/imported. There is no claim that GPL
  status alone grants data/API/trademark rights. No non-commercial code/data incorporated.

## Verification and limitations

Authored **30 new Kotlin tests**: 12 policy/parser tests, seven session-store tests,
11 MockEngine HTTP tests. Fixtures exist only in tests, never production UI.
Tests cover namespaces/account switching, redaction, queue identity/repeat,
filters/tabs, timestamps, URL validation, header/form auth, redirect rejection,
service errors, continuation mapping and cancellation.

Attempted locally:

```sh
./gradlew :composeApp:testAndroidHostTest -Pnuvio.android.distribution=playstore \
  --tests 'com.nuvio.app.features.media.service.*' --offline --console=plain
```

**Exit 1 before Gradle: JAVA_HOME unset and Java absent. No new Kotlin test or
compiler result exists.** This also prevents establishing compiler correctness of
new platform vault code here. No full CI, production build, release, tag or merge.
No live Google/Navidrome authentication or network request was attempted: credentials
and a test server are not supplied, and permitted sandbox outbound hosts exclude
these services. No fake credentials were used against services.

Static checks: whitespace/diff checks; XML parse and resource-reference resolution;
source-set import checks; syntax-only Tree-sitter Kotlin parsing of all 16 media Kotlin files; unchanged player/PiP/download and release/app-identity paths.
These checks are not a Kotlin compiler, Keychain/Keystore test or UI test.

Earlier Android eight-test and iOS framework passes apply to the preceding navigation
foundation source, **not this increment**. Existing Swift simulator MetalLayer blocker
remains unchanged. New Android/iOS compilation, real service integration testing,
security-store failure/reinstall/backup behavior, rotation/background/foreground,
profile switching, native modal lifecycle, accessibility and device layouts all
remain unverified. New controls need runtime validation before shipment.

## Next implementation work

1. Build and run the new tests on provisioned toolchains, then test real server/API
   connections and native vault behavior. Address errors before claiming compatibility.
2. Agree on a supported/licensed cross-platform YouTube/Music service engine and app-owned
   OAuth registrations; implement real independent sign-in/refresh/account switching.
3. Implement and test a player handoff/return design that preserves the dedicated
   environment and existing playback ownership. Do not use metadata API URLs as media URLs.
4. Wire full YT Music catalogue/mutations, history/progress, comments UI, discovery
   categories, persistence/settings, queue/radio/shuffle, lyrics and advanced player features.
5. Add platform local-library permissions, offline adapters using the current download
   owner, WebDAV/Spotify/Last.fm/Party integrations with explicit licensing/access review.
6. Complete Enhanced-inspired responsive artwork/card/search/comment/player UX and
   automatic paging/caching once actual service and lifecycle behavior is tested.

The original 100% parity acceptance criterion remains unmet. This is a committed
implementation increment with substantial remaining work, not a finished experience.

### Files/modules in this increment

- Shared media service package: `MediaServices.kt`, `MediaSessionStore.kt`,
  `MediaPolicies.kt`, `YouTubeDataService.kt`, `NavidromeService.kt`.
- Shared connected content: new `MediaServiceBrowser.kt`, updated
  `MediaEnvironmentHost.kt` and `media_strings.xml`.
- Native storage: `MediaCredentialVault.android.kt`, `MediaCredentialVault.ios.kt`;
  one Android `MainActivity.kt` vault-initialization line.
- Credential lifecycle: targeted additions in `ProfileRepository.kt` and
  `LocalAccountDataCleaner.kt`, no player/download-owner changes.
- Tests: `MediaPoliciesTest.kt`, `MediaSessionStoreTest.kt`, `MediaServiceHttpTest.kt`.
- Documentation: this implementation/audit report and updated
  `DEDICATED_MEDIA_VERIFICATION.md` with the new compilation boundary.

No new Gradle module or project dependency was added.

## Increment 2 — connected Google accounts, libraries and WebDAV playback

This addendum supersedes the earlier increment's "not connected" statements only
for the features explicitly listed here. Full OneTube/YT Music parity remains
**incomplete**; no release or platform-runtime compatibility is asserted.

### Source delivered

- Real Google TV/limited-input device authorization using an **owner-supplied,
  eligible OAuth registration** and the documented `youtube` scope. Registration
  alone is not authentication. The UI displays Google's device code, opens an
  allowlisted Google verification URL in the system browser, polls at the granted
  interval, handles `authorization_pending`/`slow_down`, cancellation, denial and
  expiry, then validates the granted channel through `channels?mine=true`.
- Access/refresh credentials are saved only after a real token and channel response.
  Expired access tokens refresh under a per-owner mutex. An account switch/removal
  during refresh cannot reactivate the previous account. No borrowed registrations,
  browser cookies, client keys, passwords or accounts are embedded.
- Account switching/add/remove UI and independent YouTube / Music-provider namespaces.
  Remove deletes the local connection; it is **not** Google server-side revocation.
- Authorized playlists, liked videos and subscriptions; browse subscribed channels;
  subscribe/unsubscribe; private playlist creation, save a single selected item to
  a chosen playlist, and confirmed playlist deletion. Mutations update UI only after
  server success. API-only connections still cannot access private account data.
- Public comments with Top/Newest, replies, pagination and explicit errors. Comment
  list scroll/selection retention across closing a panel is not complete.
- Music's Google connection is honestly labeled **Google music-video library**:
  documented category-10 catalogue + YouTube account library, with its own grant.
  This is **not personalized YouTube Music Home, album/artist APIs or YT Music radio**.
  The documented API does not provide those endpoints, watch history or stream URLs.
- Navidrome server-supplied similar tracks, random tracks and lyrics (plain or parsed
  LRC). Unsupported server radio/lyrics is not replaced with invented results. Timed
  lyrics can be viewed, but there is no synchronized playback highlighting yet.
- Real read-only HTTPS WebDAV connection, bounded Depth-1 collection listing, nested
  folders, collection-root name filtering and media resolution. Same-origin/base-path
  validation rejects outside collection/traversal URLs. Credentials are header-only
  Basic auth in the existing encrypted vault, not URLs. Listing redirects are rejected.
  Playback follows the existing engine's network/redirect policy: only trusted servers
  should be connected. The intentionally restricted XML parser rejects DTD/entities,
  CDATA/comments, numeric/unknown entities and unsupported responses rather than
  attempting unsafe XML expansion; this limits server compatibility.
- **WebDAV playback is wired to StreamBridge's existing PlayerScreen and platform
  engines**, not a new player. The browsing composition remains retained but unplaced
  and semantics-hidden while playback is shown, with fixed Exit and Back-to-library.
  Active mini-player ownership prevents starting a competing instance; parked iOS
  playback is released using the existing new-playback hook. No MetalLayer/native
  player/PiP implementation was edited.
- WebDAV session queue: play collection tracks, append, select, remove, move up,
  shuffle and manual Previous/Next using the same player. No automatic end-of-track
  advance, persistent queue or background-music service is claimed.
- One targeted existing player policy addition excludes only the new
  `dedicated-media` parent type from movie/series watch-progress/scrobble recording.
  This avoids polluting StreamBridge Continue Watching or persisting private file
  URLs there. Existing content types retain their previous policy. Dedicated-media
  history/resume is not implemented.

### New/changed paths

Shared production additions: `GoogleAccountConnect.kt`, `MediaComments.kt`,
`MediaPlayback.kt`, `service/GoogleDeviceAuthService.kt`, `service/WebDavService.kt`,
`service/WebDavListing.kt`. Updated `MediaServiceBrowser.kt`, the existing media host
content name/wiring, `NavidromeService.kt`, `YouTubeDataService.kt`, media resources,
and the one-line player progress guard. No new service-contract family, engine,
application navigator, Gradle dependency, version, app ID or release file was added.
Tests added: `GoogleDeviceAuthTest.kt`, `ConnectedLibraryTest.kt`,
`WebDavListingTest.kt`, `WebDavServiceTest.kt`.

### Actual verification

Java limitation was partially resolved using the allowed Python package source:
`jdk4py==21.0.8.2` supplies **Temurin Java 21.0.8 runtime**, not a complete Android
JDK/SDK. A standalone Kotlin 2.4.21 compiler was obtained from the npm
`kotlin-compiler` package. Tools live outside Git under `/tmp` and are not product
runtime/project dependencies.

Attempted affected target:

```sh
JAVA_HOME=/tmp/media-java/jdk4py/java-runtime \
PATH=/tmp/media-java/jdk4py/java-runtime/bin:$PATH \
./gradlew :composeApp:compileAndroidMain -Pnuvio.android.distribution=playstore \
  --offline --console=plain
```

**Exit 1**: wrapper attempted to obtain the absent Gradle 9.8.2 distribution and
failed with `SSLHandshakeException` / EOF. It did not reach Gradle project
configuration, dependency resolution, Kotlin compilation or Android compilation.
`--offline` applies to Gradle, not its missing distribution bootstrap. No release
workflow or remote CI was dispatched. Full JDK/Android SDK and dependency access
remain absent; Xcode/macOS remain unavailable.

**17 actual Kotlin test methods passed in a standalone JVM subset harness**:
12 existing `MediaPoliciesTest` methods and five new `WebDavListingTest` methods.
The harness compiled the actual `MediaServices.kt`, `MediaPolicies.kt`,
`WebDavListing.kt` and those actual test sources, with real `kotlin-test` assertions.
Because serialization/JUnit dependencies are not cached, it used compile-only
`Serializable` and `Test` marker annotations and the unchanged session-model class
extracted from `MediaSessionStore.kt`. Methods were invoked directly, not via JUnit.
This does **not** test generated serializers, the session-store implementation,
Gradle wiring, Ktor, Compose, Android or Kotlin/Native. It is not a substitute for
running the project test suite. The harness/artifacts are outside Git in `/tmp`.

There are now **53 authored service-package tests**: 17 executed in that narrow
harness, **36 not executed**, including every HTTP/OAuth MockEngine integration test.
Mocks are test fixtures only, never production authentication or catalogue data.
Static XML/resource, source-set import, syntax-only parsing and diff checks also ran.
No real Google registration/account, Navidrome/WebDAV server or device/simulator
session was supplied or exercised. OAuth app eligibility/consent, service responses,
player lifecycle, real audio/video output, safe areas and accessibility all require
integration/device verification. Earlier navigation CI does not verify this code.

### Remaining work and blockers

- Real OneTube/YT Music stream resolution, full Music catalogue and personalized
  feeds/history, signed account progress synchronization and audio/video switching
  are **not implemented**. The public Data API is not a streaming or watch-history
  API. No unofficial stream bypass was wired as an undocumented "success" fallback.
- Navidrome token-in-query playback has **not** been handed to the player; it needs
  an audited credential-safe networking/persistence path. WebDAV header-auth playback
  is the only new playback source wired in this increment.
- Google device flow requires a valid owner-owned limited-input client, enabled APIs,
  eligible scopes/accounts and Google's application verification/consent requirements.
  No Google account authentication is claimed tested without such a registration.
- Spotify import, Last.fm, Party Mode, local/USB library, downloads/offline, advanced
  player parity and complete responsive Enhanced-style UI remain implementation work,
  not features claimed blocked solely by the compiler environment.
- SponsorBlock non-commercial data/API approval and DeArrow service/data clearance
  remain unchanged licensing gates. No upstream code, credentials or assets imported.
- WebDAV queue is session-only/manual-advance; server lyrics are display-only, not a
  claim of full integrated karaoke/radio playback. Modal/player return behavior needs
  runtime verification. A user profile change clears the active media playback view.

### Git restoration note

The workspace restored the prior increment's files but not its local Git object
`e44a13b81e866fd35629cca486474f3d23736172`. Fetching that exact SHA returned
`not our ref`; the remote branch still ended at `c7d5579…`. Existing source was
preserved in recovery commit `9662d85` before increment-2 edits. No source reset,
branch replacement or claim of ancestry from the unavailable object was made.

## Autonomous verification increment — current branch restoration and connected policies

The next workspace restoration again contained implementation bytes but only baseline
Git history. The exact `9662d85`/`3ee86b9` objects were absent. All restored files were
committed as `a74c104`, then the actual remote `c7d5579` history was merged in
`1cb0e98`; no history was reset, rewritten or force-pushed. Merge resolutions retained
the newer media host, resources and verification documentation.

Additional connected functionality:

- Account/profile/provider-scoped encrypted queues survive catalogue navigation and
  process restart. Resolved media, authorization headers and OAuth tokens are not
  queue entries. Account removal/profile cleanup removes its queue and saved browsing
  state. Queue writes cannot reactivate or overwrite another selected account.
- Repeat off/all/one, shuffle, and automatic end-of-stream advancement now use an
  optional composition-scoped callback in the existing PlayerScreen runtime. Its
  default is null; movie/series autoplay behavior is not replaced. Same-item repeat
  recreates the existing player composition, not a competing engine. Runtime audio,
  lifecycle/background and end-event behavior still require device verification.
- Documented category discovery (Music/Gaming/News/Sports), live video discovery,
  channel discovery, and authorized playlists/subscriptions are connected in feed tabs.
  Tab visibility/order, remembered feed, upcoming broadcasts, title-word and channel-ID
  filters persist independently per connected account. History and Shorts feeds are
  not exposed as supported Data API features. Subscriptions are channel management,
  not a fabricated aggregated personalized subscription-video feed.
- Bounded, clearable local search history supplies matching previous queries; it is
  explicitly not remote search suggestions. No remote history or progress is invented.
- Responsive catalogue width and real artwork loading; near-end automatic pagination
  retains the explicit Load more action and the existing 300-entry safety bound.
- Private dedicated-media identities passed to the player are opaque rather than
  private file URLs. Movie metadata, parental-guide/skip lookups and addon subtitle
  fetching are excluded for this new content type; existing types retain their paths.
- Refresh checks now also prevent an older refresh response overwriting a newer grant
  for the same account. Five MockEngine tests cover valid-token reuse, concurrent
  refresh, logout, account switch and replacement-grant races.

### Real project verification (not the earlier standalone harness)

GitHub Actions run https://github.com/sascio/MyBridge/actions/runs/45901218119
on source `1542146` completed **Android shared/platform compilation and 61 focused
project tests with zero failures/skips**, asserted from Gradle XML reports. This
includes all 53 service tests then present and eight navigation/swipe tests. Its
Android job is https://github.com/sascio/MyBridge/actions/runs/45901218119/job/140187180596 .
This supersedes the earlier statement that those HTTP/OAuth tests had never run;
it does not verify changes committed after `1542146`.

The focused workflow now requires **75 tests** (67 service, eight navigation/swipe),
including persistent queue/browsing and refresh-race coverage. Subsequent latest-source
Android, Apple framework/host, full APK, unsigned IPA and simulator-test workflows
are being exercised; their final results and exact source SHAs must be taken from
Actions and the final execution report, not inferred from the earlier green job.

Local toolchain recheck: installed `jdk4py` from PyPI again; Java runtime is available.
No Android SDK, Xcode or Gradle distribution cache was restored. The same local
Gradle bootstrap attempt failed TLS/EOF before project configuration. TLS validation
was not disabled, no wrapper/dependency versions changed, no untrusted binaries used.
Remote runners successfully bootstrapped the existing wrapper and compiled Android.

Upstream was re-read at unchanged main `c47267c1d2d2a2c87731de7fd2d131526bf9e2f5`.
Its Music Innertube implementation explicitly depends on WebView-captured cookies,
SAPISIDHASH and a separate resolver, with Android/JVM-specific imports. Those private
protocol/authentication/stream components have not been copied or represented as a
supported public Data API capability. Component-level license findings above still
apply. Full OneTube/Music parity, live account/device verification, native background
Music UX, Navidrome playback, imports/scrobbling/party/local sources and dedicated
media download/offline completion remain outstanding. Existing download architecture
persists source headers: it was not blindly connected to private WebDAV credentials.
No signing/version/updater/release/production configuration was modified.
