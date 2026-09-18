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
