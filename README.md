# AIVO

AIVO is a new Android-first application, developed independently from any other project.

## Current status

Phase 1 foundation, Phase 2 onboarding/dashboard, Phase 3 client flows, and Phase 4 foreground voice features are represented in this repository. The chat offers push-to-talk English, Hindi, and Urdu transcription, a configurable Android text-to-speech engine with pause/resume/stop controls, and optional automatic reading. On-device recognition is preferred; use of a system provider that may process audio externally requires explicit consent. Transcripts remain in the composer until the user reviews and sends them. No always-on wake word or background microphone service is implemented. Live authentication and AI responses still require an external AIVO backend and OAuth configuration that are not currently available. See the [feature status register](docs/FEATURE_STATUS.md), [architecture](docs/ARCHITECTURE.md), [voice assistant design](docs/VOICE_ASSISTANT.md), and [Phase 3 backend contract](docs/PHASE_3_BACKEND_CONTRACT.md).

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

The production Android application ID is `io.aivo.app`; development and staging use `.dev` and `.staging` suffixes.

## Backend and OAuth configuration

Select the build environment with `AIVO_ENVIRONMENT=development|staging|production` (or `-PAIVO_ENVIRONMENT=...`). Its endpoint is configured separately:

| Environment | Endpoint input |
|---|---|
| Development (default) | `AIVO_PROXY_URL` |
| Staging | `AIVO_STAGING_PROXY_URL` |
| Production | `AIVO_PRODUCTION_PROXY_URL` |

Gradle properties with the same names are also accepted. The selected endpoint is exposed at runtime as `BuildConfig.AIVO_PROXY_URL`; missing configuration disables remote features and never falls back to another environment or legacy service. Endpoints must be HTTPS and must not contain URL credentials, query parameters, fragments, or dot segments.

Google Web client IDs are public identifiers configured with `AIVO_GOOGLE_WEB_CLIENT_ID`, `AIVO_GOOGLE_WEB_CLIENT_ID_STAGING`, and `AIVO_GOOGLE_WEB_CLIENT_ID_PRODUCTION`. Do not add OAuth client secrets, passwords, API keys, or signing credentials to the app or repository. These settings do not deploy, configure, or prove connectivity to an AIVO service. The backend must validate Google ID-token issuer, audience, signature, expiry, and nonce; enforce authenticated access; validate payloads/attachments; and keep AI-provider secrets server-side. See [SECURITY.md](SECURITY.md).

## Activity records

Timestamped development records are maintained in [AIVO-PROGRESS-LOG.md](AIVO-PROGRESS-LOG.md), [AIVO-SESSION-HISTORY.md](AIVO-SESSION-HISTORY.md), [AIVO-ERROR-REPORT.md](AIVO-ERROR-REPORT.md), and [AIVO-BUILD-AND-TEST-REPORT.md](AIVO-BUILD-AND-TEST-REPORT.md). Times use Pakistan Standard Time (UTC+05:00); unavailable historical timestamps are identified rather than estimated. Continuous video capture is not available in this environment.
