package com.tyust.course.academic.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface PluginWorkflowStore {
    fun read(id: String): JSONObject?
    fun write(id: String, record: JSONObject)
    fun list(): List<JSONObject>
}

/** Write-ahead journal. No network request may precede the durable 'sending' state. */
class PluginWorkflowJournal(private val store: PluginWorkflowStore) {
    fun prepare(identity: JSONObject, input: Any?, plan: JSONObject): JSONObject = synchronized(storeLock) {
        val steps = PluginJson.objects(plan.getJSONArray("steps"))
        if (steps.size !in 1..200 || steps.map { it.getString("id") }.distinct().size != steps.size || steps.map { it.getString("target") }.distinct().size != steps.size)
            throw PluginException(PluginErrorCode.VALIDATION_FAILED, "工作流目标或步骤重复、为空或超过限制")
        if (store.list().count { !terminal(it) } >= 200) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "未完成工作流过多")
        val id = "wf_" + UUID.randomUUID().toString()
        val record = JSONObject(identity.toString()).put("workflowId", id).put("input", input ?: JSONObject.NULL)
            .put("title", plan.getString("title")).put("summary", plan.getString("summary"))
            .put("createdAt", System.currentTimeMillis()).put("approved", false).put("cancelled", false)
            .put("steps", JSONArray(steps.map { JSONObject(it.toString()).put("status", "ready").put("attempts", 0) }))
        if (record.toString().toByteArray().size > PluginLimits.STATE_BYTES) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "工作流数据过大")
        store.write(id, record); snapshot(record)
    }
    fun owned(id: String, callerId: String, scope: String): JSONObject = synchronized(storeLock) {
        val record = store.read(id) ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "工作流不存在")
        if (record.getString("callerId") != callerId || record.getString("scope") != scope) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "工作流属于其他插件或账号")
        record
    }
    fun list(callerId: String, scope: String): JSONArray = synchronized(storeLock) { JSONArray(store.list().filter { it.optString("callerId") == callerId && it.optString("scope") == scope }.sortedByDescending { it.optLong("createdAt") }.take(200).map(::snapshot)) }
    fun approve(id: String) = mutate(id) { it.put("approved", true) }
    fun cancel(id: String): JSONObject = mutate(id) { it.put("cancelled", true) }.let(::snapshot)
    suspend fun run(id: String, active: () -> Boolean, invoke: suspend (JSONObject, JSONObject) -> JSONObject): JSONObject = mutex(id).withLock {
        var record = recover(id)
        if (!record.getBoolean("approved")) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "请先确认工作流范围")
        if (PluginJson.objects(record.getJSONArray("steps")).any { it.getString("status") == "unknown" }) return@withLock snapshot(record)
        for (index in 0 until record.getJSONArray("steps").length()) {
            record = read(id)
            if (record.getBoolean("cancelled") || !active()) break
            val step = record.getJSONArray("steps").getJSONObject(index)
            if (step.getString("status") != "ready") continue
            mutate(id) { current -> current.getJSONArray("steps").getJSONObject(index).put("status", "sending").put("attempts", step.getInt("attempts") + 1) }
            val receipt = try { invoke(read(id), JSONObject(step.toString())) }
            catch (e: Exception) {
                // Even cancellation can arrive after the server commits. Leave a durable
                // unknown outcome; reconciliation is the only path back to ready.
                withContext(NonCancellable) { mutate(id) { it.getJSONArray("steps").getJSONObject(index).put("status", "unknown").put("message", "提交结果待核对") } }
                if (e is CancellationException) throw e
                break
            }
            val status = receipt.optString("status")
            if (status !in setOf("confirmed", "failed", "unknown")) {
                mutate(id) { it.getJSONArray("steps").getJSONObject(index).put("status", "unknown").put("message", "响应格式无效，结果待核对") }; break
            }
            mutate(id) { current -> current.getJSONArray("steps").getJSONObject(index).put("status", status).put("message", receipt.optString("message")).apply { if (receipt.has("value")) put("value", receipt.get("value")) } }
            if (status == "unknown") break
        }
        snapshot(read(id))
    }
    suspend fun reconcile(id: String, active: () -> Boolean, invoke: suspend (JSONObject, JSONObject) -> JSONObject): JSONObject = mutex(id).withLock {
        val recovered = recover(id)
        for (index in 0 until recovered.getJSONArray("steps").length()) {
            val record = read(id); val step = record.getJSONArray("steps").getJSONObject(index)
            if (!active() || step.getString("status") != "unknown") continue
            val result = try { invoke(record, JSONObject(step.toString())) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            val status = when (result.optString("status")) { "confirmed" -> "confirmed"; "notApplied" -> "ready"; else -> "unknown" }
            mutate(id) { it.getJSONArray("steps").getJSONObject(index).put("status", status).put("message", result.optString("message")).apply { if (result.has("value")) put("value", result.get("value")) } }
        }
        snapshot(read(id))
    }
    fun references(): Set<String> = synchronized(storeLock) { store.list().filterNot(::terminal).flatMap { listOf(it.getString("callerDigest"), it.getString("providerDigest")) }.toSet() }
    private fun recover(id: String): JSONObject = mutate(id) { record -> PluginJson.objects(record.getJSONArray("steps")).filter { it.getString("status") == "sending" }.forEach { it.put("status", "unknown").put("message", "进程中断，提交结果待核对") } }
    private fun read(id: String): JSONObject = synchronized(storeLock) { store.read(id) ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "工作流不存在") }
    private fun mutate(id: String, action: (JSONObject) -> Unit): JSONObject = synchronized(storeLock) { val record = read(id); action(record); store.write(id, record); record }
    private fun terminal(record: JSONObject): Boolean { val statuses = PluginJson.objects(record.getJSONArray("steps")).map { it.getString("status") }; return statuses.none { it in setOf("sending", "unknown") } && (record.getBoolean("cancelled") || statuses.none { it == "ready" }) }
    private fun snapshot(record: JSONObject): JSONObject = JSONObject().put("workflowId", record.getString("workflowId")).put("title", record.getString("title")).put("summary", record.getString("summary"))
        .put("approved", record.getBoolean("approved")).put("cancelled", record.getBoolean("cancelled"))
        .put("steps", JSONArray(PluginJson.objects(record.getJSONArray("steps")).map { step -> JSONObject().put("id", step.getString("id")).put("target", step.getString("target")).put("label", step.getString("label")).put("status", step.getString("status")).put("message", step.optString("message")) }))
    private fun mutex(id: String) = executions.getOrPut(id) { Mutex() }
    companion object { private val storeLock = Any(); private val executions = ConcurrentHashMap<String, Mutex>() }
}
