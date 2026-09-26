# CloudStream runtime audit — StreamBridge vs NuvioMobile-Enhanced

Audit target: `sascio/MyBridge` @ `a5fe726`
Reference: `yesnt10/NuvioMobile-Enhanced` @ `8944991` (branch `enhanced`), GPL-3.0

Method: no code was changed to produce sections 1–7. Findings are derived from
reading both implementations, from the CloudStream runtime AAR's actual
bytecode, and from parsing the DEX of 50 `.cs3` packages published by a real
CloudStream repository (`phisher98`, 86 extensions).

---

## 1. Enhanced's CloudStream architecture

Enhanced has **two** designs, and reading only the documentation gives the
wrong one.

`CLOUDSTREAM_CROSS_PLATFORM_COMPATIBILITY.md` (still at the repo root)
describes an early feature branch where *no DEX is executed at all*:

> | Execute arbitrary downloaded `classes.dex` | Not supported | Not possible |
> Downloaded DEX is never executed.

Under that design "CloudStream support" meant repository/manifest parsing plus
**hand-written adapters compiled into the app** — the first and only one being
`KickTrCloudStreamProvider` (`composeApp/src/fullCommonMain/.../KickTrCloudStreamProvider.kt`),
reimplemented from a public service contract. That is the "supports only a
subset of providers" limitation in the task: the subset is literally the
adapters someone wrote by hand.

The shipped `enhanced` branch has moved on. It embeds the same CloudStream
runtime AAR StreamBridge uses (`third_party/cloudstream-runtime/NOTICE.md`
pins the identical SHA-256 `b67a4384…`) and **does** execute `.cs3` DEX in
`composeApp/src/androidFull/.../CloudStreamPlatformRuntime.android.kt` (512
lines): `PathClassLoader` → `BasePlugin` → `APIHolder.allProviders` →
`search`/`load`/`loadLinks`.

Classification requested by the task:

| Area | Class | Notes |
| --- | --- | --- |
| Repo/`plugins.json` parsing, hash-verified install, private storage | **A — reusable** | StreamBridge already has an equivalent, stricter, version |
| `PathClassLoader` + `BasePlugin` + `APIHolder` load/rollback | **A — reusable** | Near-identical in both |
| All five `LoadResponse` shapes handled | **A — reusable** | **Enhanced had this; StreamBridge did not** |
| Per-provider `MainAPI.*TimeoutMs` stage budgets | **A — reusable** | **Enhanced had this; StreamBridge did not** |
| `unload()` / `beforeUnload()` / registry rollback | **A — reusable** | **Enhanced had this; StreamBridge did not** |
| `CommonActivity.setActivityInstance` wiring | **A — reusable** | **Enhanced had this; StreamBridge did not** |
| `encodeAndroidDexRoute` provider routing, catalogue/main-page browsing | **B — Enhanced-specific** | Enhanced surfaces CloudStream as a *catalogue*; StreamBridge only as a *stream source* |
| `KickTrCloudStreamProvider` | **C — provider-specific adapter** | Not adopted |
| `applyHostCompatibilityDefaults()` — `if (pluginClassName == "com.megix.CineStream") …` | **C — provider-specific hack** | Not adopted; the task forbids this shape |
| iOS and Play Store cannot execute | **D/E — intentional limit** | Same in StreamBridge |
| No bundled repos, explicit user action, hash mandatory, no secrets in logs | **E — security boundary** | Same in StreamBridge, which adds more |
| CloudStream AAR, jsoup, jackson, NiceHttp, rhino, fuzzywuzzy | **F — runtime dependency** | Same set in both |

---

## 2. StreamBridge's CloudStream architecture

```
CloudStreamRepositoryLoader → CloudStreamRepositoryParser → CloudStreamPlugin
   → CloudStreamPackageInstaller (download, SHA-256, layout, Zip Slip)
   → CloudStreamPackageStorage   (app-private, staged, read-only commit)
   → CloudStreamPlatformRuntime  (androidFull only; 7 gates → PathClassLoader)
   → CloudStreamPluginExecutor.resolve()
   → CloudStreamProviderAdapter  (ExtractorLink → StreamItem)
   → StreamsRepository aggregation → source picker → Media3 / mpv
```

