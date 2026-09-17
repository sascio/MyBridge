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
| `SIMKL_APP_NAME` | optional | `StreamBridge` |

Trakt requires **both** the client id and the client secret; a partially
configured Trakt is treated as not configured. Simkl uses PKCE and only needs a
client id.

## Sources and precedence

`:composeApp:generateRuntimeConfigs` resolves each key in this order:

1. `local.properties` (repository root — gitignored, never commit it)
2. environment variable (used by CI via GitHub Actions secrets)
3. Gradle project property (`-PTRAKT_CLIENT_ID=...`)

Values are trimmed and one layer of matching surrounding quotes is removed, so
`KEY=value`, `KEY="value"` and a CI secret with a trailing newline all resolve
identically. A blank value is treated as "not set" and falls through to the next
source.

### Local development

Add to `local.properties` (gitignored):

```properties
TRAKT_CLIENT_ID=your-trakt-client-id
TRAKT_CLIENT_SECRET=your-trakt-client-secret
SIMKL_CLIENT_ID=your-simkl-client-id
```

### CI

Set repository secrets named `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET` and
`SIMKL_CLIENT_ID`. They are passed to Gradle as environment variables in
`.github/workflows/build.yml` and `.github/workflows/release-draft.yml`. If a
secret is unset the corresponding value stays empty and the build still
succeeds with the integration reported as unconfigured.

## Pipeline

```
local.properties / env / -P
  -> runtimeConfigValue()            (composeApp/build.gradle.kts)
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
