# StreamBridge Playback Compatibility

This document is the compatibility decision record for StreamBridge's
playback engine, benchmarked against **Nuvio's** playback behavior on the
same Android device: sources that play in Nuvio must play in StreamBridge
whenever the device's decoders allow it.

Method: Nuvio's player source (`NuvioMedia/NuvioMobile`, branch
`cmp-rewrite`, `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/`)
was read directly; Media3 behavior was verified against the 1.5.1 sources
StreamBridge builds with. Nothing below is guessed.

---

## 1. The reference (Nuvio) playback architecture

Nuvio runs a **three-tier escalation ladder**, configured in
`PlayerEngine.android.kt`:

| Tier | Engine | When it is used |
|---|---|---|
| 1 | Media3/ExoPlayer + device MediaCodec decoders | Always first (setting `Auto` / `ExoPlayer`) |
| 2 | Media3/ExoPlayer + **software extension renderers** | Decoder failure while extensions were only "available": the player is rebuilt with `EXTENSION_RENDERER_MODE_PREFER` and the same position is retried |
| 3 | **libmpv** (`is.xyz.mpv`) | Any remaining ExoPlayer error in `Auto` mode switches the engine to libmpv for the same source |

Facts read from the source:

- **Extension software decoders are bundled as locally built AARs**
  (`composeApp/libs/`): `lib-decoder-ffmpeg-release.aar`,
  `lib-decoder-av1-release.aar`, `lib-decoder-mpegh-release.aar`.
  These are the Media3 extension decoders built from source with the NDK.
  They are **not published on Maven** (Google publishes only the core
  media3 artifacts) — every consumer must build or vendor them.
- `DefaultRenderersFactory … setEnableDecoderFallback(true)`
  `.setExtensionRendererMode(<decoder priority setting>)`
  `.setMapDV7ToHevc(setting)`.
- Decoder failure escalation (tier 2): on `error.isDecoderFailure()` with
  extensions at `EXTENSION_RENDERER_MODE_ON`, retry once at
  `EXTENSION_RENDERER_PREFER` from the current position.
- Engine switch (tier 3): in `Auto` mode ANY ExoPlayer error (after tier
  2) switches the surface to libmpv for the same source.
- Track selector: `DefaultTrackSelector` with
  `setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)`
  (tunneling and several text/subtitle options behind settings).
  **No audio codec blacklist, no audio muting, no forced stereo anywhere.**
- Load control: `DefaultLoadControl` with a **100 MB target buffer** and
  15 s / 70 s buffer durations.
- Extractors: HDMV/DTS TS flag + 1500×TS-packet timestamp search.
- MIME/container: provider hint → URL evidence → HTTP probe cascade
  (`PlaybackMediaItems.android.kt`), `setMimeType` on the MediaItem.
- Headers: per-source headers applied through a source-scoped data source
  factory (manifest, segments, keys, redirects).
- libmpv surface exposes `hardwareDecodingEnabled` — software decoding is
  a per-tier choice, not a global constant.
- Nuvio builds against **media3 1.8.0**; StreamBridge against **1.5.1**.

## 2. Compatibility matrix

"StreamBridge" = the media3 backend + this device's MediaCodec decoders,
queried at runtime (`PlaybackCapabilities` → `MediaCodecUtil`). Nothing is
hardcoded per codec: a capable device gets playback, an incapable one gets
an honest, classified failure with the capability verdict recorded.
"Nuvio" = same device, tiers 1–3 above.

