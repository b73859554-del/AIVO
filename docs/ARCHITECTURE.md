# AIVO Phase 1 architecture

## Application identity

- Product name: AIVO
- Android application ID: `io.aivo.app`
- Initial platform: Android

This is a new application foundation with its own source tree and Git history. It does not depend on or import another project's code, data, build products, or services.

## Application structure

- `app`: one native Android application module, implemented in Kotlin.
- `app/src/main`: Android activity with branded onboarding and dashboard screens, plus an Android manifest and resource-based theme/copy.
- `app/src/test`: local JVM unit tests for backend URL validation and display-name normalization.
- `gradle/libs.versions.toml`: centralized versions for Android and Kotlin build plugins and test dependencies.
- `.github/workflows/android.yml`: read-only CI build, lint, and unit-test checks.

The app uses the Android framework directly. Onboarding stores its completion flag and optional display name in private app preferences; the dashboard reads and can edit that profile. No account, backend service, authentication flow, or AI integration is configured.

## Phase 2 screens

- Onboarding introduces AIVO, explains the current service boundary, and lets the user optionally save a display name. Users can move backward, skip the introduction, and finish without entering a name.
- The dashboard displays the saved local profile and clearly states that no live AI service is connected. Its profile action edits the saved name; no unimplemented AI controls are presented.
- Screen copy and brand colors are Android resources. The app keeps the UI scrollable and preserves onboarding state across activity recreation.

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
