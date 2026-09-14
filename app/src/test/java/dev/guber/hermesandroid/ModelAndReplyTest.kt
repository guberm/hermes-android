package dev.guber.hermesandroid

import dev.guber.hermesandroid.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelAndReplyTest {
    @Test
    fun localQueueSurvivesRestartWithoutReusingRuntimeOrLosingOrder() {
        val items = listOf(QueuedPrompt("one", "stored", "First", "Chat", "old-runtime"),
            QueuedPrompt("two", "stored", "Second", "Chat", error = "Delivery not confirmed"))
        val restored = decodeQueue(encodeQueue(items))
        assertEquals(listOf("one", "two"), restored.map { it.id })
        assertEquals("First", restored.first().text)
        assertNull(restored.first().runtimeId)
        assertEquals("Delivery not confirmed", restored.last().error)
        assertTrue(decodeQueue("invalid").isEmpty())
    }
    @Test
    fun queuedReplyKeepsBackgroundWaitingAfterCurrentReplyCompletes() {
        val tracker = ReplyTracker()
        val session = SessionIdentity("stored", "runtime")
        tracker.begin(session, "Chat")
        tracker.begin(session, "Chat", append = true)
        val event = JSONObject("""{"type":"message.complete","session_id":"runtime","payload":{"text":"Done"}}""")
        assertNotNull(tracker.receive(event))
        assertTrue(tracker.isWaiting)
        assertNotNull(tracker.receive(event))
        assertFalse(tracker.isWaiting)
    }
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
    fun reasoningSelectionIsSessionScopedAndValidatesLevels() {
        val params = reasoningSelectionParams("runtime-1", "high")
        assertEquals("runtime-1", params.getString("session_id"))
        assertEquals("reasoning", params.getString("key"))
        assertEquals("high", params.getString("value"))
        assertThrows(IllegalArgumentException::class.java) { reasoningSelectionParams("runtime-1", "maximum") }
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
