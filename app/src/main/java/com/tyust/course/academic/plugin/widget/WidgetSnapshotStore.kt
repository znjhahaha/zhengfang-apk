package com.tyust.course.academic.plugin.widget

import android.content.Context
import com.tyust.course.academic.plugin.PluginJson
import com.tyust.course.academic.plugin.PluginStorageScope
import com.tyust.course.academic.plugin.PluginWidgetContract
import com.tyust.course.academic.plugin.widgetInvalid
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Push caches belong to a declared view; pull caches belong to a configured launcher instance. */
object WidgetSnapshotStore {
    private const val ROOT = "plugin-widgets"

    data class Snapshot(
        val pluginId: String,
        val widgetId: String,
        val viewKey: String,
        val updatedAt: Long,
        val root: JSONObject,
    ) {
        val payload: JSONObject get() = root.optJSONObject("snapshot") ?: JSONObject()
    }

    @Synchronized
    internal fun save(context: Context, pluginId: String, widgetId: String, viewKey: String,
             payload: JSONObject, now: Long = System.currentTimeMillis(), provenance: WidgetSnapshotScope? = null): Snapshot {
        val scope = requireScope(context, pluginId, provenance)
        val legacy = legacyFile(context, pluginId, widgetId).takeIf {
            load(it, pluginId, widgetId, viewKey)?.root?.optInt("schema") == 1
        }
        val snapshot = persist(context, pluginId, widgetId, viewKey, payload, now,
            pushFile(context, pluginId, widgetId, viewKey), identity("push", scope), replacedLegacy = legacy)
        legacy?.delete()
        return snapshot
    }

    @Synchronized
    internal fun savePull(context: Context, binding: PluginWidgetBindings.Binding, declaration: PluginWidgetContract.Declaration,
                 payload: JSONObject, now: Long = System.currentTimeMillis(), provenance: WidgetSnapshotScope? = null): Snapshot {
        require(!declaration.push && declaration.id == binding.widgetId)
        val scope = requireScope(context, binding.pluginId, provenance)
        return persist(context, binding.pluginId, binding.widgetId, declaration.viewKey(binding.viewKey), payload, now,
            pullFile(context, binding), identity("pull", scope).put("appWidgetId", binding.appWidgetId)
                .put("configuration", configuration(binding, declaration)))
    }

    /** Only push snapshots can safely migrate from the old declaration-wide cache. */
    @Synchronized
    fun read(context: Context, pluginId: String, widgetId: String, viewKey: String = ""): Snapshot? {
        val target = pushFile(context, pluginId, widgetId, viewKey)
        if (runCatching { recover(target) }.isFailure) return null
        if (target.exists()) {
            val snapshot = load(target, pluginId, widgetId, viewKey)
            if (snapshot?.root?.optString("mode") == "push" && ownsCurrentScope(context, snapshot)) return snapshot
            delete(target)
        }
        val scope = WidgetSnapshotScope.current(context, pluginId) ?: return null
        val legacy = legacyFile(context, pluginId, widgetId)
        // Account-sensitive legacy caches never recorded ownership and cannot be assigned now.
        if (scope.kind != "plugin") { delete(legacy); return null }
        val snapshot = load(legacy, pluginId, widgetId, viewKey)?.takeIf { it.root.optInt("schema") == 1 } ?: return null
        return runCatching {
            val migrated = persist(context, pluginId, widgetId, viewKey, snapshot.payload, snapshot.updatedAt,
                target, identity("push", scope), replacedLegacy = legacy)
            legacy.delete()
            migrated
        }.getOrNull()
    }

    @Synchronized
    fun read(context: Context, binding: PluginWidgetBindings.Binding, declaration: PluginWidgetContract.Declaration): Snapshot? {
        val viewKey = declaration.viewKey(binding.viewKey)
        if (declaration.push) return read(context, binding.pluginId, binding.widgetId, viewKey)
        // Legacy pull snapshots lack instance and options provenance: refetch instead of guessing.
        delete(legacyFile(context, binding.pluginId, binding.widgetId))
        val file = pullFile(context, binding)
        val snapshot = load(file, binding.pluginId, binding.widgetId, viewKey)?.takeIf {
            it.root.optString("mode") == "pull" && it.root.optInt("appWidgetId") == binding.appWidgetId &&
                it.root.optString("configuration") == configuration(binding, declaration) && ownsCurrentScope(context, it)
        }
        if (snapshot == null) delete(file)
        return snapshot
    }

    private fun ownsCurrentScope(context: Context, snapshot: Snapshot): Boolean {
        val owner = WidgetSnapshotScope.stored(snapshot.root) ?: return false
        return WidgetSnapshotScope.current(context, snapshot.pluginId, owner)?.identity == owner.identity
    }

    private fun requireScope(context: Context, pluginId: String, expected: WidgetSnapshotScope?): WidgetSnapshotScope {
        val scope = WidgetSnapshotScope.current(context, pluginId, expected) ?: widgetInvalid("插件快照上下文已改变")
        if (expected != null && expected.identity != scope.identity) widgetInvalid("账号或插件快照上下文已改变")
        return scope
    }

    private fun identity(mode: String, scope: WidgetSnapshotScope) = JSONObject().put("mode", mode)
        .put("provenance", scope.identity).put("scopeKind", scope.kind).put("scopeServerId", scope.serverId)

