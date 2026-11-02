package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject

/** One running task's immutable selection and host-only device/record evidence. Never serialized. */
class PluginForegroundWorkflowGrant internal constructor(
    declaration: JSONObject, input: JSONObject, private val pluginId: String, private val digest: String,
    private val accountId: String, private val active: () -> Boolean
) {
    private val plan = JSONObject(declaration.getJSONObject("workflow").toString())
    private val serverId = declaration.getString("serverId")
    private val selection = JSONObject(input.toString())
    private val owned = linkedMapOf<String, String>()
    private var location: JSONObject? = null
    private var profile: JSONObject? = null
    private val deviceGeneration = mutableMapOf("location" to 0L, "profile" to 0L)
    private val consumedDevice = mutableMapOf("location" to 0L, "profile" to 0L)
    private val items: List<JSONObject>

    init {
        if (plan.keys().asSequence().toSet() != setOf("name", "version", "inputList", "tenantInputKey") || plan.optString("name").isBlank() || plan.optInt("version") != 1 || plan.optString("inputList") != "items" || plan.optString("tenantInputKey") != "tenantId") invalid("持续工作流声明无效")
        if (selection.keys().asSequence().toSet() != setOf("tenantId", "items") || (selection.opt("tenantId") as? String).isNullOrBlank() || selection.optString("tenantId").length > 256 || selection.toString().toByteArray().size > 65536) invalid("持续工作流输入无效")
        val values = selection.optJSONArray("items") ?: invalid("请选择固定任务范围")
        if (values.length() !in 1..20) invalid("请选择 1–20 项任务")
        items = (0 until values.length()).map { values.optJSONObject(it) ?: invalid("任务项目无效") }
        items.forEach { item ->
            if (item.keys().asSequence().toSet() != setOf("family", "identity", "materials") || (item.opt("family") as? String).isNullOrBlank() || item.optString("family").length > 96 || !validIdentity(item.opt("identity"))) invalid("任务身份无效")
            val materials = item.optJSONObject("materials") ?: invalid("任务材料无效")
            for ((key, value) in mapOf("tenantId" to selection.get("tenantId"), "family" to item.get("family"), "identity" to item.get("identity")))
                if (materials.has(key) && PluginJson.canonical(materials.get(key)) != PluginJson.canonical(value)) invalid("材料不能更改所选学校或身份")
        }
        if (items.map { PluginJson.canonical(JSONArray().put(it.get("family")).put(it.get("identity"))) }.distinct().size != items.size) invalid("任务项目重复")
    }
    fun supports(name: String) = name in setOf("workflow.prepare", "workflow.step", "workflow.reconcile", "workflow.list", "workflow.cancel", "device.location", "device.profile")
    private fun checkActive() { if (!active()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "持续任务的账号、版本或授权已失效") }
    @Synchronized fun rememberLocation(value: JSONObject) { checkActive(); location = JSONObject(value.toString()); deviceGeneration["location"] = deviceGeneration.getValue("location") + 1 }
    @Synchronized fun rememberProfile(value: JSONObject) { checkActive(); profile = JSONObject(value.toString()); deviceGeneration["profile"] = deviceGeneration.getValue("profile") + 1 }
    @Synchronized fun requireRead(input: JSONObject) {
        checkActive(); val value = input.optJSONObject("input") ?: denied("只读服务输入无效")
        if (input.optString("providerId", pluginId) != pluginId || value.optString("tenantId") != selection.getString("tenantId") || items.none { sameItem(it, value) }) denied("只读查询超出所选任务")
    }
    @Synchronized fun requirePrepare(input: JSONObject) {
        checkActive()
        if (input.optString("name") != plan.getString("name") || input.optInt("version") != plan.getInt("version") || input.optString("providerId", pluginId) != pluginId) denied("工作流服务或提供方超出本次授权")
        val value = input.optJSONObject("input") ?: denied("工作流输入无效")
        val item = items.firstOrNull { value.optString("tenantId") == selection.getString("tenantId") && sameItem(it, value) } ?: denied("工作流身份超出所选项目")
        val expected = JSONObject(item.getJSONObject("materials").toString()).put("tenantId", selection.get("tenantId")).put("family", item.get("family")).put("identity", item.get("identity"))
        for ((key, evidence) in mapOf("location" to location, "profile" to profile)) if (expected.has(key) || value.has(key)) {
            if (deviceGeneration.getValue(key) <= consumedDevice.getValue(key)) denied("每项任务都需要新的宿主设备结果")
            expected.put(key, evidence ?: denied("请先取得当前宿主设备材料"))
        }
        if (PluginJson.canonical(value) != PluginJson.canonical(expected)) denied("工作流材料与固定选择或宿主设备结果不同")
    }
    private fun sameItem(item: JSONObject, value: JSONObject) = item.optString("family") == value.optString("family") && PluginJson.canonical(item.opt("identity")) == PluginJson.canonical(value.opt("identity"))
    private fun fingerprint(record: JSONObject) = PluginJson.canonical(JSONObject().apply {
        for (key in listOf("callerId", "callerDigest", "providerId", "providerDigest", "serverId", "providerAccount", "name", "version", "input")) put(key, record.opt(key))
    })
    @Synchronized fun remember(record: JSONObject) {
        requirePrepare(JSONObject().put("name", record.get("name")).put("version", record.get("version")).put("providerId", record.get("providerId")).put("input", record.get("input")))
        if (record.optString("callerId") != pluginId || record.optString("providerId") != pluginId || record.optString("callerDigest") != digest || record.optString("providerDigest") != digest || record.optString("serverId") != serverId || record.optString("providerAccount") != accountId) denied("工作流账号或版本已改变")
        owned[record.getString("workflowId")] = fingerprint(record)
        consumedDevice.putAll(deviceGeneration)
    }
    @Synchronized fun requireRecord(record: JSONObject) { checkActive(); if (owned[record.optString("workflowId")] != fingerprint(record)) denied("此工作流不属于当前运行任务") }
    @Synchronized fun handles(): Set<String> { checkActive(); return owned.keys.toSet() }
    companion object {
        internal fun validate(manifest: PluginManifest, declaration: JSONObject, version: Int = 3) {
            val plan = declaration.getJSONObject("workflow")
            if (version < 3 || manifest.json.optInt("minAppVersionCode") < 132 || PluginJson.objects(manifest.json.optJSONArray("requires") ?: JSONArray()).none { it.optString("name") == "tasks.foreground.start" && it.optInt("version") >= 3 }) throw PluginException(PluginErrorCode.UNSUPPORTED, "持续工作流需要 App 132 和 tasks.foreground.start 版本 3")
            if (!manifest.usesProtectedServices || !setOf("tasks", "workflow", "services").all { it in manifest.permissions } || declaration.has("script") || declaration.has("batchInputKey") || declaration.getJSONArray("mutations").length() != 0) denied("持续工作流不能附加其他写入授权")
            if (PluginJson.objects(manifest.json.optJSONArray("services") ?: JSONArray()).none { it.optString("name") == plan.getString("name") && it.optInt("version") == plan.getInt("version") && it.optString("kind") == "write" && it.optString("serverId") == declaration.getString("serverId") }) denied("持续工作流需要同插件同服务账号的写入服务")
        }
        private fun validIdentity(value: Any?): Boolean = when (value) {
            is String -> value.isNotBlank()
            is JSONArray -> value.length() in 1..16 && (0 until value.length()).all { value.opt(it).let { v -> v is String && v.isNotBlank() || v is Number && v.toDouble().isFinite() && v.toDouble() % 1 == 0.0 } }
            else -> false
        }
        private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
        private fun denied(message: String): Nothing = throw PluginException(PluginErrorCode.PERMISSION_DENIED, message)
    }
}
