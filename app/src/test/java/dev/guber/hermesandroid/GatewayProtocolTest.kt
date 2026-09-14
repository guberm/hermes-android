package dev.guber.hermesandroid

import dev.guber.hermesandroid.data.GatewayEndpoint
import dev.guber.hermesandroid.data.GatewayClient
import dev.guber.hermesandroid.data.GatewayGenerationGate
import dev.guber.hermesandroid.data.NewlineJsonRpcDecoder
import dev.guber.hermesandroid.data.ReconnectPolicy
import dev.guber.hermesandroid.data.SequencedEventGate
import dev.guber.hermesandroid.data.jsonRpcRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProtocolTest {
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
