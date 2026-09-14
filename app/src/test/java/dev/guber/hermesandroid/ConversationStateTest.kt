package dev.guber.hermesandroid

import dev.guber.hermesandroid.data.ChatMessage
import dev.guber.hermesandroid.data.ToolActivity
import dev.guber.hermesandroid.ui.HermesUiState
import dev.guber.hermesandroid.ui.finishTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStateTest {
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
}
