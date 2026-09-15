package dev.guber.hermesandroid

import dev.guber.hermesandroid.data.ChatMessage
import dev.guber.hermesandroid.data.SessionSummary
import dev.guber.hermesandroid.data.ToolActivity
import dev.guber.hermesandroid.ui.HermesUiState
import dev.guber.hermesandroid.ui.finishTurn
import dev.guber.hermesandroid.ui.insertSubmittedMessage
import dev.guber.hermesandroid.ui.mergeToolActivity
import dev.guber.hermesandroid.ui.queueErrorMessage
import dev.guber.hermesandroid.ui.visibleSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStateTest {
    @Test
    fun invalidGatewayTimestampUsesTheCurrentTimeInsteadOfPlaceholder() {
        assertTrue(messageTime("null").matches(Regex("\\d{2}:\\d{2}")))
        assertTrue(messageTime("not-a-time").matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun queuedPromptStaysBeforeAnAnswerThatArrivesBeforeAcknowledgement() {
        val previous = ChatMessage("old", "assistant", "Previous response")
        val earlyAnswer = ChatMessage("new", "assistant", "New response", true)
        val ordered = insertSubmittedMessage(listOf(previous, earlyAnswer), "old", "Queued prompt")
        assertEquals(listOf("Previous response", "Queued prompt", "New response"), ordered.map { it.text })
        val alreadyComplete = insertSubmittedMessage(listOf(earlyAnswer.copy(isStreaming = false)), null, "First prompt")
        assertEquals(listOf("First prompt", "New response"), alreadyComplete.map { it.text })
    }

    @Test
    fun sessionSearchKeepsPinsFirstAndPreservesServerOrder() {
        val sessions = listOf(
            SessionSummary("new", "Newest", "Android build", 1),
            SessionSummary("other", "Other", "", 1),
            SessionSummary("pin", "Android", "Older chat", 2),
        )
        assertEquals(listOf("pin", "new", "other"), visibleSessions(sessions, setOf("pin", "missing"), "").map { it.id })
        assertEquals(listOf("pin", "new"), visibleSessions(sessions, setOf("pin"), " ANDROID ").map { it.id })
        assertEquals(sessions, visibleSessions(sessions, emptySet(), ""))
        assertTrue(visibleSessions(sessions, setOf("pin"), "no match").isEmpty())
    }

    @Test
    fun finishedTurnStopsThinkingAndPreservesPartialResponse() {
        val state = HermesUiState(
            isSending = true,
            messages = listOf(ChatMessage("reply", "assistant", "Partial reply", true)),
            tools = listOf(ToolActivity("reasoning", "Thinking", "Working", false)),
        ).finishTurn("Response stopped")
        assertFalse(state.isSending)
        assertFalse(state.messages.single().isStreaming)
        assertEquals("Partial reply", state.messages.single().text)
        assertTrue(state.tools.single().complete)
        assertEquals("Response stopped", state.statusText)
    }

    @Test
    fun reasoningUpdatesKeepTheEntireGatewaySummary() {
        val first = ToolActivity("reasoning", "Thinking", "Checking logs")
        val updated = mergeToolActivity(first, "Checking new errors", complete = false)
        assertEquals("Checking logs\nChecking new errors", updated.detail)
        assertEquals("Checking logs\nChecking new errors", mergeToolActivity(updated, "", complete = true).detail)
        assertTrue(mergeToolActivity(updated, "", complete = true).complete)
    }

    @Test
    fun liveDesktopOwnerErrorGivesAUsefulRetryInstruction() {
        val raw = "Session 20260915_092822_062a8f already has a live owner (desktop, pid 1894627, lease age 12m)."
        assertEquals("This chat is active in Hermes Desktop. Close it there, then tap Retry.", queueErrorMessage(raw))
        assertEquals("Network unavailable", queueErrorMessage("Network unavailable"))
    }
}
