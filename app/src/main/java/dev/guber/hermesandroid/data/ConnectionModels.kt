package dev.guber.hermesandroid.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A validated server origin plus the routes used by the native client. */
class GatewayEndpoint private constructor(
    val origin: HttpUrl,
    val wsUrl: String,
) {
    fun route(path: String): HttpUrl {
        val base = origin.encodedPath.trimEnd('/')
        return origin.newBuilder().encodedPath("$base${if (path.startsWith('/')) path else "/$path"}").build()
    }

    companion object {
        fun parse(raw: String): Result<GatewayEndpoint> {
            val candidate = raw.trim().removeSuffix("/")
            if (candidate.isBlank()) return Result.failure(IllegalArgumentException("Server URL is required"))
            val url = candidate.toHttpUrlOrNull()
                ?: return Result.failure(IllegalArgumentException("Use an https:// server URL"))
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
                return Result.failure(IllegalArgumentException("Server URL must not include credentials or query parameters"))
            }
            if (url.scheme != "https" && url.scheme != "http") {
                return Result.failure(IllegalArgumentException("Server URL must use http or https"))
            }
            if (url.host.isBlank()) return Result.failure(IllegalArgumentException("Server host is required"))
            val wsScheme = if (url.scheme == "https") "wss" else "ws"
            val base = url.encodedPath.trimEnd('/')
            val ws = url.toString().replaceFirst("${url.scheme}://", "$wsScheme://") + "/api/ws"
            return Result.success(GatewayEndpoint(url, ws))
        }
    }
}

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val provider: String? = null,
) {
    fun isExpired(nowEpochSeconds: Long): Boolean = expiresAtEpochSeconds > 0 && expiresAtEpochSeconds <= nowEpochSeconds + 30
}

data class StoredConnection(
    val endpoint: GatewayEndpoint,
    val auth: AuthSession,
)

data class GatewayError(
    val code: Int? = null,
    val message: String,
    val retryable: Boolean = false,
) {
    override fun toString(): String = message
}

data class SessionSummary(
    val id: String,
    val title: String,
    val preview: String,
    val messageCount: Int,
    val updatedLabel: String = "",
)

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val isStreaming: Boolean = false,
)

data class ToolActivity(
    val id: String,
    val name: String,
    val detail: String,
    val complete: Boolean = false,
)

data class ApprovalRequest(
    val requestId: String,
    val command: String,
    val choices: List<String>,
)

data class AttachmentReceipt(
    val name: String,
    val marker: String,
    val bytes: Long,
    val kind: String,
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}