    @Synchronized fun clearAcademic(context: Context) {
        File(context.filesDir, ROOT).listFiles().orEmpty().filter(File::isDirectory).forEach { directory ->
            directory.listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
                .map { if (it.name.endsWith(".bak")) File(it.parentFile, it.name.removeSuffix(".bak")) else it }.distinct()
                .filter { parse(it)?.optString("scopeKind") !in setOf("plugin", "service") }.forEach(::delete)
        }
    }

    internal fun configuration(binding: PluginWidgetBindings.Binding, declaration: PluginWidgetContract.Declaration): String =
        PluginJson.sha256(PluginJson.canonical(JSONObject()
            .put("pluginId", binding.pluginId).put("widgetId", binding.widgetId)
            .put("viewKey", declaration.viewKey(binding.viewKey)).put("providerId", declaration.providerId)
            .put("account", binding.account).put("boundAt", binding.boundAt)
            .put("options", PluginWidgetPull.options(binding, declaration))).toByteArray())

    @Synchronized fun clearPlugin(context: Context, pluginId: String) {
        dir(context, pluginId).deleteRecursively()
    }

    @Synchronized fun clearWidget(context: Context, pluginId: String, widgetId: String) {
        delete(legacyFile(context, pluginId, widgetId))
        files(context, pluginId).filter { parse(it)?.optString("widgetId") == widgetId }.forEach(::delete)
    }

    @Synchronized fun clearInstance(context: Context, binding: PluginWidgetBindings.Binding) {
        delete(pullFile(context, binding))
    }

    @Synchronized fun totalBytes(context: Context, pluginId: String): Long = files(context, pluginId).sumOf { it.length() }

    private fun persist(context: Context, pluginId: String, widgetId: String, viewKey: String, payload: JSONObject,
                        now: Long, file: File, identity: JSONObject, replacedLegacy: File? = null): Snapshot {
        val root = identity.put("schema", 2).put("pluginId", pluginId).put("widgetId", widgetId)
            .put("viewKey", viewKey).put("updatedAt", now).put("snapshot", payload)
        val bytes = root.toString().toByteArray()
        if (bytes.size > PluginWidgetContract.MAX_SNAPSHOT_BYTES) widgetInvalid("小组件快照超过 64 KiB")
        val remaining = totalBytes(context, pluginId) - file.length() - (replacedLegacy?.length() ?: 0)
        if (remaining + bytes.size > PluginWidgetContract.MAX_PLUGIN_BYTES) widgetInvalid("插件快照总量超过 256 KiB")
        file.parentFile?.mkdirs()
        write(file, bytes)
        return Snapshot(pluginId, widgetId, viewKey, now, root)
    }

    private fun load(file: File, pluginId: String, widgetId: String, viewKey: String): Snapshot? {
        val root = parse(file) ?: return null
        if (root.optInt("schema") !in 1..2 || root.optString("pluginId") != pluginId ||
            root.optString("widgetId") != widgetId || root.optString("viewKey") != viewKey || root.optJSONObject("snapshot") == null) return null
        return Snapshot(pluginId, widgetId, viewKey, root.optLong("updatedAt"), root)
    }
    private fun parse(file: File): JSONObject? = runCatching {
        recover(file)
        if (!file.exists() || file.length() > PluginWidgetContract.MAX_SNAPSHOT_BYTES) return null
        PluginJson.parse(file.readText(Charsets.UTF_8))
    }.getOrNull()

    /** renameTo cannot replace an existing target on Windows; retain the previous commit until done. */
    private fun write(file: File, bytes: ByteArray) {
        recover(file)
        val temp = File(file.parentFile, file.name + ".tmp")
        val backup = File(file.parentFile, file.name + ".bak")
        temp.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
        if (file.exists() && !file.renameTo(backup)) {
            temp.delete()
            throw IOException("Cannot back up widget snapshot")
        }
        if (!temp.renameTo(file)) {
            backup.renameTo(file)
            temp.delete()
            throw IOException("Cannot commit widget snapshot")
        }
        backup.delete()
    }

    private fun recover(file: File) {
        val backup = File(file.parentFile, file.name + ".bak")
        if (!file.exists() && backup.exists() && !backup.renameTo(file)) throw IOException("Cannot recover widget snapshot")
        if (file.exists()) backup.delete()
    }
    private fun delete(file: File) {
        file.delete()
        File(file.parentFile, file.name + ".bak").delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }
    private fun files(context: Context, pluginId: String): List<File> = dir(context, pluginId).listFiles().orEmpty()
        .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { if (it.name.endsWith(".bak")) File(it.parentFile, it.name.removeSuffix(".bak")) else it }.distinct()
        .filter { runCatching { recover(it); it.exists() }.getOrDefault(false) }
    private fun dir(context: Context, pluginId: String) =
        File(File(context.filesDir, ROOT), PluginStorageScope.hash("widget\u0000$pluginId"))
    private fun keyedFile(context: Context, pluginId: String, key: String) = File(dir(context, pluginId), PluginStorageScope.hash(key) + ".json")
    private fun legacyFile(context: Context, pluginId: String, widgetId: String) = keyedFile(context, pluginId, widgetId)
    private fun pushFile(context: Context, pluginId: String, widgetId: String, viewKey: String) = keyedFile(context, pluginId, "push\u0000$widgetId\u0000$viewKey")
    private fun pullFile(context: Context, binding: PluginWidgetBindings.Binding) = keyedFile(context, binding.pluginId, "pull\u0000${binding.widgetId}\u0000${binding.appWidgetId}")
}
