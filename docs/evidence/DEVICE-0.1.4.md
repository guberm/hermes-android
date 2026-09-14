# 0.1.4: insets and send modes

Verified on Pixel 9 Pro XL with the permanent release signing key.

- Original navigation overlap reproduced: composer footer bottom 2965 px, navigation bar top 2920 px.
- Original keyboard gap reproduced: footer 1042 px above the keyboard.
- Fixed: footer bottom 2893 px with keyboard hidden (27 px above navigation); 1727 px with keyboard open (27 px above keyboard at 1754 px).
- `scripts/check_navigation_insets.py SERIAL [--keyboard]` checks both overlap and excessive keyboard gap.
- `scripts/check_drawer.py SERIAL` passes for focused-search Close and system Back.
- Long-press Send shows only Steer/Queue, marks the default, and does not offer persistent settings.
- The separate Chat actions / Default send mode dialog saves the preference. Selected Steer, restarted the process, and verified Steer (default) in the long-press menu. Restored Queue afterward.
- Live steering: during a terminal sleep, requested a changed final response through Steer; server acknowledged it and returned STEER_OK.
- Live local queue: while the agent slept, the unsent text appeared in a QUEUED card. Cancel removed CANCELLED_SHOULD_NOT_RUN without adding it to history. Send now removed another card after Steer acknowledgement, and the current response changed to NOW_SENT_OK. A remaining card automatically dispatched after completion.
- JVM tests include explicit RPC mode selection, retaining notification waiting for queued turns, and persisted queue round-trips without stale runtime IDs. Release lint and build pass.

The gateway can reject a Steer if the agent has already finished or does not support steering; the draft or queued card stays available in that case. Pending cards live on this device until dispatch. After restart, reopen the chat to resume its saved queue. No server configuration is modified when changing the Android default.

Final build verification:
- 38 JVM tests pass, including a regression that failed before the fix: the queued user prompt precedes an assistant answer even when streaming/completion arrives before the submit acknowledgement.
- Release lint and signed assembleRelease pass.
- Final APK SHA-256: a7ac3e9711c88418496ac7c0221e4e59a20e7882a7b4b6d803c3dd6c3b12b084.
- Pixel 9 Pro XL was no longer connected for final ordering acceptance. Pixel 7 Pro had an incompatible old signature; after the user requested installation on Pixel 7, the old app was removed and the final signed APK installed successfully. Package manager confirms versionName 0.1.4 / versionCode 5. Sign-in must be completed again; final live ordering acceptance remains unverified.
