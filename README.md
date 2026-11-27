# AIVO

AIVO is a new Android-first application, developed independently from any other project.

## Phase 2 status

The Android foundation now has AIVO branding, a three-step onboarding flow, and a local dashboard. Onboarding saves an optional display name only on-device; the dashboard reports honestly that no AI service is connected. See [the feature status register](docs/FEATURE_STATUS.md) and [the architecture](docs/ARCHITECTURE.md).

## Build and test

Requirements: JDK 17 and Android SDK platform 36 with Android build tools 36.0.0.

On Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On macOS or Linux:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The Android application ID is `io.aivo.app`. No backend URL is configured by default, and the app makes no network requests.

## Backend URL configuration

`AIVO_PROXY_URL` is the canonical optional configuration name. Set it in the build environment only when using a real HTTPS proxy endpoint; alternatively, pass it as a Gradle project property with `-PAIVO_PROXY_URL=https://proxy.example`. It must be an HTTPS URL without embedded credentials, query parameters, or a fragment. Do not put secrets in this URL, source code, Gradle properties committed to the repository, or CI logs. Leave it unset to keep backend configuration disabled.

This setting only makes a URL available to the app. It does not configure or connect to a backend.

## Security

See [SECURITY.md](SECURITY.md) for safe development and reporting guidance. No production credentials, signing keys, OAuth clients, or service endpoints are included.

## Activity records

The timestamped development record is maintained in [AIVO-PROGRESS-LOG.md](AIVO-PROGRESS-LOG.md), [AIVO-SESSION-HISTORY.md](AIVO-SESSION-HISTORY.md), [AIVO-ERROR-REPORT.md](AIVO-ERROR-REPORT.md), and [AIVO-BUILD-AND-TEST-REPORT.md](AIVO-BUILD-AND-TEST-REPORT.md). Times are recorded in Pakistan Standard Time (UTC+05:00); unavailable historical timestamps are identified rather than estimated.
