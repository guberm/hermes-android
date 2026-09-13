package dev.guber.hermesandroid

import dev.guber.hermesandroid.data.GatewayEndpoint
import dev.guber.hermesandroid.data.NewlineJsonRpcDecoder
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
        decoder.feed("{\"method\":\"event\"}")
        assertEquals("event", decoder.finish().single().getString("method"))
        assertTrue(decoder.finish().isEmpty())
    }

    @Test
    fun jsonRpcRequestIsNewlineDelimitedAndKeepsParamsObject() {
        val request = JSONObject(jsonRpcRequest(7, "session.resume", JSONObject().put("session_id", "stored-1")).trim())
        assertEquals("2.0", request.getString("jsonrpc"))
        assertEquals(7, request.getInt("id"))
        assertEquals("stored-1", request.getJSONObject("params").getString("session_id"))
    }
}