| Format | StreamBridge (media3 + MediaCodec) | Device dependency | Nuvio (same device) | Required backend for full parity |
|---|---|---|---|---|
| H.264/AAC (MP4) | ✅ plays | AVC decode is universal on Android | ✅ tier 1 | none |
| HEVC/AAC | ✅ when the device has an HEVC decoder | HEVC hw decoder (most modern SoCs, not all) | ✅ tier 1; else tier 2/3 software HEVC | software video decoder (tier 2/3) for HEVC-less devices |
| H.264/E-AC-3 | track fallback to AAC when present, else video-only + notice | E-AC-3 MediaCodec decode is device-dependent (common on Dolby devices, absent on many) | ✅ ffmpeg renderer / libmpv software E-AC-3 | software audio decoder (tier 2/3) |
| HEVC/E-AC-3 | same as above | both, see rows above | ✅ tier 2/3 | software audio + (device-dependent) video decoder |
| VP9/Opus (WebM) | ✅ when the device decodes VP9 | VP9 hw decoder (very common, not universal); Opus decode near-universal | ✅ tier 1; else software | software video decoder for VP9-less devices |
| AV1/Opus | ✅ when the device has an AV1 decoder | AV1 hw decoder (recent SoCs only) | ✅ gav1 software decoder (bundled) | software video decoder (tier 2/3) |
| MKV | ✅ container fine (`MatroskaExtractor`); codec rows above apply | audio codec rows (MKV often carries DTS/TrueHD/E-AC-3) | ✅ incl. software DTS/TrueHD | software audio decoder for those tracks |
| MPEG-TS | ✅ with HDMV-DTS flag + deep timestamp search | codec rows above | ✅ same configuration | codec rows above |
| HLS | ✅ `HlsMediaSource`, all tracks/languages/bandwidth/HDR preserved; keys+segments carry the source's headers | codec rows above | ✅ tier 1 | codec rows above |
| DASH | ✅ `DashMediaSource`, same guarantees | codec rows above | ✅ tier 1 | codec rows above |
| MP3 / AAC / HE-AAC / Opus / Vorbis / FLAC / PCM | ✅ | decode effectively universal on device MediaCodec | ✅ | none |
| AC-3 | ✅ when the device decodes AC-3 | AC-3 MediaCodec decode is device-dependent | ✅ tier 2/3 | software audio decoder |
| E-AC-3 (incl. Atmos JOC) | track fallback → video-only + notice when no AAC/other track exists | E-AC-3 decode device-dependent | ✅ tier 2/3 | software audio decoder |
| DTS / DTS-HD / TrueHD | TS extraction enabled; decode almost never exposed by MediaCodec → classified + alternate source | hw decode rare | ✅ tier 2/3 software | software audio decoder (tier 2/3) |
| MPEG-2 / MPEG-4 video | ✅ when the device decodes them | device-dependent | ✅ tier 2/3 | software video decoder |

**Matrix decision** (per the task's rule: configuration first, then
software decoding, then a second backend, no blind dependencies):

1. **Better Media3 configuration — DONE.** Decoder fallback, capability-
   aware audio track selection/recovery, the reference buffering profile,
   `allowInvalidateSelectionsOnRendererCapabilitiesChange`, container/MIME
   cascade and full-request-context headers are implemented and unit-
   tested (see §3). This tier is what makes "device CAN decode it" and
   "StreamBridge fails" stop happening.
2. **Software decoding (tier 2) — architecture in place, artifacts not
   bundled.** Media3 extension decoders are not published on Maven; the
   reference app vendors NDK-built AARs. Building/hosting those binaries
   requires NDK infrastructure and real-device validation, which this
   sandbox does not have (see §5) — adding them blind would violate the
   task's own constraints. StreamBridge already wires the slot:
   `SoftwareDecoderExtensions` detects bundled extension renderers and
   `DefaultRenderersFactory` enables them automatically the day they are
   added to the build (no code change needed).
3. **libmpv (tier 3) — same conclusion.** A heavy native dependency with
   licensing and device-validation requirements; not added blind. The
   `PlaybackBackend`/`PlaybackBackendSelector` contract is the plug-in
   point: a libmpv backend registers itself and wins exactly when media3
   reports the format unsupported — capability-based, never
   provider-based.

## 3. StreamBridge playback architecture (as implemented)

```
PlayerViewModel (PlaybackController role)
  ├─ PlaybackPlanning      → URL + per-source headers + container MIME
  ├─ PlaybackBackendSelector ──► Media3PlaybackBackend (primary)
  │                            └► [future software/libmpv backend]
  └─ PlayerHolder (media3 backend)
       ├─ PlaybackCapabilities ← MediaCodecDecoderRegistry (device query)
       ├─ AudioTrackPolicy     → capability-aware audio selection/recovery
       ├─ PlaybackFailureClassifier → 10 clean categories
       └─ PlaybackDiagnostics  → redacted structured facts (host only,
                                header names only, class-name causes)
```

Key behaviors:

