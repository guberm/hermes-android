# Hermes Android - результат remediation implementation

Дата: 2026-09-13
Репозиторий: `/home/mg/projects/hermes-android`
Commit: `a45b2ba85bedc4ddc39e9ba2f8018fa47d591cb8`
Commit message: `fix: harden Android gateway reconnect lifecycle`

## Итог

Исправлены шесть дефектов из FAIL review: фактическое Hermes WebSocket framing, разделение durable/runtime session identity, seq-gated replay, stale WebSocket callbacks, streaming cancellation и bounded reconnect/read-watchdog с восстановлением pending approvals.

Публикация не выполнялась. Hermes production/profile/credentials и сетевые настройки не изменялись. GitHub remote/repository/release не создавались.

## Что изменено

- `JsonRpcCodec.kt`
  - decoder теперь dispatch-ит один complete JSON object из каждого Hermes text frame без `\n`;
  - сохранена совместимость с newline-delimited и несколькими JSON object в одном frame;
  - добавлены payload helpers для `session.interrupt` и `approval.pending`.
- `GatewayLifecycle.kt`
  - введён отдельный `SessionIdentity(storedSessionId, runtimeSessionId)`;
  - добавлены seq watermark/dedup gate, WebSocket generation gate, bounded exponential reconnect policy и read-watchdog predicate.
- `GatewayClient.kt`
  - durable ID используется для `session.resume`, runtime ID - для prompt/events/replay/attachments/interrupt;
  - после `gateway.ready` active durable session автоматически resume-ится и получает новый runtime ID;
  - live и replay события проходят через один monotonic seq gate;
  - callbacks старых WebSocket generations игнорируются;
  - `session.interrupt` dispatch-ится через authenticated RPC;
  - heartbeat deadline/read watchdog запускает ограниченный reconnect (3 попытки, 1/2/4 секунды);
  - после resume запрашивается `approval.pending` и pending cards восстанавливаются;
  - production `onMessage` использует тот же `receiveTextFrame` seam, который покрыт тестом server-shaped frame без newline.
- `HermesViewModel.kt`
  - runtime session ID больше не смешивается с durable ID в prompt/event/attachment flow;
  - добавлены stop streaming и восстановление pending approvals;
  - stale runtime ID очищается при disconnect/reconnect.
- `MainActivity.kt`
  - во время streaming Send заменён на видимую Stop action;
  - attachments доступны только при наличии текущего runtime session.
- Поведенные тесты добавлены в `GatewayProtocolTest.kt` и `SessionAndErrorContractTest.kt`; README обновлён с реальной framing-моделью.

## Проверки

1. `ANDROID_HOME=/home/mg/.local/share/android-sdk ./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain`
   - `BUILD SUCCESSFUL`.
   - 20 tests: `GatewayProtocolTest` 12, `AttachmentPolicyTest` 3, `SessionAndErrorContractTest` 5; skipped=0, failures=0, errors=0.
   - Включает реальный `GatewayClient.receiveTextFrame` seam: Hermes-style complete JSON object без newline dispatch-ится, duplicate seq не доставляется повторно.
2. `ANDROID_HOME=/home/mg/.local/share/android-sdk ./gradlew :app:lintRelease --rerun-tasks --console=plain`
   - `BUILD SUCCESSFUL`.
   - Lint report: 0 errors, 4 informational `GradleDependency` warnings о более новых версиях `core-ktx`, `activity-compose` и lifecycle Compose; эти предупреждения не относятся к acceptance blockers.
3. `git diff --check`
   - clean.
4. Secret-pattern scan по Kotlin/Markdown source
   - private-key/API-key/bearer-token matches не найдено.
5. Signed release:
   - `ANDROID_HOME=/home/mg/.local/share/android-sdk ./gradlew :app:assembleRelease --console=plain`
   - `BUILD SUCCESSFUL`.
   - APK собран с существующим private signing key вне Git; пароль в отчёт не записывался.
6. APK hash/size:
   - `/home/mg/projects/hermes-android/app/build/outputs/apk/release/app-release.apk`
   - `/home/mg/work/hermes-android-evidence/release/hermes-android-v0.1.0-release.apk`
   - SHA-256 обоих файлов: `db9197bcb650af6c26b978af5300813687ab0cca43e5ce67fd3d29fd79714279`
   - размер: `11157564` bytes.
7. `apksigner verify --verbose --print-certs`
   - `Verifies`;
   - v2: `true`, один signer, RSA 3072;
   - signer certificate DN: `CN=Hermes Android, O=Guber Dev`.
8. `aapt dump badging`
   - package `dev.guber.hermesandroid`;
   - versionCode `1`, versionName `0.1.0`;
   - min SDK `26`, target SDK `35`;
   - launchable activity `dev.guber.hermesandroid.MainActivity`.

## Честные ограничения

- Live authenticated Hermes backend integration не запускалась: в окружении нет разрешённого gateway URL/credential. Поэтому framing/reconnect/interrupt/approval implementation подтверждена локальными transport-seam/protocol tests и source contract, но не заявляется как live production handshake.
- Устройство и Android emulator/AVD недоступны; device install/UI smoke test не выполнялся.
- `session.resume`, `session.interrupt`, `approval.pending` и фактические server event payloads не проверялись against live backend; их поля реализованы по контрактам из acceptance review.
- Lint dependency freshness warnings остаются (0 errors, 4 warnings).
- GitHub publication/release upload остаются задачей default/orchestrator после независимого re-review. Текущий APK не следует публиковать до PASS re-review.
