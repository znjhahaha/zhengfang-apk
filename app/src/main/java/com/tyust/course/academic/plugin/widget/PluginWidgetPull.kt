package com.tyust.course.academic.plugin.widget

import android.content.Context
import android.util.Log
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.plugin.AcademicProviderRegistry
import com.tyust.course.academic.plugin.NativeCapabilityHost
import com.tyust.course.academic.plugin.NativePluginRunner
import com.tyust.course.academic.plugin.PluginLegacyData
import com.tyust.course.academic.plugin.PluginPackage
import com.tyust.course.academic.plugin.PluginPages
import com.tyust.course.academic.plugin.PluginServiceAccounts
import com.tyust.course.academic.plugin.PluginWidgetContract
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pull-mode widget data.
 *
 * Launcher broadcasts never run plugin code: they only ask [PluginWidgetPullService] for a job. The
 * service resolves the plugin session once, calls `data.query` for every pull instance and stores the
 * mapped snapshot. A failed instance keeps its previous snapshot (and `updatedAt`), so the widget
 * degrades to a stale view instead of an error page.
 */
internal object PluginWidgetPull {
    private const val TAG = "PluginWidgetPull"
    private const val TIMEOUT_MS = 90_000L
    private const val MAX_ROWS = 6

    /**
     * Maps the `data.query` item convention of the widget template to a snapshot:
     * `{id,title,subtitle,time,badge,tone}` rows -> `snapshot.rows`. Rows without a title are dropped
     * and the trailing rows beyond the host limit are trimmed. The result still has to pass
     * [PluginWidgetContract.sanitize] (byte budget, control characters, timer bounds).
     */
    fun snapshotFromItems(
        declaration: PluginWidgetContract.Declaration,
        items: JSONArray,
        now: Long,
    ): JSONObject {
        val rows = JSONArray()
        for (index in 0 until items.length()) {
            if (rows.length() >= MAX_ROWS) break
            val item = items.optJSONObject(index) ?: continue
            val title = item.optString("title").trim()
            if (title.isEmpty()) continue
            val row = JSONObject()
                .put("id", item.optString("id").trim().ifBlank { "row-$index" })
                .put("title", title.take(60))
            item.optString("subtitle").trim().takeIf(String::isNotEmpty)?.let { row.put("subtitle", it.take(80)) }
            item.optString("time").trim().takeIf(String::isNotEmpty)?.let { row.put("time", it.take(24)) }
            item.optString("badge").trim().takeIf(String::isNotEmpty)?.let { row.put("badge", it.take(8)) }
            item.optString("tone").trim().takeIf(String::isNotEmpty)?.let { row.put("tone", it) }
            rows.put(row)
        }
        val snapshot = JSONObject()
            .put("schema", 1)
            .put("updatedAt", now)
            .put("header", JSONObject().put("title", declaration.title.take(40)))
            .put("rows", rows)
        if (rows.length() == 0) snapshot.put("empty", JSONObject().put("text", "暂无数据"))
        return snapshot
    }

    /** Called from launcher broadcasts; only enqueues work, never touches the plugin runtime. */
    fun requestForBindings(context: Context, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        appWidgetIds.toList().mapNotNull { PluginWidgetBindings.read(context, it) }.mapNotNull { binding ->
            val pkg = AcademicProviderRegistry.packages().active(binding.pluginId) ?: return@mapNotNull null
            if (!PluginPages.available(pkg)) return@mapNotNull null
            binding.pluginId.takeIf { PluginWidgetContract.declaration(pkg.manifest, binding.widgetId)?.push == false }
        }.distinct().forEach { PluginWidgetPullService.requestImmediate(context, it) }
    }

    /** Boot/time changes: every bound widget may need a fresh pull. */
    fun requestAll(context: Context) =
        requestForBindings(context, PluginWidgetBindings.all(context).map { it.appWidgetId }.toIntArray())

