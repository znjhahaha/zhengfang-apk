package com.tyust.course.academic.plugin

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import org.json.JSONObject

/** Private UID-only IPC. Large scripts/responses travel through pipes, below Binder's size limit. */
internal object PluginBrowserWire {
    const val DESCRIPTOR = "com.tyust.course.plugin.embedded.v1"
    const val MAX_BYTES = 9 * 1024 * 1024
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun write(parcel: Parcel, value: JSONObject) {
        val bytes = value.toString().toByteArray()
        if (bytes.size > MAX_BYTES) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "浏览器消息过大")
        if (bytes.size <= 128 * 1024) { parcel.writeString(String(bytes)); return }
        parcel.writeString(null)
        val pipe = ParcelFileDescriptor.createPipe()
        try { pipe[0].writeToParcel(parcel, 0) } finally { pipe[0].close() }
        io.launch { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } } }
    }
    fun read(parcel: Parcel): JSONObject {
        val text = parcel.readString()
        if (text != null) {
            if (text.length > MAX_BYTES) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "浏览器消息过大")
            return JSONObject(text)
        }
        return ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.CREATOR.createFromParcel(parcel)).use {
            JSONObject(String(it.readBytesBounded(MAX_BYTES)))
        }
    }
    fun call(binder: IBinder, value: JSONObject, callback: IBinder? = null): JSONObject {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Browser IPC must not block the main thread" }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR); write(data, value); data.writeStrongBinder(callback)
            if (!binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) throw PluginException(PluginErrorCode.NOT_OPEN, "浏览器连接已关闭")
            reply.readException()
            val result = read(reply)
            if (!result.optBoolean("ok")) throw PluginException(runCatching { PluginErrorCode.valueOf(result.optString("code")) }.getOrDefault(PluginErrorCode.UNSUPPORTED), result.optString("message", "浏览器请求未完成"))
            return result.optJSONObject("data") ?: JSONObject()
        } finally { data.recycle(); reply.recycle() }
    }
    fun failure(error: Throwable, action: String = ""): JSONObject {
        val stage = when (action) {
            "configure" -> "初始化"
            "page.open" -> "打开课程页面"
            "script.start" -> "注册原脚本"
            "script.control" -> "启动原脚本"
            "script.settings" -> "同步脚本设置"
            "cookies.read", "cookies.write" -> "同步登录状态"
            else -> "处理请求"
        }
        val code = (error as? PluginException)?.code ?: when (error) {
            is TimeoutCancellationException -> PluginErrorCode.TIMEOUT
            is android.os.DeadObjectException -> PluginErrorCode.RUNTIME_EXITED
            is CancellationException -> PluginErrorCode.CANCELLED
            else -> PluginErrorCode.UNSUPPORTED
        }
        // Never surface arbitrary exception text: URLs and headers can contain credentials.
        val message = if (error is PluginException) error.message else when (code) {
            PluginErrorCode.TIMEOUT -> "任务引擎${stage}超时，请查看提示后重试"
            PluginErrorCode.RUNTIME_EXITED -> "内置浏览器进程已退出，请重新打开课程"
            PluginErrorCode.CANCELLED -> "浏览器操作已取消"
            else -> "任务引擎${stage}失败（${error.javaClass.simpleName}），请停止后重试"
        }
        return JSONObject().put("ok", false).put("code", code.name).put("message", message)
    }
}
