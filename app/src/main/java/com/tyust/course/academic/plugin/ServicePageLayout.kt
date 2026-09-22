package com.tyust.course.academic.plugin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stores only layout IDs, never service data or credentials. New blocks remain visible. */
class ServicePageLayout(context: Context, scope: String) {
    private val prefs = context.getSharedPreferences("campus-layout-" + PluginJson.sha256(scope.toByteArray()), Context.MODE_PRIVATE)
    fun order(page: JSONObject): List<JSONObject> {
        val saved = PluginJson.strings(runCatching { JSONArray(prefs.getString(page.getString("pageId") + ":order", "[]")) }.getOrNull())
        return ordered(PluginJson.objects(page.getJSONArray("blocks")), saved)
    }
    fun hidden(pageId: String): Set<String> = prefs.getStringSet("$pageId:hidden", emptySet()).orEmpty().toSet()
    fun hide(pageId: String, id: String, value: Boolean) {
        val hidden = hidden(pageId).toMutableSet(); if (value) hidden.add(id) else hidden.remove(id)
        prefs.edit().putStringSet("$pageId:hidden", hidden).apply()
    }
    fun move(page: JSONObject, id: String, delta: Int) {
        val order = order(page).map { it.getString("id") }.toMutableList()
        val index = order.indexOf(id); if (index < 0) return
        val destination = (index + delta).coerceIn(0, order.lastIndex)
        order.add(destination, order.removeAt(index))
        prefs.edit().putString(page.getString("pageId") + ":order", JSONArray(order).toString()).apply()
    }
    fun reset(pageId: String) { prefs.edit().remove("$pageId:order").remove("$pageId:hidden").apply() }
    companion object {
        fun ordered(blocks: List<JSONObject>, saved: List<String>): List<JSONObject> {
            val positions = saved.withIndex().associate { it.value to it.index }
            return blocks.sortedBy { positions[it.getString("id")] ?: Int.MAX_VALUE }
        }
    }
}
