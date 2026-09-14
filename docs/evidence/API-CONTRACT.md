Hermes Android client - verified API contract
Дата проверки: 2026-09-13

Статус

В /home/mg/projects/hermes-android нет обнаруживаемых файлов и нет .git; это не checkout Android-проекта. Поэтому исходники Android не изменялись, проект не собирался и тесты Android не запускались. Отчёт фиксирует контракт для следующего implementation-задачи.

Краткий вердикт

Для полноценного Android companion-клиента нужен authenticated Gateway WebSocket:

  wss://<host>[:<port>]/api/ws

Это JSON-RPC 2.0 поверх text frames, по одному JSON object на строку. Использовать TUI/Desktop gateway, а не OpenAI-compatible API, как основной транспорт: только он даёт session lifecycle, серверные event frames, approval RPC и bounded replay в одном канале.

HTTP API остаётся полезным fallback/diagnostic surface и для простого клиента: `/api/sessions/*`, `/v1/responses`, `/v1/runs/*` и SSE. Но HTTP API не заменяет rich WebSocket attachment/event contract.

1. Транспорт и authentication

Rich gateway:

- Backend `serve` предоставляет `/api/ws` (official docs: `nix-setup.md:1025`). Dashboard route `gateway_ws` монтирует его на `/api/ws` и передаёт соединение в `tui_gateway.ws.handle_ws` (`hermes_cli/web_routers/chat_ws.py:555-574`).
- Wire contract прямо указан в `tui_gateway/ws.py:1-4`: WebSocket transport, newline-delimited JSON-RPC, `gateway.ready` сразу после accept.
- Server принимает JSON-RPC request only when `method` is non-empty string and `params` is an object/null; invalid request/params and unknown method use standard errors -32600/-32601/-32602 (`tui_gateway/server.py:739-758`).
- Normal reply: `{"jsonrpc":"2.0","id":<same id>,"result":{...}}`; error: `{"jsonrpc":"2.0","id":<same id>,"error":{"code":<int>,"message":<string>,"data":...}}` (`tui_gateway/server.py:723-729`).

Auth modes are not interchangeable:

- Loopback or explicit `--insecure`: legacy `?token=<SESSION_TOKEN>`, constant-time checked. In loopback mode non-loopback peers are rejected (`hermes_cli/web_server_chat.py:137-157`, `:220-231`, `:285-290`). Do not hardcode or log the token.
- Gated remote backend: legacy `?token` is rejected. Accepted credentials are a single-use `?ticket` or server-internal `?internal`; the latter is not for Android (`hermes_cli/web_server_chat.py:220-231`, `:256-283`).
- Preferred Android flow: use RFC 8252 native PKCE (`GET /auth/native/authorize`, `code_challenge_method=S256`, loopback redirect `http://127.0.0.1:<port>` or `http://[::1]:<port>`), then exchange the returned code at `POST /auth/native/token` with `{code, code_verifier}`. The server explicitly validates S256 and loopback-only redirect (`hermes_cli/dashboard_auth/routes.py:220-285`, `:467-486`).
- Native token response contains `{access_token, refresh_token, token_type:"Bearer", expires_at, provider, user_id}`. Refresh is `POST /auth/native/refresh` with `{refresh_token, provider?}`; expiry returns 401 `session_expired` (`routes.py:108-113`, `:489-514`). Keep tokens only in Android secure storage; never put them in the task report or URL.
- Bearer access tokens work through gated middleware for HTTP API routes (`hermes_cli/dashboard_auth/middleware.py:148-170`). To upgrade WebSocket, send `POST /api/auth/ws-ticket` with the bearer; response is `{ticket, ttl_seconds}`, ticket TTL is 30 seconds and single-use (`routes.py:438-464`). Mint immediately before opening the socket.
- Best upgrade form avoids putting the credential in URL logs: `Sec-WebSocket-Protocol: hermes-gateway-v1, hermes-gateway-ticket.<ticket>`. Both protocol names and exactly one ticket protocol are required; server reflects only the stable `hermes-gateway-v1` subprotocol (`web_server_chat.py:202-217`, `:265-280`). Query `?ticket=` is also accepted but is less desirable for logging hygiene.
- Host and optional Origin must match the configured bound/trusted host; no Origin is allowed, while non-HTTP origins are accepted for packaged clients (`web_server_chat.py:165-189`). Android should send the backend host and omit a browser Origin unless a deployment explicitly requires one.

2. WebSocket startup and event envelope

