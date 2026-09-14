# 0.1.5: message menu, safe top inset, and themes

Verified signed release on Pixel 7 Pro (1440 x 3120).

- Ordinary tap does not show Copy. Long press on UI_CHECK_OK opens Copy message; selecting it closes the menu. No permanent Copy icon remains.
- App content stays below the 144 px status bar, including the three-line conversation header.
- Dark/Light switch in Connection settings changes the theme immediately, persists across force-stop/restart, and updates status-bar icon contrast. Light screenshot inspected; original Dark preference restored.
- Composer bottom is 3004 px with keyboard hidden (navigation bar starts at 3036); 1740 px with keyboard visible (IME starts at 1772). Both have a 32 px gap. Light mode keyboard check also passes.
- Focused-search drawer close and system Back regression pass.
- 38 JVM tests, release lint, and signed release build pass.
- Installed versionName 0.1.5, versionCode 6 using an in-place upgrade; authentication retained. Live Gateway returned UI_CHECK_OK.
- Runnable device regression: scripts/check_message_ui.py SERIAL VISIBLE_MESSAGE_TEXT; scripts/check_navigation_insets.py SERIAL [--keyboard].

APK SHA-256: dca5740dc973c46e7a3d60d2d818a91e1a3d4c204d1149a55734701b3d19457e.
