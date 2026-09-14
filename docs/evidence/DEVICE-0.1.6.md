# 0.1.6: message viewport and notifications

Verified on Pixel 7 Pro, Android 16, with the permanent release signing key.

- Sent a live Gateway prompt that produced fifteen lines beginning `LAYOUT_TEST`.
- The conversation header ended at 354 px; the first conversation item started at 368 px. The full assistant reply ended at 2566 px, above the composer, so neither the header nor composer covered it.
- The reply produced the normal `Hermes replies` completion notification containing the response text.
- There was no `ReplyWaitService` and no active `Hermes is working` notification. The legacy `hermes_waiting` channel is deleted on app startup.
- The composer bottom was 3004 px while the navigation bar began at 3036 px.
- `versionName=0.1.6`, `versionCode=7` installed as an in-place upgrade with sign-in retained.
- 38 JVM tests and release lint passed. APK signature SHA-256 matches the permanent release certificate.

APK SHA-256: `89240B3F26E6F3388A154E127694013D724010517C4041C27DBC7C7E9CB753CE`.
