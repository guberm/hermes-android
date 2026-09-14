# Hermes Android companion

A native Kotlin + Jetpack Compose client for an authenticated Hermes Gateway. The app is intentionally a companion surface: sessions and tools execute on the Gateway, while Android provides connection setup, streaming chat, approvals, and attachment staging.

## What is implemented

- Native RFC 8252 PKCE sign-in with a loopback callback on `127.0.0.1`.
- Android Keystore AES/GCM storage for the gateway origin and bearer/refresh tokens.
- Bearer-authenticated short-lived WebSocket ticket via `/api/auth/ws-ticket`.
- Newline-delimited JSON-RPC over `/api/ws`, tolerant of multiple messages per WebSocket frame.
- Gateway ready, heartbeat, session list/create/resume, prompt streaming, tool progress, approvals, replay watermark, and truncated-replay recovery.
- Image uploads through `image.attach_bytes` and non-image files through `file.attach` data URLs. The Android filesystem path is never sent to Hermes as a host path.
- Explicit offline/error/unsupported states. Clipboard integration, local Android tool execution, and unauthenticated Desktop endpoints are not exposed.
- Original vector launcher mark and dark Material 3 interface.

## Backend prerequisites

Use a Hermes Gateway with native PKCE enabled and a private HTTPS/WSS route. A non-loopback backend must have its authentication gate configured. This app does not ship `API_SERVER_KEY`, a dashboard token, or a host address. If the gateway is only an unauthenticated loopback Desktop server, configure a secure authenticated deployment first rather than weakening the app.

The protocol contract used here is documented in [`docs/evidence/API-CONTRACT.md`](docs/evidence/API-CONTRACT.md).

## Build

Requires JDK 17 and Android SDK platform 35. The Gradle wrapper pins Gradle 8.10.2 and AGP 8.7.2.

```bash
export ANDROID_HOME="$HOME/.local/share/android-sdk"
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

The debug artifact is an alpha/debug build and is not a production release. For a local upgrade-safe release build, keep the keystore outside this repository and provide it only through environment variables:

```bash
export HERMES_KEYSTORE=/private/path/hermes-release.jks
export HERMES_KEYSTORE_PASSWORD='use-a-secret-manager-or-private-shell'
export HERMES_KEY_PASSWORD="$HERMES_KEYSTORE_PASSWORD"
./gradlew :app:assembleRelease
```

The Gradle configuration only enables release signing when all three variables are present. Never commit a keystore, passwords, `local.properties`, or APKs.

## Verification boundaries

Unit tests cover URL validation, HTTPS-to-WSS mapping, Hermes one-object-per-WebSocket-frame plus newline/multi-object compatibility framing, JSON-RPC request envelopes, durable/runtime session identity and replay sequencing, generation-safe reconnect policy/watchdog behavior, session interrupt/pending-approval payloads, expiry safety window, error mapping, and attachment size/data-URL behavior. They are protocol/logic tests and do not claim a live Gateway.

A live integration test requires a user-authorized gateway URL and sign-in. No backend credentials are stored in this repository. Device validation requires an attached authorized device or isolated emulator; the build remains useful without one.
