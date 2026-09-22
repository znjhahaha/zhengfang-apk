package com.tyust.course.academic.plugin

import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Validates routing and authority in addition to the shared structural schema. */
object ServicePluginContract {
    fun validateManifest(manifest: PluginManifest) {
        val config = manifest.service ?: invalid("校园服务缺少页面声明")
        if (manifest.json.getInt("apiVersion") !in 2..3 || manifest.baseProvider != null) invalid("校园服务需要 API 2 或 3，不能继承教务登录")
        if (manifest.capabilities.any { it !in PluginManifest.AUTH && it !in setOf("service.page", "service.action") } ||
            "service.page" !in manifest.capabilities) invalid("校园服务不能替换教务能力")
        val password = config.getJSONObject("authentication").getString("mode") == "password"
        if (password != manifest.capabilities.containsAll(PluginManifest.AUTH) || !password && manifest.capabilities.any { it in PluginManifest.AUTH })
            invalid("登录能力与认证方式不一致")
        val pages = unique(config.getJSONArray("pages"))
        unique(config.getJSONArray("entries"))
        unique(config.getJSONArray("actions"))
        PluginJson.objects(config.getJSONArray("entries")).forEach { if (it.getString("pageId") !in pages) invalid("入口引用了未声明的页面") }
        val actions = PluginJson.objects(config.getJSONArray("actions"))
        if (actions.isNotEmpty() != ("service.action" in manifest.capabilities)) invalid("操作声明与实现不一致")
        actions.forEach { if (it.getString("kind") == "mutation" && it.optString("confirmation").isBlank()) invalid("写入操作必须说明确认内容") }
        ServiceNativePolicy.validateManifest(manifest)
        val school = manifest.school
        val base = "${school.getString("protocol")}://${school.getString("domain")}${school.getString("basePath")}".toHttpUrlOrNull()
            ?: invalid("服务地址无效")
        if (base.username.isNotEmpty() || base.password.isNotEmpty() || base.query != null || base.fragment != null) invalid("服务地址不能包含凭据或查询参数")
    }

    fun matches(manifest: PluginManifest, school: SchoolConfig): Boolean {
        val config = manifest.service ?: return false
        return school.id in PluginJson.strings(config.getJSONArray("schoolIds")) ||
            school.domain.lowercase() in PluginJson.strings(config.optJSONArray("academicHosts"))
    }

    fun action(manifest: PluginManifest, id: String): JSONObject = PluginJson.objects(manifest.service?.getJSONArray("actions") ?: invalid("不是校园服务"))
        .firstOrNull { it.getString("id") == id } ?: invalid("操作未在插件中声明")

    fun requireRequest(manifest: PluginManifest, method: String, args: JSONObject, confirmed: Boolean) {
        if (!manifest.isService) { if (method.startsWith("service.")) invalid("教务适配不能调用校园服务接口"); return }
        if (method == "service.page") requirePageId(manifest, args.optString("pageId"))
        if (method == "service.action") {
            val declaration = action(manifest, args.optString("actionId"))
            if (declaration.getString("kind") == "mutation" && !confirmed) invalid("请先确认服务操作")
            args.optJSONObject("nativeResult")?.let { ServiceNativePolicy.validateResult(manifest, args.getString("actionId"), it) }
        }
        if (method.startsWith("service.")) validateParams(args.optJSONObject("params"))
    }

    fun validatePage(manifest: PluginManifest, page: JSONObject) {
        requirePageId(manifest, page.getString("pageId"))
        unique(page.getJSONArray("blocks"))
        PluginJson.objects(page.getJSONArray("blocks")).forEach { block ->
            val type = block.getString("type")
            if (manifest.json.getInt("apiVersion") < 3 && type in ServiceNativePolicy.NEW_BLOCKS) invalid("此页面组件需要 API 3")
            if (type == "table") {
                unique(block.getJSONArray("rows"))
                if (PluginJson.objects(block.getJSONArray("rows")).any { it.getJSONArray("cells").length() != block.getJSONArray("columns").length() }) invalid("表格列数不匹配")
            }
            val items = block.optJSONArray("items")
            if (items != null && block.getString("type") != "actions") unique(items)
            if (block.getString("type") == "form") {
                val fields = block.getJSONArray("fields"); unique(fields)
                action(manifest, block.getJSONObject("submit").getString("actionId"))
                PluginJson.objects(fields).forEach { field ->
                    if (field.getString("type") in setOf("multiline", "toggle") && manifest.json.getInt("apiVersion") < 3) invalid("此表单字段需要 API 3")
                    if (field.getString("type") == "toggle" && field.has("value") && field.getString("value") !in setOf("true", "false")) invalid("开关默认值无效")
                    if (field.getString("type") == "select") {
                        val options = field.optJSONArray("options") ?: invalid("选择字段缺少选项")
                        if (options.length() == 0) invalid("选择字段缺少选项")
                        val choices = PluginJson.objects(options).map { it.getString("value") }
                        if (choices.distinct().size != choices.size) invalid("选项标识重复")
                        if (field.has("value") && field.getString("value") !in choices) invalid("默认值不在选项中")
                    }
                }
            }
            items?.let { PluginJson.objects(it).forEach { item -> item.optJSONObject("action")?.let { link -> validateLink(manifest, link) } } }
        }
    }

    fun validateLink(manifest: PluginManifest, link: JSONObject) {
        when (link.getString("type")) {
            "page" -> requirePageId(manifest, link.getString("pageId"))
            "action" -> action(manifest, link.getString("actionId"))
            "url" -> {
                val url = link.getString("url").toHttpUrlOrNull() ?: invalid("服务链接无效")
                PluginNetworkPolicy(manifest.network).requireAllowed(url, "GET", "query", null)
            }
            "native" -> ServiceNativePolicy.request(manifest, link)
            else -> invalid("不支持的页面操作")
        }
        validateParams(link.optJSONObject("params"))
    }

    private fun requirePageId(manifest: PluginManifest, id: String) {
        if (PluginJson.objects(manifest.service?.getJSONArray("pages") ?: invalid("不是校园服务")).none { it.getString("id") == id }) invalid("页面未在插件中声明")
    }
    private fun validateParams(params: JSONObject?) {
        if (params == null) return
        if (params.length() > 20 || params.keys().asSequence().any { it.length !in 1..64 || params.opt(it) !is String || params.getString(it).length > 1000 }) invalid("页面参数超过限制")
    }
    private fun unique(array: JSONArray): Set<String> {
        val ids = PluginJson.objects(array).map { it.getString("id") }
        if (ids.distinct().size != ids.size) invalid("页面元素标识重复")
        return ids.toSet()
    }
    private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
}
