package com.tyust.course.academic.plugin.widget

import android.content.Context
import android.content.SharedPreferences
import com.tyust.course.academic.plugin.PluginWidgetContract
import com.tyust.course.academic.plugin.PluginJson
import org.json.JSONObject

/**
 * One bound launcher instance: which plugin widget it shows and how it is configured.
 *
 * Stored in a dedicated preferences file keyed by `appWidgetId`, following the existing schedule
 * widget appearance pattern so cleanup can be a simple key removal.
 */
object PluginWidgetBindings {
    private const val PREFS = "plugin-widget-bindings"

    data class Binding(
        val appWidgetId: Int,
        val pluginId: String,
        val widgetId: String,
        val viewKey: String,
        val template: String,
        val theme: String,
        val account: String,
        val options: JSONObject,
        val size: String,
        val boundAt: Long,
        val offset: Int = 0,
    ) {
        fun family(): PluginWidgetContract.Family? = PluginWidgetContract.Family.of(size)
    }

    fun read(context: Context, appWidgetId: Int): Binding? {
        if (appWidgetId <= 0) return null
        val raw = prefs(context).getString(appWidgetId.toString(), null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val pluginId = json.optString("pluginId")
        val widgetId = json.optString("widgetId")
        if (pluginId.isBlank() || widgetId.isBlank()) return null
        return Binding(
            appWidgetId = appWidgetId,
            pluginId = pluginId,
            widgetId = widgetId,
            viewKey = json.optString("viewKey"),
            template = json.optString("template"),
            theme = json.optString("theme", "auto").takeIf { it in PluginWidgetContract.THEMES } ?: "auto",
            account = json.optString("account"),
            options = json.optJSONObject("options") ?: JSONObject(),
            size = json.optString("size"),
            boundAt = json.optLong("boundAt"),
            offset = json.optInt("offset"),
        )
    }

    fun save(context: Context, binding: Binding) = synchronized(WidgetSnapshotStore) {
        if (binding.appWidgetId <= 0) return@synchronized
        prefs(context).edit().putString(binding.appWidgetId.toString(), json(binding).toString()).apply()
    }

    internal fun fingerprint(binding: Binding): String = PluginJson.canonical(json(binding))

    private fun json(binding: Binding): JSONObject = JSONObject()
            .put("pluginId", binding.pluginId)
            .put("widgetId", binding.widgetId)
            .put("viewKey", binding.viewKey)
            .put("template", binding.template)
            .put("theme", binding.theme)
            .put("account", binding.account)
            .put("options", binding.options)
            .put("size", binding.size)
            .put("boundAt", binding.boundAt)
            .put("offset", binding.offset)
    fun delete(context: Context, appWidgetId: Int) = synchronized(WidgetSnapshotStore) {
        read(context, appWidgetId)?.let { WidgetSnapshotStore.clearInstance(context, it) }
        prefs(context).edit().remove(appWidgetId.toString()).apply()
    }

    fun all(context: Context): List<Binding> = prefs(context).all.keys
        .mapNotNull { it.toIntOrNull() }
        .sorted()
        .mapNotNull { read(context, it) }

    fun forPlugin(context: Context, pluginId: String): List<Binding> = all(context).filter { it.pluginId == pluginId }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
