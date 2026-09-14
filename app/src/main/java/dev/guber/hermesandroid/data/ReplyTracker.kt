package dev.guber.hermesandroid.data

import org.json.JSONObject

data class CompletedReply(val sessionId: String, val title: String, val text: String)

class ReplyTracker {
    private val pending = mutableMapOf<String, CompletedReply>()
    val isWaiting: Boolean @Synchronized get() = pending.isNotEmpty()

    @Synchronized
    fun begin(session: SessionIdentity, title: String) {
        session.runtimeSessionId?.let { pending[it] = CompletedReply(session.storedSessionId, title, "") }
    }

    @Synchronized
    fun remap(session: SessionIdentity) {
        val runtime = session.runtimeSessionId ?: return
        val old = pending.entries.firstOrNull { it.value.sessionId == session.storedSessionId } ?: return
        val reply = old.value
        pending.remove(old.key)
        pending[runtime] = reply
    }

    @Synchronized
    fun cancel(runtimeSessionId: String? = null) {
        if (runtimeSessionId == null) pending.clear() else pending.remove(runtimeSessionId)
    }

    @Synchronized
    fun receive(event: JSONObject): CompletedReply? {
        val runtime = event.optString("session_id")
        val reply = pending[runtime] ?: return null
        val payload = event.optJSONObject("payload") ?: JSONObject()
        val text = payload.optionalString("text", "content", "delta")
        when (event.optString("type")) {
            "message.delta", "message.interim" -> pending[runtime] = reply.copy(text = (reply.text + text).take(512))
            "message.complete" -> {
                pending.remove(runtime)
                return reply.copy(text = text.ifBlank { reply.text }.ifBlank { "Your response is ready." }.take(512))
            }
        }
        return null
    }
}
