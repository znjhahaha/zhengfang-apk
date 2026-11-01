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
    val actionId: String? = null,
    val id: String = UUID.randomUUID().toString(),
    val pageContext: JSONObject = JSONObject(),
    val packageDigest: String = manifest.version,
    private val scopeStillActive: () -> Boolean = { true }
) {
    companion object {
        private val live = java.util.Collections.synchronizedMap(java.util.WeakHashMap<PluginOperation, Unit>())
        fun cancelPlugin(id: String) {
            val operations = synchronized(live) { live.keys.filter { it.manifest.id == id } }
            operations.forEach(PluginOperation::close)
        }
    }
    val epoch = session.epoch
    private val active = AtomicBoolean(true)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val mutation = AtomicBoolean(false)
    private val businessMutation = AtomicBoolean(false)
    init { live[this] = Unit }
    val mutationSent: Boolean get() = mutation.get()
    val context: JSONObject get() = JSONObject().put("schoolId", session.key.schoolId)
        .put("accountId", session.key.accountKey).put("sessionEpoch", epoch)
        .put("providerId", manifest.id).put("providerVersion", manifest.version).put("operationId", id)
        .put("baseUrl", session.baseUrl).put("development", development)
        .apply { pageContext.keys().forEach { key -> if (key in setOf("pageId", "pageInstance", "capabilities", "settings")) put(key, pageContext.get(key)) } }

    fun requireActive() {
        if (!active.get()) throw PluginException(PluginErrorCode.CANCELLED, "调用已取消")
        if (!scopeStillActive()) throw PluginException(PluginErrorCode.SESSION_EXPIRED, "账号或插件状态已改变")
        if (session.retired || session.epoch != epoch) throw PluginException(PluginErrorCode.SESSION_EXPIRED, "会话已失效")
    }
    internal fun requireMutationAllowed() {
        requireActive()
        val serviceMutation = manifest.isService && method == "service.action" && actionId != null &&
            ServicePluginContract.action(manifest, actionId).getString("kind") == "mutation"
        val nativeMutation = manifest.isNative && method == "host.effect"
        val workflowMutation = manifest.isNative && method == "workflow.step" && manifest.json.optJSONArray("services")?.let(PluginJson::objects).orEmpty().any { it.getString("name") == actionId && it.getString("kind") == "write" }
        if (!confirmed || method !in setOf("selection.select", "selection.drop") && !serviceMutation && !nativeMutation && !workflowMutation)
            throw PluginException(PluginErrorCode.VALIDATION_FAILED, "写入操作需要用户确认")
        if (businessMutation.get()) throw PluginException(PluginErrorCode.RESULT_UNKNOWN, "单次调用不能重放写入请求")
    }
    fun markMutation() {
        requireMutationAllowed()
        if (!businessMutation.compareAndSet(false, true))
            throw PluginException(PluginErrorCode.RESULT_UNKNOWN, "单次调用不能重放写入请求")
        mutation.set(true)
    }
    /** Only host-authorized shared-site or legacy read-state requests reach this path. */
    internal fun markAuthorizedSharedWrite() {
        requireActive()
        if (!(manifest.isService && method in setOf("service.page", "service.action") || manifest.isNative && method == "host.effect"))
            throw PluginException(PluginErrorCode.PERMISSION_DENIED, "状态更新必须通过共享服务请求")
        mutation.set(true)
    }
    fun register(call: Call) { calls.add(call); try { requireActive() } catch (e: Exception) { call.cancel(); calls.remove(call); throw e } }
    fun unregister(call: Call) { calls.remove(call) }
    fun close() { active.set(false); calls.forEach(Call::cancel); calls.clear(); live.remove(this) }
    fun failure(code: PluginErrorCode, message: String): PluginException {
        PluginTrace.failure(this, code)
        return PluginException(
        if (mutationSent && code in setOf(PluginErrorCode.TIMEOUT, PluginErrorCode.CANCELLED,
            PluginErrorCode.RUNTIME_EXITED, PluginErrorCode.NETWORK_RETRYABLE, PluginErrorCode.RESOURCE_LIMIT,
            PluginErrorCode.PAGE_CHANGED, PluginErrorCode.VALIDATION_FAILED, PluginErrorCode.STALE_CONTEXT,
            PluginErrorCode.SESSION_EXPIRED, PluginErrorCode.PERMISSION_DENIED))
            PluginErrorCode.RESULT_UNKNOWN else code, message)
    }
}
