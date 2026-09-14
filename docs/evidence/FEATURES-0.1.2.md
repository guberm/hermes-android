# Chat conveniences: 0.1.2

Compared with [adebnar/hermes-android](https://github.com/adebnar/hermes-android/tree/9f08f39ed2b9fc7cb29a551b1d9b695a409fdb7a) on 2026-09-14. Implemented these capabilities within this app's existing Compose/StateFlow architecture without copying source code or adding dependencies.

| Capability | Outcome |
| --- | --- |
| Pin chats | Added; local preferences scoped by gateway URL; stable pinned-first ordering |
| Search chats | Added; case-insensitive title and preview search over loaded sessions |
| Copy and export | Added selectable message text, message/transcript copy, and native text-file export |
| Model selection and reconnect | Already implemented and retained |
| Cron, tenant profiles, usage, messaging administration, archive/delete | Deferred: reference uses dashboard REST endpoints that this client's native Gateway integration has not verified |
| Full-text server search, Markdown/code rendering, command palette | Not included in this increment |

## Verification

- Installed version 0.1.2 (code 3) on the connected Pixel 7 Pro, Android 17.
- Pinned the test chat `ANDROID_OK`; after force-stop/relaunch it was first in the drawer with an Unpin action.
- Searching `android` left the matching test chat visible.
- Exported the test transcript through the Android document picker to Downloads. Read back 232 bytes containing all four user/assistant test turns, ending in `FINAL_OK`.
- Build, 34 unit tests, and lint completed successfully; no test failures/errors.
- Regression test covers pinned-first stable ordering, unpinned order, case/whitespace-insensitive filtering, absent pins, and no matches.
- Debug APK SHA256: `498336F5E2D029DCE19DB12B827367C0630BD4A197B897A115E8EB8DF7552834`.

Pins are device-local and do not sync to desktop. Search and export cover currently loaded data. This is a debug build, not a production-signed release.