First server event after accept:

  {
    "jsonrpc":"2.0",
    "method":"event",
    "params":{
      "type":"gateway.ready",
      "payload":{
        "skin": ..., "change_events": true, "heartbeat": true,
        "replay_epoch":"..."
      }
    }
  }

`gateway.ready` shape is implemented at `tui_gateway/ws.py:284-302`. Per-session event frames are built as:

  {"jsonrpc":"2.0","method":"event","params":{
    "type":"<event>", "session_id":"<runtime sid>", "payload":{...}, "seq":N
  }}

`server._event_frame` defines the envelope (`tui_gateway/server.py:597-603`); `write_json` stamps session events and routes them to that session's transport (`server.py:582-594`). Treat `type`, `session_id`, `payload`, and `seq` as the stable fields; tolerate additional fields.

Relevant native event types verified in callbacks:

- `message.start`, `message.delta`, `message.complete`
- `reasoning.delta`, `thinking.delta`, `message.interim`
- `tool.start`, `tool.complete`, `tool.generating`
- `approval.request`
- `status.*`, `session.info`, `notification.show`, `notification.clear`
- `reaction`

The callback bindings are in `tui_gateway/agent_callbacks.py:89-125`; child-session mirroring explicitly emits message/tool event names at `:54-78`. Token events are coalesced by the WebSocket transport; tool/approval/status/completion events flush promptly and preserve ordering (`tui_gateway/ws.py:70-76`, `:116-166`). Android must not assume one network frame equals one token or one event; parse each newline-delimited JSON object and allow multiple objects per WebSocket frame.

3. Session lifecycle RPCs

`session.create` (`tui_gateway/methods_session.py:325-395`):

Request params supported by the verified handler include:

  {
    "profile":"default"?,
    "messages":[{"role":"user|assistant|...","content":"..."}]?,
    "cwd":"/absolute/or/known/path"?,
    "title":"..."?,
    "parent_session_id":"..."?,
    "model": ... / provider/model override fields?,
    "close_on_disconnect": false?,
    "hidden": false?
  }

Response result includes `session_id` (runtime id), `stored_session_id` (durable key), `message_count`, `messages`, and `info` with model/provider/cwd/branch/project/lazy/desktop_contract/profile_name. An empty draft is not persisted until its first prompt; seeded sessions are persisted immediately.

`session.list` (`methods_session.py:425-437`): params `limit`, optional exact `title`, optional `include_hidden`; response `{sessions:[safe summaries...]}`.

`session.resume` (`methods_session.py:825-857`): requires `params.session_id`; it can reuse a live session or cold/deferred-resume a durable session. The Android client must retain the durable `stored_session_id` and not assume a runtime `session_id` survives backend restart.

Useful lifecycle/control methods verified in the same module:

- `session.activate`, `session.delete`, `session.close`
- `session.interrupt` (`methods_session.py:1987-2015`)
- `session.steer` and `session.redirect` (`methods_session.py:2037-2065`)

Prompt submission (`prompt.submit`, `methods_prompt.py:540-653`):

  {"session_id":"<runtime sid>", "text":"...", "queued":false?, "interrupted":false?, "surface":"hud"?}

The reply is `{status:"streaming", ...}`. The user turn is then delivered asynchronously through event frames. Keep the RPC id mapping separate from event processing; the event `session_id` is the routing key.

4. Replay/reconnect

After reconnect, first compare `gateway.ready.payload.replay_epoch` to the saved epoch. A changed epoch means the server process restarted and prior sequence watermarks must be reset (`event_replay.py:21-24`, `:45-47`). For each session, call:

  {
    "jsonrpc":"2.0", "id":..., "method":"session.events.since",
    "params":{"session_id":"<runtime sid>", "last_seen":N}
  }

The result is `{events:[<params dicts>], latest_seq:N, truncated:boolean, count:N, epoch:"..."}` (`methods_session.py:2179-2191`). Replay returns event `params` objects, not full JSON-RPC envelopes (`event_replay.py:100-108`). If `truncated=true`, do not pretend the stream is complete: refetch session history/messages, then resume live events. The server ring is bounded to 512 events / 4 MiB per session and 64 MiB process-wide (`event_replay.py:27-34`, `:85-96`).

5. Approval flow

Server emits `approval.request` with redacted command and a choices list. TUI RPCs are:

- `approval.pending` -> `{approvals:[...]}`
- `approval.received` with `request_id`
- `approval.respond` with `choice` (`once`, `session`, `always`, `deny` where permitted), optional `all`, and optional exact `request_id`

