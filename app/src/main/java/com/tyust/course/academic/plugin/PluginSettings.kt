package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.manager.UserManager
import org.json.JSONObject

object PluginSettings {
    fun legacyServiceValues(app: Context, pkg: PluginPackage): Map<String, String> {
        if (!pkg.official) return emptyMap()
        val prefs = app.getSharedPreferences("plugin-settings", Context.MODE_PRIVATE)
        return declarations(pkg).filter { it.optString("scope") == "service" && !prefs.contains(key(app, pkg, it)) }.mapNotNull { setting ->
            val id = setting.getString("id")
            prefs.getString("${pkg.manifest.id}/$id/${UserManager.getInstance().currentAccountStorageKey}", null)?.let { id to it }
        }.toMap()
    }
    fun key(app: Context, pkg: PluginPackage, setting: JSONObject): String {
        val scope = setting.optString("scope", "plugin")
        val identity = when (scope) {
            "account" -> UserManager.getInstance().currentAccountStorageKey
            "service" -> {
                val accounts = PluginServiceAccounts(app)
                val id = setting.optString("serverId").ifBlank { pkg.manifest.json.getJSONArray("servers").getJSONObject(0).getString("id") }
                listOf(id, accounts.server(pkg, id).getString("origin"), accounts.selected(pkg.manifest.id, id)).joinToString("\u0000")
            }
            else -> "plugin"
        }
        return PluginStorageScope.setting(pkg.manifest.id, setting.getString("id"), scope, identity, !pkg.official)
    }
    fun values(app: Context, pkg: PluginPackage): JSONObject = synchronized(PluginServiceAccounts.lock) {
        val prefs = app.getSharedPreferences("plugin-settings", Context.MODE_PRIVATE)
        JSONObject().apply { declarations(pkg).forEach { setting ->
            val key = key(app, pkg, setting)
            var raw = prefs.getString(key, null)
            if (raw == null && pkg.official && setting.optString("scope") != "service") {
                // Earlier settings used an unversioned account suffix. Migrate only
                // trusted plugin/account data; service settings previously had no safe identity.
                val oldScope = if (setting.optString("scope", "plugin") == "plugin") "plugin" else UserManager.getInstance().currentAccountStorageKey
                raw = prefs.getString("${pkg.manifest.id}/${setting.getString("id")}/$oldScope", null)
                if (raw != null) check(prefs.edit().putString(key, raw).commit())
            }
            val value: Any? = if (raw == null) setting.opt("default") else when (setting.getString("type")) {
                "boolean" -> raw.toBooleanStrictOrNull()
                "number" -> raw.toDoubleOrNull()?.takeIf { it.isFinite() }
                else -> raw
            }
            put(setting.getString("id"), value ?: setting.opt("default") ?: JSONObject.NULL)
        } }
    }
    fun write(app: Context, pkg: PluginPackage, expectedKeys: Map<String, String>, values: Map<String, String>) = synchronized(PluginServiceAccounts.lock) {
        val declarations = declarations(pkg)
        if (declarations.any { expectedKeys[it.getString("id")] != key(app, pkg, it) }) throw PluginException(PluginErrorCode.STALE_CONTEXT, "账号已改变，请重新打开设置")
        val edit = app.getSharedPreferences("plugin-settings", Context.MODE_PRIVATE).edit()
        for (setting in declarations) values[setting.getString("id")]?.let { raw ->
            if (raw.length > 1000 || setting.getString("type") == "boolean" && raw.toBooleanStrictOrNull() == null ||
                setting.getString("type") == "number" && raw.toDoubleOrNull()?.isFinite() != true) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "设置值类型无效")
            edit.putString(key(app, pkg, setting), raw)
        }
        check(edit.commit())
    }
    private fun declarations(pkg: PluginPackage) = pkg.manifest.contributes.optJSONArray("settings")?.let(PluginJson::objects).orEmpty()
}
