# Corrected 0.1.14 build — distribution record

This release fixes regressions inside 0.1.14 without opening a new version:
version name stays `0.1.14`, version code stays `114`, tag stays `v0.1.14`.

## What was corrected

- About Us heading restored (had briefly read "Supporters & Contributors").
- Settings footer: motto removed, lockup/attribution/version centred,
  version line last. Attribution and version are unchanged otherwise.
- Home hero section now renders the rich metadata rows (type, genre, runtime,
  release date, IMDb, age rating/status, language, external ratings, synopsis)
  from the app's existing metadata cache.
- Remaining user-facing "Nuvio" strings (all locales) say StreamBridge;
  licence attributions, privacy-policy backend disclosures and the
  `api.nuvio.tv` host are preserved.
- Five resource files that no code referenced were removed from the source
  tree (release APK output is unchanged by this: resource shrinking already
  stripped them).

## Same-version updater limitation

The corrected build keeps version code 114. An installed first-run 0.1.14 does
not see this rebuild as an available update, because the updater compares
version codes and 114 is not greater than 114. Existing 0.1.14 users install
the corrected APK for their ABI directly from the release page. Installs of
earlier versions and new installs update normally. The version code was not
bumped to work around this; that decision belongs to the release owner.

## How the corrected build is distributed

The `v0.1.14` tag was moved once, from the first build's commit to the
corrections commit, and the release workflow rebuilt and re-verified the
production assets. The first build is preserved:

- Backup tag: `v0.1.14-build-1` → the original build commit
  (`25335785ad84a3fb85386d3a627cc32295fb767d`). Checking out that ref and
  running the release workflow from it reproduces the original assets.
- The original release assets were recorded before replacement (below). They
  were replaced under identical names so the in-app updater's ABI-matching
  asset lookup stays stable. Nothing else about the release (title, creation
  date, discussion) changed.

### Original first-build assets (SHA-256, recorded 2026-11-03)

| Asset | Size (B) | SHA-256 |
|---|---:|---|
| `androidApp-full-arm64-v8a-release.apk` | 54720370 | `9d950823bea2abc0d47a0085dd37c7d3b82b1045a1ccdb8e4eab68c469f6f056` |
| `androidApp-full-armeabi-v7a-release.apk` | 52837269 | `309ce9a2e06699776a7f4fc14f0f4e8c73f500f17b0e4c1054e101d95004bf64` |
| `androidApp-full-x86-release.apk` | 55165068 | `f8d7c2cbdbe3008b80cb282189dbba08dfb4b4d0ccf2873ff5997c41b4ac68c5` |
| `androidApp-full-x86_64-release.apk` | 56219773 | `fc85d3346c963fdbd87816521f44e8c241f44754b6cbc769aef3dde30e97a418` |
| `checksums.sha256` | 577 | `52f41752a7f7bca842bd6fcfb4f030f3a53f39f67958b675337c285ba381fbb1` |
| `StreamBridge-0.1.14-iOS-unsigned.ipa` | 73141429 | `07ce445aac8bd3711bd1d9311d3926889e9958fbc5f972932890ba88000f811a` |

The release pipeline itself is unchanged and still fail-closed: assets are
verified by digest, debug-signed APKs are rejected, and the iOS IPA is
published unsigned for AltStore/SideStore re-signing.
