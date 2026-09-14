package dev.guber.hermesandroid.data

import org.json.JSONObject

/** Decodes JSON-RPC objects from Hermes frames and newline/multi-object compatibility streams. */
class NewlineJsonRpcDecoder {
    private val buffer = StringBuilder()

    fun reset() = buffer.clear()

    fun feed(frame: String): List<JSONObject> {
        buffer.append(frame)
        val result = mutableListOf<JSONObject>()
        while (true) {
            while (buffer.isNotEmpty() && buffer[0].isWhitespace()) buffer.deleteCharAt(0)
            val end = completeJsonObjectEnd(buffer) ?: break
            val objectText = buffer.substring(0, end)
            buffer.delete(0, end)
            result += JSONObject(objectText)
        }
        return result
    }

    fun finish(): List<JSONObject> {
        val result = feed("").toMutableList()
        if (buffer.isNotEmpty()) {
            val remainder = buffer.toString().trim()
            buffer.clear()
            if (remainder.isNotEmpty()) result += JSONObject(remainder)
        }
        return result
    }

    private fun completeJsonObjectEnd(value: CharSequence): Int? {
        if (value.isEmpty() || value[0] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in value.indices) {
            val character = value[index]
            if (inString) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
                continue
            }
            when (character) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index + 1
                }
            }
        }
        return null
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

fun sessionInterruptParams(sessionId: String): JSONObject = JSONObject().put("session_id", sessionId)

fun approvalPendingParams(sessionId: String): JSONObject = JSONObject().put("session_id", sessionId)

fun JSONObject.optionalString(vararg keys: String): String = keys
    .asSequence()
    .map { optString(it) }
    .firstOrNull { it.isNotBlank() }
    .orEmpty()
