package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.plugin.runtime.PluginSandboxClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

object NativePluginRunner {
    suspend fun invoke(app: Context, pkg: PluginPackage, session: AcademicSession, method: String, args: JSONObject, context: JSONObject, active: () -> Boolean): JSONObject {
        if (method !in pkg.manifest.capabilities) throw PluginException(PluginErrorCode.UNSUPPORTED, "插件未实现此接口")
        when (method) {
            "ui.init", "ui.reduce" -> NativePluginContract.page(pkg.manifest, args.getString("pageId"))
            "task.run" -> NativePluginContract.contribution(pkg.manifest, "tasks", args.getString("taskId"))
            "data.query" -> NativePluginContract.contribution(pkg.manifest, "dataProviders", args.getString("providerId"))
        }
        val operation = PluginOperation(session, pkg.manifest, method, development = !pkg.official, pageContext = context, packageDigest = pkg.digest, scopeStillActive = active)
        val host = PluginHost(operation, File(app.filesDir, "academic-plugin-storage"))
        val result = PluginSandboxClient(app).execute(pkg.source, args, operation, host)
        val schema = PluginSchema(PluginJson.parse(app.assets.open("academic-plugin/contract.schema.json").bufferedReader().use { it.readText() }))
        return schema.response(method, result).also { if (method != "data.query") NativePluginContract.validateResult(it, method == "task.run") }
    }
}

data class NativeUiSnapshot(val pageId: String = "", val instance: String = "", val view: JSONObject? = null, val busy: Boolean = false, val error: String = "", val errorCode: String = "")

