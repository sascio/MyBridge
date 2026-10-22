# Trakt, Simkl & MDBList configuration

Trakt, Simkl and MDBList are **optional integrations**. When they are not
configured the app builds and runs normally and Settings shows the
missing-credentials state for each one:

| Provider | Message shown when unconfigured |
| --- | --- |
| Trakt | "Trakt is not configured in this build: TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET were empty when the app was built." |
| Simkl | "Missing SIMKL_CLIENT_ID" |
| MDBList | "MDBList connection is unavailable in this build." |

That state is intentional: the Connect button stays disabled rather than starting
an OAuth request that cannot succeed. No credentials are committed to this
repository, and none are required to build.

## Keys

| Key | Required for | Default |
| --- | --- | --- |
| `TRAKT_CLIENT_ID` | Trakt | *(empty)* |
| `TRAKT_CLIENT_SECRET` | Trakt | *(empty)* |
| `SIMKL_CLIENT_ID` | Simkl | *(empty)* |
| `MDBLIST_CLIENT_ID` | MDBList | *(empty)* |
| `TRAKT_REDIRECT_URI` | optional | `nuvio://auth/trakt` |
| `SIMKL_REDIRECT_URI` | optional | `nuvio://auth/simkl` |
| `SIMKL_APP_NAME` | optional | `nuvio` |
| `ANILIST_CLIENT_ID` | optional branding override | compiled-in `53329` |
| `WETRAKR_CLIENT_ID` | optional branding override | compiled-in id |

`ANILIST_CLIENT_ID` / `WETRAKR_CLIENT_ID` do not belong to Trakt/Simkl/MDBList.
They exist so the app can point at a "StreamBridge" registration without a code
edit; see [OAUTH-BRANDING.md](OAUTH-BRANDING.md). Leave them unset to keep the
current behaviour.

Trakt requires **both** the client id and the client secret; a partially
configured Trakt is treated as not configured. Simkl uses PKCE and only needs a
client id. MDBList uses the OAuth 2.0 device-authorization grant and only needs a
client id — **there is no `MDBLIST_CLIENT_SECRET`**, because a device-flow client
is a public client. Do not add one.

### Trakt: current flow vs Trakt's current documentation

StreamBridge (like both upstream pins, `014fa2e2` and `b9504e1a`) uses the classic
authorization-code flow with a **client secret**: authorize at
`https://trakt.tv/oauth/authorize`, exchange/refresh/revoke at
`https://api.trakt.tv/oauth/*`, and no `code_challenge` anywhere. Only Simkl uses
PKCE (S256, `features/simkl/SimklPkce.kt`).

Trakt's documentation has moved on: apps created today receive a Client ID only,
PKCE is the recommended sign-in, the client secret is optional and deprecated for
user sign-in, and OAuth calls should target `auth.trakt.tv`. An ID-only PKCE
rewrite is deferred to a future release; the approved 0.1.13 build still expects
a Trakt registration with a secret. Do not add a secret that you do not have —
an unconfigured build says so instead of starting an OAuth request that cannot
succeed. The full comparison is in
[upstream-parity-0.6.3.md](upstream-parity-0.6.3.md) (section "Trakt").

## Sources and precedence

There are two resolution paths, and they are deliberately different.

**Trakt and Simkl** use the **same credential-supply mechanism as official
NuvioMobile**: their keys are read from `local.properties` and nothing else.

Upstream's `GenerateRuntimeConfigsTask` resolves them with
`props.getProperty("TRAKT_CLIENT_ID", "")` — reading only the loaded
`local.properties` — even though its generic helper consults the environment for
other keys. StreamBridge mirrors that with `runtimeLocalPropertyValue()`.

> Individual `TRAKT_*` / `SIMKL_*` environment variables are **not** supported.
> That was a StreamBridge-only deviation and has been removed so there is exactly
> one credential path, shared with official Nuvio.

**MDBList** is resolved with `runtimeConfigValue("MDBLIST_CLIENT_ID")`, the same
helper as non-credential keys such as `NUVIO_SUPABASE_URL` and `TMDB_API_KEY`. Its
precedence is:

```
local.properties  ->  environment variable  ->  -P gradle property  ->  ""
```

That is a superset of upstream (which reads `MDBLIST_CLIENT_ID` from
`local.properties` only), so anything that works upstream also works here, and CI
may additionally supply it as its own GitHub Secret. `local.properties` always
wins when both are set.

Values are trimmed and one layer of matching surrounding quotes is removed, so
`KEY=value` and `KEY="value"` resolve identically. A blank value is treated as
"not set".

### Local development

Add to `local.properties` (gitignored):

```properties
TRAKT_CLIENT_ID=your-trakt-client-id
TRAKT_CLIENT_SECRET=your-trakt-client-secret
SIMKL_CLIENT_ID=your-simkl-client-id
MDBLIST_CLIENT_ID=your-mdblist-client-id
```

### CI / release

Exactly as official Nuvio's `android-release.yml` does it: a single repository
secret **`NUVIO_LOCAL_PROPERTIES_BASE64`** holds a base64-encoded
`local.properties`, which `.github/workflows/release-draft.yml` decodes into the
repository root *before* Gradle runs. Trakt and Simkl are read from that file and
nowhere else, so **if the secret is absent, incomplete or malformed, the APK ships
with Trakt unconfigured** — nothing else in the build can supply it.

The workflow now:

1. decodes the secret into a temporary file and only then moves it to
   `local.properties` (a malformed secret can no longer leave a half-written file
   for Gradle), failing with an explicit `::error::` if the value is not base64;
2. rejects a secret that decodes to something without `KEY=value` lines, because
   that is never a properties file;
3. prints the **property names** the decoded file defines — names only, never
   values — so you can see what the stored secret really contains;
4. strips `sdk.dir` and `NUVIO_RELEASE_STORE_FILE` (runner-specific; the keystore
   is decoded separately);
5. prints a **presence-only report** for `TRAKT_CLIENT_ID`,
   `TRAKT_CLIENT_SECRET`, `SIMKL_CLIENT_ID` and `MDBLIST_CLIENT_ID`, plus a step
   summary table and a `::warning::` for every missing provider. Tracking remains
   optional, so a missing provider is still not a build failure by default. Pass
   the workflow input **`require_tracking_credentials=true`** to make a release
   fail instead of warn when `TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET` are absent;
6. after assembling, greps the *generated* `TraktConfig.kt`, `SimklConfig.kt` and
   `MdbListConfig.kt` to confirm the constants are non-empty — presence only,
   never values. Each line is prefixed with its provider (`TRAKT CLIENT_ID
   present=true`, …) because all three constants share the name `CLIENT_ID`, and
   every empty constant produces its own warning.

#### Check the secret without building anything

Run **Actions → Check tracking secrets → Run workflow**
(`.github/workflows/check-tracking-secrets.yml`). It decodes the stored secret
exactly like the release does, lists the property names it finds, and fails with a
clear message if Trakt's two keys are missing. It builds nothing, publishes
nothing, and never prints a value — use it before every release.

#### Updating `NUVIO_LOCAL_PROPERTIES_BASE64` safely (beginner steps)

GitHub secrets are **write-only**: the website never shows you the current value,
so you cannot "append one line". You must rebuild the whole properties file from
your own records and replace the secret. This is where most failures come from.

1. Collect every tracking property you already use. The build reads these:
   `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET`, `SIMKL_CLIENT_ID`,
   `MDBLIST_CLIENT_ID`, and optionally `TRAKT_REDIRECT_URI`,
   `SIMKL_REDIRECT_URI`, `SIMKL_APP_NAME`, `ANILIST_CLIENT_ID`,
   `WETRAKR_CLIENT_ID`. Include the keys for every provider you want configured —
   a file containing only the Trakt keys silently disables Simkl and MDBList.
2. Write them as plain `KEY=value` lines with no quotes and no spaces around `=`.
3. Base64-encode the whole file as **one line**. With no PC you can use either:
   * **Termux** (Android, from F-Droid):
     ```bash
     cat > local.properties
     # paste the KEY=value lines, then press Ctrl-D
     base64 -w0 local.properties
     ```
   * **GitHub Codespaces** (works in a phone browser): on the repository page
     press `.` or open *Code → Codespaces → Create codespace*, then run the same
     two commands in its terminal.
