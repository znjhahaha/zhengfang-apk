package com.tyust.course.academic.plugin

import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Explicit inheritance may reuse a protocol on another school; defaults must never select the reference school. */
object BuiltinAcademicInheritance {
    val providers = mapOf("builtin.jinzhi" to "cn.edu.huat.neweas", "builtin.chengfang" to "cn.edu.sdipct.chengfang")
    private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
    private fun https(value: String, query: Boolean = false): String {
        val u = value.toHttpUrlOrNull() ?: invalid("内置教务地址无效")
        if (!u.isHttps || u.username.isNotEmpty() || u.password.isNotEmpty() || u.fragment != null || !query && u.query != null || u.encodedPath.any { it in "%\\" })
            invalid("内置教务地址须为明确的 HTTPS 地址")
        return u.toString()
    }
    fun configuration(manifest: PluginManifest): JSONObject? {
        if (manifest.baseProvider !in providers) {
            if (manifest.json.has("builtinConfig")) invalid("builtinConfig 仅用于金智或乘方继承")
            return null
        }
        if (manifest.apiVersion != 3 || manifest.kind !in setOf("configuration", "extension")) invalid("金智或乘方继承需要 API 3")
        val config = manifest.json.optJSONObject("builtinConfig") ?: invalid("必须声明本校内置协议配置")
        val school = SchoolConfig.fromJson(manifest.school)
        val base = https(school.fullBasePath).trimEnd('/')
        return JSONObject().put("baseUrl", base).apply {
            if (manifest.baseProvider == "builtin.jinzhi") {
                if (config.keys().asSequence().toSet() != setOf("casBaseUrl", "periods")) invalid("金智需要本校 CAS 和作息")
                put("casBaseUrl", https(config.getString("casBaseUrl")).trimEnd('/'))
                val periods = config.optJSONArray("periods") ?: invalid("缺少本校作息")
                val values = PluginJson.objects(periods)
                if (values.isEmpty() || values.size > 30 || values.map { it.optInt("number") }.toSet().size != values.size) invalid("节次数量或编号无效")
                for (p in values) {
                    val time = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
                    if (p.optInt("number") !in 1..30 || !time.matches(p.optString("start")) || !time.matches(p.optString("end")) || p.getString("start") >= p.getString("end")) invalid("作息时间无效")
                }
                put("periods", periods)
            } else {
                if (config.keys().asSequence().toSet() != setOf("loginUrl")) invalid("乘方需要本校统一认证入口")
                put("loginUrl", https(config.getString("loginUrl"), query = true))
            }
        }
    }

    fun inherit(parent: PluginPackage, base: PluginPackage, school: SchoolConfig): PluginPackage {
        val config = configuration(parent.manifest) ?: invalid("没有可继承的内置协议")
        val declared = PluginSchoolMatcher.endpoint(SchoolConfig.fromJson(parent.manifest.school))
        val actual = PluginSchoolMatcher.endpoint(school)
        if (declared == null || actual == null || declared.scheme != actual.scheme || declared.host != actual.host ||
            declared.port != actual.port || declared.encodedPath.trimEnd('/') != actual.encodedPath.trimEnd('/')) invalid("插件继承配置与当前学校不一致")
        require(base.bundled && base.manifest.id == providers[parent.manifest.baseProvider])
        val source = "globalThis.__builtinAcademicConfig=" + PluginJson.canonical(config) + ";\n" + base.source
        val manifest = JSONObject(base.manifest.json.toString()).put("id", parent.manifest.id).put("version", parent.manifest.version)
            .put("school", parent.manifest.school).put("network", parent.manifest.json.getJSONArray("network"))
            .put("files", JSONObject().put("index.js", PluginJson.sha256(source.toByteArray())))
        // Preserve the parent's authority and development namespace. This is not a signed package import.
        return PluginPackage(PluginManifest(manifest), source, PluginJson.sha256((manifest.toString() + source).toByteArray()), parent.official, parent.bundled)
    }
}
