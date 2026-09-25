package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Service identities are never the academic username and never select arbitrary origins. */
class PluginServiceAccounts(private val app: Context) {
    private val prefs = app.getSharedPreferences("plugin-service-accounts", Context.MODE_PRIVATE)
    private fun generation(pluginId: String) = prefs.getLong("generation:$pluginId", 0)
    private fun advance(pluginId: String) { check(prefs.edit().putLong("generation:$pluginId", generation(pluginId) + 1).commit()) }
    fun trackScope(pluginId: String, scope: String) = synchronized(lock) {
        val scopes = prefs.getStringSet("scopes:$pluginId", emptySet()).orEmpty()
        if (scope !in scopes) check(prefs.edit().putStringSet("scopes:$pluginId", scopes + scope).commit())
    }
    private fun key(pluginId: String, serverId: String) = "$pluginId/$serverId"
    fun server(pkg: PluginPackage, serverId: String): JSONObject = pkg.manifest.json.optJSONArray("servers")?.let(PluginJson::objects).orEmpty().firstOrNull { it.getString("id") == serverId }
        ?: throw PluginException(PluginErrorCode.PERMISSION_DENIED, "服务器未在插件中声明")
    fun selected(pluginId: String, serverId: String): String = prefs.getString("selected:${key(pluginId, serverId)}", "default")!!
    fun accounts(pluginId: String, serverId: String): List<JSONObject> = PluginJson.objects(JSONArray(prefs.getString("accounts:${key(pluginId, serverId)}", "[]")))
    fun select(pkg: PluginPackage, serverId: String, label: String): String = synchronized(lock) {
        server(pkg, serverId)
        if (label.isBlank() || label.length > 80) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "请输入不超过 80 字的账号名称")
        val items = accounts(pkg.manifest.id, serverId).toMutableList()
        val id = items.firstOrNull { it.getString("label") == label }?.getString("id") ?: UUID.randomUUID().toString().also {
            if (items.size >= 20) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "服务账号数量超过限制")
            items.add(JSONObject().put("id", it).put("label", label))
        }
        check(prefs.edit().putString("accounts:${key(pkg.manifest.id, serverId)}", JSONArray(items).toString()).putString("selected:${key(pkg.manifest.id, serverId)}", id).commit())
        advance(pkg.manifest.id)
        revision.value++; id
    }
    fun namespace(pluginId: String, serverId: String, accountId: String, development: Boolean) = "service\u0000$pluginId\u0000$serverId\u0000$accountId\u0000$development"
    fun profile(pkg: PluginPackage, serverId: String, accountId: String = selected(pkg.manifest.id, serverId)): String = synchronized(lock) {
        val name = "plugin_" + PluginJson.sha256((namespace(pkg.manifest.id, serverId, accountId, !pkg.official) + "\u0000" + server(pkg, serverId).getString("origin") +
            "\u0000" + prefs.getLong("profile-epoch:${pkg.manifest.id}", 0)).toByteArray())
        check(prefs.edit().putStringSet("profiles:${pkg.manifest.id}", prefs.getStringSet("profiles:${pkg.manifest.id}", emptySet()).orEmpty() + name).commit())
        name
    }
    fun session(pkg: PluginPackage, serverId: String): AcademicSession = synchronized(lock) {
        val base = server(pkg, serverId).getString("origin")
        val account = selected(pkg.manifest.id, serverId)
        AcademicSession(AcademicSessionKey("service:${pkg.manifest.id}:$serverId", account), base).also {
            sessions[it] = generation(pkg.manifest.id)
            val scope = PluginStorageScope.session(it, pkg.manifest.id, !pkg.official)
            check(prefs.edit().putStringSet("scopes:${pkg.manifest.id}", prefs.getStringSet("scopes:${pkg.manifest.id}", emptySet()).orEmpty() + scope).commit())
            PluginSessionCookies.restore(app, pkg, it)
        }
    }
    fun current(pkg: PluginPackage, session: AcademicSession): Boolean = synchronized(lock) {
        if (!session.key.schoolId.startsWith("service:")) return@synchronized true
        val serverId = session.key.schoolId.removePrefix("service:${pkg.manifest.id}:")
        !session.retired && sessions[session] == generation(pkg.manifest.id) &&
            selected(pkg.manifest.id, serverId) == session.key.accountKey
    }
    fun remove(pkg: PluginPackage, serverId: String, accountId: String) = synchronized(lock) {
        server(pkg, serverId)
        if (accountId != "default" && accounts(pkg.manifest.id, serverId).none { it.getString("id") == accountId }) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "服务账号不存在")
        advance(pkg.manifest.id)
        val legacyScope = "service:${pkg.manifest.id}:$serverId\u0000$accountId\u0000${pkg.manifest.id}\u0000${!pkg.official}"
        for (scope in listOf(legacyScope, legacyScope + "\u0000" + server(pkg, serverId).getString("origin"))) {
            NativePluginVault(app, scope).clear(); NativePluginFiles(app, scope).clear()
        }
        val profiles = prefs.getStringSet("retiredProfiles", emptySet()).orEmpty() + profile(pkg, serverId, accountId)
        check(prefs.edit().putString("accounts:${key(pkg.manifest.id, serverId)}", JSONArray(accounts(pkg.manifest.id, serverId).filter { it.getString("id") != accountId }).toString())
            .putString("selected:${key(pkg.manifest.id, serverId)}", if (accountId == selected(pkg.manifest.id, serverId)) UUID.randomUUID().toString() else selected(pkg.manifest.id, serverId))
            .putStringSet("retiredProfiles", profiles).commit())
        revision.value++
    }
    fun clearPlugin(pkg: PluginPackage) = synchronized(lock) {
        advance(pkg.manifest.id)
        val trackedScopes = prefs.getStringSet("scopes:${pkg.manifest.id}", emptySet()).orEmpty()
        for (scope in trackedScopes) { NativePluginVault(app, scope).clear(); NativePluginFiles(app, scope).clear() }
        val profiles = (prefs.getStringSet("retiredProfiles", emptySet()).orEmpty() + prefs.getStringSet("profiles:${pkg.manifest.id}", emptySet()).orEmpty()).toMutableSet()
        for (server in pkg.manifest.json.optJSONArray("servers")?.let(PluginJson::objects).orEmpty()) {
            val id = server.getString("id")
            (accounts(pkg.manifest.id, id).map { it.getString("id") } + "default" + selected(pkg.manifest.id, id)).distinct().forEach { account ->
                // Include current anonymous identities created after removing an account.
                val legacy = "service:${pkg.manifest.id}:$id\u0000$account\u0000${pkg.manifest.id}\u0000${!pkg.official}"
                for (scope in listOf(legacy, legacy + "\u0000" + server.getString("origin").trimEnd('/'))) {
                    NativePluginVault(app, scope).clear(); NativePluginFiles(app, scope).clear()
                }
                profiles.add(profile(pkg, id, account))
            }
            prefs.edit().remove("accounts:${key(pkg.manifest.id,id)}").remove("selected:${key(pkg.manifest.id,id)}").commit()
        }
        check(prefs.edit().putStringSet("retiredProfiles", profiles).remove("profiles:${pkg.manifest.id}").remove("scopes:${pkg.manifest.id}")
            .putLong("profile-epoch:${pkg.manifest.id}", prefs.getLong("profile-epoch:${pkg.manifest.id}", 0) + 1).commit())
        revision.value++
    }
    fun cleanRetiredProfiles() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { cleanRetiredProfiles() }; return
        }
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) return
        synchronized(lock) {
            val remaining = prefs.getStringSet("retiredProfiles", emptySet()).orEmpty().filter { name ->
                // In-use profiles are retried after the WebView is destroyed or on next startup.
                !runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(name) }.getOrDefault(false)
            }.toSet()
            check(prefs.edit().putStringSet("retiredProfiles", remaining).commit())
        }
    }
    companion object { internal val lock = Any(); private val sessions = java.util.WeakHashMap<AcademicSession, Long>(); val revision = MutableStateFlow(0L) }
}
