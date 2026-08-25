package com.abhi.miniagent

import android.content.Context

class ProviderStore(context: Context) {
    private val prefs = context.getSharedPreferences("miniagent_providers", Context.MODE_PRIVATE)

    fun load(): MutableList<ProviderConfig> =
        ProviderConfig.listFromJson(prefs.getString("providers", null))

    fun save(list: List<ProviderConfig>) {
        prefs.edit().putString("providers", ProviderConfig.listToJson(list)).apply()
    }

    fun activeOnes(): List<ProviderConfig> = load().filter { it.active }
}
