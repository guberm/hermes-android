# Hermes Android

A native Kotlin + Jetpack Compose client for an authenticated Hermes Gateway. The app is intentionally a companion surface: sessions and tools execute on the Gateway, while Android provides connection setup, streaming chat, approvals, and attachment staging.

**[Download the latest APK](https://github.com/guberm/hermes-android/releases/latest)** · [Release notes](https://github.com/guberm/hermes-android/releases) · [Report an issue](https://github.com/guberm/hermes-android/issues)

## Install and connect

Requires **Android 8.0 or newer** and an authenticated Hermes Gateway with native PKCE sign-in enabled.

1. Download the `hermes-android-…-release.apk` asset from the latest release.
2. Install the APK. If Android asks, allow installation from the browser or file manager you used.
3. Enter your Gateway's HTTPS address and tap **Sign in with Hermes**. Complete sign-in in the browser and return to the app.
4. Allow notifications to receive an alert when a reply is ready.
5. Open **Sessions** to select an existing chat or start a **New conversation**.

Install subsequent releases over the existing app to retain sign-in and preferences. Older candidate/debug builds with a different signing key require a reinstall, which removes local sign-in, preferences, pins, and pending local messages. History already saved on the Gateway remains available after signing in again.

## Features

- **Conversations:** create chats, load history with message times, stream replies, follow expandable Gateway reasoning and tool progress, and answer approval requests. Final replies appear after their reasoning and tool activity.
- **Pins and search:** pin chats to the top and filter loaded titles and previews. Pins are saved locally for each Gateway.
- **Models and reasoning:** use **Choose model** in the chat header to search Gateway models and set the current conversation's reasoning level.
- **Steer and Queue:** guide a running response or save a message to send after it finishes.
- **Notifications:** receive reply alerts and tap them to open the conversation.
- **Copy and export:** long-press a message for **Copy message**; use **Chat actions** to copy or export the loaded transcript.
- **Attachments:** stage images and files on the Gateway, up to 25 MiB per attachment.
- **Appearance:** switch between Dark and Light in **Connection settings**. The preference is saved on this device.
- **Layout:** content respects the status bar, display cutouts, navigation bar, and keyboard. Close the session drawer with its close button or system Back.

## Sending messages

Set **Chat actions → Default send mode** to **Steer** or **Queue**. Ordinary Send uses this preference while the agent is working. Long-press **Send** to choose once without changing the default. **Stop streaming** remains a separate button.

| Action | Behavior |
| --- | --- |
| **Steer** | Send guidance to the current response; acceptance depends on the Gateway and running agent. |
| **Queue** | Keep the message on this device until the current response finishes, then submit it. |
| **Cancel** on a queued card | Remove the unsent message from the local queue. |
| **Send now** on a queued card | Use Steer during a response, or start a new turn when idle. |

Unsent messages appear as **QUEUED** cards. A card remains visible while submission is in progress and disappears after acceptance. Rejected requests remain available with an error. If delivery cannot be confirmed after a connection loss, check the chat before retrying to avoid duplicates.

Pending messages are saved in private app storage for that Gateway. After a force-stop or restart, reopen the chat to resume its queue. Regular Send creates a conversation if none exists yet.

## Chat actions and appearance

Long-press a message and choose **Copy message**. There is no permanent Copy button. **Chat actions → Copy transcript** and **Export chat (.txt)** use the currently loaded conversation; export opens Android's document picker. **Refresh chats** reloads the session list. Drawer search filters loaded titles and previews, not all server messages.

Use **Connection settings → Dark mode** to switch between Dark and Light. The selection survives restart, and status-bar icons follow the selected theme.

## Notifications

Allow notifications when Android asks, or use **Connection settings → Notification settings**. Completion produces a reply notification with sound, subject to Android's channel and Do Not Disturb settings. The app deliberately does not show a persistent **Hermes is working** notification.

Notifications use the authenticated Gateway connection, not a separate push backend. Android may stop a background connection when the app is not active, so completion alerts are reliable while the app remains active but are not a background push guarantee. The app does not monitor every unrelated server conversation when stopped.

## Authentication and protocol

- Native RFC 8252 PKCE sign-in with a loopback callback on `127.0.0.1`.
- Android Keystore AES/GCM storage for the gateway origin and bearer/refresh tokens.
- Bearer-authenticated short-lived WebSocket ticket via `/api/auth/ws-ticket`.
- Newline-delimited JSON-RPC over `/api/ws`, tolerant of multiple messages per WebSocket frame.
- Gateway ready, heartbeat, session list/create/resume, prompt streaming, tool progress, approvals, replay watermark, and truncated-replay recovery.
- Image uploads through `image.attach_bytes` and non-image files through `file.attach` data URLs. The Android filesystem path is never sent to Hermes as a host path.
- Explicit offline/error/unsupported states. Local Android tool execution and unauthenticated Desktop endpoints are not exposed.
- Original vector launcher mark and Material 3 interface with dark and light themes.
- Chat history accepts the Gateway's `text` format and legacy `content` blocks; late session responses cannot replace a newer selection.
- Searchable, server-provided model picker, with session-only changes and server-requested confirmation.
- Reply notifications with sound when a tracked Android-started response completes. Tapping a notification opens its conversation.

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

The debug artifact is `app/build/outputs/apk/debug/app-debug.apk` and uses a different signing identity from official releases. For a local upgrade-safe release build, keep the keystore outside this repository and provide it only through environment variables:

```bash
export HERMES_KEYSTORE=/private/path/hermes-release.jks
export HERMES_KEYSTORE_PASSWORD='use-a-secret-manager-or-private-shell'
export HERMES_KEY_PASSWORD="$HERMES_KEYSTORE_PASSWORD"
./gradlew :app:assembleRelease
```

The Gradle configuration only enables release signing when all three variables are present. Never commit a keystore, passwords, `local.properties`, or APKs.

### GitHub signed builds

The [Signed APK workflow](.github/workflows/signed-apk.yml) runs on pushes to `main` and through **Run workflow**. It runs unit tests and release lint, builds the signed APK, verifies its certificate, and uploads only the APK as an Actions artifact. Publishing a GitHub Release is a separate step.

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

## Verification boundaries

Version **0.1.7** passed 38 JVM tests and release lint. Its signed APK was installed on a Pixel 7 Pro and checked against a live Gateway. Device checks covered the order of active tool cards after the user's message, long streaming replies, no foreground waiting service, themes, system/keyboard insets, and drawer dismissal. See [v0.1.7 device evidence](docs/evidence/DEVICE-0.1.7.md).

Runnable checks require ADB and a signed-in app:

```bash
python scripts/check_drawer.py DEVICE_SERIAL
python scripts/check_navigation_insets.py DEVICE_SERIAL
# Focus the composer first to open the keyboard:
python scripts/check_navigation_insets.py DEVICE_SERIAL --keyboard
# Display a message first; this restarts the app and restores its initial theme:
python scripts/check_message_ui.py DEVICE_SERIAL "VISIBLE_MESSAGE_TEXT"
# Start a tool-running request first:
python scripts/check_active_tool_after_user.py DEVICE_SERIAL "VISIBLE_USER_MESSAGE" "VISIBLE_ASSISTANT_ANSWER"
```

Unit tests cover URL validation, HTTPS-to-WSS mapping, Hermes one-object-per-WebSocket-frame plus newline/multi-object compatibility framing, JSON-RPC request envelopes, durable/runtime session identity and replay sequencing, generation-safe reconnect policy/watchdog behavior, session interrupt/pending-approval payloads, expiry safety window, error mapping, and attachment size/data-URL behavior. They are protocol/logic tests and do not claim a live Gateway.

A live integration test requires a user-authorized gateway URL and sign-in. No backend credentials are stored in this repository. Device validation requires an attached authorized device or isolated emulator; the build remains useful without one.
