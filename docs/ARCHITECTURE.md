# AIVO architecture

## Application identity

- Product name: AIVO
- Production Android application ID: `io.aivo.app`
- Development and staging IDs: `io.aivo.app.dev` and `io.aivo.app.staging`
- Initial platform: Android

This is an independent application with its own source tree and Git history. It does not depend on or import another project's code, data, build products, or services.

## Application structure

- `app`: one native Kotlin Android application module.
- `app/src/main`: onboarding/dashboard, account/authentication, and chat activities with Android resources and manifest.
- `app/src/test`: JVM unit tests for environment URL policy, API routing, authentication/session behavior, encryption, conversation rules, and profile normalization.
- `gradle/libs.versions.toml`: centralized Android, Kotlin, auth, serialization, and test dependency versions.
- `.github/workflows/android.yml`: CI build, lint, and unit-test checks.

The UI uses Android framework views. Credential Manager obtains Google ID tokens. Coroutines keep remote requests off the UI thread and support cancellation. Gson handles the fixed AIVO JSON wire contract.

## Local data and account security

Onboarding's optional display name and completion state are app-private preferences. Session access/refresh tokens are AES-GCM encrypted before storage in private preferences; the AES key is generated and held by Android Keystore. Local conversation history uses AES-GCM and atomic writes to app-private storage. Backup and data-extraction rules exclude session/history files. Passwords and Google ID tokens are sent to the configured AIVO endpoint only for the corresponding request and are not persisted by the app.

If local encrypted data cannot be decrypted, the app reports the failure and does not silently replace it with an empty session/history. Removing a local session while the backend is unavailable cannot revoke the remote token.

## Backend configuration and transport

The build exposes exactly one normalized runtime endpoint as `BuildConfig.AIVO_PROXY_URL`. Development uses `AIVO_PROXY_URL`; staging and production use their separate inputs. No endpoint fallback is performed across environments or to a legacy service. A missing endpoint disables remote auth/chat.

Configured endpoints must use HTTPS and a hostname, and omit user info, query, fragment, and dot-segment paths. The client constructs only fixed `/v1/...` routes; it uses TLS, disables redirects, bounds request/response sizes, sets finite timeouts, and uses bearer authorization for session-protected routes. It contains no AI-provider secret. These client-side controls do not establish that a configured endpoint is operated by AIVO; only configure an AIVO-owned service.

The server must validate all requests and resource ownership, validate Google ID-token signature/issuer/audience/expiry/nonce, rotate/revoke sessions, rate-limit auth/recovery/chat endpoints, inspect attachment content, and keep service credentials server-side. The endpoint shapes are specified in [PHASE_3_BACKEND_CONTRACT.md](PHASE_3_BACKEND_CONTRACT.md).

## Phase 3 behavior and boundaries

Email registration/sign-in/password reset, Google ID-token exchange, refresh, logout, account deletion, and chat are implemented as client requests to AIVO-owned paths. The app exposes English, Hindi, and Urdu chat language selection, local encrypted history, new/rename/delete, copy, device text-to-speech, stop/cancellation, and supported attachment selection. The composer is available only when an endpoint and encrypted local session are available. The app accepts only a real assistant response from the server; it does not generate mock replies.

No AIVO backend is implemented or deployed here, and no OAuth client registration, production signing, or AI provider is configured. Thus live authentication, backend ID-token validation, AI generation, server-side deletion, and fresh-install-to-real-response are not verified. Stop cancels/disconnects the client request, but cannot guarantee that server-side inference stops after receipt. Device speech synthesis behavior depends on the selected OS engine.

## Build baseline

- JDK 17
- Gradle 8.11.1
- Android Gradle Plugin 8.10.1
- Kotlin 2.1.20
- Android compile SDK 36, target SDK 35, minimum SDK 26

These are a development baseline, not a declaration of production readiness or tested device compatibility.
