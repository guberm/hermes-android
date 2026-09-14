package dev.guber.hermesandroid.data

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

fun interface GatewayTicketProvider {
    suspend fun mint(endpoint: GatewayEndpoint, accessToken: String): Result<String>
}

/** Authenticated Hermes gateway transport with generation-safe reconnect and replay handling. */
class GatewayClient(
    private val authApi: AuthApi = AuthApi(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val ticketProvider: GatewayTicketProvider = GatewayTicketProvider { endpoint, accessToken ->
        authApi.mintWsTicket(endpoint, accessToken)
    },
    private val webSocketFactory: WebSocket.Factory = http,
) {
    interface Listener {
        fun onStatus(status: ConnectionStatus, detail: String? = null) {}
        fun onReady(replayEpoch: String?) {}
        fun onSessions(sessions: List<SessionSummary>) {}
        fun onSessionReady(session: SessionIdentity, messages: List<ChatMessage>) {}
        fun onEvent(params: JSONObject) {}
        fun onAttachment(receipt: AttachmentReceipt) {}
        fun onPendingApprovals(approvals: List<ApprovalRequest>) {}
        fun onReplayGap(sessionId: String) {}
        fun onSessionInterrupted() {}
        fun onRpcError(method: String, error: GatewayError) {}
        fun onModels(models: List<GatewayModel>, currentModel: String, currentProvider: String) {}
        fun onModelChanged(model: String, confirmation: String?) {}
        fun onSessionModel(model: String, provider: String) {}
    }

    companion object {
        private const val HEARTBEAT_PERIOD_SECONDS = 20L
        private const val WATCHDOG_PERIOD_SECONDS = 5L
        private const val READ_TIMEOUT_MILLIS = 60_000L
    }

    var listener: Listener? = null
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, String>()
    private val decoder = NewlineJsonRpcDecoder()
    private val sequenceGate = SequencedEventGate()
    private val generationGate = GatewayGenerationGate()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeat: ScheduledFuture<*>? = null
    private var readWatchdog: ScheduledFuture<*>? = null
    private var reconnect: ScheduledFuture<*>? = null
    private var socket: WebSocket? = null
    private var endpointForReconnect: GatewayEndpoint? = null
    private var authForReconnect: AuthSession? = null
    private var reconnectAttempt = 0
    private var lastReadMillis = System.currentTimeMillis()
    private var manuallyClosed = true
    private var activeSession: SessionIdentity? = null
    private var replayEpoch: String? = null
    @Volatile private var latestSessionRequest: Long? = null

    suspend fun connect(endpoint: GatewayEndpoint, auth: AuthSession): Result<Unit> = withContext(Dispatchers.IO) {
        val generation = synchronized(this@GatewayClient) {
            resetTransport()
            manuallyClosed = false
            endpointForReconnect = endpoint
            authForReconnect = auth
            reconnectAttempt = 0
            generationGate.begin()
        }
        listener?.onStatus(ConnectionStatus.CONNECTING, "Requesting a short-lived gateway ticket…")
        openTransport(endpoint, auth, generation)
    }

    fun close() {
        synchronized(this) { manuallyClosed = true }
        resetTransport()
        listener?.onStatus(ConnectionStatus.DISCONNECTED)
    }

    fun shutdown() {
        close()
        reconnectScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        scheduler.shutdownNow()
    }

    fun setActiveSession(session: SessionIdentity?) {
        activeSession = session
        session?.runtimeSessionId?.let {
            requestReplay(it)
            requestPendingApprovals(it)
        }
    }

    fun requestSessions(limit: Int = 50) {
        sendRpc("session.list", JSONObject().put("limit", limit))
    }

    fun requestModels(runtimeSessionId: String? = null) {
        sendRpc("model.options", JSONObject().apply {
            if (runtimeSessionId != null) put("session_id", runtimeSessionId)
            put("include_unconfigured", false)
        })
    }

    fun selectModel(runtimeSessionId: String, model: GatewayModel, confirmed: Boolean = false) {
        sendRpc("config.set", modelSelectionParams(runtimeSessionId, model, confirmed))
    }

    fun createSession(title: String? = null) {
        latestSessionRequest = sendRpc("session.create", sessionCreateParams(title))
    }

    /** The argument is always the durable stored ID, never a runtime session ID. */
    fun resumeSession(storedSessionId: String) {
        if (storedSessionId.isBlank()) return
        activeSession = SessionIdentity(storedSessionId)
        latestSessionRequest = sendRpc("session.resume", sessionResumeParams(storedSessionId))
    }

    fun resumeActiveSession() {
        activeSession?.storedSessionId?.let(::resumeSession)
    }

    fun submitPrompt(sessionId: String, text: String) {
        sendRpc("prompt.submit", promptSubmitParams(sessionId, text))
    }

    fun interruptActiveSession(): Boolean {
        val runtimeId = activeSession?.runtimeSessionId ?: return false
        return sendRpc("session.interrupt", sessionInterruptParams(runtimeId)) != null
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

    private fun requestReplay(runtimeSessionId: String) {
        if (socket == null || runtimeSessionId.isBlank()) return
        sendRpc(
            "session.events.since",
            JSONObject().put("session_id", runtimeSessionId).put("last_seen", sequenceGate.watermark(runtimeSessionId)),
        )
    }

    private fun requestPendingApprovals(runtimeSessionId: String) {
        if (socket == null || runtimeSessionId.isBlank()) return
        sendRpc("approval.pending", approvalPendingParams(runtimeSessionId))
    }

    private fun sendRpc(method: String, params: JSONObject = JSONObject()): Long? {
        val ws = synchronized(this) { socket }
        val id = nextId.getAndIncrement()
        if (ws == null) {
            listener?.onRpcError(method, GatewayError(message = "Gateway is offline; reconnect and try again", retryable = true))
            return null
        }
        pending[id] = method
        if (!ws.send(jsonRpcRequest(id, method, params))) {
            pending.remove(id)
            listener?.onRpcError(method, GatewayError(message = "Gateway socket rejected the request", retryable = true))
            return null
        }
        return id
    }

    private suspend fun openTransport(endpoint: GatewayEndpoint, auth: AuthSession, generation: Long): Result<Unit> {
        // Callers reserve the generation before scheduling work or awaiting a ticket.
        if (!isCurrent(generation, null)) return Result.failure(IllegalStateException("Obsolete gateway connection"))
        val ticketResult = ticketProvider.mint(endpoint, auth.accessToken)
        if (!isCurrent(generation, null)) {
            return Result.failure(IllegalStateException("Obsolete gateway connection"))
        }
        if (ticketResult.isFailure) {
            val error = ticketResult.exceptionOrNull() ?: IllegalStateException("Unable to mint a gateway ticket")
            if (error is GatewayHttpException && error.statusCode == 401) {
                listener?.onStatus(ConnectionStatus.CONNECTING, "Renewing sign-in…")
                listener?.onRpcError("authentication", GatewayError(code = 401, message = "Sign-in needs to be renewed"))
                return Result.failure(error)
            }
            listener?.onStatus(ConnectionStatus.ERROR, error.message ?: "Unable to mint a gateway ticket")
            scheduleReconnect()
            return Result.failure(error)
        }
        val requestUrl = endpoint.route("/api/ws").newBuilder()
            .addQueryParameter("ticket", ticketResult.getOrThrow())
            .build()
        val request = Request.Builder()
            // Cloudflare Tunnel reliably forwards the WSS upgrade when the short-lived
            // ticket is a query parameter; keep the public gateway URL and auth boundary intact.
            .url(requestUrl)
            .build()
        val webSocket = webSocketFactory.newWebSocket(request, socketListener(generation))
        synchronized(this) {
            if (generationGate.isCurrent(generation) && !manuallyClosed) {
                socket = webSocket
                lastReadMillis = System.currentTimeMillis()
            }
            else webSocket.close(1000, "Obsolete gateway generation")
        }
        scheduleTransportTimers(generation)
        return Result.success(Unit)
    }

    private fun resetTransport() {
        val oldSocket: WebSocket?
        synchronized(this) {
            reconnect?.cancel(false)
            reconnect = null
            heartbeat?.cancel(false)
            heartbeat = null
            readWatchdog?.cancel(false)
            readWatchdog = null
            generationGate.invalidate()
            oldSocket = socket
            socket = null
            pending.clear()
            decoder.reset()
            activeSession = activeSession?.copy(runtimeSessionId = null)
        }
        oldSocket?.close(1000, "Client closed")
    }

    private fun scheduleTransportTimers(generation: Long) {
        synchronized(this) {
            if (!isCurrent(generation, null) || manuallyClosed) return
            heartbeat?.cancel(false)
            readWatchdog?.cancel(false)
            heartbeat = scheduler.scheduleWithFixedDelay({
                if (isCurrent(generation, null)) sendRpc("gateway.ping")
            }, HEARTBEAT_PERIOD_SECONDS, HEARTBEAT_PERIOD_SECONDS, TimeUnit.SECONDS)
            readWatchdog = scheduler.scheduleWithFixedDelay({
                val expired = synchronized(this) {
                    isCurrent(generation, null) && readWatchdogExpired(
                        System.currentTimeMillis(),
                        lastReadMillis,
                        READ_TIMEOUT_MILLIS,
                    )
                }
                if (expired) handleTransportLoss(generation, "Gateway read watchdog expired")
            }, WATCHDOG_PERIOD_SECONDS, WATCHDOG_PERIOD_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun scheduleReconnect() {
        val delay: Long
        synchronized(this) {
            if (manuallyClosed || reconnect?.isDone == false) return
            val next = reconnectPolicy.delayForAttempt(reconnectAttempt)
            if (next == null) {
                listener?.onStatus(ConnectionStatus.ERROR, "Gateway reconnect attempts exhausted")
                return
            }
            reconnectAttempt += 1
            delay = next
            val generation = generationGate.begin()
            listener?.onStatus(ConnectionStatus.CONNECTING, "Gateway disconnected - reconnecting in ${delay / 1_000}s")
            reconnect = scheduler.schedule({
                val endpoint: GatewayEndpoint
                val auth: AuthSession
                synchronized(this) {
                    if (!isCurrent(generation, null) || manuallyClosed) return@schedule
                    reconnect = null
                    endpoint = endpointForReconnect ?: return@schedule
                    auth = authForReconnect ?: return@schedule
                }
                reconnectScope.launch {
                    openTransport(endpoint, auth, generation)
                }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun handleTransportLoss(generation: Long, detail: String) {
        val oldSocket: WebSocket?
        synchronized(this) {
            if (!isCurrent(generation, null) || manuallyClosed) return
            generationGate.invalidate()
            oldSocket = socket
            socket = null
            heartbeat?.cancel(false)
            heartbeat = null
            readWatchdog?.cancel(false)
            readWatchdog = null
            pending.clear()
            decoder.reset()
            activeSession = activeSession?.copy(runtimeSessionId = null)
        }
        oldSocket?.cancel()
        listener?.onStatus(ConnectionStatus.CONNECTING, detail)
        scheduleReconnect()
    }

    private fun isCurrent(generation: Long, webSocket: WebSocket?): Boolean = synchronized(this) {
        generationGate.isCurrent(generation) && (webSocket == null || socket == null || socket === webSocket)
    }

    private fun socketListener(generation: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(generation, webSocket)) return
            synchronized(this@GatewayClient) {
                socket = webSocket
                reconnectAttempt = 0
                lastReadMillis = System.currentTimeMillis()
            }
            listener?.onStatus(ConnectionStatus.CONNECTED, "Secure gateway channel open")
            sendRpc("gateway.ping")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(generation, webSocket)) return
            synchronized(this@GatewayClient) { lastReadMillis = System.currentTimeMillis() }
            receiveTextFrame(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(generation, webSocket)) return
            webSocket.close(code, null)
            listener?.onStatus(ConnectionStatus.DISCONNECTED, "Gateway closed the channel ($code)")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (isCurrent(generation, webSocket)) handleTransportLoss(generation, "Gateway closed the channel ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(generation, webSocket)) return
            handleTransportLoss(generation, friendlyError(t))
        }
    }

    /** Transport seam: every OkHttp text callback is decoded and dispatched through this method. */
    internal fun receiveTextFrame(text: String) {
        runCatching {
            decoder.feed(text).forEach(::handleMessage)
        }.onFailure {
            listener?.onRpcError("transport", GatewayError(message = "Gateway sent invalid JSON", retryable = true))
        }
    }

    private fun handleMessage(message: JSONObject) {
        if (message.optString("method") == "event") {
            val params = message.optJSONObject("params") ?: return
            val type = params.optString("type")
            if (type == "gateway.ready") {
                val payload = params.optJSONObject("payload")
                val epoch = payload?.optString("replay_epoch")?.ifBlank { null }
                val changed = replayEpoch != null && epoch != null && replayEpoch != epoch
                replayEpoch = epoch
                if (changed) sequenceGate.clear()
                listener?.onReady(epoch)
                requestSessions()
                activeSession?.storedSessionId?.let(::resumeSession)
                return
            }
            if (!sequenceGate.accept(params)) return
            listener?.onEvent(params)
            return
        }
        if (!message.has("id")) return
        val id = message.optLong("id", -1)
        val method = pending.remove(id) ?: "unknown"
        if (method == "session.create" || method == "session.resume") {
            if (id != latestSessionRequest) return
            latestSessionRequest = null
        }
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
                val fallback = if (method == "session.resume") activeSession?.storedSessionId else null
                val identity = result.sessionIdentity(fallback)
                if (identity != null) {
                    activeSession = identity
                    listener?.onSessionReady(identity, parseMessages(result.optJSONArray("messages") ?: JSONArray()))
                    result.optJSONObject("info")?.let { listener?.onSessionModel(it.optionalString("model"), it.optionalString("provider")) }
                    identity.runtimeSessionId?.let {
                        requestReplay(it)
                        requestPendingApprovals(it)
                    }
                }
            }
            "session.interrupt" -> listener?.onSessionInterrupted()
            "model.options" -> listener?.onModels(parseModelOptions(result), result.optionalString("model"), result.optionalString("provider"))
            "config.set" -> if (result.optString("key") == "model") {
                val confirmation = if (result.optBoolean("confirm_required"))
                    result.optionalString("confirm_message", "warning").ifBlank { "Confirm this model change?" } else null
                listener?.onModelChanged(result.optionalString("value"), confirmation)
            }
            "approval.pending" -> listener?.onPendingApprovals(parsePendingApprovals(result))
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
                        if (!sequenceGate.accept(replay)) return@let
                        val replayParams = JSONObject().put("replayed", true)
                        for (key in replay.keys()) replayParams.put(key, replay.get(key))
                        listener?.onEvent(replayParams)
                    }
                }
                if (result.optBoolean("truncated", false)) {
                    activeSession?.runtimeSessionId?.let { listener?.onReplayGap(it) }
                }
            }
        }
    }

    private fun parseSessions(array: JSONArray): List<SessionSummary> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val identity = item.sessionIdentity() ?: continue
            add(
                SessionSummary(
                    id = identity.storedSessionId,
                    title = item.optionalString("title", "name").ifBlank { "Untitled session" },
                    preview = item.optionalString("preview", "last_message", "summary"),
                    messageCount = item.optInt("message_count", item.optInt("messageCount", 0)),
                    updatedLabel = item.optionalString("updated_at", "updated", "created_at"),
                ),
            )
        }
    }

    private fun parsePendingApprovals(result: JSONObject): List<ApprovalRequest> {
        val value = result.opt("approvals") ?: result.opt("pending") ?: result.opt("approval")
        val objects = when (value) {
            is JSONObject -> listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
            else -> if (result.has("request_id") || result.has("approval_id")) listOf(result) else emptyList()
        }
        return objects.mapNotNull(::parseApproval).distinctBy { it.requestId }
    }

    private fun parseApproval(value: JSONObject): ApprovalRequest? {
        val requestId = value.optionalString("request_id", "approval_id", "id")
        if (requestId.isBlank()) return null
        val choices = value.optJSONArray("choices")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).ifBlank { null } }
        }?.ifEmpty { null } ?: listOf("once", "deny")
        return ApprovalRequest(
            requestId = requestId,
            command = value.optionalString("command", "redacted_command", "description").ifBlank { "Server requested approval" },
            choices = choices,
        )
    }

    private fun parseMessages(array: JSONArray): List<ChatMessage> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val text = contentText(item.opt("text")).ifBlank { contentText(item.opt("content")) }
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
