# CloudStream Phase 1 — read-only audit and gap analysis

Audit performed at commit `49cda7d9` against reference
`yesnt10/NuvioMobile-Enhanced` @ branch `enhanced`.

This document is the Phase 1 deliverable. No production code was changed to produce it.

---

## 1. What StreamBridge already has

| Area | File | Classification |
| --- | --- | --- |
| repo.json / plugins.json parsing | `features/cloudstream/CloudStreamRepositoryParser.kt` | **A — functional** |
| Repository fetch + validation | `CloudStreamRepositoryLoader.kt` | **A — functional** |
| Plugin metadata model | `CloudStreamModels.kt` | **A — functional** |
| CloudStream→StreamBridge mapping | `CloudStreamProviderAdapter.kt` | **A — functional** (links, subtitles, headers, cookies, referer, HLS/DASH, quality) |
| Persistence (repos, source state, config) | `CloudStreamStorage*.kt` | **A — functional** |
| UI state / overview counters | `CloudStreamUiModels.kt`, `CloudStreamExtensionMapping.kt` | **A — functional** |
| Extensions + detail screens | `CloudStreamExtensionsScreen.kt`, `CloudStreamExtensionDetail.kt` | **A — functional** |
| Settings navigation | fixed in `49cda7d9` | **A — functional** |
| Execution seam | `CloudStreamSourceProvider.kt` + `CloudStreamPluginExecutor` | **C — stub seam, no implementation** |
| Aggregator integration | *none* | **D — MISSING** |

### The two decisive gaps

1. **`CloudStreamSourceProvider` is dead code.** Verified by grep: it is referenced
   only from within `features/cloudstream/` and its own tests. Nothing in
   `StreamsRepository` ever constructs or calls it. CloudStream therefore cannot
   contribute a single source to the source picker today.
2. **`CloudStreamPluginExecutor` has no production implementation.** `resolveStreams()`
   begins `val runner = executor ?: return emptyList()`. Every call returns empty.

Current honest scope: **discovery + metadata + UI only. Zero playback.**

---

## 2. What Enhanced has that StreamBridge lacks

| Enhanced component | Purpose |
| --- | --- |
| `composeApp/libs/cloudstream-runtime-api-4.8.0-3496e5f.aar` | CloudStream `library` artifact (GPL-3.0), 2.0 MB. Provides `MainAPI`, `BasePlugin`, `APIHolder`, `extractorApis`, `ExtractorLink`, `SubtitleFile`. |
| `androidFull/.../CloudStreamPlatformRuntime.android.kt` (512 lines) | **Actually executes `.cs3` DEX** via `PathClassLoader`. |
| `androidFull/.../com/lagradost/cloudstream3/**` | Host shims CloudStream plugins expect: `CloudStreamApp`, `CommonActivity`, `PluginManager`, `RepositoryManager`, `CloudflareKiller`, `DdosGuardKiller`, `DataStore`. |
| `CloudStreamPackageInspector.kt`, `CloudStreamSha256.kt` | `.cs3` archive validation + hash verification. |
| `CloudStreamSearchRouteIndex.kt` | Maps StreamBridge metadata → provider search routes. |
| `KickTrCloudStreamProvider.kt` | One hand-written native adapter. |
| `androidPlaystore/**` stubs | Store builds get disabled no-op implementations. |

### Critical discovery — the reference documentation is wrong

`CLOUDSTREAM_CROSS_PLATFORM_COMPATIBILITY.md` states:

> "Execute arbitrary downloaded `classes.dex` — **Not supported**"
> "Downloaded DEX is **never executed**."

This is **contradicted by the code in the same branch**. `CloudStreamPlatformRuntime.android.kt`
lines 127-128:

```kotlin
val loader = PathClassLoader(file.absolutePath, context.classLoader)
val pluginClass = loader.loadClass(pluginClassName)
```

It then calls `getDeclaredConstructor().newInstance()` and `instance.load(context)` — full
execution of downloaded third-party bytecode. The prose describes the *Play Store* flavor;
the *full* (sideload) flavor executes DEX.

`third_party/cloudstream-runtime/` contains **only a `NOTICE.md`** — there is no sandbox, no
interpreter, no safe execution mechanism. **The "controlled runtime" the task hypothesised
does not exist.** The only mechanism that runs real CloudStream providers is
`PathClassLoader`.

