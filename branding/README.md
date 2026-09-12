# StreamBridge marks

Use cases are separate. Do not reuse one PNG for all of them.

| File | Use |
| --- | --- |
| `streambridge-launcher-1024.png` | Store / marketing launcher (full rounded plate, RGB). |
| `streambridge-mark-transparent.png` | In-app / splash / credits glyph (true alpha, no plate). |
| `streambridge-wordmark-1600.png` | Marketing composite (not the in-app lockup). |

Android copies:

- `composeApp/.../drawable/app_mark_transparent.png` — Compose splash, intro, credits, auth, profile.
- `composeApp/.../drawable/app_icon_original.png` — Appearance “Original” icon preview (full plate).
- `composeApp/.../drawable-nodpi/ic_splash_logo.png` — Android 12 splash icon (transparent glyph).
- `composeApp/.../drawable/ic_launcher_foreground.png` — Adaptive-icon foreground (padded transparent glyph).
