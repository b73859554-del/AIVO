# AIVO Phase 1 architecture

## Application identity

- Product name: AIVO
- Android application ID: `io.aivo.app`
- Initial platform: Android

This is a new application foundation with its own source tree and Git history. It does not depend on or import another project's code, data, build products, or services.

## Initial structure

- `app`: one native Android application module, implemented in Kotlin.
- `app/src/main`: minimal activity and manifest; no feature screens or network client are implied.
- `app/src/test`: local JVM unit tests for backend URL validation.
- `gradle/libs.versions.toml`: centralized versions for Android and Kotlin build plugins and test dependencies.
- `.github/workflows/android.yml`: read-only CI build, lint, and unit-test checks.

The initial app uses the Android framework directly. This keeps the foundation small while leaving room to add product modules and libraries when their actual requirements are specified. There is no backend service, persistence layer, authentication flow, or AI integration in Phase 1.

## Backend configuration boundary

`AIVO_PROXY_URL` is an optional build-time configuration value. When absent, it resolves to an empty string and `BackendConfiguration.proxyBaseUrl` is `null`. A configured value must be an HTTPS URL with a host and without user information, query parameters, or a fragment. The value is an endpoint, not a secret. The app does not make requests with it.

Secrets belong in a future, explicitly designed server-side secret store; they must never be embedded in the Android package. CI does not need secrets to build or test this foundation.

## Build baseline

- JDK 17
- Gradle 8.11.1
- Android Gradle Plugin 8.10.1
- Kotlin 2.1.20
- Android compile SDK 36, target SDK 35, minimum SDK 26

These are a starting point for development, not a declaration of production readiness or tested device compatibility.