Verified at `methods_prompt.py:1114-1189`; server always redacts approval command before event delivery (`server.py:640-653`, `:688-...`). The Android UI must default to deny on timeout/cancel and must not infer that tool output is a client-side tool call. Tools execute server-side.

6. Attachments

Rich TUI/Desktop RPC attachment support is materially different from `/v1` OpenAI routes:

- `image.attach_bytes` is intended for remote clients. Params: `content_base64` (or `data`), optional `filename`/`ext`; a `data:image/...;base64,...` wrapper is accepted. Payload is decoded, checked by magic/extension, capped at 25 MiB, queued in the session's image directory, and reply includes `attached`, `path`, `count`, metadata, `bytes`, and generated marker text (`methods_prompt.py:717-745`; caps in `prompt_attachments.py:16-18`, decoder `:30-61`). Use this for Android screenshots/photos.
- `pdf.attach` accepts host `path` or base64 `content_base64`, caps PDF at 50 MiB and 25 pages, renders pages to PNG, and queues them as images (`methods_prompt.py:795-850`; `prompt_attachments.py:16-18`).
- `file.attach` stages non-image bytes using a gateway-visible `path` or `data_url`; for remote Android, send a data URL and a sanitized `name`. Reply includes `ref_path`, `ref_text` (`@file:...`), `path`, `name`, and `uploaded` (`methods_prompt.py:853-873`; staging rules `prompt_attachments.py:136-187`).
- `clipboard.paste` is desktop-only and should not be exposed as an Android feature (`methods_prompt.py:665-686`).
- Attach first, then submit a prompt that references the generated marker/text. Do not send a client filesystem path unless the path is actually visible on the Hermes host.

HTTP `/v1/chat/completions` and `/v1/responses` support only inline images: `http(s)` or `data:image/...` URLs. Uploaded files (`file`, `input_file`, `file_id`) and non-image data URLs are explicitly rejected with `400 unsupported_content_type` (`website/docs/user-guide/features/api-server.md:90-109`, `:147-164`; validation in `api_server.py:485-528`).

Do not confuse `/v1/artifacts/upload` with chat attachments. It is a browser-control, API-key-authenticated, raw one-shot artifact store: raw request body, allowed `Content-Type`, required `X-Artifact-Filename`, bounded bytes, scope-bound receipt, then one-shot download (`api_server.py:2550-2608`, `:2610-2633`). It is not the generic Android file-upload contract.

7. HTTP fallback surfaces

The authoritative route table is `APIServerAdapter._http_route_table` (`gateway/platforms/api_server.py:1515-1563`):

- `GET /v1/health`, `GET /v1/models`, `GET /v1/capabilities`
- Session CRUD/history/fork:
  `GET|POST /api/sessions`, `GET|PATCH|DELETE /api/sessions/{session_id}`,
  `GET /api/sessions/{session_id}/messages`,
  `POST /api/sessions/{session_id}/fork`
- Synchronous/streaming session chat:
  `POST /api/sessions/{session_id}/chat`,
  `POST /api/sessions/{session_id}/chat/stream`
- OpenAI-compatible:
  `POST /v1/chat/completions`, `POST /v1/responses`,
  `GET|DELETE /v1/responses/{response_id}`
- Durable runs:
  `POST /v1/runs`, `GET /v1/runs/{run_id}`,
  `GET /v1/runs/{run_id}/events`,
  `POST /v1/runs/{run_id}/approval`, `/steer`, `/stop`

Session REST details:

- `GET /api/sessions` supports `limit`, `offset`, `source`, `include_children`, exact `title`; response `{object:"list", data:[...], limit, offset, has_more}` (`api_server.py:2749-2796`).
- `POST /api/sessions` accepts optional `id|session_id`, `system_prompt`, `source`, title, and runtime/model lock fields; response 201 `{object:"hermes.session", session:<safe summary>}` (`api_server.py:2798-2864`).
- `GET /api/sessions/{id}/messages` supports `limit`, `offset`, `order=oldest|latest`, max page 500; response has resolved session id, safe message fields, and pagination (`api_server.py:2925-2956`).
- `POST /api/sessions/{id}/chat/stream` is SSE (`Content-Type: text/event-stream`) with `run.started`, `message.started`, `assistant.delta`, tool progress, `assistant.completed`, `run.completed`, `error`, and `done`; keepalive comments are emitted (`api_server.py:3126-3225`).
- `/v1/runs` returns 202 immediately `{run_id,status,replayed}`. `GET /v1/runs/{id}/events` is SSE; run event names include `message.delta`, `tool.started`, `tool.completed`, `approval.request`, `run.completed`, `run.failed`, `run.cancelled` (`api_server_runs.py:377-504`, `:603-655`, `:717-820`). `Idempotency-Key` is supported and conflicts are 409 (`api_server_runs.py:395-406`, `:434-485`).
- `/v1/responses` is the easiest HTTP multi-turn contract: `previous_response_id` and named `conversation` preserve server-side history; tool calls in output were executed server-side (`website/docs/user-guide/features/api-server.md:115-189`). This is not the same event naming as rich Gateway WebSocket.

