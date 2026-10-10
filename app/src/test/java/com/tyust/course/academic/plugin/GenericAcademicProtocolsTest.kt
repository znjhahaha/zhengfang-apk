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
    @Test fun genericProtocolsAndLegacyAliasResolveOnlyTypeScriptWithoutErasingBasePath() {
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
    @Test fun configuredSchoolsUseTheirLoginProfilesAndCookieContextBoundary() {
        for (profile in AcademicSchoolProfiles.profiles) {
            val school = profile.school()
            assertTrue(AcademicGatewayFactory.hasSelectedAdapter(school))
            assertEquals(profile.loginUrl, AcademicGatewayFactory.loginUrl(school))
            AcademicGatewayFactory.importCookie(school, "synthetic-cookie-account", "JSESSIONID=fictional", username = "synthetic")
            val session = AcademicGatewayFactory.sharedSession(school, "synthetic-cookie-account")!!
            assertEquals(1, session.cookies.loadForRequest((school.fullBasePath + "?sfjrxk=1").toHttpUrl()).size)
            assertEquals(1, session.cookies.loadForRequest((school.fullBasePath + "/query").toHttpUrl()).size)
            assertTrue(session.cookies.loadForRequest((school.fullBasePath + "-other/query").toHttpUrl()).isEmpty())
            assertTrue(session.cookies.loadForRequest("https://other.example.test/".toHttpUrl()).isEmpty())
        }
    }
    @Test fun newInheritanceOptionsRemainBoundToSchoolAndAuthenticationPurpose() {
        for (type in listOf("eams", "chaoxing_academic")) {
            val school = school(type)
            val config = JSONObject().put("protocol", type).put("loginUrl", "https://auth.example.test/sso/login")
            val parent = PluginPackage(PluginManifest(JSONObject().put("id", "synthetic.$type").put("name", "合成")
                .put("version", "1.0.0").put("apiVersion", 3).put("kind", "configuration").put("extends", "builtin.$type")
                .put("minAppVersionCode", 113).put("builtinConfig", config).put("school", school.toJson())
                .put("capabilities", org.json.JSONArray()).put("network", org.json.JSONArray()).put("files", JSONObject())), "", "synthetic-$type", false)
            val base = AcademicProviderRegistry.knownPackage(GenericAcademicProtocols.providers.getValue(type))!!
            val inherited = BuiltinAcademicInheritance.inherit(parent, base, school)
            val policy = PluginNetworkPolicy(inherited.manifest.network)
            policy.requireAllowed("https://auth.example.test/sso/login".toHttpUrl(), "GET", "auth", null)
            assertThrows(PluginException::class.java) { policy.requireAllowed("https://auth.example.test/sso/data".toHttpUrl(), "POST", "mutation", null) }
            assertEquals("/custom", school.basePath)
            assertTrue(inherited.source.contains("https://school.example.test/custom"))
            assertThrows(PluginException::class.java) { BuiltinAcademicInheritance.inherit(parent, base,
                SchoolConfig.fromJson(school.toJson()).apply { domain = "outside.example.test" }) }
            parent.manifest.json.put("minAppVersionCode", 112)
            assertThrows(PluginException::class.java) { BuiltinAcademicInheritance.configuration(parent.manifest) }
            parent.manifest.json.put("minAppVersionCode", 113).put("apiVersion", 2)
            assertThrows(PluginException::class.java) { BuiltinAcademicInheritance.configuration(parent.manifest) }
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
        // Populate the material cache before revoking the provider.
        assertNotNull(AcademicProviderRegistry.operationProvider(school, "study.schedule"))
        AcademicProviderRegistry.setEnabled("org.zf.protocol.zf", false)
        try {
            assertTrue(AcademicGatewayFactory.supports(school))
            assertFalse(AcademicProviderRegistry.hasCapability(school, "study.schedule"))
            try { AcademicGatewayFactory.create(school, "account"); fail() } catch (e: AcademicException) { assertEquals(AcademicStatus.UNSUPPORTED, e.status) }
        } finally { AcademicProviderRegistry.setEnabled("org.zf.protocol.zf", true) }
    }
    @Test fun repeatedBindingReusesScriptButNeverSharesMutableManifest() {
        val base = AcademicProviderRegistry.knownPackage("org.zf.protocol.zf")!!
        val school = school("zf")
        val first = GenericAcademicProtocols.bind(base, school)
        val originalDigest = first.digest
        first.manifest.json.put("id", "synthetic.mutation")
        first.manifest.school.put("domain", "other.example.test")
        val next = GenericAcademicProtocols.bind(base, SchoolConfig.fromJson(school.toJson()))
        assertSame(first.source, next.source)
        assertEquals(originalDigest, next.digest)
        assertEquals(base.manifest.id, next.manifest.id)
        assertEquals(school.domain, next.manifest.school.getString("domain"))
    }
    @Test fun changedSchoolScopeOrPackageNeverReusesOldExecutionMaterial() {
        val base = AcademicProviderRegistry.knownPackage("org.zf.protocol.zf")!!
        val school = school("zf")
        val first = GenericAcademicProtocols.bind(base, school)
        school.domain = "second.example.test"
        val second = GenericAcademicProtocols.bind(base, school)
        assertNotEquals(first.digest, second.digest)
        assertEquals("school.example.test", first.manifest.school.getString("domain"))
        assertTrue(second.source.contains("second.example.test"))
        val updated = GenericAcademicProtocols.bind(base.copy(source = base.source + "\n// update", digest = "new-synthetic-package"), school)
        assertNotEquals(second.digest, updated.digest)
        assertTrue(updated.source.endsWith("// update"))
    }
    @Test fun bindingCacheNeverConfusesPublisherTrust() {
        val base = AcademicProviderRegistry.knownPackage("org.zf.protocol.zf")!!
        val first = GenericAcademicProtocols.bind(base, school("zf"))
        val next = GenericAcademicProtocols.bind(base.copy(official = true, bundled = false, publisher = "synthetic-publisher"), school("zf"))
        assertEquals(first.digest, next.digest)
        assertTrue(next.official)
        assertFalse(next.bundled)
        assertEquals("synthetic-publisher", next.publisher)
    }
}
