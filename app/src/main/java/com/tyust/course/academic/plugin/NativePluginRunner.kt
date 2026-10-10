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
    suspend fun invoke(app: Context, pkg: PluginPackage, session: AcademicSession, method: String, args: JSONObject, context: JSONObject, confirmed: Boolean = false, active: () -> Boolean): JSONObject {
        if (method !in pkg.manifest.capabilities) throw PluginException(PluginErrorCode.UNSUPPORTED, "插件未实现此接口")
        when (method) {
            "ui.init", "ui.reduce" -> NativePluginContract.page(pkg.manifest, args.getString("pageId"))
            "task.run" -> NativePluginContract.contribution(pkg.manifest, "tasks", args.getString("taskId"))
            "data.query" -> NativePluginContract.contribution(pkg.manifest, "dataProviders", args.getString("providerId"))
            "command.run" -> NativePluginContract.contribution(pkg.manifest, "commands", args.getString("commandId"))
        }
        val lease = PluginVersionLeases.acquire(pkg.manifest.id)
        val pageContext = JSONObject(context.toString()).put("settings", PluginSettings.values(app, pkg))
        val operation = PluginOperation(session, pkg.manifest, method, development = !pkg.official, confirmed = confirmed, actionId = args.optString("name"), pageContext = pageContext, packageDigest = pkg.digest, scopeStillActive = active)
        try {
        operation.requireActive()
        val host = PluginHost(operation, File(app.filesDir, "academic-plugin-storage"), PluginWebSessionCookies.jar(app, pkg, session, active), dataGuard = PluginDataGuard(app, pkg))
        val result = PluginSandboxClient(app).execute(pkg.source, args, operation, host)
        operation.requireActive()
        val schema = PluginContractCache.schema(app)
        return schema.response(method, result).also {
            if (method in setOf("ui.init", "ui.reduce", "task.run")) NativePluginContract.validateResult(it, method == "task.run")
            if (it.has("navigation")) {
                if (pkg.manifest.json.optInt("minAppVersionCode") < 114 ||
                    pkg.manifest.json.optJSONArray("requires")?.let(PluginJson::objects).orEmpty().none { requirement -> requirement.optString("name") == "ui.navigation" && requirement.optInt("version") >= 1 })
                    throw PluginException(PluginErrorCode.VALIDATION_FAILED, "页面导航需要声明 ui.navigation 及 App 1.0.114")
                NativePageNavigation.from(it)
            }
        }
        } finally { operation.close(); lease.close() }
    }
}

data class NativeUiSnapshot(val pageId: String = "", val instance: String = "", val view: JSONObject? = null, val busy: Boolean = false, val error: String = "", val errorCode: String = "", val inputValues: Map<String, Any> = emptyMap(), val navigation: NativePageNavigation? = null)

