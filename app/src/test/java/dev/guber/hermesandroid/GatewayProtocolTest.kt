package dev.guber.hermesandroid

import dev.guber.hermesandroid.data.GatewayEndpoint
import dev.guber.hermesandroid.data.GatewayClient
import dev.guber.hermesandroid.data.GatewayGenerationGate
import dev.guber.hermesandroid.data.GatewayTicketProvider
import dev.guber.hermesandroid.data.NewlineJsonRpcDecoder
import dev.guber.hermesandroid.data.ReconnectPolicy
import dev.guber.hermesandroid.data.SessionIdentity
import dev.guber.hermesandroid.data.SequencedEventGate
import dev.guber.hermesandroid.data.jsonRpcRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okio.ByteString
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProtocolTest {
    @Test
    fun followUpsUseExplicitQueueAndNonInterruptingSteer() = runBlocking {
        val factory = RecordingWebSocketFactory()
        val client = GatewayClient(ticketProvider = GatewayTicketProvider { _, _ -> Result.success("ticket") }, webSocketFactory = factory)
        try {
            client.connect(GatewayEndpoint.parse("https://gateway.example").getOrThrow(), dev.guber.hermesandroid.data.AuthSession("access", "", 0))
            val socket = factory.sockets.single()
            socket.open()
            client.followUp("runtime", "next", true)
            val queue = socket.sentRequest("prompt.submit").getJSONObject("params")
            assertTrue(queue.getBoolean("queued"))
            assertEquals("next", queue.getString("text"))
            client.followUp("runtime", "correction", false)
            val steer = socket.sentRequest("session.steer").getJSONObject("params")
            assertEquals("runtime", steer.getString("session_id"))
            assertEquals("correction", steer.getString("text"))
            assertFalse(steer.has("queued"))
        } finally { client.shutdown() }
    }
    @Test
    fun expiredBearerRequestsRefreshInsteadOfRetryingTheSameCredential() = runBlocking {
        val errors = mutableListOf<dev.guber.hermesandroid.data.GatewayError>()
        val client = GatewayClient(ticketProvider = GatewayTicketProvider { _, _ ->
            Result.failure(dev.guber.hermesandroid.data.GatewayHttpException(401, "session_expired"))
        })
        client.listener = object : GatewayClient.Listener {
            override fun onRpcError(method: String, error: dev.guber.hermesandroid.data.GatewayError) { errors += error }
        }
        try {
            client.connect(GatewayEndpoint.parse("https://gateway.example").getOrThrow(), dev.guber.hermesandroid.data.AuthSession("expired", "refresh", 0))
            assertEquals(401, errors.single().code)
        } finally { client.shutdown() }
    }

    @Test
    fun lateHistoryResponseCannotReplaceTheSelectedConversation() = runBlocking {
        val factory = RecordingWebSocketFactory()
        val client = GatewayClient(ticketProvider = GatewayTicketProvider { _, _ -> Result.success("ticket") }, webSocketFactory = factory)
        val selected = mutableListOf<String>()
        client.listener = object : GatewayClient.Listener {
            override fun onSessionReady(session: SessionIdentity, messages: List<dev.guber.hermesandroid.data.ChatMessage>) { selected += session.storedSessionId }
        }
        try {
            client.connect(GatewayEndpoint.parse("https://gateway.example").getOrThrow(), dev.guber.hermesandroid.data.AuthSession("access", "", 0))
            val socket = factory.sockets.single()
            socket.open()
            client.resumeSession("first")
            client.resumeSession("second")
            val requests = socket.sent.map(::JSONObject).filter { it.getString("method") == "session.resume" }
            for (request in requests.reversed()) {
                val session = request.getJSONObject("params").getString("session_id")
                socket.deliver("""{"id":${request.getLong("id")},"result":{"session_id":"runtime-$session","session_key":"$session","messages":[]}}""")
            }
            assertEquals(listOf("second"), selected)
        } finally { client.shutdown() }
    }

    @Test
    fun resumedHistoryReadsGatewayTextAndLegacyContent() = runBlocking {
        val factory = RecordingWebSocketFactory()
        val client = GatewayClient(ticketProvider = GatewayTicketProvider { _, _ -> Result.success("ticket") }, webSocketFactory = factory)
        var history = emptyList<dev.guber.hermesandroid.data.ChatMessage>()
        client.listener = object : GatewayClient.Listener {
            override fun onSessionReady(session: SessionIdentity, messages: List<dev.guber.hermesandroid.data.ChatMessage>) { history = messages }
        }
        try {
            client.connect(GatewayEndpoint.parse("https://gateway.example").getOrThrow(), dev.guber.hermesandroid.data.AuthSession("access", "", 0))
            val socket = factory.sockets.single()
            socket.open()
            client.resumeSession("stored")
            val request = socket.sentRequest("session.resume")
            socket.deliver("""{"id":${request.getLong("id")},"result":{"session_id":"runtime","session_key":"stored","messages":[{"role":"user","text":"Hello"},{"role":"assistant","text":"ANDROID_OK"},{"role":"assistant","content":[{"type":"text","text":"Legacy"}]}]}}""")
            assertEquals(listOf("Hello", "ANDROID_OK", "Legacy"), history.map { it.text })
            assertEquals(listOf("user", "assistant", "assistant"), history.map { it.role })
        } finally { client.shutdown() }
    }

    @Test
    fun resumedHistoryKeepsReasoningAndToolActivity() = runBlocking {
        val factory = RecordingWebSocketFactory()
        val client = GatewayClient(ticketProvider = GatewayTicketProvider { _, _ -> Result.success("ticket") }, webSocketFactory = factory)
        var history = emptyList<dev.guber.hermesandroid.data.ChatMessage>()
        var activity = emptyList<dev.guber.hermesandroid.data.ToolActivity>()
        client.listener = object : GatewayClient.Listener {
            override fun onSessionReady(
                session: SessionIdentity,
                messages: List<dev.guber.hermesandroid.data.ChatMessage>,
                tools: List<dev.guber.hermesandroid.data.ToolActivity>,
            ) {
                history = messages
                activity = tools
            }
        }
        try {
            client.connect(GatewayEndpoint.parse("https://gateway.example").getOrThrow(), dev.guber.hermesandroid.data.AuthSession("access", "", 0))
            val socket = factory.sockets.single()
            socket.open()
            client.resumeSession("stored")
            val request = socket.sentRequest("session.resume")
            socket.deliver("""{"id":${request.getLong("id")},"result":{"session_id":"runtime","session_key":"stored","messages":[{"role":"user","text":"Start","created_at":null},{"role":"assistant","text":"","reasoning":"Check the state"},{"role":"tool","name":"terminal","context":"git status"},{"role":"assistant","text":"Done"}]}}""")
            assertEquals(listOf("Start", "Done"), history.map { it.text })
            assertEquals(listOf("Thinking", "terminal"), activity.map { it.name })
            assertEquals(listOf("Check the state", "git status"), activity.map { it.detail })
            assertTrue(history.first().createdAt != "null")
        } finally { client.shutdown() }
    }

    @Test
    fun obsoleteTicketCannotReplaceNewConnection() = runBlocking {
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstTicket = kotlinx.coroutines.CompletableDeferred<Result<String>>()
        val factory = RecordingWebSocketFactory()
        val client = GatewayClient(
            ticketProvider = GatewayTicketProvider { _, token ->
                if (token == "old") {
                    firstStarted.complete(Unit)
                    firstTicket.await()
                } else Result.success("new-ticket")
            },
            webSocketFactory = factory,
        )
        try {
            val endpoint = GatewayEndpoint.parse("https://gateway.example").getOrThrow()
            val old = kotlinx.coroutines.CoroutineScope(coroutineContext).async {
                client.connect(endpoint, dev.guber.hermesandroid.data.AuthSession("old", "", 0))
            }
            firstStarted.await()
            client.connect(endpoint, dev.guber.hermesandroid.data.AuthSession("new", "", 0))
            firstTicket.complete(Result.success("old-ticket"))
            assertTrue(old.await().isFailure)
            assertEquals(1, factory.sockets.size)
            assertEquals("new-ticket", factory.sockets.single().request().url.queryParameter("ticket"))
        } finally {
            firstTicket.complete(Result.failure(IllegalStateException("Test ended")))
            client.shutdown()
        }
    }

    @Test
    fun serverClosingHandshakeClosesClientSocket() = runBlocking {
        val factory = RecordingWebSocketFactory()
        val client = GatewayClient(ticketProvider = GatewayTicketProvider { _, _ -> Result.success("ticket") }, webSocketFactory = factory)
        try {
            client.connect(GatewayEndpoint.parse("https://gateway.example").getOrThrow(), dev.guber.hermesandroid.data.AuthSession("access", "", 0))
            val socket = factory.sockets.single()
            socket.open()
            socket.serverClosing()
            assertEquals(1, socket.closeCalls)
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun restoresApprovalsArrayFromGatewayContract() = runBlocking {
        val factory = RecordingWebSocketFactory()
        val client = GatewayClient(ticketProvider = GatewayTicketProvider { _, _ -> Result.success("ticket") }, webSocketFactory = factory)
        val restored = mutableListOf<dev.guber.hermesandroid.data.ApprovalRequest>()
        val answered = mutableListOf<String>()
        client.listener = object : GatewayClient.Listener {
            override fun onPendingApprovals(approvals: List<dev.guber.hermesandroid.data.ApprovalRequest>) { restored += approvals }
            override fun onApprovalResponse(requestId: String) { answered += requestId }
        }
        try {
            client.connect(GatewayEndpoint.parse("https://gateway.example").getOrThrow(), dev.guber.hermesandroid.data.AuthSession("access", "", 0))
            val socket = factory.sockets.single()
            socket.open()
            client.setActiveSession(SessionIdentity("stored", "runtime"))
            val request = socket.sentRequest("approval.pending")
            socket.deliver("""{"id":${request.getLong("id")},"result":{"approvals":[{"request_id":"approval-1","command":"Run checks","choices":["once","deny"]}]}}""")
            assertEquals("approval-1", restored.single().requestId)
            client.respondApproval("approval-1", "once")
            val response = socket.sentRequest("approval.respond")
            socket.deliver("""{"id":${response.getLong("id")},"result":{"ok":true}}""")
            assertEquals(listOf("approval-1"), answered)
        } finally { client.shutdown() }
    }

    @Test
    fun restoresInteractivePromptsAndRespondsWithTheGatewayFields() = runBlocking {
        val factory = RecordingWebSocketFactory()
        val events = mutableListOf<JSONObject>()
        val answered = mutableListOf<String>()
        val client = GatewayClient(ticketProvider = GatewayTicketProvider { _, _ -> Result.success("ticket") }, webSocketFactory = factory)
        client.listener = object : GatewayClient.Listener {
            override fun onEvent(params: JSONObject) { events += params }
            override fun onPromptResponse(requestId: String) { answered += requestId }
        }
        try {
            client.connect(GatewayEndpoint.parse("https://gateway.example").getOrThrow(), dev.guber.hermesandroid.data.AuthSession("access", "", 0))
            val socket = factory.sockets.single()
            socket.open()
            client.setActiveSession(SessionIdentity("stored", "runtime"))
            val pending = socket.sentRequest("prompt.pending")
            socket.deliver("""{"id":${pending.getLong("id")},"result":{"requests":[{"type":"clarify.request","payload":{"request_id":"clarify-1","question":"Continue?","choices":["Yes","No"]}},{"type":"secret.request","payload":{"request_id":"secret-1","prompt":"Enter secret"}}]}}""")
            assertEquals(listOf("clarify.request", "secret.request"), events.map { it.getString("type") })
            assertEquals("clarify-1", events.first().getJSONObject("payload").getString("request_id"))

            client.respondPrompt("clarify-1", "clarify.request", "Yes")
            client.respondPrompt("secret-1", "secret.request", "value")
            val clarify = socket.sentRequest("clarify.respond").getJSONObject("params")
            val secret = socket.sentRequest("secret.respond").getJSONObject("params")
            assertEquals("Yes", clarify.getString("answer"))
            assertEquals("value", secret.getString("value"))
            val response = socket.sentRequest("clarify.respond")
            socket.deliver("""{"id":${response.getLong("id")},"result":{"ok":true}}""")
            assertEquals(listOf("clarify-1"), answered)
        } finally { client.shutdown() }
    }

    @Test
    fun endpointMapsHttpsToWssAndKeepsBasePath() {
        val endpoint = GatewayEndpoint.parse("https://gateway.example/hermes/").getOrThrow()
        assertEquals("wss://gateway.example/hermes/api/ws", endpoint.wsUrl)
        assertEquals("https://gateway.example/hermes/api/auth/ws-ticket", endpoint.route("/api/auth/ws-ticket").toString())
    }

    @Test
    fun endpointRejectsCredentialsAndQueryParameters() {
        assertTrue(GatewayEndpoint.parse("https://user:secret@gateway.example").isFailure)
        assertTrue(GatewayEndpoint.parse("https://gateway.example?token=do-not-store").isFailure)
        assertFalse(GatewayEndpoint.parse("http://127.0.0.1:8642").isFailure)
    }

    @Test
    fun gatewayRequestCarriesTicketInQueryForProxyCompatibleWssUpgrade() = runBlocking {
        val factory = RecordingWebSocketFactory()
        val client = GatewayClient(
            ticketProvider = GatewayTicketProvider { _, _ -> Result.success("test-ticket") },
            webSocketFactory = factory,
        )
        val endpoint = GatewayEndpoint.parse("https://gateway.example").getOrThrow()

        assertTrue(client.connect(endpoint, dev.guber.hermesandroid.data.AuthSession("access", "refresh", 0)).isSuccess)

        val request = factory.sockets.single().request()
        assertEquals("https", request.url.scheme)
        assertEquals("https://gateway.example/api/ws?ticket=test-ticket", request.url.toString())
        assertEquals("/api/ws", request.url.encodedPath)
        assertEquals("test-ticket", request.url.queryParameter("ticket"))
        assertEquals(null, request.header("Sec-WebSocket-Protocol"))
        client.shutdown()
    }

    @Test
    fun decoderAcceptsMultipleObjectsAndFragmentedFrames() {
        val decoder = NewlineJsonRpcDecoder()
        assertTrue(decoder.feed("{\"id\":1").isEmpty())
        val messages = decoder.feed(",\"result\":{}}\n{\"id\":2,\"result\":{\"ok\":true}}\n")
        assertEquals(2, messages.size)
        assertEquals(1, messages[0].getInt("id"))
        assertTrue(messages[1].getJSONObject("result").getBoolean("ok"))
    }

    @Test
    fun decoderFinishHandlesFinalObjectWithoutNewline() {
        val decoder = NewlineJsonRpcDecoder()
        assertEquals("event", decoder.feed("{\"method\":\"event\"}").single().getString("method"))
        assertTrue(decoder.finish().isEmpty())
    }

    @Test
    fun decoderDispatchesOneCompleteJsonObjectPerWebSocketTextFrame() {
        val decoder = NewlineJsonRpcDecoder()
        val messages = decoder.feed("{\"method\":\"event\",\"params\":{\"seq\":7}}")
        assertEquals(1, messages.size)
        assertEquals(7, messages.single().getJSONObject("params").getInt("seq"))
    }

    @Test
    fun decoderAcceptsMultipleObjectsInOneFrameWithoutNewlines() {
        val decoder = NewlineJsonRpcDecoder()
        val messages = decoder.feed("{\"id\":1}{\"id\":2}")
        assertEquals(listOf(1, 2), messages.map { it.getInt("id") })
    }

    @Test
    fun sequenceGateDeduplicatesReplayAndNeverMovesWatermarkBackwards() {
        val gate = SequencedEventGate()
        fun event(seq: Long) = JSONObject().put("session_id", "runtime-1").put("seq", seq)

        assertTrue(gate.accept(event(4)))
        assertFalse(gate.accept(event(4)))
        assertFalse(gate.accept(event(2)))
        assertTrue(gate.accept(event(5)))
        assertEquals(5, gate.watermark("runtime-1"))
    }

    @Test
    fun gatewayTransportDispatchesActualUnterminatedHermesTextFrameAndDeduplicatesIt() {
        val client = GatewayClient()
        val received = mutableListOf<JSONObject>()
        client.listener = object : GatewayClient.Listener {
            override fun onEvent(params: JSONObject) {
                received += params
            }
        }
        val frame = "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"message.delta\",\"session_id\":\"runtime-1\",\"seq\":9}}"
        client.receiveTextFrame(frame)
        client.receiveTextFrame(frame)
        assertEquals(1, received.size)
        assertEquals("message.delta", received.single().getString("type"))
        client.shutdown()
    }

    @Test
    fun reconnectResumesDurableSessionAndGatesReplayAgainstLiveEvents() = runBlocking {
        val factory = RecordingWebSocketFactory()
        val client = GatewayClient(
            ticketProvider = GatewayTicketProvider { _, _ -> Result.success("ticket") },
            webSocketFactory = factory,
        )
        val ready = mutableListOf<SessionIdentity>()
        val events = mutableListOf<JSONObject>()
        val restoredApprovals = mutableListOf<dev.guber.hermesandroid.data.ApprovalRequest>()
        client.listener = object : GatewayClient.Listener {
            override fun onSessionReady(session: SessionIdentity, messages: List<dev.guber.hermesandroid.data.ChatMessage>) {
                ready += session
            }

            override fun onEvent(params: JSONObject) {
                events += params
            }

            override fun onPendingApprovals(approvals: List<dev.guber.hermesandroid.data.ApprovalRequest>) {
                restoredApprovals += approvals
            }
        }
        client.setActiveSession(SessionIdentity("stored-1", "runtime-old"))
        val endpoint = GatewayEndpoint.parse("https://gateway.example").getOrThrow()
        val auth = dev.guber.hermesandroid.data.AuthSession("access", "refresh", 0)

        assertTrue(client.connect(endpoint, auth).isSuccess)
        val socket = factory.sockets.single()
        socket.open()
        socket.deliver("{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"gateway.ready\",\"payload\":{\"replay_epoch\":\"epoch-1\"}}}")

        val resume = socket.sentRequest("session.resume")
        assertEquals("stored-1", resume.getJSONObject("params").getString("session_id"))
        socket.deliver("{\"jsonrpc\":\"2.0\",\"id\":${resume.getLong("id")},\"result\":{\"session_id\":\"runtime-new\",\"session_key\":\"stored-1\",\"messages\":[]}}")
        assertEquals("stored-1", ready.single().storedSessionId)
        assertEquals("runtime-new", ready.single().runtimeSessionId)

        val pending = socket.sentRequest("approval.pending")
        assertEquals("runtime-new", pending.getJSONObject("params").getString("session_id"))
        socket.deliver("{\"jsonrpc\":\"2.0\",\"id\":${pending.getLong("id")},\"result\":{\"pending\":{\"request_id\":\"approval-1\",\"command\":\"run checks\",\"choices\":[\"once\",\"deny\"]}}}")
        assertEquals("approval-1", restoredApprovals.single().requestId)

        val replay = socket.sentRequest("session.events.since")
        socket.deliver("{\"jsonrpc\":\"2.0\",\"id\":${replay.getLong("id")},\"result\":{\"events\":[{\"type\":\"message.delta\",\"session_id\":\"runtime-new\",\"seq\":4}]}}")
        socket.deliver("{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"message.delta\",\"session_id\":\"runtime-new\",\"seq\":4}}")
        assertEquals(1, events.size)
        assertTrue(events.single().getBoolean("replayed"))
        client.shutdown()
    }

    @Test
    fun callbacksFromAnOldSocketGenerationCannotDispatchIntoTheNewSocket() = runBlocking {
        val factory = RecordingWebSocketFactory()
        val client = GatewayClient(
            ticketProvider = GatewayTicketProvider { _, _ -> Result.success("ticket") },
            webSocketFactory = factory,
        )
        val events = mutableListOf<JSONObject>()
        client.listener = object : GatewayClient.Listener {
            override fun onEvent(params: JSONObject) {
                events += params
            }
        }
        val endpoint = GatewayEndpoint.parse("https://gateway.example").getOrThrow()
        val auth = dev.guber.hermesandroid.data.AuthSession("access", "refresh", 0)
        client.connect(endpoint, auth)
        val first = factory.sockets[0]
        first.open()
        client.connect(endpoint, auth)
        val second = factory.sockets[1]
        second.open()
        val stale = "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"message.delta\",\"session_id\":\"runtime\",\"seq\":1}}"
        first.deliver(stale)
        second.deliver(stale)
        assertEquals(1, events.size)
        client.shutdown()
    }

    @Test
    fun oldWebSocketGenerationIsRejectedAfterReconnect() {
        val generations = GatewayGenerationGate()
        val first = generations.begin()
        val second = generations.begin()
        assertFalse(generations.isCurrent(first))
        assertTrue(generations.isCurrent(second))
    }

    @Test
    fun reconnectPolicyIsBoundedAndUsesExponentialBackoff() {
        val policy = ReconnectPolicy(maxAttempts = 3, baseDelayMillis = 1_000)
        assertEquals(1_000L, policy.delayForAttempt(0))
        assertEquals(2_000L, policy.delayForAttempt(1))
        assertEquals(4_000L, policy.delayForAttempt(2))
        assertEquals(null, policy.delayForAttempt(3))
    }

    @Test
    fun readWatchdogHasARealDeadline() {
        assertFalse(dev.guber.hermesandroid.data.readWatchdogExpired(10_000, 9_500, 1_000))
        assertTrue(dev.guber.hermesandroid.data.readWatchdogExpired(10_500, 9_500, 1_000))
    }

    @Test
    fun jsonRpcRequestIsNewlineDelimitedAndKeepsParamsObject() {
        val request = JSONObject(jsonRpcRequest(7, "session.resume", JSONObject().put("session_id", "stored-1")).trim())
        assertEquals("2.0", request.getString("jsonrpc"))
        assertEquals(7, request.getInt("id"))
        assertEquals("stored-1", request.getJSONObject("params").getString("session_id"))
    }
}

private class RecordingWebSocketFactory : WebSocket.Factory {
    val sockets = mutableListOf<RecordingWebSocket>()

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        return RecordingWebSocket(request, listener).also(sockets::add)
    }
}

private class RecordingWebSocket(
    private val requestValue: Request,
    private val listener: WebSocketListener,
) : WebSocket {
    val sent = mutableListOf<String>()
    var closeCalls = 0

    override fun request(): Request = requestValue
    override fun queueSize(): Long = 0
    override fun send(text: String): Boolean = sent.add(text)
    override fun send(bytes: ByteString): Boolean = true
    override fun close(code: Int, reason: String?): Boolean { closeCalls++; return true }
    override fun cancel() = Unit

    fun open() {
        listener.onOpen(this, Response.Builder().request(requestValue).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
    }

    fun deliver(text: String) {
        listener.onMessage(this, text)
    }

    fun serverClosing() = listener.onClosing(this, 1000, "Server shutdown")

    fun sentRequest(method: String): JSONObject = sent.map(::JSONObject).first { it.getString("method") == method }
}