Enhanced does apply real guards before loading, which is the reviewable part:
- mandatory SHA-256 `fileHash` match against metadata,
- file forced read-only, size cross-check,
- `manifest.json` must declare `pluginClassName`,
- class must extend `BasePlugin`,
- must register ≥1 provider or the load is rejected and rolled back,
- provider/extractor registration rolled back on failure,
- per-plugin failure isolation.

---

## 3. What StreamBridge can reuse

- `CloudStreamProviderAdapter` already implements the Phase 6/7 conversion contract
  (headers, cookies, Referer, UA, HLS/DASH, quality, subtitles). **No rewrite needed.**
- Parser, loader, storage, UI, and compatibility model are sound and newer than Enhanced's.
- **StreamBridge already has the `full` / `playstore` distribution split**
  (`composeApp/build.gradle.kts:365`, source dirs `src/androidFull/kotlin` and
  `src/androidPlaystore/kotlin`). This is the exact isolation mechanism Enhanced uses —
  it already exists here and needs no new build system.
- `fullCommonMain` already hosts a sandboxed **JS** plugin runtime
  (`features/plugins/runtime/`, QuickJS) — precedent for flavor-gated execution engines.

---

## 4. What Enhanced does NOT support (and we should exceed)

- Only **one** native adapter (`KickTR`). Its non-DEX path is effectively single-provider.
- iOS cannot execute DEX at all (no class loader + App Store rule 2.5.2).
- Its compatibility resolver reduces to "is there a reviewed adapter"; the task explicitly
  forbids that definition of *Unsupported*.

**To exceed it:** compatibility must be derived from the *runtime's* declared capability set
(does the host provide HTTP, cookies, Cloudflare bypass, extractors, the required `apiVersion`),
not from a hardcoded provider allowlist. Any DEX plugin whose `apiVersion` the embedded
runtime supports becomes a candidate — that is what turns "25 extensions" into "all
technically supportable extensions".

---

## 5. Exactly where CloudStream must connect to the aggregator

`features/streams/StreamsRepository.kt`, inside `private fun load(...)`:

- **line 174** — `pluginProviderGroups` is computed from enabled scrapers.
- **line 179** — early-return guard `if (installedAddons.isEmpty() && pluginProviderGroups.isEmpty())`.
  CloudStream providers must be counted here or the screen shows "no addons" incorrectly.
- **line 249** — `activeJob = scope.launch { ... }` is the aggregation scope.
- **lines 446-497** — Stremio addons fan out with `launch { ... }` → `publishCompletion(...)`.
- **lines 499+** — plugin scrapers fan out identically.

**Integration = add a third sibling fan-out block in the same scope**, emitting
`StreamLoadCompletion.PluginScraper(addonId, streams, error)` from
`StreamFetchSupport.kt:29`. That reuses the existing concurrency, cancellation, per-provider
failure isolation, ordering, dedup, source picker, and player with **no new pipeline** —
satisfying Phase 4 exactly. `addonId` of the form `cloudstream:<internalName>::<tvType>`
gives the stable identity Phase 8 requires for dedup.

---

## 6. Recommendation

Real playback requires executing DEX. There is no safe interpreter to adopt. The defensible
position is the one StreamBridge's build system is *already* structured for:

- **`androidFull` (sideload):** controlled, hash-verified, rollback-guarded DEX execution
  behind the existing execution seam.
- **`androidPlaystore` + iOS:** hard no-op stub; the no-DEX boundary stays absolute.

This keeps the security boundary explicit and reviewable rather than silently weakened, and
is the only route to the user's stated acceptance criterion of real streams in the player.

---

## Increment 2 — controlled execution runtime (implemented)

### Distribution mechanism (verified in code, not assumed)

`composeApp` does **not** use Gradle product flavors. `composeApp/build.gradle.kts`
resolves `androidDistribution` (`full` | `playstore`) from
`-Pnuvio.android.distribution` / `NUVIO_ANDROID_DISTRIBUTION` and then swaps a
**source directory**: `src/androidFull/kotlin` vs `src/androidPlaystore/kotlin`
(line ~569). `src/fullCommonMain/kotlin` is added for `full` only.