/** Reducers run sequentially. Effects leave QuickJS and return as events to the same page instance. */
class NativeUiSession(
    private val app: Context, val pkg: PluginPackage, private val session: AcademicSession,
    val host: NativeCapabilityHost, private val active: () -> Boolean
) {
    private data class Pending(val instance: String, val event: JSONObject, val flow: NativeFlow, val init: Boolean = false, val params: JSONObject = JSONObject())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = Channel<Pending>(64)
    private val queuedInputs = mutableMapOf<String, Pending>()
    private val effects = mutableMapOf<String, Job>()
    private val mutableSnapshot = MutableStateFlow(NativeUiSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private var state: Any? = JSONObject()
    private var closed = false
    init {
        host.cancelEffects = { ids -> ids.forEach { effects[it]?.cancel() } }
        scope.launch {
            for (pending in events) {
                if (!isCurrent(pending.instance)) continue
                queuedInputs.entries.removeAll { it.value === pending }
                val before = mutableSnapshot.value
                mutableSnapshot.value = before.copy(busy = true)
                try {
                    val args = JSONObject().put("pageId", before.pageId)
                    if (pending.init) args.put("params", pending.params) else args.put("state", state ?: JSONObject.NULL).put("event", pending.event)
                    val context = JSONObject().put("pageId", before.pageId).put("pageInstance", before.instance).put("capabilities", host.capabilities())
                    val result = withContext(Dispatchers.IO) { NativePluginRunner.invoke(app, pkg, session, if (pending.init) "ui.init" else "ui.reduce", args, context) { isCurrent(pending.instance) } }
                    if (!isCurrent(pending.instance)) continue
                    state = result.get("state")
                    mutableSnapshot.value = before.copy(view = result.getJSONObject("view"), busy = false, error = "", errorCode = "")
                    for (effect in PluginJson.objects(result.getJSONArray("effects"))) runEffect(effect, pending)
                } catch (error: CancellationException) { if (!scope.isActive) throw error }
                catch (error: Exception) { if (isCurrent(pending.instance)) showError(error) }
            }
        }
    }
    fun open(pageId: String, params: JSONObject = JSONObject()) {
        NativePluginContract.page(pkg.manifest, pageId)
        host.requireCompatible()
        effects.values.toList().forEach(Job::cancel); effects.clear(); queuedInputs.clear()
        while (events.tryReceive().isSuccess) { /* Retire queued events from the old page. */ }
        state = JSONObject()
        val instance = UUID.randomUUID().toString()
        mutableSnapshot.value = NativeUiSnapshot(pageId, instance, busy = true)
        if (!events.trySend(Pending(instance, JSONObject(), NativeFlow(false), init = true, params = params)).isSuccess) showError(PluginException(PluginErrorCode.RESOURCE_LIMIT, "页面请求过多"))
    }
    fun event(instance: String, event: JSONObject, userGesture: Boolean) {
        if (!isCurrent(instance)) return
        val nodeId = event.optString("nodeId")
        if (nodeId.isNotBlank()) {
            val node = mutableSnapshot.value.view?.let { NativePluginContract.findNode(it, nodeId) } ?: return
            if (!node.optBoolean("enabled", true) || event.optString("name") !in setOf(node.optString("event"), node.optString("onEnd"))) return
        }
        if (event.optString("type") == "input") queuedInputs[nodeId]?.let { it.event.put("value", event.get("value")); return }
        val pending = Pending(instance, event, NativeFlow(userGesture))
        if (events.trySend(pending).isSuccess) { if (event.optString("type") == "input") queuedInputs[nodeId] = pending }
        else showError(PluginException(PluginErrorCode.RESOURCE_LIMIT, "操作过快，请稍后重试"))
    }
    fun menu(action: JSONObject) {
        val page = action.optString("pageId")
        if (page.isNotBlank() && page != mutableSnapshot.value.pageId) { open(page); return }
        event(mutableSnapshot.value.instance, JSONObject().put("type", "click").put("name", action.getString("event")), true)
    }
    private fun runEffect(effect: JSONObject, pending: Pending) {
        if (!isCurrent(pending.instance)) return
        val id = effect.getString("id")
        try {
            pending.flow.accept(id)
            if (effects[id]?.isActive == true) throw PluginException(PluginErrorCode.CONFLICT, "同一效果仍在运行")
        } catch (error: Exception) { showError(error); return }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val response = try { PluginJson.success(host.execute(effect, pending.flow)) }
            catch (error: Exception) {
                val code = pending.flow.failureCode(when (error) { is TimeoutCancellationException -> PluginErrorCode.TIMEOUT; is CancellationException -> PluginErrorCode.CANCELLED; is PluginException -> error.code; else -> PluginErrorCode.VALIDATION_FAILED })
                if (code == PluginErrorCode.RESULT_UNKNOWN) pending.flow.markUnknown()
                PluginJson.error(code, if (error is PluginException) error.message.orEmpty() else when (code) { PluginErrorCode.RESULT_UNKNOWN -> "请求可能已提交，请先核实结果"; PluginErrorCode.CANCELLED -> "已取消"; PluginErrorCode.TIMEOUT -> "等待超时"; else -> "宿主操作未完成" })
            }
            if (isCurrent(pending.instance)) withContext(NonCancellable) {
                // A cancellation result must survive cancellation of its own effect job.
                // Backpressure preserves completed effects when input events fill the queue.
                try { events.send(Pending(pending.instance,
                    JSONObject().put("type", "effect.result").put("effectId", id).put("result", response), pending.flow)) }
                catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) { }
            }
        }
        effects[id] = job
        job.invokeOnCompletion { scope.launch { if (effects[id] === job) effects.remove(id) } }
        job.start()
    }
    private fun showError(error: Exception) {
        mutableSnapshot.value = mutableSnapshot.value.copy(busy = false, error = error.message ?: "插件暂时不可用", errorCode = (error as? PluginException)?.code?.name ?: "RUNTIME_EXITED")
    }
    private fun isCurrent(instance: String): Boolean = !closed && active() && !session.retired && mutableSnapshot.value.instance == instance
    fun close() { closed = true; events.close(); effects.values.toList().forEach(Job::cancel); scope.cancel(); host.close(); session.retire() }
}
