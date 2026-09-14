# Android 0.1.1 device acceptance

Tested on 2026-09-14 against https://hermes.guber.dev/.

- Baseline: `9b2e31e`; branch: `gbr/android-connection-fixes`.
- Installed debug candidate: version 0.1.1, version code 2.
- Device: Pixel 7 Pro, Android 17, connected through ADB.
- APK: `release/hermes-android-v0.1.1-debug.apk` (ignored build artifact).
- SHA256: `D4F4DE275293DC57BEF10E0B9A0B4FA0577EA0371FF8443AF545770AC23102FA`.

## Observed results

- Native browser PKCE sign-in returned to the app and connected successfully.
- Selecting the test chat from the drawer restored user and assistant history, including after reconnecting. Gateway history uses `text`; legacy `content` remains supported.
- The model picker loaded the server's configured providers. Changing this test session from `gpt-5.6-luna` to `gpt-5.6-sol` was confirmed by the server. Restored `gpt-5.6-luna` afterward.
- With the final APK installed, sent `Reply exactly FINAL_OK` and returned to the launcher. The app's data-sync foreground service remained active while waiting.
- The reply notification contained `FINAL_OK` and the default notification sound URI. The waiting service stopped after completion.
- Opening the notification's session target through an Android intent restored the test chat; the screen showed `FINAL_OK`, `Connected`, and `gpt-5.6-luna`. An actual notification-shade tap was not independently verified.
- No crash entries were returned for the final app process.

## Automated checks

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: BUILD SUCCESSFUL.
- 33 unit tests, zero failures or errors.
- Lint: zero errors; five existing target-SDK/dependency warnings.
- APK signature verification: v2 verified, one signer.
- Source/build mirror comparison: zero source mismatches.
- `git diff --check`: passed.

Windows Controlled Folder Access blocked Java writes under Documents. Built from an identical source mirror under `%LOCALAPPDATA%\HermesAndroid\device-build`; Windows protection was left enabled. README contains the build command.

## Scope

Notifications cover replies to turns started in this app, including when it is in the background. This uses a temporary native foreground service and the gateway WebSocket, not server push. It does not guarantee delivery after force-stop or reboot and does not monitor unrelated server chats. This is a debug candidate, not a production-signed release or an independent review verdict.
