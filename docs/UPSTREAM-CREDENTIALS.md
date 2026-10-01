# Optional StreamBridge runtime credentials

Signing credentials and runtime API/OAuth settings are different inputs. Never
commit either, and never put actual values in issue comments, release notes,
logs or chat. `local.properties` and local signing overrides are ignored.

The project preserves the established optional configuration mechanism:
`NUVIO_LOCAL_PROPERTIES_BASE64` in GitHub Secrets, or supported keys in ignored
`local.properties` for a local build. The release helper materializes a mode-600
file without logging values and removes machine-specific SDK/keystore paths.
Gradle tracks relevant configuration inputs and escapes generated Kotlin string
literals; the new MDBList client ID uses that same mechanism.

Optional integrations include Trakt client ID/secret, Simkl client ID/redirect,
TMDB API access, MDBList client identity, Supabase/backend configuration and
other target-source settings. See the generated-config declarations in
`composeApp/build.gradle.kts` and the relevant runtime settings screen for exact
supported keys. User-supplied API keys entered in Settings stay device-local or
use the service's established account/preferences flow; they are not example
credentials that should be copied into this repository.

An unconfigured build must not invent OAuth credentials. Missing optional
integrations display their existing unavailable/unconfigured behavior rather
than silently claiming a service works. The application's existing account/
backend defaults and registered OAuth contracts are retained where technical
compatibility requires them; Nuvio-operated services are accurately named.
Generated canonical app/download links use `streambridge://`; legacy
`nuvio://` callback parsing/registration remains accepted so registered services
and previously configured links are not broken.

Mobile OAuth client constants included by an authorized build are not a substitute
for protecting user access/refresh tokens or signing private keys. Follow the
provider's native-app registration rules, rotate compromised credentials and
review distribution of build artifacts. No actual API/OAuth secret values are
added by this update.

For Android update certificates use [SIGNING.md](SIGNING.md). For Apple
certificate/provisioning setup use [IOS.md](IOS.md). Current GitHub secret metadata
and signed workflow dispatch are inaccessible to this session; no claim is made
that production credentials are either present or absent.