`androidApp` *does* use real flavors (`full`, `playstore`, dimension
`distribution`), which is where the CloudStream ProGuard rules are attached.

CI builds `:androidApp:assembleFullDebug` and `:androidApp:assembleFullRelease`,
so **only the full distribution is compiled in CI**. The playstore path is not
currently exercised by CI.

### Execution boundary

| Target | `supportsExecution` | Source set |
| --- | --- | --- |
| Android full | `true` | `androidFull/.../CloudStreamPlatformRuntime.android.kt` |
| Android playstore | `false` | `androidPlaystore/.../CloudStreamPlatformRuntime.android.kt` |
| iOS | `false` | `iosMain/.../CloudStreamPlatformRuntime.ios.kt` |

The runtime AAR is declared inside `if (androidDistribution == "full")`. Its
filename deliberately does **not** match the unconditional
`fileTree(... "lib-*.aar")` include, so it cannot leak into the Play Store build
implicitly. Play Store/iOS are compiled *without* the CloudStream ABI, so there
is no `PathClassLoader` path to disable — the capability is absent.

### Gates applied before any plugin code is reachable

1. app-private storage (`filesDir/cloudstream-packages/`), staged then committed
2. SHA-256 vs the repository's `fileHash` (verified before commit *and* at load)
3. archive layout: `manifest.json` + `classes.dex`, Zip-Slip rejected
4. `pluginClassName` present and a well-formed JVM binary name
5. file set read-only before loading
6. loaded class must extend `BasePlugin`
7. must register ≥1 provider, else providers/extractors are rolled back

Decisions for 2-4 live in `CloudStreamPackageValidation` (pure, unit-tested).

### Artifact integrity

`cloudstream-runtime-api-4.8.0-3496e5f.aar`, SHA-256
`b67a4384bea1f4072123b86c5f164471422d9c6c12845d5067f12db44674d427`, committed to
the repo (not fetched at build time), pinned by the `verifyCloudStreamRuntime`
Gradle task that every full-distribution Kotlin compilation depends on, and
re-checked by `CloudStreamRuntimeArtifactTest`.

## Increment 3 — download and install lifecycle (implemented)

### The blocker this closes

`CloudStreamPackageStorage.install()` existed from Increment 2 but **had no
callers**. The runtime's `providersFor()` resolves a plugin's providers from the
installed `.cs3`, so in practice it always threw *"CloudStream package is not
installed"*: extensions could be discovered and displayed, but never obtained.

### What was added

| Concern | Where |
| --- | --- |
| Lifecycle states + pure decision rules | `CloudStreamInstallation.kt` (`CloudStreamInstallState`, `CloudStreamInstallError`, `CloudStreamInstallPolicy`) |
| Platform install seam | `CloudStreamPackageInstaller.kt` (`expect`) |
| Real implementation | `androidFull/.../CloudStreamPackageInstaller.android.kt` |
| Hard no-op stubs | `androidPlaystore/…`, `iosMain/…` |
| Orchestration + state | `CloudStreamExtensionsRepository` (`installExtension`, `updateExtension`, `removeExtension`) |
| UI | `InstallStateChip`, `InstallActions` on the extension card; status/version/error on the detail screen |

### Pipeline

discover → download → verify hash → validate archive → install to app-private
storage → enable → provider available to the aggregator.

Downloads reuse the existing `AddonHttpClientProvider` OkHttp client — **no
second HTTP engine**. Bytes are buffered under a 32 MB ceiling, staged in the
cache directory, and validated *before* `CloudStreamPackageStorage.install()`
commits them, so a rejected artifact never reaches the plugin directory.

### Failure modes handled distinctly

HTTP failure, 404/410 (repository gone), timeout, empty/invalid response,
oversize response, interrupted/truncated transfer, `fileSize` disagreement,
hash mismatch, corrupt `.cs3`, malformed manifest, missing/invalid
`pluginClassName`, insufficient storage, unsupported distribution, and
duplicate/concurrent install requests (coalesced).

### Honesty invariants now enforced

- A package that is not genuinely on disk cannot be enabled, cannot appear in
  `CloudStreamAggregatorBridge.resolveTargets`, and cannot resolve streams —
  three independent gates, so a stale persisted flag cannot resurrect a dead
  provider.
