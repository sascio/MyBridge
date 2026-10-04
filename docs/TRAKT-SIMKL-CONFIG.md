# Trakt, Simkl & MDBList configuration

Trakt, Simkl and MDBList are **optional integrations**. When they are not
configured the app builds and runs normally and Settings shows the
missing-credentials state for each one:

| Provider | Message shown when unconfigured |
| --- | --- |
| Trakt | "Missing TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET in local.properties." |
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

Trakt requires **both** the client id and the client secret; a partially
configured Trakt is treated as not configured. Simkl uses PKCE and only needs a
client id. MDBList uses the OAuth 2.0 device-authorization grant and only needs a
client id — **there is no `MDBLIST_CLIENT_SECRET`**, because a device-flow client
is a public client. Do not add one.

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
repository root *before* Gradle runs.

Create it locally from a file that is never committed:

```bash
base64 -w0 local.properties        # paste the output into the secret
```

The workflow then:

1. decodes the secret to `local.properties` (no value is ever echoed);
2. strips `sdk.dir` and `NUVIO_RELEASE_STORE_FILE` (runner-specific; the keystore
   is decoded separately);
3. prints a **presence-only report** for `TRAKT_CLIENT_ID`,
   `TRAKT_CLIENT_SECRET`, `SIMKL_CLIENT_ID` and `MDBLIST_CLIENT_ID`. This is a
   report, not a gate: unlike upstream Nuvio (which hard-fails on missing
   `TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET`), StreamBridge treats tracking as
   optional, so an absent secret produces an empty `local.properties` and a
   perfectly valid APK whose Settings shows the missing-credentials state. Read
   this step's output before shipping a release, or you will only find out on a
   device;
4. after assembling, greps the *generated* `TraktConfig.kt`, `SimklConfig.kt` and
   `MdbListConfig.kt` to confirm the constants are non-empty — presence only,
   never values. Each line is prefixed with its provider (`TRAKT CLIENT_ID
   present=true`, …) because all three constants share the name `CLIENT_ID`.

`MDBLIST_CLIENT_ID` may alternatively be supplied as its own repository secret of
that exact name; the release workflow forwards it to Gradle as an environment
variable, which `runtimeConfigValue()` accepts. Prefer
`NUVIO_LOCAL_PROPERTIES_BASE64` when you already maintain that secret.

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
generateRuntimeConfigs: TRAKT_CLIENT_ID present=true TRAKT_CLIENT_SECRET present=true SIMKL_CLIENT_ID present=true MDBLIST_CLIENT_ID present=true
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
