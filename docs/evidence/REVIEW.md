# Hermes Android - независимая acceptance-проверка

Дата проверки: 2026-09-13
Кандидат: `0d4b8c11f667527821ca8b40ff957d7df62cc98d`
Принятый commit: НЕТ - кандидат отклонён
Принятый APK: НЕТ - APK криптографически валиден, но функциональная acceptance не пройдена
Файлы implementation не изменялись. Изменён только этот review-отчёт в evidence directory.

## Итог

FAIL / release-blocked.

Сборка и локальные protocol-тесты проходят, но основной `/api/ws` путь несовместим с фактическим WebSocket framing Hermes: сервер отправляет один JSON object в каждом text frame без `\n`, а Android parser выдаёт JSON только после `\n`. Поэтому `gateway.ready`, RPC replies и события фактически не обрабатываются. Это блокирует login-to-chat/session flow и делает APK не release-worthy даже как alpha.

## Блокирующие дефекты

1. P0 - фактический WebSocket framing делает клиент нерабочим

   Доказательства:
   - Android `app/src/main/java/dev/guber/hermesandroid/data/JsonRpcCodec.kt:11-20`: `feed()` ищет только newline и возвращает сообщения только для завершённых строк.
   - Android `app/src/main/java/dev/guber/hermesandroid/data/GatewayClient.kt:158-161`: `onMessage()` вызывает только `decoder.feed(text)`; `decoder.finish()` в production path не вызывается (`finish()` найден только в codec-тесте).
   - Фактический Hermes server `tui_gateway/ws.py:108-112`: `WSTransport.write()` сериализует `json.dumps(obj)` без newline.
   - Фактический Hermes server `tui_gateway/ws.py:183-193`: `_safe_send_many()` передаёт сериализованный `line` непосредственно в `send_text()`, также без добавления newline.
   - Server `gateway.ready` отправляется этим transport в `tui_gateway/ws.py:297-301`.

   Последовательность отказа: сервер принимает `prompt.submit` и отвечает одним text frame вроде `{...}`; Android оставляет его в `StringBuilder`, потому что newline отсутствует. `onSessionReady`, `onSessions`, `onAttachment`, RPC errors и `gateway.ready` не вызываются. WebSocket может выглядеть `CONNECTED` на `onOpen`, но приложение не получает сессии и не может завершить создание/возобновление чата.

   Требуемое исправление: parser должен принимать фактический server shape (один JSON object per WebSocket text frame) и newline-delimited/multiple-object frames для совместимости; добавить реальный transport-тест, подающий server-shaped frame без newline и проверяющий dispatch.

2. P0 - потеря durable session identity при reconnect/backend restart

   Фактический сервер разделяет runtime и durable IDs: `tui_gateway/methods_session.py:699-702` возвращает `session_id` (runtime), `session_key` (durable), а `session.create` также возвращает `stored_session_id` в `:388-395`. Runtime ID не должен переживать restart.

   Android `GatewayClient.kt:214-218` выбирает только `result.optionalString("session_id", "stored_session_id", "id")` и передаёт одно значение в listener. `HermesUiState` содержит только `activeSessionId` (`HermesViewModel.kt:30-45`), а `GatewayClient.activeSessionId` (`GatewayClient.kt:44-46`) используется и для replay. Durable ID нигде не сохраняется.

   После backend restart старый runtime ID остаётся активным в UI. `gateway.ready` может сбросить replay watermark (`GatewayClient.kt:185-192`), но не выполняет `session.resume` по durable ID. Следующий prompt/replay использует мёртвый runtime ID и получает session-not-found; ручной выбор сессии из drawer - единственный обходной путь. Это нарушает контракт, требующий хранить `stored_session_id` и отдельно использовать runtime ID для live events.

   Требуемое исправление: хранить пару `(storedSessionId, runtimeSessionId)`, при reconnect/resume маппить durable ID на новый runtime ID, использовать durable ID для `session.resume`, а event/replay/prompt - только для актуального runtime ID. Добавить тест backend-restart/reconnect.

## Существенные дефекты, обнаруженные независимо

3. P1 - replay watermark не продвигается для replayed events

   `GatewayClient.kt:228-235` передаёт элементы `result.events` напрямую в `listener?.onEvent(replayParams)`, обходя `handleMessage()` и его обновление `lastSeen` на `:181-183`. После успешного replay watermark остаётся прежним, поэтому следующий reconnect повторно запрашивает тот же диапазон и может повторно доставить события. Кроме того, live path присваивает `lastSeen[sessionId] = seq` без `max`, тогда как out-of-order/late event не должен регрессировать watermark.

   Требуемое исправление: единый seq-gated dispatch для live и replay; `max(previous, seq)`; тест `live seq -> replay seq -> second reconnect` с проверкой отсутствия duplicate dispatch.

4. P1 - stale WebSocket generation может разрушить новое соединение

   `GatewayClient.kt:152-175` использует один shared listener для всех sockets. `onMessage()` не проверяет, что callback принадлежит текущему `socket`; `onClosing()` не проверяет generation; `onFailure()` безусловно делает `socket = null`, останавливает heartbeat и публикует ERROR. Sequence: socket A закрывается поздно после ручного reconnect, socket B уже присвоен; callback A обнуляет B и переводит UI в ошибку. Старые frames A также могут попасть в общий decoder и state.

   Требуемое исправление: захватывать socket generation и игнорировать callbacks, если `socket !== callbackSocket`; отдельно очищать pending requests/decoder только для текущего поколения; добавить deterministic stale-callback test.

