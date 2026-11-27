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
| PR #1 | `6a501a4` | Passed at `2026-11-27T14:42:24+05:00` | Run `45975815716` passed unit tests, lint, and debug APK assembly for the Phase 2 checkpoint. |
| Phase 3 request | In progress | `2026-11-27T21:00:47.690+05:00` onward | Scope supplied at `21:03:35+05:00` for Sections 4, 14, 30, and 36. No production backend or OAuth credentials are configured, so live service flows cannot be verified. |

## Phase 3 local validation

| Check | Result | Notes |
|---|---|---|
| Kotlin compilation | Passed at `2026-11-27T21:31:10+05:00` | Final full run completed successfully after moving `GoogleCredentialUnavailableException` to top-level scope and applying UI accuracy fixes. |
| JVM unit tests | Passed (19 tests) | 0 failures, 0 errors, 0 skipped. Coverage includes URL/path rules, endpoint construction/fail-closed behavior, session validation/refresh/sign-out, AES-GCM integrity, conversation rules, and profile rules. |
| Android lint | Passed with 10 warnings | No lint errors. Remaining warnings: target SDK 35; newer Gradle/AGP/Kotlin/Credential Manager/Google ID versions available; three KTX `SharedPreferences.edit` style hints. |
| Debug APK assembly | Passed | `app/build/outputs/apk/debug/app-debug.apk` exists. This is a local development debug build, not a release artifact or device test. |
| Environment isolation | Passed | A production BuildConfig generated while only `-PAIVO_PROXY_URL=https://dev.example.test` was supplied contained `AIVO_ENVIRONMENT="production"` and `AIVO_PROXY_URL=""`. |
| Diff whitespace | Passed | `git diff --check` completed without errors. |
| Android device/runtime | Not run | `adb devices -l` listed no attached emulator or physical device. No UI/runtime integration test is claimed. |
| GitHub Actions for Phase 3 publication head `85ed523` | Passed | Run [46035480969](https://github.com/b73859554-del/AIVO/actions/runs/46035480969) passed unit tests, lint, and debug assembly at `2026-11-27T21:41:51+05:00`. GitHub emitted a non-failing Node.js 20 deprecation annotation for `gradle/actions/setup-gradle@v4`. |
| GitHub Actions for final published head `f6276fa` | Passed | Run [46035921239](https://github.com/b73859554-del/AIVO/actions/runs/46035921239) passed unit tests, lint, and debug assembly at `2026-11-27T21:44:27+05:00`; same non-failing Node.js 20 deprecation annotation. |

The final Phase 3 command `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` completed with `BUILD SUCCESSFUL in 44s`. All reported warnings are non-fatal. No live backend/OAuth flow or emulator/device test is claimed.

## Authorized publication validation

The user authorized publishing Phase 3 at `2026-11-27T21:36:31.248+05:00`. A fresh full test/lint/debug assembly run is required before committing; results will be appended here. The expected destination is the existing open PR branch `phase-1-activity-logs` in the verified public repository; `main` must remain untouched.

Fresh command result at `2026-11-27T21:38:05.286+05:00`: 19 unit tests passed (0 failures/errors/skips), Android lint passed with 10 warnings, debug APK assembly passed, and `git diff --check` passed. Implementation commit: `c6ddd2e`; publication checkpoint commit: `85ed523`. Both were pushed to PR #1.

## Not run / not claimed

- No Android emulator or physical-device test.
- No release APK/AAB, production signing, deployed service, or live backend connection.
- No production OAuth/API credentials or AI service integration.
- Foundation CI tests and lint are not the Section 40 AI-assisted fixing or self-healing capability.
