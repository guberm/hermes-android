package dev.guber.hermesandroid.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Authenticated newline-delimited JSON-RPC gateway transport. */
class GatewayClient(
    private val authApi: AuthApi = AuthApi(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) {
    interface Listener {
        fun onStatus(status: ConnectionStatus, detail: String? = null) {}
        fun onReady(replayEpoch: String?) {}
        fun onSessions(sessions: List<SessionSummary>) {}
        fun onSessionReady(sessionId: String, messages: List<ChatMessage>) {}
        fun onEvent(params: JSONObject) {}
        fun onAttachment(receipt: AttachmentReceipt) {}
        fun onReplayGap(sessionId: String) {}
        fun onRpcError(method: String, error: GatewayError) {}
    }

    var listener: Listener? = null
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, String>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val decoder = NewlineJsonRpcDecoder()
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
    private var heartbeat: ScheduledFuture<*>? = null
    private var socket: WebSocket? = null
    private var activeSessionId: String? = null
    private var replayEpoch: String? = null

    suspend fun connect(endpoint: GatewayEndpoint, auth: AuthSession): Result<Unit> = withContext(Dispatchers.IO) {
        close()
        listener?.onStatus(ConnectionStatus.CONNECTING, "Requesting a short-lived gateway ticket…")
        authApi.mintWsTicket(endpoint, auth.accessToken).fold(
            onSuccess = { ticket ->
                val request = Request.Builder()
                    .url(endpoint.wsUrl)
                    .header("Sec-WebSocket-Protocol", "hermes-gateway-v1, hermes-gateway-ticket.$ticket")
                    .build()
                socket = http.newWebSocket(request, socketListener)
                heartbeat = heartbeatExecutor.scheduleWithFixedDelay({
                    if (socket != null) {
                        sendRpc("gateway.ping")
                    }
                }, 30, 30, TimeUnit.SECONDS)
                Result.success(Unit)
            },
            onFailure = { error ->
                val message = error.message ?: "Unable to mint a gateway ticket"
                listener?.onStatus(ConnectionStatus.ERROR, message)
                Result.failure(error)
            },
        )
    }

    fun close() {
        heartbeat?.cancel(false)
        heartbeat = null
        socket?.close(1000, "Client closed")
        socket = null
        pending.clear()
        decoder.reset()
        listener?.onStatus(ConnectionStatus.DISCONNECTED)
    }

    fun shutdown() {
        close()
        heartbeatExecutor.shutdownNow()
    }

    fun setActiveSession(sessionId: String?) {
        activeSessionId = sessionId
        if (sessionId != null) requestReplay(sessionId)
    }

    fun requestSessions(limit: Int = 50) {
        sendRpc("session.list", JSONObject().put("limit", limit))
    }

    fun createSession(title: String? = null) {
        sendRpc("session.create", sessionCreateParams(title))
    }

    fun resumeSession(sessionId: String) {
        sendRpc("session.resume", sessionResumeParams(sessionId))
    }

    fun submitPrompt(sessionId: String, text: String) {
        sendRpc("prompt.submit", promptSubmitParams(sessionId, text))
    }

    fun respondApproval(requestId: String, choice: String) {
        sendRpc("approval.respond", approvalResponseParams(requestId, choice))
    }

    fun attachImage(sessionId: String, bytes: ByteArray, filename: String, extension: String?) {
        val params = JSONObject()
            .put("session_id", sessionId)
            .put("content_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("filename", filename)
        if (!extension.isNullOrBlank()) params.put("ext", extension)
        sendRpc("image.attach_bytes", params)
    }

    fun attachFile(sessionId: String, bytes: ByteArray, filename: String, mimeType: String) {
        sendRpc(
            "file.attach",
            JSONObject()
                .put("session_id", sessionId)
                .put("name", filename)
                .put("data_url", AttachmentPolicy.dataUrl(mimeType, bytes)),
        )
    }

    private fun requestReplay(sessionId: String) {
        if (socket == null) return
        sendRpc(
            "session.events.since",
            JSONObject().put("session_id", sessionId).put("last_seen", lastSeen[sessionId] ?: 0),
        )
    }

    private fun sendRpc(method: String, params: JSONObject = JSONObject()): Long? {
        val id = nextId.getAndIncrement()
        val ws = socket ?: return null
        pending[id] = method
        if (!ws.send(jsonRpcRequest(id, method, params))) {
            pending.remove(id)
            listener?.onRpcError(method, GatewayError(message = "Gateway socket rejected the request", retryable = true))
            return null
        }
        return id
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            listener?.onStatus(ConnectionStatus.CONNECTED, "Secure gateway channel open")
            sendRpc("gateway.ping")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                decoder.feed(text).forEach(::handleMessage)
            }.onFailure {
                listener?.onRpcError("transport", GatewayError(message = "Gateway sent invalid JSON", retryable = true))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            listener?.onStatus(ConnectionStatus.DISCONNECTED, "Gateway closed the channel ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            heartbeat?.cancel(false)
            heartbeat = null
            listener?.onStatus(ConnectionStatus.ERROR, friendlyError(t))
        }
    }

    private fun handleMessage(message: JSONObject) {
        if (message.optString("method") == "event") {
            val params = message.optJSONObject("params") ?: return
            val sessionId = params.optString("session_id")
            val seq = params.optLong("seq", -1)
            if (sessionId.isNotBlank() && seq >= 0) lastSeen[sessionId] = seq
            when (params.optString("type")) {
                "gateway.ready" -> {
                    val payload = params.optJSONObject("payload")
                    val epoch = payload?.optString("replay_epoch")?.ifBlank { null }
                    val changed = replayEpoch != null && epoch != null && replayEpoch != epoch
                    replayEpoch = epoch
                    listener?.onReady(epoch)
                    if (changed) lastSeen.clear()
                    activeSessionId?.let(::requestReplay)
                }
                else -> listener?.onEvent(params)
            }
            return
        }
        if (!message.has("id")) return
        val id = message.optLong("id", -1)
        val method = pending.remove(id) ?: "unknown"
        val errorJson = message.optJSONObject("error")
        if (errorJson != null) {
            listener?.onRpcError(
                method,
                GatewayError(
                    code = errorJson.optInt("code"),
                    message = errorJson.optString("message").ifBlank { "Gateway request failed" }.take(220),
                    retryable = errorJson.optInt("code") in setOf(-32000, -32001, 401, 4401),
                ),
            )
            return
        }
        val result = message.optJSONObject("result") ?: JSONObject()
        when (method) {
            "session.list" -> listener?.onSessions(parseSessions(result.optJSONArray("sessions") ?: JSONArray()))
            "session.create", "session.resume" -> {
                val sessionId = result.optionalString("session_id", "stored_session_id", "id")
                if (sessionId.isNotBlank()) listener?.onSessionReady(sessionId, parseMessages(result.optJSONArray("messages") ?: JSONArray()))
            }
            "image.attach_bytes", "file.attach" -> listener?.onAttachment(
                AttachmentReceipt(
                    name = result.optionalString("name", "filename").ifBlank { "Attachment" },
                    marker = result.optionalString("marker", "ref_text", "path", "ref_path"),
                    bytes = result.optLong("bytes", 0),
                    kind = if (method.startsWith("image")) "image" else "file",
                ),
            )
            "session.events.since" -> {
                val events = result.optJSONArray("events") ?: JSONArray()
                for (index in 0 until events.length()) {
                    events.optJSONObject(index)?.let { replay ->
                        val replayParams = JSONObject().put("replayed", true)
                        for (key in replay.keys()) replayParams.put(key, replay.get(key))
                        listener?.onEvent(replayParams)
                    }
                }
                if (result.optBoolean("truncated", false)) {
                    activeSessionId?.let { listener?.onReplayGap(it) }
                }
            }
        }
    }

    private fun parseSessions(array: JSONArray): List<SessionSummary> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optionalString("stored_session_id", "session_id", "id")
            if (id.isBlank()) continue
            add(
                SessionSummary(
                    id = id,
                    title = item.optionalString("title", "name").ifBlank { "Untitled session" },
                    preview = item.optionalString("preview", "last_message", "summary"),
                    messageCount = item.optInt("message_count", item.optInt("messageCount", 0)),
                    updatedLabel = item.optionalString("updated_at", "updated", "created_at"),
                ),
            )
        }
    }

    private fun parseMessages(array: JSONArray): List<ChatMessage> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val text = contentText(item.opt("content"))
            if (text.isBlank()) continue
            add(ChatMessage(item.optionalString("id", "message_id").ifBlank { "history-$index" }, item.optString("role", "assistant"), text))
        }
    }

    private fun contentText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("") { contentText(value.opt(it)) }
        is JSONObject -> value.optionalString("text", "value", "content")
        else -> ""
    }

    private fun friendlyError(error: Throwable): String = when {
        error is java.net.UnknownHostException -> "Host was not found"
        error is java.net.ConnectException -> "Could not reach the gateway"
        error is java.net.SocketTimeoutException -> "Gateway connection timed out"
        else -> error.message?.take(180) ?: "Gateway connection failed"
    }
}
