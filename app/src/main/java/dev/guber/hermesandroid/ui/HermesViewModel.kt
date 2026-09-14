package dev.guber.hermesandroid.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.guber.hermesandroid.data.ApprovalRequest
import dev.guber.hermesandroid.data.AttachmentPolicy
import dev.guber.hermesandroid.data.AttachmentReceipt
import dev.guber.hermesandroid.data.AuthApi
import dev.guber.hermesandroid.data.AuthSession
import dev.guber.hermesandroid.data.ChatMessage
import dev.guber.hermesandroid.data.ConnectionStatus
import dev.guber.hermesandroid.data.GatewayClient
import dev.guber.hermesandroid.data.GatewayEndpoint
import dev.guber.hermesandroid.data.GatewayError
import dev.guber.hermesandroid.data.NativePkceLogin
import dev.guber.hermesandroid.data.SecureCredentialStore
import dev.guber.hermesandroid.data.SessionSummary
import dev.guber.hermesandroid.data.SessionIdentity
import dev.guber.hermesandroid.data.StoredConnection
import dev.guber.hermesandroid.data.ToolActivity
import dev.guber.hermesandroid.data.GatewayModel
import dev.guber.hermesandroid.data.ReplyTracker
import dev.guber.hermesandroid.data.QueuedPrompt
import dev.guber.hermesandroid.data.encodeQueue
import dev.guber.hermesandroid.data.decodeQueue
import dev.guber.hermesandroid.ReplyNotifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID


data class HermesUiState(
    val endpointText: String = "",
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val statusText: String = "Connect to a Hermes Gateway",
    val signedIn: Boolean = false,
    val sessions: List<SessionSummary> = emptyList(),
    val pinnedSessions: Set<String> = emptySet(),
    val activeSessionId: String? = null,
    val activeRuntimeSessionId: String? = null,
    val activeTitle: String = "New conversation",
    val messages: List<ChatMessage> = emptyList(),
    val tools: List<ToolActivity> = emptyList(),
    val approvals: List<ApprovalRequest> = emptyList(),
    val attachments: List<AttachmentReceipt> = emptyList(),
    val draft: String = "",
    val isSending: Boolean = false,
    val sendingFollowUp: Boolean = false,
    val defaultQueue: Boolean = true,
    val queuedPrompts: List<QueuedPrompt> = emptyList(),
    val sendingQueuedId: String? = null,
    val loginInProgress: Boolean = false,
    val models: List<GatewayModel> = emptyList(),
    val currentModel: String = "",
    val currentProvider: String = "",
    val loadingModels: Boolean = false,
    val changingModel: Boolean = false,
    val modelConfirmation: String? = null,
    val modelError: String? = null,
)

internal fun visibleSessions(sessions: List<SessionSummary>, pins: Set<String>, query: String): List<SessionSummary> =
    sessions.filter { it.title.contains(query.trim(), ignoreCase = true) || it.preview.contains(query.trim(), ignoreCase = true) }
        .sortedByDescending { it.id in pins }

internal fun insertSubmittedMessage(messages: List<ChatMessage>, afterId: String?, text: String): List<ChatMessage> =
    messages.toMutableList().apply {
        val index = if (afterId == null) 0 else indexOfLast { it.id == afterId }.let { if (it < 0) size else it + 1 }
        add(index, ChatMessage("local-${UUID.randomUUID()}", "user", text))
    }

internal fun HermesUiState.finishTurn(statusText: String = this.statusText): HermesUiState =
    copy(
        isSending = false,
        statusText = statusText,
        messages = messages.map { it.copy(isStreaming = false) },
        tools = tools.map { it.copy(complete = true) },
    )

