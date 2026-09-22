package com.tyust.course.academic.plugin.runtime

import android.content.*
import android.os.*
import com.tyust.course.academic.plugin.*
import kotlinx.coroutines.*
import org.json.JSONObject

class PluginSandboxClient(context: Context) {
    private val application = context.applicationContext

    suspend fun execute(source: String, args: JSONObject, operation: PluginOperation, host: PluginHost): JSONObject = coroutineScope {
        operation.requireActive()
        val scope = this
        val connected = CompletableDeferred<IPluginSandbox>()
        val result = CompletableDeferred<String>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(IPluginSandbox.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) {
                val error = operation.failure(PluginErrorCode.RUNTIME_EXITED, "插件进程已退出")
                connected.completeExceptionally(error)
                result.completeExceptionally(error)
            }
            override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(PluginException(PluginErrorCode.RUNTIME_EXITED, "无法启动插件进程")) }
            override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
        }
        var bound = false
        var sandbox: IPluginSandbox? = null
        try {
            withTimeout(PluginLimits.WALL_MILLIS) {
                bound = application.bindService(Intent(application, PluginSandboxService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound) throw PluginException(PluginErrorCode.RUNTIME_EXITED, "无法绑定插件进程")
                sandbox = withTimeout(10_000) { connected.await() }
                val bridge = object : IPluginHost.Stub() {
                    override fun call(id: String, method: String, payload: ParcelFileDescriptor): ParcelFileDescriptor {
                        val response = try {
                            payload.use {
                                if (id != operation.id) throw PluginException(PluginErrorCode.CANCELLED, "操作标识已失效")
                                operation.requireActive()
                                host.call(method, PluginJson.parse(PluginWire.read(it)))
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
                        if (id != operation.id || result.isCompleted) { descriptor.close(); return }
                        scope.launch(Dispatchers.IO) {
                            try { descriptor.use { result.complete(PluginWire.read(it)) } }
                            catch (e: Exception) { result.completeExceptionally(e) }
                        }
                    }
                }
                val request = JSONObject().put("source", source).put("operation", operation.method)
                    .put("args", args).put("context", operation.context)
                PluginWire.send(scope, request.toString()).use { sandbox!!.execute(it, bridge, callback) }
                val response = PluginJson.parse(result.await())
                operation.requireActive()
                if (!response.optBoolean("ok")) {
                    val failure = response.optJSONObject("error")
                    val code = runCatching { PluginErrorCode.valueOf(failure?.optString("code").orEmpty()) }.getOrDefault(PluginErrorCode.PAGE_CHANGED)
                    throw operation.failure(code, failure?.optString("message") ?: "插件返回错误")
                }
                response
            }
        } catch (e: TimeoutCancellationException) {
            throw operation.failure(PluginErrorCode.TIMEOUT, "插件调用超时")
        } catch (e: RemoteException) {
            throw operation.failure(PluginErrorCode.RUNTIME_EXITED, "插件进程已退出")
        } finally {
            operation.close()
            runCatching { sandbox?.cancel(operation.id) }
            if (bound) runCatching { application.unbindService(connection) }
        }
    }
}
