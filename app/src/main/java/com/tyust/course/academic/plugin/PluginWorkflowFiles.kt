package com.tyust.course.academic.plugin

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

class PluginWorkflowFiles(context: Context) : PluginWorkflowStore {
    private val root = File(context.noBackupFilesDir, "plugin-workflows")
    private fun file(id: String): AtomicFile {
        if (!id.matches(Regex("wf_[a-f0-9-]{36}"))) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "无效工作流 ID")
        return AtomicFile(File(root, "$id.json"))
    }
    override fun read(id: String): JSONObject? = file(id).takeIf { it.baseFile.exists() }?.let { PluginJson.parse(String(it.readFully())) }
    override fun write(id: String, record: JSONObject) {
        root.mkdirs(); val file = file(id); val output = file.startWrite()
        try { output.write(record.toString().toByteArray()); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }
    override fun list(): List<JSONObject> = root.listFiles().orEmpty().filter { it.extension == "json" }.map { PluginJson.parse(String(AtomicFile(it).readFully())) }
}

object PluginVersionLeases {
    private val counts = mutableMapOf<String, Int>()
    @Synchronized fun acquire(pluginId: String): AutoCloseable {
        counts[pluginId] = (counts[pluginId] ?: 0) + 1
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) synchronized(this) {
            val count = counts[pluginId] ?: 0; if (count <= 1) counts.remove(pluginId) else counts[pluginId] = count - 1
        } }
    }
    @Synchronized fun busy(pluginId: String): Boolean = (counts[pluginId] ?: 0) > 0
    @Synchronized fun <T> whenIdle(pluginId: String, action: () -> T): T? = if (busy(pluginId)) null else action()
}
