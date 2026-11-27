# AIVO

AIVO is a new Android-first application, developed independently from any other project.

## Phase 1 status

Phase 1 establishes the Android project, its build and unit-test scaffolding, safe backend URL configuration, and basic CI and developer documentation. Later product features are not implemented. See [the feature status register](docs/FEATURE_STATUS.md) and [the architecture](docs/ARCHITECTURE.md).

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
