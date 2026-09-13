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
import dev.guber.hermesandroid.data.StoredConnection
import dev.guber.hermesandroid.data.ToolActivity
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
    val activeSessionId: String? = null,
    val activeTitle: String = "New conversation",
    val messages: List<ChatMessage> = emptyList(),
    val tools: List<ToolActivity> = emptyList(),
    val approvals: List<ApprovalRequest> = emptyList(),
    val attachments: List<AttachmentReceipt> = emptyList(),
    val draft: String = "",
    val isSending: Boolean = false,
    val loginInProgress: Boolean = false,
)

class HermesViewModel(application: Application) : AndroidViewModel(application), GatewayClient.Listener {
    private val store = SecureCredentialStore(application)
    private val authApi = AuthApi()
    private val gateway = GatewayClient(authApi)
    private val pkce = NativePkceLogin(application, authApi)
    private var connection: StoredConnection? = store.load()
    private val _state = MutableStateFlow(HermesUiState(endpointText = connection?.endpoint?.origin?.toString().orEmpty()))
    val state: StateFlow<HermesUiState> = _state.asStateFlow()
    private var pendingPrompt: String? = null
    private var refreshInProgress = false

    init {
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
                    _state.update { it.copy(signedIn = true, loginInProgress = false, statusText = "Signed in - opening gateway…") }
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
        val saved = connection ?: store.load()
        if (saved == null) {
            showError("Sign in first to create a secure gateway session")
            return
        }
        connection = saved
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
        gateway.close()
        _state.update { it.copy(status = ConnectionStatus.DISCONNECTED, statusText = "Disconnected") }
    }

    fun signOut() {
        gateway.close()
        store.clear()
        connection = null
        _state.value = HermesUiState(endpointText = _state.value.endpointText, statusText = "Connect to a Hermes Gateway")
    }

    fun refreshSessions() = gateway.requestSessions()

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

    fun submitPrompt() {
        val text = _state.value.draft.trim()
        if (text.isBlank()) return
        if (_state.value.status != ConnectionStatus.CONNECTED) {
            showError("Connect to the gateway before sending a message")
            return
        }
        val sessionId = _state.value.activeSessionId
        if (sessionId == null) {
            pendingPrompt = text
            _state.update { it.copy(draft = "", statusText = "Creating a session…") }
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
        gateway.submitPrompt(sessionId, text)
    }

    fun chooseApproval(request: ApprovalRequest, choice: String) {
        _state.update { it.copy(approvals = it.approvals.filterNot { item -> item.requestId == request.requestId }) }
        gateway.respondApproval(request.requestId, choice)
    }

    fun attach(name: String, mimeType: String, bytes: ByteArray) {
        val sessionId = _state.value.activeSessionId
        if (sessionId == null) {
            showError("Start or resume a session before attaching a file")
            return
        }
        if (AttachmentPolicy.validate(bytes.size.toLong()).isFailure) {
            showError("Attachments must be between 1 byte and 25 MiB")
            return
        }
        _state.update { it.copy(statusText = "Uploading $name…") }
        if (mimeType.startsWith("image/", ignoreCase = true)) {
            gateway.attachImage(sessionId, bytes, name, name.substringAfterLast('.', "").ifBlank { null })
        } else {
            gateway.attachFile(sessionId, bytes, name, mimeType.ifBlank { "application/octet-stream" })
        }
    }

    override fun onStatus(status: ConnectionStatus, detail: String?) {
        _state.update {
            it.copy(
                status = status,
                statusText = detail ?: when (status) {
                    ConnectionStatus.CONNECTED -> "Connected"
                    ConnectionStatus.CONNECTING -> "Connecting…"
                    ConnectionStatus.DISCONNECTED -> "Disconnected"
                    ConnectionStatus.ERROR -> "Connection error"
                },
                isSending = if (status == ConnectionStatus.ERROR) false else it.isSending,
            )
        }
    }

    override fun onReady(replayEpoch: String?) {
        _state.update { it.copy(statusText = "Connected - syncing sessions") }
        gateway.requestSessions()
    }

    override fun onSessions(sessions: List<SessionSummary>) {
        _state.update { it.copy(sessions = sessions, statusText = if (it.activeSessionId == null) "Connected" else it.statusText) }
    }

    override fun onSessionReady(sessionId: String, messages: List<ChatMessage>) {
        gateway.setActiveSession(sessionId)
        val queued = pendingPrompt
        pendingPrompt = null
        _state.update {
            it.copy(
                activeSessionId = sessionId,
                messages = messages,
                tools = emptyList(),
                approvals = emptyList(),
                isSending = queued != null,
                activeTitle = it.sessions.firstOrNull { session -> session.id == sessionId }?.title ?: "New conversation",
                statusText = "Connected",
            )
        }
        if (!queued.isNullOrBlank()) {
            _state.update { it.copy(messages = it.messages + ChatMessage("local-${UUID.randomUUID()}", "user", queued)) }
            gateway.submitPrompt(sessionId, queued)
        }
    }

    override fun onEvent(params: JSONObject) {
        val type = params.optString("type")
        val payload = params.optJSONObject("payload") ?: JSONObject()
        val sessionId = params.optString("session_id")
        if (sessionId.isNotBlank() && sessionId != _state.value.activeSessionId) return
        when (type) {
            "message.start" -> {
                val role = payload.optString("role", "assistant")
                _state.update { it.copy(messages = it.messages + ChatMessage("stream-${UUID.randomUUID()}", role, "", true)) }
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
                    if (index < 0) current.copy(isSending = false)
                    else current.copy(
                        isSending = false,
                        messages = current.messages.toMutableList().also { list ->
                            val previous = list[index]
                            list[index] = previous.copy(text = if (text.isBlank()) previous.text else text, isStreaming = false)
                        },
                    )
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
                _state.update { it.copy(statusText = detail) }
            }
            else -> if (type.startsWith("status.")) {
                val detail = payload.optionalString("message", "text", "status").ifBlank { type.removePrefix("status.") }
                _state.update { it.copy(statusText = detail) }
            }
        }
    }

    override fun onAttachment(receipt: AttachmentReceipt) {
        _state.update { it.copy(attachments = (it.attachments + receipt).takeLast(8), statusText = "Attached ${receipt.name}") }
    }

    override fun onReplayGap(sessionId: String) {
        _state.update { it.copy(statusText = "Replay window was truncated - reloading session history…") }
        gateway.resumeSession(sessionId)
    }

    override fun onRpcError(method: String, error: GatewayError) {
        if (error.code == 401 || error.code == 4401) {
            refreshAndReconnect()
        }
        _state.update { it.copy(statusText = "$method: ${error.message}", isSending = false) }
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
        if (saved.auth.refreshToken.isBlank()) return
        refreshInProgress = true
        viewModelScope.launch {
            authApi.refresh(saved.endpoint, saved.auth.refreshToken, saved.auth.provider).onSuccess { auth ->
                connection = saved.copy(auth = auth)
                store.save(connection!!)
                gateway.connect(saved.endpoint, auth)
            }.onFailure {
                showError("Session expired - sign in again")
            }
            refreshInProgress = false
        }
    }

    private fun showError(message: String) = _state.update { it.copy(status = ConnectionStatus.ERROR, statusText = message.take(220)) }

    override fun onCleared() {
        gateway.close()
        super.onCleared()
    }
}

private fun String?.toStringOrEmpty(): String = this?.takeIf { it != "null" }.orEmpty()

private fun JSONObject.optionalString(vararg keys: String): String = keys
    .asSequence()
    .map { optString(it) }
    .firstOrNull { it.isNotBlank() }
    .orEmpty()
