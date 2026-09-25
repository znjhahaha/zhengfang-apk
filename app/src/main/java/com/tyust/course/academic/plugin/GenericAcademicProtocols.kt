package com.tyust.course.academic.plugin

import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Host responsibility is selecting an approved school authority; protocol execution is TypeScript. */
object GenericAcademicProtocols {
    val providers = mapOf("zf" to "org.zf.protocol.zf", "legacy_zf" to "org.zf.protocol.zf",
        "zf_old" to "org.zf.protocol.zf-old", "qz" to "org.zf.protocol.qz", "qz_old" to "org.zf.protocol.qz-old")
    fun configuration(school: SchoolConfig): JSONObject = JSONObject(school.toJson().toString()).put("baseUrl", school.fullBasePath.trimEnd('/')).apply {
        val url = school.fullBasePath.toHttpUrlOrNull() ?: error("Invalid school URL")
        if (url.host == "jwxt.hut.edu.cn" && url.port in setOf(80, 443) && url.encodedPath.trimEnd('/') == "/jsxsd") {
            put("casLoginUrl", "https://mycas.hut.edu.cn/cas/login")
            put("casServiceUrl", "http://jwxt.hut.edu.cn/jsxsd/sso.jsp")
        }
    }
    fun network(school: SchoolConfig): JSONArray {
        val base = school.fullBasePath.toHttpUrlOrNull() ?: error("Invalid school URL")
        val result = JSONArray()
        fun add(url: String, path: String, purposes: List<String>) {
            val parsed = url.toHttpUrlOrNull() ?: return
            val rule = JSONObject().put("origin", parsed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'))
                .put("pathPrefix", path.ifBlank { "/" }).put("methods", JSONArray(listOf("GET", "POST"))).put("purposes", JSONArray(purposes))
            if (school.userAgent.isNotBlank()) rule.put("userAgent", school.userAgent)
            PluginNetworkPolicy.validateRule(rule); result.put(rule)
        }
        add(base.toString(), base.encodedPath.trimEnd('/'), listOf("auth", "query", "mutation"))
        // The user's saved academic allowlist may contain a separate CAS host. It receives auth only.
        school.allowedAcademicHosts.filter { it != base.host && it != school.domain }.forEach { host ->
            if (!host.contains('/') && !host.contains('@')) add("${base.scheme}://$host/", "/", listOf("auth"))
        }
        val config = configuration(school)
        if (config.has("casLoginUrl")) {
            add(config.getString("casLoginUrl"), "/cas", listOf("auth"))
            add(config.getString("casServiceUrl"), "/jsxsd", listOf("auth"))
        }
        return result
    }
    fun bind(base: PluginPackage, school: SchoolConfig, parent: PluginPackage? = null): PluginPackage {
        require(base.manifest.id in providers.values && (base.bundled || base.official))
        val config = configuration(school)
        val source = "globalThis.__builtinAcademicConfig=" + PluginJson.canonical(config) + ";\n" + base.source
        val identity = parent ?: base
        val manifest = JSONObject(base.manifest.json.toString()).put("id", identity.manifest.id).put("version", identity.manifest.version)
            .put("school", parent?.manifest?.school ?: JSONObject().put("id", school.id).put("name", school.name)
                .put("domain", school.domain).put("protocol", school.protocol).put("basePath", school.basePath).put("academicSystem", school.academicSystem))
            .put("network", network(school)).put("files", JSONObject().put("index.js", PluginJson.sha256(source.toByteArray())))
        return PluginPackage(PluginManifest(manifest), source, PluginJson.sha256((PluginJson.canonical(manifest) + source).toByteArray()), identity.official, identity.bundled)
    }
}
