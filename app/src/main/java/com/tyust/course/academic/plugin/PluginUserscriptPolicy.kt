package com.tyust.course.academic.plugin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** The upstream match list never enlarges the host's independently reviewed declaration. */
internal class PluginUserscriptPolicy(val declaration: JSONObject, val manifest: PluginManifest) {
    val id: String = declaration.getString("id")
    val serverId: String = declaration.getString("serverId")
    private val matches = PluginJson.objects(declaration.getJSONArray("matches"))
    private val excluded = PluginJson.objects(declaration.optJSONArray("exclude") ?: JSONArray())
    private val connect = PluginJson.strings(declaration.getJSONArray("connect")).toSet()
    private val source = declaration.getString("sourcePrefix").toHttpUrlOrNull()
        ?: invalid("脚本订阅来源无效")
    private fun route(url: HttpUrl, rules: List<JSONObject>) = rules.any {
        url.host == it.getString("host") && (url.encodedPath == it.getString("pathPrefix") ||
            url.encodedPath.startsWith(it.getString("pathPrefix").trimEnd('/') + "/"))
    }
    private fun safe(url: HttpUrl) = url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
        !Regex("(?i)%2f|%5c|%00").containsMatchIn(url.encodedPath)
    fun executes(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        safe(it) && route(it, matches) && !route(it, excluded)
    } == true
    fun resource(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        safe(it) && it.host in connect && !route(it, excluded) && manifest.network.any { rule ->
            val origin = rule.getString("origin").toHttpUrlOrNull()
            origin?.host == it.host && origin.port == it.port && origin.scheme == it.scheme
        }
    } == true
    fun source(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: invalid("无效脚本下载地址")
        if (!safe(url) || url.fragment != null || url.query != null || source.scheme != url.scheme ||
            source.host != url.host || source.port != url.port || !url.encodedPath.startsWith(source.encodedPath))
            invalid("脚本下载超出订阅来源")
        return url
    }
    fun request(url: HttpUrl, method: String, purpose: String, form: JSONObject?) {
        if (method in setOf("PUT", "HEAD") && method !in PluginJson.strings(declaration.optJSONArray("requestMethods")))
            throw PluginException(PluginErrorCode.PERMISSION_DENIED, "脚本未声明 $method 请求")
        if (method == "HEAD" && purpose != "query")
            throw PluginException(PluginErrorCode.PERMISSION_DENIED, "HEAD 只用于读取请求")
        if (!resource(url.toString())) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "脚本请求超出声明的站点")
        PluginNetworkPolicy(manifest.network).requireAllowed(url, method, purpose, form)
    }
    fun origins(): Set<String> = manifest.network.map { it.getString("origin") }.filter {
        it.toHttpUrlOrNull()?.host in connect
    }.toSet()
    companion object {
        fun declaration(manifest: PluginManifest, id: String): JSONObject =
            PluginJson.objects(manifest.json.optJSONArray("userscripts") ?: JSONArray()).singleOrNull { it.getString("id") == id }
                ?: invalid("插件未声明这个脚本订阅")
        fun validate(manifest: PluginManifest) {
            val servers = PluginJson.objects(manifest.json.optJSONArray("servers") ?: JSONArray())
            for (server in servers) {
                for (origin in PluginJson.strings(server.optJSONArray("cookieOrigins"))) {
                    val url = origin.toHttpUrlOrNull() ?: invalid("Cookie 来源无效")
                    if (server.optString("authentication") != "web" || !url.isHttps ||
                        url.username.isNotEmpty() || url.password.isNotEmpty() || '*' in url.host ||
                        url.encodedPath != "/" || url.query != null || url.fragment != null ||
                        url.toString().removeSuffix("/") != origin ||
                        manifest.network.none { it.getString("origin") == origin }) invalid("Cookie 共享需要准确的网络来源及网页账号")
                }
            }
            val scripts = PluginJson.objects(manifest.json.optJSONArray("userscripts") ?: JSONArray())
            for (method in listOf("PUT", "HEAD")) {
                if (manifest.network.any { rule -> method in PluginJson.strings(rule.optJSONArray("methods")) && scripts.none { script ->
                        method in PluginJson.strings(script.optJSONArray("requestMethods")) && rule.getString("origin").toHttpUrlOrNull()?.host in PluginJson.strings(script.getJSONArray("connect")) } })
                    invalid("$method 只能用于已声明的脚本请求")
            }
            if ((scripts.isNotEmpty() || servers.any { it.has("cookieOrigins") } || PluginJson.objects(manifest.contributes.optJSONArray("tasks") ?: JSONArray()).any { it.has("foreground") }) && manifest.json.optInt("minAppVersionCode") < 111) invalid("脚本与持续会话需要 App 111")
            if (scripts.map { it.getString("id") }.distinct().size != scripts.size) invalid("脚本订阅 ID 重复")
            fun requires(name: String) {
                if (PluginJson.objects(manifest.json.optJSONArray("requires") ?: JSONArray()).none { it.getString("name") == name && it.getInt("version") >= 1 }) invalid("必须声明 $name 版本 1")
            }
            for (server in servers.filter { it.has("browser") }) {
                if (!manifest.isNative || server.optString("authentication") != "web" || manifest.json.optInt("minAppVersionCode") < 112) invalid("嵌入浏览器需要原生网页账号和 App 112")
                requires("browser.session.open")
            }
            for (script in scripts) {
                if (script.has("requestMethods") && (manifest.json.optInt("minAppVersionCode") < 112 ||
                    PluginJson.objects(manifest.json.optJSONArray("requires") ?: JSONArray()).none { it.optString("name") == "userscript.start" && it.optInt("version") >= 2 } ||
                    servers.none { it.optString("id") == script.optString("serverId") && it.optJSONObject("browser")?.optString("engine") == "embedded" })) invalid("脚本扩展请求方法需要 userscript.start 版本 2 和嵌入引擎")
                if (!manifest.isNative || !manifest.permissions.containsAll(setOf("network", "userscripts"))) invalid("脚本需要原生插件、网络和脚本权限")
                requires("userscript.start")
                val policy = PluginUserscriptPolicy(script, manifest)
                if (servers.none { it.getString("id") == policy.serverId && it.getString("authentication") == "web" }) invalid("脚本需要独立网页账号")
                val source = script.getString("sourcePrefix").toHttpUrlOrNull() ?: invalid("订阅来源无效")
                if (!source.encodedPath.endsWith('/')) invalid("订阅来源必须是目录")
                policy.source(source.toString()); policy.source(script.getString("downloadUrl")); policy.source(script.getString("updateUrl"))
                for (route in PluginJson.objects(script.getJSONArray("matches")) + PluginJson.objects(script.optJSONArray("exclude") ?: JSONArray())) validateRoute(route)
                for (host in PluginJson.strings(script.getJSONArray("connect"))) if (!host.matches(Regex("[a-z0-9.-]+")) || manifest.network.none { it.getString("origin").toHttpUrlOrNull()?.host == host }) invalid("脚本联网来源未声明")
                val adapter = script.getJSONObject("adapter")
                if (adapter.optBoolean("browserPrompts")) {
                    requires("userscript.interact")
                    if (manifest.json.optInt("minAppVersionCode") < 112 || servers.none { it.optString("id") == policy.serverId && it.optJSONObject("browser")?.optString("engine") == "embedded" }) invalid("原生脚本提示需要嵌入引擎和 App 112")
                }
                val settings = PluginJson.objects(adapter.getJSONArray("settings"))
                for ((key, capability) in listOf("dialogs" to "userscript.interact", "actions" to "userscript.action")) {
                    if (!adapter.has(key)) continue
                    requires(capability)
                    if (manifest.json.optInt("minAppVersionCode") < 112 || servers.none { it.getString("id") == policy.serverId && it.optJSONObject("browser")?.optString("engine") == "embedded" }) invalid("原生脚本操作需要嵌入引擎和 App 112")
                    val definitions = PluginJson.objects(adapter.getJSONArray(key))
                    if (definitions.map { it.getString("id") }.distinct().size != definitions.size) invalid("脚本操作 ID 重复")
                    if (key == "dialogs" && definitions.any { dialog -> PluginJson.objects(dialog.getJSONArray("actions")).let { actions -> actions.map { it.getString("id") }.distinct().size != actions.size } }) invalid("脚本提示操作 ID 重复")
                }
                if (settings.any { it.has("default") && PluginUserscriptStore.normalizedSetting(it, it.opt("default")) == null }) invalid("脚本设置默认值无效")
                if (adapter.optString("entrySelector").isNotEmpty() || adapter.optString("promptSelector").isNotEmpty() || settings.any { it.optString("enabledClass").isNotEmpty() }) {
                    if (manifest.json.optInt("minAppVersionCode") < 112 || servers.none { it.getString("id") == policy.serverId && it.optJSONObject("browser")?.optString("engine") == "embedded" })
                        invalid("入口、提示及样式开关适配需要嵌入浏览器和 App 112")
                }
                val sensitive = PluginJson.strings(script.optJSONArray("sensitiveKeys"))
                if (sensitive.isNotEmpty()) {
                    if (manifest.json.optInt("minAppVersionCode") < 112 || settings.any { it.getString("key") in sensitive }) invalid("敏感 GM 键需要 App 112，且不能作为公开设置")
                    requires("userscript.configure")
                }
                if (settings.map { it.getString("key") }.distinct().size != settings.size) invalid("脚本设置重复")
                if (settings.any { it.getString("type") == "number" && (!it.has("min") || !it.has("max") || it.getDouble("min") >= it.getDouble("max")) }) invalid("数值设置需要上下界")
            }
            for (task in PluginJson.objects(manifest.contributes.optJSONArray("tasks") ?: JSONArray())) {
                val foreground = task.optJSONObject("foreground") ?: continue
                requires("tasks.foreground.start")
                if (!manifest.isNative || "tasks" !in manifest.permissions || servers.none { it.getString("id") == foreground.getString("serverId") }) invalid("持续任务需要服务账号及任务权限")
                for (route in PluginJson.objects(foreground.getJSONArray("mutations"))) {
                    validateRoute(route)
                    if (manifest.network.none { rule -> rule.getString("origin").toHttpUrlOrNull()?.host == route.getString("host") && "mutation" in PluginJson.strings(rule.getJSONArray("purposes")) &&
                            (route.getString("pathPrefix") == rule.getString("pathPrefix") || route.getString("pathPrefix").startsWith(rule.getString("pathPrefix").trimEnd('/') + "/")) }) invalid("持续任务提交端点未声明")
                }
            }
        }
        private fun validateRoute(route: JSONObject) {
            val path = route.getString("pathPrefix"); val host = route.getString("host")
            if (!host.matches(Regex("[a-z0-9.-]+")) || !host.contains('.') || host.startsWith('.') || !path.startsWith('/') ||
                path.any { it.code !in 33..126 || it in "\\%?#" } || path.split('/').any { it == "." || it == ".." }) invalid("脚本或任务路径无效")
        }
        private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
    }
}