5. P1 - cancellation отсутствует

   В `GatewayClient.kt` нет метода `session.interrupt`, а в `MainActivity.kt:476-482` composer всегда показывает только Send; отдельной stop/cancel affordance для `state.isSending` нет. Backend предоставляет `session.interrupt` (`tui_gateway/methods_session.py:1987-2015`). Это нарушает явно заявленное требование streaming cancellation: пользователь не может остановить зависший/долгий turn из Android.

   Требуемое исправление: добавить authenticated `session.interrupt` RPC, UI stop action, обработку interrupted terminal state и тест результата/ошибки.

6. P1 - reconnect/read watchdog отсутствует и пропущенные approval cards не восстанавливаются

   Клиент отправляет heartbeat (`GatewayClient.kt:57-62`), но не проверяет срок ответа и не запускает bounded reconnect/backoff после `onFailure()` (`:170-175` только переводит состояние в ERROR). Есть только ручной `Reconnect` в settings (`MainActivity.kt:506-513`). При disconnect во время approval пропущенный `approval.request` не восстанавливается через `approval.pending`; серверный RPC существует в `tui_gateway/methods_prompt.py:1125-1131`. Таким образом, turn может остаться server-side blocked без карточки, которую можно подтвердить/отклонить.

   Требуемое исправление: heartbeat deadline/read watchdog, bounded reconnect с понятным состоянием и восстановление pending approvals после reconnect/resume; покрыть disconnect/reconnect/approval race тестом.

## Acceptance matrix

| Область | Verdict | Evidence |
|---|---|---|
| Native Kotlin + Compose project | PASS (build-level) | Gradle build/test/lint ниже |
| PKCE, bearer ticket, TLS policy, Keystore storage | PARTIAL | Static inspection: `AuthApi.kt`, `SecureCredentialStore.kt`, manifest; live auth/backend не запускался |
| Actual authenticated `/api/ws` chat | FAIL | P0 framing mismatch above |
| Session list/create/resume | FAIL | Main path blocked by framing; durable/runtime reconnect bug |
| Streaming/tool/approval events | FAIL | Main path blocked; cancellation and approval recovery absent |
| Replay/reconnect | FAIL | P0 durable identity + P1 watermark/generation defects |
| Image/file attachments | PARTIAL | Size/path policy statically present; no live RPC/device/backend verification |
| Release APK package/signature | PASS as artifact only | Hash, `apksigner`, `aapt` below |
| Secret/source hygiene | PASS for repository scan | No matching private-key/API-key/bearer patterns; no remote/publish mutation |
| Device/UI smoke | NOT RUN | No attached device, emulator binary, or AVD available |

## Independent verification evidence

Commands run in `/home/mg/projects/hermes-android`:

- `ANDROID_HOME=/home/mg/.local/share/android-sdk ./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain`
  - `BUILD SUCCESSFUL`, 24 tasks executed.
  - XML reports: 5 + 3 + 4 = 12 tests, skipped=0, failures=0, errors=0.
  - These tests cover pure codec/payload/policy logic only; they do not exercise an actual WebSocket text frame from Hermes server.
- `ANDROID_HOME=/home/mg/.local/share/android-sdk ./gradlew :app:lintRelease --rerun-tasks --console=plain`
  - `BUILD SUCCESSFUL`.
  - 4 informational `GradleDependency` warnings remain (core-ktx, activity-compose, lifecycle runtime/viewmodel versions).
- `git diff --check HEAD`
  - clean.
- `git status --short`
  - clean before and after review; no implementation edits.
- APK SHA-256:
  - `/home/mg/work/hermes-android-evidence/release/hermes-android-v0.1.0-release.apk`
  - `/home/mg/projects/hermes-android/app/build/outputs/apk/release/app-release.apk`
  - both: `4968288b8ede82edd575b2c54d708f6ef5beeada8ec9186601b14989107633f8`
  - size: `11157564` bytes.
- `/home/mg/.local/share/android-sdk/build-tools/35.0.0/apksigner verify --verbose --print-certs ...`
  - Verifies; v2=true; v1/v3/v4=false; one signer; RSA 3072.
- `aapt dump badging ...`
  - package `dev.guber.hermesandroid`, versionCode `1`, versionName `0.1.0`, min SDK 26, target SDK 35, label `Hermes`, launchable `dev.guber.hermesandroid.MainActivity`.
- `adb devices -l`
  - no attached devices.
- Emulator binary/AVD check
  - `emulator` command not found; no emulator executable found under `/home/mg/.local/share/android-sdk`.
- Repository secret scan (filenames only on matches)
  - no matches for private-key, AWS-key, bearer-token, or API-key patterns.
- `git remote -v` and `git tag --list`
  - no remote and no tags; no GitHub mutation occurred.

## Coverage limitations

- Live authenticated Hermes backend integration was not run because no authorized gateway URL/credentials were available; this is acceptable as a stated environment limitation, but it cannot override the source-proven P0 framing failure.
- No emulator/device UI smoke test was possible in this environment.
- No GitHub repository or release was created; publication remains downstream and must not proceed from this FAIL review.

## Required next action

Return the candidate to the implementation worker for the six findings above, beginning with the P0 framing and durable/runtime identity fixes. Rebuild the APK, rerun transport/reconnect/cancellation tests, and request a fresh independent review for the exact new commit and APK hash. Do not publish the current candidate.