- `CloudStreamExtension.isActive` requires **installed AND an enabled source**.
- A failed *update* keeps the previously working installation visible and
  usable; only a failed *first* install reports `FAILED`.
- Uninstalling clears the enabled state of every source it owned.
- Play Store and iOS report `UNSUPPORTED` with a truthful message rather than
  pretending an install merely failed.

### Verification

Commit `833da4b`, CI run `35448116026` — **success**, all steps green including
`Verify Play Store distribution excludes the CloudStream runtime`.

Physical CloudStream playback verification was not possible in this environment.

## Final pass — real execution on Android Full

### Regression target: "Unsupported — requires native execution"

Two independent defects produced this on the installed Full APK. Both are fixed.

**Defect 1 — classification never consulted the runtime.**
`CloudStreamRepositoryParser.toPlugin()` assigned
`compatibility = CloudStreamCompatibility.UNSUPPORTED` unconditionally and only
chose *which reason* to display. The androidFull execution backend could
therefore never influence what the UI reported. Because `plugin.isExecutable`
requires `COMPATIBLE`, this also silently gated `setSourceEnabled`,
`CloudStreamAggregatorBridge.resolveTargets` and `resolveStreams` — the whole
execution path was unreachable on every build, including Full.

*Fix:* compatibility derives from `CloudStreamPlatformRuntime.supportsExecution`,
injected as `canExecute` so both branches stay pure and testable. Genuine format
problems (unsupported `apiVersion`, missing artifact URL) still outrank
capability, so nothing is whitewashed as compatible.

**Defect 2 — the runtime was not packaged into the APK.**
The CloudStream AAR was declared with `implementation` on a Kotlin Multiplatform
`androidMain` source set. Such a local `.aar` file dependency is not reliably
exported to the consuming application's runtime classpath: `composeApp` compiled
against it, but `:androidApp` did not package it. Even with Defect 1 fixed,
every plugin load would have failed on device with `NoClassDefFoundError` on
`BasePlugin`, while CI stayed green.

*Fix:* the runtime and the libraries plugin bytecode resolves by original JVM
name are declared `fullImplementation` on `:androidApp`. This keeps them
strictly out of the `playstore` flavor, so the no-DEX boundary is unchanged, and
integrity remains pinned by `verifyCloudStreamRuntime`.

### Extractor support

Providers split into two kinds and both now work: those that emit
`ExtractorLink`s directly, and those that only know an embed/host page URL and
expect the host to run the extractor chain. The second kind previously produced
no streams. `collectLinks` now falls back to CloudStream's generic
`loadExtractor(url, referer, subtitleCallback, callback)` when the provider
emitted nothing, dispatching through the runtime's 328 registered extractors
plus any the plugin registered itself. Registry-driven — no per-provider or
per-extractor special-casing, and no allowlist.

### Removed second aggregation path

`CloudStreamSourceProvider` was referenced only by its own tests while the live
path is `StreamsRepository` → `CloudStreamExtensionsRepository.resolveStreams`.
It was deleted; its seam types moved unchanged to `CloudStreamExecution.kt`.

### CI verification of the built APK

`Verify the Full APK selects the real CloudStream execution runtime` inspects
both `full/debug` and `full/release` for the plugin package, the `BasePlugin`
type, the controlled `PathClassLoader` path, and the extractor API — so an R8
rule regression or a source-set fallback to the no-DEX stub fails the build.

A false failure from this step exposed a further real problem: the dex string
pool is large enough that capturing it into a shell variable and echoing it back
overflows the argument list, making every `grep` fail regardless of content.
Both the Full positive check and the **Play Store negative check** now extract
to a file first; the negative check had the same flaw and could have been
passing vacuously, which would have voided the no-DEX guarantee.

### Not verified

Physical CloudStream playback verification was not possible in this environment.
See `docs/CLOUDSTREAM-DEVICE-CHECKLIST.md`.

## Root cause: an installed extension never reached Play → Sources

