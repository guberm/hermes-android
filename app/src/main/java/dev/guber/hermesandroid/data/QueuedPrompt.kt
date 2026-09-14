package dev.guber.hermesandroid.data

import org.json.JSONArray
import org.json.JSONObject

data class QueuedPrompt(val id: String, val sessionId: String, val text: String, val title: String,
    val runtimeId: String? = null, val error: String? = null)

fun encodeQueue(items: List<QueuedPrompt>): String = JSONArray().apply {
    items.forEach { put(JSONObject().put("id", it.id).put("session", it.sessionId).put("text", it.text)
        .put("title", it.title).put("error", it.error ?: JSONObject.NULL)) }
}.toString()

fun decodeQueue(value: String): List<QueuedPrompt> = runCatching {
    val array = JSONArray(value)
    (0 until array.length()).mapNotNull { index ->
        val item = array.getJSONObject(index)
        val id = item.getString("id")
        val session = item.getString("session")
        val text = item.getString("text")
        if (id.isBlank() || session.isBlank() || text.isBlank()) null
        else QueuedPrompt(id, session, text, item.optString("title"), error = if (item.isNull("error")) null else item.optString("error"))
    }.distinctBy { it.id }
}.getOrDefault(emptyList())
