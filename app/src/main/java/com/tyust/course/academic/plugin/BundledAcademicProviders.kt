package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicAddress
import com.tyust.course.academic.AcademicSystem
import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** These programs ship inside the APK. This loader never accepts an external package or path. */
object BundledAcademicProviders {
    data class Definition(val id: String, val system: AcademicSystem, val host: String, val authHost: String, val loginUrl: String)
    val definitions = listOf(
        Definition("cn.edu.huat.neweas", AcademicSystem.JINZHI, "neweas.huat.edu.cn", "cas.huat.edu.cn", "https://cas.huat.edu.cn/authserver/login"),
        Definition("cn.edu.sdipct.chengfang", AcademicSystem.CHENGFANG, "jwxt.sdipct.edu.cn", "authserver.sdipct.edu.cn",
            "https://authserver.sdipct.edu.cn/authserver/login?service=https%3A%2F%2Fjwxt.sdipct.edu.cn%2Fnew%2FssoLogin")
    )

    fun detect(input: String): Definition? {
        val url = input.trim().let { if (it.contains("://")) it else "https://$it" }.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != if (url.isHttps) 443 else 80) return null
        return definitions.firstOrNull { definition ->
            url.host == definition.host || url.host == definition.authHost &&
                (url.encodedPath == "/authserver" || url.encodedPath.startsWith("/authserver/"))
        }
    }

    fun address(definition: Definition) = AcademicAddress("https", definition.host, "")

    fun matching(school: SchoolConfig, choice: String = school.academicSystem): Definition? {
        val url = PluginSchoolMatcher.endpoint(school) ?: return null
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return definitions.firstOrNull { url.host == it.host && choice in setOf("auto", it.system.id) }
    }

    internal fun load(readAsset: (String) -> ByteArray): Map<String, PluginPackage> {
        val index = JSONObject(readAsset("bundled-academic/index.json").toString(Charsets.UTF_8))
        require(index.getInt("format") == 1)
        val entries = PluginJson.objects(index.getJSONArray("entries"))
        require(entries.size == definitions.size && entries.map { it.getString("id") }.toSet() == definitions.map { it.id }.toSet())
        val schema = PluginSchema(JSONObject(readAsset("academic-plugin/manifest.schema.json").toString(Charsets.UTF_8)))
        return definitions.associate { definition ->
            val entry = entries.single { it.getString("id") == definition.id }
            val asset = "${definition.system.id}.eduplugin"
            require(entry.getString("asset") == asset && entry.getString("type") == definition.system.id)
            val bytes = readAsset("bundled-academic/$asset")
            require(PluginJson.sha256(bytes) == entry.getString("sha256")) { "Builtin academic package digest mismatch" }
            val pkg = PluginPackageVerifier.read(bytes, schema, emptyMap(), allowDevelopment = true)
            require(pkg.manifest.id == definition.id && pkg.manifest.kind == "independent" &&
                pkg.manifest.school.getString("academicSystem") == definition.system.id &&
                pkg.manifest.school.getString("domain") == definition.host)
            definition.id to pkg.copy(bundled = true)
        }
    }
}
