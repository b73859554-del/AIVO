# AIVO error report

All known timestamps use PKT (UTC+05:00). Local command times that were not retained are identified as such.

| Time | Symptom | Cause | Action and result |
|---|---|---|---|
| Time not retained; before `2026-11-27T14:09:42+05:00` | Kotlin compilation failed with `Missing '}'` in `BackendConfiguration.kt`. | The new URL-validation helper had an unclosed object declaration. | Added the missing closing brace and corrected indentation. Subsequent local compilation and tests passed. |
| Time not retained | One concurrent local Gradle invocation failed at `:app:lintReportDebug` with `Unable to delete file ... lint-results-debug.txt`. | Two overlapping Gradle runs attempted to write the same lint report. | Let the duplicate run finish and reran the full test/lint/debug-build task without overlapping invocations. That run completed successfully. No source fix was needed. |
| `2026-11-27T14:12:36.594+05:00`–`14:12:42.594+05:00` | CI run `45971763873` failed at Android SDK setup (`sdkmanager` exit code 1). | `android-actions/setup-android@v3` attempted to install the obsolete `tools` SDK package, which is not present in the current package repository. | Removed the incompatible setup action and updated checkout/setup-java. CI run `45972061902` later passed with the runner-provided Android SDK. |
| `2026-11-27T14:13:45.428+05:00` | CI run `45971934030` exited 126: `./gradlew: Permission denied`. | The wrapper script had Git mode `100644`, so Ubuntu could not execute it. | Updated the tracked mode to `100755` in commit `0889c04`. CI run `45972061902` passed after this correction. |

## Warnings and outstanding results

- The first CI attempt warned that earlier checkout/setup-java action versions were deprecated. Those steps were updated to `actions/checkout@v5` and `actions/setup-java@v5`.
- CI run `45971934030` warned that `gradle/actions/setup-gradle@v4` targets Node.js 20 while the runner is forcing Node.js 24. This was a warning, not the cause of the run failure; its continued support/compatibility should be reviewed separately.
- CI run `45972061902` completed successfully at `2026-11-27T14:16:53+05:00`; the unit-test, lint, and debug-build step passed.
- No production or device-runtime errors can be assessed: no service or app was deployed or tested on a device.
