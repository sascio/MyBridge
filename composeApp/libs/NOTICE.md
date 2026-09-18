# Third-party binary artifacts

## CloudStream runtime API snapshot

StreamBridge's Android **full (sideload)** distribution embeds the `library`
Android artifact built from the official CloudStream repository:

- Repository: https://github.com/recloudstream/cloudstream
- Commit: `3496e5f8d2ebae4c1b5bdf264782f58375c1eb06`
- Upstream version at build time: `4.8.0` / library `1.0.1`
- Embedded artifact: `composeApp/libs/cloudstream-runtime-api-4.8.0-3496e5f.aar`
- SHA-256: `b67a4384bea1f4072123b86c5f164471422d9c6c12845d5067f12db44674d427`

CloudStream is licensed under **GPL-3.0**. StreamBridge is also distributed
under GPL-3.0; the repository root `LICENSE` / `COPYING` contains the applicable
license text, so embedding this artifact introduces no additional licensing
obligation beyond this attribution.

### Scope and integrity

- The artifact is used **only** by the sideload-oriented Android `full` build.
  It is declared inside `if (androidDistribution == "full")` in
  `composeApp/build.gradle.kts`, and its filename deliberately does not match
  the unconditional `lib-*.aar` fileTree so it cannot be picked up implicitly.
- The **Play Store** distribution does not include this runtime and cannot
  execute downloaded CloudStream DEX code.
- The artifact is pinned by content hash and verified by the
  `verifyCloudStreamRuntime` Gradle task, which every full-distribution Kotlin
  compilation depends on. Replacing the file without updating the expected hash
  fails the build.
- The artifact is committed to the repository rather than downloaded at build
  time, so builds are reproducible and no runtime binary is fetched from the
  network during compilation.

See `docs/CLOUDSTREAM-AUDIT.md` for the security boundary and the rationale.
