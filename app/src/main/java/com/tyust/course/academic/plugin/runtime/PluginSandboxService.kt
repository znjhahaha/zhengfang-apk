package com.tyust.course.academic.plugin.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.tyust.course.academic.plugin.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** No application data, sockets, native modules or filesystem bindings are exposed to JS. */
class PluginSandboxService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val binder = object : IPluginSandbox.Stub() {
        override fun execute(input: ParcelFileDescriptor, host: IPluginHost, callback: IPluginResult) {
            scope.launch(Dispatchers.IO) {
                var id = ""
                try {
                    val request = PluginJson.parse(PluginWire.read(input))
                    id = request.getJSONObject("context").getString("operationId")
                    val job = currentCoroutineContext().job
                    if (jobs.putIfAbsent(id, job) != null) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "重复操作标识")
                    val result = runEngine(request, host)
                    PluginWire.send(this, result).use { callback.complete(id, it) }
                } catch (e: Exception) {
                    val code = when (e) {
                        is PluginException -> e.code
                        is QuickJsInterruptedException, is TimeoutCancellationException -> PluginErrorCode.TIMEOUT
                        is CancellationException -> PluginErrorCode.CANCELLED
                        else -> if (e.message.orEmpty().contains("memory", true) || e.message.orEmpty().contains("stack", true))
                            PluginErrorCode.RESOURCE_LIMIT else PluginErrorCode.PAGE_CHANGED
                    }
                    if (id.isNotEmpty()) withContext(NonCancellable) {
                        // Use the service scope so a cancelled engine can still report its terminal state.
                        runCatching { PluginWire.send(scope, PluginJson.error(code, "插件运行失败：${code.name}").toString()).use { callback.complete(id, it) } }
                    }
                } finally { if (id.isNotEmpty()) jobs.remove(id, currentCoroutineContext().job) }
            }
        }
        override fun cancel(operationId: String) { jobs[operationId]?.cancel() }
    }

    private suspend fun runEngine(request: JSONObject, host: IPluginHost): String = withTimeout(PluginLimits.WALL_MILLIS) {
        val engine = QuickJs.create(Dispatchers.IO)
        try {
            engine.memoryLimit = PluginLimits.MEMORY_BYTES
            engine.maxStackSize = PluginLimits.STACK_BYTES
            engine.evaluationTimeoutMillis = PluginLimits.JS_MILLIS
            val operationId = request.getJSONObject("context").getString("operationId")
            val completion = CompletableDeferred<String>()
            engine.function("__zfResult") { values ->
                val value = values.singleOrNull() as? String
                    ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "插件结果必须为 JSON 字符串")
                if (value.toByteArray().size > PluginLimits.WIRE_BYTES)
                    throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "插件结果超过上限")
                completion.complete(value)
                Unit
            }
            engine.asyncFunction("__zfHost") { args ->
                if (args.size != 2 || args.any { it !is String }) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "无效宿主调用")
                coroutineScope {
                    withContext(Dispatchers.IO) {
                        PluginWire.send(this, args[1] as String).use { payload ->
                            val response = host.call(operationId, args[0] as String, payload)
                                ?: throw PluginException(PluginErrorCode.RUNTIME_EXITED, "宿主已断开")
                            PluginWire.read(response)
                        }
                    }
                }
            }
            // One evaluation covers bootstrap, plugin initialization and invocation under one JS budget.
            val sdk = assets.open("academic-plugin/host-sdk.js").bufferedReader().use { it.readText() }
            val source = request.getString("source")
            val invoke = JSONObject(request.toString()).apply { remove("source") }
            engine.evaluate<Unit>(sdk + "\n;" + source + "\n;__zfInvoke(" + invoke.toString() + ").then(__zfResult);void 0;", "plugin.js")
            completion.await()
        } finally { engine.close() }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
