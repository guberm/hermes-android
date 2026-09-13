package dev.guber.hermesandroid.data

import org.json.JSONObject

/** Decodes JSON-RPC objects from arbitrary WebSocket frame boundaries. */
class NewlineJsonRpcDecoder {
    private val buffer = StringBuilder()

    fun feed(frame: String): List<JSONObject> {
        buffer.append(frame)
        val result = mutableListOf<JSONObject>()
        while (true) {
            val newline = buffer.indexOf("\n")
            if (newline < 0) break
            val line = buffer.substring(0, newline).trim()
            buffer.delete(0, newline + 1)
            if (line.isNotEmpty()) result += JSONObject(line)
        }
        return result
    }

    fun finish(): List<JSONObject> {
        val line = buffer.toString().trim()
        buffer.clear()
        return if (line.isEmpty()) emptyList() else listOf(JSONObject(line))
    }
}

fun jsonRpcRequest(id: Long, method: String, params: JSONObject = JSONObject()): String =
    JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", method)
        .put("params", params)
        .toString() + "\n"

fun sessionResumeParams(sessionId: String): JSONObject = JSONObject().put("session_id", sessionId)

fun sessionCreateParams(title: String? = null): JSONObject = JSONObject()
    .put("profile", "default")
    .put("close_on_disconnect", false)
    .apply { if (!title.isNullOrBlank()) put("title", title.trim()) }

fun promptSubmitParams(sessionId: String, text: String): JSONObject = JSONObject()
    .put("session_id", sessionId)
    .put("text", text)
    .put("queued", false)
    .put("surface", "hud")

fun approvalResponseParams(requestId: String, choice: String): JSONObject = JSONObject()
    .put("request_id", requestId)
    .put("choice", choice)

fun JSONObject.optionalString(vararg keys: String): String = keys
    .asSequence()
    .map { optString(it) }
    .firstOrNull { it.isNotBlank() }
    .orEmpty()
