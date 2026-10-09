package com.tyust.course.academic.plugin

import android.content.Context
import android.util.AtomicFile
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A grant is immutable, local to one running task and never handed to plugin JavaScript. */
internal class NativeForegroundGrant(declaration: JSONObject, input: JSONObject, private val active: () -> Boolean) {
    private val rules = PluginJson.objects(JSONObject(declaration.toString()).getJSONArray("mutations"))
    private val selection = JSONObject(input.toString())
    fun requireRequest(url: HttpUrl, method: String, purpose: String, form: JSONObject?) {
        if (!active()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "持续任务的账号、版本或授权已失效")
        if (purpose != "mutation") return
        val allowed = url.isHttps && url.port == 443 && url.username.isBlank() && url.password.isBlank() && rules.any { rule ->
            val prefix = rule.getString("pathPrefix")
            val scope = rule.getJSONObject("scope")
            val parameters = scope.getJSONObject("parameters")
            url.host == rule.getString("host") && (url.encodedPath == prefix || url.encodedPath.startsWith(prefix.trimEnd('/') + "/")) &&
                PluginJson.objects(selection.optJSONArray(scope.getString("inputList")) ?: JSONArray()).any { item ->
                    parameters.keys().asSequence().all { parameter ->
                        val expected = item.optString(parameters.getString(parameter)).takeIf { it.isNotBlank() }
                        val query = url.queryParameterValues(parameter)
                        val actual = if (form?.has(parameter) == true) form.optString(parameter).takeIf { query.isEmpty() } else query.singleOrNull()
                        expected != null && expected == actual
                    }
                }
        }
        if (!allowed) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "提交超出本次选择的课程或任务端点")
    }
}