/** Reducers run sequentially. Effects leave QuickJS and return as events to the same page instance. */
class NativeUiSession(
    private val app: Context, val pkg: PluginPackage, private val session: AcademicSession,
    val host: NativeCapabilityHost, private val active: () -> Boolean
) {
    private data class Pending(val instance: String, val event: JSONObject, val flow: NativeFlow, val init: Boolean = false, val params: JSONObject = JSONObject(), val queuedAt: Long = android.os.SystemClock.elapsedRealtime(), var edit: NativeInputReconciliation.Edit? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = Channel<Pending>(64)
    private val inputs = NativeInputReconciliation()
    private val queuedInputs = NativeInputEvents<Pending> { previous, next ->
        previous.event.put("value", next.event.get("value")); previous.edit = next.edit
    }
    private val effects = mutableMapOf<String, Job>()
    private val mutableSnapshot = MutableStateFlow(NativeUiSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private var state: Any? = JSONObject()
    private var templateId = ""
    private var previousViewport = ""
    private val sandboxConnection = com.tyust.course.academic.plugin.runtime.PluginSandboxConnections.acquire(app)
    private var closed = false
    init {
        host.cancelEffects = { ids -> ids.forEach { effects[it]?.cancel() } }
        if (pkg.manifest.permissions.any { it in setOf("userscripts", "tasks") }) scope.launch {
            PluginForegroundWork.revision.collect {
                val instance = mutableSnapshot.value.instance
                if (instance.isNotBlank() && isCurrent(instance)) events.trySend(Pending(instance,
                    JSONObject().put("type", "lifecycle").put("name", "tasks.changed"), NativeFlow(false)))
            }
        }
        scope.launch {
            for (pending in events) {
                if (!isCurrent(pending.instance)) continue
                android.util.Log.i("PluginUi", "version=${pkg.manifest.version} phase=queue_done elapsedMs=${android.os.SystemClock.elapsedRealtime() - pending.queuedAt}")
                queuedInputs.consumed(pending)
                val before = mutableSnapshot.value
                mutableSnapshot.value = before.copy(busy = true)
                try {
                    val args = JSONObject().put("pageId", templateId)
                    if (pending.init) args.put("params", pending.params) else args.put("state", state ?: JSONObject.NULL).put("event", pending.event)
                    val context = JSONObject().put("pageId", before.pageId).put("pageInstance", before.instance).put("capabilities", host.capabilities())
                    val result = withContext(Dispatchers.IO) { NativePluginRunner.invoke(app, pkg, session, if (pending.init) "ui.init" else "ui.reduce", args, context) { isCurrent(pending.instance) } }
                    if (!isCurrent(pending.instance)) continue
                    state = result.get("state")
                    mutableSnapshot.value = mutableSnapshot.value.copy(view = result.getJSONObject("view"), busy = false, error = "", errorCode = "",
                        navigation = NativePageNavigation.from(result))
                    for (effect in PluginJson.objects(result.getJSONArray("effects"))) runEffect(effect, pending)
                } catch (error: CancellationException) { if (!scope.isActive) throw error }
                catch (error: Exception) { if (isCurrent(pending.instance)) showError(error) }
                finally {
                    if (isCurrent(pending.instance)) {
                        inputs.acknowledge(pending.edit)
                        mutableSnapshot.value = mutableSnapshot.value.copy(inputValues = inputs.values, busy = false)
                    }
                }
            }
        }
    }
    fun open(pageId: String, params: JSONObject = JSONObject()) {
        val registered = PluginPages.registry.page(pageId)
        if (pageId.contains('/') && registered?.pluginId != pkg.manifest.id) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "页面不属于当前插件")
        templateId = registered?.templateId ?: pageId
        val declaration = NativePluginContract.page(pkg.manifest, templateId)
        if (declaration.optString("renderer") == "web") throw PluginException(PluginErrorCode.UNSUPPORTED, "此页面由网页容器打开")
        if (PluginPlatformContract.requirements(declaration, PluginPages.capabilities()).isNotEmpty()) throw PluginException(PluginErrorCode.UNSUPPORTED, "页面能力条件不满足")
        host.requireCompatible()
        effects.values.toList().forEach(Job::cancel); effects.clear(); queuedInputs.clear(); inputs.clear()
        while (events.tryReceive().isSuccess) { /* Retire queued events from the old page. */ }
        state = JSONObject()
        previousViewport = ""
        val instance = UUID.randomUUID().toString()
        mutableSnapshot.value = NativeUiSnapshot(pageId, instance, busy = true)
        if (!events.trySend(Pending(instance, JSONObject(), NativeFlow(false), init = true, params = params)).isSuccess) showError(PluginException(PluginErrorCode.RESOURCE_LIMIT, "页面请求过多"))
    }
    fun event(instance: String, event: JSONObject, userGesture: Boolean) {
        if (!isCurrent(instance)) return
        val nodeId = event.optString("nodeId")
        var continuous = false
        var optimistic = false
        val flow = NativeFlow(userGesture)
        if (nodeId.isNotBlank()) {
            val node = mutableSnapshot.value.view?.let { NativePluginContract.findNode(it, nodeId) } ?: return
            if (!node.optBoolean("enabled", true) || event.optString("name") !in setOf(node.optString("event"), node.optString("onEnd"))) return
            continuous = node.optString("type") in setOf("input", "slider") && event.optString("type") == "input"
            optimistic = node.optString("type") in setOf("input", "slider", "segmented", "toggle", "select", "pattern") && event.optString("type") == "input" && event.has("value")
            flow.submission = runCatching { NativeSubmissionGrant.fromButton(pkg.manifest, node, event, userGesture) }.getOrNull()
        }
        val edit = if (optimistic) inputs.edit(nodeId, event.get("value")) else null
        if (edit != null) mutableSnapshot.value = mutableSnapshot.value.copy(inputValues = inputs.values)
        val pending = Pending(instance, event, flow, edit = edit)
        val key = if (continuous) instance + ":" + nodeId + ":" + event.optString("name") else null
        if (!queuedInputs.offer(key, pending) { events.trySend(it).isSuccess }) {
            inputs.acknowledge(edit)
            mutableSnapshot.value = mutableSnapshot.value.copy(inputValues = inputs.values)
            showError(PluginException(PluginErrorCode.RESOURCE_LIMIT, "操作过快，请稍后重试"))
        }
    }
    fun viewportChanged(value: JSONObject) {
        val serialized = value.toString()
        if (previousViewport == serialized) return
        val declaration = runCatching { NativePluginContract.page(pkg.manifest, templateId) }.getOrNull() ?: return
        val requirements = PluginJson.objects(declaration.optJSONArray("requires") ?: org.json.JSONArray()) +
            PluginJson.objects(pkg.manifest.json.optJSONArray("requires") ?: org.json.JSONArray())
        if (requirements.none { it.optString("name") == "ui.viewport" && it.optInt("version") == 1 }) return
        val instance = mutableSnapshot.value.instance
        if (!isCurrent(instance)) return
        val pending = Pending(instance, JSONObject().put("type", "lifecycle").put("name", "viewport.changed").put("value", value), NativeFlow(false))
        if (queuedInputs.offer(instance + ":viewport", pending) { events.trySend(it).isSuccess }) previousViewport = serialized
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
    fun close() { sandboxConnection.close(); closed = true; events.close(); effects.values.toList().forEach(Job::cancel); scope.cancel(); host.close(); session.retire() }
}
