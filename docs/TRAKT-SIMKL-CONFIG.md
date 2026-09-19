# Trakt & Simkl configuration

Trakt and Simkl are **optional integrations**. When they are not configured the
app builds and runs normally and Settings shows the missing-credentials state.
No credentials are committed to this repository, and none are required to build.

## Keys

| Key | Required for | Default |
| --- | --- | --- |
| `TRAKT_CLIENT_ID` | Trakt | *(empty)* |
| `TRAKT_CLIENT_SECRET` | Trakt | *(empty)* |
| `SIMKL_CLIENT_ID` | Simkl | *(empty)* |
| `TRAKT_REDIRECT_URI` | optional | `nuvio://auth/trakt` |
| `SIMKL_REDIRECT_URI` | optional | `nuvio://auth/simkl` |
| `SIMKL_APP_NAME` | optional | `nuvio` |

Trakt requires **both** the client id and the client secret; a partially
configured Trakt is treated as not configured. Simkl uses PKCE and only needs a
client id.

## Sources and precedence

StreamBridge uses the **same credential-supply mechanism as official
NuvioMobile**: the Trakt and Simkl keys are read from `local.properties` and
nothing else.

Upstream's `GenerateRuntimeConfigsTask` resolves them with
`props.getProperty("TRAKT_CLIENT_ID", "")` — reading only the loaded
`local.properties` — even though its generic helper consults the environment for
other keys. StreamBridge mirrors that with `runtimeLocalPropertyValue()`.

> Individual `TRAKT_*` / `SIMKL_*` environment variables are **not** supported.
> That was a StreamBridge-only deviation and has been removed so there is exactly
> one credential path, shared with official Nuvio. Non-credential keys such as
> `NUVIO_SUPABASE_URL` and `TMDB_API_KEY` still use `runtimeConfigValue()`, which
> does accept the environment.

Values are trimmed and one layer of matching surrounding quotes is removed, so
`KEY=value` and `KEY="value"` resolve identically. A blank value is treated as
"not set".

### Local development

Add to `local.properties` (gitignored):

```properties
TRAKT_CLIENT_ID=your-trakt-client-id
TRAKT_CLIENT_SECRET=your-trakt-client-secret
SIMKL_CLIENT_ID=your-simkl-client-id
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
3. **fails the build** if `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET` or
   `SIMKL_CLIENT_ID` is missing or empty, rather than silently shipping an APK
   that reports "Missing TRAKT_CLIENT_ID";
4. after assembling, greps the *generated* `TraktConfig.kt`/`SimklConfig.kt` to
   confirm the constants are non-empty — presence only, never values.

`build.yml` (CI compile validation) does not supply credentials at all, so its
APKs intentionally build in the unconfigured state.

## Pipeline

```
NUVIO_LOCAL_PROPERTIES_BASE64 (CI secret)
  -> decoded local.properties        (release-draft.yml, before Gradle)
  -> runtimeLocalPropertyValue()     (composeApp/build.gradle.kts)
  -> GenerateRuntimeConfigsTask      (@Input properties)
  -> generated TraktConfig.kt / SimklConfig.kt
     (build/generated/runtime-config/kotlin, added to commonMain srcDir)
  -> TraktAuthRepository / SimklAuthRepository
  -> RuntimeCredentials -> hasRequiredCredentials()
  -> Settings UI / OAuth / sync + scrobbling
```

The same generated config is used by every variant, so debug and release behave
identically; only the values supplied to the build differ.

## Verifying

The task logs presence only — never the values:

```
generateRuntimeConfigs: TRAKT_CLIENT_ID present=true TRAKT_CLIENT_SECRET present=true SIMKL_CLIENT_ID present=true
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
- Never log credential values; log presence booleans only.