internal data class UserscriptMetadata(val values: Map<String, List<String>>) {
    val version get() = values["version"]?.singleOrNull().orEmpty()
    fun one(key: String) = values[key]?.singleOrNull().orEmpty()
    fun json() = JSONObject().put("version", version).put("name", one("name")).put("author", one("author"))
        .put("license", one("license")).put("updateUrl", one("updateURL")).put("downloadUrl", one("downloadURL"))
    companion object {
        private val grants = setOf("none", "unsafeWindow", "GM_info", "GM_xmlhttpRequest", "GM_getValue", "GM_setValue", "GM_addStyle", "GM_openInTab")
        fun parse(source: String): UserscriptMetadata {
            if (source.toByteArray().size > 2 * 1024 * 1024) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "脚本超过 2 MiB")
            val header = Regex("(?s)^\\s*// ==UserScript==\\s*(.*?)// ==/UserScript==").find(source)?.groupValues?.get(1)
                ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "缺少原脚本元信息")
            val values = Regex("(?m)^//\\s*@([\\w:-]+)\\s+([^\\r\\n]+)").findAll(header).groupBy({ it.groupValues[1] }, { it.groupValues[2].trim() })
            val metadata = UserscriptMetadata(values)
            if (metadata.version.isBlank() || metadata.one("name").isBlank() || metadata.version.length > 64) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "脚本版本或名称无效")
            return metadata
        }
        fun validate(source: String, policy: PluginUserscriptPolicy): UserscriptMetadata {
            val metadata = parse(source)
            val unsupported = metadata.values["grant"].orEmpty().filter { it !in grants }
            if (unsupported.isNotEmpty() || metadata.values.containsKey("require") || metadata.values.containsKey("resource") ||
                Regex("\\bnew\\s+(?:Worker|SharedWorker)\\s*\\(").containsMatchIn(source) ||
                metadata.one("run-at").ifBlank { "document-end" } !in setOf("document-end", "document-idle"))
                throw PluginException(PluginErrorCode.UNSUPPORTED, "新版脚本需要尚未支持的运行能力，继续保留上一可用版本")
            policy.source(metadata.one("updateURL")); policy.source(metadata.one("downloadURL"))
            return metadata
        }
    }
}