Distribution boundary is a source-set boundary, not a flag: `androidPlaystore`
and `iosMain` compile a stub with no runtime dependency at all, and CI proves
both directions (`.github/verify-cloudstream-apk.py`).

---

## 3. Compatibility matrix

| Feature | Enhanced | StreamBridge (before) | Problem | Action taken |
| --- | --- | --- | --- | --- |
| repo.json / plugins.json | yes | yes | — | none |
| `.cs3` download + `sha256-` verify | yes | yes (+ staged, + Zip Slip, + pure validator) | — | none |
| DEX execution | yes | yes | — | none |
| Classloader hierarchy | `PathClassLoader(file, app loader)` | same | — | none |
| `BasePlugin` gate + registry rollback | yes | yes (+ 7 explicit gates) | — | none |
| **Kotlin stdlib kept under R8** | **no** | **no** | **`SetsKt` NoClassDefFoundError** | **`-keep kotlin.**` + APK-level proof** |
| okhttp3 / coroutines kept under R8 | no | no | same class of failure | keep rules added |
| `MovieLoadResponse` | yes | yes | — | — |
| `TvSeriesLoadResponse` | yes | yes | — | — |
| `AnimeLoadResponse` | yes | **no** | 14/50 extensions always failed | added |
| `LiveStreamLoadResponse` | yes | **no** | 2/50 always failed | added |
| `TorrentLoadResponse` | yes | **no** | magnet never reached player | added |
| Episode match with null `season` | n/a (Enhanced lists episodes to the user) | **exact match only** | most single-season/anime providers failed | tolerant ordered rules |
| Per-provider stage timeouts | yes | **no** (fixed 20/25/40s) | slow providers cut off | adopted, clamped |
| Plugin unload / update | yes | **no** | update ⇒ "registered no providers" | adopted |
| `CommonActivity.activity` | yes | **no** (always null) | WebView/Cloudflare paths | adopted |
| `loadLinks` boolean honoured | yes | **no** | declined request looked empty | adopted |
| Link de-dup keeps referer/headers | yes | **no** (url+quality+type) | working mirror discarded | adopted |
| Extractor fallback (`loadExtractor`) | no | **yes** | — | StreamBridge ahead |
| Subtitles, headers, referer, cookies → StreamItem | yes | yes | — | none |
| HLS / DASH flags | yes | yes | — | none |
| Extensionless URLs | accepted | accepted | — | test added |
| Error surfaced to user | per-provider | **collapsed to one sentence** | undiagnosable | detail plumbed to empty state |
| Linkage error classified | no | no | read as provider bug | `CloudStreamRuntimeFailure` |

StreamBridge is ahead of Enhanced on: validation gates, extractor-registry
fallback, APK-boundary CI proof, and now R8 correctness. Enhanced is ahead on
catalogue/main-page browsing, which StreamBridge does not attempt.

---

## 4. The AllMovieLand `SetsKt` failure — exact cause

Reported: `Movie: Failed resolution of: Lkotlin/collections/SetsKt;`

Answering the task's checklist directly:

| Question | Finding |
| --- | --- |
| Is the Kotlin stdlib packaged? | Yes — as a normal dependency. |
| Which class does the provider need? | Parsing `AllMovieLandProvider.cs3`'s DEX method table: `Lkotlin/collections/SetsKt;` → `setOf([Ljava/lang/Object;)Ljava/util/Set;`. |
| Is the class present in the APK? | **In the release APK, no.** `kotlin.collections.SetsKt` is a *multifile facade*; nothing in the app referenced it, `-keep` did not cover it, so R8 shrank it away (and would have renamed it otherwise). |
| ClassLoader isolation? | No. `PathClassLoader(file, context.classLoader)` — the app loader is the parent, so everything the APK contains is visible. |
| Parent/child classloader issue? | No. |
| DEX loading misconfigured? | No; identical to upstream CloudStream and to Enhanced. |
| Duplicate/incompatible Kotlin runtime? | No. One stdlib, supplied by the parent loader. |
| **R8 removing required classes?** | **Yes. This is the cause.** `androidApp` sets `isMinifyEnabled = true` for release (`releaseMinifyEnabled` defaults to `true`), and `proguard-cloudstream-full.pro` kept `com.lagradost.**`, jsoup, jackson, NiceHttp, rhino, fuzzywuzzy, kotlinx-serialization and cryptography — but **not `kotlin.**`**. |
| Why did no test catch it? | Debug builds are not minified. Every unit test, every CI check and every debug install exercised an unminified APK. |
| Does Enhanced have the same bug? | **Yes.** Its `proguard-cloudstream-full.pro` keeps strictly less (fuzzywuzzy, kotlinx-serialization, cryptography only). |

