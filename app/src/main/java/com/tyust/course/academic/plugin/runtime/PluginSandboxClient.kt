package com.tyust.course.academic.plugin.runtime

import android.content.*
import android.os.*
import android.util.Log
import com.tyust.course.academic.plugin.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class PluginSandboxClient(context: Context, private val startupTimeoutMillis: Long = 10_000, private val idleTimeoutMillis: Long = 30_000) {
    private val application = context.applicationContext

    suspend fun execute(source: String, args: JSONObject, operation: PluginOperation, host: PluginHost): JSONObject = coroutineScope {
        operation.requireActive()
        val scope = this
        val result = CompletableDeferred<String>()
        val accepting = AtomicBoolean(true)
        val received = AtomicBoolean(false)
        val incomingDescriptors = java.util.concurrent.ConcurrentHashMap.newKeySet<ParcelFileDescriptor>()
        val startedAt = SystemClock.elapsedRealtime()
        var phaseStartedAt = startedAt
        var phase = "binding"
        val dispatched = AtomicBoolean(false)
        PluginTrace.stage(operation, "binding")
        fun diagnostic(outcome: String) {
            val now = SystemClock.elapsedRealtime()
            Log.i("PluginSandbox", "api=${Build.VERSION.SDK_INT} version=${operation.manifest.version} phase=$phase outcome=$outcome phaseMs=${now - phaseStartedAt} totalMs=${now - startedAt}")
        }
        fun disconnected(message: String) {
            if (!accepting.get() || !dispatched.get()) return
            val error = operation.failure(PluginErrorCode.RUNTIME_EXITED, message)
            result.completeExceptionally(error)
        }
        var connectionLease: PluginSandboxConnections.Lease? = null
        var sandbox: IPluginSandbox? = null
        try {
            withTimeout(PluginLimits.WALL_MILLIS) {
                sandbox = withTimeout(startupTimeoutMillis) {
                    var attempt = 0
                    var connected: IPluginSandbox? = null
                    while (connected == null) {
                        operation.requireActive()
                        val lease = PluginSandboxConnections.acquire(application, idleTimeoutMillis, ::disconnected)
                        connectionLease = lease
                        try { connected = lease.ready.await(); lease.requireReady() }
                        catch (e: PluginException) {
                            lease.close()
                            if (e.code != PluginErrorCode.RUNTIME_EXITED || attempt++ >= 1) throw e
                            connected = null
                            PluginTrace.stage(operation, "binding_retry")
                            yield()
                        }
                    }
                    connected
                }
                diagnostic("connected")
                operation.requireActive()
                val bridge = object : IPluginHost.Stub() {
                    override fun call(id: String, method: String, payload: ParcelFileDescriptor): ParcelFileDescriptor {
                        val response = try {
                            payload.use {
                                if (!accepting.get() || id != operation.id) throw PluginException(PluginErrorCode.CANCELLED, "操作标识已失效")
                                operation.requireActive()
                                val value = PluginJson.parse(PluginWire.read(it))
                                if (method == "__runtimeStage") {
                                    val stage = value.optString("stage")
                                    if (stage !in setOf("engine_initializing", "engine_ready", "plugin_loading", "script"))
                                        throw PluginException(PluginErrorCode.VALIDATION_FAILED, "无效运行阶段")
                                    PluginTrace.stage(operation, stage)
                                    PluginJson.success(JSONObject.NULL)
                                } else host.call(method, value)
                            }
                        } catch (e: Exception) {
                            val error = if (e is PluginException) e else operation.failure(PluginErrorCode.VALIDATION_FAILED, "宿主调用失败")
                            PluginJson.error(error.code, error.message.orEmpty())
                        }
                        return PluginWire.send(scope, response.toString())
                    }
                }
                val callback = object : IPluginResult.Stub() {
                    override fun complete(id: String, descriptor: ParcelFileDescriptor) {
                        if (!accepting.get() || !scope.isActive || id != operation.id || result.isCompleted ||
                            !received.compareAndSet(false, true)) { descriptor.close(); return }
                        incomingDescriptors.add(descriptor)
                        // Enter use before cancellation can strand an incoming descriptor.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try { descriptor.use {
                                try { result.complete(withContext(Dispatchers.IO) { PluginWire.read(it) }) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { result.completeExceptionally(e) }
                            } } finally { incomingDescriptors.remove(descriptor) }
                        }
                    }
                }
                val request = JSONObject().put("source", source).put("operation", operation.method)
                    .put("args", args).put("context", operation.context)
                phase = "executing"
                phaseStartedAt = SystemClock.elapsedRealtime()
                PluginWire.send(scope, request.toString()).use {
                    connectionLease!!.requireReady()
                    operation.requireActive()
                    dispatched.set(true) // From here on, never replay execute(), even if Binder throws.
                    sandbox!!.execute(it, bridge, callback)
                }
                val response = PluginJson.parse(result.await())
                operation.requireActive()
                if (!response.optBoolean("ok")) {
                    val failure = response.optJSONObject("error")
                    var code = runCatching { PluginErrorCode.valueOf(failure?.optString("code").orEmpty()) }.getOrDefault(PluginErrorCode.PAGE_CHANGED)
                    val detail = failure?.optString("message") ?: "插件返回错误"
                    // __zfInvoke may catch QuickJS's interrupt and serialize it as a generic
                    // page error before the Kotlin engine can surface its typed exception.
                    if (code == PluginErrorCode.PAGE_CHANGED &&
                        detail.trim() in setOf("interrupted", "InternalError: interrupted")) code = PluginErrorCode.TIMEOUT
                    val message = if (code == PluginErrorCode.TIMEOUT) {
                        if (operation.method.substringBefore('.') in setOf("auth", "serviceAuth")) "登录处理超时，请重试或使用网页登录"
                        else "教务处理超时，请重试"
                    } else detail
                    throw operation.failure(code, message)
                }
                PluginTrace.stage(operation, "completed")
                diagnostic("completed")
                response
            }
        } catch (e: TimeoutCancellationException) {
            // An outer deadline must remain caller cancellation.
            currentCoroutineContext().ensureActive()
            if (phase == "binding") connectionLease?.invalidate("插件进程启动超时，请重试")
            diagnostic("timeout")
            throw if (phase == "binding") operation.failure(PluginErrorCode.RUNTIME_EXITED, "插件进程启动超时，请重试")
                else operation.failure(PluginErrorCode.TIMEOUT, "插件执行超时，请重试")
        } catch (e: CancellationException) {
            diagnostic("cancelled")
            throw e
        } catch (e: RemoteException) {
            diagnostic("disconnected")
            throw operation.failure(PluginErrorCode.RUNTIME_EXITED, "插件进程已退出")
        } catch (e: SecurityException) {
            diagnostic("denied")
            throw operation.failure(PluginErrorCode.RUNTIME_EXITED, "系统未允许启动插件服务")
        } catch (e: java.io.IOException) {
            diagnostic("response_interrupted")
            throw operation.failure(PluginErrorCode.RUNTIME_EXITED, "插件进程响应中断，请重试")
        } catch (e: PluginException) {
            diagnostic(e.code.name)
            throw operation.failure(e.code, e.message.orEmpty())
        } finally {
            accepting.set(false)
            incomingDescriptors.forEach { runCatching { it.close() } }
            incomingDescriptors.clear()
            // The caller owns the operation through response validation/publication.
            // This client only retires its Binder connection and transport resources.
            runCatching { sandbox?.cancel(operation.id) }
            connectionLease?.close()
            result.cancel()
        }
    }
}
