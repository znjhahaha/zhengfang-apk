package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object PluginPlatformContract {
    val methods = setOf("command.run", "services.invoke", "workflow.prepare", "workflow.step", "workflow.reconcile", "workflow.cancel")
    fun requirements(value: JSONObject, available: Map<String, Int>): List<String> =
        value.optJSONArray("requires")?.let(PluginJson::objects).orEmpty().filter { (available[it.getString("name")] ?: 0) < it.getInt("version") }.map { it.getString("name") }

    fun requireCompatible(manifest: PluginManifest, versionCode: Int, available: Map<String, Int>) {
        if (manifest.apiVersion !in 1..PluginLimits.API_VERSION || manifest.json.optInt("minAppVersionCode", 0) > versionCode || requirements(manifest.json, available).isNotEmpty())
            throw PluginException(PluginErrorCode.UNSUPPORTED, "当前 App 不满足插件的兼容要求")
    }
    fun validate(manifest: PluginManifest) {
        val m = manifest.json
        unique(m.optJSONArray("requires"), "name")
        val servers = m.optJSONArray("servers")?.let(PluginJson::objects).orEmpty()
        val services = m.optJSONArray("services")?.let(PluginJson::objects).orEmpty()
        val dependencies = m.optJSONArray("serviceDependencies")?.let(PluginJson::objects).orEmpty()
        if (!manifest.isNative && (servers.isNotEmpty() || services.isNotEmpty() || dependencies.isNotEmpty() || manifest.capabilities.any { it in methods })) invalid("服务契约需要原生扩展类型")
        unique(m.optJSONArray("servers"))
        if (services.map { it.getString("name") to it.getInt("version") }.distinct().size != services.size || dependencies.map { it.getString("name") to it.getInt("version") }.distinct().size != dependencies.size) invalid("服务声明重复")
        for (server in servers) {
            val origin = server.getString("origin")
            val uri = runCatching { URI(origin) }.getOrNull() ?: invalid("无效服务来源")
            if (uri.scheme !in setOf("http", "https") || uri.host == null || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.rawPath.orEmpty().isNotEmpty() || origin != "${uri.scheme}://${uri.rawAuthority}") invalid("服务必须声明准确来源")
            if (uri.scheme == "http" && uri.host !in setOf("localhost", "127.0.0.1", "[::1]")) invalid("非本地服务必须使用 HTTPS")
            for (pageId in PluginJson.strings(server.optJSONArray("pages"))) if (manifest.contributes.optJSONArray("pages")?.let(PluginJson::objects).orEmpty().none { it.getString("id") == pageId && it.optString("serverId") == server.getString("id") }) invalid("服务页面声明不一致")
        }
        if (!manifest.isNative) return
        val c = manifest.contributes
        unique(c.optJSONArray("commands")); unique(c.optJSONArray("settings"))
        for (page in PluginJson.objects(c.getJSONArray("pages"))) {
            unique(page.optJSONArray("requires"), "name")
            if (page.optString("renderer") == "web") {
                val server = servers.firstOrNull { it.getString("id") == page.optString("serverId") } ?: invalid("网页需要声明服务器")
                val path = page.optString("path", "/")
                val uri = runCatching { URI(path) }.getOrNull() ?: invalid("无效网页路径")
                if (!path.startsWith("/") || path.startsWith("//") || uri.rawAuthority != null || uri.rawFragment != null || path.contains('\\') || path.contains('%') || uri.normalize() != uri) invalid("无效网页路径")
                val url = server.getString("origin") + path
                PluginNetworkPolicy(manifest.network).requireAllowed(okhttp3.HttpUrl.Companion.run { url.toHttpUrl() }, "GET", "query", null)
            } else {
                if (page.has("path")) invalid("只有网页可以声明地址路径")
                if (page.has("serverId") && servers.none { it.getString("id") == page.getString("serverId") }) invalid("原生服务页面需要已声明的服务器")
            }
        }
        for (command in c.optJSONArray("commands")?.let(PluginJson::objects).orEmpty()) unique(command.optJSONArray("requires"), "name")
        for (setting in c.optJSONArray("settings")?.let(PluginJson::objects).orEmpty()) {
            if (setting.has("default")) PluginSchema(JSONObject().put("type", setting.getString("type"))).validate(setting.get("default"))
            if (setting.optString("scope") == "service" && !(if (setting.has("serverId")) servers.any { it.getString("id") == setting.getString("serverId") } else servers.size == 1)) invalid("服务设置必须指定已声明的服务器")
            if (setting.has("serverId") && setting.optString("scope") != "service") invalid("仅服务设置可以指定服务器")
        }
        for (service in services) {
            validateServiceSchema(service.getJSONObject("input")); validateServiceSchema(service.getJSONObject("output"))
            if (service.has("serverId") && servers.none { it.getString("id") == service.getString("serverId") }) invalid("服务引用未声明服务器")
        }
        val groups = listOf(Triple("command.", c.optJSONArray("commands")?.length()?.let { it > 0 } == true, setOf("command.run")),
            Triple("services.", services.any { it.getString("kind") == "read" }, setOf("services.invoke")),
            Triple("workflow.", services.any { it.getString("kind") == "write" }, setOf("workflow.prepare", "workflow.step", "workflow.reconcile", "workflow.cancel")))
        for ((prefix, required, names) in groups) if (manifest.capabilities.filter { it.startsWith(prefix) }.toSet() != if (required) names else emptySet<String>()) invalid("服务或命令与实现接口不一致")
    }
    fun validateServiceSchema(schema: JSONObject, depth: Int = 0) {
        val allowed = setOf("type", "properties", "required", "additionalProperties", "items", "minItems", "maxItems", "uniqueItems", "minLength", "maxLength", "pattern", "minimum", "maximum", "enum", "const", "anyOf")
        if (depth > 12 || schema.keys().asSequence().any { it !in allowed } || schema.has("type") && schema.optString("type") !in setOf("object", "array", "string", "number", "integer", "boolean", "null")) invalid("服务结构超出可移植的 Schema 子集")
        if (schema.has("pattern")) { if (schema.getString("pattern").length > 256) invalid("Schema 正则过长"); runCatching { Regex(schema.getString("pattern")) }.getOrElse { invalid("无效 Schema 正则") } }
        schema.optJSONObject("properties")?.let { props -> if (props.length() > 64) invalid("Schema 属性过多"); props.keys().forEach { validateServiceSchema(props.getJSONObject(it), depth + 1) } }
        for (key in PluginJson.strings(schema.optJSONArray("required"))) if (schema.optJSONObject("properties")?.has(key) != true) invalid("Schema 必需属性未定义")
        schema.optJSONObject("items")?.let { validateServiceSchema(it, depth + 1) }
        schema.optJSONObject("additionalProperties")?.let { validateServiceSchema(it, depth + 1) }
        schema.optJSONArray("anyOf")?.let { if (it.length() !in 1..8) invalid("Schema 分支过多"); PluginJson.objects(it).forEach { s -> validateServiceSchema(s, depth + 1) } }
    }
    private fun unique(array: JSONArray?, key: String = "id") { val ids = array?.let(PluginJson::objects).orEmpty().map { it.getString(key) }; if (ids.distinct().size != ids.size) invalid("声明重复") }
    private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
}