HTTP auth is `Authorization: Bearer <API_SERVER_KEY>`. The API server rejects a missing/incorrect bearer with 401 and expects a configured key (`gateway/platforms/api_server.py:1338-1384`; official docs `website/docs/reference/environment-variables.md:542-549`). API server default is `127.0.0.1:8642`; the official quick start uses `hermes gateway`, then `/v1` (`website/docs/user-guide/features/api-server.md:17-54`).

8. Remote deployment/security recommendation

- Keep the backend on a private tailnet or behind a TLS reverse proxy. Do not expose an unauthenticated non-loopback listener. Hermes docs state that a non-loopback backend host starts the authentication gate (`website/docs/getting-started/nix-setup.md:1019-1028`).
- For Android, preferred order is: private Tailscale address/hostname -> HTTPS/WSS -> native PKCE bearer -> short-lived WS ticket -> WebSocket. Never ship `API_SERVER_KEY` or a dashboard/session token inside the APK.
- Pin the backend host certificate/normal CA policy as appropriate; use reconnect with jitter and never reuse a consumed ticket. On 401/4401, refresh bearer or restart native login; on 4403, report host/origin/policy mismatch; on `truncated=true`, history-refetch before rendering a recovered stream.
- Gateway heartbeats are part of the ready contract (`heartbeat:true`); implement periodic `gateway.ping` and a read watchdog. The server's ping reply is `{jsonrpc:"2.0", result:{ok:true}, id:<same id>}` (`tui_gateway/ws.py:325-353`).

9. Verified local environment / implementation blocker

- `/home/mg/projects/hermes-android` exists but is empty to the available file inventory and is not a Git repository (`git status`: fatal, not a git repository).
- No `gradlew` was present and system `gradle` was not installed. Do not install dependencies or create a substitute project in this research task.
- Android platform-tools exists; `platform-tools/source.properties` reports `Pkg.Revision=37.0.0`. `adb devices -l` found no attached device.
- No Android build/test result is claimed.

10. Next implementation action

Restore/checkout the Android repository at `/home/mg/projects/hermes-android`, then assign implementation to a coding worker. Start with a small transport module and contract tests using a fake WebSocket server: native PKCE/token refresh, WS-ticket mint, subprotocol upgrade, newline JSON-RPC parser, `gateway.ready`, session create/resume, prompt/event routing, approval response, replay truncation, and `image.attach_bytes`/`file.attach` data URL paths. Only after those tests pass add UI state and device integration.

Primary source references

- Official docs: https://hermes-agent.nousresearch.com/docs/developer-guide/programmatic-integration?format=md
- Official API docs: https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server?format=md
- Local authoritative API route table: `/home/mg/.hermes/hermes-agent/gateway/platforms/api_server.py`
- Local authoritative TUI WebSocket: `/home/mg/.hermes/hermes-agent/tui_gateway/ws.py`
- Local JSON-RPC method contract: `/home/mg/.hermes/hermes-agent/tui_gateway/server.py`
- Sessions: `/home/mg/.hermes/hermes-agent/tui_gateway/methods_session.py`
- Prompt/attachments/approvals: `/home/mg/.hermes/hermes-agent/tui_gateway/methods_prompt.py`
- Event callbacks/replay: `/home/mg/.hermes/hermes-agent/tui_gateway/agent_callbacks.py`, `/home/mg/.hermes/hermes-agent/tui_gateway/event_replay.py`
- Dashboard WS/auth: `/home/mg/.hermes/hermes-agent/hermes_cli/web_routers/chat_ws.py`, `/home/mg/.hermes/hermes-agent/hermes_cli/web_server_chat.py`, `/home/mg/.hermes/hermes-agent/hermes_cli/dashboard_auth/routes.py`, `/home/mg/.hermes/hermes-agent/hermes_cli/dashboard_auth/middleware.py`
