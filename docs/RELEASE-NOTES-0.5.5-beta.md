# StreamBridge 0.5.5-beta

StreamBridge’s new beta generation brings updated playback, discovery, tracking,
and library tools to Android and establishes a native iOS release target.

## What’s new

- More consistent playback startup and resume behavior, clearer seek/volume/
  brightness feedback, and improved next-episode and shuffle controls.
- Visual subtitle synchronization, plus retained HLS quality selection,
  picture-in-picture, volume boost, external players, and Android mpv fallback.
- In-app title and episode ratings with optional Trakt, Simkl, and MDBList
  synchronization; refreshed rating controls and feedback.
- Expanded MDBList account/library/tracking support and Simkl recommendations,
  alongside the existing optional metadata integrations.
- More reliable library and calendar refresh, clearer loading errors, correctly
  ordered new additions, and easier access to downloads from Library.
- Richer Profile Insight statistics and Taste DNA visualization, with persistent
  title facts and background lookups designed to reduce repeated API requests.
- Custom poster patterns, landscape artwork/clearlogo controls, and improved
  metadata-source and debrid episode-file selection.
- Updated navigation, dialogs, sheets, and device-local appearance controls,
  including tablet layouts and password-manager autofill improvements.
- Live TV playlist/EPG improvements and sticky search/filter controls for
  user-configured sources.
- Bengali and Urdu support, with refreshed Greek, Vietnamese, Slovak, and other
  translations. StreamBridge’s name and artwork remain consistent across locales.
- Updated JavaScript plugin execution with asynchronous timers and binary fetch
  support. No catalogs, plugins, or content providers are bundled by default.
- Native iOS host/player improvements, including Now Playing controls,
  picture-in-picture, subtitle fonts, and download Live Activities.

## Installation and updates

Android packages are published per architecture with StreamBridge names. Keep
using the same production-signed package for in-place updates. The beta update
channel recognizes this release; stable-channel users can opt into beta in
Settings.

The iOS pipeline supports a StreamBridge archive and signed IPA when the
maintainer supplies matching Apple distribution credentials. An unsigned
validation IPA is **not** an installable production release: it requires your
own signing. For Ad Hoc signed IPAs, the device must be registered in the
provisioning profile. See [iOS installation and signing](IOS.md).

This beta still needs device-level regression testing before publication.
Available artifacts and platform build status should be checked on the release
page; an iOS download is not promised until a signed export succeeds.

StreamBridge provides no content. Add only sources you are legally entitled to
use. This update does not include the experimental CloudStream runtime.

## Credits

This generation adapts open-source work from Nuvio Mobile and NuvioMobile
Enhanced and their contributors. StreamBridge is independently branded and
released; it is not an official build of either upstream project. GPL and
third-party attribution remain in the application and source distribution.
