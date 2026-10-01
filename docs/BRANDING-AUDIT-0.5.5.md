# StreamBridge identity and Nuvio-reference audit

## Own-product identity

| Surface | Verified source configuration |
| --- | --- |
| Gradle root name | `StreamBridge` |
| Android application / launcher name | `com.streambridge.app` / `StreamBridge` |
| Shared release / About version | `0.5.5-beta`, build `108` |
| iOS target / product / shared scheme | `StreamBridge`, `StreamBridge.app`, `StreamBridge` |
| iOS main / widget bundle IDs | `com.streambridge.app`, `com.streambridge.app.DownloadsWidgetExtension` |
| iOS app / widget display names | `StreamBridge`, `StreamBridge Downloads` |
| Apple framework bundle ID | `com.streambridge.app.shared` |
| Apple marketing version / build | `0.5.5` / `108`, plus full beta metadata |
| Main generated callback/download/widget scheme | `streambridge://` |
| Updater owner/repository and client name | `sascio/MyBridge`, `StreamBridge` |
| Device/client and local library labels | `StreamBridge`, `StreamBridge Library` |
| Android artifacts | `StreamBridge-0.5.5-beta-<ABI>.apk` / `...-full.aab` |
| Signed iOS artifacts | `StreamBridge-0.5.5-beta.ipa` / `...xcarchive.zip` |
| Nonproduction artifacts | Explicit `-not-production`, `-debug-not-production`, or `-unsigned` suffixes |

All **25** shared resource sets use `StreamBridge` for the application name.
App-owned localized copy, user agents, debrid device/library labels and default
Files export folder are adapted selectively. Registered external services and
legal attribution are not renamed into imaginary StreamBridge services.

Source/configuration verification is not runtime validation. The APK helper
checks real built manifests/labels and signatures; the archive/IPA helper checks
actual app/widget bundle metadata and architecture. Actual completed build results
must be read separately in [VALIDATION-0.5.5.md](VALIDATION-0.5.5.md).

## Comprehensive occurrence inventory

[branding-occurrences-0.5.5.tsv](branding-occurrences-0.5.5.tsv) indexes every
case-insensitive `Nuvio`-like matching tracked path and source-text line, including
`NuvioMobile`, Enhanced, NuvioMedia, `nuvio.tv`, mixed-case symbols and `NUVIO_*`.
It includes the pinned MPVKit source and the unbuilt historical prototype.
Each row has a path/line, match count, classification and retention decision;
source/credential excerpts are not duplicated into the report.

Regenerate it after source/doc changes with:

```sh
python3 .github/verify_update_track.py
python3 .github/audit_branding.py
```

The generated inventory/audit report are excluded from their own scan. Counts
include path references as well as code/text, not just visible strings. The
resource guard independently rejects unreviewed visible upstream branding; style
names and resource identifiers are not treated as product labels. The 86 active
non-URL/non-namespace literal candidates were read and classified explicitly:
engine names, logs/threads, existing scheduler/data/cache IDs, backend/protocol
names, legacy callbacks and paired Swift/Kotlin notifications.

<!-- inventory-counts-start -->
```text
Classified references (including paths):
build-dependency-source-pin-or-secret-name: 198
callback-protocol-or-existing-data: 9
dependency-component-or-its-data: 175
external-service-or-operator: 148
external-service-or-protocol: 17
legal-credit: 144
pinned-third-party-source: 285
source-audit-documentation: 701
technical-namespace-symbol-notification-or-diagnostic: 24219
test-fixture-or-compatibility-contract: 1212
unbuilt-historical-compatibility: 326
Total: 27434; indexed rows: 23685
```
<!-- inventory-counts-end -->

## Why retained occurrences are intentional

| Classification | Examples / decision |
| --- | --- |
| Technical namespace / symbol | `com.nuvio.*`, `nuvio.composeapp.generated.resources`, `NuvioGlassTabBar`, `Theme.Nuvio`: keep source/binary/resource binding compatibility. These do not define product names or application IDs. |
| Existing data / notifications | `nuvio-downloads`, installation prefixes, `NuvioNativeTab*`, member/engine cache directories, matched Kotlin/Swift notification names: avoid orphaning work, cache/profile preferences and native callbacks. |
| Protocol / registered callback | `nuvio://` acceptance, `/.well-known/nuvio`, backend `service=nuvio`, existing OAuth registration and server keys: remain interoperable while new generated app links use `streambridge://`. |
| Dependency component | Nuvio Engine, native library/module names, AAR/XCFramework and QuickJS filenames: name the actual upstream dependency, not StreamBridge's application. |
| External service/operator | Nuvio account/backend/sync/supporter program, real authentication/donation/terms URLs: accurate service naming, not fabricated StreamBridge-owned endpoints. |
| Legal/source credit | NuvioMedia, luqmanfadlli/Enhanced, source pins/URLs, original licenses/headers and in-app credits: required attribution stays. |
| Test fixture | Legacy scheme/installation IDs, mock usernames/provider labels: prove compatibility or exercise arbitrary user data; two assertions of own app labels are adapted to StreamBridge. |
| Historical source/docs | Explicitly unbuilt MIT client and historical parity/player/test reports: not current product/runtime claims. |
| Build/property name | Existing `NUVIO_RELEASE_*`, optional credential/property names and upstream-watch source references: keep deployment compatibility and trace source provenance. |

No package-wide search/replace is used. iOS inherited app/team identity is
removed; source/package/storage names are retained only where they have a
technical, external-service, test or legal role. There is no claim that an
uncompiled internal namespace is itself an app display-name defect.

## Artwork and legal text

A SHA-256 comparison verifies **14 shared icon/wordmark PNGs** and **five iOS
alternate icon PNGs** remain identical to the clean StreamBridge baseline's own
artwork. A visual check confirmed the shared StreamBridge wordmark and Arctic
Blue native icon. The native primary icon was the stale upstream distribution
asset and is now byte-identical to the canonical opaque RGB 1024×1024
`branding/streambridge-launcher-1024.png`:

```text
22a74fcda4083b122082ddcc4d14aaac5426d6508e3e14c64edb7cdb9770391f
```

Color/icon-choice behavior remains; assets are not replaced with one generic
icon. `NuvioTab*.imageset` names refer to generic navigation SVGs, not own-product
wordmarks. Third-party service logos and user-selected avatar/background media
are not overwritten as if they were the app's branding.

Root `LICENSE`/`COPYING` are unchanged; `NOTICE`, source credits, embedded engine
license/third-party notices and the Noto OFL are retained. Full source and
licensing provenance is in [THIRD-PARTY-0.5.5.md](THIRD-PARTY-0.5.5.md). Legal/
compatibility references are not deleted merely to achieve zero grep matches.

The README is newly written for StreamBridge, covering both platforms, real
installation/build/signing constraints, architecture, source pins, features and
credits. Release notes are plain Markdown rather than copied upstream release
HTML. Historical documents are labeled as historical instead of being presented
as current successful tests or device validation.
