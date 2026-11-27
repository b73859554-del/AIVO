# Security

## Phase 1 boundaries

This repository contains a development foundation only. It has no production backend, authentication, AI service, release signing key, or configured secret. The Android app makes no network requests. These boundaries are intentional; do not treat the project as production-ready.

## Configuration and secrets

- `AIVO_PROXY_URL` is an optional HTTPS endpoint, not a place for credentials or tokens.
- Keep secrets out of source, committed Gradle properties, the Android app package, test fixtures, and CI output.
- Do not add production credentials or deploy services as part of this foundation.
- The default empty endpoint disables backend configuration.

## Reporting

For a suspected vulnerability in this project, use GitHub's private vulnerability reporting for the repository when available. Do not include secrets or sensitive personal data in a public issue.
