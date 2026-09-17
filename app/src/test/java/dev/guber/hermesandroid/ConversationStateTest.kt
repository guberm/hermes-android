package dev.guber.hermesandroid

import dev.guber.hermesandroid.data.ChatMessage
import dev.guber.hermesandroid.data.SessionSummary
import dev.guber.hermesandroid.data.ToolActivity
import dev.guber.hermesandroid.data.transcriptContentText
import dev.guber.hermesandroid.ui.HermesUiState
import dev.guber.hermesandroid.ui.finishTurn
import dev.guber.hermesandroid.ui.insertSubmittedMessage
import dev.guber.hermesandroid.ui.mergeToolActivity
import dev.guber.hermesandroid.ui.queueErrorMessage
import dev.guber.hermesandroid.ui.restorableSessionId
import dev.guber.hermesandroid.ui.sessionBadge
import dev.guber.hermesandroid.ui.sessionSourceLabel
import dev.guber.hermesandroid.ui.sessionsWithNewActivity
import dev.guber.hermesandroid.gatewayImageUrl
import dev.guber.hermesandroid.markdownAnnotatedString
import dev.guber.hermesandroid.splitMarkdownImages
import dev.guber.hermesandroid.ui.visibleSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ConversationStateTest {
    @Test
    fun invalidGatewayTimestampUsesTheCurrentTimeInsteadOfPlaceholder() {
        assertTrue(messageTime("null").matches(Regex("\\d{2}:\\d{2}")))
        assertTrue(messageTime("not-a-time").matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun queuedPromptStaysBeforeAnAnswerThatArrivesBeforeAcknowledgement() {
        val previous = ChatMessage("old", "assistant", "Previous response", timelineOrder = 100)
        val earlyAnswer = ChatMessage("new", "assistant", "New response", true, timelineOrder = 200)
        val ordered = insertSubmittedMessage(listOf(previous, earlyAnswer), "old", "Queued prompt")
        assertEquals(listOf("Previous response", "Queued prompt", "New response"), ordered.map { it.text })
        val alreadyComplete = insertSubmittedMessage(listOf(earlyAnswer.copy(isStreaming = false)), null, "First prompt")
        assertEquals(listOf("First prompt", "New response"), alreadyComplete.map { it.text })
        assertEquals(listOf("Previous response", "Queued prompt", "New response"), ordered.sortedBy { it.timelineOrder }.map { it.text })
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
        assertEquals("This chat is active in another Hermes client. Update the Gateway to attach the existing session, then retry.", queueErrorMessage(raw))
        assertEquals("Network unavailable", queueErrorMessage("Network unavailable"))
    }

    @Test
    fun sessionBadgesPrioritizeInputThenPausedOrPendingQueue() {
        val queued = dev.guber.hermesandroid.data.QueuedPrompt("q1", "s1", "Later", "Chat")
        val working = HermesUiState(activeSessionId = "s1", isSending = true, queuedPrompts = listOf(queued))
        assertEquals("Queued", sessionBadge(working, "s1"))
        assertEquals("Queue paused", sessionBadge(working.copy(parkedQueueSessionIds = setOf("s1")), "s1"))
        assertEquals("Input needed", sessionBadge(working.copy(prompts = listOf(dev.guber.hermesandroid.data.InteractivePrompt("p1", "clarify.request", "Continue?"))), "s1"))
        assertEquals(null, sessionBadge(working, "s2"))
    }

    @Test
    fun unreadReplyBadgeIsShownForAnotherChat() {
        val state = HermesUiState(unreadSessionIds = setOf("s2"))
        assertEquals("New reply", sessionBadge(state, "s2"))
        assertEquals(null, sessionBadge(state, "s1"))
    }

    @Test
    fun refreshedSessionListMarksOnlyChatsWithNewGatewayActivity() {
        val previous = listOf(SessionSummary("same", "Same", "", 2), SessionSummary("changed", "Changed", "", 4))
        val refreshed = listOf(SessionSummary("same", "Same", "", 2), SessionSummary("changed", "Changed", "", 5), SessionSummary("new", "New", "", 1))
        assertEquals(setOf("changed"), sessionsWithNewActivity(previous, refreshed))
    }

    @Test
    fun conversationSearchMatchesMessageAndToolText() {
        assertTrue(conversationSearchMatches("Running terminal: ./gradlew test", "terminal"))
        assertTrue(conversationSearchMatches("Approval required", "APPROVAL"))
        assertFalse(conversationSearchMatches("Running terminal", "image"))
    }

    @Test
    fun structuredExportsPreserveTimelineAndMessageMetadata() {
        val state = HermesUiState(
            messages = listOf(
                ChatMessage("user", "user", "Hello", createdAt = "2026-09-17T10:00:00Z", timelineOrder = 100),
                ChatMessage("answer", "assistant", "Done", createdAt = "2026-09-17T10:02:00Z", timelineOrder = 300),
            ),
            tools = listOf(ToolActivity("tool", "Terminal", "./gradlew test", complete = true, timelineOrder = 200)),
        )
        val markdown = transcriptMarkdown("Build chat", state)
        assertTrue(markdown.indexOf("## You") < markdown.indexOf("## Terminal"))
        assertTrue(markdown.indexOf("## Terminal") < markdown.indexOf("## Hermes"))
        val items = JSONObject(transcriptJson("Build chat", state)).getJSONArray("items")
        assertEquals(listOf("message", "tool", "message"), (0 until items.length()).map { items.getJSONObject(it).getString("kind") })
        assertEquals("Hello", items.getJSONObject(0).getString("text"))
    }

    @Test
    fun sourceLabelsMakeCrossDeviceContinuationsClear() {
        assertEquals("Desktop", sessionSourceLabel("desktop"))
        assertEquals("Android", sessionSourceLabel("android"))
        assertEquals("Telegram", sessionSourceLabel("telegram"))
    }

    @Test
    fun lastOpenedSessionIsRestoredOnlyWhenTheGatewayStillListsIt() {
        val sessions = listOf(SessionSummary("desktop-chat", "Desktop chat", "", 1))
        assertEquals("desktop-chat", restorableSessionId("desktop-chat", sessions))
        assertEquals(null, restorableSessionId("deleted-chat", sessions))
        assertEquals(null, restorableSessionId(null, sessions))
    }

    @Test
    fun markdownImagesAreRemovedFromTextAndLimitedToTheGateway() {
        val content = splitMarkdownImages("**Done**\n\n![Chart](/media/chart.png)")
        assertEquals("**Done**", content.markdown)
        assertEquals(listOf("/media/chart.png"), content.imageUrls)
        assertEquals("https://hermes.guber.dev/media/chart.png", gatewayImageUrl("/media/chart.png", "https://hermes.guber.dev"))
        assertEquals("data:image/png;base64,AA==", gatewayImageUrl("data:image/png;base64,AA==", "https://hermes.guber.dev"))
        assertEquals(null, gatewayImageUrl("https://example.com/chart.png", "https://hermes.guber.dev"))
    }

    @Test
    fun transcriptImageBlockBecomesMarkdownImage() {
        val image = JSONObject().put("image_url", JSONObject().put("url", "https://hermes.guber.dev/media/chart.png"))
        assertEquals("![](https://hermes.guber.dev/media/chart.png)", transcriptContentText(image))
    }

    @Test
    fun markdownRendererRemovesMarkupFromVisibleText() {
        assertEquals("Title\nDone with code and docs", markdownAnnotatedString("# Title\n**Done** with `code` and [docs](https://example.com)").text)
    }
}
