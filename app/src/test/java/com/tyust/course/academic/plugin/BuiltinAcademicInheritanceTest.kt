package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSessionStore
import com.tyust.course.model.SchoolConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BuiltinAcademicInheritanceTest {
    private val assets = File("src/main/assets")
    private val schema = PluginSchema(JSONObject(File(assets, "academic-plugin/manifest.schema.json").readText()))
    private val bases = BundledAcademicProviders.load { File(assets, it).readBytes() }
    private fun parent(type: String): PluginPackage {
        val config = if (type == "jinzhi") JSONObject().put("casBaseUrl", "https://auth.example.edu.cn/authserver")
            .put("periods", JSONArray().put(JSONObject().put("number", 1).put("start", "08:00").put("end", "08:45")))
        else JSONObject().put("loginUrl", "https://auth.example.edu.cn/authserver/login?service=https%3A%2F%2Fjw.example.edu.cn%2Fnew%2FssoLogin")
        val school = JSONObject().put("id", "example.$type").put("name", "合成学校").put("domain", "jw.example.edu.cn")
            .put("protocol", "https").put("basePath", "/").put("academicSystem", type)
        val manifest = JSONObject().put("id", "example.$type").put("version", "1.0.0").put("name", "合成适配")
            .put("kind", "configuration").put("apiVersion", 3).put("extends", "builtin.$type").put("builtinConfig", config)
            .put("school", school).put("network", JSONArray()).put("capabilities", JSONArray()).put("files", JSONObject())
        return PluginPackage(PluginManifest(manifest), "", "synthetic", false)
    }

    @Test fun completeSchemasRejectMissingConfigurationLegacyApisAndInvalidPeriods() {
        for (type in listOf("jinzhi", "chengfang")) {
            parent(type).manifest.validate(schema)
            val missing = parent(type).manifest; missing.json.remove("builtinConfig")
            reject { missing.validate(schema) }
            val legacy = parent(type).manifest; legacy.json.put("apiVersion", 2)
            reject { legacy.validate(schema) }
        }
        val wrong = parent("jinzhi").manifest
        wrong.json.getJSONObject("builtinConfig").getJSONArray("periods").getJSONObject(0).put("end", "07:00")
        reject { wrong.validate(schema) }
    }

    @Test fun inheritedProgramsHaveParentsNetworkIdentityAndTrustWithoutReferenceDefaults() {
        for (type in listOf("jinzhi", "chengfang")) {
            val parent = parent(type)
            val base = bases.getValue(BuiltinAcademicInheritance.providers.getValue("builtin.$type"))
            val derived = BuiltinAcademicInheritance.inherit(parent, base, SchoolConfig.fromJson(parent.manifest.school))
            assertEquals(parent.manifest.id, derived.manifest.id)
            assertEquals(parent.manifest.network, derived.manifest.network)
            assertFalse(derived.official); assertFalse(derived.bundled)
            assertEquals(base.manifest.capabilities, derived.manifest.capabilities)
            val configuration = derived.source.substringBefore(';').substringAfter('=')
            assertEquals("https://jw.example.edu.cn", JSONObject(configuration).getString("baseUrl"))
            assertFalse(configuration.contains("huat.edu.cn")); assertFalse(configuration.contains("sdipct.edu.cn"))
            reject { BuiltinAcademicInheritance.inherit(parent, base, SchoolConfig.fromJson(parent.manifest.school).apply { domain = "other.school" }) }
        }
    }

    @Test fun authOverrideAndInheritedQueriesShareStateOnlyWithinTheSameAccountAndProvider() {
        val parent = parent("jinzhi")
        val base = BuiltinAcademicInheritance.inherit(parent, bases.getValue("cn.edu.huat.neweas"), SchoolConfig.fromJson(parent.manifest.school))
        val session = AcademicSessionStore().session("school", "account", "https://jw.example.edu.cn")
        fun host(pkg: PluginPackage) = PluginHost(PluginOperation(session, pkg.manifest, "auth.start", development = true), File("build/inheritance-state"))
        host(parent).call("state.set", JSONObject().put("key", "token").put("value", "fictional-token"))
        assertEquals("fictional-token", host(base).call("state.get", JSONObject().put("key", "token")).getString("data"))
        val otherSession = AcademicSessionStore().session("school", "other-account", "https://jw.example.edu.cn")
        val other = PluginHost(PluginOperation(otherSession, base.manifest, "auth.start", development = true), File("build/inheritance-state"))
        assertTrue(other.call("state.get", JSONObject().put("key", "token")).isNull("data"))
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Invalid inherited configuration accepted") }
        catch (e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, e.code) }
    }
}
