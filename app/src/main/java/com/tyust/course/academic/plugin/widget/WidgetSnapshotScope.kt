package com.tyust.course.academic.plugin.widget

import android.content.Context
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import com.tyust.course.academic.plugin.*
import com.tyust.course.manager.UserManager
import org.json.JSONObject

/** Internal ownership, independent of the launcher's optional account text. */
internal data class WidgetSnapshotScope(val identity: String, val kind: String, val serverId: String = "") {
    companion object {
        fun current(app: Context, pluginId: String, owner: WidgetSnapshotScope? = null): WidgetSnapshotScope? {
            val pkg = runCatching { AcademicProviderRegistry.packages().active(pluginId) }.getOrNull() ?: return null
            val servers = pkg.manifest.json.optJSONArray("servers")?.let(PluginJson::objects).orEmpty()
            val server = when (owner?.kind) {
                "service" -> if (owner.serverId.isBlank()) servers.singleOrNull() else servers.singleOrNull { it.optString("id") == owner.serverId }
                "plugin", "academic" -> null // An explicit plugin-wide owner never borrows a service account.
                null -> servers.singleOrNull()
                else -> return null
            }
            if (owner?.kind == "service" && server == null) return null
            val user = UserManager.getInstance()
            val session = if (server != null) AcademicSession(
                AcademicSessionKey("service:$pluginId:${server.getString("id")}", PluginServiceAccounts(app).selected(pluginId, server.getString("id"))),
                server.getString("origin"),
            ) else PluginLegacyData.session(app, pkg, user.currentSchool, user.currentAccountStorageKey)
            return try { forSession(app, pkg, session).takeIf { owner == null || it.kind == owner.kind } } finally { session.retire() }
        }

        fun stored(root: JSONObject): WidgetSnapshotScope? {
            val identity = root.optString("provenance")
            val kind = root.optString("scopeKind")
            if (!identity.matches(Regex("[0-9a-f]{64}")) || kind !in setOf("service", "academic", "plugin")) return null
            return WidgetSnapshotScope(identity, kind, root.optString("scopeServerId"))
        }

        fun forSession(app: Context, pkg: PluginPackage, session: AcademicSession): WidgetSnapshotScope {
            val kind = when {
                session.key.schoolId.startsWith("service:${pkg.manifest.id}:") -> "service"
                session.key.schoolId == "plugin:${pkg.manifest.id}" && session.key.accountKey == "default" -> "plugin"
                else -> "academic"
            }
            val data = JSONObject().put("kind", kind).put("plugin", pkg.manifest.id).put("package", pkg.digest)
                .put("development", !pkg.official).put("school", session.key.schoolId)
                .put("account", session.key.accountKey).put("baseUrl", session.baseUrl)
            when (kind) {
                // SharedPreferences reads do not acquire the service-account mutation lock.
                "service" -> data.put("generation", app.getSharedPreferences("plugin-service-accounts", Context.MODE_PRIVATE)
                    .getLong("generation:${pkg.manifest.id}", 0))
                "academic" -> {
                    val user = UserManager.getInstance()
                    val token = user.sessionState.token
                    data.put("accountStorageKey", token.accountStorageKey).put("generation", token.generation)
                        .put("configuration", user.currentSchool?.toJson() ?: JSONObject())
                }
            }
            val serverId = if (kind == "service") session.key.schoolId.removePrefix("service:${pkg.manifest.id}:") else ""
            return WidgetSnapshotScope(PluginJson.sha256(PluginJson.canonical(data).toByteArray()), kind, serverId)
        }
    }
}
