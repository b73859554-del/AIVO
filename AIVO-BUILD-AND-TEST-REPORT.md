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

The successful non-overlapping local run reported `BUILD SUCCESSFUL in 1m 44s` (execution timestamp not retained). The final local invocation at `2026-11-27T14:25:57.451+05:00` also completed successfully in 5 seconds, with tests and most build tasks up-to-date. A prior concurrent invocation reported failure because it could not delete the lint intermediate report; see the error report.

## Phase 2 local validation

Successful command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The run from `2026-11-27T14:38:30.369+05:00` to `14:38:57.289+05:00` passed. Six JVM tests passed: three existing backend URL tests and three `ProfileRulesTest` cases for whitespace normalization, the display-name length limit, and first-name extraction. Android lint reported zero errors and four warnings about the target SDK and newer build-tool versions. Debug APK assembly passed. No emulator/device runtime test was performed.

Earlier Phase 2 attempts: the first run (`14:36:52.794`–`14:37:16.410+05:00`) failed Kotlin compilation; the second (`14:37:26.955`–`14:38:09.448+05:00`) compiled and passed tests/assembly but failed lint on an API-27-only theme item. Both issues were fixed before the successful run; see the error report.

## GitHub Actions

| Run | Commit | Result | Detail |
|---|---|---|---|
| `45971763873` | `15ef84a` | Failed | Android SDK setup requested the removed `tools` SDK package. |
| `45971934030` | `13dd153` | Failed | `./gradlew` was not executable (`100644`); corrected in commit `0889c04`. |
| `45972061902` | `0889c04` | Passed at `2026-11-27T14:16:53+05:00` | Unit tests, lint, and debug APK assembly passed. |
| PR #1 | `ba0aed7` | Passed at `2026-11-27T14:20:59+05:00` | Run `45972835249` passed unit tests, lint, and debug APK assembly. PR remains open; it has not been merged. |
| PR #1 | `49384c2` | Passed at `2026-11-27T14:23:49+05:00` | Run `45973250029` passed unit tests, lint, and debug APK assembly. |
| PR #1 | `07ff120` | Passed at `2026-11-27T14:26:13+05:00` | Run `45973589690` passed unit tests, lint, and debug APK assembly. |
| PR #1 | `ab38615` | Passed at `2026-11-27T14:29:38+05:00` | Run `45974070129` passed unit tests, lint, and debug APK assembly. |
| PR #1 | `4ce4593` | Passed at `2026-11-27T14:31:50+05:00` | Run `45974347270` passed unit tests, lint, and debug APK assembly. |
| Phase 2 | Not run yet | — | Phase 2 changes remain local and uncommitted; CI will run after publishing the review-branch checkpoint. |

## Not run / not claimed

- No Android emulator or physical-device test.
- No release APK/AAB, production signing, deployed service, or live backend connection.
- No production OAuth/API credentials or AI service integration.
- Foundation CI tests and lint are not the Section 40 AI-assisted fixing or self-healing capability.
