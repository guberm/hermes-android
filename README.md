# Hermes Android companion

A native Kotlin + Jetpack Compose client for an authenticated Hermes Gateway. The app is intentionally a companion surface: sessions and tools execute on the Gateway, while Android provides connection setup, streaming chat, approvals, and attachment staging.

## What is implemented

- Native RFC 8252 PKCE sign-in with a loopback callback on `127.0.0.1`.
- Android Keystore AES/GCM storage for the gateway origin and bearer/refresh tokens.
- Bearer-authenticated short-lived WebSocket ticket via `/api/auth/ws-ticket`.
- Newline-delimited JSON-RPC over `/api/ws`, tolerant of multiple messages per WebSocket frame.
- Gateway ready, heartbeat, session list/create/resume, prompt streaming, tool progress, approvals, replay watermark, and truncated-replay recovery.
- Image uploads through `image.attach_bytes` and non-image files through `file.attach` data URLs. The Android filesystem path is never sent to Hermes as a host path.
- Explicit offline/error/unsupported states. Local Android tool execution and unauthenticated Desktop endpoints are not exposed.
- Original vector launcher mark and dark Material 3 interface.
- Chat history accepts the Gateway's `text` format and legacy `content` blocks; late session responses cannot replace a newer selection.
- Searchable, server-provided model picker, with session-only changes and server-requested confirmation.
- Reply notifications with sound and a temporary foreground service while an Android-started response is pending. Tapping a notification opens its conversation.

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

### GitHub signed builds

The **Signed APK** workflow runs on pushes to `main` and through **Run workflow**. It runs unit tests and release lint, builds the signed APK, verifies its certificate, and uploads only the APK as an Actions artifact.

Repository Actions secrets: `HERMES_KEYSTORE_BASE64`, `HERMES_KEYSTORE_PASSWORD`, `HERMES_KEY_PASSWORD`. Key alias: `hermes`.

Permanent release certificate SHA-256 (created September 14, 2026): `E93103035BE3FC77AA486D0186BD1E2A7D1FA110DCAC4C82CEEB0022B0730A03`.

This new key intentionally replaces the unavailable v0.1.0 candidate key. Older candidate/debug installations cannot update directly to this signature; reinstalling removes local app data. Subsequent releases must keep this key.

### Windows with Controlled Folder Access

If Windows blocks Java from writing under Documents, build a local copy under LocalAppData. Keep Windows protection enabled:

```powershell
$stage = Join-Path $env:LOCALAPPDATA 'HermesAndroid\device-build'
robocopy . $stage /E /XD .git .gradle .kotlin build /XF local.properties /NFL /NDL /NJH /NJS /NP
if ($LASTEXITCODE -ge 8) { throw 'Build staging failed' }
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
# Set JAVA_HOME to your installed JDK before invoking Gradle.
& "$stage\gradlew.bat" -p $stage :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain --max-workers=2
```

### Notifications

Allow notifications when Android asks, or use **Connection settings → Notification settings**. While a response started in Android is pending, a quiet **Hermes is working** notification keeps the connection active after leaving the app. The reply replaces it with a normal sound-enabled notification. Android's Do Not Disturb and channel settings still apply.

This uses the authenticated Gateway connection, not a push backend. It does not monitor every unrelated server conversation when the application is stopped. Disconnecting/signing out ends the local wait; it does not cancel work on the server.

## Verification boundaries

Unit tests cover URL validation, HTTPS-to-WSS mapping, Hermes one-object-per-WebSocket-frame plus newline/multi-object compatibility framing, JSON-RPC request envelopes, durable/runtime session identity and replay sequencing, generation-safe reconnect policy/watchdog behavior, session interrupt/pending-approval payloads, expiry safety window, error mapping, and attachment size/data-URL behavior. They are protocol/logic tests and do not claim a live Gateway.

A live integration test requires a user-authorized gateway URL and sign-in. No backend credentials are stored in this repository. Device validation requires an attached authorized device or isolated emulator; the build remains useful without one.
# Chat conveniences (0.1.2)

- Pin/unpin from the pin button on each chat row. Pinned chats appear first and persist on this device, separately for each gateway URL.
- Search loaded chat titles and previews from the drawer. This is local filtering, not a server-wide message search.
- Select text or use **Copy message**. **Chat actions** also provides **Copy transcript**, **Export chat (.txt)**, and **Refresh chats**.
- Export saves the loaded conversation through Android's system document picker.
