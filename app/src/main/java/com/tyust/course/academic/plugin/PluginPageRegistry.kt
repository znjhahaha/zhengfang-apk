package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject

data class PluginPage(val id: String, val title: String, val pluginId: String? = null, val templateId: String = "", val renderer: String = "native", val icon: String = "activity", val params: JSONObject = JSONObject())

/** Route identity never depends on a tab's index, package digest or display title. */
class PluginPageRegistry(initial: JSONObject = JSONObject(), private val persist: (JSONObject) -> Unit = {}) {
    private var state = JSONObject(initial.toString())
    private var manifests = emptyMap<String, PluginManifest>()
    private var enabled = emptySet<String>()
    private var available = emptyMap<String, Int>()
    private var catalog = builtins.associateBy { it.id }
    @Synchronized fun synchronize(plugins: List<PluginManifest>, enabledIds: Set<String>, capabilities: Map<String, Int>) {
        manifests = plugins.associateBy { it.id }; enabled = enabledIds; available = capabilities
        val installedIds = manifests.keys
        val instances = state.optJSONObject("instances") ?: JSONObject()
        instances.keys().asSequence().toList().filter { instances.getJSONObject(it).optString("pluginId") !in installedIds }.forEach(instances::remove)
        state.put("instances", instances)
        rebuild(); save()
    }
    @Synchronized fun pages(): List<PluginPage> = catalog.values.toList()
    @Synchronized fun page(id: String): PluginPage? = catalog[id]
    @Synchronized fun pinned(): List<String> {
        val desired = state.optJSONArray("pinned")?.let(PluginJson::strings) ?: defaults
        val result = desired.filter { it in catalog }.distinct().take(5)
        return if (SETTINGS in result) result else result.take(4) + SETTINGS
    }
    @Synchronized fun customize(ids: List<String>) {
        if (ids.size !in 1..5 || ids.distinct().size != ids.size || SETTINGS !in ids || ids.any { it !in catalog }) invalid("主导航最多五个入口，且必须保留设置")
        state.put("pinned", JSONArray(ids)); save()
    }
    @Synchronized fun restoreDefaults() { state.remove("pinned"); save() }
    @Synchronized fun setStartup(id: String) { if (id !in catalog) invalid("启动页不可用"); state.put("startup", id); save() }
    @Synchronized fun startup(legacy: String): String = state.optString("startup").takeIf { it in catalog } ?: legacy.takeIf { it in catalog } ?: fallback()
    @Synchronized fun fallback(): String = defaults.firstOrNull { it != SETTINGS && it in pinned() } ?: SETTINGS
    @Synchronized fun register(pluginId: String, templateId: String, instanceId: String, title: String? = null, params: JSONObject = JSONObject()): PluginPage {
        val manifest = manifests[pluginId]?.takeIf { pluginId in enabled } ?: invalid("插件已停用")
        val template = NativePluginContract.page(manifest, templateId)
        if (!instanceId.matches(Regex("[a-zA-Z][a-zA-Z0-9_.:-]{0,95}")) || !template.optBoolean("dynamic") && instanceId != templateId || title != null && (title.isBlank() || title.length > 160)) invalid("只能从自己的页面模板创建页面")
        if (PluginPlatformContract.requirements(template, available).isNotEmpty()) invalid("当前 App 不支持此页面")
        if (PluginJson.canonical(params).toByteArray().size > 16384) invalid("页面参数超过限制")
        val route = "$pluginId/$instanceId"
        val instances = state.optJSONObject("instances") ?: JSONObject()
        if (!instances.has(route) && instances.length() >= 200) invalid("注册页面过多")
        val collision = catalog[route]
        if (collision != null && collision.templateId != templateId) invalid("页面 ID 已被使用")
        instances.put(route, JSONObject().put("pluginId", pluginId).put("templateId", templateId).put("instanceId", instanceId).put("title", title ?: template.getString("title")).put("params", params))
        state.put("instances", instances)
        val hidden = PluginJson.strings(state.optJSONArray("suppressed")).filter { it != route }
        state.put("suppressed", JSONArray(hidden)); rebuild(); save()
        return catalog[route] ?: invalid("页面不可用")
    }
    @Synchronized fun unregister(pluginId: String, route: String) {
        if (!route.startsWith("$pluginId/") || pluginId !in manifests) invalid("插件只能移除自己的页面")
        state.optJSONObject("instances")?.remove(route)
        state.put("suppressed", JSONArray((PluginJson.strings(state.optJSONArray("suppressed")) + route).distinct()))
        rebuild(); save()
    }
    @Synchronized fun clearPlugin(pluginId: String) {
        val prefix = "$pluginId/"
        state.optJSONObject("instances")?.let { obj -> obj.keys().asSequence().toList().filter { it.startsWith(prefix) }.forEach(obj::remove) }
        state.put("suppressed", JSONArray(PluginJson.strings(state.optJSONArray("suppressed")).filterNot { it.startsWith(prefix) }))
        state.optJSONArray("pinned")?.let { state.put("pinned", JSONArray(PluginJson.strings(it).filterNot { id -> id.startsWith(prefix) })) }
        manifests = manifests - pluginId; enabled = enabled - pluginId; rebuild(); save()
    }
    private fun rebuild() {
        val next = builtins.associateBy { it.id }.toMutableMap()
        val suppressed = PluginJson.strings(state.optJSONArray("suppressed")).toSet()
        for ((id, m) in manifests) if (id in enabled) {
            val pages = m.contributes.optJSONArray("pages")?.let(PluginJson::objects).orEmpty()
            for (page in pages) if (!page.optBoolean("dynamic") && PluginPlatformContract.requirements(page, available).isEmpty()) {
                val route = "$id/${page.getString("id")}"; if (route !in suppressed) next[route] = from(id, page, page.getString("id"), page.getString("title"), JSONObject())
            }
        }
        state.optJSONObject("instances")?.let { instances -> for (route in instances.keys()) {
            val record = instances.getJSONObject(route); val id = record.getString("pluginId")
            val m = manifests[id] ?: continue
            if (id !in enabled || route in suppressed) continue
            val template = runCatching { NativePluginContract.page(m, record.getString("templateId")) }.getOrNull() ?: continue
            if (PluginPlatformContract.requirements(template, available).isEmpty()) next[route] = from(id, template, record.getString("instanceId"), record.getString("title"), record.optJSONObject("params") ?: JSONObject())
        } }
        catalog = next
    }
    private fun from(id: String, template: JSONObject, instanceId: String, title: String, params: JSONObject) = PluginPage("$id/$instanceId", title, id, template.getString("id"), template.optString("renderer", "native"), template.optString("icon", "activity"), params)
    private fun save() { persist(JSONObject(state.toString())) }
    private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.PERMISSION_DENIED, message)
    companion object {
        const val SETTINGS = "app.settings"
        const val SERVICES = "app.services"
        const val SCHEDULE = "app.schedule"
        val defaults = listOf("app.courses", SCHEDULE, "app.grab", "app.grades", SETTINGS)
        val builtins = listOf(PluginPage("app.courses", "课程"), PluginPage(SCHEDULE, "课表"), PluginPage("app.grab", "抢课"), PluginPage("app.grades", "成绩"), PluginPage(SETTINGS, "设置"), PluginPage(SERVICES, "服务中心"))
        fun legacyTab(index: Int) = defaults.getOrElse(index) { SCHEDULE }
    }
}
