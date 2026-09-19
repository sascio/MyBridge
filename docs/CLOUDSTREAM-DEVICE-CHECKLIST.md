# CloudStream — manual Android device test checklist

**Status: PENDING. Not executed.** No physical Android device or emulator was
available in the build environment, so nothing below has been run. Physical
CloudStream playback verification was not possible in this environment.

Everything in "Verified automatically" was proven in CI; everything in this
checklist requires hardware.

## Build under test

| Item | Value |
| --- | --- |
| Variant | `:androidApp:assembleFullDebug` (or `assembleFullRelease`) |
| Distribution property | `-Pnuvio.android.distribution=full` |
| APK path | `androidApp/build/outputs/apk/full/<variant>/` |
| CloudStream runtime | `cloudstream-runtime-api-4.8.0-3496e5f.aar` |
| Runtime SHA-256 | `b67a4384bea1f4072123b86c5f164471422d9c6c12845d5067f12db44674d427` |
| Upstream | recloudstream/cloudstream @ `3496e5f8d2ebae4c1b5bdf264782f58375c1eb06` (4.8.0), GPL-3.0 |

> Do **not** test the `playstore` variant for execution. It is a hard no-op by
> design and correctly reports that extensions cannot run.

## Pre-flight

Confirm the APK really is the executing build:

```sh
APK=$(find androidApp/build/outputs/apk/full/debug -name '*.apk' | head -1)
unzip -p "$APK" classes*.dex | strings | grep -c "com/lagradost/cloudstream3/plugins/BasePlugin"
# expect: non-zero
```

## Checklist

Record PASS / FAIL / BLOCKED with a note for each.

### Repository management
1. Settings → Contents & Discovery → CloudStream Extension opens without error.
2. Add a valid repository URL → extensions appear with name, icon, author,
   version, language and type chips.
3. Add an invalid URL (`not-a-url`) → clear, specific error; no crash.
4. Add an unreachable URL (`https://127.0.0.1:9/repo.json`) → "could not reach"
   error, not a generic failure.
5. Add the same repository twice → refused as a duplicate.
6. Refresh → list reloads; previously installed extensions keep their state.
7. Remove a repository → its extensions disappear; others are unaffected.

### Extension lifecycle
8. An extension shows **Not installed** before any action.
9. Tap Install → state moves Downloading → Installing → **Installed**.
   The install button is not offered while busy.
10. Force-quit mid-download, reopen → state is *not* stuck "Downloading" and is
    *not* falsely "Installed".
11. Turn off networking, Install → **Install failed** with a network-specific
    message and a **Retry** action.
12. Retry with networking restored → succeeds.
13. Uninstall → returns to **Not installed**; any enabled sources switch off.
14. Reinstall the same extension → succeeds.
15. Where the repository publishes a newer version → **Update available**, and
    Update installs it; the installed version line updates.

### Compatibility honesty
16. On this Full build, a well-formed extension is **Compatible** — it must
    **not** say "Unsupported — requires native execution" (the original bug).
17. An extension with a newer `apiVersion` still reports unsupported format.
18. No extension card is empty, and no Configure/gear control appears anywhere.

### Enable / disable
19. The enable switch is disabled until the package is installed.
20. Enable a source → extension reports Active.
21. Disable it → no longer Active.

### Provider execution — movie
22. Open a **movie**, press Play.
23. The CloudStream provider appears in the normal source list, grouped with
    other sources — not in a separate screen or a hardcoded group.
24. Its streams carry real quality labels.
25. Tap a stream → the existing StreamBridge player opens and **plays**.
26. A provider that returns nothing is hidden, not shown empty.

### Provider execution — series
27. Open a **series**, choose a season and episode, press Play.
28. The provider resolves that specific episode, not the series root.
29. Playback starts in the existing player.
30. Switching episode re-resolves correctly.

### Extractors / headers / subtitles
31. With a provider that returns an **embed URL**, streams still resolve
    (extractor fallback).
32. With a Referer/User-Agent-locked host, playback works — no 403.
33. Where the provider supplies subtitles, they are selectable and render.

### Isolation, performance, security
34. Install a deliberately broken/unavailable extension alongside a good one →
    the good one still returns sources.
35. Press Play then immediately go back → no crash; work is cancelled.
36. Several enabled providers resolve concurrently, not one after another.
37. Nothing sensitive (cookies, auth headers, tokens) appears in `adb logcat`.
38. On the **playstore** APK, extensions honestly report that they cannot run,
    and no install is offered.

## Known environmental limitation

Steps 22-33 depend on third-party repositories that change availability over
time. If a chosen extension has stopped working upstream, select another
legitimate published extension rather than treating it as a StreamBridge
regression — confirm by checking whether the same extension works in
CloudStream itself.
