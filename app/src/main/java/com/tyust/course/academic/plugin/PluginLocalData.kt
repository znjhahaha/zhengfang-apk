package com.tyust.course.academic.plugin

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Explicit local cleanup, separate from navigation and never an HTTP business operation. */
object PluginLocalData {
    private fun index(root: File) = AtomicFile(File(root, "scope-index.json"))
    internal fun trackStorage(root: File, pluginId: String, hash: String) = synchronized(PluginServiceAccounts.lock) {
        val file = index(root)
        val state = if (file.baseFile.exists()) PluginJson.parse(String(file.readFully())) else JSONObject()
        val scopes = state.optJSONArray(pluginId)?.let(PluginJson::strings).orEmpty().toSet()
        if (hash !in scopes) {
            root.mkdirs(); state.put(pluginId, JSONArray(scopes + hash))
            val out = file.startWrite()
            try { out.write(state.toString().toByteArray()); file.finishWrite(out) }
            catch (e: Exception) { file.failWrite(out); throw e }
        }
    }
    fun clear(app: Context, pkg: PluginPackage) {
        val id = pkg.manifest.id
        val references = PluginWorkflowJournal(PluginWorkflowFiles(app)).references()
        if (NativePluginTasks.busy(app, id) || (AcademicProviderRegistry.packages().lineage(id) + pkg.digest).any { it in references })
            throw PluginException(PluginErrorCode.CONFLICT, "请先完成或取消任务，并核对结果未知的步骤")
        PluginVersionLeases.whenIdle(id) {
            synchronized(PluginServiceAccounts.lock) {
                PluginServiceAccounts(app).clearPlugin(pkg)
                val root = File(app.filesDir, "academic-plugin-storage"); val file = index(root)
                val state = if (file.baseFile.exists()) PluginJson.parse(String(file.readFully())) else JSONObject()
                state.optJSONArray(id)?.let(PluginJson::strings).orEmpty().filter { it.matches(Regex("[a-f0-9]{64}")) }.forEach {
                    AtomicFile(File(root, "$it.json")).delete()
                }
                for (name in listOf("plugin-settings", "native-plugin-permissions", "plugin-data-scopes")) {
                    val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE); val edit = prefs.edit()
                    prefs.all.keys.filter { it.startsWith("$id/") || it.startsWith("$id:") }.forEach(edit::remove)
                    check(edit.commit())
                }
            }
            true
        } ?: throw PluginException(PluginErrorCode.CONFLICT, "请关闭此插件页面后再清理本地数据")
        PluginServiceAccounts(app).cleanRetiredProfiles()
        PluginPages.refresh()
    }
}
