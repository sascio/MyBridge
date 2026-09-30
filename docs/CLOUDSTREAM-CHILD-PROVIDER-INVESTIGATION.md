# CloudStream child-provider investigation

Date: 2026-09-30

## Finding

The missing SKTech child providers are not homepage section names. They are
separate `MainAPI` instances registered by the extension's `Plugin.load()`.
The parent live-events API is registered unconditionally; the IPTV APIs are
preference-gated.

The inspected source is the CNCVerse CloudStream extension, including the
SKTechProvider source and the published `SKTechProvider.cs3` manifest version
54. The source checkout used for inspection was the public CNCVerse extension
repository's source mirror:

`https://github.com/Mishraji-op/Cloudstream/tree/master/SKTechProvider`

The published package manifest contains:

```json
{"requiresResources":true,"version":54,"pluginClassName":"com.cncverse.SKTechPlugin","name":"SKTechProvider"}
```

## Exact lifecycle

```text
SKTechPlugin.load(context)
  -> registerMainAPI(LiveEventsProvider())
  -> ProviderManager.fetchProviders()
       -> remote categories.txt
       -> decrypt response
       -> parse SKTechCategoryWrapper.cat JSON
       -> SKTechCategoryData(name, logo, api)
  -> read SharedPreferences("SKTech") by provider title
  -> for every enabled provider:
       registerMainAPI(SKTech(title, catLink))
  -> expose openSettings { Settings(... provider titles ...) }
```

The same source pattern is present in the inspected PlayFy and PlayZTV
implementations: each `Plugin.load()` registers its always-available events
provider, fetches a remote provider/category list, checks the extension-owned
shared preferences, and registers one parameterized `MainAPI` per enabled
entry. Both also expose their own settings fragment through `Plugin.openSettings`.
The generic runtime therefore captures the registered instances instead of
assuming a fixed class, title, preference key, or provider count.

Source evidence used for these comparisons:

- `ToonTamilIndia/CNCVerse-Cloud-Stream-Extension/PlayFyProvider/.../PlayFyPlugin.kt`
- `ToonTamilIndia/CNCVerse-Cloud-Stream-Extension/PlayZTVProvider/.../PlayZTVPlugin.kt`
- `ToonTamilIndia/CNCVerse-Cloud-Stream-Extension/SKTechProvider/.../SKTechPlugin.kt`

Published package artifacts inspected from the repository builds were
SKTechProvider 54, PlayFyProvider 10, and PlayZTVProvider 38. These are package
versions, not proof that all three were successfully executed by StreamBridge.

`SKTechCategoryData.api` is the provider-specific playlist/configuration URL.
The title is the display label, but the actual provider identity is the
registered `SKTech(title, catLink)` MainAPI instance and its `mainUrl`.

`SKTech.getMainPage()` loads and decrypts the playlist at its `mainUrl`, groups
M3U entries by the playlist's `group-title`, and emits one `HomePageList` per
group. Each channel's `SearchResponse.url` is an opaque serialized `LoadData`
payload containing the stream URL, title, artwork, group, DRM fields, cookies,
headers and user agent. It must be passed unchanged to `load()` and then
`loadLinks()`.

`LiveEventsProvider` is a different, always-registered MainAPI. Its current
source name is `⚡SKTech Live Events`; it fetches events and emits event-category
homepage lists. Seeing a `Live Events` row therefore does not prove that the
configured IPTV MainAPI instances were registered.

## Exact StreamBridge loss boundary

Before this fix, StreamBridge loaded the plugin and recorded only whichever
MainAPI instances `SKTechPlugin.load()` registered. It did not expose the
plugin's `openSettings` hook. On a clean StreamBridge install the extension's
`SharedPreferences("SKTech")` had no enabled-title values, so `selectedProviders`
was empty. The runtime registered `LiveEventsProvider` but none of the
`SKTech(title, catLink)` MainAPI instances. The catalog normalizer then
correctly reported the only registered provider's event section as `Live
Events`; it had no child APIs to display.

This is why adding a list of names or treating `HomePageList.name` as a child
provider would have been incorrect. The data had already been excluded at
plugin registration time.

## Fix boundary

The Android full runtime now exposes the standard CloudStream `Plugin.openSettings`
hook through `CloudStreamPlatformRuntime.openSettings()` and
`CloudStreamExtensionsRepository.openSettings()`. The extension's own settings
UI owns the provider list, preference keys and restart behavior. StreamBridge
never recreates the SKTech list or writes provider-specific keys.

After the user enables child sources through the extension-provided settings
hook and the extension reloads, `APIHolder.allProviders` contains the real
registered `MainAPI` instances. The existing generic catalog path then:

```text
registered MainAPI instances
  -> provider identity from repository/extension/class/name/mainUrl
  -> getMainPage()
  -> opaque SearchResponse payload retained in CloudStreamCatalogItem
  -> load(payload)
  -> loadLinks(payload.dataUrl / LoadData)
  -> existing StreamBridge source/player pipeline
```

The hierarchy treats registered MainAPI instances as provider boundaries. It
does not invent a parent-child relation that CloudStream does not expose in
`APIHolder`; every real registered provider remains selectable and isolated by
its stable identity. If an extension exposes further navigation through an
opaque payload, that payload is retained on the channel node and catalog item.

## Diagnostics

Provider initialization records the extension identity, repository provenance,
registered provider count, settings-hook availability, provider class, stable
provider ID, provider name, main URL and source-plugin mapping. Operation
records identify provider identity and operation/output counts for `search`,
`getMainPage`, `load` and `loadLinks`. URLs and secret-bearing values are
redacted by `CloudStreamDiagnostics`.

## Current evidence status

Static source evidence establishes the SKTech registration and preference
boundary above. A real installed-extension/device trace is still required to
record the actual current category list and verify playback for SKTech, PlayFy
and PlayZTV. No child names are hardcoded in the implementation.