    /** Refreshes every pull instance of [pluginId]; never throws for a single widget failure. */
    suspend fun refreshPlugin(app: Context, pluginId: String) {
        val pkg = AcademicProviderRegistry.packages().active(pluginId)
        if (pkg == null || !PluginPages.available(pkg)) {
            PluginWidgetPullService.cancel(app, pluginId)
            return
        }
        val declarations = PluginWidgetContract.declarations(pkg.manifest).filter { !it.push }
        val instances = PluginWidgetBindings.forPlugin(app, pluginId).filter { binding ->
            declarations.any { it.id == binding.widgetId }
        }
        if (declarations.isEmpty() || instances.isEmpty()) {
            PluginWidgetPullService.cancel(app, pluginId)
            return
        }
        PluginWidgetPullService.schedule(app, pluginId)
        val accounts = PluginServiceAccounts(app)
        val session = session(app, pkg, accounts)
        val provenance = WidgetSnapshotScope.forSession(app, pkg, session)
        val user = UserManager.getInstance()
        val token = user.sessionState.token
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        fun active(): Boolean = job?.isActive == true &&
            user.sessionState.isCurrent(token) &&
            !session.retired &&
            PluginPages.available(pkg) &&
            AcademicProviderRegistry.isCurrentPackage(pkg.manifest.id, pkg.digest) &&
            accounts.current(pkg, session)
        try {
            if (!active()) return
            withTimeout(TIMEOUT_MS) {
                for (binding in instances) {
                    try {
                        pullOne(app, pkg, session, binding, provenance, ::active)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w(TAG, "pull failed plugin=$pluginId widget=${binding.widgetId}", t)
                    }
                }
            }
        } finally {
            session.retire()
        }
    }

    private suspend fun pullOne(
        app: Context,
        pkg: PluginPackage,
        session: AcademicSession,
        binding: PluginWidgetBindings.Binding,
        provenance: WidgetSnapshotScope,
        active: () -> Boolean,
    ) {
        val declaration = PluginWidgetContract.declaration(pkg.manifest, binding.widgetId) ?: return
        if (declaration.push || declaration.providerId.isBlank()) return
        val host = NativeCapabilityHost(app, pkg, session, null, active = active)
        try {
            val viewKey = declaration.viewKey(binding.viewKey)
            val args = JSONObject()
                .put("providerId", declaration.providerId)
                .put(
                    "input",
                    JSONObject()
                        .put("widgetId", binding.widgetId)
                        .put("viewKey", viewKey)
                        .put("appWidgetId", binding.appWidgetId)
                        .put("options", options(binding, declaration)),
                )
            val result = NativePluginRunner.invoke(
                app, pkg, session, "data.query", args,
                JSONObject().put("capabilities", host.capabilities()),
                confirmed = false, active = active,
            )
            val now = System.currentTimeMillis()
            val raw = snapshotFromItems(declaration, result.optJSONArray("items") ?: JSONArray(), now)
            val snapshot = PluginWidgetContract.sanitize(declaration, raw, now)
            storeResult(app, binding, declaration, snapshot, now, provenance, active)
        } finally {
            host.close()
        }
    }

    private fun session(app: Context, pkg: PluginPackage, accounts: PluginServiceAccounts): AcademicSession {
        val serverId = pkg.manifest.json.optJSONArray("servers")
            ?.takeIf { it.length() == 1 }?.optJSONObject(0)?.optString("id")?.takeIf(String::isNotBlank)
        if (serverId != null) return accounts.session(pkg, serverId)
        val user = UserManager.getInstance()
        return PluginLegacyData.session(app, pkg, user.currentSchool, user.currentAccountStorageKey)
    }

    /** `options` is the declared instance configuration with defaults filled in. */
    internal fun options(
        binding: PluginWidgetBindings.Binding,
        declaration: PluginWidgetContract.Declaration,
    ): JSONArray {
        val array = JSONArray()
        declaration.options.forEach { option ->
            val value = if (binding.options.has(option.id)) binding.options.opt(option.id) else option.default
            if (value != null && value != JSONObject.NULL) array.put(JSONObject().put("id", option.id).put("value", value))
        }
        return array
    }

    /** An awaited query must not revive a deleted, reconfigured or disabled widget. */
    internal fun storeResult(app: Context, binding: PluginWidgetBindings.Binding,
                             declaration: PluginWidgetContract.Declaration, snapshot: JSONObject,
                             now: Long, provenance: WidgetSnapshotScope? = null, active: () -> Boolean) {
        if (!active()) return
        val current = PluginWidgetBindings.read(app, binding.appWidgetId) ?: return
        if (WidgetSnapshotStore.configuration(current, declaration) != WidgetSnapshotStore.configuration(binding, declaration)) return
        WidgetSnapshotStore.savePull(app, binding, declaration, snapshot, now, provenance)
        PluginWidgets.refreshId(app, binding.appWidgetId)
        PluginWidgets.rescheduleCountdown(app)
    }
}
