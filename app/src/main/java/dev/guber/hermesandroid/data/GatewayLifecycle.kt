package dev.guber.hermesandroid.data

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Durable identity survives a gateway restart; runtime identity is valid only for one process. */
data class SessionIdentity(
    val storedSessionId: String,
    val runtimeSessionId: String? = null,
)

fun JSONObject.sessionIdentity(fallbackStoredSessionId: String? = null): SessionIdentity? {
    val runtime = optionalString("runtime_session_id", "session_id").ifBlank { null }
    val stored = optionalString("stored_session_id", "session_key", "stored_id", "id")
        .ifBlank { fallbackStoredSessionId.orEmpty() }
        .ifBlank { runtime.orEmpty() }
    if (stored.isBlank() && runtime.isNullOrBlank()) return null
    return SessionIdentity(storedSessionId = stored, runtimeSessionId = runtime)
}

/** Accepts an event only when its sequence is newer than the delivered watermark. */
class SequencedEventGate {
    private val lastSeen = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun accept(params: JSONObject): Boolean {
        val sessionId = params.optString("session_id")
        val seq = params.optLong("seq", -1)
        if (sessionId.isBlank() || seq < 0) return true
        val previous = lastSeen[sessionId]
        if (previous != null && seq <= previous) return false
        lastSeen[sessionId] = maxOf(previous ?: -1L, seq)
        return true
    }

    fun watermark(sessionId: String): Long = lastSeen[sessionId] ?: 0L

    @Synchronized
    fun clear() = lastSeen.clear()
}

/** Generation counter used to reject callbacks delivered by an obsolete WebSocket. */
class GatewayGenerationGate {
    private var currentGeneration = 0L

    @Synchronized
    fun begin(): Long {
        currentGeneration += 1
        return currentGeneration
    }

    @Synchronized
    fun isCurrent(generation: Long): Boolean = generation == currentGeneration

    @Synchronized
    fun invalidate() {
        currentGeneration += 1
    }
}

data class ReconnectPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 1_000,
) {
    fun delayForAttempt(attempt: Int): Long? {
        if (attempt < 0 || attempt >= maxAttempts) return null
        return baseDelayMillis * (1L shl attempt)
    }
}

fun readWatchdogExpired(nowMillis: Long, lastReadMillis: Long, timeoutMillis: Long): Boolean =
    nowMillis - lastReadMillis >= timeoutMillis