class HermesViewModel(application: Application) : AndroidViewModel(application), GatewayClient.Listener {
    private val store = SecureCredentialStore(application)
    private val authApi = AuthApi()
    private val gateway = GatewayClient(authApi)
    private val pkce = NativePkceLogin(application, authApi)
    private var connection: StoredConnection? = store.load()
    private val chatPreferences = application.getSharedPreferences("chat_preferences", android.content.Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(HermesUiState(endpointText = connection?.endpoint?.origin?.toString().orEmpty()))
    val state: StateFlow<HermesUiState> = _state.asStateFlow()
    private var pendingPrompt: String? = null
    private data class FollowUp(val text: String, val session: SessionIdentity, val title: String, val queued: Boolean, val queueId: String? = null, val afterMessageId: String? = null)
    private var pendingFollowUp: FollowUp? = null
    private val idleSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var refreshInProgress = false
    private val replies = ReplyTracker()
    private val notifications = ReplyNotifications(application)
    private var pendingModel: GatewayModel? = null
    private var pendingOpenSession: String? = null
    private var manuallyDisconnected = false
    val hasPendingReply: Boolean get() = pendingPrompt != null || pendingFollowUp != null || replies.isWaiting ||
        _state.value.queuedPrompts.any { it.runtimeId != null && it.error == null }

    init {
        _state.update { it.copy(defaultQueue = chatPreferences.getBoolean("default_queue", true)) }
        gateway.listener = this
        if (connection != null) {
            _state.update { it.copy(signedIn = true, statusText = "Ready to connect") }
            connectSaved()
        }
    }

    fun setEndpoint(value: String) = _state.update { it.copy(endpointText = value) }

    fun reportError(message: String) = showError(message)

    fun login() {
        val endpoint = GatewayEndpoint.parse(_state.value.endpointText).getOrElse {
            showError(it.message ?: "Enter a valid server URL")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loginInProgress = true, statusText = "Opening secure sign-in…") }
            pkce.start(endpoint).fold(
                onSuccess = { auth ->
                    val saved = StoredConnection(endpoint, auth)
                    store.save(saved)
                    connection = saved
                    _state.update { it.copy(endpointText = endpoint.origin.toString(), signedIn = true, loginInProgress = false, statusText = "Signed in - opening gateway…") }
                    connectSaved()
                },
                onFailure = { error ->
                    _state.update { it.copy(loginInProgress = false) }
                    showError(error.message ?: "Sign-in was not completed")
                },
            )
        }
    }

    fun connectSaved() {
        manuallyDisconnected = false
        val saved = connection ?: store.load()
        if (saved == null) {
            showError("Sign in first to create a secure gateway session")
            return
        }
        connection = saved
        _state.update { it.copy(queuedPrompts = decodeQueue(chatPreferences.getString("queue:${saved.endpoint.origin}", "[]") ?: "[]")) }
        _state.update { it.copy(pinnedSessions = chatPreferences.getStringSet("pins:${saved.endpoint.origin}", emptySet()).orEmpty().toSet()) }
        viewModelScope.launch {
            var auth = saved.auth
            if (auth.isExpired(System.currentTimeMillis() / 1000) && auth.refreshToken.isNotBlank()) {
                auth = authApi.refresh(saved.endpoint, auth.refreshToken, auth.provider).getOrElse {
                    showError("Session expired - sign in again")
                    return@launch
                }
                connection = saved.copy(auth = auth)
                store.save(connection!!)
            }
            gateway.connect(saved.endpoint, auth)
        }
    }

    fun disconnect() {
        manuallyDisconnected = true
        cancelBackgroundWait()
        gateway.close()
        _state.update { it.finishTurn("Disconnected").copy(status = ConnectionStatus.DISCONNECTED) }
    }

    fun signOut() {
        manuallyDisconnected = true
        cancelBackgroundWait()
        pendingOpenSession = null
        gateway.setActiveSession(null)
        gateway.close()
        store.clear()
        connection = null
        _state.value = HermesUiState(endpointText = _state.value.endpointText, statusText = "Connect to a Hermes Gateway", defaultQueue = chatPreferences.getBoolean("default_queue", true))
    }

    fun refreshSessions() = gateway.requestSessions()

    fun togglePin(session: SessionSummary) {
        val origin = connection?.endpoint?.origin ?: return
        val pins = _state.value.pinnedSessions.toMutableSet()
        if (!pins.add(session.id)) pins.remove(session.id)
        chatPreferences.edit().putStringSet("pins:$origin", pins).apply()
        _state.update { it.copy(pinnedSessions = pins) }
    }

    fun openSession(sessionId: String) {
        if (sessionId.isBlank()) return
        if (_state.value.status == ConnectionStatus.CONNECTED) gateway.resumeSession(sessionId)
        else {
            pendingOpenSession = sessionId
            if (_state.value.signedIn && _state.value.status != ConnectionStatus.CONNECTING) connectSaved()
        }
    }

    fun onForeground() {
        if (_state.value.signedIn && !manuallyDisconnected && _state.value.status in setOf(ConnectionStatus.ERROR, ConnectionStatus.DISCONNECTED)) connectSaved()
    }

    fun loadModels() {
        if (_state.value.status != ConnectionStatus.CONNECTED) return
        _state.update { it.copy(loadingModels = true, modelError = null) }
        val runtime = _state.value.activeRuntimeSessionId
        if (runtime == null) gateway.createSession() else gateway.requestModels(runtime)
    }

    fun selectModel(model: GatewayModel, confirmed: Boolean = false) {
        val runtime = _state.value.activeRuntimeSessionId ?: return
        if (_state.value.isSending || !model.available) return
        pendingModel = model
        _state.update { it.copy(changingModel = true, modelConfirmation = null, modelError = null) }
        gateway.selectModel(runtime, model, confirmed)
    }

    fun confirmModel() { pendingModel?.let { selectModel(it, true) } }
    fun cancelModelConfirmation() {
        pendingModel = null
        _state.update { it.copy(modelConfirmation = null, changingModel = false) }
    }

    override fun onModels(models: List<GatewayModel>, currentModel: String, currentProvider: String) {
        _state.update { it.copy(models = models, currentModel = currentModel, currentProvider = currentProvider, loadingModels = false) }
    }

    override fun onModelChanged(model: String, confirmation: String?) {
        if (confirmation != null) {
            _state.update { it.copy(modelConfirmation = confirmation, changingModel = false) }
            return
        }
        _state.update { it.copy(currentModel = model, currentProvider = pendingModel?.provider ?: it.currentProvider,
            changingModel = false, statusText = "Model changed to $model") }
        pendingModel = null
        gateway.requestModels(_state.value.activeRuntimeSessionId)
    }

    override fun onSessionModel(model: String, provider: String) {
        _state.update { it.copy(currentModel = model.ifBlank { it.currentModel }, currentProvider = provider.ifBlank { it.currentProvider }) }
    }

    fun cancelBackgroundWait() {
        pendingFollowUp?.queueId?.let { markQueueError(it, "Delivery not confirmed. Check chat before retrying.") }
        pendingFollowUp = null
        idleSessions.clear()
        _state.update { it.copy(sendingFollowUp = false, sendingQueuedId = null, queuedPrompts = it.queuedPrompts.map { item -> item.copy(runtimeId = null) }) }
        saveQueue()
        pendingPrompt?.let { text -> _state.update { it.copy(draft = text) } }
        pendingPrompt = null
        replies.cancel()
        notifications.stopWaiting()
    }

    private fun startBackgroundWait() {
        runCatching { notifications.startWaiting() }.onFailure {
            _state.update { it.copy(statusText = "Keep Hermes open while waiting for this response") }
        }
    }

    fun newSession() {
        if (_state.value.status != ConnectionStatus.CONNECTED) {
            showError("Connect to the gateway before starting a session")
            return
        }
        pendingPrompt = null
        gateway.createSession()
    }

    fun resumeSession(session: SessionSummary) {
        if (_state.value.status != ConnectionStatus.CONNECTED) return
        _state.update { it.copy(activeTitle = session.title, statusText = "Resuming ${session.title}…") }
        gateway.resumeSession(session.id)
    }

    fun updateDraft(value: String) = _state.update { it.copy(draft = value) }

    fun setDefaultQueue(queued: Boolean) {
        chatPreferences.edit().putBoolean("default_queue", queued).apply()
        _state.update { it.copy(defaultQueue = queued) }
    }

    fun sendFollowUp(queued: Boolean) {
        val current = _state.value
        if (current.sendingFollowUp || current.changingModel || current.status != ConnectionStatus.CONNECTED) return
        val text = current.draft.trim()
        val runtime = current.activeRuntimeSessionId ?: return
        if (text.isBlank()) return
        if (queued) {
            val item = QueuedPrompt(UUID.randomUUID().toString(), current.activeSessionId ?: runtime, text, current.activeTitle, runtime)
            _state.update { it.copy(draft = "", queuedPrompts = it.queuedPrompts + item) }
            saveQueue()
            startBackgroundWait()
            drainQueue()
            return
        }
        dispatchFollowUp(FollowUp(text, SessionIdentity(current.activeSessionId ?: runtime, runtime), current.activeTitle, queued))
    }

    private fun dispatchFollowUp(sent: FollowUp) {
        val runtime = sent.session.runtimeSessionId ?: return
        pendingFollowUp = sent.copy(afterMessageId = _state.value.messages.lastOrNull()?.id)
        if (sent.queued) idleSessions.remove(runtime)
        _state.update { it.copy(sendingFollowUp = true, sendingQueuedId = sent.queueId) }
        startBackgroundWait()
        gateway.followUp(runtime, sent.text, sent.queued)
    }

    private fun saveQueue() {
        val origin = connection?.endpoint?.origin ?: return
        chatPreferences.edit().putString("queue:$origin", encodeQueue(_state.value.queuedPrompts)).apply()
    }

    private fun markQueueError(id: String, error: String) {
        _state.update { it.copy(queuedPrompts = it.queuedPrompts.map { item -> if (item.id == id) item.copy(error = error) else item }) }
        saveQueue()
    }

    fun cancelQueued(id: String) {
        if (pendingFollowUp?.queueId == id) return
        _state.update { it.copy(queuedPrompts = it.queuedPrompts.filterNot { item -> item.id == id }) }
        saveQueue()
        if (!hasPendingReply) notifications.stopWaiting()
    }

    fun sendQueuedNow(id: String) {
        if (pendingFollowUp != null || _state.value.status != ConnectionStatus.CONNECTED) return
        val item = _state.value.queuedPrompts.firstOrNull { it.id == id } ?: return
        val runtime = item.runtimeId ?: return
        val idle = runtime in idleSessions || (runtime == _state.value.activeRuntimeSessionId && !_state.value.isSending)
        dispatchFollowUp(FollowUp(item.text, SessionIdentity(item.sessionId, runtime), item.title, queued = idle, queueId = id))
    }

    private fun drainQueue() = viewModelScope.launch {
        if (pendingFollowUp != null || _state.value.status != ConnectionStatus.CONNECTED) return@launch
        val item = _state.value.queuedPrompts.firstOrNull { it.runtimeId != null && it.runtimeId in idleSessions && it.error == null } ?: return@launch
        dispatchFollowUp(FollowUp(item.text, SessionIdentity(item.sessionId, item.runtimeId), item.title, true, item.id))
    }

    override fun onFollowUpResult(method: String, result: JSONObject) {
        val sent = pendingFollowUp ?: return
        pendingFollowUp = null
        val accepted = result.optString("status") in setOf("queued", "streaming")
        sent.queueId?.let { id ->
            if (accepted) {
                _state.update { it.copy(queuedPrompts = it.queuedPrompts.filterNot { item -> item.id == id }) }
                saveQueue()
            } else markQueueError(id, "Not sent. The agent did not accept the message.")
        }
        if (accepted && sent.queued) replies.begin(sent.session, sent.title, append = result.optString("status") == "queued")
        _state.update { current ->
            val sameSession = current.activeRuntimeSessionId == sent.session.runtimeSessionId
            current.copy(sendingFollowUp = false, sendingQueuedId = null,
                isSending = if (accepted && sameSession) true else current.isSending,
                draft = if (sent.queueId == null && accepted && sameSession && current.draft.trim() == sent.text) "" else current.draft,
                statusText = if (accepted) (if (sent.queued) "Message ${result.optString("status")}" else "Steer accepted") else "${if (sent.queued) "Queue" else "Steer"} rejected; message kept",
                messages = if (accepted && sameSession && sent.queued) insertSubmittedMessage(current.messages, sent.afterMessageId, sent.text) else current.messages)
        }
        if (!hasPendingReply) notifications.stopWaiting()
        drainQueue()
    }

    fun submitPrompt() {
        if (_state.value.sendingFollowUp || _state.value.changingModel) return
        if (_state.value.activeRuntimeSessionId != null && (_state.value.isSending || _state.value.defaultQueue)) {
            sendFollowUp(_state.value.defaultQueue); return
        }
        val text = _state.value.draft.trim()
        if (text.isBlank()) return
        if (_state.value.status != ConnectionStatus.CONNECTED) {
            showError("Connect to the gateway before sending a message")
            return
        }
        val runtimeSessionId = _state.value.activeRuntimeSessionId
        if (runtimeSessionId == null) {
            pendingPrompt = text
            _state.update { it.copy(draft = "", isSending = true, statusText = "Creating a session…") }
            startBackgroundWait()
            gateway.createSession()
            return
        }
        _state.update {
            it.copy(
                draft = "",
                isSending = true,
                messages = it.messages + ChatMessage("local-${UUID.randomUUID()}", "user", text),
            )
        }
        replies.begin(SessionIdentity(_state.value.activeSessionId ?: runtimeSessionId, runtimeSessionId), _state.value.activeTitle)
        startBackgroundWait()
        gateway.submitPrompt(runtimeSessionId, text)
    }

    fun stopStreaming() {
        if (!_state.value.isSending) return
        if (gateway.interruptActiveSession()) {
            _state.update { it.copy(statusText = "Stopping the current response…") }
        } else {
            showError("No active gateway session is available to stop")
        }
    }

    fun chooseApproval(request: ApprovalRequest, choice: String) {
        _state.update { it.copy(approvals = it.approvals.filterNot { item -> item.requestId == request.requestId }) }
        gateway.respondApproval(request.requestId, choice)
    }

    fun attach(name: String, mimeType: String, bytes: ByteArray) {
        val runtimeSessionId = _state.value.activeRuntimeSessionId
        if (runtimeSessionId == null) {
            showError("Start or resume a session before attaching a file")
            return
        }
        if (AttachmentPolicy.validate(bytes.size.toLong()).isFailure) {
            showError("Attachments must be between 1 byte and 25 MiB")
            return
        }
        _state.update { it.copy(statusText = "Uploading $name…") }
        if (mimeType.startsWith("image/", ignoreCase = true)) {
            gateway.attachImage(runtimeSessionId, bytes, name, name.substringAfterLast('.', "").ifBlank { null })
        } else {
            gateway.attachFile(runtimeSessionId, bytes, name, mimeType.ifBlank { "application/octet-stream" })
        }
    }

    override fun onStatus(status: ConnectionStatus, detail: String?) {
        if (status != ConnectionStatus.CONNECTED) {
            pendingFollowUp?.queueId?.let { markQueueError(it, "Delivery not confirmed. Check chat before retrying.") }
            pendingFollowUp = null
            idleSessions.clear()
            _state.update { it.copy(sendingFollowUp = false, sendingQueuedId = null, queuedPrompts = it.queuedPrompts.map { item -> item.copy(runtimeId = null) }) }
            if (!hasPendingReply) notifications.stopWaiting()
        }
        if (status == ConnectionStatus.ERROR) cancelBackgroundWait()
        _state.update {
            it.copy(
                status = status,
                statusText = detail ?: when (status) {
                    ConnectionStatus.CONNECTED -> "Connected"
                    ConnectionStatus.CONNECTING -> "Connecting…"
                    ConnectionStatus.DISCONNECTED -> "Disconnected"
                    ConnectionStatus.ERROR -> "Connection error"
                },
                activeRuntimeSessionId = if (status == ConnectionStatus.CONNECTED) it.activeRuntimeSessionId else null,
                isSending = if (status == ConnectionStatus.ERROR) false else it.isSending,
            )
        }
    }

    override fun onReady(replayEpoch: String?) {
        _state.update { it.copy(statusText = "Connected - syncing sessions") }
    }

    override fun onSessions(sessions: List<SessionSummary>) {
        _state.update { it.copy(sessions = sessions, statusText = if (it.activeSessionId == null) "Connected" else it.statusText) }
        pendingOpenSession?.let { id -> pendingOpenSession = null; gateway.resumeSession(id) }
    }

    override fun onSessionReady(session: SessionIdentity, messages: List<ChatMessage>) {
        replies.remap(session)
        val queued = pendingPrompt
        pendingPrompt = null
        _state.update {
            it.copy(
                activeSessionId = session.storedSessionId,
                activeRuntimeSessionId = session.runtimeSessionId,
                queuedPrompts = it.queuedPrompts.map { item -> if (item.sessionId == session.storedSessionId) item.copy(runtimeId = session.runtimeSessionId) else item },
                messages = messages,
                tools = emptyList(),
                approvals = emptyList(),
                attachments = emptyList(),
                isSending = queued != null,
                activeTitle = it.sessions.firstOrNull { item -> item.id == session.storedSessionId }?.title ?: "New conversation",
                statusText = "Connected",
            )
        }
        if (!queued.isNullOrBlank() && !session.runtimeSessionId.isNullOrBlank()) {
            replies.begin(session, _state.value.activeTitle)
            _state.update { it.copy(messages = it.messages + ChatMessage("local-${UUID.randomUUID()}", "user", queued)) }
            gateway.submitPrompt(session.runtimeSessionId, queued)
        }
        if (_state.value.loadingModels) gateway.requestModels(session.runtimeSessionId)
    }

    override fun onSessionActivity(session: SessionIdentity, running: Boolean) {
        val runtime = session.runtimeSessionId ?: return
        if (running) idleSessions.remove(runtime)
        else if (!_state.value.isSending) idleSessions.add(runtime)
        _state.update { if (it.activeRuntimeSessionId == runtime && running) it.copy(isSending = true) else it }
        drainQueue()
    }

    override fun onEvent(params: JSONObject) {
        replies.receive(params)?.let { reply ->
            notifications.show(reply)
            if (!hasPendingReply) notifications.stopWaiting()
        }
        val type = params.optString("type")
        val payload = params.optJSONObject("payload") ?: JSONObject()
        val sessionId = params.optString("session_id")
        if (type in setOf("status.busy", "message.start")) idleSessions.remove(sessionId)
        val completed = type in setOf("status.idle", "message.complete") && !params.optBoolean("replayed")
        if (completed) {
            idleSessions.add(sessionId)
        }
        if (sessionId.isNotBlank() && sessionId != _state.value.activeRuntimeSessionId) {
            if (completed) drainQueue()
            return
        }
        when (type) {
            "session.info" -> onSessionModel(payload.optionalString("model"), payload.optionalString("provider"))
            "message.start" -> {
                val role = payload.optString("role", "assistant")
                _state.update { it.copy(isSending = true, messages = it.messages + ChatMessage("stream-${UUID.randomUUID()}", role, "", true)) }
            }
            "message.delta", "reasoning.delta", "thinking.delta", "message.interim" -> {
                val delta = payload.optionalString("delta", "text", "content")
                if (delta.isBlank()) return
                if (type == "reasoning.delta" || type == "thinking.delta") {
                    updateTool("reasoning", "Thinking", delta, false)
                } else {
                    appendAssistantDelta(delta)
                }
            }
            "message.complete" -> {
                val text = payload.optionalString("text", "content")
                _state.update { current ->
                    val index = current.messages.indexOfLast { it.role == "assistant" && it.isStreaming }
                    (if (index < 0) {
                        if (text.isBlank()) current.copy(isSending = false)
                        else current.copy(
                            isSending = false,
                            messages = current.messages + ChatMessage("complete-${UUID.randomUUID()}", "assistant", text),
                        )
                    }
                    else current.copy(
                        isSending = false,
                        messages = current.messages.toMutableList().also { list ->
                            val previous = list[index]
                            list[index] = previous.copy(text = if (text.isBlank()) previous.text else text, isStreaming = false)
                        },
                    )).finishTurn("Connected")
                }
            }
            "tool.start", "tool.generating", "tool.complete" -> {
                val id = payload.optionalString("tool_call_id", "id").ifBlank { "tool-${type}" }
                val name = payload.optionalString("name", "tool", "title").ifBlank { "Server tool" }
                val detail = payload.optionalString("detail", "text", "output", "command")
                updateTool(id, name, detail, type == "tool.complete")
            }
            "approval.request" -> {
                val choices = payload.optJSONArray("choices")?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).ifBlank { null } } }
                    ?: listOf("once", "deny")
                val request = ApprovalRequest(
                    requestId = payload.optionalString("request_id", "id"),
                    command = payload.optionalString("command", "redacted_command").ifBlank { "Server requested approval" },
                    choices = choices,
                )
                if (request.requestId.isNotBlank()) _state.update { it.copy(approvals = (it.approvals + request).distinctBy { item -> item.requestId }) }
            }
            "status.busy", "status.idle", "notification.show" -> {
                val detail = payload.optionalString("message", "text", "status").ifBlank { type.removePrefix("status.") }
                _state.update { if (type == "status.idle") it.finishTurn(detail) else it.copy(statusText = detail, isSending = if (type == "status.busy") true else it.isSending) }
            }
            else -> if (type.startsWith("status.")) {
                val detail = payload.optionalString("message", "text", "status").ifBlank { type.removePrefix("status.") }
                _state.update { it.copy(statusText = detail) }
            }
        }
        if (completed) drainQueue()
    }

    override fun onAttachment(receipt: AttachmentReceipt) {
        _state.update { it.copy(attachments = (it.attachments + receipt).takeLast(8), statusText = "Attached ${receipt.name}") }
    }

    override fun onPendingApprovals(approvals: List<ApprovalRequest>) {
        if (approvals.isEmpty()) return
        _state.update { current ->
            val merged = (current.approvals + approvals).distinctBy { it.requestId }
            current.copy(approvals = merged, statusText = "Approval required")
        }
    }

    override fun onReplayGap(sessionId: String) {
        _state.update { it.copy(statusText = "Replay window was truncated - reloading session history…") }
        gateway.resumeActiveSession()
    }

    override fun onSessionInterrupted() {
        replies.cancel(_state.value.activeRuntimeSessionId)
        if (!hasPendingReply) notifications.stopWaiting()
        _state.update { it.finishTurn("Response stopped") }
    }

    override fun onRpcError(method: String, error: GatewayError) {
        if (method in setOf("prompt.queue", "session.steer")) {
            pendingFollowUp?.queueId?.let { markQueueError(it, error.message) }
            pendingFollowUp = null
            _state.update { it.copy(sendingFollowUp = false, sendingQueuedId = null, statusText = "${error.message}; message kept") }
            if (!hasPendingReply) notifications.stopWaiting()
            return
        }
        if (error.code == 401 || error.code == 4401) {
            refreshAndReconnect()
        }
        if (method in setOf("prompt.submit", "session.create")) cancelBackgroundWait()
        _state.update { it.copy(statusText = "$method: ${error.message}",
            isSending = if (method in setOf("prompt.submit", "session.create")) false else it.isSending,
            loadingModels = if (method in setOf("model.options", "session.create")) false else it.loadingModels,
            changingModel = if (method == "config.set") false else it.changingModel,
            modelError = if (method in setOf("model.options", "config.set")) error.message else it.modelError) }
    }

    private fun appendAssistantDelta(delta: String) {
        _state.update { current ->
            val index = current.messages.indexOfLast { it.role == "assistant" && it.isStreaming }
            if (index < 0) current.copy(messages = current.messages + ChatMessage("stream-${UUID.randomUUID()}", "assistant", delta, true))
            else current.copy(messages = current.messages.toMutableList().also { list -> list[index] = list[index].copy(text = list[index].text + delta) })
        }
    }

    private fun updateTool(id: String, name: String, detail: String, complete: Boolean) {
        _state.update { current ->
            val existing = current.tools.indexOfFirst { it.id == id }
            val updated = ToolActivity(id, name, detail, complete)
            if (existing < 0) current.copy(tools = current.tools + updated)
            else current.copy(tools = current.tools.toMutableList().also { list -> list[existing] = updated })
        }
    }

    private fun refreshAndReconnect() {
        if (refreshInProgress) return
        val saved = connection ?: return
        if (saved.auth.refreshToken.isBlank()) {
            cancelBackgroundWait()
            showError("Session expired - sign in again")
            return
        }
        refreshInProgress = true
        viewModelScope.launch {
            authApi.refresh(saved.endpoint, saved.auth.refreshToken, saved.auth.provider).onSuccess { auth ->
                connection = saved.copy(auth = auth)
                store.save(connection!!)
                gateway.connect(saved.endpoint, auth)
            }.onFailure {
                cancelBackgroundWait()
                showError("Session expired - sign in again")
            }
            refreshInProgress = false
        }
    }

    private fun showError(message: String) = _state.update { it.copy(status = ConnectionStatus.ERROR, statusText = message.take(220)) }

    override fun onCleared() {
        gateway.shutdown()
        super.onCleared()
    }
}

private fun String?.toStringOrEmpty(): String = this?.takeIf { it != "null" }.orEmpty()

private fun JSONObject.optionalString(vararg keys: String): String = keys
    .asSequence()
    .map { optString(it) }
    .firstOrNull { it.isNotBlank() }
    .orEmpty()
