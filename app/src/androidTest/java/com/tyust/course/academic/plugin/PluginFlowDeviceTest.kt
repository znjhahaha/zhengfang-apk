package com.tyust.course.academic.plugin

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyust.course.academic.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PluginFlowDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("academic-plugin/$name").use { it.readBytes() }
    @Test fun threeTemplatesImportAndStandaloneQueriesUseOpaqueTermsAndPagination() = runBlocking {
        val store = AcademicProviderRegistry.packages()
        for (name in listOf("config", "extend", "mock")) {
            store.deactivate("local.test-$name")
            val installed = store.install(asset("local.test-$name-1.0.0.zfplugin"), true)
            assertEquals("local.test-$name", installed.manifest.id)
        }
        AcademicProviderRegistry.reload()
        val pkg = store.active("local.test-mock")!!
        val school = AcademicProviderRegistry.school(pkg)
        val adapter = AcademicGatewayFactory.create(school, "dev:flow") as PluginAcademicAdapter
        assertEquals(AcademicStatus.SUCCESS, adapter.login(Credentials("demo", "demo")).status)
        val catalog = adapter.catalog()
        assertEquals("autumn:2026", catalog.currentTerm.id)
        assertEquals("spring:2027", catalog.currentTerm.next().id)
        assertEquals(1, adapter.schedule(catalog.currentTerm).size)
        assertEquals(2, adapter.exams(catalog.currentTerm).size)
        val grade = adapter.grades().grades.single()
        assertTrue(adapter.gradeDetails(grade).contains("期末"))
        assertEquals(2, adapter.calendar(catalog.currentTerm)!!.getJSONArray("periods").length())
        val context = adapter.loadCourseContext()
        val course = adapter.listCourses(context, CourseQuery()).single()
        val section = adapter.listSections(course).single()
        try { adapter.select(SelectionTarget(course, section)); fail("unconfirmed write") }
        catch (e: AcademicException) { assertEquals(AcademicStatus.VALIDATION_FAILED, e.status) }
        assertEquals(AcademicStatus.SUCCESS, adapter.select(SelectionTarget(course, section, true)).status)
        val enrolled = adapter.selected(context).single()
        assertEquals(section.stableId, enrolled.sectionId)
        assertEquals(AcademicStatus.SUCCESS, adapter.drop(SelectionTarget(course.copy(raw = enrolled.raw), section, true)).status)
        assertTrue(adapter.selected(context).isEmpty())
        val other = AcademicGatewayFactory.create(school, "dev:other") as PluginAcademicAdapter
        try { other.validateSession(); fail("account state leaked") }
        catch (e: AcademicException) { assertEquals(AcademicStatus.SESSION_EXPIRED, e.status) }
        AcademicGatewayFactory.invalidate(school, "dev:flow")
        try { adapter.catalog(); fail("retired session accepted") } catch (_: kotlinx.coroutines.CancellationException) { }
    }
    @Test fun nodeSignedOfflinePackageIsVerifiedByAndroid() = runBlocking {
        val key = JSONObject(asset("test-public-key.json").toString(Charsets.UTF_8))
        val isolatedFiles = object : ContextWrapper(app) {
            override fun getFilesDir() = File(app.filesDir, "signature-test").apply { mkdirs() }
        }
        val store = PluginPackageStore(isolatedFiles, mapOf(key.getString("keyId") to PluginPackageVerifier.publicKey(key.getString("spki"))))
        val pkg = store.install(asset("local.test-mock-1.0.0-signed.zfplugin"))
        assertTrue(pkg.official)
        assertEquals("local.test-mock", pkg.manifest.id)
        val original = pkg.digest
        try { store.install(asset("local.test-mock-1.0.0.zfplugin")); fail("unsigned official update") }
        catch (e: PluginException) { assertEquals(PluginErrorCode.BAD_SIGNATURE, e.code) }
        assertEquals(original, store.active(pkg.manifest.id)!!.digest)
    }
    @Test fun captchaAndWebContinuationKeepTheLoginVersionAcrossUpdate() = runBlocking {
        val store = AcademicProviderRegistry.packages()
        store.deactivate("local.test-mock")
        val initial = store.install(asset("local.test-mock-1.0.0.zfplugin"), true)
        AcademicProviderRegistry.reload()
        val school = AcademicProviderRegistry.school(initial)
        val captcha = AcademicGatewayFactory.create(school, "dev:captcha") as PluginAcademicAdapter
        assertEquals(AcademicStatus.CAPTCHA_REQUIRED, captcha.login(Credentials("captcha", "demo")).status)
        assertTrue(captcha.refreshCaptcha()!!.image.isNotEmpty())
        assertEquals(AcademicStatus.CAPTCHA_REQUIRED, captcha.submitCaptcha("wrong").status)
        assertEquals(AcademicStatus.SUCCESS, captcha.submitCaptcha("1234").status)

        val gateway = AcademicPasswordLoginGateway(school)
        val web = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.login(school, "web", "demo", object : com.tyust.course.login.PasswordLoginCallback {
            override fun onSuccess(cookie: String) { web.completeExceptionally(AssertionError("expected web handoff")) }
            override fun onCaptchaRequired(imageBytes: ByteArray) { onError("unexpected captcha") }
            override fun onCaptchaInvalid() { onError("unexpected captcha") }
            override fun onInvalidCredentials() { onError("unexpected credentials error") }
            override fun onError(message: String) { web.completeExceptionally(AssertionError(message)) }
            override fun onWebLoginRequired(message: String) { web.complete(Unit) }
        })
        try {
            kotlinx.coroutines.withTimeout(10_000) { web.await() }
            val manifest = JSONObject(initial.manifest.json.toString()).put("version", "1.0.1")
            val updated = java.io.ByteArrayOutputStream().also { out -> java.util.zip.ZipOutputStream(out).use { zip ->
                for ((name, bytes) in mapOf("manifest.json" to manifest.toString().toByteArray(), "index.js" to initial.source.toByteArray())) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            } }.toByteArray()
            store.install(updated, true)
            AcademicProviderRegistry.reload()
            assertEquals("1.0.1", AcademicProviderRegistry.resolve(school)!!.manifest.version)
            try { gateway.resumeWebLogin("", "https://school.example/outside"); fail("wrong completion accepted") }
            catch (e: AcademicException) { assertEquals(AcademicStatus.UNTRUSTED_URL, e.status) }
            assertEquals(AcademicStatus.SUCCESS, gateway.resumeWebLogin("", "https://school.example/done").status)
            assertEquals("1.0.0", gateway.completedPlugin!!.version)
            assertEquals(AcademicStatus.SUCCESS, gateway.completedPlugin!!.validateSession().status)
        } finally {
            gateway.clearSensitiveState()
            store.install(asset("local.test-mock-1.0.0.zfplugin"), true)
            AcademicProviderRegistry.reload()
        }
    }
}
