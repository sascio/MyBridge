# Third-party licensing and binary provenance audit

## Scope and retained obligations

The runnable Android/iOS application remains GPL v3. Root `LICENSE` and `COPYING`
are byte-for-byte unchanged from the clean StreamBridge baseline. The updated
`NOTICE` credits both genuine 0.5.5-beta source projects, StreamBridge modifications,
engine/native dependencies and the separately licensed historical prototype.
Existing code headers and in-app source/library credit are retained; user-facing
rebranding is not copyright removal or a relicense.

This is a source/payload/provenance audit, not a legal certification of every
transitive artifact. Before binary publication, retain all dependency-specific
notices, provide corresponding source for the exact distributed commit and
review the selected native build's license options. A public GPL repository does
not alone justify stripping copyright/notice payloads from redistributable files.

## Verified native engine

| Item | Verified identity |
| --- | --- |
| Engine source release | NuvioMedia/nuvio-engine `v0.1.2` |
| Exact source commit | `f2217b8c6ac4563046be2a55a06981b6b4d1152b` |
| Android AAR | `lib-nuvio-engine-android-0.1.2.aar` |
| Android AAR SHA-256 | `7cb7feed4068818060166f180532a1d86fb28f4ebf16558690c5ee0717086e40` |
| Apple archive SHA-256 | `ed35576d962930d3207b2725fa737f71ef080e35dbfa2f0bf6f21ab1719b7029` |
| Engine license | GPL-3.0-or-later |

The Android binary matches the verified official target byte-for-byte. Its
`classes.jar/META-INF/` actually contains the complete engine license, engine
third-party notice, Boost, libtorrent, OpenSSL and `try_signal` license payloads.
These are preserved, not replaced with StreamBridge copyright. The Apple archive
is pinned and checksum-verified before extraction; no new engine source/license
or dependency version is silently substituted.

The embedded third-party notice records:

- libtorrent 2.0.12, source `740a0b9aeabe00e762cc0efe4a0f27593db2550b`, BSD 3-Clause;
- Boost 1.86.0, Boost Software License 1.0;
- OpenSSL 3.5.7 LTS, Apache-2.0, with platform static-link/build notices;
- exact `try_signal` notices as bundled by libtorrent;
- Text::Template 1.61, a build-only Perl module under Perl/GPL-or-Artistic terms;
- llvm-mingw/LLVM Windows notices in the engine's cross-platform notice (not a
  claim that Windows runtime binaries are shipped in the Android/iOS app).

## Player and platform dependencies

| Component | Licensing/provenance treatment |
| --- | --- |
| NuvioMobile / Enhanced source | Original GPL licensing/source attribution retained at the full pinned commits |
| Kotlin / kotlinx / Compose / AndroidX / Media3 / Ktor / Coil / SQLDelight | Respect their Apache-2.0 and other published notices; target catalog preserved, no speculative upgrades |
| Vendored Media3 UI/ExoPlayer, AV1/FFmpeg/MPEG-H decoder AARs | Exact established target/baseline payloads retained; not renamed as original StreamBridge libraries |
| mpvAndroid 0.1.12 | Verified target dependency; its own mpv/native-source obligations remain applicable |
| MPVKit local package | NuvioMedia/MPVKit at `bb1d0250ddcfa9d220761fcdad6011ac9fecf15e`; Package.swift pins URLs/checksums for its native components |
| MPVKit vs MPVKit-GPL products | The selected non-GPL-suffix product is not a claim that every native component has one generic license; retain each mpv/FFmpeg/font/rendering/network library's terms |
| QuickJS bindings | Target common dependency 1.0.15 and established customized Android runtime AAR remain separately credited/compatible |
| Noto Sans subtitle font | Full original `OFL.txt` and font retained; only CRLF→LF normalization of the notice |
| Historical `legacy/streambridge-app/` | Separate MIT prototype, not built and not the application's license |

The selected MPVKit gitlink has **no standalone top-level LICENSE file** at that
commit. No new license is invented for it; wrapper/native dependency source and
binary notices must be reviewed with the selected product. Several established
player AARs do not contain conveniently named `LICENSE`/`NOTICE` entries in their
ZIP or nested classes.jar. Their licenses must be traced to the upstream build/
source, not inferred from that absence. This audit explicitly records that limit.

Required project attribution remains in [NOTICE](../NOTICE), the application
license/credits screens, source headers and retained notices. Third-party logos
(MDBList/Trakt/Simkl etc.) and service/operator names remain accurate; those are
not own-product branding defects. Source URLs and legal notices must not be
removed merely to reduce the number of `Nuvio` occurrences.

Dependency-resolution and R8 reports are produced by hosted Android validation.
Actual completed run results/artifacts and any remaining publication checks are
listed in [VALIDATION-0.5.5.md](VALIDATION-0.5.5.md).
