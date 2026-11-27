# AIVO build and test report

All timestamps use PKT (UTC+05:00). Historical local command times that were not retained are marked accordingly.

## Local checks

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

| Check | Result | Notes |
|---|---|---|
| Kotlin/Android compilation | Passed | The initial run found a missing brace in `BackendConfiguration.kt`; it was fixed before successful verification. |
| JVM unit tests | Passed | Exercises unset URL behavior, valid HTTPS configuration, and rejection of HTTP, URL credentials, query parameters, and fragments. |
| Android lint | Passed | The successful full run produced `app/build/reports/lint-results-debug.html`. An earlier overlapping Gradle run failed while deleting a shared lint report; the later run completed successfully. |
| Debug APK assembly | Passed | `:app:assembleDebug` succeeded. This is a local debug build, not a release artifact or device test. |

The successful non-overlapping local run reported `BUILD SUCCESSFUL in 1m 44s` (execution timestamp not retained). A prior concurrent invocation reported failure because it could not delete the lint intermediate report; see the error report.

## GitHub Actions

| Run | Commit | Result | Detail |
|---|---|---|---|
| `45971763873` | `15ef84a` | Failed | Android SDK setup requested the removed `tools` SDK package. |
| `45971934030` | `13dd153` | Failed | `./gradlew` was not executable (`100644`); corrected in commit `0889c04`. |
| `45972061902` | `0889c04` | Passed at `2026-11-27T14:16:53+05:00` | Unit tests, lint, and debug APK assembly passed. |
| PR #1 | `ba0aed7` | Passed at `2026-11-27T14:20:59+05:00` | Run `45972835249` passed unit tests, lint, and debug APK assembly. PR remains open; it has not been merged. |

## Not run / not claimed

- No Android emulator or physical-device test.
- No release APK/AAB, production signing, deployed service, or live backend connection.
- No production OAuth/API credentials or AI service integration.
- Foundation CI tests and lint are not the Section 40 AI-assisted fixing or self-healing capability.
