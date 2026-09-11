# Long-term Nuvio upstream sync

StreamBridge is **NuvioMobile core + StreamBridge branding/updater**.
NuvioMobile (`cmp-rewrite`) moves often. Do not freeze on one commit,
and do **not** auto-merge upstream into the production branch.

## Pin

Canonical pin: [`streambridge.version.properties`](../streambridge.version.properties)
and [`UPSTREAM.md`](UPSTREAM.md).

## Flow

```
NuvioMobile cmp-rewrite
        ↓  (watch workflow, daily)
new SHA detected → GitHub issue (no auto-merge)
        ↓
review branch on arena/… (or equivalent)
        ↓
overlay upstream sources; re-apply StreamBridge deltas
        ↓
./gradlew :androidApp:assembleFullDebug :androidApp:assembleFullRelease
        ↓
device check for playback-critical paths
        ↓
merge when verified
```

## StreamBridge deltas to re-apply after every sync

Keep these; they are not Nuvio bugs:

- `applicationId` `com.streambridge.app` (debug and release)
- `app_name` / `app_brand_name` StreamBridge
- Gradle heap + `nuvio.android.distribution=full`
- `generateRuntimeConfigs` skips missing `local.properties`
- StreamBridge version properties (do not ship Nuvio’s version as ours)
- Updater GitHub owner/repo = `sascio/MyBridge` (never NuvioMedia)
- Empty addons/plugins; empty Trakt/Simkl/TMDB/Supabase keys
- Release signing fallback for CI
- Branding assets under `branding/` and launcher/splash overlays

Prefer upstream for player, addons, networking, metadata, settings.

## Automation

[`.github/workflows/nuvio-upstream.yml`](../.github/workflows/nuvio-upstream.yml)
compares `NUVIO_UPSTREAM_COMMIT` to `NuvioMedia/NuvioMobile@cmp-rewrite`
and opens an issue. It never pushes a production merge.
