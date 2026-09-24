package com.tyust.course.academic.plugin

import com.tyust.course.academic.*
import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BundledAcademicProvidersTest {
    private val assets = File("src/main/assets")
    private fun school(host: String, type: String = "auto") = SchoolConfig("test", "Test", host, "https").apply { academicSystem = type }

    @Test fun packagesAreCompleteValidatedProgramsBundledWithApp() {
        val packages = BundledAcademicProviders.load { File(assets, it).readBytes() }
        assertEquals(2, packages.size)
        for ((_, pkg) in packages) {
            assertTrue(pkg.bundled)
            assertFalse(pkg.official) // Bundling does not forge a catalog signature.
            assertTrue(pkg.source.contains("globalThis.plugin"))
            assertTrue(pkg.manifest.capabilities.containsAll(listOf("auth.start", "auth.resume", "auth.validate", "study.terms",
                "study.schedule", "study.calendar", "study.grades", "study.exams", "selection.catalog", "selection.enrolled")))
        }
    }

    @Test fun publicSsoAndAcademicAddressesDetectToCorrectBackendWithoutNetwork() = runBlocking {
        for (definition in BundledAcademicProviders.definitions) {
            for (input in listOf("https://${definition.host}/", definition.loginUrl)) {
                val result = AcademicDetection.detect(input)
                assertEquals(AcademicDetectionStatus.SUCCESS, result.status)
                assertEquals(definition.system, result.system)
                assertEquals(AcademicAddress("https", definition.host, ""), result.address)
            }
        }
        assertNull(BundledAcademicProviders.detect("https://cas.other.edu.cn/authserver/login"))
        assertNull(BundledAcademicProviders.detect("https://neweas.huat.edu.cn.evil.test/"))
        assertNull(BundledAcademicProviders.detect("https://jwxt.sdipct.edu.cn:8443/"))
        assertNull(BundledAcademicProviders.detect("https://user@jwxt.sdipct.edu.cn/"))
        assertNull(BundledAcademicProviders.detect("https://cas.huat.edu.cn/other/login"))
        assertNull(SystemDetector.classify("<title>金智统一身份认证</title><form id='pwdFromId'><input id='pwdEncryptSalt'></form>"))
    }

    @Test fun typeSelectionCannotSendAnotherSchoolsCredentialsToBundledHost() {
        for (definition in BundledAcademicProviders.definitions) {
            assertEquals(definition, BundledAcademicProviders.matching(school(definition.host)))
            assertEquals(definition, BundledAcademicProviders.matching(school(definition.host, definition.system.id)))
            assertNull(BundledAcademicProviders.matching(school("another.school", definition.system.id)))
            assertNull(BundledAcademicProviders.matching(school(definition.host, "zf")))
            assertNull(BundledAcademicProviders.matching(school(definition.host).apply { protocol = "http" }))
        }
    }

    @Test fun modifiedAssetsAndPathsAreRejectedWithoutWeakeningExternalVerification() {
        val index = JSONObject(File(assets, "bundled-academic/index.json").readText())
        index.getJSONArray("entries").getJSONObject(0).put("asset", "../../outside.eduplugin")
        try {
            BundledAcademicProviders.load { if (it.endsWith("index.json")) index.toString().toByteArray() else File(assets, it).readBytes() }
            fail("Invalid asset path accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            BundledAcademicProviders.load { if (it.endsWith(".eduplugin")) byteArrayOf(0) else File(assets, it).readBytes() }
            fail("Modified package accepted")
        } catch (_: IllegalArgumentException) { }
        val bytes = File(assets, "bundled-academic/jinzhi.eduplugin").readBytes()
        val schema = PluginSchema(JSONObject(File(assets, "academic-plugin/manifest.schema.json").readText()))
        try { PluginPackageVerifier.read(bytes, schema, emptyMap(), false); fail("External unsigned import accepted") }
        catch (e: PluginException) { assertEquals(PluginErrorCode.BAD_SIGNATURE, e.code) }
    }
}