- **Audio is first-class.** Every audio track is preserved and inspected
  (codec, language, channels, bitrate, capability). A *supported*
  preferred track is never downgraded. A *known-undecodable* selected
  track is switched preemptively; after a decoder failure the next
  compatible track is selected (failed track's language preferred, then
  channels, then bitrate; every track at most once; chain capped at 3).
  **Audio is muted only when no decodable audio track exists at all** —
  and then the notice and diagnostics state the real reason. Audio
  failures never become video failures; video failures never become
  audio-only playback.
- **No blacklists.** Every capability verdict comes from
  `MediaCodecUtil` at runtime, per device, per MIME — the same codec from
  any provider gets the same treatment.
- **Bounded fallback everywhere.** In-place audio recovery (≤3, distinct
  tracks), then the next untried alternate source (once per source, per
  session), then a clean error. No infinite retries, no hammering, the
  user can always pick another source.
- **Diagnostics.** `PlaybackDiagnostics` retains backend, source, host,
  container MIME, video/audio MIME + codec strings, selected and
  available audio tracks, HTTP status, cause chain (class names),
  fallback attempts and the final category. Cookies, tokens and signed
  URL paths/queries are structurally excluded.

## 4. Root cause of the "playing the video without audio" reports

The message appeared on sources Nuvio plays with audio because of a
chain of player-layer defects (NOT a device limitation):

1. Decoder fallback was disabled → the first `DecoderInitializationException`
   (typically E-AC-3) ended recovery immediately.
2. The old degradation picked the **first track with a different MIME**
   — without any capability check — so it often picked another
   undecodable track (AC-3 after E-AC-3), which failed again.
3. Degradation was **one-shot for the whole session** (`audioDegradationAttempted`
   never reset per source), so later sources got no audio recovery at all.
4. When the heuristic found nothing, it muted — hiding the real cause
   ("no decoder for E-AC-3 on this device, no alternative track in this
   stream") behind a generic message.

Nuvio plays these sources with audio because its tier-2/3 software
decoders decode E-AC-3/DTS families that device MediaCodec often cannot.
StreamBridge now: recovers via a decodable alternative track in the same
stream when one exists (the common HLS case: E-AC-3 5.1 + AAC stereo),
and states the exact missing capability when one does not. The remaining
gap to Nuvio is the software-decoding tier (§2, decision 2).

## 5. On-device validation protocol (required before claiming parity)

CI proves compilation and the pure decision layers only. Real-device
validation must run the matrix of §2 with the debug APK:

1. `adb install` the CI debug APK artifact.
2. For each source class — H.264/AAC, HEVC, E-AC-3, DTS-in-TS,
   header-dependent (Referer/Cookie), previously-403, previously
   container-failed — play the SAME source in Nuvio and StreamBridge.
3. Record per source (all available in logcat `SBPlayer`
   `PlaybackDiagnostics(…)`): backend, container, codecs, selected +
   available audio tracks, HTTP status, fallback attempts, category.
4. Compare with Nuvio: engine used (ExoPlayer vs libmpv — Nuvio logs
   `ExoPlayer failed; falling back to libmpv`), selected audio track,
   playback result.
5. Device decoder inventory: `adb shell dumpsys media.codec |
   grep -iA2 'audio/eac3\|video/hevc\|c2.android.av1'` — the ground
   truth `PlaybackCapabilities` queries through `MediaCodecUtil`.

Expected outcomes: formats the device decodes → identical playback;
formats only software decodes → StreamBridge classifies honestly and
falls back within the source (or to the next source) while Nuvio's
tier 2/3 plays them — the documented, deliberate gap pending the
software-decoding tier.

## 6. Verification status

| Claim | Verified by |
|---|---|
| Capability layers (registry, audio policy, diagnostics, backend selector) compile and behave per contract | CI unit tests (this branch) |
| No codec blacklist / no provider-specific rule exists | Code + tests |
| Redaction (no cookies/tokens/signed URLs in diagnostics) | CI unit tests |
| Player wiring (track recovery, per-stream reset, buffering profile) | Code review + CI build; **needs device run (§5)** |
| Nuvio comparison facts | Read from NuvioMobile `cmp-rewrite` source directly |
| "Plays what Nuvio plays" on a real device | **NOT yet verified — no device in CI** |
