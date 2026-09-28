# CloudStream validation matrix

Date: 2026-09-29
Branch: `arena/01a0e422-mybridge`

This matrix is intentionally conservative. `PASS` requires the corresponding
operation to have executed in the current checkout. Source inspection alone is
not a device or playback result.

| Scope | Result | Evidence / blocker |
| --- | --- | --- |
| Discovery, repository parsing and identity deduplication | BLOCKED | Current-tree focused tests were not executed: this environment has no Java runtime. |
| Safe install, package validation and class loading | BLOCKED | Current-tree Android host tests/build were not executed: no Java runtime. |
| Verified Activity compatibility (`R81`/host hierarchy) | BLOCKED | Source confirms `com.nuvio.app.MainActivity : AppCompatActivity` and runtime now requires a live instance; no APK/device execution. |
| Provider initialization isolation | BLOCKED | Source implements per-`MainAPI` initialization and rollback; no loaded extension was executed. |
| `getMainPage` / section hierarchy / catalog visibility | BLOCKED | Generic paginated `getMainPage` path is implemented; no provider request or UI observation was made. |
| Search, movie details and metadata | BLOCKED | Generic normalization is implemented; no real provider search/detail response was observed. |
| Series/anime seasons and episodes | BLOCKED | Generic response-shape/episode code is present; no real series/anime provider was executed. |
| `loadLinks`, extractor fallback and source picker | BLOCKED | Header/link normalization is covered by source tests but those tests were not run in this environment; no provider links were observed. |
| Headers, Referer, cookies, User-Agent and subtitles | BLOCKED | Mapping code preserves these fields; no playback request was captured. |
| HLS, DASH, direct and extensionless playback | BLOCKED | Existing Media3 MIME inference/probe path is wired; no stream was played. |
| SKTech Live TV | BLOCKED | No SKTech package/provider was installed or run on a physical device. |
| Additional Live provider | BLOCKED | No second Live provider was installed or run on a physical device. |
| Movie provider | BLOCKED | No real movie provider operation was run. |
| Series provider | BLOCKED | No real series provider operation was run. |
| Anime provider | BLOCKED | No real anime provider operation was run. |
| Multiple extensions with isolation | BLOCKED | No multiple-extension APK/device run was performed. |
| M3U, Xtream, Stalker, Nuvio, Stremio regressions | BLOCKED | No current-tree regression suite or device run was executed. |
| Debug build | BLOCKED | `./gradlew` could not start: `JAVA_HOME` is unset and no `java` executable is installed. |
| Release/minified build | BLOCKED | Same missing-Java blocker; no release APK or mapping was produced. |
| Real Android device validation | BLOCKED | No `adb` or physical Android device is available in this environment. |

No provider, catalog, metadata, source, header, subtitle, playback or regression
row is marked `PASS` in the absence of execution evidence.
