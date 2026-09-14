# 0.1.7: active tool ordering

Verified on Pixel 7 Pro, Android 16, with the permanent release signing key.

- Created an isolated chat and sent `Use terminal tool to sleep 25 seconds then reply exactly ORDER_FIXED_OK`.
- The pre-fix device check failed because `Thinking` appeared before the user message.
- With 0.1.7, the user message began at 522 px, `Thinking` began at 1226 px, and the running `terminal` card followed it at 1486 px.
- The header ended at 354 px, so no active content was hidden beneath it.
- The composer bottom was 3004 px while the navigation bar began at 3036 px.
- `check_active_tool_after_user.py` passed. `check_navigation_insets.py` and `check_no_waiting_notification.py` also passed.
- `versionName=0.1.7`, `versionCode=8` installed as an in-place upgrade with sign-in retained.
- 38 JVM tests and release lint passed. APK signature SHA-256 matches the permanent release certificate.

APK SHA-256: `04555BAEC5F3F85B0630716032FD2AE2DC4AA86E23DE380C3B09BEF0A500EBCD`.
