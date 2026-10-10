package com.tyust.course.academic.plugin

import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Host responsibility is selecting an approved school authority; protocol execution is TypeScript. */
object GenericAcademicProtocols {
    // Only immutable execution material is cached. Account sessions, enabled state,
    // permissions and revocation are still resolved by the registry on every call.
    private data class BoundMaterial(val manifest: String, val source: String, val digest: String,
        val official: Boolean, val bundled: Boolean, val publisher: String?) {
        // JSONObject is mutable: never share it between adapters or callers.
        fun packageCopy() = PluginPackage(PluginManifest(JSONObject(manifest)), source, digest, official, bundled, publisher)
    }
    private val boundPackages = object : LinkedHashMap<List<Any?>, BoundMaterial>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, BoundMaterial>?) = size > 16
    }
    val providers = mapOf("zf" to "org.zf.protocol.zf", "legacy_zf" to "org.zf.protocol.zf",
        "zf_old" to "org.zf.protocol.zf-old", "qz" to "org.zf.protocol.qz", "qz_old" to "org.zf.protocol.qz-old",
        "eams" to "org.zf.protocol.eams", "chaoxing_academic" to "org.zf.protocol.chaoxing-academic")
    fun configuration(school: SchoolConfig, options: JSONObject? = null): JSONObject = JSONObject(school.toJson().toString()).put("baseUrl", school.fullBasePath.trimEnd('/')).apply {
        val url = school.fullBasePath.toHttpUrlOrNull() ?: error("Invalid school URL")
        if (url.host == "jwxt.hut.edu.cn" && url.port in setOf(80, 443) && url.encodedPath.trimEnd('/') == "/jsxsd") {
            put("casLoginUrl", "https://mycas.hut.edu.cn/cas/login")
            put("casServiceUrl", "http://jwxt.hut.edu.cn/jsxsd/sso.jsp")
        }
        AcademicProtocolOptions.resolve(school.academicSystem, school.fullBasePath.trimEnd('/'), options)?.let { put("protocolOptions", it) }
    }
    fun network(school: SchoolConfig, options: JSONObject? = null): JSONArray {
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
        val config = configuration(school, options)
        if (config.has("casLoginUrl")) {
            add(config.getString("casLoginUrl"), "/cas", listOf("auth"))
            add(config.getString("casServiceUrl"), "/jsxsd", listOf("auth"))
        }
        config.optJSONObject("protocolOptions")?.optString("loginUrl")?.takeIf { it.isNotBlank() }?.let { login ->
            val path = login.toHttpUrlOrNull()!!.encodedPath.substringBeforeLast('/').ifBlank { "/" }
            add(login, path, listOf("auth"))
        }
        return result
    }
    fun bind(base: PluginPackage, school: SchoolConfig, parent: PluginPackage? = null): PluginPackage {
        require(base.manifest.id in providers.values && (base.bundled || base.official))
        val snapshot = SchoolConfig.fromJson(school.toJson())
        parent?.manifest?.baseProvider?.removePrefix("builtin.")?.let { snapshot.academicSystem = it }
        if (parent != null) {
            val declared = PluginSchoolMatcher.endpoint(SchoolConfig.fromJson(parent.manifest.school))
            val actual = PluginSchoolMatcher.endpoint(snapshot)
            if (declared == null || actual == null || declared.scheme != actual.scheme || declared.host != actual.host ||
                declared.port != actual.port || declared.encodedPath.trimEnd('/') != actual.encodedPath.trimEnd('/'))
                throw PluginException(PluginErrorCode.VALIDATION_FAILED, "插件继承配置与当前学校不一致")
            require(base.manifest.id == providers[snapshot.academicSystem])
        }
        val options = parent?.manifest?.json?.optJSONObject("builtinConfig")
        val config = configuration(snapshot, options)
        val configText = PluginJson.canonical(config)
        val cacheKey = listOf(base.digest, base.official, base.bundled, base.publisher,
            parent?.digest, parent?.official, parent?.bundled, parent?.publisher, configText)
        val cached = synchronized(boundPackages) { boundPackages[cacheKey] }
        if (cached != null) return cached.packageCopy()
        val source = "globalThis.__builtinAcademicConfig=" + configText + ";\n" + base.source
        val identity = parent ?: base
        val manifest = JSONObject(base.manifest.json.toString()).put("id", identity.manifest.id).put("version", identity.manifest.version)
            .put("school", parent?.manifest?.school ?: JSONObject().put("id", snapshot.id).put("name", snapshot.name)
                .put("domain", snapshot.domain).put("protocol", snapshot.protocol).put("basePath", snapshot.basePath).put("academicSystem", snapshot.academicSystem))
            .put("network", network(snapshot, options)).put("files", JSONObject().put("index.js", PluginJson.sha256(source.toByteArray())))
        parent?.let {
            PluginAcademicTokenRule.inherit(it.manifest, manifest)
            it.manifest.json.optJSONArray("sharedOperations")?.let { rules -> manifest.put("sharedOperations", org.json.JSONArray(rules.toString())) }
        }
        val result = BoundMaterial(manifest.toString(), source, PluginJson.sha256((PluginJson.canonical(manifest) + source).toByteArray()), identity.official, identity.bundled, identity.publisher)
        return synchronized(boundPackages) { boundPackages.getOrPut(cacheKey) { result } }.packageCopy()
    }
}
