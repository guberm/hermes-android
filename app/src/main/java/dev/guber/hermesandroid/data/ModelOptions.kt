package dev.guber.hermesandroid.data

import org.json.JSONObject

data class GatewayModel(val id: String, val provider: String, val providerName: String, val available: Boolean = true)

private val modelToken = Regex("[A-Za-z0-9][A-Za-z0-9_./:@+\\-]*")

fun parseModelOptions(result: JSONObject): List<GatewayModel> = buildList {
    val providers = result.optJSONArray("providers") ?: return@buildList
    for (index in 0 until providers.length()) {
        val provider = providers.optJSONObject(index) ?: continue
        if (!provider.optBoolean("authenticated", true)) continue
        val slug = provider.optionalString("slug", "id")
        if (!modelToken.matches(slug)) continue
        val models = provider.optJSONArray("models") ?: continue
        val unavailable = provider.optJSONArray("unavailable_models")
        val unavailableIds = if (unavailable == null) emptySet() else
            (0 until unavailable.length()).map { unavailable.optString(it) }.toSet()
        for (i in 0 until models.length()) {
            val item = models.opt(i)
            val id = if (item is JSONObject) item.optionalString("id", "model") else item as? String ?: continue
            if (modelToken.matches(id)) add(GatewayModel(id, slug, provider.optionalString("name").ifBlank { slug },
                id !in unavailableIds && (item !is JSONObject || item.optBoolean("available", true))))
        }
    }
}.distinctBy { it.provider to it.id }

fun modelSelectionParams(sessionId: String, model: GatewayModel, confirmed: Boolean): JSONObject {
    require(sessionId.isNotBlank()) { "Start or resume a conversation first" }
    require(modelToken.matches(model.id) && modelToken.matches(model.provider) && model.available) { "Model is unavailable" }
    return JSONObject().put("session_id", sessionId).put("key", "model")
        .put("value", "${model.id} --provider ${model.provider} --session")
        .put("confirm_expensive_model", confirmed)
}
