package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.academic.*
import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class BundledAcademicRegistryTest {
    @Before fun initialize() { AcademicProviderRegistry.initialize(RuntimeEnvironment.getApplication()) }
    private fun school(definition: BundledAcademicProviders.Definition) = SchoolConfig("builtin-${definition.system.id}", "Test", definition.host, "https").apply {
        academicSystem = definition.system.id
    }

    @Test fun freshInstallCreatesBothStudyAndLoginAdaptersWithoutImport() {
        assertTrue(AcademicProviderRegistry.packages().list().isEmpty())
        for (definition in BundledAcademicProviders.definitions) {
            val school = school(definition)
            assertTrue(AcademicGatewayFactory.hasSelectedAdapter(school))
            assertTrue(AcademicProviderRegistry.overrides(school, "auth.start"))
            assertTrue(AcademicProviderRegistry.hasCapability(school, "study.schedule"))
            val adapter = AcademicGatewayFactory.create(school, "fixture") as PluginAcademicAdapter
            assertEquals(definition.id, adapter.pinned.manifest.id)
            assertTrue(adapter.pinned.bundled)
            assertTrue(AcademicGatewayFactory.createStudy(school, "fixture") is PluginAcademicAdapter)
            assertEquals(definition.system.id, AcademicProviderRegistry.school(adapter.pinned).academicSystem)
            val replacement = AcademicSessionStore().session(school.id, "fixture-restored", school.fullBasePath)
            assertTrue(adapter.rebind(replacement).pinned.bundled)
            assertEquals(definition.loginUrl, AcademicGatewayFactory.loginUrl(school))
        }
    }

    @Test fun autoTypeIsSavedAndCookieImportKeepsTheBundledSessionAtRoot() {
        val definition = BundledAcademicProviders.definitions.last()
        val school = school(definition).apply { academicSystem = "auto"; basePath = "/new/student/xsgrkb" }
        val adapter = AcademicGatewayFactory.create(school, "cookie-fixture") as PluginAcademicAdapter
        assertEquals("chengfang", school.academicSystem)
        assertEquals("", school.basePath)
        AcademicGatewayFactory.importCookie(school, "cookie-fixture", "JSESSIONID=synthetic-cookie", username = "synthetic-student")
        val restored = AcademicGatewayFactory.createStudy(school, "cookie-fixture") as PluginAcademicAdapter
        assertSame(adapter.session, restored.session)
        assertEquals("JSESSIONID=synthetic-cookie", restored.session.cookieHeader())
    }

    @Test fun builtInChoiceAndExplicitProviderRestoreWorkWithoutCatalogInstallation() {
        for (definition in BundledAcademicProviders.definitions) {
            val school = school(definition)
            for (choice in listOf("builtin.auto", "builtin.${definition.system.id}", definition.id)) {
                AcademicProviderRegistry.choose(school, choice)
                assertEquals(definition.id, AcademicProviderRegistry.resolve(school)?.manifest?.id)
                assertTrue(AcademicProviderRegistry.hasBinding(school))
            }
            AcademicProviderRegistry.choose(school, "builtin.zf")
            assertNull(AcademicProviderRegistry.resolve(school))
            assertFalse(AcademicProviderRegistry.hasBinding(school))
            try { AcademicGatewayFactory.create(school, "different-builtin"); fail("Explicit builtin must not be hijacked") }
            catch (e: AcademicException) { assertEquals(AcademicStatus.UNSUPPORTED, e.status) }
        }
    }

    @Test fun unknownSchoolWithSameVendorHasNoBundledBindingOrMisroutedReader() {
        for (definition in BundledAcademicProviders.definitions) {
            val school = school(definition).apply { domain = "another.school" }
            assertFalse(AcademicProviderRegistry.hasBinding(school))
            assertFalse(AcademicGatewayFactory.hasSelectedAdapter(school))
            assertFalse(AcademicProviderRegistry.hasCapability(school, "study.schedule"))
            try { AcademicGatewayFactory.createStudy(school, "fixture"); fail("Unsupported school must not use another vendor reader") }
            catch (e: AcademicException) { assertEquals(AcademicStatus.UNSUPPORTED, e.status) }
        }
    }

    @Test fun installedSchoolConfigurationInheritsCapabilitiesAndPreservesSessionAcrossCookieImportAndRebind() = runBlocking {
        for (type in listOf("jinzhi", "chengfang")) {
            val config = if (type == "jinzhi") JSONObject().put("casBaseUrl", "https://auth.example.edu.cn/authserver")
                .put("periods", JSONArray().put(JSONObject().put("number", 1).put("start", "08:00").put("end", "08:45")))
            else JSONObject().put("loginUrl", "https://auth.example.edu.cn/authserver/login?service=https%3A%2F%2Fjw.example.edu.cn%2Fnew%2FssoLogin")
            val school = SchoolConfig("inherited-$type", "合成学校", "jw.example.edu.cn", "https").apply {
                academicSystem = type
                basePath = ""
            }
            val manifest = JSONObject().put("id", "example.$type").put("name", "合成配置").put("version", "1.0.0")
                .put("apiVersion", 3).put("kind", "configuration").put("extends", "builtin.$type").put("builtinConfig", config)
                .put("school", JSONObject().put("id", school.id).put("name", school.name).put("domain", school.domain)
                    .put("protocol", "https").put("basePath", "").put("academicSystem", type))
                .put("network", JSONArray()).put("capabilities", JSONArray()).put("files", JSONObject())
            val bytes = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
                }
            }.toByteArray()
            val pkg = AcademicProviderRegistry.packages().install(bytes, allowDevelopment = true)
            try {
                AcademicProviderRegistry.reload()
                AcademicProviderRegistry.choose(school, pkg.manifest.id)
                val adapter = AcademicGatewayFactory.create(school, "inherited-account") as PluginAcademicAdapter
                assertFalse(adapter.pinned.bundled)
                assertTrue(adapter.pinned.manifest.capabilities.isEmpty())
                assertTrue(adapter.effectiveCapabilities.containsAll(setOf("auth.start", "auth.resume", "study.terms", "study.schedule", "study.grades", "study.exams")))
                assertTrue(AcademicProviderRegistry.overrides(school, "auth.start"))
                assertEquals(type == "jinzhi", AcademicProviderRegistry.hasCapability(school, "study.gradeDetails"))
                AcademicGatewayFactory.importCookie(school, "inherited-account", "JSESSIONID=synthetic", username = "synthetic-student")
                val study = AcademicGatewayFactory.createStudy(school, "inherited-account") as PluginAcademicAdapter
                assertSame(adapter.session, study.session)
                assertEquals("JSESSIONID=synthetic", study.cookieHeader())
                val replacement = AcademicSessionStore().session(school.id, "replacement", school.fullBasePath)
                val rebound = adapter.rebind(replacement)
                assertSame(replacement, rebound.session)
                assertEquals(adapter.effectiveCapabilities, rebound.effectiveCapabilities)
                assertEquals("", rebound.cookieHeader())
            } finally {
                AcademicProviderRegistry.choose(school, null)
                AcademicProviderRegistry.packages().deactivate(pkg.manifest.id)
                AcademicProviderRegistry.reload()
            }
        }
    }
}
