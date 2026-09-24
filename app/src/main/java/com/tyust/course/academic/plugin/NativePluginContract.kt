package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject

object NativePluginContract {
    fun validateManifest(manifest: PluginManifest) {
        if (manifest.apiVersion != 3 || !manifest.json.has("contributes") || !manifest.json.has("permissions")) invalid("原生插件需要 API v3、扩展点与权限声明")
        val c = manifest.contributes
        val pages = unique(c.getJSONArray("pages"))
        unique(c.getJSONArray("entries")); unique(c.optJSONArray("menuActions")); unique(c.optJSONArray("dataProviders")); unique(c.optJSONArray("tasks"))
        PluginJson.objects(c.getJSONArray("entries")).forEach { if (it.getString("pageId") !in pages) invalid("入口指向未声明页面") }
        c.optJSONArray("menuActions")?.let { actions -> PluginJson.objects(actions).forEach { if (it.has("pageId") && it.getString("pageId") !in pages) invalid("菜单指向未声明页面") } }
        val groups = listOf(Triple("ui.", PluginJson.objects(c.getJSONArray("pages")).any { it.optString("renderer") != "web" }, setOf("ui.init", "ui.reduce")),
            Triple("task.", (c.optJSONArray("tasks")?.length() ?: 0) > 0, setOf("task.run")),
            Triple("data.", (c.optJSONArray("dataProviders")?.length() ?: 0) > 0, setOf("data.query")))
        for ((prefix, required, expected) in groups) {
            val actual = manifest.capabilities.filter { it.startsWith(prefix) }.toSet()
            if (actual != if (required) expected else emptySet<String>()) invalid("扩展点与接口声明不一致")
        }
        if (c.optBoolean("academic") && !manifest.json.has("school") || !c.optBoolean("academic") && manifest.capabilities.any { it.startsWith("auth.") || it.startsWith("study.") || it.startsWith("selection.") }) invalid("教务接口需要显式声明教务扩展点")
        manifest.json.optJSONArray("matches")?.let { rules -> PluginJson.objects(rules).forEach(PluginSchoolMatcher::validateRule) }
    }

    fun validateResult(result: JSONObject, task: Boolean = false) {
        if (PluginJson.canonical(result.get("state")).toByteArray().size > PluginLimits.STATE_BYTES) invalid("页面状态超过 256 KiB")
        unique(result.getJSONArray("effects"))
        if (task) return
        val ids = mutableSetOf<String>()
        fun walk(node: JSONObject, depth: Int) {
            if (depth > 16 || ids.size >= 2000) invalid("组件数量或嵌套超过限制")
            if (!ids.add(node.getString("id"))) invalid("组件 ID 重复")
            if (node.getString("type") == "select") {
                val values = PluginJson.objects(node.getJSONArray("options")).map { it.getString("value") }
                if (values.distinct().size != values.size || node.getString("value") !in values) invalid("选择项或当前值无效")
            }
            if (node.getString("type") == "slider" && (node.getDouble("min") >= node.getDouble("max") || node.getDouble("value") !in node.getDouble("min")..node.getDouble("max"))) invalid("滑动条范围无效")
            node.optJSONArray("children")?.let { children -> PluginJson.objects(children).forEach { walk(it, depth + 1) } }
        }
        walk(result.getJSONObject("view"), 0)
    }
    fun page(manifest: PluginManifest, id: String): JSONObject = contribution(manifest, "pages", id)
    fun contribution(manifest: PluginManifest, kind: String, id: String): JSONObject = manifest.contributes.optJSONArray(kind)?.let(PluginJson::objects)?.firstOrNull { it.getString("id") == id }
        ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "插件未声明此扩展点")
    fun findNode(view: JSONObject, id: String): JSONObject? {
        if (view.optString("id") == id) return view
        view.optJSONArray("children")?.let { for (node in PluginJson.objects(it)) findNode(node, id)?.let { match -> return match } }
        return null
    }
    private fun unique(array: JSONArray?): Set<String> {
        val ids = array?.let(PluginJson::objects)?.map { it.getString("id") }.orEmpty()
        if (ids.toSet().size != ids.size) invalid("扩展点或效果 ID 重复")
        return ids.toSet()
    }
    private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
}

/** A completed effect ID cannot be replayed by a reducer in the same user interaction. */
class NativeFlow(val userGesture: Boolean) {
    private val ids = mutableSetOf<String>()
    private var uncertain = false
    private val startedAt = System.nanoTime()
    @Synchronized fun accept(id: String) {
        if (uncertain) throw PluginException(PluginErrorCode.RESULT_UNKNOWN, "上一步结果未知，请核实后重新操作")
        if (ids.size >= 32 || System.nanoTime() - startedAt > 600_000_000_000L) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "流程超过步骤或时间限制")
        if (!ids.add(id)) throw PluginException(PluginErrorCode.RESULT_UNKNOWN, "同一流程不能重放已执行效果")
    }
    @Synchronized fun markUnknown() { uncertain = true }
    @Synchronized fun failureCode(code: PluginErrorCode): PluginErrorCode =
        if (uncertain && code in setOf(PluginErrorCode.CANCELLED, PluginErrorCode.TIMEOUT)) PluginErrorCode.RESULT_UNKNOWN else code
}
