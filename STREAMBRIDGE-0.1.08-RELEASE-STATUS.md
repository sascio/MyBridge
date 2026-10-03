# StreamBridge 0.1.08 — production release status

Branch: `arena/01a0fc9f-mybridge` · PR #180 (open) · date: 2026-10-03
`main` is untouched at `c52a2d72` (0.1.07) and is never merged into by this branch.

**The GitHub release has not been created yet.** The release infrastructure was
reworked so that the release *can* be produced end to end without any Apple
credentials, and the resulting workflows were re-validated in CI. Creating and
publishing `v0.1.08` is now a deliberate, manual step (section 2).

---

## 1. What is ready

| Item | Status |
| --- | --- |
| 0.1.07 starting point | `c52a2d7238e3e2874743851f968bb0e14dfe94f9` on `main` (unchanged) |
| Upstream pins | official `5c6028f24d3017f27fc4aeb0b6234b18fcfd2ee8` (0.5.5-beta), Enhanced `8b3bd867e172757c9fd7373da301e6489914f3fc` (0.5.5-beta) |
| CloudStream isolation | no CloudStream development-branch path or code; only the 3 upstream-identical plugin-runtime files from 0.5.5-beta |
| Identity / version | 0.1.08 (108) everywhere; app name StreamBridge on Android and iOS; `com.streambridge.app` |
| Android CI | green: unit tests, R8 `assembleFullRelease`, distribution checks, artifacts uploaded |
| iOS CI | green: unsigned Release IPA builds and validates on `macos-26` |
| iOS distribution | unsigned IPA + `store.json` AltStore/SideStore source; no Apple credentials involved |

### What changed in the release infrastructure

1. **The unsigned IPA is now the published iOS artifact.** Previously the release
   carried no IPA unless Apple signing secrets existed. It is now built unsigned
   by `release-draft.yml`, attached to the release as
   `StreamBridge-<version>-iOS-unsigned.ipa`, and consumed by AltStore/SideStore.
   The job fails if the application comes out signed.
2. **`store.json` + `.github/workflows/update-store-source.yml`** (ported from
   NuvioMobile 0.5.5-beta): on `release: published`, the workflow verifies the
   single published IPA against the release metadata and commits the regenerated
   sideload source.
3. **Brand assets re-encoded** to the sizes the UI renders at
   (`scripts/optimize-app-assets.py`, output available; encoded sizes listed in
   the release report). Adds CI guard to prevent regression.
4. **Action majors aligned** with the upstream pin, clearing the measured
   `Node.js 20 is deprecated` annotations that named `actions/cache@v4` and
   `gradle/actions/setup-gradle@v4`.
5. **`release-draft.yml` concurrency group** added so two runs for one tag cannot
   race over the tag and the draft release.

## 2. Releasing 0.1.08

```bash
cd /home/user/MyBridge
git fetch origin && git reset --hard origin/arena/01a0fc9f-mybridge

# 1. confirm both validation workflows are green on the head commit
gh run list --branch arena/01a0fc9f-mybridge --limit 5

# 2. tag the verified commit; release-draft.yml builds the production APKs and
#    the unsigned IPA, then creates a DRAFT release
git tag v0.1.08 <verified-commit>
git push origin refs/tags/v0.1.08

gh run list --workflow release-draft.yml --limit 2
gh release view v0.1.08 --json isDraft,assets --jq '{isDraft,assets:[.assets[].name]}'

# 3. publishing triggers update-store-source.yml, which writes store.json
gh release edit v0.1.08 --draft=false
gh run list --workflow update-store-source.yml --limit 2
git pull --ff-only   # pick up the store.json commit
```

The Android job fails closed without the four `NUVIO_RELEASE_*` secrets, so a
debug-signed APK can never be published as a production release.

## 3. Known limitations to report with the release

- **Runtime testing**: no device or emulator is available in this environment, so
  app launch, navigation, playback, source selection, downloads and the updater
  were not exercised at runtime. Validation is static plus CI.
- **Artifact inspection**: GitHub's artifact and release CDNs are unreachable from
  this sandbox, so APK/IPA bytes cannot be downloaded and re-inspected here.
  Validation relies on the workflows' own assertions (production upload
  certificate pinned, debug certificate rejected, per-ABI APK presence, and the
  iOS script's name/bundle-id/version/architecture/signing checks) plus the IPA
  composition report the iOS jobs write to the job summary.
- **Pre-existing brand leaks**: 170 strings across 21 locales still say "Nuvio"
  where English says StreamBridge (present since 0.1.07). Left untouched
  deliberately — a translation change, not a release blocker.
- **iOS sideloading limits**: a free Apple ID signs for 7 days and allows 3
  sideloaded apps and 10 App IDs per week. AltStore/SideStore refresh before
  expiry. An Apple Developer account only raises those limits.
