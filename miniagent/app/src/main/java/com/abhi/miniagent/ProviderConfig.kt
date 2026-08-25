package com.abhi.miniagent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    var label: String,      // your own name for it, e.g. "OpenRouter - Kimi K2"
    var baseUrl: String,    // e.g. https://openrouter.ai/api/v1/chat/completions
    var apiKey: String,
    var model: String,      // model id sent in the request body
    var active: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("active", active)
    }

    companion object {
        fun fromJson(o: JSONObject): ProviderConfig = ProviderConfig(
            id = o.getString("id"),
            label = o.getString("label"),
            baseUrl = o.getString("baseUrl"),
            apiKey = o.getString("apiKey"),
            model = o.getString("model"),
            active = o.optBoolean("active", false)
        )

        fun listToJson(list: List<ProviderConfig>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(str: String?): MutableList<ProviderConfig> {
            if (str.isNullOrBlank()) return mutableListOf()
            val arr = JSONArray(str)
            val out = mutableListOf<ProviderConfig>()
            for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
            return out
        }
    }
}
