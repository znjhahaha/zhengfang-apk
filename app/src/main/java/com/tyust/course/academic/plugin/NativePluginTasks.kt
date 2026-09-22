package com.tyust.course.academic.plugin

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.AtomicFile
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object NativePluginTasks {
    private val lock = Any()
    private fun root(app: Context) = File(app.noBackupFilesDir, "native-plugin-tasks").apply { mkdirs() }
    private fun path(app: Context, handle: String): AtomicFile {
        require(handle.matches(Regex("[a-f0-9]{32}"))) { "任务句柄无效" }
        return AtomicFile(File(root(app), "$handle.json"))
    }
    fun load(app: Context, handle: String): JSONObject = synchronized(lock) { PluginJson.parse(String(path(app, handle).readFully())) }
    private fun save(app: Context, handle: String, record: JSONObject) {
        val file = path(app, handle); val stream = file.startWrite()
        try { stream.write(record.toString().toByteArray()); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    private fun records(app: Context) = root(app).listFiles()?.filter { it.extension == "json" }?.mapNotNull { runCatching { PluginJson.parse(it.readText()) }.getOrNull() }.orEmpty()
    fun schedule(app: Context, pkg: PluginPackage, session: AcademicSession, namespace: String, input: JSONObject): JSONObject = synchronized(lock) {
        if (PluginJson.canonical(input.get("state")).toByteArray().size > PluginLimits.STATE_BYTES || PluginJson.canonical(input.get("input")).toByteArray().size > 65536) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "任务状态过大")
        val mine = records(app).filter { it.optString("namespace") == namespace }
        if (mine.count { it.optString("status") in setOf("scheduled", "running") } >= 16) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "最多安排 16 个任务")
        val handle = UUID.randomUUID().toString().replace("-", "")
        val used = app.getSystemService(JobScheduler::class.java).allPendingJobs.map { it.id }.toSet()
        val jobId = (500_000..600_000).firstOrNull { it !in used } ?: throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "无法分配任务")
        val whenMillis = System.currentTimeMillis() + input.getLong("delaySeconds") * 1000
        val record = JSONObject().put("handle", handle).put("namespace", namespace).put("pluginId", pkg.manifest.id).put("digest", pkg.digest)
            .put("schoolKey", session.key.schoolId).put("accountKey", session.key.accountKey).put("baseUrl", session.baseUrl)
            .put("taskId", input.getString("taskId")).put("input", input.get("input")).put("state", input.get("state"))
            .put("jobId", jobId).put("scheduledAt", whenMillis).put("status", "scheduled")
        save(app, handle, record)
        val job = JobInfo.Builder(jobId, ComponentName(app, NativePluginJobService::class.java)).setMinimumLatency(input.getLong("delaySeconds") * 1000)
            .setPersisted(true).setExtras(PersistableBundle().apply { putString("handle", handle) }).build()
        if (app.getSystemService(JobScheduler::class.java).schedule(job) != JobScheduler.RESULT_SUCCESS) { path(app, handle).delete(); throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "系统未能安排后台任务") }
        mine.filter { it.optString("status") !in setOf("scheduled", "running") }.sortedByDescending { it.optLong("scheduledAt") }.drop(15).forEach { path(app, it.getString("handle")).delete() }
        JSONObject().put("handle", handle)
    }
    fun list(app: Context, namespace: String): JSONArray = synchronized { JSONArray(records(app).filter { it.optString("namespace") == namespace }.sortedByDescending { it.optLong("scheduledAt") }.take(16).map {
        JSONObject().put("handle", it.getString("handle")).put("taskId", it.getString("taskId")).put("status", it.getString("status")).put("scheduledAt", it.getLong("scheduledAt"))
    }) }
    private inline fun <T> synchronized(block: () -> T): T = kotlin.synchronized(lock, block)
    fun status(app: Context, handle: String, value: String) = synchronized {
        val record = load(app, handle)
        if (record.getString("status") == "cancelled" && value != "result_unknown") return@synchronized
        record.put("status", value); save(app, handle, record)
    }
    fun cancel(app: Context, namespace: String, handle: String) = synchronized {
        val record = load(app, handle)
        if (record.getString("namespace") != namespace) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "不能取消其他账号的任务")
        app.getSystemService(JobScheduler::class.java).cancel(record.getInt("jobId")); status(app, handle, "cancelled")
    }
}

class NativePluginJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val handle = params.extras.getString("handle") ?: return false
        running[params.jobId] = scope.launch {
            var host: NativeCapabilityHost? = null
            val flow = NativeFlow(false)
            try {
                val record = NativePluginTasks.load(this@NativePluginJobService, handle)
                // A process may have died after sending a write. Never restart that flow automatically.
                if (record.getString("status") != "scheduled") { if (record.getString("status") == "running") NativePluginTasks.status(this@NativePluginJobService, handle, "result_unknown"); return@launch }
                NativePluginTasks.status(this@NativePluginJobService, handle, "running")
                val pkg = AcademicProviderRegistry.packages().readDigest(record.getString("digest"))
                val session = AcademicSession(AcademicSessionKey(record.getString("schoolKey"), record.getString("accountKey")), record.getString("baseUrl"))
                val job = currentCoroutineContext().job
                host = NativeCapabilityHost(this@NativePluginJobService, pkg, session, null) { job.isActive }
                var state: Any = record.get("state")
                val pending = ArrayDeque<JSONObject?>().apply { add(null) }
                while (pending.isNotEmpty()) {
                    ensureActive()
                    val event = pending.removeFirst()
                    val args = JSONObject().put("taskId", record.getString("taskId")).put("input", record.get("input")).put("state", state).apply { if (event != null) put("event", event) }
                    val result = NativePluginRunner.invoke(this@NativePluginJobService, pkg, session, "task.run", args, JSONObject().put("capabilities", host.capabilities())) { job.isActive }
                    state = result.get("state")
                    for (effect in PluginJson.objects(result.getJSONArray("effects"))) {
                        flow.accept(effect.getString("id"))
                        val response = try { PluginJson.success(host.execute(effect, flow)) }
                        catch (e: PluginException) { if (e.code == PluginErrorCode.RESULT_UNKNOWN) flow.markUnknown(); PluginJson.error(e.code, e.message.orEmpty()) }
                        pending.add(JSONObject().put("type", "effect.result").put("effectId", effect.getString("id")).put("result", response))
                    }
                }
                NativePluginTasks.status(this@NativePluginJobService, handle, if (flow.failureCode(PluginErrorCode.CANCELLED) == PluginErrorCode.RESULT_UNKNOWN) "result_unknown" else "completed")
            } catch (e: CancellationException) { runCatching { NativePluginTasks.status(this@NativePluginJobService, handle, if (flow.failureCode(PluginErrorCode.CANCELLED) == PluginErrorCode.RESULT_UNKNOWN) "result_unknown" else "cancelled") } }
            catch (e: Exception) { runCatching { NativePluginTasks.status(this@NativePluginJobService, handle, if (e is PluginException && e.code == PluginErrorCode.RESULT_UNKNOWN) "result_unknown" else "failed") } }
            finally { host?.close(); running.remove(params.jobId); withContext(NonCancellable + Dispatchers.Main) { jobFinished(params, false) } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running.remove(params.jobId)?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
