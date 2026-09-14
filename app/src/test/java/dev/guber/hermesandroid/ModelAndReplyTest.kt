package dev.guber.hermesandroid

import dev.guber.hermesandroid.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelAndReplyTest {
    @Test
    fun modelOptionsKeepConfiguredProvidersAndDisableUnavailableModels() {
        val result = JSONObject("""{"providers":[{"slug":"openai-codex","name":"Codex","authenticated":true,"models":["gpt-5","gpt-6"],"unavailable_models":["gpt-6"]},{"slug":"other","authenticated":false,"models":["unconfigured"]}]}""")
        val models = parseModelOptions(result)
        assertEquals(listOf("gpt-5", "gpt-6"), models.map { it.id })
        assertTrue(models.first().available)
        assertFalse(models.last().available)
        val params = modelSelectionParams("runtime-1", models.first(), false)
        assertEquals("runtime-1", params.getString("session_id"))
        assertEquals("model", params.getString("key"))
        assertEquals("gpt-5 --provider openai-codex --session", params.getString("value"))
        assertFalse(params.getBoolean("confirm_expensive_model"))
    }

    @Test
    fun replyNotificationIsProducedOnceOnlyForRequestedTurns() {
        val tracker = ReplyTracker()
        fun event(type: String, text: String) = JSONObject().put("type", type).put("session_id", "runtime")
            .put("payload", JSONObject().put("text", text))
        assertNull(tracker.receive(event("message.complete", "Old history")))
        tracker.begin(SessionIdentity("stored", "runtime"), "My chat")
        assertNull(tracker.receive(event("message.delta", "Hello")))
        assertEquals("Hello", tracker.receive(event("message.complete", ""))?.text)
        assertNull(tracker.receive(event("message.complete", "Hello")))
        assertFalse(tracker.isWaiting)
    }

    @Test
    fun pendingReplySurvivesRuntimeChangeAndCanBeCancelled() {
        val tracker = ReplyTracker()
        tracker.begin(SessionIdentity("stored", "old-runtime"), "My chat")
        tracker.remap(SessionIdentity("stored", "new-runtime"))
        assertTrue(tracker.isWaiting)
        val reply = tracker.receive(JSONObject("""{"type":"message.complete","session_id":"new-runtime","payload":{"text":"Done"}}"""))
        assertEquals("stored", reply?.sessionId)
        tracker.begin(SessionIdentity("stored", "new-runtime"), "My chat")
        tracker.cancel("new-runtime")
        assertFalse(tracker.isWaiting)
    }
}