4. Copy the single long string that `base64 -w0` prints.
5. Open
   <https://github.com/sascio/MyBridge/settings/secrets/actions>
   (repository page → **Settings** → **Secrets and variables** → **Actions**).
6. If `NUVIO_LOCAL_PROPERTIES_BASE64` already exists, open it and choose
   **Update**; otherwise choose **New repository secret**.
7. Name it **exactly** `NUVIO_LOCAL_PROPERTIES_BASE64` (no spaces, no suffixes),
   paste the string, save.
8. Do **not** touch `NUVIO_RELEASE_STORE_BASE64`,
   `NUVIO_RELEASE_STORE_PASSWORD`, `NUVIO_RELEASE_KEY_ALIAS` or
   `NUVIO_RELEASE_KEY_PASSWORD`. Those are the Android signing secrets and are
   unrelated to tracking credentials.
9. Run **Actions → Check tracking secrets** and confirm
   `TRAKT_CLIENT_ID present=true` and `TRAKT_CLIENT_SECRET present=true`.
10. Build a new APK: the credentials are compiled in at build time, so an already
    installed APK can never pick them up. Either start a new **Draft production
    GitHub Release** for a new version (the tag must equal
    `STREAMBRIDGE_VERSION_NAME`) or install a CI build.

`build.yml` (CI compile validation) does not supply credentials at all, so its
APKs intentionally build in the unconfigured state — an APK downloaded from the
**Build Stream Bridge** workflow will always show the missing-credentials state,
even when the release APK is configured.

`build.yml` (CI compile validation) does not supply credentials at all, so its
APKs intentionally build in the unconfigured state.

## Pipeline

```
Trakt / Simkl:
  NUVIO_LOCAL_PROPERTIES_BASE64 (CI secret)
    -> decoded local.properties        (release-draft.yml, before Gradle)
    -> runtimeLocalPropertyValue()     (composeApp/build.gradle.kts)
    -> GenerateRuntimeConfigsTask      (@Input properties)
    -> generated TraktConfig.kt / SimklConfig.kt
    -> TraktAuthRepository / SimklAuthRepository
    -> RuntimeCredentials -> hasRequiredCredentials()
    -> Settings UI / OAuth / sync + scrobbling

MDBList:
  NUVIO_LOCAL_PROPERTIES_BASE64  |  MDBLIST_CLIENT_ID secret (env)
    -> local.properties or environment
    -> runtimeConfigValue("MDBLIST_CLIENT_ID")
    -> GenerateRuntimeConfigsTask
    -> generated MdbListConfig.kt (CLIENT_ID)
    -> MdbListTracker -> MdbListConfiguration(clientId, …)
    -> MdbListAuthRepository.hasRequiredCredentials()
    -> Settings UI / device-flow OAuth / sync + scrobbling
```

All three generated configs land in
`composeApp/build/generated/runtime-config/kotlin`, which is added to the
`commonMain` source set, so every variant — debug, full and Play Store, Android
and iOS — is configured identically; only the values supplied to the build differ.

## Verifying

The task logs presence only — never the values:

```
generateRuntimeConfigs: TRAKT_CLIENT_ID present=true TRAKT_CLIENT_SECRET present=true SIMKL_CLIENT_ID present=true MDBLIST_CLIENT_ID present=true ANILIST_CLIENT_ID override=false WETRAKR_CLIENT_ID override=false
```

```bash
./gradlew :composeApp:generateRuntimeConfigs
```

> `local.properties` is read through a Gradle value provider so the
> configuration cache correctly invalidates when you edit it. Reading it with
> plain file IO made stale, empty credentials persist across builds.

## Security

- Never commit `local.properties` (it is gitignored).
- Never hardcode client ids/secrets in source.
- Never log credential values; log presence booleans only. Both the Gradle task
  and the release workflow emit `present=true|false` and nothing else.