Reported symptom: Phisher installs and is enabled, but no CloudStream provider
appears under Play → Sources. Tracing the real path
(`StreamsRepository.load()` → `CloudStreamAggregatorBridge.resolveTargets()` →
`CloudStreamExtensionsRepository.uiState`) showed the aggregator gates were
correct; they were being handed an empty extension list. Two separate defects
produced that.

### 1. An async/sync race at cold start

`CloudStreamExtensionsRepository.initialize()` called `refresh()`, an
*asynchronous* network re-fetch. `StreamsRepository.load()` then read
`uiState.value.extensions` *synchronously* to build its targets. Installed
plugin metadata lived only in memory and was rebuilt from the network, so on a
cold start the list was still empty and every CloudStream provider was dropped
before aggregation. The integration only appeared to work if the user first
opened CloudStream settings and waited for a refresh to land — which is exactly
the "works in settings, invisible in Play" behaviour reported.

Fix: installed extensions are cached (`CloudStreamStorage.loadInstalledPlugins`
/ `saveInstalledPlugins`) and restored **synchronously** in `initialize()`
before any network work, so discovery is correct at cold start and offline.
The cache is metadata only:

* installation is re-checked against `CloudStreamPackageInstaller`, so a cache
  entry for a removed package is ignored rather than trusted;
* compatibility is re-derived from the current build's
  `CloudStreamPlatformRuntime.supportsExecution`, so a Play Store build reading
  the same cache still reports unsupported.

### 2. Installed, but with every source switched off

`CloudStreamExtensionMapping` set `enabled = canActivate && persisted?.enabled
== true`, so a source was enabled only when a preference had already been
persisted. A freshly installed extension therefore had no enabled source and
contributed nothing — the install looked like it had done nothing at all.

Fix: installing is an explicit opt-in, so `enableSourcesByDefault()` switches on
the extension's activatable sources — but only those the user has never
expressed a preference for, so a deliberate disable is never undone by a
reinstall or update. The cache is written immediately on install and removal, so
neither requires a refresh or an app restart.

### CI: the boundary checks were partly fictional

Both APK checks piped the dex string pool through `strings` into a shell
variable. The pool is large enough to overflow argument limits, which caused
false failures on the Full check and made the Play Store *negative* check pass
vacuously — the no-DEX guarantee was never actually being proven.
`.github/verify-cloudstream-apk.py` now reads each `classes*.dex` as bytes
straight from the zip, so there is no quoting, argument-size limit or
truncation. It was verified against synthetic APKs in both directions before
being wired in.

## Root cause: Play refused before Source Results could open

Reported symptom: an extension is installed and enabled, but tapping Play
immediately shows "Playback isn't available for this title with your current
setup" and the Source Results screen never opens.

This is a *different and earlier* defect than the discovery race documented
above, and it is the one responsible for the reported behaviour. Tracing the
message rather than the assumed cause:

`playback_unavailable_message` (`strings.xml:1849`) has two call sites. The one
that matches the symptom is `MainAppContent.kt:1127`, a toast in the Play
handler:

```kotlin
if (!PlaybackAvailability.current().canStream(type, videoId)) {
    NuvioToastController.show(playbackUnavailableMessage)
    return
}
```

This runs *before* `StreamLaunchStore.put(...)` and the navigation to the
streams route, so the early `return` means Source Results is never created —
exactly "the screen does not open at all".

`PlaybackAvailability.canStream()` considered only Stremio addons, plugin
scrapers, embedded streams and downloads. CloudStream was a first-class source
in `StreamsRepository` (which already resolves CloudStream targets and handles
the empty cases correctly) but was **invisible to the one check that decides
whether Play may proceed**. With CloudStream as the only installed source the
gate always failed, and the correctly-wired aggregation path below it was never
reached.

Fix: the gate now also consults CloudStream, reusing
`CloudStreamAggregatorBridge.resolveTargets()` instead of reimplementing the
eligibility rules — so the check that permits Play and the check that later
builds the targets cannot drift apart. Extensions are initialised synchronously
in `PlaybackAvailability.current()` for the cold-start case, and collected as
observed state in `rememberPlaybackAvailability()` so installing or enabling an
extension enables Play without reopening the screen or restarting.

`describeUnavailability()` is logged whenever Play is refused, reporting each
source family separately, so "no eligible CloudStream provider" can never again
be indistinguishable from "no addons installed".
