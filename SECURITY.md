# Security

## Current boundaries

The app implements encrypted local session/history storage and an AIVO-only HTTPS client, but this repository does not include or deploy a production AIVO backend, Google OAuth registration, AI provider, release signing key, or production secret. With no environment endpoint configured, account and chat requests are unavailable. Live sign-in, live AI generation, and production readiness are not claimed.

## Configuration and secrets

- `AIVO_PROXY_URL` is the canonical development endpoint name, not a place for credentials or tokens. Staging and production endpoint values are separate; they never inherit development configuration.
- Configure only an AIVO-owned HTTPS endpoint. The app constructs fixed `/v1/...` routes, disables redirects and cleartext traffic, and does not use a legacy or alternate-service fallback.
- Passwords and Google ID tokens are not persisted. Session tokens and local conversation history are AES-GCM encrypted using an Android Keystore key and excluded from backup/transfer.
- Google Web client IDs are public identifiers. OAuth client secrets, AI-provider keys, and service credentials belong only in the AIVO backend's secret store.
- The backend must validate Google ID-token signature, issuer, audience, expiry, and nonce; enforce authenticated account/resource ownership; validate request and attachment data; rotate/revoke sessions; rate-limit endpoints; and avoid account enumeration in password recovery.
- Never put secrets in source, committed Gradle properties, the Android package, test fixtures, or CI output.
- The default empty endpoint disables remote auth/chat operations.
- `RECORD_AUDIO` is requested only after the user chooses push-to-talk. On-device recognition is preferred; if unavailable, the system speech provider may process audio externally and must be explicitly permitted by the user. Raw audio is not stored by AIVO or sent to its backend; only reviewed transcript text is sent after the user taps Send.
- Automatic text-to-speech is off by default. First-time playback discloses that the installed Android TTS engine may have its own text-processing behavior; users explicitly opt into automatic playback and can stop playback. Voice input/output stop when the chat activity is backgrounded.
- No always-on wake-word listener or microphone foreground service is present. Do not add background capture without a separate user opt-in, visible ongoing notification, current Android foreground-service/while-in-use permission review, battery analysis, and on-device privacy design.

## Remaining production/security validation

Backend implementation/deployment, domain ownership, token validation, account recovery delivery, abuse controls, and provider-secret handling require separate service setup and are not part of this Android repository. Google OAuth package/client registration, signing fingerprints, brand verification, production signing, and device validation are not configured. Voice recognition languages and TTS voices depend on the Android provider, installed models, and device; no device microphone/runtime test is available. See [VOICE_ASSISTANT.md](docs/VOICE_ASSISTANT.md).

## Reporting

For a suspected vulnerability in this project, use GitHub's private vulnerability reporting for the repository when available. Do not include secrets or sensitive personal data in a public issue.
