# Enhanced 0.6.12-beta integration manifest

This manifest describes the source-only integration from the previous pinned Enhanced baseline
`254ae152fe94a2541c5a32b897a814594363d97f` (tag `0.6.11-beta`) to the verified target
`ac86e820e76362284c9d7524bb523976df32e195` (tag `0.6.12-beta`) in
[`luqmanfadlli/NuvioMobile-Enhanced`](https://github.com/luqmanfadlli/NuvioMobile-Enhanced).
The official core tag `0.6.12-beta` (`4135f96cd29f235b7c412f78dcfc13164c4b1e80`) is an ancestor
of the target. GitHub/Git reports 46 commits and 133 changed paths: 46 additions, 87 modifications,
no removals, +8,226/−358 lines.

No upstream commit history was merged. Modified paths were compared against the previous source pin
and integrated with per-file three-way merges. Files already identical to the prior source baseline
were copied from the target; StreamBridge-owned customizations were merged or resolved explicitly.
The seven manual resolutions cover the Android/iOS Simkl storage adapters, Simkl metadata and auth
repository, PKCE tests, and Dutch/Vietnamese locale resources. They preserve StreamBridge's
placeholder-credential guard, form-encoded single-attempt token requests, protected refresh tokens,
proactive refresh, app branding and existing localized customizations while adding the target's
AUTH V2 device authorization/revocation and translated changes.

The two upstream-owned metadata paths were not imported: `store.json` remains the latest published
unsigned IPA entry and is regenerated only after a published-release IPA is verified; the Xcode
version file is StreamBridge-owned and was separately set to version 0.1.18 / build 118. Added
community links and About Us changes are StreamBridge additions and are not upstream changes.

| Upstream status | Path | Latest source commit touching path | Disposition |
|---|---|---|---|
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/HlsParameterSetInitTest.kt` | `1d775bcd` — fix(player): add an SPS/PPS init segment to HLS streams that lack them | Added from the verified target |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/MpvErrorLogTest.kt` | `30e3a85f` — fix(player): word ExoPlayer and libmpv errors the same way as NuvioTV | Added from the verified target |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/MpvPlaybackPositionTest.kt` | `4d0e64cf` — fix(player): use the audio clock when mpv video time drops to 0 | Added from the verified target |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/PlaybackDashMimeTest.kt` | `25cc3e47` — Preserve DASH routing for explicit MIME hints | Added from the verified target |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/PlaybackErrorMessagesTest.kt` | `30e3a85f` — fix(player): word ExoPlayer and libmpv errors the same way as NuvioTV | Added from the verified target |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/ShouldReadExoChaptersTest.kt` | `c3e19993` — feat(player): chapters on iOS, and ExoPlayer reads them as NuvioTV does | Added from the verified target |
| `M` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/SubtitleAppearanceStorageTest.kt` | `ee6821fb` — feat(player): isolate subtitle preferences per device | Copied verbatim; local matched the baseline |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/bluray/BdavPgsReaderTest.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/bluray/BdavTrueHdReaderTest.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/bluray/BlurayBdavTest.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/bluray/BlurayClipInfoTest.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/bluray/BlurayDiscResolverTest.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/bluray/BlurayIsoImageTest.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/streams/StreamLinkCacheTokenTest.kt` | `3d70db68` — fix(streams): keep tokenized links for reuse and refresh expired saved links | Added from the verified target |
| `M` | `composeApp/src/androidMain/assets/anime4k/Nuvio_Line_Sharpen.glsl` | `89de485c` — Significantly increase anime line sharpener strength | Copied verbatim; local matched the baseline |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/HlsParameterSetInit.kt` | `1d775bcd` — fix(player): add an SPS/PPS init segment to HLS streams that lack them | Added from the verified target |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/HlsParameterSetInitDataSource.kt` | `1d775bcd` — fix(player): add an SPS/PPS init segment to HLS streams that lack them | Added from the verified target |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/LivePlayback.android.kt` | `3e285654` — fix live tv buffering endlessly and black screen on some streams | Added from the verified target |
| `M` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/MatroskaChapterLoader.kt` | `c3e19993` — feat(player): chapters on iOS, and ExoPlayer reads them as NuvioTV does | Copied verbatim; local matched the baseline |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/MpvErrorLog.kt` | `30e3a85f` — fix(player): word ExoPlayer and libmpv errors the same way as NuvioTV | Added from the verified target |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/MpvPlaybackPosition.kt` | `4d0e64cf` — fix(player): use the audio clock when mpv video time drops to 0 | Added from the verified target |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlaybackErrorMessages.kt` | `30e3a85f` — fix(player): word ExoPlayer and libmpv errors the same way as NuvioTV | Added from the verified target |
| `M` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlaybackMediaItems.android.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerSettingsStorage.android.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/bluray/BdavM2tsDataSource.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/bluray/BdavPgsReader.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/bluray/BdavTrueHdReader.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/bluray/BlurayClipInfo.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/bluray/BlurayDiscResolver.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/bluray/BlurayIsoDataSource.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/bluray/BlurayIsoImage.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/bluray/BlurayMediaSources.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `A` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/bluray/BlurayPlaybackSession.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `M` | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/simkl/SimklPlatform.android.kt` | `f9b2ad48` — migrate simkl oauth v1 to v2 | Manual resolution; preserve StreamBridge changes and integrate applicable target change |
| `M` | `composeApp/src/commonMain/composeResources/values-ar/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-bg/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-bn/strings.xml` | `d3ba2a04` — Merge pull request #2609 from skoruppa/feat/chapter-settings | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-cs/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-de/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-el/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-es/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-fr/strings.xml` | `d3ba2a04` — Merge pull request #2609 from skoruppa/feat/chapter-settings | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-he/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-hr/strings.xml` | `d3ba2a04` — Merge pull request #2609 from skoruppa/feat/chapter-settings | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-hu/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-id/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `A` | `composeApp/src/commonMain/composeResources/values-id/strings_bluray.xml` | `8ce792c1` — Merge PR #362: Blu-ray ISO/BDMV/M2TS playback for ExoPlayer | Added from the verified target |
| `M` | `composeApp/src/commonMain/composeResources/values-in/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `A` | `composeApp/src/commonMain/composeResources/values-in/strings_bluray.xml` | `8ce792c1` — Merge PR #362: Blu-ray ISO/BDMV/M2TS playback for ExoPlayer | Added from the verified target |
| `M` | `composeApp/src/commonMain/composeResources/values-it/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-ja/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-nb/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `A` | `composeApp/src/commonMain/composeResources/values-nl/autosync_strings.xml` | `d2fdc619` — Update Dutch (nl) strings and reorder Dutch in Settings | Added from the verified target |
| `A` | `composeApp/src/commonMain/composeResources/values-nl/autosync_toast_strings.xml` | `d2fdc619` — Update Dutch (nl) strings and reorder Dutch in Settings | Added from the verified target |
| `M` | `composeApp/src/commonMain/composeResources/values-nl/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Manual resolution; preserve StreamBridge changes and integrate applicable target change |
| `A` | `composeApp/src/commonMain/composeResources/values-nl/strings_mdblist.xml` | `d2fdc619` — Update Dutch (nl) strings and reorder Dutch in Settings | Added from the verified target |
| `A` | `composeApp/src/commonMain/composeResources/values-nl/strings_party.xml` | `d2fdc619` — Update Dutch (nl) strings and reorder Dutch in Settings | Added from the verified target |
| `A` | `composeApp/src/commonMain/composeResources/values-nl/strings_ratings.xml` | `d2fdc619` — Update Dutch (nl) strings and reorder Dutch in Settings | Added from the verified target |
| `A` | `composeApp/src/commonMain/composeResources/values-nl/strings_servers.xml` | `d2fdc619` — Update Dutch (nl) strings and reorder Dutch in Settings | Added from the verified target |
| `M` | `composeApp/src/commonMain/composeResources/values-pl/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-pt-rBR/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-pt/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-ro/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-ru/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-sk/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-ta/strings_main_013.xml` | `6ef2a6c6` — fix(i18n): drop the extra requests note from the chapters description | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/composeResources/values-ta/strings_main_018.xml` | `30e3a85f` — fix(player): word ExoPlayer and libmpv errors the same way as NuvioTV | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-tr/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-ur/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/composeResources/values-vi/strings.xml` | `d3ba2a04` — Merge pull request #2609 from skoruppa/feat/chapter-settings | Manual resolution; preserve StreamBridge changes and integrate applicable target change |
| `M` | `composeApp/src/commonMain/composeResources/values/strings.xml` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `A` | `composeApp/src/commonMain/composeResources/values/strings_bluray.xml` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/PlayerDestination.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/StreamDestination.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/core/storage/LocalAccountDataCleaner.kt` | `ee6821fb` — feat(player): isolate subtitle preferences per device | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/collection/FolderDetailRepository.kt` | `05fd435c` — fix(collections): respect "Show catalog type" in folders | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/debrid/DirectDebridResolver.kt` | `3d70db68` — fix(streams): keep tokenized links for reuse and refresh expired saved links | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/discord/DiscordRpcRepository.kt` | `49151cf5` — fix view in imdb button for discord rpc | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/livetv/LiveTvCache.kt` | `3e285654` — fix live tv buffering endlessly and black screen on some streams | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/livetv/LiveTvPortalProviders.kt` | `3e285654` — fix live tv buffering endlessly and black screen on some streams | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/livetv/LiveTvRepository.kt` | `3e285654` — fix live tv buffering endlessly and black screen on some streams | Copied verbatim; local matched the baseline |
| `A` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/livetv/LiveTvStreamHeaders.kt` | `3e285654` — fix live tv buffering endlessly and black screen on some streams | Added from the verified target |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/FoldControls.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerChapters.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControlActions.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControls.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Copied verbatim; local matched the baseline |
| `A` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerDiscTitlesPanel.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerEngine.kt` | `3e285654` — fix live tv buffering endlessly and black screen on some streams | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerModels.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreen.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenArgs.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeChapters.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `A` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeDiscTitles.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Added from the verified target |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeDiscord.kt` | `068529a5` — fix imdb button for discord rpc | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeEffects.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeGestureActions.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimePlaybackActions.kt` | `39d0ea51` — raw bluray files compatibility for exoplayer | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeSourceActions.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeState.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeUi.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsStorage.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerTimeline.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `A` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/SubtitleDevicePreferences.kt` | `d65fa7ab` — fix(subtitles): config export/import and profile removal for device subtitle prefs | Added from the verified target |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/ProfileRepository.kt` | `d65fa7ab` — fix(subtitles): config export/import and profile removal for device subtitle prefs | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/AppLanguage.kt` | `d2fdc619` — Update Dutch (nl) strings and reorder Dutch in Settings | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/PlaybackInterfaceSettings.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsConfigTransfer.kt` | `d65fa7ab` — fix(subtitles): config export/import and profile removal for device subtitle prefs | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsSearch.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Clean three-way merge |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/TrackingProviderCards.kt` | `f9b2ad48` — migrate simkl oauth v1 to v2 | Clean three-way merge |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/simkl/SimklApiClient.kt` | `f9b2ad48` — migrate simkl oauth v1 to v2 | Clean three-way merge |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/simkl/SimklApiMetadata.kt` | `f9b2ad48` — migrate simkl oauth v1 to v2 | Manual resolution; preserve StreamBridge changes and integrate applicable target change |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/simkl/SimklAuthModels.kt` | `f9b2ad48` — migrate simkl oauth v1 to v2 | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/simkl/SimklAuthParsing.kt` | `f9b2ad48` — migrate simkl oauth v1 to v2 | Clean three-way merge |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/simkl/SimklAuthRepository.kt` | `f9b2ad48` — migrate simkl oauth v1 to v2 | Manual resolution; preserve StreamBridge changes and integrate applicable target change |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/simkl/SimklDeviceAuth.kt` | `f9b2ad48` — migrate simkl oauth v1 to v2 | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/simkl/SimklPlatform.kt` | `f9b2ad48` — migrate simkl oauth v1 to v2 | Already matched target; no file replacement |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamLinkCacheRepository.kt` | `3d70db68` — fix(streams): keep tokenized links for reuse and refresh expired saved links | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamModels.kt` | `3d70db68` — fix(streams): keep tokenized links for reuse and refresh expired saved links | Clean three-way merge |
| `A` | `composeApp/src/commonTest/kotlin/com/nuvio/app/features/collection/FolderCatalogTabLabelTest.kt` | `05fd435c` — fix(collections): respect "Show catalog type" in folders | Added from the verified target |
| `A` | `composeApp/src/commonTest/kotlin/com/nuvio/app/features/livetv/LiveTvM3uHeadersTest.kt` | `3e285654` — fix live tv buffering endlessly and black screen on some streams | Added from the verified target |
| `A` | `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/CachedSourceRefreshCandidateTest.kt` | `3d70db68` — fix(streams): keep tokenized links for reuse and refresh expired saved links | Added from the verified target |
| `A` | `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/MpvChaptersTest.kt` | `c3e19993` — feat(player): chapters on iOS, and ExoPlayer reads them as NuvioTV does | Added from the verified target |
| `A` | `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/SubtitleDevicePreferencesTest.kt` | `ee6821fb` — feat(player): isolate subtitle preferences per device | Added from the verified target |
| `M` | `composeApp/src/commonTest/kotlin/com/nuvio/app/features/simkl/SimklApiClientTest.kt` | `f9b2ad48` — migrate simkl oauth v1 to v2 | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/commonTest/kotlin/com/nuvio/app/features/simkl/SimklPkceTest.kt` | `f9b2ad48` — migrate simkl oauth v1 to v2 | Manual resolution; preserve StreamBridge changes and integrate applicable target change |
| `M` | `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerEngine.ios.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerSettingsStorage.ios.kt` | `ac86e820` — Merge upstream/cmp-rewrite into enhanced (0.6.12-beta) | Copied verbatim; local matched the baseline |
| `M` | `composeApp/src/iosMain/kotlin/com/nuvio/app/features/simkl/SimklPlatform.ios.kt` | `f9b2ad48` — migrate simkl oauth v1 to v2 | Manual resolution; preserve StreamBridge changes and integrate applicable target change |
| `M` | `composeApp/src/iosTest/kotlin/com/nuvio/app/features/player/SubtitleAppearancePlatformTest.kt` | `ee6821fb` — feat(player): isolate subtitle preferences per device | Copied verbatim; local matched the baseline |
| `M` | `iosApp/Configuration/Version.xcconfig` | `4135f96c` — bump version | StreamBridge-owned; upstream value excluded |
| `M` | `store.json` | `e9f136bc` — chore(store): publish 0.6.12-beta | StreamBridge-owned; upstream value excluded |

## Disposition totals

- 46: Added from the verified target.
- 43: Copied verbatim; local matched the baseline.
- 7: Manual resolution; preserve StreamBridge changes and integrate applicable target change.
- 34: Clean three-way merge.
- 1: Already matched target; no file replacement.
- 2: StreamBridge-owned; upstream value excluded.


The source-change commit is the newest non-merge commit in the verified tag range that modifies the
path; paths introduced through a merge-only change point to the verified target merge commit. The
output records source provenance; it does not claim Android/iOS builds or device-runtime behavior
are verified by this manifest.
