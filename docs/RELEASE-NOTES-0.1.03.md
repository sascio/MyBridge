# StreamBridge 0.1.03

User-facing changes in this release. Based on NuvioMobile 0.4.17
(`d177eb57dfc8989e56de577bda8f4c2714b2bd64`).

## Profiles: avatar catalog now loads

- The avatar catalog in Profile → Edit Profile now loads from the official
  Nuvio backend using its published public client configuration, the same
  one official Nuvio clients use. It no longer depends on a per-build
  injected key.
- The catalog request uses the `get_avatar_catalog` backend call and
  avatar images use the official public storage path
  (`/storage/v1/object/public/avatars/…`).
- Retry genuinely re-requests the catalog from the backend.
- Empty state now means the backend really returned no usable avatars.
  Network, request, or decode failures still surface as an error with a
  retry option — they are never shown as an empty catalog.
- Supporter avatars still require a membership and are downloaded only
  for members.

## Settings: in-app Privacy & Policy page

- Settings → Privacy & Policy now opens an in-app policy page instead of
  the old external Nuvio privacy URL.
- The page is also reachable from Settings search.
- The policy describes only what StreamBridge actually does: what is
  stored on the device, the default Nuvio account backend (not operated
  by StreamBridge), the addons, plugins, and optional services you
  configure yourself (TMDB, MDBList, OMDb, Trakt, Simkl, debrid, Live TV
  sources, skip-intro providers), optional peer-to-peer streaming, update
  checks on GitHub, and opt-out crash reporting.

## Documentation and branding

- README now uses the transparent StreamBridge mark (true alpha, legible
  on light and dark backgrounds) instead of the opaque launcher plate.
- README feature list updated for the avatar catalog and the new privacy
  page.

## Other

- Version bumped to 0.1.03 (103).

## Notes

- Playback, providers, the plugin runtime, and the Nuvio core are
  unchanged in this release.
