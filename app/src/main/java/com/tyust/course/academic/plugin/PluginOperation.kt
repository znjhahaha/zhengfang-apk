package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSession
import okhttp3.Call
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PluginOperation(
    val session: AcademicSession,
    val manifest: PluginManifest,
    val method: String,
    val development: Boolean = false,
    val confirmed: Boolean = false,
    val id: String = UUID.randomUUID().toString()
) {
    val epoch = session.epoch
    private val active = AtomicBoolean(true)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val mutation = AtomicBoolean(false)
    val mutationSent: Boolean get() = mutation.get()
    val context: JSONObject get() = JSONObject().put("schoolId", session.key.schoolId)
        .put("accountId", session.key.accountKey).put("sessionEpoch", epoch)
        .put("providerId", manifest.id).put("providerVersion", manifest.version).put("operationId", id)
        .put("baseUrl", session.baseUrl).put("development", development)

    fun requireActive() {
        if (!active.get()) throw PluginException(PluginErrorCode.CANCELLED, "调用已取消")
        if (session.retired || session.epoch != epoch) throw PluginException(PluginErrorCode.SESSION_EXPIRED, "会话已失效")
    }
    fun markMutation() {
        requireActive()
        if (!confirmed || method !in setOf("selection.select", "selection.drop"))
            throw PluginException(PluginErrorCode.VALIDATION_FAILED, "选退课需要用户确认")
        if (!mutation.compareAndSet(false, true))
            throw PluginException(PluginErrorCode.RESULT_UNKNOWN, "单次调用不能重放选退课请求")
    }
    fun register(call: Call) { calls.add(call); try { requireActive() } catch (e: Exception) { call.cancel(); calls.remove(call); throw e } }
    fun unregister(call: Call) { calls.remove(call) }
    fun close() { active.set(false); calls.forEach(Call::cancel); calls.clear() }
    fun failure(code: PluginErrorCode, message: String): PluginException = PluginException(
        if (mutationSent && code in setOf(PluginErrorCode.TIMEOUT, PluginErrorCode.CANCELLED,
            PluginErrorCode.RUNTIME_EXITED, PluginErrorCode.NETWORK_RETRYABLE, PluginErrorCode.RESOURCE_LIMIT,
            PluginErrorCode.PAGE_CHANGED, PluginErrorCode.VALIDATION_FAILED))
            PluginErrorCode.RESULT_UNKNOWN else code, message)
}
