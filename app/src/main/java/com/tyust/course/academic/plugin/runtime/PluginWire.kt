package com.tyust.course.academic.plugin.runtime

import android.os.ParcelFileDescriptor
import com.tyust.course.academic.plugin.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/** Pipes avoid Binder's 1 MiB transaction limit. Writers belong to the invocation scope. */
object PluginWire {
    fun send(scope: CoroutineScope, text: String): ParcelFileDescriptor {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > PluginLimits.WIRE_BYTES) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "跨进程数据超过上限")
        val pipe = ParcelFileDescriptor.createPipe()
        val writer = scope.launch(Dispatchers.IO) {
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } }
            catch (_: java.io.IOException) { /* Reader cancelled. */ }
        }
        writer.invokeOnCompletion { runCatching { pipe[1].close() } }
        return pipe[0]
    }
    fun read(descriptor: ParcelFileDescriptor): String = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > PluginLimits.WIRE_BYTES)
                throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "跨进程数据超过上限")
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }
}