Measured across the 50 sampled extensions:

| Package resolved by name | Extensions | Kept before |
| --- | --- | --- |
| `kotlin.**` | 50 / 50 | no |
| `kotlinx.coroutines.**` | 50 / 50 | no |
| `okhttp3.**` | 50 / 50 | no |
| `androidx.fragment.app.**` | 50 / 50 | no |
| `androidx.appcompat.app.**` | 50 / 50 | no |
| `kotlin.reflect.**` | 41 / 50 | no |
| `io.ktor.**` | 18 / 50 | no |
| `com.google.gson.**` | 12 / 50 | no |
| `org.jsoup.**` | 42 / 50 | yes |
| `com.fasterxml.jackson.**` | 38 / 50 | yes |
| `kotlinx.serialization.**` | 33 / 50 | yes |

A CloudStream provider is a suspend function that makes HTTP calls; it cannot
*not* reference the first three.

### Why the fix is not a hardcoded `SetsKt`

The defect is categorical — "symbols dynamically loaded code resolves by name
are invisible to R8" — so the fix is categorical:

1. `composeApp/cloudstream-plugin-abi.txt` declares the plugin-facing ABI as
   `keep` packages and `probe` descriptors, with the measurement that justifies
   each entry.
2. `proguard-cloudstream-full.pro` keeps every `keep` package with members.
3. `CloudStreamMinificationRulesTest` fails if a declared package loses its rule.
4. `.github/verify-cloudstream-apk.py` opens the **real built Full APK**,
   release included, and asserts each `probe` descriptor is present in the dex
   string pool. This is the only check that observes the minified artifact, and
   it is the one that would have caught the original bug.

---

## 5. Security review

No security property was weakened. The seven load gates are unchanged:
app-private storage, SHA-256 against the repository's published `fileHash`,
archive layout, Zip Slip, well-formed `pluginClassName`, read-only file,
`BasePlugin` type check, and rollback when a plugin registers nothing.

The R8 change only *widens keep rules*, which affects code layout, not
permissions, and applies solely to the sideload flavour. The Play Store build
does not receive `proguard-cloudstream-full.pro` and still contains no
CloudStream runtime — CI asserts that negatively on every run.

`unload()` is a net security improvement: a removed extension's providers are
now actually deregistered from the process-global CloudStream registries
instead of remaining callable until the process dies.

Not adopted from Enhanced, deliberately: `applyHostCompatibilityDefaults()`
writes a preference into a named third-party plugin's key space based on its
class name. It is a provider-specific hack of exactly the shape the task
forbids.

---

## 6. Licensing

CloudStream is GPL-3.0; NuvioMobile-Enhanced is GPL-3.0; the embedded runtime
AAR is already attributed in `composeApp/libs/NOTICE.md` with its upstream
commit and SHA-256. No Enhanced source was copied. The concepts adopted
(per-provider timeouts, unload lifecycle, activity wiring, the set of
`LoadResponse` shapes) are API-level facts about CloudStream itself, and were
reimplemented against the AAR's actual bytecode, not transcribed.

---

## 7. Remaining limitations

- **Multi-season absolute-numbered anime.** A provider that declares seasons
  but numbers episodes absolutely is reported as "no S2E3" rather than guessed
  at. CloudStream's `EpisodeResponse.getTotalEpisodeIndex()` could map it; that
  is unproven here and was not added speculatively.
- **No catalogue browsing.** CloudStream providers contribute streams only;
  `getMainPage` is not surfaced. Enhanced does surface it.
- **No device validation in this environment** — see the report section below.
