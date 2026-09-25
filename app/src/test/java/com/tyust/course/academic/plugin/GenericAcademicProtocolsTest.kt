package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.academic.*
import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class GenericAcademicProtocolsTest {
    @Before fun initialize() { AcademicProviderRegistry.initialize(RuntimeEnvironment.getApplication()) }
    private fun school(system: String) = SchoolConfig("synthetic-$system", "合成学校", "school.example.test", "https").apply { academicSystem = system; basePath = "/custom"; courseGnmkdm = "N999999" }
    @Test fun fourProtocolsAndLegacyAliasResolveOnlyTypeScriptWithoutErasingBasePath() {
        for ((system, id) in GenericAcademicProtocols.providers) {
            val school = school(system)
            assertTrue(AcademicGatewayFactory.supports(school))
            val adapter = AcademicGatewayFactory.create(school, "account") as PluginAcademicAdapter
            assertEquals(id, adapter.pinned.manifest.id)
            assertEquals("/custom", school.basePath)
            assertTrue(adapter.pinned.source.contains("globalThis.__builtinAcademicConfig="))
            val config = JSONObject(adapter.pinned.source.substringBefore(';').substringAfter('='))
            assertEquals("N999999", config.getString("courseGnmkdm"))
            assertTrue(AcademicGatewayFactory.createStudy(school, "account") is PluginAcademicAdapter)
            assertEquals(adapter.effectiveCapabilities, adapter.rebind(AcademicSessionStore().session(school.id, "other", school.fullBasePath)).effectiveCapabilities)
        }
    }
    @Test fun inheritedProtocolsKeepOwnAccountAndHostAuthority() {
        val school = school("zf")
        val base = AcademicProviderRegistry.knownPackage("org.zf.protocol.zf")!!
        val parent = PluginPackage(PluginManifest(JSONObject("""{"id":"synthetic.extension","name":"合成","version":"1.0.0","apiVersion":1,"kind":"extension","extends":"builtin.zf","entry":"index.js","capabilities":["study.schedule"],"network":[],"files":{}}""").put("school", school.toJson())), "", "parent", false)
        val derived = BuiltinAcademicInheritance.inherit(parent, base, school)
        assertEquals(parent.manifest.id, derived.manifest.id); assertFalse(derived.bundled); assertFalse(derived.official)
        val policy = PluginNetworkPolicy(derived.manifest.network)
        policy.requireAllowed("https://school.example.test/custom/xtgl/login_slogin.html".toHttpUrl(), "POST", "auth", null)
        try { policy.requireAllowed("https://other.example.test/custom/login".toHttpUrl(), "POST", "auth", null); fail() } catch (e: PluginException) { assertEquals(PluginErrorCode.UNTRUSTED_URL, e.code) }
    }
    @Test fun disablingGenericProtocolCannotReactivateLegacyKotlinFallback() {
        val school = school("legacy_zf")
        AcademicProviderRegistry.setEnabled("org.zf.protocol.zf", false)
        try {
            assertTrue(AcademicGatewayFactory.supports(school))
            assertFalse(AcademicProviderRegistry.hasCapability(school, "study.schedule"))
            try { AcademicGatewayFactory.create(school, "account"); fail() } catch (e: AcademicException) { assertEquals(AcademicStatus.UNSUPPORTED, e.status) }
        } finally { AcademicProviderRegistry.setEnabled("org.zf.protocol.zf", true) }
    }
}