internal object NativeForegroundTasks {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<String, Job>()
    private val discarded = ConcurrentHashMap.newKeySet<String>()
    private var initialized = false
    private fun root(app: Context) = File(app.noBackupFilesDir, "native-continuous-tasks").apply { mkdirs() }
    private fun path(app: Context, handle: String): AtomicFile {
        require(handle.matches(Regex("t[a-f0-9]{32}"))) { "任务句柄无效" }
        return AtomicFile(File(root(app), "$handle.json"))
    }
    private fun records(app: Context) = root(app).listFiles()?.filter { it.extension == "json" }?.mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }.orEmpty()
    private fun save(app: Context, record: JSONObject) = synchronized(lock) {
        if (record.getString("handle") in discarded) return@synchronized
        val atomic = path(app, record.getString("handle")); val stream = atomic.startWrite()
        try { stream.write(record.toString().toByteArray()); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e }
        PluginForegroundWork.changed()
    }
    private fun load(app: Context, handle: String): JSONObject = synchronized(lock) { JSONObject(String(path(app, handle).readFully())) }
    private fun updateIfPresent(app: Context, handle: String, change: (JSONObject) -> Unit) = synchronized(lock) {
        if (handle !in discarded && path(app, handle).baseFile.exists()) save(app, load(app, handle).also(change))
    }
    fun clear(app: Context, namespace: String) = synchronized(lock) {
        records(app).filter { it.optString("namespace") == namespace }.forEach { record ->
            val handle = record.getString("handle")
            if (running.containsKey(handle)) discarded.add(handle)
            running[handle]?.cancel()
            path(app, handle).delete()
            PluginForegroundWork.remove(app, handle)
        }
    }
    private fun initialize(app: Context) = synchronized(lock) {
        if (initialized) return@synchronized
        initialized = true
        records(app).filter { it.optString("status") == "running" }.forEach { save(app, it.put("status", "interrupted").put("message", "上次运行被系统中断，恢复前将核对平台状态")) }
    }
    fun list(app: Context, namespace: String): JSONArray = synchronized(lock) {
        initialize(app)
        JSONArray(records(app).filter { it.optString("namespace") == namespace }.sortedByDescending { it.optLong("startedAt") }.take(8).map {
            JSONObject(it.toString()).apply { remove("namespace"); remove("digest"); remove("accountId") }
        })
    }
    suspend fun start(app: Context, pkg: PluginPackage, owner: AcademicSession, namespace: String, input: JSONObject): JSONObject {
        initialize(app)
        val task = NativePluginContract.contribution(pkg.manifest, "tasks", input.getString("taskId"))
        val declaration = task.optJSONObject("foreground") ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "此任务未声明持续运行")
        val accounts = PluginServiceAccounts(app)
        val serverId = declaration.getString("serverId")
        if (owner.key.schoolId != "service:${pkg.manifest.id}:$serverId" || !accounts.current(pkg, owner)) throw PluginException(PluginErrorCode.STALE_CONTEXT, "请从对应服务账号启动任务")
        if (PluginJson.canonical(input.get("state")).toByteArray().size > PluginLimits.STATE_BYTES || input.getJSONObject("input").toString().toByteArray().size > 65536) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "持续任务状态过大")
        val batchKey = declaration.optString("batchInputKey")
        val batch = if (batchKey.isNotBlank()) PluginJson.objects(input.getJSONObject("input").optJSONArray(batchKey) ?: JSONArray()) else listOf(JSONObject())
        if (batch.size !in 1..20) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "请选择 1–20 门课程")
        val handle = "t" + UUID.randomUUID().toString().replace("-", "")
        synchronized(lock) {
            val mine = records(app).filter { it.optString("namespace") == namespace }
            if (mine.count { it.optString("status") == "running" } >= 4 || mine.any { it.optString("status") == "running" && it.optString("taskId") == task.getString("id") }) throw PluginException(PluginErrorCode.CONFLICT, "这个任务已经在运行")
            mine.filter { it.optString("status") != "running" }.sortedByDescending { it.optLong("startedAt") }.drop(3).forEach { path(app, it.getString("handle")).delete() }
            save(app, JSONObject().put("handle", handle).put("namespace", namespace).put("pluginId", pkg.manifest.id).put("digest", pkg.digest)
                .put("accountId", owner.key.accountKey).put("taskId", task.getString("id")).put("input", input.get("input")).put("state", input.get("state"))
                .put("status", "running").put("startedAt", System.currentTimeMillis()).put("updatedAt", System.currentTimeMillis()))
        }
        try {
            withContext(Dispatchers.Main) { PluginForegroundWork.add(app, handle, PluginForegroundWork.Entry(pkg.manifest.id, task.getString("title")) { reason -> stop(app, namespace, handle, reason) }) }
        } catch (e: Exception) { updateIfPresent(app, handle) { it.put("status", "failed") }; throw e }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var ownedSession: AcademicSession? = null
            var lease: AutoCloseable? = null
            var ownedHost: NativeCapabilityHost? = null
            var flow = NativeFlow(false)
            try {
                val session = accounts.session(pkg, serverId).also { ownedSession = it }
                lease = PluginVersionLeases.acquire(pkg.manifest.id)
                fun active() = currentCoroutineContextSafe(handle) && handle !in discarded && !session.retired && accounts.current(pkg, session) && owner.key.accountKey == session.key.accountKey &&
                    AcademicProviderRegistry.isCurrentPackage(pkg.manifest.id, pkg.digest) && AcademicProviderRegistry.isEnabled(pkg.manifest.id) &&
                    runCatching { load(app, handle).optString("status") == "running" }.getOrDefault(false)
                val grant = NativeForegroundGrant(declaration, input.getJSONObject("input"), ::active)
                val host = NativeCapabilityHost(app, pkg, session, null, foregroundGrant = grant, active = ::active).also { ownedHost = it }
                while (active()) {
                    val cycleStart = android.os.SystemClock.elapsedRealtime()
                    for ((index, item) in batch.withIndex()) {
                        if (!active()) break
                        flow = NativeFlow(false)
                        var record = load(app, handle)
                        var state: Any = record.get("state")
                        val pending = ArrayDeque<JSONObject>().apply { add(JSONObject().put("type", "lifecycle").put("name", "foreground.tick").put("value", JSONObject().put("item", item).put("index", index).put("handle", handle).put("at", System.currentTimeMillis()))) }
                        var uncertain = false
                        while (pending.isNotEmpty() && active()) {
                            val args = JSONObject().put("taskId", task.getString("id")).put("input", input.get("input")).put("state", state).put("event", pending.removeFirst())
                            val result = NativePluginRunner.invoke(app, pkg, session, "task.run", args, JSONObject().put("capabilities", host.capabilities()), active = ::active)
                            state = result.get("state")
                            record = load(app, handle).put("state", state).put("updatedAt", System.currentTimeMillis())
                            save(app, record)
                            // Deliver an unknown result once, persist the reducer's reconciliation state,
                            // then give the next tick a fresh bounded flow. Never replay writes here.
                            if (uncertain) break
                            for (effect in PluginJson.objects(result.getJSONArray("effects"))) {
                                flow.accept(effect.getString("id"))
                                val response = try { PluginJson.success(host.execute(effect, flow)) } catch (e: CancellationException) { throw e }
                                catch (e: Exception) {
                                    val code = (e as? PluginException)?.code ?: PluginErrorCode.NETWORK_RETRYABLE
                                    if (code == PluginErrorCode.RESULT_UNKNOWN) { flow.markUnknown(); uncertain = true }
                                    PluginJson.error(code, if (e is PluginException) e.message.orEmpty() else "网络暂不可用")
                                }
                                pending.add(JSONObject().put("type", "effect.result").put("effectId", effect.getString("id")).put("result", response))
                                if (uncertain) break
                            }
                        }
                        val due = cycleStart + declaration.getLong("intervalSeconds") * 1000 * (index + 1) / batch.size
                        delay((due - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1000))
                    }
                }
            } catch (e: CancellationException) {
                if (flow.failureCode(PluginErrorCode.CANCELLED) == PluginErrorCode.RESULT_UNKNOWN) updateIfPresent(app, handle) { it.put("status", "result_unknown") }
            } catch (e: Exception) {
                updateIfPresent(app, handle) { it.put("status", "failed").put("message", if (e is PluginException) e.message.orEmpty() else "任务中断，请重新打开后核对状态") }
            }
            finally {
                ownedHost?.close(); ownedSession?.retire(); lease?.close(); running.remove(handle)
                updateIfPresent(app, handle) { if (it.optString("status") == "running") it.put("status", "interrupted") }
                discarded.remove(handle)
                withContext(NonCancellable + Dispatchers.Main) { PluginForegroundWork.remove(app, handle) }
            }
        }
        running[handle] = job; job.start()
        return JSONObject().put("handle", handle)
    }
    private fun currentCoroutineContextSafe(handle: String) = running[handle]?.isActive == true
    fun stop(app: Context, namespace: String, handle: String, reason: String = "stopped") {
        synchronized(lock) {
            if (handle in discarded || !path(app, handle).baseFile.exists()) return
            val record = load(app, handle)
            if (record.getString("namespace") != namespace) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "任务不属于当前账号")
            save(app, record.put("status", reason)); running[handle]?.cancel()
        }
        PluginForegroundWork.remove(app, handle)
    }
}
